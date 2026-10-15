package com.example.indexing

import android.content.Context
import androidx.work.*
import com.example.data.AnalysisJobEntity
import com.example.data.ItemEntity
import com.example.data.LibraryRepository
import com.personallibrary.core.ExtractedText
import com.personallibrary.core.TextExtraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalIndexer(
    private val context: Context,
    private val repository: LibraryRepository
) {
    suspend fun indexItem(item: ItemEntity): ExtractedText = withContext(Dispatchers.IO) {
        val hash = item.contentHash
        if (hash == null) {
            // Text note or link: content is already owner text, no separate blob extraction needed
            val coverage = "COMPLETE"
            repository.dao.updateExtractedText(item.id, null, coverage, item.localIndexVersion)
            enqueueDurableAiJob(item.id)
            return@withContext ExtractedText(item.content, coverage)
        }

        val blobFile = repository.getBlobFile(hash)
        if (blobFile == null || !blobFile.exists()) {
            repository.dao.updateExtractedText(item.id, null, "UNSUPPORTED", item.localIndexVersion)
            return@withContext ExtractedText("", "UNSUPPORTED")
        }

        val mime = (item.mimeType ?: "").lowercase()
        val ext = (item.originalFileName ?: "").substringAfterLast('.', "").lowercase()

        val extracted = try {
            when {
                mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript") ||
                    ext in setOf("txt", "json", "csv", "tsv", "md", "xml", "yaml", "yml", "log") -> {
                    TextExtraction.read(blobFile.inputStream())
                }
                mime.startsWith("image/") -> {
                    AndroidTextExtractor.image(blobFile, mime)
                }
                mime == "application/pdf" || ext == "pdf" -> {
                    AndroidTextExtractor.pdf(blobFile) { progress ->
                        // Checkpoint save partial progress into extractedText WITHOUT destroying owner content
                        val current = repository.dao.getItemById(item.id)
                        if (current != null && progress.text.isNotBlank()) {
                            repository.dao.updateExtractedText(
                                item.id,
                                progress.text,
                                progress.coverage,
                                item.localIndexVersion
                            )
                        }
                    }
                }
                else -> ExtractedText("", "UNSUPPORTED")
            }
        } catch (_: Exception) {
            ExtractedText("", "FAILED")
        }

        // Store extracted text in dedicated extractedText column, NEVER overwriting owner content!
        val newVersion = item.localIndexVersion + 1
        repository.dao.updateExtractedText(
            item.id,
            extracted.text.ifBlank { null },
            extracted.coverage,
            newVersion
        )

        // Record durable analysis job
        repository.dao.insertOrUpdateAnalysisJob(
            AnalysisJobEntity(
                itemId = item.id,
                expectedContentVersion = newVersion,
                indexVersion = newVersion,
                status = "QUEUED",
                updatedAt = System.currentTimeMillis()
            )
        )

        // Enqueue background AI job with WorkManager
        enqueueDurableAiJob(item.id)

        extracted
    }

    fun enqueueDurableAiJob(itemId: String) {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val pauseAi = prefs.getBoolean("ai_paused", false)
        if (pauseAi) return

        // Check if item or its collections are excluded
        val excludedItems = prefs.getStringSet("excluded_items", emptySet()) ?: emptySet()
        if (excludedItems.contains(itemId)) return

        val wifiOnly = prefs.getBoolean("ai_wifi_only", true)
        val networkType = if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .build()

        val data = workDataOf("item_id" to itemId)
        val request = OneTimeWorkRequestBuilder<DurableAiWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("ai_enrichment")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "ai_job_$itemId",
            ExistingWorkPolicy.REPLACE,
            request
        )
    }
}
