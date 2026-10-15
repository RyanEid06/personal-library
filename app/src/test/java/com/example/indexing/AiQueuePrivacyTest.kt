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
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiQueuePrivacyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

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
}
