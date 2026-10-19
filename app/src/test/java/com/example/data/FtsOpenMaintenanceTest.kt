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
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FtsOpenMaintenanceTest {
    private lateinit var context: Context
    private lateinit var db: LibraryRoomDatabase
    private val name = "synthetic-fts-${UUID.randomUUID()}.db"
    private fun open() = Room.databaseBuilder(context, LibraryRoomDatabase::class.java, name)
        // sqlite4java's legacy Windows backend cannot reopen WAL files reliably.
        // The fixture keeps real persisted SQLite/FTS behavior with rollback journaling.
        .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
        .allowMainThreadQueries().addCallback(DATABASE_CALLBACK).build()

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = open()
    }
    @After fun close() { db.close(); context.deleteDatabase(name) }
    private fun scalar(sql: String): Long = db.openHelper.writableDatabase.query(sql).use { it.moveToFirst(); it.getLong(0) }

    @Test fun legacyTriggerRepairDoesNotHideSqlFailures() = runBlocking {
        db.libraryDao().insertItem(ItemEntity("kept", "birthday", "", "TEXT"))
        val sqlDb = db.openHelper.writableDatabase
        sqlDb.execSQL("CREATE TRIGGER IF NOT EXISTS items_fts_update AFTER UPDATE ON items BEGIN SELECT 1; END")
        var failure: String? = null
        val observed = object : androidx.sqlite.db.SupportSQLiteDatabase by sqlDb {
            override fun execSQL(sql: String) {
                try { sqlDb.execSQL(sql) } catch (error: Exception) {
                    failure = "$sql: ${error.javaClass.name}: ${error.message}"
                    throw error
                }
            }
        }
        DATABASE_CALLBACK.onOpen(observed)
        assertNull("FTS maintenance must not swallow a failed SQL operation", failure)
        assertEquals(0L, scalar("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='items_fts_update'"))
        assertEquals("kept", db.libraryDao().searchFts("birthday*").single().id)
    }

    @Test fun unchangedOpenDoesNotRewriteSchemaOrRebuildIndex() = runBlocking {
        db.libraryDao().insertItem(ItemEntity("kept", "birthday", "", "TEXT"))
        val schemaBefore = scalar("PRAGMA schema_version")
        val changesBefore = scalar("SELECT total_changes()")
        DATABASE_CALLBACK.onOpen(db.openHelper.writableDatabase)
        assertEquals("No trigger DDL on an unchanged open", schemaBefore, scalar("PRAGMA schema_version"))
        assertEquals("No FTS rebuild on an unchanged open", changesBefore, scalar("SELECT total_changes()"))
        assertEquals("kept", db.libraryDao().searchFts(SearchQuery.fts("birthday")!!).single().id)
    }

    @Test fun failedRebuildRollsBackRepairAndPropagatesForSafeRetry() = runBlocking {
        db.libraryDao().insertItem(ItemEntity("kept", "birthday", "", "TEXT"))
        val sqlDb = db.openHelper.writableDatabase
        sqlDb.execSQL("CREATE TRIGGER items_fts_update AFTER UPDATE ON items BEGIN SELECT 1; END")
        val failing = object : androidx.sqlite.db.SupportSQLiteDatabase by sqlDb {
            override fun execSQL(sql: String) {
                if (sql.contains("VALUES('rebuild')")) throw android.database.sqlite.SQLiteException("Synthetic rebuild failure")
                sqlDb.execSQL(sql)
            }
        }
        assertThrows(android.database.sqlite.SQLiteException::class.java) { DATABASE_CALLBACK.onOpen(failing) }
        assertEquals("Repair DDL must roll back with a failed rebuild", 1L,
            scalar("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='items_fts_update'"))
        DATABASE_CALLBACK.onOpen(sqlDb)
        assertEquals(0L, scalar("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='items_fts_update'"))
        assertEquals("kept", db.libraryDao().searchFts("birthday*").single().id)
    }

    @Test fun alteredCanonicalTriggerIsRepairedEvenWithoutLegacyTriggers() = runBlocking {
        db.libraryDao().insertItem(ItemEntity("changed", "oldword", "", "TEXT"))
        val sqlDb = db.openHelper.writableDatabase
        sqlDb.execSQL("DROP TRIGGER room_fts_content_sync_items_fts_AFTER_UPDATE")
        sqlDb.execSQL("CREATE TRIGGER room_fts_content_sync_items_fts_AFTER_UPDATE AFTER UPDATE ON items BEGIN SELECT 1; END")
        sqlDb.execSQL("UPDATE items SET title='changedword' WHERE id='changed'")
        assertTrue(db.libraryDao().searchFts("changedword*").isEmpty())
        db.close(); db = open()
        val dao = db.libraryDao()
        assertEquals("changed", dao.searchFts("changedword*").single().id)
        assertTrue(dao.searchFts("oldword*").isEmpty())
        dao.updateItem(dao.getItemById("changed")!!.copy(title = "nextword"))
        assertTrue(dao.searchFts("changedword*").isEmpty())
        assertEquals("changed", dao.searchFts("nextword*").single().id)
        db.openHelper.writableDatabase.execSQL("INSERT INTO items_fts(items_fts) VALUES('integrity-check')")
    }

    @Test fun persistedReopenMaintainsInsertUpdateReplaceDeleteAndTrashCorrectness() = runBlocking {
        db.libraryDao().insertItem(ItemEntity("kept", "oldword", "", "TEXT"))
        db.close(); db = open()
        val dao = db.libraryDao()
        dao.updateItem(dao.getItemById("kept")!!.copy(title = "freshword"))
        assertTrue(dao.searchFts("oldword*").isEmpty())
        assertEquals("kept", dao.searchFts("freshword*").single().id)
        dao.insertItem(ItemEntity("kept", "replacement", "", "TEXT"))
        assertTrue(dao.searchFts("freshword*").isEmpty())
        assertEquals("kept", dao.searchFts("replacement*").single().id)
        dao.insertItem(ItemEntity("new", "inserted", "", "TEXT"))
        assertEquals("new", dao.searchFts("inserted*").single().id)
        dao.moveToTrash("new", 1)
        assertTrue(dao.searchFts("inserted*").isEmpty())
        dao.restoreFromTrash("new")
        assertEquals("new", dao.searchFts("inserted*").single().id)
        dao.deleteItemPermanently("kept")
        assertTrue(dao.searchFts("replacement*").isEmpty())
        db.close(); db = open()
        assertEquals("new", db.libraryDao().searchFts("inserted*").single().id)
        assertTrue(db.libraryDao().searchFts("replacement*").isEmpty())
        db.openHelper.writableDatabase.execSQL("INSERT INTO items_fts(items_fts) VALUES('integrity-check')")
    }

    @Test fun reopeningRepairsMissingRoomAndLegacyTriggersAndPreviouslyMissedWrites() = runBlocking {
        db.libraryDao().insertItem(ItemEntity("repair", "oldword", "", "TEXT"))
        val sqlDb = db.openHelper.writableDatabase
        val triggers = sqlDb.query("SELECT name FROM sqlite_master WHERE type='trigger' AND tbl_name='items'").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        // Synthetic old installation with no working content sync and one obsolete custom trigger.
        triggers.forEach { sqlDb.execSQL("DROP TRIGGER `$it`") }
        sqlDb.execSQL("CREATE TRIGGER items_fts_update AFTER UPDATE ON items BEGIN SELECT 1; END")
        sqlDb.execSQL("UPDATE items SET title='repairedword' WHERE id='repair'")
        assertTrue(db.libraryDao().searchFts("repairedword*").isEmpty())
        db.close(); db = open()
        val dao = db.libraryDao()
        assertEquals("repair", dao.searchFts("repairedword*").single().id)
        assertTrue(dao.searchFts("oldword*").isEmpty())
        dao.updateItem(dao.getItemById("repair")!!.copy(title = "nextword"))
        assertTrue(dao.searchFts("repairedword*").isEmpty())
        assertEquals("repair", dao.searchFts("nextword*").single().id)
        dao.deleteItemPermanently("repair")
        assertTrue(dao.searchFts("nextword*").isEmpty())
        db.openHelper.writableDatabase.execSQL("INSERT INTO items_fts(items_fts) VALUES('integrity-check')")
    }
}
