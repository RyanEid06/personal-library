package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.example.indexing.LocalIndexer
import com.example.reminder.ReminderScheduler
import com.personallibrary.core.backup.BackupAssetRecord
import com.personallibrary.core.backup.BackupCollectionRecord
import com.personallibrary.core.backup.BackupItemRecord
import com.personallibrary.core.backup.BackupManifest
import com.personallibrary.core.backup.BackupMembershipRecord
import com.personallibrary.core.backup.BackupReminderRecord
import com.personallibrary.core.backup.EncryptedBackupEngine
import com.personallibrary.core.backup.RestorePreview
import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.migration.WhatsAppExportParser
import com.personallibrary.core.model.ItemKind
import com.personallibrary.core.model.SearchQuery
import com.personallibrary.core.storage.ManagedAssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.util.UUID

class LibraryRepository(private val context: Context, database: LibraryRoomDatabase? = null) {
    companion object {
        private val assetMutations = Mutex()
    }

    private suspend fun removeUnreferencedBlobs(hashes: Collection<String>) = withContext(NonCancellable + Dispatchers.IO) {
        for (hash in hashes) {
            if (dao.getAssetByHash(hash) == null && dao.getAllItemsIncludingTrash().none { it.contentHash == hash }) {
                assetStore.deleteBlob(hash)
            }
        }
    }

    private val db = database ?: Room.databaseBuilder(
        context.applicationContext,
        LibraryRoomDatabase::class.java,
        "personal_library_room.db"
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        .addCallback(DATABASE_CALLBACK)
        .build()

    val dao = db.libraryDao()

    val assetStore = ManagedAssetStore(File(context.filesDir, "storage_root").toPath())
    private val backupEngine = EncryptedBackupEngine()

    fun observeActiveItemsWithCollections(): Flow<List<ItemWithCollections>> = dao.observeItemsWithCollections()

    fun observeTrashItems(): Flow<List<ItemEntity>> = dao.observeTrashItems()

    fun observeCollections(): Flow<List<CollectionEntity>> = dao.observeCollections()

    suspend fun getBlobFile(hash: String): File? = withContext(Dispatchers.IO) {
        assetStore.getBlobFile(hash)
    }

    /**
     * Imports an input stream into content-addressed managed storage and commits Room entity.
     * Reuses identical blobs, increments refCount if duplicate.
     */
    suspend fun captureStream(
        input: InputStream,
        originalName: String?,
        declaredMime: String?,
        explicitSticker: Boolean = false,
        collectionIds: List<String> = emptyList(),
        sourceDate: Long? = null
    ): ItemEntity = withContext(Dispatchers.IO) {
        assetMutations.withLock {
        val storedAsset = assetStore.import(input, originalName, declaredMime, explicitSticker)
        try {
        db.withTransaction {
        // Reference counting
        val existingAsset = dao.getAssetByHash(storedAsset.hash)
        if (existingAsset != null) {
            dao.updateAsset(existingAsset.copy(refCount = existingAsset.refCount + 1))
        } else {
            dao.insertAsset(
                AssetEntity(
                    hash = storedAsset.hash,
                    relativePath = storedAsset.relativePath,
                    sizeBytes = storedAsset.sizeBytes,
                    originalName = storedAsset.originalName,
                    mimeType = storedAsset.mimeType,
                    kind = storedAsset.kind.name,
                    refCount = 1
                )
            )
        }

        val itemId = UUID.randomUUID().toString()
        val item = ItemEntity(
            id = itemId,
            title = storedAsset.originalName,
            content = "",
            kind = storedAsset.kind.name,
            contentHash = storedAsset.hash,
            originalFileName = storedAsset.originalName,
            mimeType = storedAsset.mimeType,
            fileSizeBytes = storedAsset.sizeBytes,
            sourceDate = sourceDate,
            createdAt = System.currentTimeMillis()
        )

        dao.insertItem(item)

        // Assign to collections
        for (colId in collectionIds) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
        }

        item
        }
        } catch (failure: Throwable) {
            try { removeUnreferencedBlobs(listOf(storedAsset.hash)) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
        }
    }

    suspend fun createNote(
        title: String,
        content: String,
        collectionIds: List<String> = emptyList()
    ): ItemEntity = withContext(Dispatchers.IO) {
        val itemId = UUID.randomUUID().toString()
        val item = ItemEntity(
            id = itemId,
            title = title.ifBlank { "Untitled Note" },
            content = content,
            kind = ItemKind.TEXT.name,
            createdAt = System.currentTimeMillis()
        )
        dao.insertItem(item)
        for (colId in collectionIds) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
        }
        item
    }

    suspend fun saveLink(
        url: String,
        title: String,
        note: String,
        collectionIds: List<String> = emptyList()
    ): ItemEntity = withContext(Dispatchers.IO) {
        val itemId = UUID.randomUUID().toString()
        val itemTitle = title.ifBlank {
            try {
                Uri.parse(url).host ?: url
            } catch (_: Exception) {
                url
            }
        }
        val item = ItemEntity(
            id = itemId,
            title = itemTitle,
            content = if (note.isNotBlank()) "$url\n$note" else url,
            kind = ItemKind.LINK.name,
            createdAt = System.currentTimeMillis()
        )
        dao.insertItem(item)
        for (colId in collectionIds) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
        }
        item
    }

