package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.example.indexing.LocalIndexer
import com.example.indexing.DurableAiWorker
import com.example.reminder.ReminderScheduler
import com.personallibrary.core.backup.BackupAssetRecord
import com.personallibrary.core.backup.BackupCollectionRecord
import com.personallibrary.core.backup.BackupItemRecord
import com.personallibrary.core.backup.BackupManifest
import com.personallibrary.core.backup.BackupMembershipRecord
import com.personallibrary.core.backup.BackupReminderRecord
import com.personallibrary.core.backup.EncryptedBackupEngine
import com.personallibrary.core.backup.RestorePreview
import com.personallibrary.core.backup.RestoreStaging
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
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.net.URI

class LibraryRepository(private val context: Context, database: LibraryRoomDatabase? = null) {
    companion object {
        private val assetMutations = Mutex()
        private val reservedCollectionNames = listOf("Favorites", "Unfiled", "Reminders", "Due Reminders", "Trash", "All Items")
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
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
        .addCallback(DATABASE_CALLBACK)
        .build()

    val dao = db.libraryDao()

    suspend fun updateAnalysisJobIfCurrent(job: AnalysisJobEntity, itemStatus: String = job.status): Boolean = db.withTransaction {
        val current = dao.getItemById(job.itemId)
        if (current == null || current.isTrash || current.localIndexVersion != job.expectedContentVersion) return@withTransaction false
        dao.updateAiStatus(job.itemId, itemStatus)
        dao.insertOrUpdateAnalysisJob(job)
        true
    }

    suspend fun publishLocalIndex(itemId: String, expectedVersion: Int, text: String?, coverage: String,
        documents: List<SearchDocumentEntity>): Boolean = db.withTransaction {
        val current = dao.getItemById(itemId)
        if (current == null || current.isTrash || current.localIndexVersion != expectedVersion) return@withTransaction false
        dao.updateExtractedText(itemId, text, coverage, expectedVersion + 1)
        dao.deleteSearchDocumentsForItem(itemId)
        if (documents.isNotEmpty()) dao.insertSearchDocuments(documents)
        true
    }

    suspend fun checkpointLocalIndex(itemId: String, expectedVersion: Int, text: String?, coverage: String): Boolean = db.withTransaction {
        val current = dao.getItemById(itemId)
        if (current == null || current.isTrash || current.localIndexVersion != expectedVersion) return@withTransaction false
        dao.updateExtractedText(itemId, text, coverage, expectedVersion)
        true
    }

    suspend fun publishAiResult(itemId: String, expectedVersion: Int, description: String, concepts: String,
        status: String, documents: List<SearchDocumentEntity>, attempts: Int): Boolean = db.withTransaction {
        val current = dao.getItemById(itemId)
        if (current == null || current.isTrash || current.localIndexVersion != expectedVersion) return@withTransaction false
        val preferences = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        if (preferences.getBoolean("ai_paused", false) || itemId in preferences.getStringSet("excluded_items", emptySet()).orEmpty() ||
            dao.getCollectionIdsForItem(itemId).any { it in preferences.getStringSet("excluded_collections", emptySet()).orEmpty() }) return@withTransaction false
        require(documents.all { it.itemId == itemId })
        dao.updateAiMetadata(itemId, description, concepts, status)
        dao.deleteSearchDocumentsForItem(itemId)
        if (documents.isNotEmpty()) dao.insertSearchDocuments(documents)
        dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(itemId, expectedVersion, expectedVersion,
            status = "COMPLETED", attempts = attempts, metadataOnly = dao.getAnalysisJob(itemId)?.metadataOnly == true))
        true
    }

    val assetStore = ManagedAssetStore(File(context.filesDir, "storage_root").toPath())
    private val backupEngine = EncryptedBackupEngine()
    private val restoreStaging = RestoreStaging(File(context.cacheDir, "staging"))

    suspend fun cleanAbandonedRestoreStages() = withContext(Dispatchers.IO) { restoreStaging.cleanAbandoned() }

    suspend fun discardRestorePreview(preview: RestorePreview) = withContext(NonCancellable + Dispatchers.IO) {
        restoreStaging.discard(preview)
    }

