package com.example.ui

import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.model.DateFilter
import com.example.model.DateGrouping
import com.example.model.ItemKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LatestLocalSearchTest {
    private fun row(id: String, kind: String = "NOTE", collection: String? = null, saved: Long = 100) =
        ItemWithCollections(
            ItemEntity(id, id, "synthetic birthday evidence", kind, createdAt = saved),
            collection?.let { listOf(CollectionEntity(it, it)) } ?: emptyList()
        )

    @Test fun olderCancellationResistantSearchCannotReplaceLatestQueryOrGroups() = runTest {
        val old = CompletableDeferred<List<ItemWithCollections>>()
        val current = CompletableDeferred<List<ItemWithCollections>>()
        val publications = mutableListOf<LocalSearchResult>()
        var ready = false
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { query ->
            withContext(NonCancellable) { if (query == "bi") old.await() else current.await() }
        }, { ready = false }, { publications += it; ready = true }, { fail("Unexpected error") }, dispatcher, dispatcher)

        runner.refresh(LocalSearchContext(query = "bi", grouping = DateGrouping.YEAR))
        runCurrent()
        runner.refresh(LocalSearchContext(query = "birth", grouping = DateGrouping.DAY))
        assertFalse(ready)
        runCurrent()
        current.complete(listOf(row("latest")))
        runCurrent()
        assertTrue(ready)
        assertEquals(listOf("latest"), publications.last().items.map { it.item.id })
        assertEquals(publications.last().items, publications.last().sections.flatMap { it.items })
        val expectedSections = publications.last().sections
        old.complete(listOf(row("obsolete")))
        runCurrent()
        assertEquals(1, publications.size)
        assertEquals(expectedSections, publications.last().sections)
    }

    @Test fun filterCollectionAndDataChangeInvalidatePendingSearchImmediately() = runTest {
        val pending = mutableListOf<CompletableDeferred<List<ItemWithCollections>>>()
        val publications = mutableListOf<LocalSearchResult>()
        var ready = true
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, {
            val result = CompletableDeferred<List<ItemWithCollections>>()
            pending += result
            withContext(NonCancellable) { result.await() }
        }, { ready = false }, { publications += it; ready = true }, { fail("Unexpected error") }, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "b"))
        runCurrent()
        runner.refresh(LocalSearchContext(query = "b", kind = ItemKind.LINK, collectionId = "chosen", dateFilter = DateFilter.CUSTOM, customStart = 90, customEnd = 110))
        assertFalse(ready)
        runCurrent()
        // A Room source emission replaces both earlier operations, even for the same query.
        runner.refresh(LocalSearchContext(query = "b", kind = ItemKind.LINK, collectionId = "chosen", sourceItems = listOf(row("changed"))))
        assertFalse(ready)
        runCurrent()
        pending[2].complete(listOf(row("keep", "LINK", "chosen"), row("wrong-kind", "NOTE", "chosen"), row("wrong-collection", "LINK", "other")))
        runCurrent()
        assertEquals(listOf("keep"), publications.single().items.map { it.item.id })
        pending[1].complete(listOf(row("older-context", "LINK", "chosen")))
        pending[0].complete(listOf(row("old-query")))
        runCurrent()
        assertTrue(ready)
        assertEquals(1, publications.size)
    }

    @Test fun rapidTypingDebouncesOneLocalQueryAndContextChangesExecuteImmediately() = runTest {
        val queries = mutableListOf<String>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { queries += it; emptyList() }, {}, {}, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "b"), debounceQuery = true)
        runCurrent()
        advanceTimeBy(100)
        runner.refresh(LocalSearchContext(query = "bi"), debounceQuery = true)
        runCurrent()
        advanceTimeBy(149)
        runCurrent()
        assertTrue(queries.isEmpty())
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf("bi"), queries)
        runner.refresh(LocalSearchContext(query = "bi", collectionId = "chosen"))
        runCurrent()
        assertEquals(listOf("bi", "bi"), queries)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(2, queries.size)
    }

    @Test fun failedLocalQueryHasDistinctErrorAndNextQueryRecovers() = runTest {
        var failed = false
        var ready = false
        val publications = mutableListOf<LocalSearchResult>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, {
            if (it == "broken") error("synthetic SQLite failure") else listOf(row("recovered"))
        }, { failed = false; ready = false }, { publications += it; ready = true }, { failed = true }, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "broken"))
        runCurrent()
        assertTrue(failed)
        assertFalse(ready)
        assertTrue(publications.isEmpty())
        runner.refresh(LocalSearchContext(query = "good"))
        assertFalse(failed)
        runCurrent()
        assertTrue(ready)
        assertFalse(failed)
        assertEquals("recovered", publications.single().items.single().item.id)
    }

    @Test fun blankQueryUsesCapturedOfflineItemsWithoutSearchOrAiDependency() = runTest {
        val publications = mutableListOf<LocalSearchResult>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { fail("Blank browse must not query"); emptyList() }, {}, { publications += it }, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(sourceItems = listOf(row("offline"))))
        runCurrent()
        assertEquals("offline", publications.single().items.single().item.id)
    }

    @Test fun typedOfflineQueryUsesOnlyTheSuppliedLocalSearchDependency() = runTest {
        val queries = mutableListOf<String>()
        val publications = mutableListOf<LocalSearchResult>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val runner = LatestLocalSearch(backgroundScope, { queries += it; listOf(row("offline-match")) }, {}, { publications += it }, {}, dispatcher, dispatcher)
        runner.refresh(LocalSearchContext(query = "b"), debounceQuery = true)
        advanceTimeBy(150)
        runCurrent()
        assertEquals(listOf("b"), queries)
        assertEquals("offline-match", publications.single().items.single().item.id)
    }

    @Test fun sharedFilteringHonorsDateRangeAndComputedCollections() {
        val rows = listOf(row("inside", saved = 100), row("outside", saved = 200))
        val result = projectLocalSearch(rows, LocalSearchContext(dateFilter = DateFilter.CUSTOM, customStart = 90, customEnd = 110, collectionId = "computed_unfiled"))
        assertEquals(listOf("inside"), result.items.map { it.item.id })
    }
}
