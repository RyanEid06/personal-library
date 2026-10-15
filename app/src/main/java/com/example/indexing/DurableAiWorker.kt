package com.example.indexing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.util.Base64
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ai.AiGateway
import com.example.ai.AiGatewayException
import com.example.ai.MediaPart
import com.example.data.AnalysisJobEntity
import com.example.data.LibraryRepository
import com.example.data.SearchDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max

class DurableAiWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val itemId = inputData.getString("item_id") ?: return@withContext Result.failure()

        val prefs = applicationContext.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("ai_paused", false)) {
            return@withContext Result.retry()
        }

        val repository = LibraryRepository(applicationContext)
        val initialItem = repository.dao.getItemById(itemId) ?: return@withContext Result.success()

        // If item is trashed, do not process
        if (initialItem.isTrash) return@withContext Result.success()

        // Check exclusions (item or collections)
        val colIds = repository.dao.getCollectionIdsForItem(itemId)
        val excludedCols = prefs.getStringSet("excluded_collections", emptySet()) ?: emptySet()
        val excludedItems = prefs.getStringSet("excluded_items", emptySet()) ?: emptySet()
        if (excludedItems.contains(itemId) || colIds.any { excludedCols.contains(it) }) {
            repository.dao.updateAiStatus(itemId, "EXCLUDED")
            repository.dao.insertOrUpdateAnalysisJob(
                AnalysisJobEntity(
                    itemId = itemId,
                    status = "EXCLUDED",
                    updatedAt = System.currentTimeMillis()
                )
            )
            return@withContext Result.success()
        }

        val expectedVersion = initialItem.localIndexVersion
        val aiGateway = AiGateway(applicationContext)

        try {
            if (!aiGateway.identityManager.isPaired) {
                repository.dao.updateAiStatus(itemId, "NEEDS_RETRY")
                repository.dao.insertOrUpdateAnalysisJob(
                    AnalysisJobEntity(
                        itemId = itemId,
                        status = "NEEDS_RETRY",
                        lastErrorClass = "UNPAIRED",
                        lastErrorTime = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
                return@withContext Result.failure()
            }

            // Update status to ANALYZING and record job progress
            repository.dao.updateAiStatus(itemId, "ANALYZING")
            repository.dao.insertOrUpdateAnalysisJob(
                AnalysisJobEntity(
                    itemId = itemId,
                    expectedContentVersion = expectedVersion,
                    indexVersion = expectedVersion,
                    status = "PROCESSING",
                    attempts = runAttemptCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )

            // Extract bounded media parts if applicable
            val mediaParts = mutableListOf<MediaPart>()
            val blobFile = initialItem.contentHash?.let { repository.getBlobFile(it) }

            if (blobFile != null && blobFile.exists()) {
                val mime = (initialItem.mimeType ?: "").lowercase()
                try {
                    when {
                        mime.startsWith("image/") -> {
                            val part = extractBoundedImagePart(blobFile)
                            if (part != null) mediaParts.add(part)
                        }
                        mime.startsWith("video/") -> {
                            mediaParts.addAll(extractBoundedVideoFrames(blobFile))
                        }
                    }
                } catch (_: Exception) {
                    // Media bounding fallback: proceed without media part if corrupted
                }
            }

            val textForAnalysis = buildString {
                if (initialItem.content.isNotBlank()) append(initialItem.content)
                if (!initialItem.extractedText.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(initialItem.extractedText)
                }
            }

            val analysis = aiGateway.analyze(
                itemId = initialItem.id,
                kind = initialItem.kind,
                textContent = textForAnalysis.ifBlank { null },
                originalFileName = initialItem.originalFileName,
                mediaParts = mediaParts
            )

            // --- STALE AI RESULT PROTECTION ---
            // Re-read item from database to ensure content has not changed while network call was running
            val currentItem = repository.dao.getItemById(itemId)
            if (currentItem == null || currentItem.isTrash || currentItem.localIndexVersion != expectedVersion) {
                // Item was edited, deleted, or trashed while AI was in-flight: discard stale results
                return@withContext Result.success()
            }

            // Targeted update of AI-only fields
            repository.dao.updateAiMetadata(
                id = currentItem.id,
                aiDescription = analysis.description,
                aiConcepts = analysis.concepts,
                aiStatus = "READY"
            )

            // --- CHUNKED SEMANTIC EMBEDDINGS ---
            val chunks = createChunksForItem(currentItem, analysis.description, analysis.concepts)
            if (chunks.isNotEmpty()) {
                // Embed in batches of at most 32 chunks
                val allEmbeddedDocs = mutableListOf<SearchDocumentEntity>()
                val batchSize = 32
                for (chunkBatch in chunks.chunked(batchSize)) {
                    val textsToEmbed = chunkBatch.map { it.text }
                    val vectors = aiGateway.embed(textsToEmbed)
                    for (i in chunkBatch.indices) {
                        val chunkInfo = chunkBatch[i]
                        val vec = vectors.getOrNull(i)
                        if (vec != null && vec.size == 768) {
                            allEmbeddedDocs.add(
                                SearchDocumentEntity(
                                    id = "doc_${currentItem.id}_${expectedVersion}_${chunkInfo.index}",
                                    itemId = currentItem.id,
                                    chunkIndex = chunkInfo.index,
                                    pageNumber = chunkInfo.pageNumber,
                                    textContent = chunkInfo.text,
                                    embedding = vec.joinToString(","),
                                    embeddingDimension = 768,
                                    modelVersion = "gemini-embedding-2",
                                    coverage = analysis.coverage
                                )
                            )
                        }
                    }
                }

                // Replace old search documents safely
                repository.dao.deleteSearchDocumentsForItem(currentItem.id)
                if (allEmbeddedDocs.isNotEmpty()) {
                    repository.dao.insertSearchDocuments(allEmbeddedDocs)
                }
            }

            repository.dao.insertOrUpdateAnalysisJob(
                AnalysisJobEntity(
                    itemId = itemId,
                    expectedContentVersion = expectedVersion,
                    indexVersion = expectedVersion,
                    status = "COMPLETED",
                    attempts = runAttemptCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )

            Result.success()
        } catch (e: Exception) {
            val isPermanent = e is AiGatewayException.UnconfiguredException ||
                e is AiGatewayException.UnpairedException

            val status = if (isPermanent || runAttemptCount >= 3) "NEEDS_RETRY" else "QUEUED"
            repository.dao.updateAiStatus(itemId, status)
            repository.dao.insertOrUpdateAnalysisJob(
                AnalysisJobEntity(
                    itemId = itemId,
                    expectedContentVersion = expectedVersion,
                    indexVersion = expectedVersion,
                    status = status,
                    attempts = runAttemptCount + 1,
                    lastErrorClass = e::class.java.simpleName,
                    lastErrorTime = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            if (!isPermanent && runAttemptCount < 3) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    private data class ChunkInfo(val index: Int, val text: String, val pageNumber: Int? = null)

    private fun createChunksForItem(item: com.example.data.ItemEntity, aiDesc: String?, aiConcepts: String?): List<ChunkInfo> {
        val chunks = mutableListOf<ChunkInfo>()
        var chunkIndex = 0

        // Primary summary chunk
        val headerSummary = buildString {
            append(item.title).append(". ")
            if (!aiDesc.isNullOrBlank()) append(aiDesc).append(". ")
            if (!aiConcepts.isNullOrBlank()) append(aiConcepts)
        }.trim()
        if (headerSummary.isNotBlank()) {
            chunks.add(ChunkInfo(chunkIndex++, headerSummary.take(1000)))
        }

        // Chunk owner content (notes, text, links)
        if (item.content.isNotBlank()) {
            val contentChunks = splitTextIntoBoundedChunks(item.content, 600)
            for (c in contentChunks) {
                chunks.add(ChunkInfo(chunkIndex++, c))
            }
        }

        // Chunk extracted text (OCR or PDF)
        if (!item.extractedText.isNullOrBlank()) {
            val extractedChunks = splitTextIntoBoundedChunks(item.extractedText, 600)
            for (c in extractedChunks.take(15)) { // Bounded max chunks per item
                chunks.add(ChunkInfo(chunkIndex++, c))
            }
        }

        return chunks
    }

    private fun splitTextIntoBoundedChunks(text: String, targetSize: Int): List<String> {
        val result = mutableListOf<String>()
        val paragraphs = text.split("\n\n").filter { it.isNotBlank() }

        var current = StringBuilder()
        for (para in paragraphs) {
            if (current.length + para.length > targetSize && current.isNotEmpty()) {
                result.add(current.toString().trim())
                current = StringBuilder()
            }
            if (para.length > targetSize) {
                // Split large paragraph by sentence or size
                var start = 0
                while (start < para.length) {
                    val end = minOf(start + targetSize, para.length)
                    result.add(para.substring(start, end).trim())
                    start = end
                }
            } else {
                if (current.isNotEmpty()) current.append("\n\n")
                current.append(para)
            }
        }
        if (current.isNotEmpty()) {
            result.add(current.toString().trim())
        }
        return result
    }

    private fun extractBoundedImagePart(file: File): MediaPart? {
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, boundsOpts)
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return null

        val longest = max(origW, origH)
        var sampleSize = 1
        while ((longest / sampleSize) > 1600) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOpts) ?: return null

        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
        bitmap.recycle()
        val base64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
        return MediaPart(mimeType = "image/jpeg", dataBase64 = base64)
    }

    private fun extractBoundedVideoFrames(file: File): List<MediaPart> {
        val parts = mutableListOf<MediaPart>()
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val durationMs = durationStr?.toLongOrNull() ?: 0L

            val frameTimes = if (durationMs > 0) {
                val step = durationMs / 4
                listOf(0L, step, step * 2, step * 3)
            } else {
                listOf(0L)
            }

            for (timeMs in frameTimes.take(4)) {
                val frame = retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null) {
                    val scale = 640.0 / max(frame.width, frame.height).coerceAtLeast(1)
                    val scaled = if (scale < 1.0) {
                        Bitmap.createScaledBitmap(frame, (frame.width * scale).toInt(), (frame.height * scale).toInt(), true)
                    } else frame

                    val baos = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    if (scaled != frame) scaled.recycle()
                    frame.recycle()

                    parts.add(MediaPart("image/jpeg", Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)))
                }
            }
        } catch (_: Exception) {
        } finally {
            try { retriever.release() } catch (_: Exception) {}
        }
        return parts
    }
}
