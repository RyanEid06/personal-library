package com.example.indexing

import android.content.Context
import androidx.work.*
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
            // Text or link note
            return@withContext ExtractedText(item.content, "complete")
        }

        val blobFile = repository.getBlobFile(hash)
        if (blobFile == null || !blobFile.exists()) {
            return@withContext ExtractedText("", "unsupported")
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
                        // Checkpoint save partial progress for restart safety
                        val current = repository.dao.getItemById(item.id)
                        if (current != null && progress.text.isNotBlank()) {
                            repository.dao.updateItem(current.copy(content = progress.text))
                            repository.dao.rebuildFts()
                        }
                    }
                }
                else -> ExtractedText("", "unsupported")
            }
        } catch (_: Exception) {
            ExtractedText("", "failed")
        }

        // If new text extracted, update item content for FTS searching
        if (extracted.text.isNotBlank()) {
            val updated = item.copy(
                content = extracted.text
            )
            repository.dao.updateItem(updated)
            repository.dao.rebuildFts()
        }

        // Enqueue durable background AI job with WorkManager
        enqueueDurableAiJob(item.id)

        extracted
    }

    fun enqueueDurableAiJob(itemId: String) {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val wifiOnly = prefs.getBoolean("ai_wifi_only", true)
        val pauseAi = prefs.getBoolean("ai_paused", false)

        if (pauseAi) return

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
