package com.example.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.AnalysisJobEntity
import com.example.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiQueuePrivacyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun resetPreferences() {
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().commit()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
    }

    @Test fun automaticRecoveryPreservesExistingWorkAndItsRetryBudget() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Active work", "Preserve work identity", emptyList())
        val indexer = LocalIndexer(context, repository)
        indexer.indexItem(item)
        val manager = androidx.work.WorkManager.getInstance(context)
        val before = manager.getWorkInfosForUniqueWork("ai_job_${item.id}").get().single()
        val job = repository.dao.getAnalysisJob(item.id)!!
        repository.dao.insertOrUpdateAnalysisJob(job.copy(status = "PROCESSING", attempts = 2))
        indexer.enqueuePendingAnalysisJobs()
        val after = manager.getWorkInfosForUniqueWork("ai_job_${item.id}").get().filter { !it.state.isFinished }.single()
        assertEquals(before.id, after.id)
        assertEquals(2, repository.dao.getAnalysisJob(item.id)!!.attempts)
    }

    @Test fun automaticRecoveryDoesNotResurrectTerminalFailures() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Failed work", "Wait for user retry", emptyList())
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id, status = "NEEDS_RETRY", attempts = 4))
        LocalIndexer(context, repository).enqueuePendingAnalysisJobs()
        assertTrue(androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().isEmpty())
        assertEquals("NEEDS_RETRY", repository.dao.getAnalysisJob(item.id)!!.status)
    }

    @Test fun pausedWorkerRetainsQueuedJobAndFinishesWithoutRetry() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Private", "Offline searchable", emptyList())
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id, status = "QUEUED"))
        context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_paused", true).commit()
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context, inputData = workDataOf("item_id" to item.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(repository.dao.getPendingAnalysisJobs().any { it.itemId == item.id })
    }

    @Test fun excludedItemStillExtractsLocallyAndNeverStartsRemoteAnalysis() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.captureStream("private chemistry".byteInputStream(), "private.txt", "text/plain")
        context.getSharedPreferences("personal_library_settings", 0).edit().putStringSet("excluded_items", setOf(item.id)).commit()
        LocalIndexer(context, repository).indexItem(item)
        assertEquals("private chemistry", repository.dao.getItemById(item.id)!!.extractedText)
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context, inputData = workDataOf("item_id" to item.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("EXCLUDED", repository.dao.getItemById(item.id)!!.aiStatus)
    }

    @Test fun resumingPausedQueueEnqueuesPersistedAnalysisWork() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Queue", "Resume this item", emptyList())
        val prefs = context.getSharedPreferences("personal_library_settings", 0)
        prefs.edit().putBoolean("ai_paused", true).commit()
        val indexer = LocalIndexer(context, repository)
        indexer.indexItem(item)
        val manager = androidx.work.WorkManager.getInstance(context)
        assertTrue(manager.getWorkInfosForUniqueWork("ai_job_${item.id}").get().isEmpty())
        prefs.edit().putBoolean("ai_paused", false).commit()
        indexer.enqueuePendingAnalysisJobs()
        assertFalse(manager.getWorkInfosForUniqueWork("ai_job_${item.id}").get().isEmpty())
    }

    @Test fun excludedCollectionStillIndexesLocallyAndPreventsWorkerUpload() = runBlocking {
        val repository = LibraryRepository(context)
        val collection = repository.createCollection("Private collection", "folder", "#ffffff")
        val item = repository.captureStream("collection chemistry".byteInputStream(), "collection.txt", "text/plain", collectionIds = listOf(collection.id))
        context.getSharedPreferences("personal_library_settings", 0).edit().putStringSet("excluded_collections", setOf(collection.id)).commit()
        LocalIndexer(context, repository).indexItem(item)
        assertTrue(repository.search("chemistry").any { it.id == item.id })
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context, inputData = workDataOf("item_id" to item.id)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("EXCLUDED", repository.dao.getItemById(item.id)!!.aiStatus)
    }
}
