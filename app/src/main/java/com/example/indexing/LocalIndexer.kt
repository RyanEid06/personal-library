package com.example.indexing

import android.content.Context
import androidx.work.*
import com.example.data.AnalysisJobEntity
import com.example.data.ItemEntity
import com.example.data.LibraryRepository
import com.example.data.SearchDocumentEntity
import com.personallibrary.core.ExtractedText
import com.personallibrary.core.TextExtraction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class LocalIndexer(
    private val context: Context,
    private val repository: LibraryRepository
) {
    private suspend fun queueAnalysis(item: ItemEntity, version: Int) {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val excludedItems = prefs.getStringSet("excluded_items", emptySet()).orEmpty()
        val excludedCollections = prefs.getStringSet("excluded_collections", emptySet()).orEmpty()
        val excluded = item.id in excludedItems || repository.dao.getCollectionIdsForItem(item.id).any { it in excludedCollections }
        val status = if (excluded) "EXCLUDED" else "QUEUED"
        repository.dao.updateAiStatus(item.id, status)
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id, expectedContentVersion = version, indexVersion = version, status = status))
        if (!excluded) enqueueDurableAiJob(item.id)
    }

    suspend fun indexItem(item: ItemEntity): ExtractedText = withContext(Dispatchers.IO) {
        val hash = item.contentHash
        if (hash == null) {
            // Text note or link: content is already owner text, no separate blob extraction needed
            val coverage = "COMPLETE"
            val newVersion = item.localIndexVersion + 1
            if (repository.publishLocalIndex(item.id, item.localIndexVersion, null, coverage, emptyList())) queueAnalysis(item, newVersion)
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
                        if (current != null && current.localIndexVersion == item.localIndexVersion && progress.text.isNotBlank()) {
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
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            ExtractedText("", "FAILED")
        }

        // Store extracted text in dedicated extractedText column, NEVER overwriting owner content!
        val newVersion = item.localIndexVersion + 1
        // Keep local page text even when remote analysis is excluded or unavailable.
        var chunk = 0
        val documents = extracted.pages.flatMap { page ->
                page.text.chunked(600).filter { it.isNotBlank() }.map { text ->
                    SearchDocumentEntity("local_${item.id}_${newVersion}_${chunk}", item.id, chunk++, page.number, text,
                        coverage = extracted.coverage.uppercase())
                }
        }
        if (repository.publishLocalIndex(item.id, item.localIndexVersion, extracted.text.ifBlank { null }, extracted.coverage, documents)) {
            queueAnalysis(item, newVersion)
        }

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

    suspend fun enqueuePendingAnalysisJobs() = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("ai_paused", false)) return@withContext

        val pendingJobs = repository.dao.getPendingAnalysisJobs()
        for (job in pendingJobs) {
            val item = repository.dao.getItemById(job.itemId)
            if (item != null && !item.isTrash) {
                enqueueDurableAiJob(job.itemId)
            }
        }
    }
}
