package com.example.ui

import android.app.Application
import android.content.Context
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
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemePreferences
import com.personallibrary.core.backup.RestorePreview
import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.model.SemanticVectorMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.time.Instant
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

data class SemanticSearchResult(
    val item: ItemWithCollections,
    val explanation: String,
    val score: Float,
    val matchingSnippet: String? = null,
    val source: String = "AI plan + keyword match",
    val pageNumber: Int? = null
)

data class GroupedLibrarySection(
    val title: String,
    val items: List<ItemWithCollections>
)

@OptIn(FlowPreview::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val trashEvents = Channel<List<String>>(Channel.UNLIMITED)
    val movedToTrash = trashEvents.receiveAsFlow()
    private val libraryMessageEvents = Channel<String>(Channel.BUFFERED)
    val libraryMessages = libraryMessageEvents.receiveAsFlow()

    val repository = LibraryRepository(application)
    val appLockManager = AppLockManager(application)
    val reminderScheduler = ReminderScheduler(application)
    val aiGateway = AiGateway(application)
    val localIndexer = LocalIndexer(application, repository)
    val captureState = (application as com.example.PersonalLibraryApp).captureState.state

    private val prefs = application.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
    private val themePreferences = ThemePreferences(prefs)
    val themeMode = themePreferences.mode

    fun setThemeMode(mode: ThemeMode) = themePreferences.setMode(mode)

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

    private val _dateBasis = MutableStateFlow(DateBasis.SAVED_DATE)
    val dateBasis: StateFlow<DateBasis> = _dateBasis.asStateFlow()

    private val _dateGrouping = MutableStateFlow(DateGrouping.NONE)
    val dateGrouping: StateFlow<DateGrouping> = _dateGrouping.asStateFlow()

    private val initialLayout = if (prefs.getString("library_layout", "LIST") == "GRID") LibraryLayout.GRID else LibraryLayout.LIST
    private val _libraryLayout = MutableStateFlow(initialLayout)
    val libraryLayout: StateFlow<LibraryLayout> = _libraryLayout.asStateFlow()

    private val _customDateStart = MutableStateFlow<Long?>(null)
    val customDateStart: StateFlow<Long?> = _customDateStart.asStateFlow()

    private val _customDateEnd = MutableStateFlow<Long?>(null)
    val customDateEnd: StateFlow<Long?> = _customDateEnd.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Filtered search results
    private val _localSearchResults = MutableStateFlow<List<ItemWithCollections>>(emptyList())
    val localSearchResults: StateFlow<List<ItemWithCollections>> = _localSearchResults.asStateFlow()
    private val resultGeneration = AtomicLong()
    private var filteredResultsJob: Job? = null
    private val _resultsReady = MutableStateFlow(false)
    val resultsReady = _resultsReady.asStateFlow()
    private val _selectingVisible = MutableStateFlow(false)
    val selectingVisible = _selectingVisible.asStateFlow()

    // Grouped library sections
    private val _groupedSections = MutableStateFlow<List<GroupedLibrarySection>>(emptyList())
    val groupedSections: StateFlow<List<GroupedLibrarySection>> = _groupedSections.asStateFlow()

    private val _aiSearchResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val aiSearchResults: StateFlow<List<SemanticSearchResult>> = _aiSearchResults.asStateFlow()

    private val _isAiSearching = MutableStateFlow(false)
    private var aiSearchJob: Job? = null
    val isAiSearching: StateFlow<Boolean> = _isAiSearching.asStateFlow()

    private val _incomingShare = MutableStateFlow<SharePayload?>(null)
    val incomingShare: StateFlow<SharePayload?> = _incomingShare.asStateFlow()

    private val _selectedItemIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedItemIds: StateFlow<Set<String>> = _selectedItemIds.asStateFlow()

    private val _isBatchMode = MutableStateFlow(false)
    val isBatchMode: StateFlow<Boolean> = _isBatchMode.asStateFlow()
    private val selectionGeneration = AtomicLong()

    private val _isBackendPaired = MutableStateFlow(aiGateway.identityManager.isPaired)
    val isBackendPaired: StateFlow<Boolean> = _isBackendPaired.asStateFlow()

    private val _lastSuccessfulBackup = MutableStateFlow<Long?>(repository.getLastSuccessfulBackupTime())
    val lastSuccessfulBackup: StateFlow<Long?> = _lastSuccessfulBackup.asStateFlow()

    init {
        // Enforce 30-day automatic Trash expiry on startup
        viewModelScope.launch(Dispatchers.IO) {
            repository.cleanAbandonedRestoreStages()
            repository.reconcileStorage()
            repository.purgeExpiredTrash()
            com.example.indexing.LocalIndexWorker.enqueue(application)
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

    fun setLibraryLayout(layout: LibraryLayout) {
        _libraryLayout.value = layout
        prefs.edit().putString("library_layout", layout.name).apply()
    }

    fun setDateBasis(basis: DateBasis) {
        _dateBasis.value = basis
        refreshFilteredResults()
    }

    fun setDateGrouping(grouping: DateGrouping) {
        _dateGrouping.value = grouping
        refreshFilteredResults()
    }

    private fun refreshFilteredResults() {
        val generation = resultGeneration.incrementAndGet()
        _resultsReady.value = false
        filteredResultsJob?.cancel()
        // Capture the entire filter context before the first suspension.
        val query = _searchQuery.value.trim()
        val sourceItems = _items.value
        val kind = _selectedKind.value
        val colId = _selectedCollectionId.value
        val dateF = _dateFilter.value
        val basis = _dateBasis.value
        val sort = _sortOrder.value
        val customStart = _customDateStart.value
        val customEnd = _customDateEnd.value
        val grouping = _dateGrouping.value
        filteredResultsJob = viewModelScope.launch(Dispatchers.IO) {
            val rawList = if (query.isBlank()) {
                sourceItems
            } else {
                repository.searchWithCollections(query)
            }

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
                    val timestamp = if (basis == DateBasis.SOURCE_DATE) item.sourceDate else item.createdAt

                    when (dateF) {
                        DateFilter.ALL_TIME -> true
                        DateFilter.THIS_MONTH -> {
                            if (timestamp == null) false
                            else {
                                calendar.timeInMillis = timestamp
                                calendar.get(Calendar.YEAR) == currentYear && calendar.get(Calendar.MONTH) == currentMonth
                            }
                        }
                        DateFilter.THIS_YEAR -> {
                            if (timestamp == null) false
                            else {
                                calendar.timeInMillis = timestamp
                                calendar.get(Calendar.YEAR) == currentYear
                            }
                        }
                        DateFilter.CUSTOM -> {
                            if (timestamp == null) false
                            else {
                                customStart != null && customEnd != null && timestamp >= customStart && timestamp <= customEnd
                            }
                        }
                    }
                }
                .sortedWith { a, b ->
                    val itemA = a.item
                    val itemB = b.item
                    val timeA = if (basis == DateBasis.SOURCE_DATE) (itemA.sourceDate ?: itemA.createdAt) else itemA.createdAt
                    val timeB = if (basis == DateBasis.SOURCE_DATE) (itemB.sourceDate ?: itemB.createdAt) else itemB.createdAt
                    when (sort) {
                        SortOrder.SAVED_NEWEST -> itemB.createdAt.compareTo(itemA.createdAt)
                        SortOrder.SAVED_OLDEST -> itemA.createdAt.compareTo(itemB.createdAt)
                        SortOrder.SOURCE_NEWEST -> (itemB.sourceDate ?: Long.MIN_VALUE).compareTo(itemA.sourceDate ?: Long.MIN_VALUE)
                        SortOrder.SOURCE_OLDEST -> (itemA.sourceDate ?: Long.MAX_VALUE).compareTo(itemB.sourceDate ?: Long.MAX_VALUE)
                        SortOrder.TITLE_AZ -> itemA.title.compareTo(itemB.title, true)
                    }
                }
                .toList()

            // Build grouped sections
            val sections = if (grouping == DateGrouping.NONE) {
                listOf(GroupedLibrarySection("", filtered))
            } else {
                val dayFormat = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
                val monthFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
                val yearFormat = SimpleDateFormat("yyyy", Locale.getDefault())

                val groups = mutableMapOf<String, MutableList<ItemWithCollections>>()
                for (itemWithCols in filtered) {
                    val ts = if (basis == DateBasis.SOURCE_DATE) itemWithCols.item.sourceDate else itemWithCols.item.createdAt
                    val key = if (ts == null) {
                        "No ${basis.label}"
                    } else {
                        val d = Date(ts)
                        when (grouping) {
                            DateGrouping.DAY -> dayFormat.format(d)
                            DateGrouping.MONTH -> monthFormat.format(d)
                            DateGrouping.YEAR -> yearFormat.format(d)
                            DateGrouping.NONE -> ""
                        }
                    }
                    groups.getOrOrPut(key) { mutableListOf() }.add(itemWithCols)
                }
                groups.map { GroupedLibrarySection(it.key, it.value) }
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation == resultGeneration.get()) {
                    _localSearchResults.value = filtered
                    _groupedSections.value = sections
                    _resultsReady.value = true
                }
            }
        }
    }

    private fun <K, V> MutableMap<K, V>.getOrOrPut(key: K, defaultValue: () -> V): V {
        return this[key] ?: run {
            val value = defaultValue()
            this[key] = value
            value
        }
    }

    fun buildMatchSnippet(item: ItemEntity, query: String): String? {
        if (query.isBlank()) return null
        val tokens = query.lowercase().split("\\s+".toRegex()).filter { it.length > 1 }
        if (tokens.isEmpty()) return null

        val candidates = listOfNotNull(
            item.title,
            item.content.takeIf { it.isNotBlank() },
            item.originalFileName,
            item.extractedText?.takeIf { it.isNotBlank() },
            item.aiDescription?.takeIf { it.isNotBlank() },
            item.aiConcepts?.takeIf { it.isNotBlank() }
        )

        for (text in candidates) {
            val lower = text.lowercase()
            for (token in tokens) {
                val idx = lower.indexOf(token)
                if (idx != -1) {
                    val start = maxOf(0, idx - 40)
                    val end = minOf(text.length, idx + token.length + 60)
                    val prefix = if (start > 0) "…" else ""
                    val suffix = if (end < text.length) "…" else ""
                    return prefix + text.substring(start, end).replace("\n", " ").trim() + suffix
                }
            }
        }
        return null
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
        aiSearchJob?.cancel()
        _isAiSearching.value = false
        _searchQuery.value = query
        _aiSearchResults.value = emptyList()
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
        startCapture(SharePayload(uris = uris.map { it.toString() }), collectionIds, explicitSticker)
    }

    private fun startCapture(payload: SharePayload, collectionIds: List<String>, sticker: Boolean = false): Boolean {
        val context = getApplication<Application>()
        val started = com.example.capture.CaptureService.start(context, payload, collectionIds, sticker)
        if (!started) android.widget.Toast.makeText(context, "Another save is running, or saving could not start. Keep the originals and try again.", android.widget.Toast.LENGTH_LONG).show()
        return started
    }

    fun confirmShare(collectionIds: List<String>) {
        val payload = _incomingShare.value ?: return
        if (startCapture(payload, collectionIds)) _incomingShare.value = null
    }
    fun cancelCapture() = com.example.capture.CaptureService.cancel(getApplication())
    fun dismissCaptureMessage() = (getApplication<Application>() as com.example.PersonalLibraryApp).captureState.dismiss()

    fun triggerAiSearch(query: String) {
        if (query.isBlank()) return
        aiSearchJob?.cancel()
        aiSearchJob = viewModelScope.launch(Dispatchers.IO) {
            _isAiSearching.value = true
            _aiSearchResults.value = emptyList()
            try {
            val active = _items.value
            val cols = _collections.value.map { it.name }
            val plan = aiGateway.getSearchPlan(query, cols)

            // Generate query embedding once online with gemini-embedding-2
            val queryVectors = try {
                aiGateway.embed(listOf(query))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                emptyList()
            }
            val queryVec = queryVectors.firstOrNull()?.takeIf { it.size == 768 && it.all(Float::isFinite) }
            // With neither a Gemini plan nor an embedding, keep ordinary local FTS visible.
            if (queryVec == null && plan.provenance != "GEMINI") return@launch

            // Group all stored search documents by item to support multi-chunk indexing
            val allDocs = repository.dao.getAllSearchDocuments()
            val docsByItem = allDocs.groupBy { it.itemId }

            val results = mutableListOf<SemanticSearchResult>()

            val startMillis = plan.dateRange.startIso?.let {
                try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
            }
            val endMillis = plan.dateRange.endIso?.let {
                try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
            }

            for (itemWithCols in active) {
                val item = itemWithCols.item

                if (plan.targetKinds.isNotEmpty() && !plan.targetKinds.contains(item.kind)) {
                    continue
                }

                if (startMillis != null && item.createdAt < startMillis) continue
                if (endMillis != null && item.createdAt > endMillis) continue

                var bestSim = 0f
                var bestChunkText = ""
                var bestPage: Int? = null
                val explanations = mutableListOf<String>()

                val itemDocs = docsByItem[item.id] ?: emptyList()
                for (doc in itemDocs) {
                    if (queryVec != null && doc.embedding != null && doc.embeddingDimension == 768 && doc.modelVersion == "gemini-embedding-2") {
                        try {
                            val chunkVec = doc.embedding.split(",").map { it.toFloat() }.toFloatArray()
                            val sim = SemanticVectorMath.compatibleSimilarity(queryVec, chunkVec, doc.embeddingDimension, doc.modelVersion)
                            if (sim > bestSim) {
                                bestSim = sim
                                bestChunkText = doc.textContent
                                bestPage = doc.pageNumber
                            }
                        } catch (_: Exception) {}
                    }
                }

                var score = 0f
                if (bestSim > 0.30f) {
                    score += bestSim * 10f
                    explanations.add("Semantic similarity %.2f".format(bestSim))
                }

                val itemTextLower = "${item.title} ${item.content} ${item.extractedText ?: ""} ${item.aiDescription ?: ""} ${item.aiConcepts ?: ""}".lowercase()
                for (c in if (plan.provenance == "GEMINI") plan.concepts else emptyList()) {
                    if (itemTextLower.contains(c.lowercase())) {
                        score += 3f
                        explanations.add("Concept '$c'")
                    }
                }

                if (score > 0f) {
                    val semantic = bestSim > 0.30f
                    val snippet = if (semantic) bestChunkText.take(180) else buildMatchSnippet(item, query)
                    results.add(
                        SemanticSearchResult(
                            item = itemWithCols,
                            explanation = explanations.distinct().take(3).joinToString(" • "),
                            score = score,
                            matchingSnippet = snippet?.ifBlank { null },
                            source = if (semantic) "Gemini semantic match" else "AI plan + keyword match",
                            pageNumber = if (semantic) bestPage else null
                        )
                    )
                }
            }

            ensureActive()
            if (_searchQuery.value == query) _aiSearchResults.value = results.sortedByDescending { it.score }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _aiSearchResults.value = emptyList() }
            finally { if (coroutineContext[Job]?.isActive == true) _isAiSearching.value = false }
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
            val ids = repository.moveToTrash(listOf(id))
            if (ids.isNotEmpty()) trashEvents.send(ids)
        }
    }

    fun undoTrash(ids: List<String>) {
        viewModelScope.launch(Dispatchers.IO) { repository.restoreFromTrash(ids) }
    }

    fun restoreFromTrash(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.restoreFromTrash(id)
        }
    }

    fun deletePermanently(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deletePermanently(id, requireTrash = true)
        }
    }

    fun emptyTrash() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.emptyTrash()
        }
    }

    fun setReminder(
        id: String,
        timeMillis: Long?,
        isExact: Boolean = true,
        isDateOnly: Boolean = false,
        localDate: String? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val item = repository.dao.getItemById(id) ?: return@launch
            repository.setReminder(id, timeMillis, isExact, isDateOnly, localDate)
            if (timeMillis != null) {
                reminderScheduler.schedule(id, item.title, timeMillis, isExact)
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

    /** Return a safe validation message while leaving the editor open on failure. */
    suspend fun saveCollection(collectionId: String?, name: String, icon: String, color: String): String? =
        withContext(Dispatchers.IO) {
            try {
                if (collectionId == null) repository.createCollection(name, icon, color)
                else repository.editCollection(collectionId, name, icon, color)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                invalid.message ?: "Check the collection name."
            } catch (_: Exception) {
                "Could not save the collection. Try again."
            }
        }

    suspend fun editItem(itemId: String, title: String, content: String): String? = withContext(Dispatchers.IO) {
        try {
            repository.editItem(itemId, title, content)
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            invalid.message ?: "Check the edited fields."
        } catch (_: Exception) {
            "Could not save the item. Try again."
        }
    }

    suspend fun getOrCreateRandomLinksCollection(): CollectionEntity = repository.getOrCreateRandomLinksCollection()

    fun restoreAllTrash() {
        val ids = _trashItems.value.map { it.id }
        viewModelScope.launch(Dispatchers.IO) {
            try { repository.restoreFromTrash(ids) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { libraryMessageEvents.send("Could not restore all items. Try again.") }
        }
    }

    suspend fun changeSelectedCollections(itemIds: Set<String>, collectionIds: Set<String>, add: Boolean): String? =
        withContext(Dispatchers.IO) {
            try {
                repository.changeItemCollections(itemIds, collectionIds, add)
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                invalid.message ?: "Check the selected collections."
            } catch (_: Exception) {
                "Could not update collections. Try again."
            }
        }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                repository.deleteCollection(collectionId)
                withContext(Dispatchers.Main.immediate) {
                    if (_selectedCollectionId.value == collectionId) {
                        _selectedCollectionId.value = null
                        refreshFilteredResults()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                libraryMessageEvents.send("Could not delete the collection. Saved items remain in Memora.")
            }
        }
    }

    fun setItemCollections(itemId: String, collectionIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.setItemCollections(itemId, collectionIds)
        }
    }

    fun isItemExcludedFromAi(itemId: String): Boolean {
        val set = prefs.getStringSet("excluded_items", emptySet()) ?: emptySet()
        return set.contains(itemId)
    }

    fun setItemAiExcluded(itemId: String, excluded: Boolean) {
        val current = (prefs.getStringSet("excluded_items", emptySet()) ?: emptySet()).toMutableSet()
        if (excluded) current.add(itemId) else current.remove(itemId)
        prefs.edit().putStringSet("excluded_items", current).apply()
        if (excluded) androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("ai_job_$itemId")
        viewModelScope.launch(Dispatchers.IO) {
            if (excluded) {
                repository.dao.updateAiStatus(itemId, "EXCLUDED")
                repository.dao.insertOrUpdateAnalysisJob(com.example.data.AnalysisJobEntity(itemId = itemId, status = "EXCLUDED"))
            } else {
                repository.dao.updateAiStatus(itemId, "QUEUED")
                val item = repository.dao.getItemById(itemId)
                if (item != null) {
                    localIndexer.indexItem(item)
                }
            }
        }
    }

    fun isCollectionExcludedFromAi(colId: String): Boolean {
        val set = prefs.getStringSet("excluded_collections", emptySet()) ?: emptySet()
        return set.contains(colId)
    }

    fun setCollectionAiExcluded(colId: String, excluded: Boolean) {
        val current = (prefs.getStringSet("excluded_collections", emptySet()) ?: emptySet()).toMutableSet()
        if (excluded) current.add(colId) else current.remove(colId)
        prefs.edit().putStringSet("excluded_collections", current).apply()
        viewModelScope.launch(Dispatchers.IO) {
            for (item in repository.dao.getAllActiveItems()) {
                if (colId in repository.dao.getCollectionIdsForItem(item.id)) {
                    if (excluded) {
                        androidx.work.WorkManager.getInstance(getApplication()).cancelUniqueWork("ai_job_${item.id}")
                        repository.dao.updateAiStatus(item.id, "EXCLUDED")
                        repository.dao.insertOrUpdateAnalysisJob(com.example.data.AnalysisJobEntity(itemId = item.id, status = "EXCLUDED"))
                    } else localIndexer.indexItem(item)
                }
            }
        }
    }

    fun setAiPaused(paused: Boolean) {
        prefs.edit().putBoolean("ai_paused", paused).apply()
        if (paused) {
            androidx.work.WorkManager.getInstance(getApplication()).cancelAllWorkByTag("ai_enrichment")
            viewModelScope.launch(Dispatchers.IO) {
                for (job in repository.dao.getPendingAnalysisJobs()) {
                    if (job.status in setOf("QUEUED", "PROCESSING")) repository.updateAnalysisJobIfCurrent(job.copy(status = "QUEUED"))
                }
            }
        } else resumePendingAiJobs()
    }

    fun resumePendingAiJobs() {
        viewModelScope.launch(Dispatchers.IO) {
            localIndexer.enqueuePendingAnalysisJobs()
        }
    }

    fun retryAiAnalysis(itemId: String) {
        viewModelScope.launch(Dispatchers.IO) { localIndexer.retryAnalysis(itemId) }
    }

    fun toggleSelection(itemId: String) {
        if (!_resultsReady.value || _selectingVisible.value) return
        selectionGeneration.incrementAndGet()
        val current = _selectedItemIds.value.toMutableSet()
        if (current.contains(itemId)) current.remove(itemId) else current.add(itemId)
        _selectedItemIds.value = current
        _isBatchMode.value = true
    }

    fun beginSelection() {
        selectionGeneration.incrementAndGet()
        _isBatchMode.value = true
    }

    fun selectCollectionItems(collectionId: String?) {
        clearSelection()
        _localSearchResults.value = emptyList()
        _groupedSections.value = emptyList()
        selectCollection(collectionId)
        beginSelection()
    }

    /** The caller supplies exactly the rows currently rendered by this screen. */
    fun selectAllVisible(visibleItemIds: () -> Collection<String>) {
        if (!_resultsReady.value || _selectingVisible.value) return
        val generation = resultGeneration.get()
        val selection = selectionGeneration.get()
        _selectingVisible.value = true
        viewModelScope.launch {
            try {
                val ids = withContext(Dispatchers.Default) { visibleItemIds().toSet() }
                if (generation == resultGeneration.get() && selection == selectionGeneration.get() && _resultsReady.value && _isBatchMode.value) {
                    _selectedItemIds.value = ids
                }
            } finally { _selectingVisible.value = false }
        }
    }

    fun clearSelection() {
        selectionGeneration.incrementAndGet()
        _selectedItemIds.value = emptySet()
        _isBatchMode.value = false
    }

    fun batchMoveToTrash() {
        val ids = _selectedItemIds.value
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val affected = repository.moveToTrash(ids)
                clearSelection()
                if (affected.isNotEmpty()) trashEvents.send(affected)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                libraryMessageEvents.send("Could not move the selected items to Trash. Try again.")
            }
        }
    }

    suspend fun pairBackend(enrollmentSecret: String): Boolean {
        return withContext(Dispatchers.IO) {
            val success = aiGateway.enrollWithSecret(enrollmentSecret)
            _isBackendPaired.value = success
            if (success) localIndexer.enqueuePendingAnalysisJobs(resumeUnpaired = true)
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

    fun discardRestorePreview(preview: RestorePreview) {
        // Cleanup survives cancellation of the screen's composition scope.
        viewModelScope.launch(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { repository.discardRestorePreview(preview) }
    }

    suspend fun commitRestore(preview: RestorePreview, keepBoth: Boolean = false): Int {
        return repository.commitRestore(preview, keepBoth)
    }

    suspend fun importWhatsAppMigration(
        chatText: String,
        dateOrder: DateOrder?,
        attachments: Map<String, InputStream> = emptyMap()
    ): Pair<Int, Int> {
        val res = repository.importWhatsAppMigration(chatText, dateOrder, attachments)
        withContext(kotlinx.coroutines.NonCancellable) { com.example.indexing.LocalIndexWorker.enqueue(getApplication()) }
        return res
    }

    suspend fun importWhatsAppFiles(chatText: String, dateOrder: DateOrder?, attachments: Map<String, Uri>, exportUri: Uri): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val resolver = getApplication<Application>().contentResolver
        val streams = mutableMapOf<String, InputStream>()
        val result = try {
            for ((name, uri) in attachments) streams[name] = resolver.openInputStream(uri) ?: throw java.io.IOException("Supplied attachment is unavailable")
            val original = resolver.openInputStream(exportUri) ?: throw java.io.IOException("Original export is unavailable")
            original.use { repository.importWhatsAppMigration(chatText, dateOrder, streams, originalExport = it) }
        } finally { streams.values.forEach { try { it.close() } catch (_: Exception) { } } }
        withContext(kotlinx.coroutines.NonCancellable) { com.example.indexing.LocalIndexWorker.enqueue(getApplication()) }
        result
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            // A restore preview owns private staging until commit/cancel. Cache cleanup must not
            // remove that active session; the staging owner separately retries abandoned cleanup.
            getApplication<Application>().cacheDir.listFiles()?.filterNot { it.name == "staging" }?.forEach { it.deleteRecursively() }
            repository.cleanAbandonedRestoreStages()
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
