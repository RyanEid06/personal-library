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

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = PersonalLibraryApp::class)
class CollectionAiMembershipTest {
    private lateinit var application: PersonalLibraryApp
    private lateinit var store: ViewModelStore
    private lateinit var viewModel: MainViewModel
    private val seededDocuments = mutableMapOf<String, List<SearchDocumentEntity>>()
    private val repository get() = viewModel.repository
    private val prefs get() = application.getSharedPreferences("personal_library_settings", 0)
    private val manager get() = WorkManager.getInstance(application)
    private val excluded = CollectionEntity("excluded", "Synthetic private collection")
    private val ordinary = CollectionEntity("ordinary", "Synthetic ordinary collection")

    @Before fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        prefs.edit().clear().putBoolean("ai_paused", true)
            .putStringSet("excluded_collections", setOf(excluded.id)).commit()
        application.getSharedPreferences("device_identity_prefs", 0).edit().clear().commit()
        setAiTestNetwork(application, unmetered = true)
        WorkManagerTestInitHelper.initializeTestWorkManager(application,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        Dispatchers.setMain(UnconfinedTestDispatcher())
        store = ViewModelStore()
        viewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[MainViewModel::class.java]
        repository.dao.insertCollection(excluded)
        repository.dao.insertCollection(ordinary)
    }

    @After fun tearDown() {
        store.clear()
        manager.cancelAllWork().result.get()
        Dispatchers.resetMain()
        for (name in listOf("personal_library_settings", "device_identity_prefs", "ai_gateway_prefs")) {
            application.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test fun singleAddCancelsQueuedAiWithoutRemovingLocalSearch() = runBlocking {
        val item = seed("single-add", "QUEUED")
        prefs.edit().putBoolean("ai_paused", false).commit()
        viewModel.localIndexer.enqueueDurableAiJob(item.id)
        assertEquals(1, liveWork(item.id))

        viewModel.setItemCollections(item.id, listOf(ordinary.id, excluded.id))

        awaitJob(item.id, "EXCLUDED")
        assertEquals(setOf(ordinary.id, excluded.id), repository.dao.getCollectionIdsForItem(item.id).toSet())
        assertEquals(0, liveWork(item.id))
        assertLocalState(item)
    }

    @Test fun singleRemoveQueuesCachedTextWithoutReextractingOrResettingAttempts() = runBlocking {
        val item = seed("single-remove", "EXCLUDED", cached = true, inExcluded = true)
        prefs.edit().putBoolean("ai_paused", false).commit()

        viewModel.setItemCollections(item.id, listOf(ordinary.id))

        awaitJob(item.id, "QUEUED")
        assertEquals(listOf(ordinary.id), repository.dao.getCollectionIdsForItem(item.id))
        assertTrue(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
        awaitLiveWork(item.id, 1)
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

    @Test fun bulkRemoveResumesInitialAiButKeepsIndependentExclusions() = runBlocking {
        val initial = seed("bulk-remove-initial", "EXCLUDED", inExcluded = true)
        val individuallyExcluded = seed("bulk-remove-private", "EXCLUDED", inExcluded = true)
        val otherCollection = seed("bulk-remove-other", "EXCLUDED", inExcluded = true)
        repository.dao.insertCollection(CollectionEntity("other-private", "Other private collection"))
        repository.dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(otherCollection.id, "other-private"))
        prefs.edit().putBoolean("ai_paused", false).putStringSet("excluded_items", setOf(individuallyExcluded.id))
            .putStringSet("excluded_collections", setOf(excluded.id, "other-private")).commit()
        val items = listOf(initial, individuallyExcluded, otherCollection)

        assertNull(viewModel.changeSelectedCollections(items.map { it.id }.toSet(), setOf(excluded.id), add = false))

        assertEquals("QUEUED", repository.dao.getAnalysisJob(initial.id)!!.status)
        assertFalse(repository.dao.getAnalysisJob(initial.id)!!.metadataOnly)
        assertEquals(1, liveWork(initial.id))
        for (item in listOf(individuallyExcluded, otherCollection)) {
            assertEquals("EXCLUDED", repository.dao.getAnalysisJob(item.id)!!.status)
            assertEquals(0, liveWork(item.id))
        }
        items.forEach { assertLocalState(it) }
    }

    @Test fun deletingExcludedCollectionReconcilesFormerMembersAndReusesCompleteEmbeddings() = runBlocking {
        val missing = seed("delete-missing", "EXCLUDED", cached = true, inExcluded = true)
        val complete = seed("delete-complete", "EXCLUDED", cached = true, inExcluded = true, completeEmbeddings = true)
        val exhausted = seed("delete-exhausted", "EXCLUDED", cached = true, inExcluded = true, attempts = 4)
        prefs.edit().putBoolean("ai_paused", false).commit()

        viewModel.deleteCollection(excluded.id)

        awaitJob(missing.id, "QUEUED")
        awaitJob(complete.id, "COMPLETED")
        awaitJob(exhausted.id, "NEEDS_RETRY")
        assertFalse(repository.dao.getAllCollections().any { it.id == excluded.id })
        assertTrue(repository.dao.getAllCollections().any { it.id == ordinary.id })
        assertTrue(repository.dao.getAnalysisJob(missing.id)!!.metadataOnly)
        awaitLiveWork(missing.id, 1)
        assertEquals(0, liveWork(complete.id))
        assertEquals(0, liveWork(exhausted.id))
        assertEquals("READY", repository.dao.getItemById(complete.id)!!.aiStatus)
        for (item in listOf(missing, complete, exhausted)) {
            assertEquals(listOf(ordinary.id), repository.dao.getCollectionIdsForItem(item.id))
            assertLocalState(item, expectedAttempts = if (item.id == exhausted.id) 4 else 2)
        }
    }

    @Test fun failedBulkMembershipChangeLeavesEligibilityAndLocalSearchIntact() = runBlocking {
        val item = seed("failed-bulk", "EXCLUDED", inExcluded = true)

        val error = viewModel.changeSelectedCollections(setOf(item.id), setOf("missing-collection"), add = true)

        assertFalse(error.isNullOrBlank())
        assertEquals("EXCLUDED", repository.dao.getAnalysisJob(item.id)!!.status)
        assertEquals(setOf(ordinary.id, excluded.id), repository.dao.getCollectionIdsForItem(item.id).toSet())
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
        repository.dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, ordinary.id))
        if (inExcluded) repository.dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, excluded.id))
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
        withTimeoutOrNull(5_000) { while (repository.dao.getAnalysisJob(itemId)?.status != status) delay(10) }
        assertEquals("AI lifecycle after membership change for $itemId", status, repository.dao.getAnalysisJob(itemId)?.status)
    }
}
