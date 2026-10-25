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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

class LocalIndexer(
    private val context: Context,
    private val repository: LibraryRepository
) {
    companion object { private val queueMutations = Mutex() }
    private suspend fun queueAnalysis(item: ItemEntity, version: Int) {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val excludedItems = prefs.getStringSet("excluded_items", emptySet()).orEmpty()
        val excludedCollections = prefs.getStringSet("excluded_collections", emptySet()).orEmpty()
        val excluded = item.id in excludedItems || repository.dao.getCollectionIdsForItem(item.id).any { it in excludedCollections }
        val status = if (excluded) "EXCLUDED" else "QUEUED"
        val queued = repository.updateAnalysisJobIfCurrent(AnalysisJobEntity(itemId = item.id, expectedContentVersion = version, indexVersion = version, status = status))
        if (queued && !excluded) enqueueDurableAiJob(item.id)
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
            repository.checkpointLocalIndex(item.id, item.localIndexVersion, null, "UNSUPPORTED")
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
                mime.startsWith("image/") || item.kind in setOf("PHOTO", "STICKER") -> {
                    AndroidTextExtractor.image(blobFile, mime)
                }
                mime == "application/pdf" || ext == "pdf" -> {
                    AndroidTextExtractor.pdf(blobFile) { progress ->
                        // Checkpoint save partial progress into extractedText WITHOUT destroying owner content
                        if (progress.text.isNotBlank()) {
                            repository.checkpointLocalIndex(
                                item.id,
                                item.localIndexVersion,
                                progress.text,
                                progress.coverage
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

    suspend fun enqueueDurableAiJob(itemId: String) = queueMutations.withLock {
        repository.completeOwnerFeedbackMigration()
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val pauseAi = prefs.getBoolean("ai_paused", false)
        if (pauseAi) return@withLock

        // Check if item or its collections are excluded
        val excludedItems = prefs.getStringSet("excluded_items", emptySet()) ?: emptySet()
        if (excludedItems.contains(itemId)) return@withLock
        val item = repository.dao.getItemById(itemId) ?: return@withLock
        val job = repository.dao.getAnalysisJob(itemId) ?: return@withLock
        if (item.isTrash || job.expectedContentVersion != item.localIndexVersion) return@withLock
        val excludedCollections = prefs.getStringSet("excluded_collections", emptySet()).orEmpty()
        if (repository.dao.getCollectionIdsForItem(itemId).any { it in excludedCollections }) return@withLock
        val versionTag = "ai_version_${item.localIndexVersion}"
        val networkType = if (prefs.getBoolean("ai_wifi_only", false)) NetworkType.UNMETERED else NetworkType.CONNECTED
        val manager = WorkManager.getInstance(context)
        val liveWork = manager.getWorkInfosForUniqueWork("ai_job_$itemId").get().filter { !it.state.isFinished }
        if (liveWork.any { versionTag in it.tags && it.constraints.requiredNetworkType == networkType }) return@withLock
        if (job.attempts >= 4) {
            // An obsolete request must not survive a preference change, even at the retry ceiling.
            if (liveWork.isNotEmpty()) manager.cancelUniqueWork("ai_job_$itemId").result.get()
            repository.updateAnalysisJobIfCurrent(job.copy(status = "NEEDS_RETRY"))
            return@withLock
        }
        // Keep unchanged work/backoff; replace the unique job when content or policy is obsolete.
        // The durable job row remains the authority for version, metadata-only mode and attempts.
        val policy = if (liveWork.isNotEmpty()) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(networkType)
            .setRequiresBatteryNotLow(true)
            .build()

        val data = workDataOf("item_id" to itemId, "content_version" to item.localIndexVersion)
        val request = OneTimeWorkRequestBuilder<DurableAiWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, java.util.concurrent.TimeUnit.SECONDS)
            .addTag("ai_enrichment")
            .addTag(versionTag)
            .build()

        manager.enqueueUniqueWork(
            "ai_job_$itemId",
            policy,
            request
        ).result.get()
    }

    /** Privacy changes only alter AI eligibility. Local extraction and keyword documents remain intact. */
    suspend fun reconcileAiEligibility(itemIds: Collection<String>) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        for (itemId in itemIds.distinct()) {
            val shouldEnqueue = queueMutations.withLock {
                val item = repository.dao.getItemById(itemId) ?: return@withLock false
                if (item.isTrash) {
                    WorkManager.getInstance(context).cancelUniqueWork("ai_job_$itemId").result.get()
                    return@withLock false
                }
                val currentJob = repository.dao.getAnalysisJob(itemId)?.takeIf { it.expectedContentVersion == item.localIndexVersion }
                    ?: AnalysisJobEntity(itemId, item.localIndexVersion, item.localIndexVersion)
                val excluded = itemId in prefs.getStringSet("excluded_items", emptySet()).orEmpty() ||
                    repository.dao.getCollectionIdsForItem(itemId).any { it in prefs.getStringSet("excluded_collections", emptySet()).orEmpty() }
                if (excluded) {
                    WorkManager.getInstance(context).cancelUniqueWork("ai_job_$itemId").result.get()
                    repository.updateAnalysisJobIfCurrent(currentJob.copy(status = "EXCLUDED"))
                    return@withLock false
                }
                val hasCachedAnalysis = !item.aiDescription.isNullOrBlank() || !item.aiConcepts.isNullOrBlank()
                val documents = repository.dao.getSearchDocumentsForItem(itemId)
                val expectedChunks = if (hasCachedAnalysis) DurableAiWorker.createChunksForItem(item, item.aiDescription, item.aiConcepts, documents) else emptyList()
                val hasAllEmbeddings = hasCachedAnalysis && expectedChunks.isNotEmpty() && expectedChunks.all { chunk ->
                    documents.any { document ->
                        document.chunkIndex == chunk.index && document.pageNumber == chunk.pageNumber && document.textContent == chunk.text &&
                            document.id in setOf("doc_${item.id}_${item.localIndexVersion}_${chunk.index}", "local_${item.id}_${item.localIndexVersion}_${chunk.index}") &&
                            document.modelVersion == "gemini-embedding-2" && document.embeddingDimension == 768 && document.embedding?.split(',')?.let { values ->
                                values.size == 768 && values.all { it.toFloatOrNull()?.isFinite() == true }
                            } == true
                    }
                }
                if (hasAllEmbeddings) {
                    val status = if (item.localIndexCoverage.equals("PARTIAL", true) || documents.any { it.coverage.equals("PARTIAL", true) }) "PARTIAL" else "READY"
                    repository.updateAnalysisJobIfCurrent(currentJob.copy(status = "COMPLETED"), status)
                    return@withLock false
                }
                val terminal = currentJob.attempts >= 4 || (currentJob.status == "NEEDS_RETRY" && currentJob.lastErrorClass != "UNPAIRED")
                repository.updateAnalysisJobIfCurrent(currentJob.copy(status = if (terminal) "NEEDS_RETRY" else "QUEUED",
                    metadataOnly = currentJob.metadataOnly || hasCachedAnalysis))
                !terminal
            }
            if (shouldEnqueue) enqueueDurableAiJob(itemId)
        }
    }

    suspend fun enqueuePendingAnalysisJobs(resumeUnpaired: Boolean = true) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        if (prefs.getBoolean("ai_paused", false)) return@withContext

        val pendingJobs = repository.dao.getPendingAnalysisJobs()
        for (job in pendingJobs) {
            if (job.status == "NEEDS_RETRY" && !(resumeUnpaired && job.lastErrorClass == "UNPAIRED")) continue
            val item = repository.dao.getItemById(job.itemId)
            if (item != null && !item.isTrash && item.localIndexVersion == job.expectedContentVersion) {
                if (job.status == "NEEDS_RETRY") repository.updateAnalysisJobIfCurrent(job.copy(status = "QUEUED"))
                enqueueDurableAiJob(job.itemId)
            }
        }
    }

    suspend fun retryAnalysis(itemId: String) = withContext(Dispatchers.IO) {
        val item = repository.dao.getItemById(itemId) ?: return@withContext
        if (item.isTrash) return@withContext
        WorkManager.getInstance(context).cancelUniqueWork("ai_job_$itemId").result.get()
        val job = repository.dao.getAnalysisJob(itemId)
        if (job?.metadataOnly == true && job.expectedContentVersion == item.localIndexVersion) {
            repository.updateAnalysisJobIfCurrent(job.copy(status = "QUEUED", attempts = 0, lastErrorClass = null, lastErrorTime = null))
            enqueueDurableAiJob(itemId)
        } else if (item.localIndexCoverage in setOf("PENDING", "FAILED") || item.aiStatus == "PENDING") indexItem(item)
        else queueAnalysis(item, item.localIndexVersion)
    }
}
