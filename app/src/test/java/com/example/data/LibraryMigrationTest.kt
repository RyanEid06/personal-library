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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryMigrationTest {

    @Test
    fun testMigrationFrom2To3PreservesDataAndAddsColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "test_migration_2_3.db")
        dbFile.delete()

        // Create DB manually at schema v2
        val helperConfig = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("test_migration_2_3.db")
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

        writableDb.close()
        dbFile.delete()
    }
}
