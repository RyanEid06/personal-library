package com.example.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.example.model.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InteractiveAiRetrievalTest {
    private lateinit var db: LibraryRoomDatabase
    private val dao get() = db.libraryDao()
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("personal_library_settings", 0)
    private lateinit var retrieval: InteractiveAiRetrieval

    @Before fun setup() {
        prefs.edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryRoomDatabase::class.java)
            .addCallback(DATABASE_CALLBACK).build()
        retrieval = InteractiveAiRetrieval(dao, prefs)
    }
    @After fun close() = db.close()

    private fun note(id: String, time: Long = 0L) = ItemEntity(id, "birthday $id", "synthetic text", "TEXT", createdAt = time)

    @Test fun explicitAskWorksWhileBackgroundAiPaused() = runBlocking {
        dao.insertItem(note("candidate"))
        prefs.edit().putBoolean("ai_paused", true).commit()
        val snapshot = retrieval.load(LocalSearchContext("birthday"))
        assertEquals(listOf("candidate"), snapshot.items.map { it.item.id })
        prefs.edit().putBoolean("ai_paused", false).commit()
        retrieval.requireCurrent(snapshot) { true }
    }

    @Test fun expandedSynonymsRetrieveOldRyanBdayBefore160RecentRowsWithoutVectors() = runBlocking {
        dao.insertCollection(CollectionEntity("current", "Current"))
        dao.insertCollection(CollectionEntity("private", "Private"))
        dao.insertItem(note("ryan-bday", 0).copy(title = "Ryan", content = "bday is November 6"))
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("ryan-bday", "current"))
        repeat(180) { index ->
            val id = "recent-$index"
            dao.insertItem(note(id, 1000L + index).copy(title = "Birthday", content = "Unrelated generic record"))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, "current"))
        }
        dao.insertItem(note("outside", 9999).copy(title = "Ryan birthday bday", content = "private"))
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("outside", "private"))
        val snapshot = retrieval.load(LocalSearchContext("When is Ryan's birthday", collectionId = "current"), listOf("bday"))
        assertEquals("ryan-bday", snapshot.items.first().item.id)
        assertEquals(InteractiveAiRetrieval.MAX_ITEMS, snapshot.items.size)
        assertTrue(snapshot.documents.isEmpty())
        assertTrue(snapshot.items.none { it.item.id == "outside" })
        val selected = selectAnswerEvidence("When is Ryan's birthday?", snapshot, emptyList(), listOf("bday"))
        assertTrue(selected.evidence.any { it.text.contains("November 6") })
    }

    @Test fun aliasOnlyMatchBeats180NewerFallbackItemsAndEarlyUnrelatedChunks() = runBlocking {
        dao.insertItem(note("alias", 0).copy(title = "Ryan", content = "", kind = "DOCUMENT", localIndexVersion = 7,
            extractedText = "Ryan bday is November 6"))
        repeat(180) { dao.insertItem(note("fallback-$it", 1000L + it).copy(title = "Unrelated", content = "Generic record")) }
        repeat(8) { chunk -> dao.insertSearchDocument(SearchDocumentEntity("local_alias_7_$chunk", "alias", chunk,
            pageNumber = chunk + 1, textContent = "Unrelated early page")) }
        dao.insertSearchDocument(SearchDocumentEntity("local_alias_7_99", "alias", 99,
            pageNumber = 100, textContent = "Ryan bday is November 6"))
        val snapshot = retrieval.load(LocalSearchContext("birthday"), listOf("bday"))
        assertEquals("alias", snapshot.items.first().item.id)
        assertEquals(100, snapshot.documents.first().pageNumber)
        assertEquals(InteractiveAiRetrieval.MAX_ITEMS, snapshot.items.size)
        val selected = selectAnswerEvidence("birthday", snapshot, emptyList(), listOf("bday"))
        assertEquals(100, selected.evidence.first().pageNumber)
        assertTrue(selected.evidence.first().text.contains("November 6"))
    }

    @Test fun privacyCapturedBeforePlanCannotChangeBeforeCandidateSelection() = runBlocking {
        dao.insertItem(note("private-later"))
        val privacy = retrieval.capturePrivacy()
        prefs.edit().putStringSet("excluded_items", setOf("private-later")).commit()
        assertBlocked { retrieval.load(LocalSearchContext("birthday"), expectedPrivacy = privacy) }
    }

    @Test fun excludedAndFilteredRowsCannotConsumeCandidateLimit() = runBlocking {
        dao.insertCollection(CollectionEntity("private", "Secret synthetic collection"))
        dao.insertCollection(CollectionEntity("visible", "Visible"))
        repeat(180) { index ->
            val id = "private-$index"
            dao.insertItem(note(id, 180L + index % 20))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, "private"))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, "visible"))
            dao.insertItem(note("wrong-kind-$index", 180L + index % 20).copy(kind = "PHOTO"))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("wrong-kind-$index", "visible"))
            dao.insertItem(note("wrong-date-$index", 3000L + index))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("wrong-date-$index", "visible"))
        }
        repeat(170) { index ->
            val id = "eligible-$index"
            dao.insertItem(note(id, index.toLong()))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, "visible"))
        }
        prefs.edit().putStringSet("excluded_collections", setOf("private")).commit()
        val snapshot = retrieval.load(LocalSearchContext("bir", kind = ItemKind.TEXT, collectionId = "visible",
            dateFilter = DateFilter.CUSTOM, customStart = 0, customEnd = 200))
        assertEquals(InteractiveAiRetrieval.MAX_ITEMS, snapshot.items.size)
        assertTrue(snapshot.items.all { it.item.id.startsWith("eligible-") })
    }

    @Test fun ftsMatchesHavePriorityOverNewerFallbackAndNamesAreNotRequestMetadata() = runBlocking {
        dao.insertCollection(CollectionEntity("private", "Never transmit this name"))
        dao.insertItem(note("match", 0))
        repeat(180) { dao.insertItem(note("fallback-$it", 1000L + it).copy(title = "unrelated")) }
        val snapshot = retrieval.load(LocalSearchContext("bir"))
        assertEquals("match", snapshot.items.first().item.id)
        assertEquals(InteractiveAiRetrieval.MAX_ITEMS, snapshot.items.size)
        assertTrue(snapshot.knownCollectionNames.isEmpty())
    }

    @Test fun documentsAreBoundedVersionedAndRetainPageProvenance() = runBlocking {
        repeat(170) { index ->
            val id = "candidate-$index"
            dao.insertItem(note(id).copy(localIndexVersion = 7))
            repeat(20) { chunk ->
                dao.insertSearchDocument(SearchDocumentEntity("doc_${id}_7_$chunk", id, chunk,
                    pageNumber = chunk + 1, textContent = "current page", embedding = "0.1"))
                dao.insertSearchDocument(SearchDocumentEntity("doc_${id}_6_$chunk", id, chunk,
                    pageNumber = 999, textContent = "stale version", embedding = "0.1"))
            }
        }
        val snapshot = retrieval.load(LocalSearchContext("bir"))
        assertEquals(InteractiveAiRetrieval.MAX_DOCUMENTS, snapshot.documents.size)
        assertTrue(snapshot.documents.isNotEmpty())
        assertTrue(snapshot.documents.groupBy { it.itemId }.values.all { it.size <= InteractiveAiRetrieval.MAX_DOCUMENTS_PER_ITEM })
        assertTrue(snapshot.documents.all { it.textContent == "current page" && it.pageNumber != 999 })
    }

    @Test fun naturalQuestionRetrievesOlderPartialTermMatchesBeforeRecentFallback() = runBlocking {
        dao.insertItem(note("sarah-note", 0).copy(title = "Sarah birthday", content = "August 19"))
        repeat(180) { dao.insertItem(note("recent-$it", 1000L + it).copy(title = "Unrelated birthday record")) }
        val snapshot = retrieval.load(LocalSearchContext("When is Sarah's birthday?"))
        assertEquals("sarah-note", snapshot.items.first().item.id)
        assertEquals(InteractiveAiRetrieval.MAX_ITEMS, snapshot.items.size)
    }

    @Test fun laterMatchingPageHasPriorityOverEarlierUnrelatedChunks() = runBlocking {
        dao.insertItem(note("pdf").copy(kind = "DOCUMENT", localIndexVersion = 7))
        repeat(12) { chunk ->
            dao.insertSearchDocument(SearchDocumentEntity("doc_pdf_7_$chunk", "pdf", chunk,
                pageNumber = chunk + 1, textContent = "unrelated earlier page", embedding = "0.1"))
        }
        dao.insertSearchDocument(SearchDocumentEntity("local_pdf_7_99", "pdf", 99,
            pageNumber = 93, textContent = "A synthetic birthday receipt on a later page"))
        val snapshot = retrieval.load(LocalSearchContext("Where did I save the birthday receipt?"))
        assertEquals(InteractiveAiRetrieval.MAX_DOCUMENTS_PER_ITEM, snapshot.documents.size)
        assertEquals(93, snapshot.documents.first().pageNumber)
        assertTrue(snapshot.documents.first().textContent.contains("birthday"))
    }

    @Test fun requestGuardRechecksPrefsAndMembershipWithoutWaitingForRoomFlow() = runBlocking {
        dao.insertItem(note("candidate"))
        dao.insertCollection(CollectionEntity("private", "Synthetic private name"))
        val snapshot = retrieval.load(LocalSearchContext("bir"))
        retrieval.requireCurrent(snapshot) { true }
        prefs.edit().putStringSet("excluded_collections", setOf("private")).commit()
        assertBlocked { retrieval.requireCurrent(snapshot) { true } }
        val next = retrieval.load(LocalSearchContext("bir"))
        retrieval.requireCurrent(next) { true }
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("candidate", "private"))
        assertBlocked { retrieval.requireCurrent(next) { true } }
        // The privacy boundary is read-only and preserves ordinary local evidence.
        assertEquals("candidate", dao.searchFts("\"bir*\"").single().id)
    }

    @Test fun everyGuardInvocationRejectsEditTrashDeletionAndGenerationChanges() = runBlocking {
        dao.insertItem(note("candidate"))
        var snapshot = retrieval.load(LocalSearchContext("bir"))
        retrieval.requireCurrent(snapshot) { true }
        assertBlocked { retrieval.requireCurrent(snapshot) { false } }
        dao.updateItem(note("candidate").copy(localIndexVersion = 2))
        assertBlocked { retrieval.requireCurrent(snapshot) { true } }
        snapshot = retrieval.load(LocalSearchContext("bir"))
        dao.moveToTrash("candidate", 1)
        assertBlocked { retrieval.requireCurrent(snapshot) { true } }
        dao.restoreFromTrash("candidate")
        snapshot = retrieval.load(LocalSearchContext("bir"))
        dao.deleteItemPermanently("candidate")
        assertBlocked { retrieval.requireCurrent(snapshot) { true } }
    }

    @Test fun sourceDatesAndComputedCollectionsAreAppliedBeforeFallbackLimit() = runBlocking {
        repeat(180) { dao.insertItem(note("wrong-$it", 1000L + it)) }
        dao.insertItem(note("wanted").copy(sourceDate = 150, isFavorite = true))
        val context = LocalSearchContext("nothing", collectionId = "computed_favorites", basis = DateBasis.SOURCE_DATE,
            dateFilter = DateFilter.CUSTOM, customStart = 100, customEnd = 200)
        assertEquals(listOf("wanted"), retrieval.load(context).items.map { it.item.id })
    }

    @Test fun arbitraryIdsAndLargePrivacySetsUseExactBoundValues() = runBlocking {
        val privateId = "synthetic_'%_,名"
        val publicId = "synthetic_'%_,名_suffix"
        val privateCollection = "collection_'%_,名"
        dao.insertCollection(CollectionEntity(privateCollection, "Private name"))
        dao.insertItem(note(privateId))
        dao.insertItem(note(publicId))
        dao.insertItem(note("member"))
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("member", privateCollection))
        // More exclusions than SQLite's traditional 999 bind ceiling must remain bounded.
        prefs.edit().putStringSet("excluded_items", (0..1100).map { "missing-$it" }.toSet() + privateId)
            .putStringSet("excluded_collections", setOf(privateCollection)).commit()
        val snapshot = retrieval.load(LocalSearchContext("bir"))
        assertEquals(listOf(publicId), snapshot.items.map { it.item.id })
        retrieval.requireCurrent(snapshot) { true }
        assertEquals(3, dao.searchFts("\"bir*\"").size)
    }

    @Test fun computedUnfiledAndDuePredicatesAreAppliedInsideBoundedQuery() = runBlocking {
        dao.insertCollection(CollectionEntity("filed", "Filed"))
        repeat(180) {
            val id = "filed-$it"
            dao.insertItem(note(id, 1000L + it))
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, "filed"))
        }
        dao.insertItem(note("unfiled-due").copy(reminderTime = 100))
        assertEquals(listOf("unfiled-due"), retrieval.load(LocalSearchContext("bir", collectionId = "computed_unfiled"))
            .items.map { it.item.id })
        assertEquals(listOf("unfiled-due"), retrieval.load(LocalSearchContext("bir", collectionId = "computed_due"))
            .items.map { it.item.id })
    }

    @Test fun pauseAndItemExclusionNeverRemoveLocalDocuments() = runBlocking {
        dao.insertItem(note("candidate"))
        val doc = SearchDocumentEntity("local_candidate_1_0", "candidate", pageNumber = 3, textContent = "cached OCR")
        dao.insertSearchDocument(doc)
        val snapshot = retrieval.load(LocalSearchContext("bir"))
        prefs.edit().putBoolean("ai_paused", true).commit()
        retrieval.requireCurrent(snapshot) { true }
        assertEquals(listOf("candidate"), retrieval.load(LocalSearchContext("bir")).items.map { it.item.id })
        prefs.edit().putBoolean("ai_paused", false).putStringSet("excluded_items", setOf("candidate")).commit()
        assertTrue(retrieval.load(LocalSearchContext("bir")).items.isEmpty())
        assertEquals(listOf(doc), dao.getSearchDocumentsForItem("candidate"))
        assertEquals("candidate", dao.searchFts("\"bir*\"").single().id)
    }

    private suspend fun assertBlocked(action: suspend () -> Unit) {
        try { action(); fail("Stale or excluded candidates must block AI") }
        catch (_: com.example.ai.AiGatewayException.RequestBlockedException) { }
    }
}
