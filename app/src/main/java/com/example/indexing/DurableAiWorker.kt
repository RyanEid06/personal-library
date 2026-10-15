package com.example.indexing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ai.AiGateway
import com.example.ai.AiGatewayException
import com.example.data.AnalysisJobEntity
import com.example.data.LibraryRepository
import com.example.data.SearchDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val item = repository.dao.getItemById(itemId) ?: return@withContext Result.success()

        // If item is trashed, do not process
        if (item.isTrash) return@withContext Result.success()

        // Check exclusions
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

        val aiGateway = AiGateway(applicationContext)

        try {
            // Check pairing state before attempting network calls
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
                    expectedContentVersion = item.localIndexVersion,
                    indexVersion = item.localIndexVersion,
                    status = "PROCESSING",
                    attempts = runAttemptCount + 1,
                    updatedAt = System.currentTimeMillis()
                )
            )

            val textForAnalysis = buildString {
                if (item.content.isNotBlank()) append(item.content)
                if (!item.extractedText.isNullOrBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(item.extractedText)
                }
            }

            val analysis = aiGateway.analyze(
                itemId = item.id,
                kind = item.kind,
                textContent = textForAnalysis.ifBlank { null },
                originalFileName = item.originalFileName
            )

            // Targeted update of AI-only fields to avoid overwriting user edits in-flight
            repository.dao.updateAiMetadata(
                id = item.id,
                aiDescription = analysis.description,
                aiConcepts = analysis.concepts,
                aiStatus = "READY"
            )

            // Embed with gemini-embedding-2 (768-D)
            val textToEmbed = buildString {
                append(item.title).append(". ")
                if (item.content.isNotBlank()) append(item.content).append(". ")
                if (!item.extractedText.isNullOrBlank()) append(item.extractedText).append(". ")
                append(analysis.description).append(". ")
                append(analysis.concepts)
            }.trim().take(4000)

            val vectors = aiGateway.embed(listOf(textToEmbed))
            val vector = vectors.firstOrNull()
            if (vector != null && vector.size == 768) {
                val vectorStr = vector.joinToString(",")
                repository.dao.insertSearchDocument(
                    SearchDocumentEntity(
                        id = "doc_${item.id}",
                        itemId = item.id,
                        chunkIndex = 0,
                        textContent = textToEmbed,
                        embedding = vectorStr,
                        embeddingDimension = 768,
                        modelVersion = "gemini-embedding-2",
                        coverage = analysis.coverage
                    )
                )
            }

            repository.dao.insertOrUpdateAnalysisJob(
                AnalysisJobEntity(
                    itemId = itemId,
                    expectedContentVersion = item.localIndexVersion,
                    indexVersion = item.localIndexVersion,
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
                    expectedContentVersion = item.localIndexVersion,
                    indexVersion = item.localIndexVersion,
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
}
