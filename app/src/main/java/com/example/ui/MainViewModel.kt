package com.example.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiGateway
import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.data.LibraryRepository
import com.example.model.*
import com.example.reminder.ReminderScheduler
import com.example.security.AppLockManager
import com.personallibrary.core.backup.RestorePreview
import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.model.SemanticVectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar

data class SemanticSearchResult(
    val item: ItemWithCollections,
    val explanation: String,
    val score: Float
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = LibraryRepository(application)
    val appLockManager = AppLockManager(application)
    private val reminderScheduler = ReminderScheduler(application)
    private val aiGateway = AiGateway(application)

    private val _items = MutableStateFlow<List<ItemWithCollections>>(emptyList())
    val items: StateFlow<List<ItemWithCollections>> = _items.asStateFlow()

    private val _collections = MutableStateFlow<List<CollectionEntity>>(emptyList())
    val collections: StateFlow<List<CollectionEntity>> = _collections.asStateFlow()

    private val _trashItems = MutableStateFlow<List<ItemEntity>>(emptyList())
    val trashItems: StateFlow<List<ItemEntity>> = _trashItems.asStateFlow()

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

    private val _aiSearchResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val aiSearchResults: StateFlow<List<SemanticSearchResult>> = _aiSearchResults.asStateFlow()

    private val _isAiSearching = MutableStateFlow(false)
    val isAiSearching: StateFlow<Boolean> = _isAiSearching.asStateFlow()

    private val _incomingShare = MutableStateFlow<SharePayload?>(null)
    val incomingShare: StateFlow<SharePayload?> = _incomingShare.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemIds: StateFlow<Set<String>> = _selectedItemIds.asStateFlow()

    private val _isBatchMode = MutableStateFlow(false)
    val isBatchMode: StateFlow<Boolean> = _isBatchMode.asStateFlow()

    init {
        // Enforce 30-day automatic Trash expiry on startup
        viewModelScope.launch(Dispatchers.IO) {
            repository.purgeExpiredTrash()
        }

        // Observe active items from Room
        viewModelScope.launch {
            repository.observeActiveItemsWithCollections().collect {
                _items.value = it
                updateStorageStats()
            }
        }

        // Observe collections from Room
        viewModelScope.launch {
            repository.observeCollections().collect {
                _collections.value = it
            }
        }

        // Observe trash items from Room
        viewModelScope.launch {
            repository.observeTrashItems().collect {
                _trashItems.value = it
                updateStorageStats()
            }
        }
    }

    private suspend fun updateStorageStats() = withContext(Dispatchers.IO) {
        val rootDir = File(getApplication<Application>().filesDir, "storage_root")
        val blobsDir = File(rootDir, "blobs")
        val blobsBytes = if (blobsDir.exists()) blobsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        val cacheBytes = getApplication<Application>().cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

        val active = _items.value
        val trash = _trashItems.value
        val trashBytes = trash.sumOf { it.fileSizeBytes }

        _storageStats.value = StorageStats(
            originalsBytes = blobsBytes,
            cacheBytes = cacheBytes,
            trashBytes = trashBytes,
            totalItems = active.size,
            trashedItems = trash.size
        )
    }

    fun selectKind(kind: ItemKind?) { _selectedKind.value = kind }
    fun selectCollection(colId: String?) { _selectedCollectionId.value = colId }
    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun setDateFilter(filter: DateFilter) { _dateFilter.value = filter }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) _aiSearchResults.value = emptyList()
    }

    fun createNote(title: String, content: String, collectionIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.createNote(title, content, collectionIds)
            enrichItem(item)
        }
    }

    fun saveLink(url: String, title: String, note: String, collectionIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.saveLink(url, title, note, collectionIds)
            enrichItem(item)
        }
    }

    fun importUris(uris: List<Uri>, collectionIds: List<String>, explicitSticker: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            for (uri in uris) {
                val mime = resolver.getType(uri) ?: "application/octet-stream"
                val name = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
                resolver.openInputStream(uri)?.use { stream ->
                    val item = repository.captureStream(stream, name, mime, explicitSticker, collectionIds)
                    enrichItem(item)
                }
            }
        }
    }

    private fun enrichItem(item: ItemEntity) {
        viewModelScope.launch(Dispatchers.IO) {
            val res = aiGateway.analyze(item.id, item.kind, item.content, item.originalFileName)
            repository.dao.updateItem(
                item.copy(
                    aiDescription = res.description,
                    aiConcepts = res.concepts,
                    aiStatus = "READY"
                )
            )
        }
    }

    fun triggerAiSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isAiSearching.value = true
            val active = _items.value
            val cols = _collections.value.map { it.name }
            val plan = aiGateway.getSearchPlan(query, cols)

            // Generate query embedding
            val queryVectors = aiGateway.embed(listOf(query))
            val queryVec = queryVectors.firstOrNull()

            val results = mutableListOf<SemanticSearchResult>()

            for (itemWithCols in active) {
                val item = itemWithCols.item
                var score = 0f
                val explanations = mutableListOf<String>()

                // 1. Vector similarity if embeddings exist
                if (queryVec != null) {
                    val itemText = "${item.title} ${item.content} ${item.aiDescription ?: ""} ${item.aiConcepts ?: ""}"
                    val itemVec = aiGateway.embed(listOf(itemText)).firstOrNull()
                    if (itemVec != null) {
                        val sim = SemanticVectorMath.cosineSimilarity(queryVec, itemVec)
                        if (sim > 0.4f) {
                            score += sim * 10f
                            explanations.add("Semantic concept match (similarity %.2f)".format(sim))
                        }
                    }
                }

                // 2. Search Plan concepts match
                val itemTextLower = "${item.title} ${item.content} ${item.aiDescription ?: ""} ${item.aiConcepts ?: ""}".lowercase()
                for (c in plan.concepts) {
                    if (itemTextLower.contains(c.lowercase())) {
                        score += 3f
                        explanations.add("Matched concept '$c'")
                    }
                }

                // 3. Target kind filter
                if (plan.targetKinds.isNotEmpty() && plan.targetKinds.contains(item.kind)) {
                    score += 2f
                    explanations.add("Filtered by ${item.kind.lowercase()} type")
                }

                if (score > 0f) {
                    results.add(
                        SemanticSearchResult(
                            item = itemWithCols,
                            explanation = explanations.distinct().take(2).joinToString(" • "),
                            score = score
                        )
                    )
                }
            }

            _aiSearchResults.value = results.sortedByDescending { it.score }
            _isAiSearching.value = false
        }
    }

    fun toggleFavorite(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.dao.getItemById(id) ?: return@launch
            repository.setFavorite(id, !item.isFavorite)
        }
    }

    fun moveToTrash(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.moveToTrash(id)
            reminderScheduler.cancel(id)
        }
    }

    fun restoreFromTrash(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.restoreFromTrash(id)
        }
    }

    fun deletePermanently(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteItemPermanently(id)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.emptyTrash()
        }
    }

    fun setReminder(id: String, timeMillis: Long?) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.dao.setReminder(id, timeMillis)
            val item = repository.dao.getItemById(id) ?: return@launch
            if (timeMillis != null) {
                reminderScheduler.schedule(id, item.title, timeMillis)
            } else {
                reminderScheduler.cancel(id)
            }
        }
    }

    fun createCollection(name: String, icon: String, color: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.createCollection(name, icon, color)
        }
    }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteCollection(collectionId)
            if (_selectedCollectionId.value == collectionId) {
                _selectedCollectionId.value = null
            }
        }
    }

    fun setItemCollections(itemId: String, collectionIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setItemCollections(itemId, collectionIds)
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
                repository.moveToTrash(id)
                reminderScheduler.cancel(id)
            }
            clearSelection()
        }
    }

    fun batchAssignCollection(collectionId: String) {
        val ids = _selectedItemIds.value
        viewModelScope.launch(Dispatchers.IO) {
            for (id in ids) {
                repository.dao.insertItemCollectionCrossRef(com.example.data.ItemCollectionCrossRef(id, collectionId))
            }
            clearSelection()
        }
    }

    suspend fun exportEncryptedBackup(uri: Uri, passphrase: CharArray): Boolean {
        return repository.exportEncryptedBackup(uri, passphrase)
    }

    suspend fun inspectBackup(uri: Uri, passphrase: CharArray): RestorePreview? {
        return repository.inspectBackup(uri, passphrase)
    }

    suspend fun commitRestore(preview: RestorePreview): Int {
        return repository.commitRestore(preview)
    }

    suspend fun importWhatsAppMigration(chatText: String, dateOrder: DateOrder?): Pair<Int, Int> {
        return repository.importWhatsAppMigration(chatText, dateOrder)
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            getApplication<Application>().cacheDir.deleteRecursively()
            getApplication<Application>().cacheDir.mkdirs()
            updateStorageStats()
        }
    }

    fun setIncomingShare(payload: SharePayload?) {
        _incomingShare.value = payload
    }

    fun getFilteredItems(): List<ItemWithCollections> {
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
            .filter { itemWithCols ->
                val item = itemWithCols.item
                kind == null || item.kind == kind.name
            }
            .filter { itemWithCols ->
                val item = itemWithCols.item
                val colIds = itemWithCols.collections.map { it.id }
                when (colId) {
                    null -> true
                    "computed_favorites" -> item.isFavorite
                    "computed_unfiled" -> colIds.isEmpty()
                    "computed_due" -> item.reminderTime != null && !item.isReminderDone
                    else -> colIds.contains(colId)
                }
            }
            .filter { itemWithCols ->
                val item = itemWithCols.item
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
            .filter { itemWithCols ->
                val item = itemWithCols.item
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
                val itemA = a.item
                val itemB = b.item
                when (sort) {
                    SortOrder.SAVED_NEWEST -> itemB.createdAt.compareTo(itemA.createdAt)
                    SortOrder.SAVED_OLDEST -> itemA.createdAt.compareTo(itemB.createdAt)
                    SortOrder.SOURCE_NEWEST -> (itemB.sourceDate ?: itemB.createdAt).compareTo(itemA.sourceDate ?: itemA.createdAt)
                    SortOrder.TITLE_AZ -> itemA.title.compareTo(itemB.title, true)
                }
            }
            .toList()
    }
}