    suspend fun reconcileStorage() = withContext(Dispatchers.IO) {
        assetMutations.withLock {
            val counts = dao.getAllItemsIncludingTrash().mapNotNull { it.contentHash }.groupingBy { it }.eachCount()
            db.withTransaction {
                for (asset in dao.getAllAssets()) {
                    val count = counts[asset.hash] ?: 0
                    if (count == 0) dao.deleteAsset(asset.hash)
                    else if (count != asset.refCount) dao.updateAsset(asset.copy(refCount = count))
                }
            }
            assetStore.reconcile(counts.keys)
        }
    }

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
            aiStatus = "PENDING",
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
        db.withTransaction {
        val itemId = UUID.randomUUID().toString()
        val item = ItemEntity(
            id = itemId,
            title = title.ifBlank { "Untitled Note" },
            content = content,
            kind = ItemKind.TEXT.name,
            aiStatus = "PENDING",
            createdAt = System.currentTimeMillis()
        )
        dao.insertItem(item)
        for (colId in collectionIds) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
        }
        item
        }
    }

    suspend fun saveLink(
        url: String,
        title: String,
        note: String,
        collectionIds: List<String> = emptyList()
    ): ItemEntity = withContext(Dispatchers.IO) {
        db.withTransaction {
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
            aiStatus = "PENDING",
            createdAt = System.currentTimeMillis()
        )
        dao.insertItem(item)
        for (colId in collectionIds) {
            dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
        }
        item
        }
    }

    suspend fun updateItem(item: ItemEntity) = withContext(Dispatchers.IO) {
        dao.updateItem(item)
    }

    /** Edits owner fields only; original bytes and source metadata remain authoritative. */
    suspend fun editItem(id: String, title: String, content: String): ItemEntity = withContext(Dispatchers.IO) {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotBlank()) { "Enter a title" }
        var bodyChanged = false
        var metadataChanged = false
        val updated = db.withTransaction {
            val original = requireNotNull(dao.getItemById(id)) { "Item no longer exists" }
            require(!original.isTrash) { "Restore the item before editing it" }
            val editableBody = original.contentHash == null && original.kind in setOf(ItemKind.TEXT.name, ItemKind.LINK.name)
            require(editableBody || content == original.content) { "Only the display title can be edited for this file" }
            val provenanceMarker = "\n\nWhatsApp provenance\n"
            if (original.id.startsWith("wa_") || original.content.contains(provenanceMarker)) {
                val marker = original.content.lastIndexOf(provenanceMarker)
                if (marker >= 0) require(content.endsWith(original.content.substring(marker))) { "Original WhatsApp provenance must be preserved" }
            }
            if (original.kind == ItemKind.LINK.name && content != original.content) {
                // Imported messages may contain the link within their text, unlike native link records.
                val ownerBody = content.substringBefore(provenanceMarker)
                val candidate = if (original.content.contains(provenanceMarker)) {
                    Regex("[A-Za-z][A-Za-z0-9+.-]*:[^\\s]+").find(ownerBody)?.value
                } else ownerBody.lineSequence().firstOrNull()?.trim()
                require(!candidate.isNullOrBlank()) { "Enter a valid URL" }
                val uri = try { URI(candidate) } catch (_: Exception) { null }
                require(uri != null && uri.isAbsolute && uri.scheme.lowercase() in setOf("http", "https", "ftp", "mailto", "tel", "sms", "geo") && !uri.rawSchemeSpecificPart.isNullOrBlank() &&
                    (uri.scheme.lowercase() !in setOf("http", "https", "ftp") || !uri.host.isNullOrBlank())) { "Enter a valid URL" }
            }
            bodyChanged = content != original.content
            metadataChanged = !bodyChanged && cleanTitle != original.title
            val edited = if (bodyChanged) original.copy(title = cleanTitle, content = content,
                extractedText = null, localIndexCoverage = "PENDING", localIndexVersion = original.localIndexVersion + 1,
                aiDescription = null, aiConcepts = null, aiStatus = "PENDING")
            else original.copy(title = cleanTitle, localIndexVersion = original.localIndexVersion + if (metadataChanged) 1 else 0)
            dao.updateItem(edited)
            if (bodyChanged) {
                dao.deleteSearchDocumentsForItem(id)
                dao.deleteAnalysisJob(id)
            } else if (metadataChanged) {
                val localDocs = dao.getSearchDocumentsForItem(id)
                val chunks = DurableAiWorker.createChunksForItem(edited, edited.aiDescription, edited.aiConcepts, localDocs)
                val cachedCoverage = if (original.aiStatus == "PARTIAL" || original.localIndexCoverage.equals("PARTIAL", true) ||
                    localDocs.any { it.coverage.equals("PARTIAL", true) }) "PARTIAL" else edited.localIndexCoverage
                dao.deleteSearchDocumentsForItem(id)
                val rebuilt = chunks.map { chunk ->
                    SearchDocumentEntity("local_${id}_${edited.localIndexVersion}_${chunk.index}", id, chunk.index,
                        chunk.pageNumber, chunk.text, coverage = cachedCoverage)
                }
                val remainingPages = localDocs.filter { local -> local.pageNumber != null &&
                    chunks.none { it.pageNumber == local.pageNumber && it.text == local.textContent } }.map { it.copy(embedding = null) }
                dao.insertSearchDocuments(rebuilt + remainingPages)
                dao.deleteAnalysisJob(id)
                val hasCachedAi = original.aiDescription != null || original.aiConcepts != null
                dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(id, edited.localIndexVersion, edited.localIndexVersion, metadataOnly = hasCachedAi))
            }
            dao.getReminderByItemId(id)?.let { dao.insertReminder(it.copy(title = cleanTitle)) }
            edited
        }
        if (bodyChanged || metadataChanged && updated.localIndexCoverage == "PENDING") LocalIndexer(context, this@LibraryRepository).indexItem(updated)
        else if (metadataChanged) LocalIndexer(context, this@LibraryRepository).enqueueDurableAiJob(id)
        dao.getItemById(id) ?: updated
    }

    suspend fun setFavorite(id: String, isFavorite: Boolean) = withContext(Dispatchers.IO) {
        dao.setFavorite(id, isFavorite)
    }

    suspend fun moveToTrash(id: String) = withContext(Dispatchers.IO) {
        moveToTrash(listOf(id))
    }

    suspend fun restoreFromTrash(id: String) = withContext(Dispatchers.IO) {
        restoreFromTrash(listOf(id))
    }

    suspend fun moveToTrash(ids: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        val affected = db.withTransaction {
            val timestamp = System.currentTimeMillis()
            ids.distinct().filter { id ->
                val item = dao.getItemById(id)
                if (item != null && !item.isTrash) { dao.moveToTrash(id, timestamp); true } else false
            }
        }
        withContext(NonCancellable) {
            val scheduler = ReminderScheduler(context)
            affected.forEach(scheduler::cancel)
        }
        affected
    }

    suspend fun restoreFromTrash(ids: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        val affected = db.withTransaction {
            ids.distinct().filter { id ->
                val item = dao.getItemById(id)
                if (item == null || !item.isTrash) false else {
                    dao.restoreFromTrash(id)
                    val reminder = dao.getReminderByItemId(id)
                    if (reminder?.isDateOnly == true && reminder.localDate != null) {
                        val due = com.personallibrary.core.ReminderDate.atNine(reminder.localDate)
                        dao.insertReminder(reminder.copy(dueMillis = due))
                        dao.setReminder(id, due, reminder.isExact, true, reminder.localDate)
                        dao.setReminderDone(id, reminder.isDone)
                    }
                    true
                }
            }
        }
        withContext(NonCancellable) {
            val scheduler = ReminderScheduler(context)
            for (id in affected) dao.getItemById(id)?.let { scheduler.scheduleRestored(it, dao.getReminderByItemId(id)) }
        }
        affected
    }

    suspend fun deletePermanently(id: String, requireTrash: Boolean = false) = withContext(Dispatchers.IO) {
        assetMutations.withLock {
        var orphanHash: String? = null
        var deleted = false
        db.withTransaction {
        val item = dao.getItemById(id)
        if (item != null && (!requireTrash || item.isTrash)) {
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
            deleted = true
        }
        }
        orphanHash?.let { removeUnreferencedBlobs(listOf(it)) }
        if (deleted) ReminderScheduler(context).cancel(id)
        }
    }

    suspend fun emptyTrash() = withContext(Dispatchers.IO) {
        val trash = dao.observeTrashItems().first()
        for (item in trash) {
            deletePermanently(item.id, requireTrash = true)
        }
    }

    suspend fun purgeExpiredTrash(maxAgeDays: Int = 30) = withContext(Dispatchers.IO) {
        val trash = dao.observeTrashItems().first()
        val cutoff = System.currentTimeMillis() - (maxAgeDays * 24 * 60 * 60 * 1000L)
        for (item in trash) {
            val ts = item.trashTimestamp ?: item.createdAt
            if (ts < cutoff) {
                deletePermanently(item.id, requireTrash = true)
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
        db.withTransaction {
        val item = dao.getItemById(id) ?: return@withTransaction
        val rem = dao.getReminderByItemId(id)
        if (item.isTrash || item.isReminderDone || rem == null || rem.isDone) return@withTransaction
        val isExact = rem.isExact
        dao.setReminder(id, newDueMillis, isExact, false, null)
        dao.insertReminder(rem.copy(dueMillis = newDueMillis, isDateOnly = false, localDate = null, isDone = false))
        }
    }

    suspend fun createCollection(name: String, iconName: String, colorHex: String): CollectionEntity = withContext(Dispatchers.IO) {
        db.withTransaction {
            val col = CollectionEntity(UUID.randomUUID().toString(), validatedCollectionName(name), iconName, colorHex)
            dao.insertCollection(col)
            col
        }
    }

    private suspend fun validatedCollectionName(name: String, editingId: String? = null): String {
        val trimmed = name.trim()
        require(trimmed.isNotBlank()) { "Enter a collection name" }
        require(reservedCollectionNames.none { it.equals(trimmed, true) }) { "This name is reserved" }
        require(dao.getAllCollections().none { it.id != editingId && it.name.trim().equals(trimmed, true) }) { "A collection with this name already exists" }
        return trimmed
    }

    private fun restoredCollectionName(name: String, existing: List<CollectionEntity>): String {
        val base = name.trim().ifBlank { "Restored collection" }
        var candidate = base
        var suffix = 2
        while (reservedCollectionNames.any { it.equals(candidate, true) } ||
            existing.any { it.name.trim().equals(candidate, true) }) {
            candidate = "$base (restored ${suffix++})"
        }
        return candidate
    }

    suspend fun editCollection(id: String, name: String, iconName: String, colorHex: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val original = requireNotNull(dao.getAllCollections().find { it.id == id }) { "Collection no longer exists" }
            dao.insertCollection(original.copy(name = validatedCollectionName(name, id), iconName = iconName, colorHex = colorHex))
        }
    }

    private suspend fun randomLinksInTransaction(): CollectionEntity {
        val all = dao.getAllCollections()
        all.firstOrNull { it.appRole == CollectionEntity.ROLE_RANDOM_LINKS }?.let { return it }
        // One-time compatibility adoption for installations/backups predating portable roles.
        val legacy = all.firstOrNull { it.appRole == null && it.name.trim().equals("Random Links", true) }
        val collection = legacy?.copy(appRole = CollectionEntity.ROLE_RANDOM_LINKS)
            ?: CollectionEntity(UUID.randomUUID().toString(), "Random Links", "chat", "#10B981", appRole = CollectionEntity.ROLE_RANDOM_LINKS)
        dao.insertCollection(collection)
        return collection
    }

    suspend fun getOrCreateRandomLinksCollection(): CollectionEntity = withContext(Dispatchers.IO) {
        db.withTransaction { randomLinksInTransaction() }
    }

    suspend fun deleteCollection(collectionId: String) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.removeAllItemsForCollection(collectionId)
            dao.deleteCollection(collectionId)
        }
    }

    suspend fun changeItemCollections(itemIds: Collection<String>, collectionIds: Collection<String>, add: Boolean) = withContext(Dispatchers.IO) {
        db.withTransaction {
            val ids = itemIds.distinct()
            val collections = collectionIds.distinct()
            require(ids.all { dao.getItemById(it)?.isTrash == false }) { "Some selected items are no longer available" }
            val knownCollections = dao.getAllCollections().map { it.id }.toSet()
            require(collections.all { it in knownCollections }) { "Collection no longer exists" }
            for (id in ids) for (collection in collections) {
                if (add) dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, collection))
                else dao.removeItemFromCollection(id, collection)
            }
        }
    }

    suspend fun setItemCollections(itemId: String, collectionIds: List<String>) = withContext(Dispatchers.IO) {
        db.withTransaction {
            dao.removeAllCollectionsForItem(itemId)
            for (colId in collectionIds.distinct()) {
                dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(itemId, colId))
            }
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

    private suspend fun backupSnapshot(): BackupManifest = db.withTransaction {
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

        BackupManifest(
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
                BackupCollectionRecord(it.id, it.name, it.iconName, it.colorHex, it.createdAt, it.appRole)
            },
            memberships = memberships,
            reminders = allReminders.map {
                BackupReminderRecord(it.id, it.itemId, it.title, it.dueMillis, it.isDone, it.isExact, it.isDateOnly, it.localDate)
            },
            assets = allAssets.map {
                BackupAssetRecord(it.hash, it.relativePath, it.sizeBytes, it.mimeType, it.originalName, it.kind)
            }
        )
    }

    // Keep originals alive through streaming, but release the Room transaction after the metadata snapshot.
    suspend fun exportEncryptedBackup(uri: Uri, passphrase: CharArray): Boolean = withContext(Dispatchers.IO) {
        assetMutations.withLock {
            try {
                val manifest = backupSnapshot()
                val output = context.contentResolver.openOutputStream(uri) ?: throw IOException("Cannot open backup destination")
                output.use { out ->
                    backupEngine.exportArchive(out, passphrase, manifest) { hash ->
                        assetStore.getBlobFile(hash)
                    }
                }

                val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
                prefs.edit().putLong("last_successful_backup_time", System.currentTimeMillis()).apply()

                true
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }
    }

    fun getLastSuccessfulBackupTime(): Long? {
        val prefs = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val ts = prefs.getLong("last_successful_backup_time", -1L)
        return if (ts > 0L) ts else null
    }

    // Encrypted Backup Inspect & Restore
    suspend fun inspectBackup(uri: Uri, passphrase: CharArray): RestorePreview? {
        var preview: RestorePreview? = null
        return try {
            withContext(Dispatchers.IO) {
                val existingIds = dao.getAllItemsIncludingTrash().map { it.id }.toSet()
                preview = context.contentResolver.openInputStream(uri)?.use { inStream ->
                    restoreStaging.inspect(inStream, passphrase, existingIds)
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                preview
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Includes prompt cancellation while returning from the IO dispatcher.
            preview?.let { discardRestorePreview(it) }
            throw cancelled
        }
        catch (_: Exception) {
            preview?.let { discardRestorePreview(it) }
            null
        } finally {
            passphrase.fill('\u0000')
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
                    val all = dao.getAllCollections()
                    val role = col.appRole ?: if (preview.manifest.collections.none { it.appRole == CollectionEntity.ROLE_RANDOM_LINKS } &&
                        col.name.trim().equals("Random Links", true)) CollectionEntity.ROLE_RANDOM_LINKS else null
                    val roleOwner = role?.let { wanted -> all.firstOrNull { it.appRole == wanted } }
                        ?: if (role == CollectionEntity.ROLE_RANDOM_LINKS) all.firstOrNull { it.appRole == null && it.name.trim().equals("Random Links", true) } else null
                    if (roleOwner != null) {
                        if (roleOwner.appRole == null) dao.insertCollection(roleOwner.copy(appRole = role))
                        collectionRemap[col.id] = roleOwner.id
                        continue
                    }
                    var existing = all.find { it.id == col.id }
                    // Portable identity survives collision-safe restore names and later owner edits.
                    // A different immutable creation timestamp or role means the ID collides with another collection.
                    fun samePortableIdentity(candidate: CollectionEntity) = candidate.appRole == role && candidate.createdAt == col.createdAt
                    var finalId = col.id
                    if (existing == null || !samePortableIdentity(existing)) {
                        val sourceIdAvailable = existing == null
                        // Stable remapping makes a true portable-ID collision idempotent across repeated restores.
                        // Length prefixes keep arbitrary imported identifiers and roles unambiguous.
                        val portableRole = role?.let { "${it.length}:$it" } ?: "-1:"
                        var salt = 0
                        do {
                            val identity = "memora.restore.collection.v1|${col.id.length}:${col.id}|${col.createdAt}|$portableRole|${salt++}"
                            finalId = UUID.nameUUIDFromBytes(identity.toByteArray(Charsets.UTF_8)).toString()
                            existing = all.find { it.id == finalId }
                        } while (existing != null && !samePortableIdentity(existing))
                        // A previous collision may already have been remapped even after its owner was deleted.
                        // Use the free original ID only when no matching remapped identity was found.
                        if (existing == null && sourceIdAvailable) finalId = col.id
                    }
                    collectionRemap[col.id] = finalId
                    if (existing == null) dao.insertCollection(CollectionEntity(finalId, restoredCollectionName(col.name, all), col.iconName, col.colorHex, col.createdAt, role))
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
                            isExact = rem.isExact && !rem.isDateOnly && ReminderScheduler(context).canUseExactAlarms(),
                            isDateOnly = rem.isDateOnly,
                            localDate = rem.localDate
                        )
                        dao.insertReminder(remEntity)
                        dao.setReminder(finalItemId, dueMillis, remEntity.isExact, rem.isDateOnly, rem.localDate)
                        dao.setReminderDone(finalItemId, rem.isDone)
                        if (!rem.isDone && dueMillis > System.currentTimeMillis() && dao.getItemById(finalItemId)?.isTrash == false) remindersToSchedule.add(remEntity to dueMillis)
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
            for ((rem, _) in remindersToSchedule) {
                dao.getItemById(rem.itemId)?.let { scheduler.scheduleRestored(it, dao.getReminderByItemId(rem.itemId)) }
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
            try { removeUnreferencedBlobs(publishedHashes) } finally {
                // Hand-built previews are also used by existing transactional restore tests.
                if (preview.stagingDir.parentFile?.canonicalFile == File(context.cacheDir, "staging").canonicalFile) restoreStaging.discard(preview)
                else preview.stagingDir.deleteRecursively()
            }
        }

        importedCount
        }
    }

    // Import originals first, then commit the complete message/reference batch together.
    suspend fun importWhatsAppMigration(
        chatText: String,
        dateOrder: DateOrder? = null,
        attachments: Map<String, InputStream> = emptyMap(),
        originalExport: InputStream? = null
    ): Pair<Int, Int> = withContext(Dispatchers.IO) {
        assetMutations.withLock {
            val parsed = WhatsAppExportParser.parse(chatText, dateOrder, java.util.TimeZone.getDefault())
            require(parsed.totalMessages > 0) { "No messages in export" }
            val supplied = mutableMapOf<String, com.personallibrary.core.model.StoredAsset>()
            val publications = mutableSetOf<String>()
            val previousHashes = mutableSetOf<String>()
            try {
                val rawExport = (originalExport ?: chatText.byteInputStream()).use { input ->
                    val bytes = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        require(bytes.size() + count <= com.personallibrary.core.TextExtraction.MAX_BYTES) { "Export exceeds import budget" }
                        bytes.write(buffer, 0, count)
                    }
                    bytes.toByteArray()
                }
                val decoded = com.personallibrary.core.TextExtraction.read(rawExport.inputStream())
                require(decoded.coverage == "complete" && decoded.text == chatText) { "Export changed since preview; choose it again" }
                val export = assetStore.import(rawExport.inputStream(), "WhatsApp export.txt", "text/plain")
                publications.add(export.hash)
                val neededNames = parsed.messages.mapNotNull { it.attachmentFileName }.toSet()
                for ((name, stream) in attachments) {
                    if (name in neededNames) {
                        val stored = stream.use { assetStore.import(it, name, null) }
                        supplied[name] = stored
                        publications.add(stored.hash)
                    }
                }
                var imported = 0
                db.withTransaction {
                    val randomLinks = randomLinksInTransaction()
                    val exportId = "whatsapp_export_${export.hash}"
                    if (dao.getItemById(exportId) == null) {
                        dao.insertItem(ItemEntity(id = exportId, title = "WhatsApp export", content = "Original WhatsApp export; imported in timezone ${java.util.TimeZone.getDefault().id}",
                            kind = ItemKind.DOCUMENT.name, contentHash = export.hash, originalFileName = "WhatsApp export.txt",
                            mimeType = "text/plain", fileSizeBytes = export.sizeBytes, aiStatus = "PENDING"))
                        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(exportId, randomLinks.id))
                    }
                    for (msg in parsed.messages) {
                        val existing = dao.getItemById(msg.importKey)
                        val stored = msg.attachmentFileName?.let(supplied::get)
                        existing?.contentHash?.let(previousHashes::add)
                        val sourceDate = msg.parsedTimestamp ?: existing?.sourceDate
                        val originalChanged = existing != null && stored != null && stored.hash != existing.contentHash
                        val item = if (existing != null) {
                            existing.copy(sourceDate = sourceDate, contentHash = stored?.hash ?: existing.contentHash,
                                originalFileName = stored?.originalName ?: existing.originalFileName,
                                mimeType = stored?.mimeType ?: existing.mimeType, fileSizeBytes = stored?.sizeBytes ?: existing.fileSizeBytes,
                                localIndexVersion = existing.localIndexVersion + if (originalChanged) 1 else 0,
                                extractedText = if (originalChanged) null else existing.extractedText,
                                localIndexCoverage = if (originalChanged) "PENDING" else existing.localIndexCoverage,
                                aiDescription = if (originalChanged) null else existing.aiDescription,
                                aiConcepts = if (originalChanged) null else existing.aiConcepts,
                                aiStatus = if (originalChanged) "PENDING" else existing.aiStatus)
                        } else {
                            val provenance = "${msg.rawText}\n\nWhatsApp provenance\nSender: ${msg.sender ?: "System"}\nSource date text: ${msg.rawDateText}\nImport timezone: ${java.util.TimeZone.getDefault().id}"
                            ItemEntity(id = msg.importKey, title = msg.attachmentFileName ?: msg.rawText.take(40).replace("\n", " "),
                                content = provenance, kind = msg.kind.name, sourceDate = sourceDate,
                                contentHash = stored?.hash, originalFileName = msg.attachmentFileName,
                                mimeType = stored?.mimeType, fileSizeBytes = stored?.sizeBytes ?: 0L, aiStatus = "PENDING")
                        }
                        if (existing == null) imported++
                        dao.insertItem(item)
                        if (originalChanged) {
                            dao.deleteSearchDocumentsForItem(item.id)
                            dao.deleteAnalysisJob(item.id)
                        }
                        dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(item.id, randomLinks.id))
                    }
                    // Derive counts from actual references, including reuse of one supplied file by many messages.
                    val counts = dao.getAllItemsIncludingTrash().mapNotNull { it.contentHash }.groupingBy { it }.eachCount()
                    val importedAssets = (supplied.values + export).associateBy { it.hash }
                    for (hash in publications + previousHashes) {
                        val count = counts[hash] ?: 0
                        val old = dao.getAssetByHash(hash)
                        if (count == 0) dao.deleteAsset(hash)
                        else if (old != null) dao.updateAsset(old.copy(refCount = count))
                        else {
                            val stored = importedAssets[hash] ?: throw IOException("Missing imported original metadata")
                            if (assetStore.getBlobFile(hash)?.isFile != true) throw IOException("Missing imported original")
                            dao.insertAsset(AssetEntity(hash, stored.relativePath, stored.sizeBytes, stored.originalName, stored.mimeType, stored.kind.name, count))
                        }
                    }
                }
                Pair(imported, parsed.ambiguousDateCount)
            } finally {
                attachments.values.forEach { try { it.close() } catch (_: Exception) { } }
                removeUnreferencedBlobs(publications + previousHashes)
            }
        }
    }
}
