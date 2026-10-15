package com.example.storage

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.model.CollectionItem
import com.example.model.ItemKind
import com.example.model.LibraryItem

class LibraryDatabaseHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        const val DATABASE_NAME = "personal_library.db"
        const val DATABASE_VERSION = 1

        const val TABLE_ITEMS = "items"
        const val TABLE_COLLECTIONS = "collections"
        const val TABLE_ITEMS_FTS = "items_fts"

        // Column names
        const val COL_ID = "id"
        const val COL_TITLE = "title"
        const val COL_CONTENT = "content"
        const val COL_KIND = "kind"
        const val COL_IS_FAVORITE = "is_favorite"
        const val COL_IS_TRASH = "is_trash"
        const val COL_TRASH_TIMESTAMP = "trash_timestamp"
        const val COL_CREATED_AT = "created_at"
        const val COL_SOURCE_DATE = "source_date"
        const val COL_REMINDER_TIME = "reminder_time"
        const val COL_IS_REMINDER_DONE = "is_reminder_done"
        const val COL_COLLECTION_ID = "collection_id"
        const val COL_LOCAL_FILE_PATH = "local_file_path"
        const val COL_ORIGINAL_FILE_NAME = "original_file_name"
        const val COL_MIME_TYPE = "mime_type"
        const val COL_FILE_SIZE_BYTES = "file_size_bytes"
        const val COL_AI_DESCRIPTION = "ai_description"
        const val COL_AI_CONCEPTS = "ai_concepts"
        const val COL_AI_STATUS = "ai_status"

        // Collection columns
        const val COL_COLLECTION_NAME = "name"
        const val COL_COLLECTION_ICON = "icon_name"
        const val COL_COLLECTION_COLOR = "color_hex"
    }

    override fun onCreate(db: SQLiteDatabase) {
        val createCollectionsTable = """
            CREATE TABLE $TABLE_COLLECTIONS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_COLLECTION_NAME TEXT NOT NULL,
                $COL_COLLECTION_ICON TEXT NOT NULL,
                $COL_COLLECTION_COLOR TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            );
        """.trimIndent()

        val createItemsTable = """
            CREATE TABLE $TABLE_ITEMS (
                $COL_ID TEXT PRIMARY KEY,
                $COL_TITLE TEXT NOT NULL,
                $COL_CONTENT TEXT NOT NULL,
                $COL_KIND TEXT NOT NULL,
                $COL_IS_FAVORITE INTEGER NOT NULL DEFAULT 0,
                $COL_IS_TRASH INTEGER NOT NULL DEFAULT 0,
                $COL_TRASH_TIMESTAMP INTEGER,
                $COL_CREATED_AT INTEGER NOT NULL,
                $COL_SOURCE_DATE INTEGER,
                $COL_REMINDER_TIME INTEGER,
                $COL_IS_REMINDER_DONE INTEGER NOT NULL DEFAULT 0,
                $COL_COLLECTION_ID TEXT,
                $COL_LOCAL_FILE_PATH TEXT,
                $COL_ORIGINAL_FILE_NAME TEXT,
                $COL_MIME_TYPE TEXT,
                $COL_FILE_SIZE_BYTES INTEGER NOT NULL DEFAULT 0,
                $COL_AI_DESCRIPTION TEXT,
                $COL_AI_CONCEPTS TEXT,
                $COL_AI_STATUS TEXT NOT NULL DEFAULT 'READY'
            );
        """.trimIndent()

        // Full-Text Search virtual table for instant keyword search
        val createFtsTable = """
            CREATE VIRTUAL TABLE $TABLE_ITEMS_FTS USING fts4(
                content="$TABLE_ITEMS",
                $COL_TITLE,
                $COL_CONTENT,
                $COL_ORIGINAL_FILE_NAME,
                $COL_AI_DESCRIPTION,
                $COL_AI_CONCEPTS
            );
        """.trimIndent()

        db.execSQL(createCollectionsTable)
        db.execSQL(createItemsTable)
        db.execSQL(createFtsTable)

        // Prepopulate default collections
        val now = System.currentTimeMillis()
        val defaultCollections = listOf(
            ContentValues().apply {
                put(COL_ID, "col_random_links")
                put(COL_COLLECTION_NAME, "Random Links")
                put(COL_COLLECTION_ICON, "chat")
                put(COL_COLLECTION_COLOR, "#10B981")
                put(COL_CREATED_AT, now)
            },
            ContentValues().apply {
                put(COL_ID, "col_university")
                put(COL_COLLECTION_NAME, "University & Grades")
                put(COL_COLLECTION_ICON, "school")
                put(COL_COLLECTION_COLOR, "#3B82F6")
                put(COL_CREATED_AT, now + 1)
            },
            ContentValues().apply {
                put(COL_ID, "col_important")
                put(COL_COLLECTION_NAME, "Important")
                put(COL_COLLECTION_ICON, "star")
                put(COL_COLLECTION_COLOR, "#F59E0B")
                put(COL_CREATED_AT, now + 2)
            },
            ContentValues().apply {
                put(COL_ID, "col_memes")
                put(COL_COLLECTION_NAME, "Memes & Stickers")
                put(COL_COLLECTION_ICON, "mood")
                put(COL_COLLECTION_COLOR, "#EC4899")
                put(COL_CREATED_AT, now + 3)
            }
        )

        for (col in defaultCollections) {
            db.insert(TABLE_COLLECTIONS, null, col)
        }
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ITEMS_FTS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_ITEMS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_COLLECTIONS")
        onCreate(db)
    }

    fun insertItem(item: LibraryItem): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, item.id)
            put(COL_TITLE, item.title)
            put(COL_CONTENT, item.content)
            put(COL_KIND, item.kind.name)
            put(COL_IS_FAVORITE, if (item.isFavorite) 1 else 0)
            put(COL_IS_TRASH, if (item.isTrash) 1 else 0)
            put(COL_TRASH_TIMESTAMP, item.trashTimestamp)
            put(COL_CREATED_AT, item.createdAt)
            put(COL_SOURCE_DATE, item.sourceDate)
            put(COL_REMINDER_TIME, item.reminderTime)
            put(COL_IS_REMINDER_DONE, if (item.isReminderDone) 1 else 0)
            put(COL_COLLECTION_ID, item.collectionId)
            put(COL_LOCAL_FILE_PATH, item.localFilePath)
            put(COL_ORIGINAL_FILE_NAME, item.originalFileName)
            put(COL_MIME_TYPE, item.mimeType)
            put(COL_FILE_SIZE_BYTES, item.fileSizeBytes)
            put(COL_AI_DESCRIPTION, item.aiDescription)
            put(COL_AI_CONCEPTS, item.aiConcepts)
            put(COL_AI_STATUS, item.aiStatus)
        }
        val rowId = db.insertWithOnConflict(TABLE_ITEMS, null, values, SQLiteDatabase.CONFLICT_REPLACE)

        // Insert into FTS
        val ftsValues = ContentValues().apply {
            put("docid", rowId)
            put(COL_TITLE, item.title)
            put(COL_CONTENT, item.content)
            put(COL_ORIGINAL_FILE_NAME, item.originalFileName ?: "")
            put(COL_AI_DESCRIPTION, item.aiDescription ?: "")
            put(COL_AI_CONCEPTS, item.aiConcepts ?: "")
        }
        db.insert(TABLE_ITEMS_FTS, null, ftsValues)
        return rowId
    }

    fun updateItem(item: LibraryItem) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TITLE, item.title)
            put(COL_CONTENT, item.content)
            put(COL_KIND, item.kind.name)
            put(COL_IS_FAVORITE, if (item.isFavorite) 1 else 0)
            put(COL_IS_TRASH, if (item.isTrash) 1 else 0)
            put(COL_TRASH_TIMESTAMP, item.trashTimestamp)
            put(COL_SOURCE_DATE, item.sourceDate)
            put(COL_REMINDER_TIME, item.reminderTime)
            put(COL_IS_REMINDER_DONE, if (item.isReminderDone) 1 else 0)
            put(COL_COLLECTION_ID, item.collectionId)
            put(COL_AI_DESCRIPTION, item.aiDescription)
            put(COL_AI_CONCEPTS, item.aiConcepts)
            put(COL_AI_STATUS, item.aiStatus)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(item.id))

        // Update FTS table
        rebuildFtsForItem(db, item)
    }

    private fun rebuildFtsForItem(db: SQLiteDatabase, item: LibraryItem) {
        val cursor = db.rawQuery("SELECT rowid FROM $TABLE_ITEMS WHERE $COL_ID = ?", arrayOf(item.id))
        if (cursor.moveToFirst()) {
            val rowId = cursor.getLong(0)
            db.delete(TABLE_ITEMS_FTS, "docid = ?", arrayOf(rowId.toString()))
            val ftsValues = ContentValues().apply {
                put("docid", rowId)
                put(COL_TITLE, item.title)
                put(COL_CONTENT, item.content)
                put(COL_ORIGINAL_FILE_NAME, item.originalFileName ?: "")
                put(COL_AI_DESCRIPTION, item.aiDescription ?: "")
                put(COL_AI_CONCEPTS, item.aiConcepts ?: "")
            }
            db.insert(TABLE_ITEMS_FTS, null, ftsValues)
        }
        cursor.close()
    }

    fun getItem(id: String): LibraryItem? {
        val db = readableDatabase
        val cursor = db.query(TABLE_ITEMS, null, "$COL_ID = ?", arrayOf(id), null, null, null)
        var item: LibraryItem? = null
        if (cursor.moveToFirst()) {
            item = cursorToLibraryItem(cursor)
        }
        cursor.close()
        return item
    }

    fun getAllItems(includeTrash: Boolean = false): List<LibraryItem> {
        val db = readableDatabase
        val selection = if (includeTrash) null else "$COL_IS_TRASH = 0"
        val cursor = db.query(TABLE_ITEMS, null, selection, null, null, null, "$COL_CREATED_AT DESC")
        val list = mutableListOf<LibraryItem>()
        while (cursor.moveToNext()) {
            list.add(cursorToLibraryItem(cursor))
        }
        cursor.close()
        return list
    }

    fun getTrashItems(): List<LibraryItem> {
        val db = readableDatabase
        val cursor = db.query(TABLE_ITEMS, null, "$COL_IS_TRASH = 1", null, null, null, "$COL_TRASH_TIMESTAMP DESC")
        val list = mutableListOf<LibraryItem>()
        while (cursor.moveToNext()) {
            list.add(cursorToLibraryItem(cursor))
        }
        cursor.close()
        return list
    }

    fun moveToTrash(id: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_TRASH, 1)
            put(COL_TRASH_TIMESTAMP, System.currentTimeMillis())
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(id))
    }

    fun restoreFromTrash(id: String) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_TRASH, 0)
            putNull(COL_TRASH_TIMESTAMP)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(id))
    }

    fun permanentDelete(id: String): String? {
        val item = getItem(id) ?: return null
        val db = writableDatabase
        val cursor = db.rawQuery("SELECT rowid FROM $TABLE_ITEMS WHERE $COL_ID = ?", arrayOf(id))
        if (cursor.moveToFirst()) {
            val rowId = cursor.getLong(0)
            db.delete(TABLE_ITEMS_FTS, "docid = ?", arrayOf(rowId.toString()))
        }
        cursor.close()
        db.delete(TABLE_ITEMS, "$COL_ID = ?", arrayOf(id))
        return item.localFilePath
    }

    fun emptyTrash(): List<String> {
        val trashItems = getTrashItems()
        val paths = mutableListOf<String>()
        val db = writableDatabase
        for (item in trashItems) {
            item.localFilePath?.let { paths.add(it) }
            val cursor = db.rawQuery("SELECT rowid FROM $TABLE_ITEMS WHERE $COL_ID = ?", arrayOf(item.id))
            if (cursor.moveToFirst()) {
                val rowId = cursor.getLong(0)
                db.delete(TABLE_ITEMS_FTS, "docid = ?", arrayOf(rowId.toString()))
            }
            cursor.close()
            db.delete(TABLE_ITEMS, "$COL_ID = ?", arrayOf(item.id))
        }
        return paths
    }

    fun setFavorite(id: String, isFavorite: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_FAVORITE, if (isFavorite) 1 else 0)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(id))
    }

    fun setReminder(id: String, reminderTime: Long?) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_REMINDER_TIME, reminderTime)
            put(COL_IS_REMINDER_DONE, 0)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(id))
    }

    fun markReminderDone(id: String, done: Boolean) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_IS_REMINDER_DONE, if (done) 1 else 0)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(id))
    }

    fun updateCollection(itemId: String, collectionId: String?) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_COLLECTION_ID, collectionId)
        }
        db.update(TABLE_ITEMS, values, "$COL_ID = ?", arrayOf(itemId))
    }

    fun search(query: String, kind: ItemKind? = null, collectionId: String? = null): List<LibraryItem> {
        val trimmed = query.trim()
        val db = readableDatabase
        val itemsMap = mutableMapOf<String, LibraryItem>()

        if (trimmed.isNotBlank()) {
            // First try Full-Text Search
            val sanitized = trimmed.replace("\"", "").replace("'", "")
            val ftsQuery = """
                SELECT i.* FROM $TABLE_ITEMS i
                JOIN $TABLE_ITEMS_FTS f ON i.rowid = f.docid
                WHERE $TABLE_ITEMS_FTS MATCH ? AND i.$COL_IS_TRASH = 0
            """.trimIndent()

            try {
                val cursor = db.rawQuery(ftsQuery, arrayOf("$sanitized*"))
                while (cursor.moveToNext()) {
                    val item = cursorToLibraryItem(cursor)
                    itemsMap[item.id] = item
                }
                cursor.close()
            } catch (_: Exception) {
                // Fallback to LIKE query if FTS syntax error
            }

            // Also search using LIKE for partial substrings in title, content, or filename
            val likePattern = "%$trimmed%"
            val likeQuery = """
                SELECT * FROM $TABLE_ITEMS
                WHERE $COL_IS_TRASH = 0 AND (
                    $COL_TITLE LIKE ? OR
                    $COL_CONTENT LIKE ? OR
                    $COL_ORIGINAL_FILE_NAME LIKE ? OR
                    $COL_AI_DESCRIPTION LIKE ? OR
                    $COL_AI_CONCEPTS LIKE ?
                )
            """.trimIndent()
            val likeCursor = db.rawQuery(likeQuery, arrayOf(likePattern, likePattern, likePattern, likePattern, likePattern))
            while (likeCursor.moveToNext()) {
                val item = cursorToLibraryItem(likeCursor)
                itemsMap[item.id] = item
            }
            likeCursor.close()
        } else {
            // Return all active items
            val all = getAllItems(includeTrash = false)
            for (i in all) itemsMap[i.id] = i
        }

        // Apply filters in-memory
        return itemsMap.values
            .filter { item ->
                (kind == null || item.kind == kind) &&
                (collectionId == null || item.collectionId == collectionId)
            }
            .sortedByDescending { it.createdAt }
    }

    fun getAllCollections(): List<CollectionItem> {
        val db = readableDatabase
        val cursor = db.query(TABLE_COLLECTIONS, null, null, null, null, null, "$COL_CREATED_AT ASC")
        val list = mutableListOf<CollectionItem>()
        while (cursor.moveToNext()) {
            list.add(
                CollectionItem(
                    id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
                    name = cursor.getString(cursor.getColumnIndexOrThrow(COL_COLLECTION_NAME)),
                    iconName = cursor.getString(cursor.getColumnIndexOrThrow(COL_COLLECTION_ICON)),
                    colorHex = cursor.getString(cursor.getColumnIndexOrThrow(COL_COLLECTION_COLOR)),
                    createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT))
                )
            )
        }
        cursor.close()
        return list
    }

    fun insertCollection(col: CollectionItem) {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, col.id)
            put(COL_COLLECTION_NAME, col.name)
            put(COL_COLLECTION_ICON, col.iconName)
            put(COL_COLLECTION_COLOR, col.colorHex)
            put(COL_CREATED_AT, col.createdAt)
        }
        db.insertWithOnConflict(TABLE_COLLECTIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun deleteCollection(collectionId: String) {
        val db = writableDatabase
        // Detach items from this collection first
        val values = ContentValues().apply { putNull(COL_COLLECTION_ID) }
        db.update(TABLE_ITEMS, values, "$COL_COLLECTION_ID = ?", arrayOf(collectionId))
        db.delete(TABLE_COLLECTIONS, "$COL_ID = ?", arrayOf(collectionId))
    }

    private fun cursorToLibraryItem(cursor: Cursor): LibraryItem {
        return LibraryItem(
            id = cursor.getString(cursor.getColumnIndexOrThrow(COL_ID)),
            title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE)),
            content = cursor.getString(cursor.getColumnIndexOrThrow(COL_CONTENT)),
            kind = ItemKind.valueOf(cursor.getString(cursor.getColumnIndexOrThrow(COL_KIND))),
            isFavorite = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_FAVORITE)) == 1,
            isTrash = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_TRASH)) == 1,
            trashTimestamp = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_TRASH_TIMESTAMP))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_TRASH_TIMESTAMP)),
            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow(COL_CREATED_AT)),
            sourceDate = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_SOURCE_DATE))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_SOURCE_DATE)),
            reminderTime = if (cursor.isNull(cursor.getColumnIndexOrThrow(COL_REMINDER_TIME))) null else cursor.getLong(cursor.getColumnIndexOrThrow(COL_REMINDER_TIME)),
            isReminderDone = cursor.getInt(cursor.getColumnIndexOrThrow(COL_IS_REMINDER_DONE)) == 1,
            collectionId = cursor.getString(cursor.getColumnIndexOrThrow(COL_COLLECTION_ID)),
            localFilePath = cursor.getString(cursor.getColumnIndexOrThrow(COL_LOCAL_FILE_PATH)),
            originalFileName = cursor.getString(cursor.getColumnIndexOrThrow(COL_ORIGINAL_FILE_NAME)),
            mimeType = cursor.getString(cursor.getColumnIndexOrThrow(COL_MIME_TYPE)),
            fileSizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(COL_FILE_SIZE_BYTES)),
            aiDescription = cursor.getString(cursor.getColumnIndexOrThrow(COL_AI_DESCRIPTION)),
            aiConcepts = cursor.getString(cursor.getColumnIndexOrThrow(COL_AI_CONCEPTS)),
            aiStatus = cursor.getString(cursor.getColumnIndexOrThrow(COL_AI_STATUS))
        )
    }
}
