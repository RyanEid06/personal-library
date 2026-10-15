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
import com.example.indexing.LocalIndexer
import com.example.model.*
import com.example.reminder.ReminderScheduler
import com.example.security.AppLockManager
import com.personallibrary.core.backup.RestorePreview
import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.model.SemanticVectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.util.Calendar

data class SemanticSearchResult(
    val item: ItemWithCollections,
    val explanation: String,
    val score: Float
)

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    val repository = LibraryRepository(application)
    val appLockManager = AppLockManager(application)
    val reminderScheduler = ReminderScheduler(application)
    val aiGateway = AiGateway(application)
    val localIndexer = LocalIndexer(application, repository)

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

    private val _customDateStart = MutableStateFlow<Long?>(null)
    val customDateStart: StateFlow<Long?> = _customDateStart.asStateFlow()

    private val _customDateEnd = MutableStateFlow<Long?>(null)
    val customDateEnd: StateFlow<Long?> = _customDateEnd.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Real FTS search result state
    private val _localSearchResults = MutableStateFlow<List<ItemWithCollections>>(emptyList())
    val localSearchResults: StateFlow<List<ItemWithCollections>> = _localSearchResults.asStateFlow()

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

    private val _isBackendPaired = MutableStateFlow(aiGateway.identityManager.isPaired)
    val isBackendPaired: StateFlow<Boolean> = _isBackendPaired.asStateFlow()

    private val _lastSuccessfulBackup = MutableStateFlow<Long?>(repository.getLastSuccessfulBackupTime())
    val lastSuccessfulBackup: StateFlow<Long?> = _lastSuccessfulBackup.asStateFlow()

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
                refreshFilteredResults()
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

        // Debounced FTS local search flow
        viewModelScope.launch {
            _searchQuery
                .debounce(150)
                .collect {
                    refreshFilteredResults()
                }
        }
    }

    private fun refreshFilteredResults() {
        viewModelScope.launch(Dispatchers.IO) {
            val query = _searchQuery.value.trim()
            val rawList = if (query.isBlank()) {
                _items.value
            } else {
                repository.searchWithCollections(query)
            }

            val kind = _selectedKind.value
            val colId = _selectedCollectionId.value
            val dateF = _dateFilter.value
            val sort = _sortOrder.value
            val customStart = _customDateStart.value
            val customEnd = _customDateEnd.value

            val calendar = Calendar.getInstance()
            val currentYear = calendar.get(Calendar.YEAR)
            val currentMonth = calendar.get(Calendar.MONTH)

            val filtered = rawList.asSequence()
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
                        DateFilter.CUSTOM -> {
                            val time = item.createdAt
                            (customStart == null || time >= customStart) &&
                                (customEnd == null || time <= customEnd)
                        }
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

            _localSearchResults.value = filtered
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

    fun selectKind(kind: ItemKind?) {
        _selectedKind.value = kind
        refreshFilteredResults()
    }

    fun selectCollection(colId: String?) {
        _selectedCollectionId.value = colId
        refreshFilteredResults()
    }

    fun setSortOrder(order: SortOrder) {
        _sortOrder.value = order
        refreshFilteredResults()
    }

    fun setDateFilter(filter: DateFilter) {
        _dateFilter.value = filter
        refreshFilteredResults()
    }

    fun setCustomDateRange(startMillis: Long?, endMillis: Long?) {
        _customDateStart.value = startMillis
        _customDateEnd.value = endMillis
        _dateFilter.value = DateFilter.CUSTOM
        refreshFilteredResults()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) _aiSearchResults.value = emptyList()
        refreshFilteredResults()
    }

    fun createNote(title: String, content: String, collectionIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.createNote(title, content, collectionIds)
            localIndexer.indexItem(item)
        }
    }

    fun saveLink(url: String, title: String, note: String, collectionIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.saveLink(url, title, note, collectionIds)
            localIndexer.indexItem(item)
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
                    localIndexer.indexItem(item)
                }
            }
        }
    }

    fun triggerAiSearch(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _isAiSearching.value = true
            val active = _items.value
            val cols = _collections.value.map { it.name }
            val plan = aiGateway.getSearchPlan(query, cols)

            // Generate query embedding once online with gemini-embedding-2
            val queryVectors = try {
                aiGateway.embed(listOf(query))
            } catch (_: Exception) {
                emptyList()
            }
            val queryVec = queryVectors.firstOrNull()

            // Group all stored search documents by item to support multi-chunk indexing
            val allDocs = repository.dao.getAllSearchDocuments()
            val docsByItem = allDocs.groupBy { it.itemId }

            val results = mutableListOf<SemanticSearchResult>()

            // Apply search plan filters
            val startMillis = plan.dateRange.startIso?.let {
                try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
            }
            val endMillis = plan.dateRange.endIso?.let {
                try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
            }

            for (itemWithCols in active) {
                val item = itemWithCols.item

                // 1. Target kind filter
                if (plan.targetKinds.isNotEmpty() && !plan.targetKinds.contains(item.kind)) {
                    continue
                }

                // 2. Date range filter
                if (startMillis != null && item.createdAt < startMillis) continue
                if (endMillis != null && item.createdAt > endMillis) continue

                var bestSim = 0f
                var bestChunkIndex = 0
                val explanations = mutableListOf<String>()

                // 3. Multi-chunk local cosine similarity scan
                val itemDocs = docsByItem[item.id] ?: emptyList()
                for (doc in itemDocs) {
                    if (queryVec != null && doc.embedding != null && doc.embeddingDimension == 768 && doc.modelVersion == "gemini-embedding-2") {
                        try {
                            val chunkVec = doc.embedding.split(",").map { it.toFloat() }.toFloatArray()
                            val sim = SemanticVectorMath.cosineSimilarity(queryVec, chunkVec)
                            if (sim > bestSim) {
                                bestSim = sim
                                bestChunkIndex = doc.chunkIndex
                            }
                        } catch (_: Exception) {}
                    }
                }

                var score = 0f
                if (bestSim > 0.30f) {
                    score += bestSim * 10f
                    explanations.add("Semantic similarity %.2f (chunk #%d)".format(bestSim, bestChunkIndex))
                }

                // 4. Concepts matching
                val itemTextLower = "${item.title} ${item.content} ${item.extractedText ?: ""} ${item.aiDescription ?: ""} ${item.aiConcepts ?: ""}".lowercase()
                for (c in plan.concepts) {
                    if (itemTextLower.contains(c.lowercase())) {
                        score += 3f
                        explanations.add("Concept '$c'")
                    }
                }

                if (score > 0f) {
                    results.add(
                        SemanticSearchResult(
                            item = itemWithCols,
                            explanation = explanations.distinct().take(3).joinToString(" • "),
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
            repository.deletePermanently(id)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.emptyTrash()
        }
    }

    fun setReminder(id: String, timeMillis: Long?, isExact: Boolean = true) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.dao.getItemById(id) ?: return@launch
            repository.setReminder(id, timeMillis, isExact)
            if (timeMillis != null) {
                reminderScheduler.schedule(id, item.title, timeMillis)
            } else {
                reminderScheduler.cancel(id)
            }
        }
    }

    fun completeReminder(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.completeReminder(id)
            reminderScheduler.cancel(id)
        }
    }

    fun snoozeReminder(id: String, newDueMillis: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.dao.getItemById(id)
            repository.snoozeReminder(id, newDueMillis)
            if (item != null) {
                reminderScheduler.schedule(id, item.title, newDueMillis)
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
                refreshFilteredResults()
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

    suspend fun pairBackend(enrollmentSecret: String): Boolean {
        return withContext(Dispatchers.IO) {
            val success = aiGateway.enrollWithSecret(enrollmentSecret)
            _isBackendPaired.value = success
            success
        }
    }

    fun unpairBackend() {
        aiGateway.identityManager.clearPairing()
        _isBackendPaired.value = false
    }

    suspend fun exportEncryptedBackup(uri: Uri, passphrase: CharArray): Boolean {
        val success = repository.exportEncryptedBackup(uri, passphrase)
        if (success) {
            _lastSuccessfulBackup.value = repository.getLastSuccessfulBackupTime()
        }
        return success
    }

    suspend fun inspectBackup(uri: Uri, passphrase: CharArray): RestorePreview? {
        return repository.inspectBackup(uri, passphrase)
    }

    suspend fun commitRestore(preview: RestorePreview): Int {
        return repository.commitRestore(preview)
    }

    suspend fun importWhatsAppMigration(
        chatText: String,
        dateOrder: DateOrder?,
        attachments: Map<String, InputStream> = emptyMap()
    ): Pair<Int, Int> {
        val res = repository.importWhatsAppMigration(chatText, dateOrder, attachments)
        // Enqueue local indexing for imported items
        val active = repository.dao.getAllActiveItems()
        for (item in active.filter { it.aiStatus == "PENDING" }) {
            localIndexer.indexItem(item)
        }
        return res
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
        return _localSearchResults.value
    }
}
