package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ItemWithCollections
import com.example.model.DateFilter
import com.example.model.ItemKind
import com.example.model.SortOrder
import com.example.ui.MainViewModel
import com.example.ui.components.ItemCard
import com.example.ui.components.TypeFilterChipStrip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: MainViewModel,
    onOpenItemDetail: (ItemWithCollections) -> Unit,
    onOpenVoiceSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAddDialog: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items by viewModel.localSearchResults.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val selectedKind by viewModel.selectedKind.collectAsState()
    val selectedColId by viewModel.selectedCollectionId.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val dateFilter by viewModel.dateFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isBatchMode by viewModel.isBatchMode.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()

    var showSortMenu by remember { mutableStateOf(false) }
    var showDateFilterMenu by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Personal Library",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${items.size} items",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                },
                actions = {
                    if (isBatchMode) {
                        IconButton(
                            onClick = { viewModel.batchMoveToTrash() },
                            modifier = Modifier.testTag("batch_delete_button")
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Trash selected", tint = MaterialTheme.colorScheme.error)
                        }
                        IconButton(
                            onClick = { viewModel.clearSelection() },
                            modifier = Modifier.testTag("batch_cancel_button")
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel selection")
                        }
                    } else {
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("settings_top_button")
                        ) {
                            Icon(Icons.Outlined.Settings, contentDescription = "Settings")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onOpenAddDialog,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add") },
                modifier = Modifier.testTag("fab_add")
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("library_list"),
            contentPadding = PaddingValues(bottom = 88.dp)
        ) {
            // Search Input Row with Mic button
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        placeholder = { Text("Search notes, photos, links...") },
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
                                    modifier = Modifier.testTag("mic_search_button")
                                ) {
                                    Icon(Icons.Filled.Mic, contentDescription = "Voice Search", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .testTag("library_search_input")
                    )
                }
            }

            // Type filter strip
            item {
                TypeFilterChipStrip(
                    selectedKind = selectedKind,
                    onKindSelected = { viewModel.selectKind(it) }
                )
            }

            // Controls row: Active Collection chip, Sort, and Date filter
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Active collection pill
                    if (selectedColId != null) {
                        val colName = when (selectedColId) {
                            "computed_favorites" -> "★ Favorites"
                            "computed_unfiled" -> "Unfiled"
                            "computed_due" -> "⏰ Due Reminders"
                            else -> collections.find { it.id == selectedColId }?.name ?: "Collection"
                        }
                        AssistChip(
                            onClick = { viewModel.selectCollection(null) },
                            label = { Text(colName, fontSize = 12.sp) },
                            trailingIcon = {
                                Icon(Icons.Filled.Close, contentDescription = "Clear collection", modifier = Modifier.size(14.dp))
                            },
                            modifier = Modifier.testTag("active_collection_chip")
                        )
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    Row {
                        // Date filter menu
                        Box {
                            TextButton(
                                onClick = { showDateFilterMenu = true },
                                modifier = Modifier.testTag("date_filter_button")
                            ) {
                                Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(dateFilter.label, fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = showDateFilterMenu,
                                onDismissRequest = { showDateFilterMenu = false }
                            ) {
                                for (df in DateFilter.values()) {
                                    DropdownMenuItem(
                                        text = { Text(df.label) },
                                        onClick = {
                                            viewModel.setDateFilter(df)
                                            showDateFilterMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        // Sort order menu
                        Box {
                            TextButton(
                                onClick = { showSortMenu = true },
                                modifier = Modifier.testTag("sort_order_button")
                            ) {
                                Icon(Icons.Outlined.Sort, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(sortOrder.label, fontSize = 12.sp)
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                for (so in SortOrder.values()) {
                                    DropdownMenuItem(
                                        text = { Text(so.label) },
                                        onClick = {
                                            viewModel.setSortOrder(so)
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Items list or empty state
            if (items.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "No matching items found" else "Your library is empty",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (searchQuery.isNotBlank()) "Try different keywords or filters" else "Tap '+' to add a note, save a link, or import files from WhatsApp",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            } else {
                items(items, key = { it.item.id }) { itemWithCols ->
                    ItemCard(
                        itemWithCols = itemWithCols,
                        fileResolver = { hash -> viewModel.repository.assetStore.getBlobFile(hash) },
                        onClick = { onOpenItemDetail(itemWithCols) },
                        onFavoriteToggle = { viewModel.toggleFavorite(itemWithCols.item.id) },
                        onDelete = { viewModel.moveToTrash(itemWithCols.item.id) },
                        isSelectMode = isBatchMode,
                        isSelected = selectedItemIds.contains(itemWithCols.item.id),
                        onSelectToggle = { viewModel.toggleSelection(itemWithCols.item.id) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}
