package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "items")
data class ItemEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val kind: String,
    val isFavorite: Boolean = false,
    val isTrash: Boolean = false,
    val trashTimestamp: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceDate: Long? = null,
    val reminderTime: Long? = null,
    val isReminderDone: Boolean = false,
    val isReminderExact: Boolean = true,
    val contentHash: String? = null,
    val originalFileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val extractedText: String? = null,
    val localIndexCoverage: String = "PENDING",
    val localIndexVersion: Int = 1,
    val aiDescription: String? = null,
    val aiConcepts: String? = null,
    val aiStatus: String = "READY"
)

@Entity(tableName = "assets")
data class AssetEntity(
    @PrimaryKey val hash: String,
    val relativePath: String,
    val sizeBytes: Long,
    val originalName: String,
    val mimeType: String,
    val kind: String,
    val refCount: Int = 1
)

@Entity(tableName = "collections")
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconName: String = "folder",
    val colorHex: String = "#3B82F6",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "item_collection_cross_ref",
    primaryKeys = ["itemId", "collectionId"],
    indices = [Index(value = ["collectionId"])]
)
data class ItemCollectionCrossRef(
    val itemId: String,
    val collectionId: String
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val title: String,
    val dueMillis: Long,
    val isDone: Boolean = false,
    val isExact: Boolean = true
)

@Entity(
    tableName = "search_documents",
    indices = [Index(value = ["itemId"])]
)
data class SearchDocumentEntity(
    @PrimaryKey val id: String,
    val itemId: String,
    val chunkIndex: Int = 0,
    val pageNumber: Int? = null,
    val textContent: String,
    val embedding: String? = null,
    val embeddingDimension: Int = 768,
    val modelVersion: String = "gemini-embedding-2",
    val coverage: String = "COMPLETE"
)

@Entity(tableName = "analysis_jobs")
data class AnalysisJobEntity(
    @PrimaryKey val itemId: String,
    val expectedContentVersion: Int = 1,
    val indexVersion: Int = 1,
    val modelVersion: String = "gemini-3.8-flash",
    val status: String = "QUEUED",
    val attempts: Int = 0,
    val lastErrorClass: String? = null,
    val lastErrorTime: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Fts4(contentEntity = ItemEntity::class)
@Entity(tableName = "items_fts")
data class ItemFtsEntity(
    val title: String,
    val content: String,
    val originalFileName: String?,
    val extractedText: String?,
    val aiDescription: String?,
    val aiConcepts: String?
)

data class ItemWithCollections(
    @Embedded val item: ItemEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = ItemCollectionCrossRef::class,
            parentColumn = "itemId",
            entityColumn = "collectionId"
        )
    )
    val collections: List<CollectionEntity>
)

@Dao
interface LibraryDao {

    @Query("SELECT * FROM items WHERE isTrash = 0 ORDER BY createdAt DESC")
    fun observeActiveItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE isTrash = 1 ORDER BY trashTimestamp DESC")
    fun observeTrashItems(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE isTrash = 0")
    suspend fun getAllActiveItems(): List<ItemEntity>

    @Query("SELECT * FROM items")
    suspend fun getAllItemsIncludingTrash(): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ItemEntity)

    @Update
    suspend fun updateItem(item: ItemEntity)

