package com.example.ui

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.PersonalLibraryApp
import com.example.ai.setAiTestNetwork
import com.example.data.AnalysisJobEntity
import com.example.data.CollectionEntity
import com.example.data.ItemCollectionCrossRef
import com.example.data.ItemEntity
import com.example.data.SearchDocumentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

@OptIn(ExperimentalCoroutinesApi::class, androidx.room.ExperimentalRoomApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PersonalLibraryApp::class)
class CollectionAiMembershipTest {
    private lateinit var application: PersonalLibraryApp
    private lateinit var store: ViewModelStore
    private lateinit var viewModel: MainViewModel
    private lateinit var mainDispatcher: kotlinx.coroutines.test.TestDispatcher
    private val seededDocuments = mutableMapOf<String, List<SearchDocumentEntity>>()
    private val repository get() = viewModel.repository
    private val prefs get() = application.getSharedPreferences("personal_library_settings", 0)
    private val manager get() = WorkManager.getInstance(application)
    private val excluded = CollectionEntity("excluded", "Synthetic private collection")
    private val ordinary = CollectionEntity("ordinary", "Synthetic ordinary collection")

    @Before fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        val invalidation = org.robolectric.Robolectric.buildService(androidx.room.MultiInstanceInvalidationService::class.java).create()
        val binding = android.content.Intent(application, androidx.room.MultiInstanceInvalidationService::class.java)
        org.robolectric.Shadows.shadowOf(application).setComponentNameAndServiceForBindServiceForIntent(
            binding, checkNotNull(binding.component), checkNotNull(invalidation.get().onBind(binding)))
        prefs.edit().clear().putBoolean("ai_paused", true)
            .putStringSet("excluded_collections", setOf(excluded.id)).commit()
        application.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        setAiTestNetwork(application, unmetered = true)
        WorkManagerTestInitHelper.initializeTestWorkManager(application,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        mainDispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(mainDispatcher)
        store = ViewModelStore()
        viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[MainViewModel::class.java]
        // Finish startup maintenance before this legacy SQLite fixture introduces concurrent writes.
        withTimeout(5_000) {
            while (manager.getWorkInfosForUniqueWork("local_index_pending").get().let {
                it.isEmpty() || it.any { work -> !work.state.isFinished }
            }) {
                mainDispatcher.scheduler.runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        repository.dao.getPendingCollectionDeletions().forEach {
            repository.finalizeCollectionDeletion(it.operationId, Long.MAX_VALUE)
        }
        repository.dao.insertCollection(excluded)
        repository.dao.insertCollection(ordinary)
    }

    @After fun tearDown() {
        runBlocking {
            repository.dao.getPendingCollectionDeletions().forEach {
                repository.finalizeCollectionDeletion(it.operationId, Long.MAX_VALUE)
            }
        }
        store.clear()
        manager.cancelAllWork().result.get()
        Dispatchers.resetMain()
        for (name in listOf("personal_library_settings", "device_identity_prefs", "ai_gateway_prefs")) {
            application.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test fun singleAddCancelsQueuedAiWithoutRemovingLocalSearch() = runBlocking {
        val messages = mutableListOf<String>()
        val observer = launch(Dispatchers.Unconfined) { viewModel.libraryMessages.collect { messages += it } }
        val item = seed("single-add", "QUEUED")
        prefs.edit().putBoolean("ai_paused", false).commit()
        viewModel.localIndexer.enqueueDurableAiJob(item.id)
        assertEquals(1, liveWork(item.id))
        assertTrue(repository.dao.getAllCollections().any { it.id == excluded.id })

        viewModel.setItemCollections(item.id, listOf(excluded.id))

        withTimeoutOrNull(5_000) { while (repository.dao.getCollectionIdsForItem(item.id) != listOf(excluded.id)) {
            mainDispatcher.scheduler.runCurrent()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(10)
        } }
        observer.cancel()
        assertEquals("Membership transition messages=$messages", listOf(excluded.id), repository.dao.getCollectionIdsForItem(item.id))
        awaitJob(item.id, "EXCLUDED")
        assertEquals(listOf(excluded.id), repository.dao.getCollectionIdsForItem(item.id))
        assertEquals(0, liveWork(item.id))
        assertLocalState(item)
    }

    @Test fun movingOutOfPrivateFolderKeepsItemExcludedWithoutChangingLocalEvidence() = runBlocking {
        val item = seed("single-remove", "EXCLUDED", cached = true, inExcluded = true)
        prefs.edit().putBoolean("ai_paused", false).commit()

        viewModel.setItemCollections(item.id, listOf(ordinary.id))

        withTimeout(5_000) {
            while (repository.dao.getCollectionIdsForItem(item.id) != listOf(ordinary.id)) {
                mainDispatcher.scheduler.runCurrent()
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                delay(10)
            }
        }
        awaitJob(item.id, "EXCLUDED")
        assertEquals(listOf(ordinary.id), repository.dao.getCollectionIdsForItem(item.id))
        assertTrue(prefs.getStringSet("excluded_items", emptySet())!!.contains(item.id))
        awaitLiveWork(item.id, 0)
        assertLocalState(item)
    }

    @Test fun bulkAddCancelsEveryAffectedJobAndPreservesKeywordSearch() = runBlocking {
        val items = listOf(seed("bulk-add-a", "QUEUED"), seed("bulk-add-b", "QUEUED", cached = true))
        prefs.edit().putBoolean("ai_paused", false).commit()
        items.forEach { viewModel.localIndexer.enqueueDurableAiJob(it.id) }

        assertNull(viewModel.changeSelectedCollections(items.map { it.id }.toSet(), setOf(excluded.id), add = true))

        for (item in items) {
            assertEquals("EXCLUDED", repository.dao.getAnalysisJob(item.id)!!.status)
            assertEquals(0, liveWork(item.id))
            assertLocalState(item)
        }
    }

    @Test fun bulkRemovePreservesEveryInheritedAndIndependentExclusion() = runBlocking {
        val initial = seed("bulk-remove-initial", "EXCLUDED", inExcluded = true)
        val individuallyExcluded = seed("bulk-remove-private", "EXCLUDED", inExcluded = true)
        val otherCollection = seed("bulk-remove-other", "EXCLUDED", inExcluded = true)
        repository.dao.insertCollection(CollectionEntity("other-private", "Other private collection"))
        repository.dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(otherCollection.id, "other-private"))
        prefs.edit().putBoolean("ai_paused", false).putStringSet("excluded_items", setOf(individuallyExcluded.id))
            .putStringSet("excluded_collections", setOf(excluded.id, "other-private")).commit()
        val items = listOf(initial, individuallyExcluded, otherCollection)

        assertNull(viewModel.changeSelectedCollections(items.map { it.id }.toSet(), setOf(excluded.id), add = false))

        assertEquals("EXCLUDED", repository.dao.getAnalysisJob(initial.id)!!.status)
        assertFalse(repository.dao.getAnalysisJob(initial.id)!!.metadataOnly)
        assertEquals(0, liveWork(initial.id))
        for (item in listOf(individuallyExcluded, otherCollection)) {
            assertEquals("EXCLUDED", repository.dao.getAnalysisJob(item.id)!!.status)
            assertEquals(0, liveWork(item.id))
        }
        items.forEach { assertLocalState(it) }
    }

    @Test fun deletingExcludedCollectionTrashesMembersWithoutReenablingAi() = runBlocking {
        val missing = seed("delete-missing", "EXCLUDED", cached = true, inExcluded = true)
        val complete = seed("delete-complete", "EXCLUDED", cached = true, inExcluded = true, completeEmbeddings = true)
        val exhausted = seed("delete-exhausted", "EXCLUDED", cached = true, inExcluded = true, attempts = 4)
        prefs.edit().putBoolean("ai_paused", false).commit()

        viewModel.deleteCollection(excluded.id)

        withTimeout(5_000) { while (repository.dao.getAllCollections().any { it.id == excluded.id }) delay(10) }
        assertFalse(repository.dao.getAllCollections().any { it.id == excluded.id })
        assertTrue(repository.dao.getAllCollections().any { it.id == ordinary.id })
        awaitLiveWork(missing.id, 0)
        assertEquals(0, liveWork(complete.id))
        assertEquals(0, liveWork(exhausted.id))
        for (item in listOf(missing, complete, exhausted)) {
            assertTrue(repository.dao.getItemById(item.id)!!.isTrash)
            assertEquals(listOf(excluded.id), repository.dao.getCollectionIdsForItem(item.id))
            assertTrue(prefs.getStringSet("excluded_items", emptySet())!!.contains(item.id))
            assertEquals(seededDocuments.getValue(item.id), repository.dao.getSearchDocumentsForItem(item.id))
            assertTrue(repository.dao.searchFts("chemistry").none { it.id == item.id })
        }
    }

    @Test fun failedBulkMembershipChangeLeavesEligibilityAndLocalSearchIntact() = runBlocking {
        val item = seed("failed-bulk", "EXCLUDED", inExcluded = true)

        val error = viewModel.changeSelectedCollections(setOf(item.id), setOf("missing-collection"), add = true)

        assertFalse(error.isNullOrBlank())
        assertEquals("EXCLUDED", repository.dao.getAnalysisJob(item.id)!!.status)
        assertEquals(listOf(excluded.id), repository.dao.getCollectionIdsForItem(item.id))
        assertLocalState(item)
    }

    @Test fun cancellationAfterBulkMembershipCommitStillReconcilesEveryItem() = runBlocking {
        val items = listOf(seed("cancel-bulk-a", "QUEUED"), seed("cancel-bulk-b", "QUEUED", cached = true))
        val boundaryReached = AtomicBoolean(false)
        val cancellationObserved = AtomicBoolean(false)
        lateinit var caller: Job
        // Only intercept this caller's explicit Main invalidation, not Room observation dispatches.
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (context[CoroutineName]?.name == "membership-transition" && boundaryReached.compareAndSet(false, true)) {
                    caller.cancel(CancellationException("Synthetic cancellation after membership commit"))
                }
                block.run()
            }
        })
        caller = launch(Dispatchers.IO + CoroutineName("membership-transition"), start = CoroutineStart.LAZY) {
            try {
                viewModel.changeSelectedCollections(items.map { it.id }.toSet(), setOf(excluded.id), add = true)
            } catch (cancelled: CancellationException) {
                cancellationObserved.set(true)
                throw cancelled
            }
        }

        caller.start()
        withTimeout(5_000) { caller.join() }

        assertTrue("The test must cancel at the post-commit Main boundary", boundaryReached.get())
        assertTrue("The caller must still observe cancellation", cancellationObserved.get())
        assertTrue(caller.isCancelled)
        for (item in items) {
            assertTrue(repository.dao.getCollectionIdsForItem(item.id).contains(excluded.id))
            assertEquals("EXCLUDED", repository.dao.getAnalysisJob(item.id)!!.status)
            assertEquals(0, liveWork(item.id))
            assertLocalState(item)
        }
    }

