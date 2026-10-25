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
    val isReminderDateOnly: Boolean = false,
    val reminderLocalDate: String? = null,
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

@Entity(tableName = "collections", indices = [Index(value = ["appRole"], unique = true)])
data class CollectionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val iconName: String = "folder",
    val colorHex: String = "#3B82F6",
    val createdAt: Long = System.currentTimeMillis(),
    val appRole: String? = null
) {
    companion object { const val ROLE_RANDOM_LINKS = "RANDOM_LINKS" }
}

@Entity(
    tableName = "item_collection_cross_ref",
    primaryKeys = ["itemId", "collectionId"],
    indices = [Index(value = ["collectionId"]), Index(value = ["itemId"], unique = true)]
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
    val isExact: Boolean = true,
    val isDateOnly: Boolean = false,
    val localDate: String? = null
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
    val updatedAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val metadataOnly: Boolean = false
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

/** Request-boundary projection: no owner text, vectors or collection names. */
data class AiCandidateState(
    val id: String,
    val localIndexVersion: Int,
    val isTrash: Boolean,
    val kind: String,
    val isFavorite: Boolean,
    val createdAt: Long,
    val sourceDate: Long?,
    val reminderTime: Long?,
    val isReminderDone: Boolean,
    val collectionIds: String?
)

@Dao
interface LibraryDao {

    // The caller constructs only fixed SQL clauses and binds every data value and LIMIT.
    @Transaction
    @RawQuery
    suspend fun getAiCandidates(query: androidx.sqlite.db.SupportSQLiteQuery): List<ItemWithCollections>

    @RawQuery
    suspend fun getAiCandidateDocuments(query: androidx.sqlite.db.SupportSQLiteQuery): List<SearchDocumentEntity>

    @Query("""
        SELECT i.id, i.localIndexVersion, i.isTrash, i.kind, i.isFavorite, i.createdAt,
            i.sourceDate, i.reminderTime, i.isReminderDone,
            (SELECT GROUP_CONCAT(hex(CAST(collectionId AS BLOB))) FROM item_collection_cross_ref
                WHERE itemId = i.id) AS collectionIds
        FROM items i WHERE i.id IN (:itemIds)
    """)
    suspend fun getAiCandidateStates(itemIds: List<String>): List<AiCandidateState>

    @Query("SELECT itemId FROM item_collection_cross_ref WHERE collectionId = :collectionId")
    suspend fun getItemIdsForCollection(collectionId: String): List<String>

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

    @Query("UPDATE items SET reminderTime = :reminderTime, isReminderDone = 0, isReminderExact = :isExact, isReminderDateOnly = :isDateOnly, reminderLocalDate = :localDate WHERE id = :id")
    suspend fun setReminder(id: String, reminderTime: Long?, isExact: Boolean = true, isDateOnly: Boolean = false, localDate: String? = null)

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
    @Query("SELECT * FROM collections WHERE id NOT IN (SELECT collectionId FROM pending_collection_deletions) ORDER BY createdAt ASC")
    fun observeCollections(): Flow<List<CollectionEntity>>

    @Query("SELECT * FROM collections WHERE id NOT IN (SELECT collectionId FROM pending_collection_deletions) ORDER BY createdAt ASC")
    suspend fun getAllCollections(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getStoredCollection(id: String): CollectionEntity?

    @Query("SELECT * FROM collections ORDER BY createdAt ASC")
    suspend fun getAllStoredCollections(): List<CollectionEntity>

    @Query("SELECT * FROM pending_collection_deletions ORDER BY undoUntil")
    suspend fun getPendingCollectionDeletions(): List<PendingCollectionDeletion>

    @Insert suspend fun insertCollectionDeletion(operation: PendingCollectionDeletion)
    @Insert suspend fun insertCollectionDeletionItems(items: List<CollectionDeletionItem>)
    @Query("SELECT itemId FROM collection_deletion_items WHERE operationId = :id")
    suspend fun getCollectionDeletionItems(id: String): List<String>
    @Query("DELETE FROM pending_collection_deletions WHERE operationId = :id")
    suspend fun deleteCollectionDeletion(id: String)
    @Query("DELETE FROM collection_deletion_items WHERE operationId = :id")
    suspend fun deleteCollectionDeletionItems(id: String)
    @Query("SELECT * FROM privacy_migration_memberships")
    suspend fun getPrivacyMigrationMemberships(): List<PrivacyMigrationMembership>
    @Query("DELETE FROM privacy_migration_memberships")
    suspend fun clearPrivacyMigrationMemberships()

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
    @Query("SELECT * FROM reminders ORDER BY dueMillis ASC")
    suspend fun getAllReminders(): List<ReminderEntity>

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

    @Query("SELECT * FROM analysis_jobs WHERE status IN ('QUEUED', 'NEEDS_RETRY', 'PROCESSING') ORDER BY updatedAt ASC")
    suspend fun getPendingAnalysisJobs(): List<AnalysisJobEntity>

    @Query("DELETE FROM analysis_jobs WHERE itemId = :itemId")
    suspend fun deleteAnalysisJob(itemId: String)

    @Query("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
    suspend fun rebuildFts()
}

private val LEGACY_FTS_TRIGGERS = setOf("items_fts_insert", "items_fts_bu", "items_fts_au", "items_fts_delete", "items_fts_update")

val DATABASE_CALLBACK = object : androidx.room.RoomDatabase.Callback() {
    // Match Room's contentEntity synchronization (also exported in schema 5).
    // Older versions installed a duplicate custom set beside these triggers.
    private val ftsTriggers = mapOf(
        "BEFORE_UPDATE" to "BEFORE UPDATE ON `items` BEGIN DELETE FROM `items_fts` WHERE `docid`=OLD.`rowid`; END",
        "BEFORE_DELETE" to "BEFORE DELETE ON `items` BEGIN DELETE FROM `items_fts` WHERE `docid`=OLD.`rowid`; END",
        "AFTER_UPDATE" to "AFTER UPDATE ON `items` BEGIN INSERT INTO `items_fts`(`docid`, `title`, `content`, `originalFileName`, `extractedText`, `aiDescription`, `aiConcepts`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`content`, NEW.`originalFileName`, NEW.`extractedText`, NEW.`aiDescription`, NEW.`aiConcepts`); END",
        "AFTER_INSERT" to "AFTER INSERT ON `items` BEGIN INSERT INTO `items_fts`(`docid`, `title`, `content`, `originalFileName`, `extractedText`, `aiDescription`, `aiConcepts`) VALUES (NEW.`rowid`, NEW.`title`, NEW.`content`, NEW.`originalFileName`, NEW.`extractedText`, NEW.`aiDescription`, NEW.`aiConcepts`); END"
    ).mapKeys { (suffix, _) -> "room_fts_content_sync_items_fts_$suffix" }
        .mapValues { (name, body) -> "CREATE TRIGGER IF NOT EXISTS `$name` $body" }

    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        super.onCreate(db)
        setupFts(db)
    }

    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        super.onOpen(db)
        setupFts(db)
    }

    private fun setupFts(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        val installed = db.query("SELECT name, sql FROM sqlite_master WHERE type='trigger' AND tbl_name='items'").use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        val obsolete = LEGACY_FTS_TRIGGERS.filter { it in installed }
        val repairs = ftsTriggers.filter { (name, sql) -> installed[name]?.let(::normalizeTriggerSql) != normalizeTriggerSql(sql) }
        if (obsolete.isEmpty() && repairs.isEmpty()) return

        // Repair and rebuild atomically. If maintenance fails, propagate the
        // error instead of silently opening with partially repaired search.
        val ownsTransaction = !db.inTransaction()
        if (ownsTransaction) db.beginTransaction()
        try {
            obsolete.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
            repairs.forEach { (name, sql) ->
                db.execSQL("DROP TRIGGER IF EXISTS `$name`")
                db.execSQL(sql)
            }
            db.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
            if (ownsTransaction) db.setTransactionSuccessful()
        } finally {
            if (ownsTransaction) db.endTransaction()
        }
    }

    // sqlite_master omits IF NOT EXISTS and may normalize SQL formatting.
    private fun normalizeTriggerSql(sql: String): String = sql.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[`\\s;]"), "").replace("ifnotexists", "")
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

val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN isReminderDateOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE items ADD COLUMN reminderLocalDate TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE reminders ADD COLUMN isDateOnly INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE reminders ADD COLUMN localDate TEXT DEFAULT NULL")
    }
}

val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE collections ADD COLUMN appRole TEXT")
        // Adopt exactly one legacy default, leaving every original row and membership intact.
        db.execSQL("UPDATE collections SET appRole = 'RANDOM_LINKS' WHERE id = (SELECT id FROM collections WHERE lower(trim(name)) = 'random links' ORDER BY createdAt, id LIMIT 1)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_collections_appRole ON collections (appRole)")
        db.execSQL("ALTER TABLE analysis_jobs ADD COLUMN metadataOnly INTEGER NOT NULL DEFAULT 0")
        // Remove duplicate legacy synchronization before rebuilding, so onOpen
        // does not repeat the full-library work for an upgraded installation.
        LEGACY_FTS_TRIGGERS.forEach { db.execSQL("DROP TRIGGER IF EXISTS `$it`") }
        // Room recreates synchronization triggers after migration, but does not
        // populate an empty/stale legacy FTS index. Every upgrade path to v5
        // converges here; rebuild once before ordinary opens use the fast path.
        db.execSQL("INSERT INTO items_fts(items_fts) VALUES('rebuild')")
    }
}

val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS privacy_migration_memberships (itemId TEXT NOT NULL, collectionId TEXT NOT NULL, PRIMARY KEY(itemId, collectionId))")
        db.execSQL("INSERT INTO privacy_migration_memberships SELECT itemId, collectionId FROM item_collection_cross_ref")
        // There was no primary/order field. The owner chose earliest-created folder, then stable ID.
        db.execSQL("""DELETE FROM item_collection_cross_ref WHERE collectionId NOT IN (SELECT id FROM collections)
            OR itemId NOT IN (SELECT id FROM items)
            OR collectionId != (SELECT c.id FROM collections c JOIN item_collection_cross_ref r ON r.collectionId = c.id
                WHERE r.itemId = item_collection_cross_ref.itemId ORDER BY c.createdAt, c.id LIMIT 1)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_item_collection_cross_ref_itemId ON item_collection_cross_ref (itemId)")
        db.execSQL("UPDATE collections SET appRole = NULL")
        db.execSQL("UPDATE items SET kind = 'STICKER' WHERE kind = 'PHOTO' AND lower(originalFileName) LIKE 'stk-%.webp'")
        db.execSQL("UPDATE items SET kind = 'GIF' WHERE kind IN ('PHOTO', 'VIDEO', 'FILE') AND (lower(mimeType) = 'image/gif' OR lower(originalFileName) LIKE '%.gif')")
        db.execSQL("UPDATE assets SET kind = 'STICKER' WHERE kind = 'PHOTO' AND lower(originalName) LIKE 'stk-%.webp'")
        db.execSQL("UPDATE assets SET kind = 'GIF' WHERE kind IN ('PHOTO', 'VIDEO', 'FILE') AND (lower(mimeType) = 'image/gif' OR lower(originalName) LIKE '%.gif')")
        db.execSQL("CREATE TABLE IF NOT EXISTS pending_collection_deletions (operationId TEXT NOT NULL PRIMARY KEY, collectionId TEXT NOT NULL, name TEXT NOT NULL, undoUntil INTEGER NOT NULL, trashedAt INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_collection_deletions_collectionId ON pending_collection_deletions (collectionId)")
        db.execSQL("CREATE TABLE IF NOT EXISTS collection_deletion_items (operationId TEXT NOT NULL, itemId TEXT NOT NULL, PRIMARY KEY(operationId, itemId))")
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
        AnalysisJobEntity::class,
        PendingCollectionDeletion::class,
        CollectionDeletionItem::class,
        PrivacyMigrationMembership::class
    ],
    version = 6,
    exportSchema = true
)
abstract class LibraryRoomDatabase : RoomDatabase() {
    abstract fun libraryDao(): LibraryDao
}
