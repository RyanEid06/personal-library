package com.example.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.AnalysisJobEntity
import com.example.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
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

    @Test fun metadataOnlyTitleEditEmbedsCachedTextWithoutAnalyzingOrReadingPdfAgain() = metadataOnlyRoundTrip(false)
    @Test fun metadataOnlyRetryAndRepeatedRenamePreserveCachedPartialCoverage() = metadataOnlyRoundTrip(true)

    private fun metadataOnlyRoundTrip(partial: Boolean) = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.captureStream("unreadable synthetic pdf bytes".byteInputStream(), "cached.pdf", "application/pdf")
        repository.dao.updateItem(item.copy(title = "oldtitleword", extractedText = "cached OCR text", localIndexCoverage = "COMPLETE", aiDescription = "cached description", aiConcepts = "cached concept", aiStatus = if (partial) "PARTIAL" else "READY"))
        context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_paused", true).commit()
        repository.editItem(item.id, "newtitleword", item.content)
        if (partial) {
            assertTrue(repository.dao.getSearchDocumentsForItem(item.id).all { it.coverage == "PARTIAL" })
            // A failed embedding attempt replaces aiStatus, but must not erase cached analysis coverage.
            repository.dao.updateAiStatus(item.id, "QUEUED")
            repository.editItem(item.id, "newtitleword2", item.content)
            assertTrue(repository.dao.getSearchDocumentsForItem(item.id).all { it.coverage == "PARTIAL" })
        }
        val edited = repository.dao.getItemById(item.id)!!
        assertEquals(item.localIndexVersion + if (partial) 2 else 1, edited.localIndexVersion)
        assertTrue(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context,
            inputData = workDataOf("item_id" to item.id, "content_version" to edited.localIndexVersion)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertTrue(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
        com.example.ai.AiGateway(context).identityManager.clearPairing()
        context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_paused", false).commit()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        assertTrue(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val payload = exchange.requestBody.bufferedReader().readText()
            requests.add(exchange.requestURI.path)
            if (!exchange.requestURI.path.endsWith("/embed")) {
                exchange.sendResponseHeaders(400, 0)
                exchange.responseBody.close()
                return@createContext
            }
            val count = kotlinx.serialization.json.Json.parseToJsonElement(payload).jsonObject.getValue("texts").jsonArray.size
            val vector = List(768) { "0.1" }.joinToString(",", "[", "]")
            val response = "{\"dimension\":768,\"embeddings\":[" + List(count) { vector }.joinToString(",") + "]}"
            exchange.responseHeaders.add("Content-Type", "application/json")
            val bytes = response.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            com.example.ai.AiGateway(context).apply {
                backendBaseUrl = "http://127.0.0.1:${server.address.port}"
                identityManager.savePairing("synthetic-credential", "synthetic-token", 3600)
            }
            context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_paused", false).commit()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertTrue(requests.isNotEmpty())
            assertTrue(requests.all { it.endsWith("/embed") })
            assertEquals("cached OCR text", repository.dao.getItemById(item.id)!!.extractedText)
            val docs = repository.dao.getSearchDocumentsForItem(item.id)
            assertTrue(docs.any { it.textContent.startsWith("newtitleword") && it.embedding != null })
            assertTrue(docs.none { "oldtitleword" in it.textContent })
            assertTrue(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
            if (partial) assertEquals("PARTIAL", repository.dao.getItemById(item.id)!!.aiStatus)
            assertFalse(repository.publishAiResult(item.id, item.localIndexVersion, "stale", "stale", "READY", emptyList(), 1))
        } finally {
            server.stop(0)
            context.getSharedPreferences("ai_gateway_prefs", 0).edit().clear().commit()
            context.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        }
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
        repository.dao.insertOrUpdateAnalysisJob(job.copy(status = "PROCESSING", attempts = 4))
        indexer.enqueuePendingAnalysisJobs()
        assertEquals("PROCESSING", repository.dao.getAnalysisJob(item.id)!!.status)
        assertEquals(before.id, manager.getWorkInfosForUniqueWork("ai_job_${item.id}").get().filter { !it.state.isFinished }.single().id)
    }

    @Test fun legacyPdfTextWithoutPagesIsReindexedBeforeRemoteWork() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.captureStream("corrupt synthetic pdf".byteInputStream(), "legacy.pdf", "application/pdf")
        repository.dao.updateExtractedText(item.id, "Legacy flattened text from 40 pages", "COMPLETE", item.localIndexVersion)
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context,
            inputData = workDataOf("item_id" to item.id, "content_version" to item.localIndexVersion)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        val after = repository.dao.getItemById(item.id)!!
        assertEquals(item.localIndexVersion + 1, after.localIndexVersion)
        assertNull("Unverifiable old page text must never enter the fallback prompt", after.extractedText)
        assertEquals("FAILED", after.localIndexCoverage.uppercase())
    }

    @Test fun automaticRecoveryDoesNotResurrectTerminalFailures() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Failed work", "Wait for user retry", emptyList())
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id, status = "NEEDS_RETRY", attempts = 4))
        LocalIndexer(context, repository).enqueuePendingAnalysisJobs()
        assertTrue(androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().isEmpty())
        assertEquals("NEEDS_RETRY", repository.dao.getAnalysisJob(item.id)!!.status)
    }

    @Test fun abandonedProcessingRecoversWithoutResettingPersistedAttemptCount() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Interrupted", "Recover persisted processing", emptyList())
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id, expectedContentVersion = item.localIndexVersion,
            indexVersion = item.localIndexVersion, status = "PROCESSING", attempts = 2))
        LocalIndexer(context, repository).enqueuePendingAnalysisJobs()
        val work = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().single()
        assertTrue("ai_version_${item.localIndexVersion}" in work.tags)
        assertEquals(2, repository.dao.getAnalysisJob(item.id)!!.attempts)
    }

    @Test fun persistedRetryCeilingSurvivesNewWorkRequestUntilExplicitRetry() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Ceiling", "Do not resend automatically", emptyList())
        repository.dao.updateExtractedText(item.id, null, "COMPLETE", item.localIndexVersion)
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id, status = "PROCESSING", attempts = 4))
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context, inputData = workDataOf("item_id" to item.id)).build()
        assertEquals(ListenableWorker.Result.failure(), worker.doWork())
        assertEquals(4, repository.dao.getAnalysisJob(item.id)!!.attempts)
        LocalIndexer(context, repository).enqueuePendingAnalysisJobs()
        assertTrue(androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().isEmpty())
        LocalIndexer(context, repository).retryAnalysis(item.id)
        assertEquals(0, repository.dao.getAnalysisJob(item.id)!!.attempts)
        assertFalse(androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().isEmpty())
    }

    @Test fun obsoleteWorkCannotStartOnNewContentVersion() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Replacement", "Current content", emptyList())
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context,
            inputData = workDataOf("item_id" to item.id, "content_version" to item.localIndexVersion - 1)).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals("PENDING", repository.dao.getItemById(item.id)!!.aiStatus)
        assertNull(repository.dao.getAnalysisJob(item.id))
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
