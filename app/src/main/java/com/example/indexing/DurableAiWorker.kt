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
import com.example.ai.AiNetworkPolicy
import com.example.ai.MediaPart
import com.example.ai.AnalyzeResponse
import com.personallibrary.core.ExtractedPage
import com.personallibrary.core.PdfAnalysisBounds
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
        val repository = LibraryRepository(applicationContext)
        repository.completeOwnerFeedbackMigration()
        val initialItem = repository.dao.getItemById(itemId) ?: return@withContext Result.success()
        val expectedVersion = initialItem.localIndexVersion
        val requestedVersion = inputData.getInt("content_version", expectedVersion)
        if (requestedVersion != expectedVersion) return@withContext Result.success()
        val previousJob = repository.dao.getAnalysisJob(itemId)
        val metadataOnly = previousJob?.takeIf { it.expectedContentVersion == expectedVersion }?.metadataOnly == true
        val isPdf = initialItem.mimeType == "application/pdf" || initialItem.originalFileName?.endsWith(".pdf", true) == true
        if (!metadataOnly && isPdf && !initialItem.extractedText.isNullOrBlank() && repository.dao.getSearchDocumentsForItem(itemId).none { it.pageNumber != null }) {
            // Upgrade flattened legacy extraction locally before any bounded page upload can begin.
            LocalIndexer(applicationContext, repository).indexItem(initialItem)
            return@withContext Result.success()
        }
        val attempts = previousJob?.takeIf { it.expectedContentVersion == expectedVersion }?.attempts ?: 0
        // Persist the ceiling across WorkManager recovery and process restarts.
        if (attempts >= 4) {
            repository.updateAnalysisJobIfCurrent(AnalysisJobEntity(itemId, expectedVersion, expectedVersion, status = "NEEDS_RETRY", attempts = attempts, metadataOnly = metadataOnly))
            return@withContext Result.failure()
        }
        val attempt = attempts + 1

        // If item is trashed, do not process
        if (initialItem.isTrash) return@withContext Result.success()

        suspend fun mayUpload(): Boolean {
            repository.completeOwnerFeedbackMigration()
            val latest = repository.dao.getItemById(itemId)
            if (latest == null || latest.isTrash || latest.localIndexVersion != expectedVersion) return false
            val colIds = repository.dao.getCollectionIdsForItem(itemId)
            val excludedCols = prefs.getStringSet("excluded_collections", emptySet()).orEmpty()
            val excludedItems = prefs.getStringSet("excluded_items", emptySet()).orEmpty()
            val excluded = itemId in excludedItems || colIds.any { it in excludedCols }
            val paused = prefs.getBoolean("ai_paused", false)
            if (excluded || paused) {
                val status = if (excluded) "EXCLUDED" else "QUEUED"
                val recordedAttempts = repository.dao.getAnalysisJob(itemId)?.takeIf { it.expectedContentVersion == expectedVersion }?.attempts ?: attempts
                repository.updateAnalysisJobIfCurrent(AnalysisJobEntity(itemId = itemId, expectedContentVersion = expectedVersion,
                    indexVersion = expectedVersion, status = status, attempts = recordedAttempts, metadataOnly = metadataOnly))
                return false
            }
            return true
        }
        if (!mayUpload()) return@withContext Result.success()
        val aiGateway = AiGateway(applicationContext)
        val beforeRequest: suspend () -> Unit = {
            if (!mayUpload()) throw AiGatewayException.RequestBlockedException()
            AiNetworkPolicy(applicationContext).requireAllowed()
        }

        try {
            // WorkManager constraints are a scheduling aid; the current policy is authoritative.
            // Check before charging a durable attempt, and again at every concrete HTTP dispatch.
            beforeRequest()

            // Update status to ANALYZING and record job progress
            if (!repository.updateAnalysisJobIfCurrent(
                AnalysisJobEntity(
                    itemId = itemId,
                    expectedContentVersion = expectedVersion,
                    indexVersion = expectedVersion,
                    metadataOnly = metadataOnly,
                    status = "PROCESSING",
                    attempts = attempt,
                    updatedAt = System.currentTimeMillis()
                ), "ANALYZING"
            )) return@withContext Result.success()

            // Extract bounded media parts if applicable
            val mediaParts = mutableListOf<MediaPart>()
            var mediaPartial = false
            val blobFile = if (metadataOnly) null else initialItem.contentHash?.let { repository.getBlobFile(it) }

            if (blobFile != null && blobFile.exists()) {
                val mime = (initialItem.mimeType ?: "").lowercase()
                try {
                    when {
                        mime.startsWith("image/") || initialItem.kind in setOf("PHOTO", "STICKER") -> {
                            val part = extractBoundedImagePart(blobFile)
                            if (part != null) mediaParts.add(part)
                        }
                        mime.startsWith("video/") || initialItem.kind == "VIDEO" -> {
                            mediaParts.addAll(extractBoundedVideoFrames(blobFile))
                            mediaPartial = true
                        }
                        mime.startsWith("audio/") || initialItem.kind == "AUDIO" -> {
                            val bounded = BoundedAudioExtractor.extract(blobFile, applicationContext.cacheDir)
                            mediaParts.add(MediaPart("audio/mp4", Base64.encodeToString(bounded.bytes, Base64.NO_WRAP)))
                            mediaPartial = bounded.partial
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) {
                    mediaPartial = true
                }
            }

            if (!metadataOnly && initialItem.kind == "AUDIO" && mediaParts.isEmpty()) {
                repository.publishAiResult(itemId, expectedVersion, "Automatic audio analysis unavailable: a bounded representation could not be produced.",
                    "", "UNSUPPORTED", repository.dao.getSearchDocumentsForItem(itemId), attempt)
                return@withContext Result.success()
            }

            val textForAnalysis = buildString {
                if (initialItem.content.isNotBlank()) append(initialItem.content)
                if (!initialItem.extractedText.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(initialItem.extractedText)
                }
            }

            val localDocs = repository.dao.getSearchDocumentsForItem(itemId)
            val pages = localDocs.filter { it.pageNumber != null }.groupBy { it.pageNumber!! }.toSortedMap().map { (number, docs) ->
                ExtractedPage(number, docs.sortedBy { it.chunkIndex }.joinToString("") { it.textContent })
            }
            val batches = if (isPdf) PdfAnalysisBounds.batches(pages) else emptyList()
            val responses = mutableListOf<AnalyzeResponse>()
            if (metadataOnly) {
                // Re-embed the cached representation; never upload or analyze original media for a rename.
            } else if (batches.isNotEmpty()) {
                for (batch in batches) {
                    if (!mayUpload()) return@withContext Result.success()
                    responses.add(aiGateway.analyze(itemId, initialItem.kind, batch.text, initialItem.originalFileName, beforeRequest = beforeRequest))
                }
            } else {
                if (!mayUpload()) return@withContext Result.success()
                responses.add(aiGateway.analyze(itemId, initialItem.kind, textForAnalysis.take(PdfAnalysisBounds.MAX_PROMPT_CHARS).ifBlank { null },
                    initialItem.originalFileName, mediaParts, beforeRequest = beforeRequest))
            }
            val boundedPartial = mediaPartial || batches.any { it.partial } ||
                (isPdf && (pages.isEmpty() || initialItem.localIndexCoverage.equals("partial", true))) ||
                (!isPdf && textForAnalysis.length > PdfAnalysisBounds.MAX_PROMPT_CHARS)
            val analysis = if (metadataOnly) AnalyzeResponse(itemId, initialItem.aiDescription.orEmpty(), initialItem.aiConcepts.orEmpty(),
                coverage = if (initialItem.aiStatus == "PARTIAL" || initialItem.localIndexCoverage.equals("PARTIAL", true) ||
                    localDocs.any { it.coverage.equals("PARTIAL", true) }) "PARTIAL" else "COMPLETE") else AnalyzeResponse(itemId,
                responses.mapIndexed { index, response ->
                    batches.getOrNull(index)?.let { "Pages ${it.pageNumbers.first()}–${it.pageNumbers.last()}: ${response.description}" } ?: response.description
                }.joinToString("\n").take(8000),
                responses.flatMap { it.concepts.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(40).joinToString(", ").take(1500),
                coverage = if (boundedPartial || responses.any { !it.coverage.equals("COMPLETE", true) }) "PARTIAL" else "COMPLETE")

            // --- STALE AI RESULT PROTECTION ---
            // Re-read item from database to ensure content has not changed while network call was running
            val currentItem = repository.dao.getItemById(itemId)
            if (currentItem == null || currentItem.isTrash || currentItem.localIndexVersion != expectedVersion) {
                // Item was edited, deleted, or trashed while AI was in-flight: discard stale results
                return@withContext Result.success()
            }
            if (!mayUpload()) return@withContext Result.success()

            // --- CHUNKED SEMANTIC EMBEDDINGS ---
            val chunks = createChunksForItem(currentItem, analysis.description, analysis.concepts, localDocs)
            val allEmbeddedDocs = mutableListOf<SearchDocumentEntity>()
            if (chunks.isNotEmpty()) {
                // Embed in batches of at most 32 chunks
                val batchSize = 32
                for (chunkBatch in chunks.chunked(batchSize)) {
                    if (!mayUpload()) return@withContext Result.success()
                    val textsToEmbed = chunkBatch.map { it.text }
                    val vectors = aiGateway.embed(textsToEmbed, beforeRequest = beforeRequest)
                    for (i in chunkBatch.indices) {
                        val chunkInfo = chunkBatch[i]
                        val vec = vectors.getOrNull(i)
                        if (vec != null && vec.size == 768 && vec.all { it.isFinite() }) {
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
                                    coverage = if (isPdf || chunks.size >= 40) "PARTIAL" else analysis.coverage
                                )
                            )
                        }
                    }
                }

            }
            if (!mayUpload()) return@withContext Result.success()
            // Keep local page chunks that were not selected for embedding, preserving page provenance.
            val unembeddedPages = localDocs.filter { local -> local.pageNumber != null && chunks.none { it.pageNumber == local.pageNumber && it.text == local.textContent } }
            repository.publishAiResult(itemId, expectedVersion, analysis.description, analysis.concepts,
                if (analysis.coverage == "COMPLETE") "READY" else "PARTIAL", allEmbeddedDocs + unembeddedPages, attempt)

            Result.success()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            if (!mayUpload()) return@withContext Result.success()
            if (e is AiGatewayException.NetworkPolicyException) {
                // A policy change is a wait condition, not a provider failure or a spent retry.
                val pending = previousJob?.takeIf { it.expectedContentVersion == expectedVersion }
                    ?: AnalysisJobEntity(itemId, expectedVersion, expectedVersion, metadataOnly = metadataOnly)
                repository.updateAnalysisJobIfCurrent(pending.copy(status = "QUEUED", attempts = attempts))
                return@withContext Result.retry()
            }
            if (e is AiGatewayException.RequestBlockedException) return@withContext Result.success()
            val isPermanent = e is AiGatewayException.UnconfiguredException ||
                e is AiGatewayException.UnpairedException

            val status = if (isPermanent || attempt >= 4) "NEEDS_RETRY" else "QUEUED"
            repository.updateAnalysisJobIfCurrent(
                AnalysisJobEntity(
                    itemId = itemId,
                    expectedContentVersion = expectedVersion,
                    indexVersion = expectedVersion,
                    metadataOnly = metadataOnly,
                    status = status,
                    attempts = attempt,
                    lastErrorClass = e::class.java.simpleName,
                    lastErrorTime = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            if (!isPermanent && attempt < 4) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    companion object {
        internal data class ChunkInfo(val index: Int, val text: String, val pageNumber: Int? = null)

        internal fun createChunksForItem(item: com.example.data.ItemEntity, aiDesc: String?, aiConcepts: String?, localDocs: List<SearchDocumentEntity>): List<ChunkInfo> {
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
                for (c in contentChunks.take(24)) {
                    chunks.add(ChunkInfo(chunkIndex++, c))
                }
            }

            // Chunk extracted text (OCR or PDF)
            val pageDocs = localDocs.filter { it.pageNumber != null }.groupBy { it.pageNumber }.toSortedMap(compareBy { it ?: 0 })
            if (pageDocs.isNotEmpty()) {
                for ((page, documents) in pageDocs) {
                    for (document in documents.sortedBy { it.chunkIndex }.take(3)) {
                        chunks.add(ChunkInfo(chunkIndex++, document.textContent.take(600), page))
                    }
                }
            } else if (!item.extractedText.isNullOrBlank()) {
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
