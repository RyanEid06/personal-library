package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.flow.first
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
class RoomDatabaseAndFtsTest {

    private lateinit var db: LibraryRoomDatabase
    private lateinit var dao: LibraryDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(DATABASE_CALLBACK)
            .build()
        dao = db.libraryDao()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun testInsertAndRetrieveActiveItems() = runBlocking {
        val item = ItemEntity(
            id = "item_1",
            title = "Organic Chemistry Notes",
            content = "Reaction mechanisms and stereochemistry",
            kind = ItemKind.DOCUMENT.name,
            createdAt = 1000L
        )
        dao.insertItem(item)

        val retrieved = dao.getItemById("item_1")
        assertNotNull(retrieved)
        assertEquals("Organic Chemistry Notes", retrieved!!.title)
        assertEquals(ItemKind.DOCUMENT.name, retrieved.kind)
        assertFalse(retrieved.isTrash)
    }

    @Test
    fun testManyToManyCollectionRelationship() = runBlocking {
        val item1 = ItemEntity(id = "item_1", title = "Midterm Exam", content = "94/100", kind = ItemKind.TEXT.name)
        val item2 = ItemEntity(id = "item_2", title = "Lab Report", content = "Titration results", kind = ItemKind.DOCUMENT.name)
        dao.insertItem(item1)
        dao.insertItem(item2)

        val col1 = CollectionEntity(id = "col_chem", name = "Chemistry", iconName = "school", colorHex = "#3B82F6")
        val col2 = CollectionEntity(id = "col_urgent", name = "Urgent", iconName = "priority_high", colorHex = "#EF4444")
        dao.insertCollection(col1)
        dao.insertCollection(col2)

        // item1 belongs to both collections
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("item_1", "col_chem"))
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("item_1", "col_urgent"))
        // item2 belongs only to col_chem
        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef("item_2", "col_chem"))

        val item1WithCols = dao.getItemWithCollections("item_1")
        assertNotNull(item1WithCols)
        assertEquals(2, item1WithCols!!.collections.size)
        assertTrue(item1WithCols.collections.any { it.id == "col_chem" })
        assertTrue(item1WithCols.collections.any { it.id == "col_urgent" })

        // Spec Requirement: Deleting a collection retains original items!
        dao.deleteCollection("col_urgent")
        dao.removeAllItemsForCollection("col_urgent")

        val item1AfterDelete = dao.getItemById("item_1")
        assertNotNull("Deleting a collection must NOT delete the item", item1AfterDelete)

        val item1ColsAfterDelete = dao.getItemWithCollections("item_1")
        assertEquals(1, item1ColsAfterDelete!!.collections.size)
        assertEquals("col_chem", item1ColsAfterDelete.collections.first().id)
    }

    @Test
    fun testTrashAndRestoreLifecycle() = runBlocking {
        val item = ItemEntity(id = "item_trash", title = "Disposable Note", content = "Temporary", kind = ItemKind.TEXT.name)
        dao.insertItem(item)

        var active = dao.observeActiveItems().first()
        assertEquals(1, active.size)

        // Move to trash
        val trashTime = System.currentTimeMillis()
        dao.moveToTrash("item_trash", trashTime)

        active = dao.observeActiveItems().first()
        assertEquals(0, active.size)

        val trashed = dao.observeTrashItems().first()
        assertEquals(1, trashed.size)
        assertEquals("item_trash", trashed.first().id)
        assertEquals(trashTime, trashed.first().trashTimestamp)

        // Restore from trash
        dao.restoreFromTrash("item_trash")
        active = dao.observeActiveItems().first()
        assertEquals(1, active.size)
        assertFalse(active.first().isTrash)
        assertNull(active.first().trashTimestamp)
    }

    @Test
    fun testFtsSearchAndLikeFallback() = runBlocking {
        val item1 = ItemEntity(
            id = "chem_1",
            title = "Chemistry Midterm Syllabus",
            content = "Chapters 1 to 5: thermodynamics, kinetics, and equilibrium",
            kind = ItemKind.DOCUMENT.name,
            originalFileName = "syllabus_2026.pdf",
            aiConcepts = "chemistry, kinetics, exam"
        )
        val item2 = ItemEntity(
            id = "math_1",
            title = "Calculus III Homework",
            content = "Vector fields and Stokes theorem",
            kind = ItemKind.DOCUMENT.name,
            originalFileName = "calc_hw3.pdf",
            aiConcepts = "calculus, vectors, math"
        )
        dao.insertItem(item1)
        dao.insertItem(item2)

        // Test Like Search fallback
        val resultsChem = dao.searchLike("%thermodynamics%")
        assertEquals(1, resultsChem.size)
        assertEquals("chem_1", resultsChem.first().id)

        val resultsExam = dao.searchLike("%exam%")
        assertEquals(1, resultsExam.size)
        assertEquals("chem_1", resultsExam.first().id)

        val resultsPdf = dao.searchLike("%pdf%")
        assertEquals(2, resultsPdf.size)
    }

    @Test
    fun testRemindersPersistence() = runBlocking {
        val reminder = ReminderEntity(
            id = "rem_1",
            itemId = "item_chem",
            title = "Review Chemistry Notes",
            dueMillis = 1800000000000L,
            isDone = false
        )
        dao.insertReminder(reminder)

        val pending = dao.getPendingReminders()
        assertEquals(1, pending.size)
        assertEquals("rem_1", pending.first().id)
        assertEquals("Review Chemistry Notes", pending.first().title)

        dao.deleteReminderById("rem_1")
        val pendingAfter = dao.getPendingReminders()
        assertEquals(0, pendingAfter.size)
    }

    @Test fun unicodeAndOperatorWordsUseActualFtsWithEscapedInput() = runBlocking {
        dao.insertItem(ItemEntity("unicode", "CANDOR ORGANIC", "درجات chemistry", "TEXT"))
        val query = com.personallibrary.core.model.SearchQuery.fts("CANDOR OR \"ORGANIC\" درجات")!!
        assertEquals(listOf("unicode"), dao.searchFts(query).map { it.id })
        assertTrue(dao.searchFts(com.personallibrary.core.model.SearchQuery.fts("missing")!!).isEmpty())
    }

    @Test
    fun testAssetReferenceCounting() = runBlocking {
        val hash = "abcd1234ef5678"
        val asset = AssetEntity(
            hash = hash,
            relativePath = "blobs/ab/$hash",
            sizeBytes = 2048L,
            originalName = "test.jpg",
            mimeType = "image/jpeg",
            kind = ItemKind.PHOTO.name,
            refCount = 1
        )
        dao.insertAsset(asset)

        val loaded = dao.getAssetByHash(hash)
        assertNotNull(loaded)
        assertEquals(1, loaded!!.refCount)

        // Increment ref count
        dao.updateAsset(loaded.copy(refCount = 2))
        val updated = dao.getAssetByHash(hash)
        assertEquals(2, updated!!.refCount)

        dao.deleteAsset(hash)
        assertNull(dao.getAssetByHash(hash))
    }
}