    suspend fun updateItem(item: ItemEntity) = withContext(Dispatchers.IO) {
        dao.updateItem(item)
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(id, isFavorite)
    }

    suspend fun moveToTrash(id: String) = withContext(Dispatchers.IO) {
        dao.moveToTrash(id, System.currentTimeMillis())
    }

    suspend fun restoreFromTrash(id: String) = withContext(Dispatchers.IO) {
        dao.restoreFromTrash(id)
    }

    suspend fun deletePermanently(id: String) = withContext(Dispatchers.IO) {
        assetMutations.withLock {
        var orphanHash: String? = null
        db.withTransaction {
        val item = dao.getItemById(id)
        if (item != null) {
            val hash = item.contentHash
            if (hash != null) {
                val asset = dao.getAssetByHash(hash)
                if (asset != null) {
                    val newCount = asset.refCount - 1
                    if (newCount <= 0) {
                        dao.deleteAsset(hash)
                        orphanHash = hash
                    } else {
                        dao.updateAsset(asset.copy(refCount = newCount))
                    }
                }
            }
            dao.removeAllCollectionsForItem(id)
            dao.deleteReminderByItemId(id)
            dao.deleteSearchDocumentsForItem(id)
            dao.deleteAnalysisJob(id)
            dao.deleteItemPermanently(id)
        }
        }
        orphanHash?.let { removeUnreferencedBlobs(listOf(it)) }
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val trash = dao.observeTrashItems().first()
        for (item in trash) {
            deletePermanently(item.id)
        }
    }

    suspend fun purgeExpiredTrash(maxAgeDays: Int = 30) = withContext(Dispatchers.IO) {
        val trash = dao.observeTrashItems().first()
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        for (item in trash) {
            val ts = item.trashTimestamp ?: item.createdAt
            if (ts < cutoff) {
                deletePermanently(item.id)
            }
        }
    }

    suspend fun setReminder(
        id: String,
        timeMillis: Long?,
        isExact: Boolean = true,
        isDateOnly: Boolean = false,
        localDate: String? = null
    ) = withContext(Dispatchers.IO) {
        val effectiveExact = isExact && ReminderScheduler(context).canUseExactAlarms()
        val resolvedTime = if (timeMillis != null && isDateOnly) com.personallibrary.core.ReminderDate.atNine(requireNotNull(localDate)) else timeMillis
        db.withTransaction {
        dao.setReminder(id, resolvedTime, effectiveExact, isDateOnly, localDate)
        if (timeMillis != null) {
            val item = dao.getItemById(id)
            dao.insertReminder(
                ReminderEntity(
                    id = "rem_$id",
                    itemId = id,
                    title = item?.title ?: "Reminder",
                    dueMillis = resolvedTime!!,
                    isDone = false,
                    isExact = effectiveExact,
                    isDateOnly = isDateOnly,
                    localDate = localDate
                )
            )
        } else {
            dao.deleteReminderByItemId(id)
        }
        }
    }

    suspend fun completeReminder(id: String) = withContext(Dispatchers.IO) {
        dao.setReminderDone(id, true)
        dao.updateReminderStatus("rem_$id", true)
    }

    suspend fun snoozeReminder(id: String, newDueMillis: Long) = withContext(Dispatchers.IO) {
        val rem = dao.getReminderByItemId(id)
        val isExact = rem?.isExact ?: true
        dao.setReminder(id, newDueMillis, isExact, false, null)
        if (rem != null) dao.insertReminder(rem.copy(dueMillis = newDueMillis, isDateOnly = false, localDate = null, isDone = false))
    }

    suspend fun createCollection(name: String, iconName: String, colorHex: String): CollectionEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val col = CollectionEntity(id, name, iconName, colorHex)
        dao.insertCollection(col)
        col
    }

