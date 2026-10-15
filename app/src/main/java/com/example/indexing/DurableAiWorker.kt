package com.example.indexing

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.ai.AiGateway
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

        // Check if item or its collections are excluded from AI
        val colIds = repository.dao.getCollectionIdsForItem(itemId)
        val excludedCols = prefs.getStringSet("excluded_collections", emptySet()) ?: emptySet()
        val excludedItems = prefs.getStringSet("excluded_items", emptySet()) ?: emptySet()
        if (excludedItems.contains(itemId) || colIds.any { excludedCols.contains(it) }) {
            repository.dao.updateItem(item.copy(aiStatus = "EXCLUDED"))
            return@withContext Result.success()
        }

        val aiGateway = AiGateway(applicationContext)

        try {
            // Update status to ANALYZING
            repository.dao.updateItem(item.copy(aiStatus = "ANALYZING"))

            val analysis = aiGateway.analyze(
                itemId = item.id,
                kind = item.kind,
                textContent = item.content,
                originalFileName = item.originalFileName
            )

            repository.dao.updateItem(
                item.copy(
                    aiDescription = analysis.description,
                    aiConcepts = analysis.concepts,
                    aiStatus = "READY"
                )
            )

            // Persist 768-D normalized embedding vector from gemini-embedding-2 locally
            val textToEmbed = "${item.title}. ${item.content}. ${analysis.description}. ${analysis.concepts}"
            val vectors = aiGateway.embed(listOf(textToEmbed))
            val vector = vectors.firstOrNull()
            if (vector != null) {
                val vectorStr = vector.joinToString(",")
                repository.dao.insertSearchDocument(
                    SearchDocumentEntity(
                        id = "doc_${item.id}",
                        itemId = item.id,
                        textContent = textToEmbed,
                        embedding = vectorStr,
                        embeddingDimension = 768,
                        modelVersion = "gemini-embedding-2",
                        coverage = analysis.coverage
                    )
                )
            }

            // Sync with FTS virtual table
            repository.dao.rebuildFts()

            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            if (runAttemptCount < 3) {
                Result.retry()
            } else {
                repository.dao.updateItem(item.copy(aiStatus = "NEEDS_RETRY"))
                Result.failure()
            }
        }
    }
}
