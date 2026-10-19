package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.annotation.MainThread
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiGateway
import com.example.ai.AiGatewayException
import com.example.ai.AiNetworkPolicy
import com.example.ai.AnswerResponse
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.time.Instant
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
    private val interactiveAiRetrieval = InteractiveAiRetrieval(repository.dao, prefs)
    private val aiNetworkPolicy = AiNetworkPolicy(application)
    private val aiPreferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("excluded_items", "excluded_collections", "ai_paused", "ai_wifi_only")) {
            viewModelScope.launch(Dispatchers.Main.immediate) { invalidateAiSearch() }
        }
    }
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
    private val _localSearchError = MutableStateFlow<String?>(null)
    val localSearchError: StateFlow<String?> = _localSearchError.asStateFlow()
    private val _resultsReady = MutableStateFlow(false)
    val resultsReady = _resultsReady.asStateFlow()
    private val _selectingVisible = MutableStateFlow(false)
    val selectingVisible = _selectingVisible.asStateFlow()

    // Grouped library sections
    private val _groupedSections = MutableStateFlow<List<GroupedLibrarySection>>(emptyList())
    val groupedSections: StateFlow<List<GroupedLibrarySection>> = _groupedSections.asStateFlow()

    private val _aiSearchResults = MutableStateFlow<List<SemanticSearchResult>>(emptyList())
    val aiSearchResults: StateFlow<List<SemanticSearchResult>> = _aiSearchResults.asStateFlow()

    private val _aiSearchError = MutableStateFlow<String?>(null)
    val aiSearchError: StateFlow<String?> = _aiSearchError.asStateFlow()

    private val _aiAnswer = MutableStateFlow<AiAnswerState?>(null)
    val aiAnswer: StateFlow<AiAnswerState?> = _aiAnswer.asStateFlow()

    private val _isAiSearching = MutableStateFlow(false)
    private var aiSearchJob: Job? = null
    private val aiSearchGeneration = AtomicLong()
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

    private val localSearch = LatestLocalSearch(
        scope = viewModelScope,
        search = repository::searchWithCollections,
        onInvalidated = {
            _resultsReady.value = false
            _localSearchError.value = null
            invalidateAiSearch()
        },
        onResult = { result ->
            _localSearchResults.value = result.items
            _groupedSections.value = result.sections
            _resultsReady.value = true
        },
        onError = { _localSearchError.value = "Could not search your library. Try again." }
    )

    init {
        prefs.registerOnSharedPreferenceChangeListener(aiPreferenceListener)
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
                refreshFilteredResults()
                viewModelScope.launch { updateStorageStats() }
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

    override fun onCleared() {
        prefs.unregisterOnSharedPreferenceChangeListener(aiPreferenceListener)
        super.onCleared()
    }

    fun setLibraryLayout(layout: LibraryLayout) {
        _libraryLayout.value = layout
        prefs.edit().putString("library_layout", layout.name).apply()
    }

    fun setDateBasis(basis: DateBasis) {
        if (_dateBasis.value == basis) return
        _dateBasis.value = basis
        refreshFilteredResults()
    }

    fun setDateGrouping(grouping: DateGrouping) {
        if (_dateGrouping.value == grouping) return
        _dateGrouping.value = grouping
        refreshFilteredResults()
    }

    private fun captureLocalSearchContext() = LocalSearchContext(
        query = _searchQuery.value.trim(), sourceItems = _items.value,
        kind = _selectedKind.value, collectionId = _selectedCollectionId.value,
        dateFilter = _dateFilter.value, basis = _dateBasis.value, sort = _sortOrder.value,
        customStart = _customDateStart.value, customEnd = _customDateEnd.value,
        grouping = _dateGrouping.value
    )

    private fun invalidateAiSearch() {
        aiSearchGeneration.incrementAndGet()
        aiSearchJob?.cancel()
        _isAiSearching.value = false
        _aiSearchResults.value = emptyList()
        _aiSearchError.value = null
        _aiAnswer.value = null
    }

    @MainThread
    private fun refreshFilteredResults(debounceQuery: Boolean = false) {
        localSearch.refresh(captureLocalSearchContext(), debounceQuery)
    }

    fun buildMatchSnippet(item: ItemEntity, query: String): String? = buildLocalMatchSnippet(item, query)

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
        if (_selectedKind.value == kind) return
        _selectedKind.value = kind
        refreshFilteredResults()
    }

    fun selectCollection(colId: String?) {
        if (_selectedCollectionId.value == colId) return
        _selectedCollectionId.value = colId
        refreshFilteredResults()
    }

    fun setSortOrder(order: SortOrder) {
        if (_sortOrder.value == order) return
        _sortOrder.value = order
        refreshFilteredResults()
    }

    fun setDateFilter(filter: DateFilter) {
        if (_dateFilter.value == filter) return
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
        val previous = _searchQuery.value
        _searchQuery.value = query
        if (previous.trim() == query.trim()) return
        refreshFilteredResults(debounceQuery = true)
    }

    /** Query and display preferences remain intact; every restrictive filter is reset together. */
    fun clearFilters() {
        _selectedCollectionId.value = null
        _selectedKind.value = null
        _dateFilter.value = DateFilter.ALL_TIME
        _customDateStart.value = null
        _customDateEnd.value = null
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
        if (query.isBlank() || query.trim() != _searchQuery.value.trim()) return
        aiSearchJob?.cancel()
        val requestGeneration = aiSearchGeneration.incrementAndGet()
        val contextGeneration = localSearch.currentGeneration
        // AI retrieval queries Room directly, rather than scanning the observed library snapshot.
        val context = captureLocalSearchContext().copy(sourceItems = emptyList())
        _aiSearchResults.value = emptyList()
        _aiSearchError.value = null
        _aiAnswer.value = null
        if (query.length > 2000) {
            _isAiSearching.value = false
            _aiSearchError.value = "Use a question of at most 2,000 characters. Your local results are still available."
            return
        }
        if (!aiGateway.identityManager.isPaired) {
            _isAiSearching.value = false
            _aiSearchError.value = "AI search is unavailable. Pair this device in Settings to use AI."
            return
        }
        _isAiSearching.value = true
        fun isCurrentRequest() = requestGeneration == aiSearchGeneration.get() &&
            contextGeneration == localSearch.currentGeneration
        aiSearchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                aiNetworkPolicy.requireAllowed()
                val snapshot = interactiveAiRetrieval.load(context)
                val beforeRequest: suspend () -> Unit = {
                    ensureActive()
                    interactiveAiRetrieval.requireCurrent(snapshot, ::isCurrentRequest)
                    aiNetworkPolicy.requireAllowed()
                }
                if (snapshot.items.isEmpty()) {
                    withContext(Dispatchers.Main.immediate) {
                        beforeRequest()
                        if (isCurrentRequest()) _aiAnswer.value = AiAnswerState(AnswerResponse("INSUFFICIENT", "", emptyList()), emptyList())
                    }
                    return@launch
                }
                // The gateway invokes this guard again at every auth/content dispatch and retry.
                val plan = aiGateway.getSearchPlan(query, snapshot.knownCollectionNames, beforeRequest = beforeRequest)

                // Generate query embedding once online with gemini-embedding-2
                val queryVectors = try {
                    aiGateway.embed(listOf(query), beforeRequest = beforeRequest)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (blocked: AiGatewayException.RequestBlockedException) { throw blocked }
                catch (blocked: AiGatewayException.NetworkPolicyException) { throw blocked }
                catch (_: Exception) {
                    emptyList()
                }
                beforeRequest()
                val queryVec = queryVectors.firstOrNull()?.takeIf { it.size == 768 && it.all(Float::isFinite) }
                // Answer evidence can still come from local FTS/text when semantic planning
                // is unavailable. No original media is opened during this interactive flow.

                val docsByItem = snapshot.documents.groupBy { it.itemId }

                val results = mutableListOf<SemanticSearchResult>()

                val startMillis = plan.dateRange.startIso?.let {
                    try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
                }
                val endMillis = plan.dateRange.endIso?.let {
                    try { Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
                }

                for (itemWithCols in snapshot.items) {
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
                        if (queryVec != null && doc.embedding != null && doc.embedding.length <= 24_000 &&
                            doc.embeddingDimension == 768 && doc.modelVersion == "gemini-embedding-2") {
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

                    val itemTextLower = listOf(item.title.take(1000), item.content.take(8000),
                        item.extractedText.orEmpty().take(12000), item.aiDescription.orEmpty().take(8000),
                        item.aiConcepts.orEmpty().take(1500)).joinToString(" ").lowercase()
                    for (c in if (plan.provenance == "GEMINI") plan.concepts.take(40).map { it.take(200) }.filter { it.isNotBlank() } else emptyList()) {
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
                val rankedResults = results.sortedByDescending { it.score }
                withContext(Dispatchers.Main.immediate) {
                    beforeRequest()
                    if (isCurrentRequest()) _aiSearchResults.value = rankedResults
                }
                val selected = selectAnswerEvidence(query, snapshot, rankedResults)
                val answer = if (selected.evidence.isEmpty()) AnswerResponse("INSUFFICIENT", "", emptyList())
                    else aiGateway.answer(query, selected.evidence, beforeRequest = beforeRequest)
                val evidenceById = selected.evidence.associateBy { it.sourceId }
                val sources = answer.sourceIds.map { sourceId ->
                    val evidence = evidenceById[sourceId] ?: throw AiGatewayException.ValidationException()
                    val item = selected.sourceItems[sourceId] ?: throw AiGatewayException.ValidationException()
                    AiAnswerSource(sourceId, item, evidence.pageNumber)
                }
                withContext(Dispatchers.Main.immediate) {
                    beforeRequest()
                    if (isCurrentRequest()) _aiAnswer.value = AiAnswerState(answer, sources)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                withContext(Dispatchers.Main.immediate) {
                    if (isCurrentRequest()) {
                        _aiAnswer.value = null
                        if (failure is AiGatewayException.RequestBlockedException) _aiSearchResults.value = emptyList()
                        _aiSearchError.value = when (failure) {
                            is AiGatewayException.AnswerUnavailableException ->
                                "Direct answers are not available on this backend yet. Your local results and related matches are still available."
                            is AiGatewayException.UnpairedException, is AiGatewayException.AuthenticationException ->
                                "AI search is unavailable. Pair this device again in Settings."
                            is AiGatewayException.NetworkPolicyException ->
                                "AI search needs an allowed connection. When Wi-Fi-only is on, connect to an unmetered network. Your local results are still available."
                            is AiGatewayException.RequestBlockedException ->
                                "AI search stopped because privacy settings or library content changed. Your local results are still available. Try again when AI is enabled."
                            else -> "AI search is unavailable right now. Your local results are still available. Try again later."
                        }
                    }
                }
            }
            finally {
                if (coroutineContext[Job]?.isActive == true) withContext(Dispatchers.Main.immediate) {
                    if (isCurrentRequest()) _isAiSearching.value = false
                }
            }
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
            var updated = false
            try {
                repository.changeItemCollections(itemIds, collectionIds, add)
                updated = true
                // Finish the committed membership change even when its caller or screen closes.
                withContext(NonCancellable + Dispatchers.IO) {
                    withContext(Dispatchers.Main.immediate) { invalidateAiSearch() }
                    localIndexer.reconcileAiEligibility(itemIds)
                }
                ensureActive()
                null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (invalid: IllegalArgumentException) {
                ensureActive()
                if (updated) "Collections updated, but AI processing could not refresh. Try again."
                else invalid.message ?: "Check the selected collections."
            } catch (_: Exception) {
                ensureActive()
                if (updated) "Collections updated, but AI processing could not refresh. Try again."
                else "Could not update collections. Try again."
            }
        }

    fun deleteCollection(collectionId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            var deleted = false
            try {
                val affectedItemIds = repository.dao.getItemIdsForCollection(collectionId)
                repository.deleteCollection(collectionId)
                deleted = true
                withContext(NonCancellable + Dispatchers.IO) {
                    withContext(Dispatchers.Main.immediate) {
                        invalidateAiSearch()
                        if (_selectedCollectionId.value == collectionId) {
                            _selectedCollectionId.value = null
                            refreshFilteredResults()
                        }
                    }
                    localIndexer.reconcileAiEligibility(affectedItemIds)
                }
                ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                libraryMessageEvents.send(if (deleted) "Collection deleted, but AI processing could not refresh. Saved items remain in Memora."
                    else "Could not delete the collection. Saved items remain in Memora.")
            }
        }
    }

    fun setItemCollections(itemId: String, collectionIds: List<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            var updated = false
            try {
                repository.setItemCollections(itemId, collectionIds)
                updated = true
                withContext(NonCancellable + Dispatchers.IO) {
                    withContext(Dispatchers.Main.immediate) { invalidateAiSearch() }
                    localIndexer.reconcileAiEligibility(listOf(itemId))
                }
                ensureActive()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                libraryMessageEvents.send(if (updated) "Collections updated, but AI processing could not refresh. Try again."
                    else "Could not update collections. Try again.")
            }
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
        invalidateAiSearch()
        viewModelScope.launch(Dispatchers.IO) {
            localIndexer.reconcileAiEligibility(listOf(itemId))
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
        invalidateAiSearch()
        viewModelScope.launch(Dispatchers.IO) {
            localIndexer.reconcileAiEligibility(repository.dao.getItemIdsForCollection(colId))
        }
    }

    fun setAiPaused(paused: Boolean) {
        prefs.edit().putBoolean("ai_paused", paused).apply()
        invalidateAiSearch()
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
        selectCollection(collectionId)
        beginSelection()
    }

    /** The caller supplies exactly the rows currently rendered by this screen. */
    fun selectAllVisible(visibleItemIds: () -> Collection<String>) {
        if (!_resultsReady.value || _selectingVisible.value) return
        val generation = localSearch.currentGeneration
        val selection = selectionGeneration.get()
        _selectingVisible.value = true
        viewModelScope.launch {
            try {
                val ids = withContext(Dispatchers.Default) { visibleItemIds().toSet() }
                if (generation == localSearch.currentGeneration && selection == selectionGeneration.get() && _resultsReady.value && _isBatchMode.value) {
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
        invalidateAiSearch()
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
