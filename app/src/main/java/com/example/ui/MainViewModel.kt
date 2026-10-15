package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiEnrichmentResult
import com.example.ai.GeminiService
import com.example.ai.SearchResultItem
import com.example.migration.WhatsAppMigrationParser
import com.example.model.CollectionItem
import com.example.model.DateFilter
import com.example.model.ItemKind
import com.example.model.LibraryItem
import com.example.model.SharePayload
import com.example.model.SortOrder
import com.example.model.StorageStats
import com.example.reminder.ReminderScheduler
import com.example.storage.AssetManager
import com.example.storage.BackupManager
import com.example.storage.LibraryDatabaseHelper
import com.example.storage.RestoreSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val dbHelper = LibraryDatabaseHelper(application)
    private val assetManager = AssetManager(application)
    private val backupManager = BackupManager(application, dbHelper)
    private val reminderScheduler = ReminderScheduler(application)
    private val geminiService = GeminiService()

    private val _items = MutableStateFlow<List<LibraryItem>>(emptyList())
    val items: StateFlow<List<LibraryItem>> = _items.asStateFlow()

    private val _collections = MutableStateFlow<List<CollectionItem>>(emptyList())
    val collections: StateFlow<List<CollectionItem>> = _collections.asStateFlow()

    private val _trashItems = MutableStateFlow<List<LibraryItem>>(emptyList())
    val trashItems: StateFlow<List<LibraryItem>> = _trashItems.asStateFlow()

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()

    private val _selectedKind = MutableStateFlow<ItemKind?>(null)
    val selectedKind: StateFlow<ItemKind?> = _selectedKind.asStateFlow()

    private val _selectedCollectionId = MutableStateFlow<String?>(null)
    val selectedCollectionId: StateFlow<String?> = _selectedCollectionId.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.SAVED_NEWEST)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    private val _dateFilter = MutableStateFlow(DateFilter.ALL_TIME)
    val dateFilter: StateFlow<DateFilter> = _dateFilter.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _aiSearchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val aiSearchResults: StateFlow<List<SearchResultItem>> = _aiSearchResults.asStateFlow()

    private val _isAiSearching = MutableStateFlow(false)
    val isAiSearching: StateFlow<Boolean> = _isAiSearching.asStateFlow()

    private val _incomingShare = MutableStateFlow<SharePayload?>(null)
    val incomingShare: StateFlow<SharePayload?> = _incomingShare.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemIds: StateFlow<Set<String>> = _selectedItemIds.asStateFlow()

    private val _isBatchMode = MutableStateFlow(false)
    val isBatchMode: StateFlow<Boolean> = _isBatchMode.asStateFlow()

    init {
        refreshAll()
    }

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            val loadedItems = dbHelper.getAllItems(includeTrash = false)
            val loadedCollections = dbHelper.getAllCollections()
            val loadedTrash = dbHelper.getTrashItems()
            val stats = assetManager.calculateStorage(dbHelper)

            _items.value = loadedItems
            _collections.value = loadedCollections
            _trashItems.value = loadedTrash
            _storageStats.value = stats
        }
    }

    fun selectKind(kind: ItemKind?) {
        _selectedKind.value = kind
    }

    fun selectCollection(collectionId: String?) {
        _selectedCollectionId.value = collectionId
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
    }

    fun setDateFilter(filter: DateFilter) {
        _dateFilter.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _aiSearchResults.value = emptyList()
        }
    }

    fun createNote(title: String, content: String, collectionId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val cleanTitle = title.ifBlank {
                content.lines().firstOrNull()?.take(40) ?: "Untitled Note"
            }
            val item = LibraryItem(
                id = id,
                title = cleanTitle,
                content = content,
                kind = ItemKind.TEXT,
                collectionId = collectionId,
                createdAt = System.currentTimeMillis()
            )
            dbHelper.insertItem(item)
            refreshAll()
            enrichWithAi(item)
        }
    }

    fun saveLink(url: String, title: String, note: String, collectionId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val id = UUID.randomUUID().toString()
            val cleanTitle = title.ifBlank {
                val host = Uri.parse(url).host ?: url
                "Link: $host"
            }
            val item = LibraryItem(
                id = id,
                title = cleanTitle,
                content = if (note.isBlank()) url else "$url\n$note",
                kind = ItemKind.LINK,
                collectionId = collectionId,
                createdAt = System.currentTimeMillis()
            )
            dbHelper.insertItem(item)
            refreshAll()
            enrichWithAi(item)
        }
    }

    fun importUris(uris: List<Uri>, collectionId: String?, explicitKind: ItemKind? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            for (uri in uris) {
                val asset = assetManager.importUri(uri, explicitKind) ?: continue
                val id = UUID.randomUUID().toString()
                val item = LibraryItem(
                    id = id,
                    title = asset.fileName,
                    content = "File: ${asset.fileName} (${formatBytes(asset.sizeBytes)})",
                    kind = asset.kind,
                    localFilePath = asset.localPath,
                    originalFileName = asset.fileName,
                    mimeType = asset.mimeType,
                    fileSizeBytes = asset.sizeBytes,
                    collectionId = collectionId,
                    createdAt = System.currentTimeMillis()
                )
                dbHelper.insertItem(item)
                enrichWithAi(item)
            }
            refreshAll()
        }
    }

    private fun enrichWithAi(item: LibraryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            val aiResult: AiEnrichmentResult = geminiService.analyzeItem(item.title, item.content, item.kind)
            val updated = item.copy(
                aiDescription = aiResult.description,
                aiConcepts = aiResult.concepts,
                aiStatus = "READY"
            )
            dbHelper.updateItem(updated)
            refreshAll()
        }
    }

    fun triggerAiSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isAiSearching.value = true
            val activeItems = dbHelper.getAllItems(includeTrash = false)
            val results = geminiService.assistedSearch(query, activeItems)
            _aiSearchResults.value = results
            _isAiSearching.value = false
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = dbHelper.getItem(id) ?: return@launch
            dbHelper.setFavorite(id, !item.isFavorite)
            refreshAll()
        }
    }

    fun moveToTrash(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.moveToTrash(id)
            reminderScheduler.cancel(id)
            refreshAll()
        }
    }

    fun restoreFromTrash(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.restoreFromTrash(id)
            refreshAll()
        }
    }

    fun permanentDelete(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val filePath = dbHelper.permanentDelete(id)
            assetManager.deleteFile(filePath)
            refreshAll()
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            val paths = dbHelper.emptyTrash()
            for (path in paths) {
                assetManager.deleteFile(path)
            }
            refreshAll()
        }
    }

    fun setReminder(id: String, timeMillis: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.setReminder(id, timeMillis)
            val item = dbHelper.getItem(id) ?: return@launch
            if (timeMillis != null) {
                reminderScheduler.schedule(id, item.title, timeMillis)
            } else {
                reminderScheduler.cancel(id)
            }
            refreshAll()
        }
    }

    fun createCollection(name: String, icon: String, color: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val col = CollectionItem(
                id = "col_${UUID.randomUUID().toString().take(8)}",
                name = name.trim(),
                iconName = icon,
                colorHex = color
            )
            dbHelper.insertCollection(col)
            refreshAll()
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.deleteCollection(collectionId)
            if (_selectedCollectionId.value == collectionId) {
                _selectedCollectionId.value = null
            }
            refreshAll()
        }
    }

    fun setItemCollection(itemId: String, collectionId: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            dbHelper.updateCollection(itemId, collectionId)
            refreshAll()
        }
    }

    fun toggleSelection(itemId: String) {
        val current = _selectedItemIds.value.toMutableSet()
        if (current.contains(itemId)) current.remove(itemId) else current.add(itemId)
        _selectedItemIds.value = current
        _isBatchMode.value = current.isNotEmpty()
    }

    fun clearSelection() {
        _selectedItemIds.value = emptySet()
        _isBatchMode.value = false
    }

    fun batchMoveToTrash() {
        val ids = _selectedItemIds.value
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) {
                dbHelper.moveToTrash(id)
                reminderScheduler.cancel(id)
            }
            clearSelection()
            refreshAll()
        }
    }

    fun batchAssignCollection(collectionId: String?) {
        val ids = _selectedItemIds.value
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) {
                dbHelper.updateCollection(id, collectionId)
            }
            clearSelection()
            refreshAll()
        }
    }

    fun importWhatsAppChat(chatText: String, onDone: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val result = WhatsAppMigrationParser.parse(chatText)
            // Ensure Random Links collection exists
            val existingCols = dbHelper.getAllCollections()
            var randomLinksCol = existingCols.find { it.name.equals("Random Links", true) }
            if (randomLinksCol == null) {
                randomLinksCol = CollectionItem(
                    id = "col_random_links",
                    name = "Random Links",
                    iconName = "chat",
                    colorHex = "#10B981"
                )
                dbHelper.insertCollection(randomLinksCol)
            }

            var count = 0
            for (msg in result.parsedItems) {
                val title = when {
                    msg.extractedUrl != null -> {
                        val host = Uri.parse(msg.extractedUrl).host ?: "Link"
                        "Link from ${msg.sender ?: "WhatsApp"}: $host"
                    }
                    msg.attachmentFileName != null -> msg.attachmentFileName
                    else -> msg.rawText.take(40).replace("\n", " ")
                }

                val item = LibraryItem(
                    id = UUID.randomUUID().toString(),
                    title = title,
                    content = msg.rawText,
                    kind = msg.detectedKind,
                    collectionId = randomLinksCol.id,
                    sourceDate = msg.dateMillis,
                    createdAt = System.currentTimeMillis(),
                    originalFileName = msg.attachmentFileName,
                    aiStatus = "READY"
                )
                dbHelper.insertItem(item)
                count++
            }
            refreshAll()
            withContext(Dispatchers.Main) {
                onDone(count)
            }
        }
    }

    fun exportBackup(destinationUri: Uri): Boolean {
        return backupManager.exportBackup(destinationUri)
    }

    fun restoreBackup(sourceUri: Uri): RestoreSummary? {
        val summary = backupManager.restoreBackup(sourceUri)
        if (summary != null) refreshAll()
        return summary
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            assetManager.clearCache()
            refreshAll()
        }
    }

    fun setIncomingShare(payload: SharePayload?) {
        _incomingShare.value = payload
    }

    fun getFilteredItems(): List<LibraryItem> {
        val all = _items.value
        val kind = _selectedKind.value
        val colId = _selectedCollectionId.value
        val dateF = _dateFilter.value
        val sort = _sortOrder.value
        val query = _searchQuery.value.trim()

        val calendar = Calendar.getInstance()
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)

        return all.asSequence()
            .filter { item ->
                // Kind filter
                kind == null || item.kind == kind
            }
            .filter { item ->
                // Collection filter
                when (colId) {
                    null -> true
                    "computed_favorites" -> item.isFavorite
                    "computed_unfiled" -> item.collectionId == null
                    "computed_due" -> item.reminderTime != null && !item.isReminderDone
                    else -> item.collectionId == colId
                }
            }
            .filter { item ->
                // Date filter
                when (dateF) {
                    DateFilter.ALL_TIME -> true
                    DateFilter.THIS_MONTH -> {
                        calendar.timeInMillis = item.createdAt
                        calendar.get(Calendar.YEAR) == currentYear && calendar.get(Calendar.MONTH) == currentMonth
                    }
                    DateFilter.THIS_YEAR -> {
                        calendar.timeInMillis = item.createdAt
                        calendar.get(Calendar.YEAR) == currentYear
                    }
                    DateFilter.CUSTOM -> true
                }
            }
            .filter { item ->
                // Query filter
                if (query.isBlank()) true
                else {
                    item.title.contains(query, true) ||
                    item.content.contains(query, true) ||
                    (item.originalFileName ?: "").contains(query, true) ||
                    (item.aiDescription ?: "").contains(query, true) ||
                    (item.aiConcepts ?: "").contains(query, true)
                }
            }
            .sortedWith { a, b ->
                when (sort) {
                    SortOrder.SAVED_NEWEST -> b.createdAt.compareTo(a.createdAt)
                    SortOrder.SAVED_OLDEST -> a.createdAt.compareTo(b.createdAt)
                    SortOrder.SOURCE_NEWEST -> (b.sourceDate ?: b.createdAt).compareTo(a.sourceDate ?: a.createdAt)
                    SortOrder.TITLE_AZ -> a.title.compareTo(b.title, true)
                }
            }
            .toList()
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