    suspend fun deleteCollection(collectionId: String) = withContext(Dispatchers.IO) {
        dao.removeAllItemsForCollection(collectionId)
        dao.deleteCollection(collectionId)
    }

    suspend fun setItemCollections(itemId: String, collectionIds: List<String>) = withContext(Dispatchers.IO) {
        dao.removeAllCollectionsForItem(itemId)
        for (colId in collectionIds) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
        }
    }

    suspend fun search(query: String): List<ItemEntity> = withContext(Dispatchers.IO) {
        val sanitized = SearchQuery.fts(query)
        val ftsResults = if (sanitized != null) {
            try {
                dao.searchFts(sanitized)
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        if (ftsResults.isNotEmpty()) {
            return@withContext ftsResults
        }

        // Substring LIKE fallback
        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            dao.searchLike("%$trimmed%")
        } else {
            dao.getAllActiveItems()
        }
    }

    suspend fun searchWithCollections(query: String): List<ItemWithCollections> = withContext(Dispatchers.IO) {
        val sanitized = SearchQuery.fts(query)
        val ftsResults = if (sanitized != null) {
            try {
                dao.searchFtsWithCollections(sanitized)
            } catch (_: Exception) {
                emptyList()
            }
        } else emptyList()

        if (ftsResults.isNotEmpty()) {
            return@withContext ftsResults
        }

        val trimmed = query.trim()
        if (trimmed.isNotBlank()) {
            dao.searchLikeWithCollections("%$trimmed%")
        } else {
            dao.observeItemsWithCollections().first()
        }
    }

    // Encrypted Backup Export
    suspend fun exportEncryptedBackup(uri: Uri, passphrase: CharArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val allItems = dao.getAllItemsIncludingTrash()
            val allCols = dao.getAllCollections()
            val allAssets = dao.getAllAssets()
            val allReminders = dao.getAllReminders()

            val memberships = mutableListOf<BackupMembershipRecord>()
            for (item in allItems) {
                val colIds = dao.getCollectionIdsForItem(item.id)
                for (cid in colIds) {
                    memberships.add(BackupMembershipRecord(item.id, cid))
                }
            }

            val manifest = BackupManifest(
                items = allItems.map {
                    BackupItemRecord(
                        id = it.id,
                        title = it.title,
                        content = it.content,
                        kind = ItemKind.valueOf(it.kind),
                        isFavorite = it.isFavorite,
                        isTrash = it.isTrash,
                        trashTimestamp = it.trashTimestamp,
                        createdAt = it.createdAt,
                        sourceDate = it.sourceDate,
                        reminderTime = it.reminderTime,
                        isReminderDone = it.isReminderDone,
                        isReminderExact = it.isReminderExact,
                        isReminderDateOnly = it.isReminderDateOnly,
                        reminderLocalDate = it.reminderLocalDate,
                        contentHash = it.contentHash,
                        originalFileName = it.originalFileName,
                        mimeType = it.mimeType,
                        fileSizeBytes = it.fileSizeBytes,
                        extractedText = it.extractedText,
                        aiDescription = it.aiDescription,
                        aiConcepts = it.aiConcepts,
                        aiStatus = it.aiStatus
                    )
                },
                collections = allCols.map {
                    BackupCollectionRecord(it.id, it.name, it.iconName, it.colorHex, it.createdAt)
                },
                memberships = memberships,
                reminders = allReminders.map {
                    BackupReminderRecord(it.id, it.itemId, it.title, it.dueMillis, it.isDone, it.isExact, it.isDateOnly, it.localDate)
                },
                assets = allAssets.map {
                    BackupAssetRecord(it.hash, it.relativePath, it.sizeBytes, it.mimeType, it.originalName, it.kind)
                }
            )

            val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open backup destination")
            output.use { out ->
                backupEngine.exportArchive(out, passphrase, manifest) { hash ->
                    assetStore.getBlobFile(hash)
                }
            }

            val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
            prefs.edit().putLong("last_successful_backup_time", System.currentTimeMillis()).apply()

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getLastSuccessfulBackupTime(): Long? {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val ts = prefs.getLong("last_successful_backup_time", -1L)
        return if (ts > 0L) ts else null
    }

    // Encrypted Backup Inspect & Restore
    suspend fun inspectBackup(uri: Uri, passphrase: CharArray): RestorePreview? = withContext(Dispatchers.IO) {
        try {
            val existingIds = dao.getAllItemsIncludingTrash().map { it.id }.toSet()
            context.contentResolver.openInputStream(uri)?.use { inStream ->
                backupEngine.inspectAndStage(
                    inStream,
                    passphrase,
                    File(context.cacheDir, "staging").apply { mkdirs() }.toPath(),
                    existingIds
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Commits a staged restore preview atomically:
     * - Respects keepBoth policy (Skip Existing vs Keep Both)
     * - When Skip Existing: existing items, memberships, and reminders remain untouched.
     * - When Keep Both: colliders get newly allocated IDs and are imported alongside existing items.
     * - External alarm scheduling occurs strictly AFTER database transaction succeeds.
     * - Re-indexes newly imported items.
     */
    suspend fun commitRestore(preview: RestorePreview, keepBoth: Boolean = false): Int = withContext(Dispatchers.IO) {
        assetMutations.withLock {
        val existingIds = dao.getAllItemsIncludingTrash().map { it.id }.toSet()
        var importedCount = 0
        val itemsToReindex = mutableListOf<ItemEntity>()
        val remindersToSchedule = mutableListOf<Pair<ReminderEntity, Long>>()
        val publishedHashes = mutableListOf<String>()
        val storedAssets = mutableMapOf<String, com.personallibrary.core.model.StoredAsset>()
        val collectionRemap = mutableMapOf<String, String>()

        try {
            // 1. Physically import asset blobs into ManagedAssetStore
            for (asset in preview.manifest.assets) {
                val stagedFile = File(preview.stagingDir, "assets/${asset.hash}")
                if (!stagedFile.isFile) throw IOException("Missing staged original: ${asset.hash}")
                val wasPresent = assetStore.getBlobFile(asset.hash) != null
                val stored = stagedFile.inputStream().use { input ->
                    assetStore.import(input, asset.originalName ?: asset.hash, asset.mimeType)
                }
                if (!wasPresent) publishedHashes.add(stored.hash)
                if (stored.hash != asset.hash || stored.sizeBytes != asset.sizeBytes) throw IOException("Staged original changed")
                storedAssets[asset.hash] = stored
            }

            // Remap colliding item IDs if keepBoth is true
            val idRemap = mutableMapOf<String, String>()
            for (item in preview.manifest.items) {
                if (existingIds.contains(item.id)) {
                    if (keepBoth) {
                        val newId = UUID.randomUUID().toString()
                        idRemap[item.id] = newId
                    }
                }
            }

            // 2. Perform database transaction atomically
            db.withTransaction {
                // Collections: insert or reuse
                for (col in preview.manifest.collections) {
                    val existing = dao.getAllCollections().find { it.id == col.id }
                    val compatible = existing != null && existing.name == col.name && existing.iconName == col.iconName && existing.colorHex == col.colorHex
                    val finalId = if (existing != null && !compatible) UUID.randomUUID().toString() else col.id
                    collectionRemap[col.id] = finalId
                    if (!compatible) dao.insertCollection(CollectionEntity(finalId, col.name, col.iconName, col.colorHex, col.createdAt))
                }

                for (item in preview.manifest.items) {
                    val isCollision = existingIds.contains(item.id)
                    if (isCollision && !keepBoth) {
                        // Skip Existing: preserve live item, do NOT overwrite
                        continue
                    }

                    val finalId = idRemap[item.id] ?: item.id
                    item.contentHash?.let { hash ->
                        if (storedAssets[hash] == null || assetStore.getBlobFile(hash)?.isFile != true) throw IOException("Restored item has no physical original")
                    }
                    val entity = ItemEntity(
                        id = finalId,
                        title = item.title,
                        content = item.content,
                        kind = item.kind.name,
                        isFavorite = item.isFavorite,
                        isTrash = item.isTrash,
                        trashTimestamp = item.trashTimestamp,
                        createdAt = item.createdAt,
                        sourceDate = item.sourceDate,
                        reminderTime = item.reminderTime,
                        isReminderDone = item.isReminderDone,
                        isReminderExact = item.isReminderExact,
                        isReminderDateOnly = item.isReminderDateOnly,
                        reminderLocalDate = item.reminderLocalDate,
                        contentHash = item.contentHash,
                        originalFileName = item.originalFileName,
                        mimeType = item.mimeType,
                        fileSizeBytes = item.fileSizeBytes,
                        extractedText = null,
                        aiDescription = null,
                        aiConcepts = null,
                        aiStatus = "PENDING" // Re-index locally after restore
                    )
                    dao.insertItem(entity)
                    itemsToReindex.add(entity)
                    importedCount++
                }

                // Memberships
                for (mem in preview.manifest.memberships) {
                    val finalItemId = idRemap[mem.itemId] ?: mem.itemId
                    // Only insert membership if item was imported or already existed
                    if (existingIds.contains(mem.itemId) && !keepBoth && !idRemap.containsKey(mem.itemId)) {
                        // Under Skip Existing, existing memberships are left unchanged
                        continue
                    }
                    require(preview.manifest.items.any { it.id == mem.itemId }) { "Unknown membership item" }
                    val collectionId = collectionRemap[mem.collectionId] ?: throw IOException("Unknown membership collection")
                    dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(finalItemId, collectionId))
                }

                // Reminders
                for (rem in preview.manifest.reminders) {
                    val finalItemId = idRemap[rem.itemId] ?: rem.itemId
                    if (existingIds.contains(rem.itemId) && !keepBoth && !idRemap.containsKey(rem.itemId)) {
                        continue
                    }
                    require(preview.manifest.items.any { it.id == rem.itemId }) { "Unknown reminder item" }
                    run {
                        val dueMillis = if (rem.isDateOnly) {
                            java.time.LocalDate.parse(rem.localDate ?: throw IOException("Missing reminder local date"))
                                .atTime(9, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                        } else rem.dueMillis
                        val remEntity = ReminderEntity(
                            id = "rem_$finalItemId",
                            itemId = finalItemId,
                            title = rem.title,
                            dueMillis = dueMillis,
                            isDone = rem.isDone,
                            isExact = rem.isExact,
                            isDateOnly = rem.isDateOnly,
                            localDate = rem.localDate
                        )
                        dao.insertReminder(remEntity)
                        dao.setReminder(finalItemId, dueMillis, rem.isExact, rem.isDateOnly, rem.localDate)
                        dao.setReminderDone(finalItemId, rem.isDone)
                        if (!rem.isDone && dueMillis > System.currentTimeMillis()) remindersToSchedule.add(remEntity to dueMillis)
                    }
                }

                // Accurate asset refCounts derived from all live items in database
                val allLiveItems = dao.getAllItemsIncludingTrash()
                val liveCounts = allLiveItems.mapNotNull { it.contentHash }.groupingBy { it }.eachCount()

                for (asset in preview.manifest.assets) {
                    val count = liveCounts[asset.hash] ?: 0
                    if (count > 0) {
                        val stored = storedAssets.getValue(asset.hash)
                        if (assetStore.getBlobFile(asset.hash)?.isFile != true) throw IOException("Missing restored blob")
                        val existing = dao.getAssetByHash(asset.hash)
                        if (existing == null) {
                            dao.insertAsset(
                                AssetEntity(
                                    hash = asset.hash,
                                    relativePath = stored.relativePath,
                                    sizeBytes = asset.sizeBytes,
                                    originalName = asset.originalName ?: asset.hash,
                                    mimeType = asset.mimeType,
                                    kind = asset.kind ?: "FILE",
                                    refCount = count
                                )
                            )
                        } else {
                            dao.updateAsset(existing.copy(refCount = count))
                        }
                    }
                }
            }

            // 3. External Alarm Scheduling strictly AFTER Room transaction succeeds
            val scheduler = ReminderScheduler(context)
            for ((rem, dueMillis) in remindersToSchedule) {
                scheduler.schedule(rem.itemId, rem.title, dueMillis, rem.isExact)
            }

            // 4. Rebuild FTS
            try {
                dao.rebuildFts()
            } catch (_: Exception) {}

            // 5. Re-index newly imported items through LocalIndexer
            val indexer = LocalIndexer(context, this@LibraryRepository)
            for (it in itemsToReindex) {
                try {
                    indexer.indexItem(it)
                } catch (_: Exception) {}
            }

        } finally {
            try { removeUnreferencedBlobs(publishedHashes) } finally { preview.stagingDir.deleteRecursively() }
        }

        importedCount
        }
    }

    // WhatsApp Migration with strict parsing, attachment matching, and idempotent refCounts
    suspend fun importWhatsAppMigration(
        chatText: String,
        dateOrder: DateOrder? = null,
        attachments: Map<String, InputStream> = emptyMap()
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val parseResult = WhatsAppExportParser.parse(chatText, dateOrder)

        val cols = dao.getAllCollections()
        var randomLinksCol = cols.find { it.name.equals("Random Links", true) }
        if (randomLinksCol == null) {
            randomLinksCol = CollectionEntity(
                id = "col_random_links",
                name = "Random Links",
                iconName = "chat",
                colorHex = "#10B981"
            )
            dao.insertCollection(randomLinksCol)
        }

        var imported = 0
        for (msg in parseResult.messages) {
            var contentHash: String? = null
            var fileSizeBytes = 0L
            var mimeType: String? = null

            val existingItem = dao.getItemById(msg.importKey)

            // If an attachment is referenced and stream bytes provided, import via ManagedAssetStore
            if (msg.attachmentFileName != null && attachments.containsKey(msg.attachmentFileName)) {
                val stream = attachments[msg.attachmentFileName]
                if (stream != null) {
                    val stored = assetStore.import(stream, msg.attachmentFileName, null)
                    contentHash = stored.hash
                    fileSizeBytes = stored.sizeBytes
                    mimeType = stored.mimeType

                    // Idempotent reference counting
                    if (existingItem?.contentHash != stored.hash) {
                        // If item previously pointed to a different hash, decrement old hash
                        if (existingItem?.contentHash != null) {
                            val oldAsset = dao.getAssetByHash(existingItem.contentHash)
                            if (oldAsset != null) {
                                val newRefCount = oldAsset.refCount - 1
                                if (newRefCount <= 0) {
                                    dao.deleteAsset(existingItem.contentHash)
                                    assetStore.deleteBlob(existingItem.contentHash)
                                } else {
                                    dao.updateAsset(oldAsset.copy(refCount = newRefCount))
                                }
                            }
                        }

                        val existingAsset = dao.getAssetByHash(stored.hash)
                        if (existingAsset != null) {
                            dao.updateAsset(existingAsset.copy(refCount = existingAsset.refCount + 1))
                        } else {
                            dao.insertAsset(
                                AssetEntity(
                                    hash = stored.hash,
                                    relativePath = stored.relativePath,
                                    sizeBytes = stored.sizeBytes,
                                    originalName = stored.originalName,
                                    mimeType = stored.mimeType,
                                    kind = stored.kind.name,
                                    refCount = 1
                                )
                            )
                        }
                    }
                }
            } else if (existingItem != null) {
                // Keep existing hash if no new stream was provided
                contentHash = existingItem.contentHash
                fileSizeBytes = existingItem.fileSizeBytes
                mimeType = existingItem.mimeType
            }

            val title = when {
                msg.extractedUrl != null -> {
                    val host = try { Uri.parse(msg.extractedUrl).host ?: "Link" } catch (_: Exception) { "Link" }
                    "Link: $host"
                }
                msg.attachmentFileName != null -> msg.attachmentFileName
                else -> msg.rawText.take(40).replace("\n", " ")
            }

            val item = ItemEntity(
                id = msg.importKey, // Deterministic key
                title = title ?: "WhatsApp Message",
                content = msg.rawText,
                kind = msg.kind.name,
                sourceDate = msg.parsedTimestamp,
                createdAt = existingItem?.createdAt ?: System.currentTimeMillis(),
                contentHash = contentHash,
                originalFileName = msg.attachmentFileName ?: existingItem?.originalFileName,
                mimeType = mimeType,
                fileSizeBytes = fileSizeBytes,
                aiStatus = "PENDING"
            )
            dao.insertItem(item)
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(msg.importKey, randomLinksCol.id))
            imported++
        }

        Pair(imported, parseResult.ambiguousDateCount)
    }
}
