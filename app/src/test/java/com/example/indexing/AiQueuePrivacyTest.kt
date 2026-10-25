package com.example.indexing

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.workDataOf
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.data.AnalysisJobEntity
import com.example.data.LibraryRepository
import com.example.ai.setAiTestNetwork
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
        setAiTestNetwork(context, unmetered = true)
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

    @Test fun changingWifiPolicyReplacesOneUniqueJobAndPreservesMetadataAndBudget() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Policy change", "Cached owner text", emptyList())
        val prefs = context.getSharedPreferences("personal_library_settings", 0)
        prefs.edit().putBoolean("ai_wifi_only", true).commit()
        val indexer = LocalIndexer(context, repository)
        indexer.indexItem(item)
        val manager = androidx.work.WorkManager.getInstance(context)
        fun liveWork() = manager.getWorkInfosForUniqueWork("ai_job_${item.id}").get().filter { !it.state.isFinished }.single()
        val original = liveWork()
        val persisted = repository.dao.getAnalysisJob(item.id)!!.copy(status = "PROCESSING", attempts = 2, metadataOnly = true,
            lastErrorClass = "SyntheticPreviousFailure", lastErrorTime = 123L)
        repository.dao.insertOrUpdateAnalysisJob(persisted)
        prefs.edit().putBoolean("ai_wifi_only", false).commit()
        indexer.enqueuePendingAnalysisJobs()
        val connected = liveWork()
        assertNotEquals(original.id, connected.id)
        assertEquals(androidx.work.NetworkType.CONNECTED, connected.constraints.requiredNetworkType)
        assertEquals(persisted, repository.dao.getAnalysisJob(item.id))
        indexer.enqueuePendingAnalysisJobs()
        assertEquals(connected.id, liveWork().id)
        prefs.edit().putBoolean("ai_wifi_only", true).commit()
        indexer.enqueuePendingAnalysisJobs()
        val unmetered = liveWork()
        assertNotEquals(connected.id, unmetered.id)
        assertEquals(androidx.work.NetworkType.UNMETERED, unmetered.constraints.requiredNetworkType)
        assertEquals(persisted, repository.dao.getAnalysisJob(item.id))
        indexer.enqueuePendingAnalysisJobs()
        assertEquals(unmetered.id, liveWork().id)
        repository.dao.insertOrUpdateAnalysisJob(persisted.copy(attempts = 4))
        prefs.edit().putBoolean("ai_wifi_only", false).commit()
        indexer.enqueuePendingAnalysisJobs()
        assertTrue(manager.getWorkInfosForUniqueWork("ai_job_${item.id}").get().all { it.state.isFinished })
        assertEquals(persisted.copy(status = "NEEDS_RETRY", attempts = 4), repository.dao.getAnalysisJob(item.id))
    }

    @Test fun newQueueDefaultsToConnectedNetworks() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Default network", "Synthetic content")
        LocalIndexer(context, repository).indexItem(item)
        val work = androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}")
            .get().filter { !it.state.isFinished }.single()
        assertEquals(androidx.work.NetworkType.CONNECTED, work.constraints.requiredNetworkType)
    }

    @Test fun unregisteredWorkerReachesAutomaticAuthAndMigratesExclusionsBeforeAnyDispatch() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Automatic setup", "Synthetic content")
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id))
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            requests.add(exchange.requestURI.path)
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            com.example.ai.AiGateway(context).apply {
                backendBaseUrl = "http://127.0.0.1:${server.address.port}"
                identityManager.clearPairing()
            }
            val worker = TestListenableWorkerBuilder<DurableAiWorker>(context,
                inputData = workDataOf("item_id" to item.id)).build()
            worker.doWork()
            assertEquals(listOf("/v1/auth/challenge"), requests)
            requests.clear()
            val database = androidx.room.Room.databaseBuilder(context, com.example.data.LibraryRoomDatabase::class.java,
                "personal_library_room.db").addMigrations(com.example.data.MIGRATION_5_6).addCallback(com.example.data.DATABASE_CALLBACK).build()
            try {
                database.openHelper.writableDatabase.execSQL("INSERT INTO privacy_migration_memberships VALUES(?, ?)", arrayOf(item.id, "old-private"))
                context.getSharedPreferences("personal_library_settings", 0).edit().putStringSet("excluded_collections", setOf("old-private")).commit()
                TestListenableWorkerBuilder<DurableAiWorker>(context, inputData = workDataOf("item_id" to item.id)).build().doWork()
                assertTrue(requests.isEmpty())
                assertTrue(database.libraryDao().getPrivacyMigrationMemberships().isEmpty())
                assertTrue(context.getSharedPreferences("personal_library_settings", 0).getStringSet("excluded_items", emptySet())!!.contains(item.id))
            } finally { database.close() }
        } finally { server.stop(0) }
    }

    @Test fun unavailableNetworkLeavesDurableWorkPendingWithoutConsumingAttempt() = runBlocking {
        context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_wifi_only", true).commit()
        val repository = LibraryRepository(context)
        val item = repository.createNote("Offline policy", "Do not send", emptyList())
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId = item.id, status = "QUEUED", attempts = 2, metadataOnly = true))
        com.example.ai.AiGateway(context).identityManager.savePairing("synthetic-credential", "synthetic-token", 3600)
        setAiTestNetwork(context, unmetered = false, connected = false)
        val worker = TestListenableWorkerBuilder<DurableAiWorker>(context, inputData = workDataOf("item_id" to item.id)).build()
        try {
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            val pending = repository.dao.getAnalysisJob(item.id)!!
            assertEquals("QUEUED", pending.status)
            assertEquals(2, pending.attempts)
            assertTrue(pending.metadataOnly)
            assertTrue(repository.dao.getPendingAnalysisJobs().any { it.itemId == item.id })
        } finally {
            com.example.ai.AiGateway(context).identityManager.clearPairing()
        }
    }

    @Test fun collectionExcludedDuring401StopsAuthenticationAndPreservesLocalSearch() = blockedDuring401(excludeCollection = true)

    @Test fun meteredNetworkDuring401KeepsJobPendingWithoutSpendingRetry() = blockedDuring401(excludeCollection = false)

    private fun blockedDuring401(excludeCollection: Boolean) = runBlocking {
        context.getSharedPreferences("personal_library_settings", 0).edit().putBoolean("ai_wifi_only", true).commit()
        val repository = LibraryRepository(context)
        val collection = repository.createCollection("Synthetic private group", "folder", "#ffffff")
        val item = repository.createNote("Synthetic policy", "chemistry survives locally", listOf(collection.id))
        LocalIndexer(context, repository).indexItem(item)
        val indexed = repository.dao.getItemById(item.id)!!
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.requestBody.use { it.readBytes() }
            requests.add(exchange.requestURI.path)
            if (excludeCollection) context.getSharedPreferences("personal_library_settings", 0).edit()
                .putStringSet("excluded_collections", setOf(collection.id)).commit()
            else setAiTestNetwork(context, unmetered = false)
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            com.example.ai.AiGateway(context).apply {
                backendBaseUrl = "http://127.0.0.1:${server.address.port}"
                identityManager.savePairing("synthetic-credential", "synthetic-token", 3600)
            }
            val worker = TestListenableWorkerBuilder<DurableAiWorker>(context,
                inputData = workDataOf("item_id" to item.id, "content_version" to indexed.localIndexVersion)).build()
            assertEquals(if (excludeCollection) ListenableWorker.Result.success() else ListenableWorker.Result.retry(), worker.doWork())
            assertEquals(listOf("/v1/ai/analyze"), requests.toList())
            assertTrue(repository.search("chemistry").any { it.id == item.id })
            val job = repository.dao.getAnalysisJob(item.id)!!
            assertEquals(if (excludeCollection) "EXCLUDED" else "QUEUED", job.status)
            if (!excludeCollection) assertEquals(0, job.attempts)
            assertEquals(indexed.localIndexVersion, repository.dao.getItemById(item.id)!!.localIndexVersion)
        } finally {
            server.stop(0)
            context.getSharedPreferences("ai_gateway_prefs", 0).edit().clear().commit()
            context.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        }
    }

    @Test fun exclusionAndReenablePreserveLocalIndexAndReuseCachedAnalysisForMissingEmbeddings() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Cached result", "local chemistry", emptyList())
        repository.dao.updateItem(item.copy(extractedText = "cached OCR", localIndexVersion = 3,
            localIndexCoverage = "PARTIAL", aiDescription = "cached description", aiConcepts = "cached concept", aiStatus = "PARTIAL"))
        val prior = AnalysisJobEntity(item.id, 3, 3, status = "COMPLETED", attempts = 2, lastErrorClass = "Synthetic", lastErrorTime = 123L)
        repository.dao.insertOrUpdateAnalysisJob(prior)
        val local = com.example.data.SearchDocumentEntity("local_cached", item.id, 0, 7, "page chemistry", coverage = "PARTIAL")
        repository.dao.insertSearchDocuments(listOf(local))
        val prefs = context.getSharedPreferences("personal_library_settings", 0)
        val indexer = LocalIndexer(context, repository)
        prefs.edit().putStringSet("excluded_items", setOf(item.id)).commit()
        indexer.reconcileAiEligibility(listOf(item.id))
        assertEquals(prior.copy(status = "EXCLUDED"), repository.dao.getAnalysisJob(item.id))
        assertEquals(listOf(local), repository.dao.getSearchDocumentsForItem(item.id))
        assertTrue(repository.search("chemistry").any { it.id == item.id })
        prefs.edit().putStringSet("excluded_items", emptySet()).commit()
        indexer.reconcileAiEligibility(listOf(item.id))
        assertEquals(prior.copy(status = "QUEUED", metadataOnly = true), repository.dao.getAnalysisJob(item.id))
        val current = repository.dao.getItemById(item.id)!!
        assertEquals(3, current.localIndexVersion)
        assertEquals("cached OCR", current.extractedText)
        assertEquals("cached description", current.aiDescription)
        assertEquals(listOf(local), repository.dao.getSearchDocumentsForItem(item.id))
        assertEquals(1, androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().count { !it.state.isFinished })
    }

    @Test fun reenableKeepsInitialAnalysisAndExhaustedRetryBudgets() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Unanalyzed", "owner text", emptyList())
        val indexer = LocalIndexer(context, repository)
        val prior = AnalysisJobEntity(item.id, item.localIndexVersion, item.localIndexVersion, status = "EXCLUDED", attempts = 2)
        repository.dao.insertOrUpdateAnalysisJob(prior)
        indexer.reconcileAiEligibility(listOf(item.id))
        assertEquals(prior.copy(status = "QUEUED"), repository.dao.getAnalysisJob(item.id))
        assertFalse(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
        androidx.work.WorkManager.getInstance(context).cancelUniqueWork("ai_job_${item.id}").result.get()
        repository.dao.insertOrUpdateAnalysisJob(prior.copy(attempts = 4))
        indexer.reconcileAiEligibility(listOf(item.id))
        assertEquals(prior.copy(status = "NEEDS_RETRY", attempts = 4), repository.dao.getAnalysisJob(item.id))
        assertTrue(androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().all { it.state.isFinished })
    }

    @Test fun reenableCompleteCachedEmbeddingsRestoresEligibilityWithoutNewWork() = runBlocking {
        val repository = LibraryRepository(context)
        val item = repository.createNote("Cached embedding", "owner chemistry", emptyList()).copy(
            aiDescription = "cached description", aiStatus = "EXCLUDED", localIndexCoverage = "COMPLETE")
        repository.dao.updateItem(item)
        val docs = DurableAiWorker.createChunksForItem(item, item.aiDescription, null, emptyList()).map { chunk ->
            com.example.data.SearchDocumentEntity("doc_${item.id}_${item.localIndexVersion}_${chunk.index}", item.id, chunk.index, chunk.pageNumber, chunk.text,
                embedding = List(768) { "0.1" }.joinToString(","), embeddingDimension = 768, coverage = "COMPLETE")
        }
        repository.dao.insertSearchDocuments(docs)
        val prior = AnalysisJobEntity(item.id, item.localIndexVersion, item.localIndexVersion, status = "EXCLUDED", attempts = 2)
        repository.dao.insertOrUpdateAnalysisJob(prior)
        LocalIndexer(context, repository).reconcileAiEligibility(listOf(item.id))
        assertEquals(prior.copy(status = "COMPLETED"), repository.dao.getAnalysisJob(item.id))
        assertEquals("READY", repository.dao.getItemById(item.id)!!.aiStatus)
        assertEquals(docs, repository.dao.getSearchDocumentsForItem(item.id))
        assertTrue(androidx.work.WorkManager.getInstance(context).getWorkInfosForUniqueWork("ai_job_${item.id}").get().isEmpty())
    }

    @Test fun reenableLegacyStaleOrWrongModelEmbeddingsRebuildsCachedTextWithoutExtraction() = runBlocking {
        val repository = LibraryRepository(context)
        for (invalid in listOf("legacy-id", "old-version", "wrong-model")) {
            val item = repository.createNote("Cached $invalid", "synthetic chemistry", emptyList()).copy(
                aiDescription = "cached analysis", aiStatus = "EXCLUDED", localIndexCoverage = "COMPLETE", localIndexVersion = 3)
            repository.dao.updateItem(item)
            val docs = DurableAiWorker.createChunksForItem(item, item.aiDescription, null, emptyList()).map { chunk ->
                val id = when (invalid) {
                    "legacy-id" -> "legacy_${item.id}_${chunk.index}"
                    "old-version" -> "doc_${item.id}_2_${chunk.index}"
                    else -> "doc_${item.id}_3_${chunk.index}"
                }
                com.example.data.SearchDocumentEntity(id, item.id, chunk.index, chunk.pageNumber, chunk.text,
                    embedding = List(768) { "0.1" }.joinToString(","), embeddingDimension = 768,
                    modelVersion = if (invalid == "wrong-model") "obsolete-model" else "gemini-embedding-2")
            }
            repository.dao.insertSearchDocuments(docs)
            val prior = AnalysisJobEntity(item.id, 3, 3, status = "EXCLUDED", attempts = 2)
            repository.dao.insertOrUpdateAnalysisJob(prior)
            LocalIndexer(context, repository).reconcileAiEligibility(listOf(item.id))
            assertEquals(invalid, prior.copy(status = "QUEUED", metadataOnly = true), repository.dao.getAnalysisJob(item.id))
            assertEquals(docs, repository.dao.getSearchDocumentsForItem(item.id))
            assertEquals(3, repository.dao.getItemById(item.id)!!.localIndexVersion)
            assertTrue(repository.search("chemistry").any { it.id == item.id })
        }
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
