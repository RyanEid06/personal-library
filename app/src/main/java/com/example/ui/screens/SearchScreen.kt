package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ItemWithCollections
import com.example.ui.MainViewModel
import com.example.ui.components.ItemCard
import com.example.ui.components.TypeFilterChipStrip
import com.example.ui.components.SelectionActions
import com.example.ui.components.getKindColor
import com.example.ui.components.getKindIcon
import com.personallibrary.core.model.ItemKind

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
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
    val isBatchMode by viewModel.isBatchMode.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()
    val visibleCount = if (aiResults.isNotEmpty()) aiResults.size else if (searchQuery.isNotBlank()) filteredItems.size else 0
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
                    if (isBatchMode) SelectionActions(viewModel, visibleCount, {
                        if (aiResults.isNotEmpty()) aiResults.map { it.item.item.id }
                        else if (searchQuery.isNotBlank()) filteredItems.map { it.item.id } else emptyList()
                    }, selectedItemIds)
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

                // Action buttons: "Ask AI"
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TypeFilterChipStrip(
                        selectedKind = selectedKind,
                        onKindSelected = { viewModel.selectKind(it) },
                        modifier = Modifier.weight(1f)
                    )

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

            HorizontalDivider()

            // Search Results
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("search_results_list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // If AI Results are active
                if (aiResults.isNotEmpty()) {
                    item {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Search matches (${aiResults.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }

                    items(aiResults, key = { "ai_${it.item.item.id}" }) { res ->
                        val item = res.item.item
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        if (isBatchMode) viewModel.toggleSelection(item.id) else onOpenItemDetail(res.item)
                                    },
                                    onLongClick = { viewModel.toggleSelection(item.id) }
                                ),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = if (item.id in selectedItemIds)
                                MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                if (isBatchMode) Checkbox(
                                    checked = item.id in selectedItemIds,
                                    onCheckedChange = { viewModel.toggleSelection(item.id) }
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Surface(
                                        color = getKindColor(item.kind).copy(alpha = 0.15f),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = item.kind,
                                            color = getKindColor(item.kind),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }

                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "${res.source}${res.pageNumber?.let { " • Page $it" } ?: ""}\n${res.explanation}",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            fontWeight = FontWeight.Medium,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )

                                res.matchingSnippet?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3)
                                }

                                if (item.content.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = item.content,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                } else if (searchQuery.isNotBlank()) {
                    // Local instant search results
                    item {
                        Text(
                            text = "Instant Keyword Matches (${filteredItems.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }

                    items(filteredItems, key = { it.item.id }) { itemWithCols ->
                        ItemCard(
                            itemWithCols = itemWithCols,
                            fileResolver = { hash -> viewModel.repository.assetStore.getBlobFile(hash) },
                            onClick = { onOpenItemDetail(itemWithCols) },
                            onFavoriteToggle = { viewModel.toggleFavorite(itemWithCols.item.id) },
                              onDelete = { viewModel.moveToTrash(itemWithCols.item.id) },
                              isSelectMode = isBatchMode,
                              isSelected = itemWithCols.item.id in selectedItemIds,
                              onSelectToggle = { viewModel.toggleSelection(itemWithCols.item.id) },
                              matchingSnippet = viewModel.buildMatchSnippet(itemWithCols.item, searchQuery)
                        )
                    }
                } else {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 64.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(56.dp),
                                    tint = MaterialTheme.colorScheme.outline
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Find anything in your personal archive",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Search by exact keywords, dates, OCR text, or tap 'Ask AI' for natural language retrieval.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
