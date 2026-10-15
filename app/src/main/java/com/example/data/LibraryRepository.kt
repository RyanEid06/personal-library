package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
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
import java.io.File
import java.io.InputStream
import java.util.UUID

class LibraryRepository(private val context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        LibraryRoomDatabase::class.java,
        "personal_library_room.db"
    ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).addCallback(DATABASE_CALLBACK).build()

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
        val storedAsset = assetStore.import(input, originalName, declaredMime, explicitSticker)

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
            content = "", // Owner-authored content is empty for raw file captures, never fake placeholder!
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
        val item = dao.getItemById(id)
        if (item != null) {
            val hash = item.contentHash
            if (hash != null) {
                val asset = dao.getAssetByHash(hash)
                if (asset != null) {
                    val newCount = asset.refCount - 1
                    if (newCount <= 0) {
                        dao.deleteAsset(hash)
                        assetStore.deleteBlob(hash)
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

    suspend fun setReminder(id: String, timeMillis: Long?, isExact: Boolean = true) = withContext(Dispatchers.IO) {
        dao.setReminder(id, timeMillis, isExact)
        if (timeMillis != null) {
            val item = dao.getItemById(id)
            dao.insertReminder(
                ReminderEntity(
                    id = "rem_$id",
                    itemId = id,
                    title = item?.title ?: "Reminder",
                    dueMillis = timeMillis,
                    isDone = false,
                    isExact = isExact
                )
            )
        } else {
            dao.deleteReminderByItemId(id)
        }
    }

    suspend fun completeReminder(id: String) = withContext(Dispatchers.IO) {
        dao.setReminderDone(id, true)
        dao.updateReminderStatus("rem_$id", true)
    }

    suspend fun snoozeReminder(id: String, newDueMillis: Long) = withContext(Dispatchers.IO) {
        dao.setReminder(id, newDueMillis)
        dao.snoozeReminder("rem_$id", newDueMillis)
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
            val allReminders = dao.getPendingReminders()

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
                    BackupReminderRecord(it.id, it.itemId, it.title, it.dueMillis, it.isDone, it.isExact)
                },
                assets = allAssets.map {
                    BackupAssetRecord(it.hash, it.relativePath, it.sizeBytes, it.mimeType, it.originalName, it.kind)
                }
            )

            context.contentResolver.openOutputStream(uri)?.use { out ->
                backupEngine.exportArchive(out, passphrase, manifest) { hash ->
                    assetStore.getBlobFile(hash)
                }
            }

            // Persist last successful backup timestamp ONLY upon success
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

    suspend fun commitRestore(preview: RestorePreview): Int = withContext(Dispatchers.IO) {
        val existingIds = dao.getAllItemsIncludingTrash().map { it.id }.toSet()
        var importedCount = 0

        try {
            // 1. Stage and import asset blobs physically into content-addressed ManagedAssetStore
            for (asset in preview.manifest.assets) {
                val stagedFile = File(preview.stagingDir, "assets/${asset.hash}")
                if (stagedFile.exists()) {
                    stagedFile.inputStream().use { input ->
                        assetStore.import(input, asset.originalName ?: asset.hash, asset.mimeType)
                    }
                }
            }

            // 2. Perform metadata installation atomically inside Room transaction
            db.withTransaction {
                for (col in preview.manifest.collections) {
                    dao.insertCollection(CollectionEntity(col.id, col.name, col.iconName, col.colorHex, col.createdAt))
                }

                for (item in preview.manifest.items) {
                    if (!existingIds.contains(item.id)) {
                        dao.insertItem(
                            ItemEntity(
                                id = item.id,
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
                                contentHash = item.contentHash,
                                originalFileName = item.originalFileName,
                                mimeType = item.mimeType,
                                fileSizeBytes = item.fileSizeBytes,
                                extractedText = item.extractedText,
                                aiDescription = item.aiDescription,
                                aiConcepts = item.aiConcepts,
                                aiStatus = item.aiStatus
                            )
                        )
                        importedCount++
                    }
                }

                for (mem in preview.manifest.memberships) {
                    dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(mem.itemId, mem.collectionId))
                }

                val reminderScheduler = com.example.reminder.ReminderScheduler(context)
                for (rem in preview.manifest.reminders) {
                    if (!rem.isDone && rem.dueMillis > System.currentTimeMillis()) {
                        dao.insertReminder(
                            ReminderEntity(
                                id = rem.id,
                                itemId = rem.itemId,
                                title = rem.title,
                                dueMillis = rem.dueMillis,
                                isDone = rem.isDone,
                                isExact = rem.isExact
                            )
                        )
                        reminderScheduler.schedule(rem.itemId, rem.title, rem.dueMillis)
                    }
                }

                // Compute accurate asset refCount based on living items in database
                val allItems = dao.getAllItemsIncludingTrash()
                val liveCounts = allItems.mapNotNull { it.contentHash }.groupingBy { it }.eachCount()

                for (asset in preview.manifest.assets) {
                    val count = liveCounts[asset.hash] ?: 0
                    if (count > 0) {
                        val existing = dao.getAssetByHash(asset.hash)
                        if (existing == null) {
                            dao.insertAsset(
                                AssetEntity(
                                    hash = asset.hash,
                                    relativePath = asset.relativePath,
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

            // 3. Rebuild FTS triggers and index for restored content
            try {
                dao.rebuildFts()
            } catch (_: Exception) {}

        } finally {
            preview.stagingDir.deleteRecursively()
        }

        importedCount
    }

    // WhatsApp Migration with strict parsing and attachment support
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

            // If an attachment is referenced and stream bytes provided, import via ManagedAssetStore
            if (msg.attachmentFileName != null && attachments.containsKey(msg.attachmentFileName)) {
                val stream = attachments[msg.attachmentFileName]
                if (stream != null) {
                    val stored = assetStore.import(stream, msg.attachmentFileName, null)
                    contentHash = stored.hash
                    fileSizeBytes = stored.sizeBytes
                    mimeType = stored.mimeType

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

            val title = when {
                msg.extractedUrl != null -> {
                    val host = Uri.parse(msg.extractedUrl).host ?: "Link"
                    "Link: $host"
                }
                msg.attachmentFileName != null -> msg.attachmentFileName
                else -> msg.rawText.take(40).replace("\n", " ")
            }

            val item = ItemEntity(
                id = msg.importKey, // Deterministic! Repeated imports replace cleanly
                title = title ?: "WhatsApp Message",
                content = msg.rawText,
                kind = msg.kind.name,
                sourceDate = msg.parsedTimestamp,
                createdAt = System.currentTimeMillis(),
                contentHash = contentHash,
                originalFileName = msg.attachmentFileName,
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

    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