    private suspend fun seed(id: String, status: String, cached: Boolean = false, inExcluded: Boolean = false,
        completeEmbeddings: Boolean = false, attempts: Int = 2): ItemEntity {
        val item = ItemEntity(id, "Synthetic $id", "local chemistry", "TEXT", extractedText = "cached OCR",
            localIndexCoverage = "COMPLETE", localIndexVersion = 3,
            aiDescription = if (cached) "cached description" else null, aiStatus = status)
        repository.dao.insertItem(item)
        repository.dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, if (inExcluded) excluded.id else ordinary.id))
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(id, 3, 3, status = status, attempts = attempts,
            lastErrorClass = "SyntheticTransient", lastErrorTime = 123L))
        val texts = if (completeEmbeddings) listOf("Synthetic $id. cached description.", "local chemistry", "cached OCR")
            else listOf("local chemistry page")
        val documents = texts.mapIndexed { index, text ->
            SearchDocumentEntity("doc_${id}_3_$index", id, index, textContent = text,
                embedding = if (completeEmbeddings) List(768) { "0.1" }.joinToString(",") else null)
        }
        repository.dao.insertSearchDocuments(documents)
        seededDocuments[id] = documents
        return item
    }

    private suspend fun assertLocalState(item: ItemEntity, expectedAttempts: Int = 2) {
        val current = repository.dao.getItemById(item.id)!!
        assertEquals(3, current.localIndexVersion)
        assertEquals("cached OCR", current.extractedText)
        assertEquals(item.aiDescription, current.aiDescription)
        assertTrue(repository.dao.searchFts("chemistry").any { it.id == item.id })
        val documents = repository.dao.getSearchDocumentsForItem(item.id)
        assertEquals(seededDocuments.getValue(item.id), documents)
        val job = repository.dao.getAnalysisJob(item.id)!!
        assertEquals(expectedAttempts, job.attempts)
        assertEquals("SyntheticTransient", job.lastErrorClass)
        assertEquals(123L, job.lastErrorTime)
    }

    private fun liveWork(itemId: String) = manager.getWorkInfosForUniqueWork("ai_job_$itemId").get().count { !it.state.isFinished }

    private suspend fun awaitLiveWork(itemId: String, count: Int) {
        withTimeoutOrNull(5_000) { while (liveWork(itemId) != count) delay(10) }
        assertEquals(count, liveWork(itemId))
    }

    private suspend fun awaitJob(itemId: String, status: String) {
        withTimeoutOrNull(5_000) { while (repository.dao.getAnalysisJob(itemId)?.status != status) {
            mainDispatcher.scheduler.runCurrent()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            delay(10)
        } }
        assertEquals("AI lifecycle after membership change for $itemId", status, repository.dao.getAnalysisJob(itemId)?.status)
    }

    @Test fun overlappingSharesWaitForExplicitConsumptionInArrivalOrder() {
        val first = com.example.model.SharePayload(text = "First synthetic share")
        val second = com.example.model.SharePayload(uris = listOf("content://synthetic/second"))
        viewModel.setIncomingShare(first)
        viewModel.setIncomingShare(second)
        assertEquals(first, viewModel.incomingShare.value)
        viewModel.setIncomingShare(null)
        assertEquals(second, viewModel.incomingShare.value)
        viewModel.setIncomingShare(null)
        assertNull(viewModel.incomingShare.value)
    }

    @Test fun savingAShareAdvancesToTheNextQueuedPreview() {
        application.captureState.finish("Reset synthetic test")
        val first = com.example.model.SharePayload(text = "First synthetic saved share")
        val second = com.example.model.SharePayload(text = "Second synthetic pending share")
        try {
            viewModel.setIncomingShare(first)
            viewModel.setIncomingShare(second)
            viewModel.confirmShare(emptyList())
            assertEquals(second, viewModel.incomingShare.value)
        } finally { application.captureState.finish("Reset synthetic test") }
    }

    @Test fun secondCollectionDeleteWaitsUntilTheFirstUndoIsResolved() = runBlocking {
        viewModel.deleteCollection(ordinary.id, 60_000)
        withTimeout(5_000) { while (viewModel.pendingCollectionDeletion.value == null) delay(10) }
        val first = viewModel.pendingCollectionDeletion.value!!
        viewModel.deleteCollection(excluded.id, 60_000)
        delay(100)
        assertEquals(first, viewModel.pendingCollectionDeletion.value)
        assertNotNull(repository.dao.getAllCollections().find { it.id == excluded.id })
        viewModel.undoCollectionDeletion(first.operationId)
        withTimeout(5_000) { while (viewModel.pendingCollectionDeletion.value != null) delay(10) }
        viewModel.deleteCollection(excluded.id, 60_000)
        withTimeout(5_000) { while (viewModel.pendingCollectionDeletion.value == null) delay(10) }
        assertEquals(excluded.id, viewModel.pendingCollectionDeletion.value!!.collectionId)
    }
}