    @Query("UPDATE items SET isFavorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: String, isFavorite: Boolean)

    @Query("UPDATE items SET isTrash = 1, trashTimestamp = :timestamp WHERE id = :id")
    suspend fun moveToTrash(id: String, timestamp: Long)

    @Query("UPDATE items SET isTrash = 0, trashTimestamp = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: String)

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun deleteItemPermanently(id: String)

    @Query("UPDATE items SET reminderTime = :reminderTime, isReminderDone = 0, isReminderExact = :isExact WHERE id = :id")
    suspend fun setReminder(id: String, reminderTime: Long?, isExact: Boolean = true)

    @Query("UPDATE items SET isReminderDone = :isDone WHERE id = :id")
    suspend fun setReminderDone(id: String, isDone: Boolean)

    @Query("UPDATE items SET extractedText = :extractedText, localIndexCoverage = :coverage, localIndexVersion = :version WHERE id = :id")
    suspend fun updateExtractedText(id: String, extractedText: String?, coverage: String, version: Int)

    @Query("UPDATE items SET aiDescription = :aiDescription, aiConcepts = :aiConcepts, aiStatus = :aiStatus WHERE id = :id")
    suspend fun updateAiMetadata(id: String, aiDescription: String?, aiConcepts: String?, aiStatus: String)

    @Query("UPDATE items SET aiStatus = :aiStatus WHERE id = :id")
    suspend fun updateAiStatus(id: String, aiStatus: String)

    // Assets
    @Query("SELECT * FROM assets WHERE hash = :hash LIMIT 1")
    suspend fun getAssetByHash(hash: String): AssetEntity?

    @Query("SELECT * FROM assets")
    suspend fun getAllAssets(): List<AssetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAsset(asset: AssetEntity)

    @Update
    suspend fun updateAsset(asset: AssetEntity)

    @Query("DELETE FROM assets WHERE hash = :hash")
    suspend fun deleteAsset(hash: String)

    // Collections
    @Query("SELECT * FROM collections ORDER BY createdAt ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections ORDER BY createdAt ASC")
    suspend fun getAllCollections(): List<CollectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCollection(collection: CollectionEntity)

    @Query("DELETE FROM collections WHERE id = :collectionId")
    suspend fun deleteCollection(collectionId: String)

    // Many-to-Many Collections
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItemCollectionCrossRef(crossRef: ItemCollectionCrossRef)

    @Query("DELETE FROM item_collection_cross_ref WHERE itemId = :itemId AND collectionId = :collectionId")
    suspend fun removeItemFromCollection(itemId: String, collectionId: String)

    @Query("DELETE FROM item_collection_cross_ref WHERE itemId = :itemId")
    suspend fun removeAllCollectionsForItem(itemId: String)

    @Query("DELETE FROM item_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun removeAllItemsForCollection(collectionId: String)

    @Query("SELECT collectionId FROM item_collection_cross_ref WHERE itemId = :itemId")
    suspend fun getCollectionIdsForItem(itemId: String): List<String>

    @Transaction
    @Query("SELECT * FROM items WHERE isTrash = 0 ORDER BY createdAt DESC")
    fun observeItemsWithCollections(): Flow<List<ItemWithCollections>>

    @Transaction
    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getItemWithCollections(id: String): ItemWithCollections?

    // Full-Text Search via FTS4
    @Query("""
        SELECT items.* FROM items
        JOIN items_fts ON items.rowid = items_fts.docid
        WHERE items_fts MATCH :sanitizedFtsQuery AND items.isTrash = 0
        ORDER BY items.createdAt DESC
    """)
    suspend fun searchFts(sanitizedFtsQuery: String): List<ItemEntity>

    @Transaction
    @Query("""
        SELECT items.* FROM items
        JOIN items_fts ON items.rowid = items_fts.docid
        WHERE items_fts MATCH :sanitizedFtsQuery AND items.isTrash = 0
        ORDER BY items.createdAt DESC
    """)
    suspend fun searchFtsWithCollections(sanitizedFtsQuery: String): List<ItemWithCollections>

    // Fallback Substring LIKE Search
    @Query("""
        SELECT * FROM items
        WHERE isTrash = 0 AND (
            title LIKE :pattern OR
            content LIKE :pattern OR
            originalFileName LIKE :pattern OR
            extractedText LIKE :pattern OR
            aiDescription LIKE :pattern OR
            aiConcepts LIKE :pattern
        )
        ORDER BY createdAt DESC
    """)
    suspend fun searchLike(pattern: String): List<ItemEntity>

    @Transaction
    @Query("""
        SELECT * FROM items
        WHERE isTrash = 0 AND (
            title LIKE :pattern OR
            content LIKE :pattern OR
            originalFileName LIKE :pattern OR
            extractedText LIKE :pattern OR
            aiDescription LIKE :pattern OR
            aiConcepts LIKE :pattern
        )
        ORDER BY createdAt DESC
    """)
    suspend fun searchLikeWithCollections(pattern: String): List<ItemWithCollections>

    // Reminders
    @Query("SELECT * FROM reminders WHERE isDone = 0 ORDER BY dueMillis ASC")
    suspend fun getPendingReminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE itemId = :itemId LIMIT 1")
    suspend fun getReminderByItemId(itemId: String): ReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ReminderEntity)

    @Query("UPDATE reminders SET isDone = :isDone WHERE id = :id")
    suspend fun updateReminderStatus(id: String, isDone: Boolean)

    @Query("UPDATE reminders SET dueMillis = :newDueMillis WHERE id = :id")
    suspend fun snoozeReminder(id: String, newDueMillis: Long)

    @Query("DELETE FROM reminders WHERE itemId = :itemId")
    suspend fun deleteReminderByItemId(itemId: String)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun deleteReminderById(id: String)

    // Search Documents & Stored Semantic Embeddings
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchDocument(doc: SearchDocumentEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchDocuments(docs: List<SearchDocumentEntity>)

    @Query("SELECT * FROM search_documents WHERE itemId = :itemId")
    suspend fun getSearchDocumentsForItem(itemId: String): List<SearchDocumentEntity>

    @Query("SELECT * FROM search_documents")
    suspend fun getAllSearchDocuments(): List<SearchDocumentEntity>

    @Query("DELETE FROM search_documents WHERE itemId = :itemId")
    suspend fun deleteSearchDocumentsForItem(itemId: String)

    // Analysis Jobs
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateAnalysisJob(job: AnalysisJobEntity)

    @Query("SELECT * FROM analysis_jobs WHERE itemId = :itemId LIMIT 1")
    suspend fun getAnalysisJob(itemId: String): AnalysisJobEntity?

    @Query("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'NEEDS_RETRY') ORDER BY updatedAt ASC")
    suspend fun getPendingAnalysisJobs(): List<AnalysisJobEntity>

    @Query("DELETE FROM analysis_jobs WHERE itemId = :itemId")
    suspend fun deleteAnalysisJob(itemId: String)

    @Query("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
    suspend fun rebuildFts()
}

val DATABASE_CALLBACK = object : androidx.room.RoomDatabase.Callback() {
    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        super.onCreate(db)
        setupFts(db)
    }

    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        super.onOpen(db)
        setupFts(db)
    }

    private fun setupFts(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        try {
            db.execSQL("DROP TRIGGER IF EXISTS items_fts_insert")
            db.execSQL("DROP TRIGGER IF EXISTS items_fts_bu")
            db.execSQL("DROP TRIGGER IF EXISTS items_fts_au")
            db.execSQL("DROP TRIGGER IF EXISTS items_fts_delete")
            db.execSQL("DROP TRIGGER IF EXISTS items_fts_update")

            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS items_fts_insert AFTER INSERT ON items BEGIN
                    INSERT INTO items_fts(docid, title, content, originalFileName, extractedText, aiDescription, aiConcepts)
                    VALUES (new.rowid, new.title, new.content, new.originalFileName, new.extractedText, new.aiDescription, new.aiConcepts);
                END;
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS items_fts_bu BEFORE UPDATE ON items BEGIN
                    DELETE FROM items_fts WHERE docid = old.rowid;
                END;
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS items_fts_au AFTER UPDATE ON items BEGIN
                    INSERT INTO items_fts(docid, title, content, originalFileName, extractedText, aiDescription, aiConcepts)
                    VALUES (new.rowid, new.title, new.content, new.originalFileName, new.extractedText, new.aiDescription, new.aiConcepts);
                END;
            """.trimIndent())
            db.execSQL("""
                CREATE TRIGGER IF NOT EXISTS items_fts_delete BEFORE DELETE ON items BEGIN
                    DELETE FROM items_fts WHERE docid = old.rowid;
                END;
            """.trimIndent())
            db.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
        } catch (_: Exception) {}
    }
}

val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS search_documents (
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
        db.execSQL("CREATE INDEX IF NOT EXISTS index_search_documents_itemId ON search_documents (itemId)")
    }
}

val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN extractedText TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE items ADD COLUMN localIndexCoverage TEXT NOT NULL DEFAULT 'PENDING'")
        db.execSQL("ALTER TABLE items ADD COLUMN localIndexVersion INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE items ADD COLUMN isReminderExact INTEGER NOT NULL DEFAULT 1")

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS analysis_jobs (
                itemId TEXT NOT NULL PRIMARY KEY,
                expectedContentVersion INTEGER NOT NULL,
                indexVersion INTEGER NOT NULL,
                modelVersion TEXT NOT NULL,
                status TEXT NOT NULL,
                attempts INTEGER NOT NULL,
                lastErrorClass TEXT,
                lastErrorTime INTEGER,
                updatedAt INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("DROP TABLE IF EXISTS items_fts")
        db.execSQL("""
            CREATE VIRTUAL TABLE IF NOT EXISTS items_fts USING FTS4(
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                originalFileName TEXT,
                extractedText TEXT,
                aiDescription TEXT,
                aiConcepts TEXT,
                content=items
            )
        """.trimIndent())
    }
}

@Database(
    entities = [
        ItemEntity::class,
        AssetEntity::class,
        CollectionEntity::class,
        ItemCollectionCrossRef::class,
        ReminderEntity::class,
        ItemFtsEntity::class,
        SearchDocumentEntity::class,
        AnalysisJobEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class LibraryRoomDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
