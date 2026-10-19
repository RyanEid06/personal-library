package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personallibrary.core.model.SearchQuery
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
class FtsSearchRegressionTest {
    private lateinit var db: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var context: Context
    private val dao get() = db.libraryDao()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("personal_library_settings", 0).edit().clear()
            .putBoolean("ai_paused", true).commit()
        db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java)
            .addCallback(DATABASE_CALLBACK).build()
        repository = LibraryRepository(context, db)
    }

    @After fun close() = db.close()

    private suspend fun insertPrefixFixtures() {
        listOf("b", "bi", "bir", "birth", "birthday", "bike", "biology", "apple").forEachIndexed { index, word ->
            dao.insertItem(ItemEntity(word, word, "", "TEXT", createdAt = index.toLong()))
        }
    }

    private val expectedPrefixes = listOf(
        "b" to setOf("b", "bi", "bir", "birth", "birthday", "bike", "biology"),
        "bi" to setOf("bi", "bir", "birth", "birthday", "bike", "biology"),
        "bir" to setOf("bir", "birth", "birthday"),
        "birth" to setOf("birth", "birthday"),
        "birthday" to setOf("birthday")
    )

    @Test fun directFtsProgressivelyNarrowsEvenWhenAnExactShortWordExists() = runBlocking {
        insertPrefixFixtures()
        for ((query, expected) in expectedPrefixes) {
            assertEquals(query, expected, dao.searchFts(SearchQuery.fts(query)!!).map { it.id }.toSet())
            assertEquals(query, expected, dao.searchFtsWithCollections(SearchQuery.fts(query)!!).map { it.item.id }.toSet())
        }
    }

    @Test fun repositoryDoesNotLosePrefixMatchesBehindNonemptyExactFtsResults() = runBlocking {
        insertPrefixFixtures()
        for ((query, expected) in expectedPrefixes) {
            assertEquals(query, expected, repository.search(query).map { it.id }.toSet())
            assertEquals(query, expected, repository.searchWithCollections(query).map { it.item.id }.toSet())
        }
    }

    @Test fun everyIndexedFieldSupportsLocalPrefixesIncludingExtractedPdfAndOcrText() = runBlocking {
        val base = ItemEntity("base", "Unrelated", "", "DOCUMENT")
        listOf(
            base.copy(id = "title", title = "birthday"),
            base.copy(id = "body", content = "birthday WhatsApp note"),
            base.copy(id = "filename", originalFileName = "birthday.pdf"),
            base.copy(id = "pdf", extractedText = "PDF birthday excerpt"),
            base.copy(id = "ocr", kind = "PHOTO", extractedText = "OCR birthday handwriting"),
            base.copy(id = "description", aiDescription = "birthday celebration"),
            base.copy(id = "concepts", aiConcepts = "birthday event")
        ).forEach { dao.insertItem(it) }
        val expected = setOf("title", "body", "filename", "pdf", "ocr", "description", "concepts")
        assertEquals(expected, dao.searchFts(SearchQuery.fts("bir")!!).map { it.id }.toSet())
        assertEquals(expected, repository.search("bir").map { it.id }.toSet())
    }

    @Test fun excludedItemsAndCollectionsRemainLocallySearchableWithoutAiConfiguration() = runBlocking {
        dao.insertCollection(CollectionEntity("private", "Synthetic private collection"))
        dao.insertItem(ItemEntity("excluded-item", "birthday", "", "TEXT"))
        dao.insertItem(ItemEntity("excluded-collection", "birthday", "", "TEXT"))
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("excluded-collection", "private"))
        context.getSharedPreferences("personal_library_settings", 0).edit()
            .putStringSet("excluded_items", setOf("excluded-item"))
            .putStringSet("excluded_collections", setOf("private")).commit()
        assertEquals(setOf("excluded-item", "excluded-collection"), repository.search("bir").map { it.id }.toSet())
        assertEquals(setOf("excluded-item", "excluded-collection"), repository.searchWithCollections("bir").map { it.item.id }.toSet())
        assertTrue(dao.getPendingAnalysisJobs().isEmpty())
    }

    @Test fun editsTrashRestoreAndPermanentDeletionDoNotLeaveGhostMatches() = runBlocking {
        val item = ItemEntity("edited", "birthday", "oldword", "TEXT")
        dao.insertItem(item)
        dao.updateItem(item.copy(title = "bicycle", content = "freshword"))
        assertTrue(dao.searchFts(SearchQuery.fts("birth")!!).isEmpty())
        assertTrue(dao.searchFts(SearchQuery.fts("oldw")!!).isEmpty())
        assertEquals("edited", dao.searchFts(SearchQuery.fts("bicy")!!).single().id)
        dao.moveToTrash(item.id, 1)
        assertTrue(repository.search("bicy").isEmpty())
        dao.restoreFromTrash(item.id)
        assertEquals("edited", repository.search("bicy").single().id)
        dao.deleteItemPermanently(item.id)
        assertTrue(dao.searchFts(SearchQuery.fts("bicy")!!).isEmpty())
        db.openHelper.writableDatabase.execSQL("INSERT INTO items_fts(items_fts) VALUES('integrity-check')")
    }

    @Test fun operatorsAndQuotesCannotBroadenOrBreakMultiwordPrefixQueries() = runBlocking {
        dao.insertItem(ItemEntity("match", "birthday candor organic", "درجات", "TEXT"))
        dao.insertItem(ItemEntity("other", "bicycle", "", "TEXT"))
        val query = SearchQuery.fts("\"bir\" OR (can*) AND org^ NOT title:درج")!!
        // The column-like text is a literal extra token, never a column selector.
        assertTrue(dao.searchFts(query).isEmpty())
        val safe = SearchQuery.fts("\"bir\" OR (can*) AND org^ NOT درج")!!
        assertEquals(listOf("match"), dao.searchFts(safe).map { it.id })
    }
}
