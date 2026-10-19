package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ItemWithCollections
import com.example.ui.MainViewModel
import com.example.ui.components.TypeFilterChipStrip
import com.example.ui.components.SearchSelectionActions
import com.example.ui.components.ActiveSearchFilters
import com.example.ui.components.SearchResultsContent
import com.example.ui.components.hasSearchFilters

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: MainViewModel,
    onOpenItemDetail: (ItemWithCollections) -> Unit,
    onOpenVoiceSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val aiResults by viewModel.aiSearchResults.collectAsState()
    val isAiSearching by viewModel.isAiSearching.collectAsState()
    val filteredItems by viewModel.localSearchResults.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val selectedKind by viewModel.selectedKind.collectAsState()
    val allItems by viewModel.items.collectAsState()
    val resultsReady by viewModel.resultsReady.collectAsState()
    val localSearchError by viewModel.localSearchError.collectAsState()
    val aiSearchError by viewModel.aiSearchError.collectAsState()
    val selectedColId by viewModel.selectedCollectionId.collectAsState()
    val dateFilter by viewModel.dateFilter.collectAsState()
    val dateBasis by viewModel.dateBasis.collectAsState()
    val customStart by viewModel.customDateStart.collectAsState()
    val customEnd by viewModel.customDateEnd.collectAsState()
    val filtersActive = hasSearchFilters(selectedColId, selectedKind, dateFilter)
    val isBatchMode by viewModel.isBatchMode.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()
    BackHandler(enabled = isBatchMode) { viewModel.clearSelection() }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.movedToTrash.collect { ids ->
            val message = if (ids.size == 1) "Moved to Trash" else "${ids.size} items moved to Trash"
            if (snackbarHost.showSnackbar(message, actionLabel = "UNDO", withDismissAction = true) == SnackbarResult.ActionPerformed) {
                viewModel.undoTrash(ids)
            }
        }
    }
    LaunchedEffect(viewModel, snackbarHost) {
        viewModel.libraryMessages.collect { snackbarHost.showSnackbar(it, withDismissAction = true) }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isBatchMode) "${selectedItemIds.size} selected" else "Search Library",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (isBatchMode) SearchSelectionActions(
                        viewModel, searchQuery, filteredItems, aiResults, resultsReady, localSearchError, filtersActive, selectedItemIds
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Input with Mic and Ask AI
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    placeholder = { Text("Search by concept, note, file, or grade...") },
                    leadingIcon = {
                        Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Filled.Clear, contentDescription = "Clear")
                            }
                        } else {
                            IconButton(
                                onClick = onOpenVoiceSearch,
                                modifier = Modifier.testTag("search_voice_btn")
                            ) {
                                Icon(Icons.Filled.Mic, contentDescription = "Voice", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().testTag("search_main_input")
                )

                Spacer(modifier = Modifier.height(10.dp))

                TypeFilterChipStrip(
                    selectedKind = selectedKind,
                    onKindSelected = { viewModel.selectKind(it) }
                )

                // AI is an optional addition to offline local results.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = { viewModel.triggerAiSearch(searchQuery) },
                        enabled = searchQuery.isNotBlank() && !isAiSearching,
                        modifier = Modifier.testTag("ask_ai_search_button")
                    ) {
                        if (isAiSearching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Ask AI")
                    }
                }
            }

            ActiveSearchFilters(
                selectedColId, collections, selectedKind, dateFilter, dateBasis, customStart, customEnd,
                onClearCollection = { viewModel.selectCollection(null) },
                onClearKind = { viewModel.selectKind(null) },
                onClearDate = { viewModel.setDateFilter(com.example.model.DateFilter.ALL_TIME) },
                onClearFilters = viewModel::clearFilters
            )
            HorizontalDivider()

            SearchResultsContent(
                query = searchQuery,
                localResults = filteredItems,
                aiResults = aiResults,
                resultsReady = resultsReady,
                localError = localSearchError,
                aiError = aiSearchError,
                isAiSearching = isAiSearching,
                libraryEmpty = allItems.isEmpty(),
                filtersActive = filtersActive,
                isSelectMode = isBatchMode,
                selectedIds = selectedItemIds,
                fileResolver = { viewModel.repository.assetStore.getBlobFile(it) },
                snippet = viewModel::buildMatchSnippet,
                onOpen = onOpenItemDetail,
                onFavorite = viewModel::toggleFavorite,
                onTrash = viewModel::moveToTrash,
                onSelect = viewModel::toggleSelection,
                onClearFilters = viewModel::clearFilters
            )
        }
    }
}
