package com.example.indexing

import android.content.Context
import androidx.work.*
import com.example.data.LibraryRepository
import kotlinx.coroutines.CancellationException

class LocalIndexWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val repository = LibraryRepository(applicationContext)
        val indexer = LocalIndexer(applicationContext, repository)
        for (item in repository.dao.getAllActiveItems().filter { it.aiStatus == "PENDING" || it.localIndexCoverage == "PENDING" }) {
            try { indexer.indexItem(item) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { repository.dao.updateAiStatus(item.id, "NEEDS_RETRY") }
        }
        indexer.enqueuePendingAnalysisJobs()
        return Result.success()
    }
    companion object {
        fun enqueue(context: Context) = WorkManager.getInstance(context).enqueueUniqueWork("local_index_pending",
            ExistingWorkPolicy.APPEND_OR_REPLACE, OneTimeWorkRequestBuilder<LocalIndexWorker>().build())
    }
}
