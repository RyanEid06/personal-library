package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
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
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.UUID

class LibraryRepository(private val context: Context) {

    private val db = Room.databaseBuilder(
        context.applicationContext,
        LibraryRoomDatabase::class.java,
        "personal_library_room.db"
    ).addMigrations(MIGRATION_1_2).build()

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
            content = "File: ${storedAsset.originalName} (${formatSize(storedAsset.sizeBytes)})",
            kind = storedAsset.kind.name,
            contentHash = storedAsset.hash,
            originalFileName = storedAsset.originalName,
            mimeType = storedAsset.mimeType,
            fileSizeBytes = storedAsset.sizeBytes,
            sourceDate = sourceDate,
            createdAt = System.currentTimeMillis()
        )

        dao.insertItem(item)

        // Assign collections
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
        val cleanTitle = title.ifBlank {
            content.lines().firstOrNull()?.take(40) ?: "Untitled Note"
        }
        val itemId = UUID.randomUUID().toString()
        val item = ItemEntity(
            id = itemId,
            title = cleanTitle,
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
        val cleanTitle = title.ifBlank {
            Uri.parse(url).host ?: url
        }
        val itemId = UUID.randomUUID().toString()
        val fullContent = if (note.isBlank()) url else "$url\n$note"
        val item = ItemEntity(
            id = itemId,
            title = cleanTitle,
            content = fullContent,
            kind = ItemKind.LINK.name,
            createdAt = System.currentTimeMillis()
        )
        dao.insertItem(item)
        for (colId in collectionIds) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
        }
        item
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(id, isFavorite)
    }

    suspend fun setReminder(id: String, timeMillis: Long?) = withContext(Dispatchers.IO) {
        dao.setReminder(id, timeMillis)
        val item = dao.getItemById(id) ?: return@withContext
        if (timeMillis != null) {
            val reminder = ReminderEntity(
                id = "rem_$id",
                itemId = id,
                title = item.title,
                dueMillis = timeMillis,
                isDone = false,
                isExact = true
            )
            dao.insertReminder(reminder)
        } else {
            dao.deleteReminderByItemId(id)
        }
    }

    suspend fun moveToTrash(id: String) = withContext(Dispatchers.IO) {
        dao.moveToTrash(id, System.currentTimeMillis())
        dao.deleteReminderByItemId(id)
    }

    suspend fun restoreFromTrash(id: String) = withContext(Dispatchers.IO) {
        dao.restoreFromTrash(id)
        val item = dao.getItemById(id)
        val reminderTime = item?.reminderTime
        if (item != null && reminderTime != null && reminderTime > System.currentTimeMillis()) {
            dao.insertReminder(
                ReminderEntity(
                    id = "rem_$id",
                    itemId = id,
                    title = item.title,
                    dueMillis = reminderTime,
                    isDone = false,
                    isExact = true
                )
            )
        }
    }

    /**
     * Safely deletes item permanently. Decrements blob refCount; if refCount drops to 0,
     * deletes physical blob file from disk.
     */
    suspend fun deleteItemPermanently(id: String) = withContext(Dispatchers.IO) {
        val item = dao.getItemById(id) ?: return@withContext
        val hash = item.contentHash

        dao.removeAllCollectionsForItem(id)
        dao.deleteReminderByItemId(id)
        dao.deleteSearchDocumentsForItem(id)
        dao.deleteItemPermanently(id)

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
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val trashItems = dao.getAllItemsIncludingTrash().filter { it.isTrash }
        for (item in trashItems) {
            deleteItemPermanently(item.id)
        }
    }

    /**
     * Enforces real 30-day Trash retention: permanently purges items older than 30 days.
     */
    suspend fun purgeExpiredTrash(nowMillis: Long = System.currentTimeMillis()) = withContext(Dispatchers.IO) {
        val threshold = nowMillis - (30L * 24 * 60 * 60 * 1000)
        val expired = dao.getAllItemsIncludingTrash().filter { it.isTrash && (it.trashTimestamp ?: 0L) < threshold }
        for (item in expired) {
            deleteItemPermanently(item.id)
        }
    }

    suspend fun createCollection(name: String, icon: String, color: String): CollectionEntity = withContext(Dispatchers.IO) {
        val col = CollectionEntity(
            id = "col_${UUID.randomUUID().toString().take(8)}",
            name = name.trim(),
            iconName = icon,
            colorHex = color
        )
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
                        contentHash = it.contentHash,
                        originalFileName = it.originalFileName,
                        mimeType = it.mimeType,
                        fileSizeBytes = it.fileSizeBytes,
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
                    BackupReminderRecord(it.id, it.itemId, it.title, it.dueMillis, it.isDone)
                },
                assets = allAssets.map {
                    BackupAssetRecord(it.hash, it.relativePath, it.sizeBytes, it.mimeType)
                }
            )

            context.contentResolver.openOutputStream(uri)?.use { out ->
                backupEngine.exportArchive(out, passphrase, manifest) { hash ->
                    assetStore.getBlobFile(hash)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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

        for (col in preview.manifest.collections) {
            dao.insertCollection(CollectionEntity(col.id, col.name, col.iconName, col.colorHex, col.createdAt))
        }

        // Restore asset files & metadata
        for (asset in preview.manifest.assets) {
            val stagedFile = File(preview.stagingDir, "assets/${asset.hash}")
            if (stagedFile.exists()) {
                stagedFile.inputStream().use { input ->
                    assetStore.import(input, asset.hash, asset.mimeType)
                }
            }
            val existingAsset = dao.getAssetByHash(asset.hash)
            if (existingAsset == null) {
                dao.insertAsset(
                    AssetEntity(
                        hash = asset.hash,
                        relativePath = asset.relativePath,
                        sizeBytes = asset.sizeBytes,
                        originalName = asset.hash,
                        mimeType = asset.mimeType,
                        kind = "FILE",
                        refCount = 1
                    )
                )
            } else {
                dao.updateAsset(existingAsset.copy(refCount = existingAsset.refCount + 1))
            }
        }

        // Restore items
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
                        contentHash = item.contentHash,
                        originalFileName = item.originalFileName,
                        mimeType = item.mimeType,
                        fileSizeBytes = item.fileSizeBytes,
                        aiDescription = item.aiDescription,
                        aiConcepts = item.aiConcepts,
                        aiStatus = item.aiStatus
                    )
                )
                importedCount++
            }
        }

        // Restore memberships
        for (mem in preview.manifest.memberships) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(mem.itemId, mem.collectionId))
        }

        // Restore reminders
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
                        isExact = true
                    )
                )
                reminderScheduler.schedule(rem.itemId, rem.title, rem.dueMillis)
            }
        }

        preview.stagingDir.deleteRecursively()
        importedCount
    }

    // WhatsApp Migration with strict parsing
    suspend fun importWhatsAppMigration(
        chatText: String,
        dateOrder: DateOrder? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val parseResult = WhatsAppExportParser.parse(chatText, dateOrder)

        // Ensure "Random Links" collection exists
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
            val title = when {
                msg.extractedUrl != null -> {
                    val host = Uri.parse(msg.extractedUrl).host ?: "Link"
                    "Link: $host"
                }
                msg.attachmentFileName != null -> msg.attachmentFileName
                else -> msg.rawText.take(40).replace("\n", " ")
            }

            val item = ItemEntity(
                id = msg.importKey, // Deterministic! Repeated imports will replace without duplicates
                title = title ?: "WhatsApp Message",
                content = msg.rawText,
                kind = msg.kind.name,
                sourceDate = msg.parsedTimestamp, // null if ambiguous or unparseable, NEVER guessed!
                createdAt = System.currentTimeMillis(),
                originalFileName = msg.attachmentFileName,
                aiStatus = "READY"
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
