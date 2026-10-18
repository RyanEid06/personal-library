package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryMigrationTest {

    @Test fun actualVersion4DatabaseOpensThroughRoom5WithoutLosingRowsOrFts() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration4_${java.util.UUID.randomUUID()}.db"
        val schema = Json.parseToJsonElement(File("schemas/com.example.data.LibraryRoomDatabase/4.json").readText()).jsonObject.getValue("database").jsonObject
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context).name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        schema.getValue("entities").jsonArray.forEach { value ->
                            val entity = value.jsonObject
                            val table = entity.getValue("tableName").jsonPrimitive.content
                            db.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            entity["indices"]?.jsonArray?.forEach { index ->
                                db.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                            }
                        }
                        schema.getValue("setupQueries").jsonArray.forEach { db.execSQL(it.jsonPrimitive.content) }
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build())
        helper.writableDatabase.apply {
            execSQL("INSERT INTO collections VALUES('legacy','Random Links','chat','#ffffff',1)")
            execSQL("INSERT INTO collections VALUES('other','Other','folder','#000000',2)")
            execSQL("INSERT INTO items(id,title,content,kind,isFavorite,isTrash,createdAt,isReminderDone,isReminderExact,isReminderDateOnly,fileSizeBytes,localIndexCoverage,localIndexVersion,aiStatus) VALUES('item','MigrationTitle','MigrationBody','TEXT',0,0,123,0,1,0,0,'COMPLETE',7,'READY')")
            execSQL("INSERT INTO item_collection_cross_ref VALUES('item','legacy')")
            execSQL("INSERT INTO search_documents(id,itemId,chunkIndex,textContent,embeddingDimension,modelVersion,coverage) VALUES('doc','item',0,'Preserved extraction',768,'existing','COMPLETE')")
            execSQL("INSERT INTO assets VALUES('hash','assets/hash',7,'original.txt','text/plain','DOCUMENT',1)")
            execSQL("INSERT INTO reminders VALUES('rem','item','Reminder',1800000000000,0,1,0,NULL)")
            execSQL("INSERT INTO analysis_jobs VALUES('item',7,7,'existing','QUEUED',2,NULL,NULL,123)")
        }
        helper.close()
        val migrated = Room.databaseBuilder(context, LibraryRoomDatabase::class.java, name)
            .addMigrations(MIGRATION_4_5).addCallback(DATABASE_CALLBACK).build()
        try {
            val dao = migrated.libraryDao()
            assertEquals("RANDOM_LINKS", dao.getAllCollections().single { it.id == "legacy" }.appRole)
            assertNull(dao.getAllCollections().single { it.id == "other" }.appRole)
            assertEquals("MigrationBody", dao.getItemById("item")!!.content)
            assertEquals(7, dao.getItemById("item")!!.localIndexVersion)
            assertEquals(listOf("legacy"), dao.getCollectionIdsForItem("item"))
            assertEquals("Preserved extraction", dao.getSearchDocumentsForItem("item").single().textContent)
            assertEquals("original.txt", dao.getAssetByHash("hash")!!.originalName)
            assertEquals(1800000000000L, dao.getReminderByItemId("item")!!.dueMillis)
            assertEquals(2, dao.getAnalysisJob("item")!!.attempts)
            assertEquals("item", dao.searchFts("MigrationBody*").single().id)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }

    @Test
    fun testMigrationFrom2To3PreservesDataAndAddsColumns() = runMigrations(false)

    @Test fun testMigrationFrom3To4PreservesOwnerDataAndAddsDateOnlyReminders() = runMigrations(true)

    private fun runMigrations(throughV4: Boolean) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "migration_${java.util.UUID.randomUUID()}.db"
        val dbFile = context.getDatabasePath(databaseName)
        dbFile.delete()

        // Create DB manually at schema v2
        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(databaseName)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL("""
                        CREATE TABLE items (
                            id TEXT NOT NULL PRIMARY KEY,
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            isFavorite INTEGER NOT NULL DEFAULT 0,
                            isTrash INTEGER NOT NULL DEFAULT 0,
                            trashTimestamp INTEGER,
                            createdAt INTEGER NOT NULL,
                            sourceDate INTEGER,
                            reminderTime INTEGER,
                            isReminderDone INTEGER NOT NULL DEFAULT 0,
                            contentHash TEXT,
                            originalFileName TEXT,
                            mimeType TEXT,
                            fileSizeBytes INTEGER NOT NULL DEFAULT 0,
                            aiDescription TEXT,
                            aiConcepts TEXT,
                            aiStatus TEXT NOT NULL DEFAULT 'READY'
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE assets (
                            hash TEXT NOT NULL PRIMARY KEY,
                            relativePath TEXT NOT NULL,
                            sizeBytes INTEGER NOT NULL,
                            originalName TEXT NOT NULL,
                            mimeType TEXT NOT NULL,
                            kind TEXT NOT NULL,
                            refCount INTEGER NOT NULL DEFAULT 1
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE collections (
                            id TEXT NOT NULL PRIMARY KEY,
                            name TEXT NOT NULL,
                            iconName TEXT NOT NULL,
                            colorHex TEXT NOT NULL,
                            createdAt INTEGER NOT NULL
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE item_collection_cross_ref (
                            itemId TEXT NOT NULL,
                            collectionId TEXT NOT NULL,
                            PRIMARY KEY (itemId, collectionId)
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE reminders (
                            id TEXT NOT NULL PRIMARY KEY,
                            itemId TEXT NOT NULL,
                            title TEXT NOT NULL,
                            dueMillis INTEGER NOT NULL,
                            isDone INTEGER NOT NULL DEFAULT 0,
                            isExact INTEGER NOT NULL DEFAULT 1
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE TABLE search_documents (
                            id TEXT NOT NULL PRIMARY KEY,
                            itemId TEXT NOT NULL,
                            chunkIndex INTEGER NOT NULL,
                            pageNumber INTEGER,
                            textContent TEXT NOT NULL,
                            embedding TEXT,
                            embeddingDimension INTEGER NOT NULL,
                            modelVersion TEXT NOT NULL,
                            coverage TEXT NOT NULL
                        )
                    """.trimIndent())

                    db.execSQL("""
                        CREATE VIRTUAL TABLE items_fts USING FTS4(
                            title TEXT NOT NULL,
                            content TEXT NOT NULL,
                            originalFileName TEXT,
                            aiDescription TEXT,
                            aiConcepts TEXT,
                            content=items
                        )
                    """.trimIndent())
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val openHelper = FrameworkSQLiteOpenHelperFactory().create(helperConfig)
        val writableDb = openHelper.writableDatabase

        // Insert initial test record into items at v2
        writableDb.execSQL("""
            INSERT INTO items (id, title, content, kind, createdAt, isFavorite, isTrash, isReminderDone, fileSizeBytes, aiStatus)
            VALUES ('item_v2_1', 'V2 Title', 'Owner Authored Text', 'TEXT', 1000, 0, 0, 0, 100, 'READY')
        """.trimIndent())

        // Run MIGRATION_2_3
        MIGRATION_2_3.migrate(writableDb)

        // Verify newly added columns exist in items table and preserved data
        val cursor = writableDb.query("SELECT id, title, content, extractedText, localIndexCoverage, localIndexVersion FROM items WHERE id = 'item_v2_1'")
        assertTrue("Cursor should return row", cursor.moveToFirst())
        assertEquals("item_v2_1", cursor.getString(0))
        assertEquals("V2 Title", cursor.getString(1))
        assertEquals("Owner Authored Text", cursor.getString(2))
        assertNull("extractedText should default to null", cursor.getString(3))
        assertEquals("PENDING", cursor.getString(4))
        assertEquals(1, cursor.getInt(5))
        cursor.close()

        // Verify analysis_jobs table was created
        writableDb.execSQL("""
            INSERT INTO analysis_jobs (itemId, expectedContentVersion, indexVersion, modelVersion, status, attempts, updatedAt)
            VALUES ('item_v2_1', 1, 1, 'gemini-3.8-flash', 'QUEUED', 0, 12345)
        """.trimIndent())

        val jobCursor = writableDb.query("SELECT itemId, status FROM analysis_jobs WHERE itemId = 'item_v2_1'")
        assertTrue(jobCursor.moveToFirst())
        assertEquals("QUEUED", jobCursor.getString(1))
        jobCursor.close()

        // Verify items_fts can be rebuilt and queried with extractedText
        writableDb.execSQL("""
            UPDATE items SET extractedText = 'Machine OCR content' WHERE id = 'item_v2_1'
        """.trimIndent())
        writableDb.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")

        val ftsCursor = writableDb.query("SELECT docid FROM items_fts WHERE items_fts MATCH 'Machine'")
        assertTrue("FTS should match on newly added extractedText column", ftsCursor.moveToFirst())
        ftsCursor.close()

        if (throughV4) {
            writableDb.execSQL("INSERT INTO reminders(id,itemId,title,dueMillis,isDone,isExact) VALUES('rem-v3','item_v2_1','Review',1800000000000,0,1)")
            MIGRATION_3_4.migrate(writableDb)
            writableDb.query("SELECT content,extractedText,isReminderDateOnly,reminderLocalDate FROM items WHERE id='item_v2_1'").use {
                assertTrue(it.moveToFirst())
                assertEquals("Owner Authored Text", it.getString(0))
                assertEquals("Machine OCR content", it.getString(1))
                assertEquals(0, it.getInt(2))
                assertNull(it.getString(3))
            }
            writableDb.query("SELECT dueMillis,isExact,isDateOnly,localDate FROM reminders WHERE id='rem-v3'").use {
                assertTrue(it.moveToFirst())
                assertEquals(1800000000000L, it.getLong(0))
                assertEquals(1, it.getInt(1))
                assertEquals(0, it.getInt(2))
                assertNull(it.getString(3))
            }
            writableDb.execSQL("UPDATE reminders SET isDateOnly=1,localDate='2026-12-01' WHERE id='rem-v3'")
            writableDb.query("SELECT isDateOnly,localDate FROM reminders WHERE id='rem-v3'").use {
                assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)); assertEquals("2026-12-01", it.getString(1))
            }
        }

        writableDb.close()
        openHelper.close()
        dbFile.delete()
    }
}
