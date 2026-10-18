package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.ItemWithCollections
import com.example.model.*
import com.example.ui.MainViewModel
import com.example.ui.components.ItemCard
import com.example.ui.components.ItemGridCard
import com.example.ui.components.TypeFilterChipStrip
import com.example.ui.components.SelectionActions
import com.personallibrary.core.model.DateRange
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

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
    val groupedSections by viewModel.groupedSections.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val selectedKind by viewModel.selectedKind.collectAsState()
    val selectedColId by viewModel.selectedCollectionId.collectAsState()
    val sortOrder by viewModel.sortOrder.collectAsState()
    val dateFilter by viewModel.dateFilter.collectAsState()
    val dateBasis by viewModel.dateBasis.collectAsState()
    val dateGrouping by viewModel.dateGrouping.collectAsState()
    val libraryLayout by viewModel.libraryLayout.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val isBatchMode by viewModel.isBatchMode.collectAsState()
    val selectedItemIds by viewModel.selectedItemIds.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    BackHandler(enabled = isBatchMode) { viewModel.clearSelection() }
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

    var showSortMenu by remember { mutableStateOf(false) }
    var showDateFilterMenu by remember { mutableStateOf(false) }
    var showGroupingMenu by remember { mutableStateOf(false) }
    var showCustomRangeDialog by remember { mutableStateOf(false) }

    if (showCustomRangeDialog) {
        val rangeState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showCustomRangeDialog = false },
            confirmButton = {
                TextButton(
                    enabled = rangeState.selectedStartDateMillis != null && rangeState.selectedEndDateMillis != null,
                    onClick = {
                        val start = Instant.ofEpochMilli(rangeState.selectedStartDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                        val end = Instant.ofEpochMilli(rangeState.selectedEndDateMillis!!).atZone(ZoneOffset.UTC).toLocalDate()
                        val range = DateRange.days(start, end, ZoneId.systemDefault())
                        viewModel.setCustomDateRange(range.startInclusive.toEpochMilli(), range.endExclusive.toEpochMilli() - 1)
                        showCustomRangeDialog = false
                    }
                ) { Text("Apply range") }
            },
            dismissButton = { TextButton(onClick = { showCustomRangeDialog = false }) { Text("Cancel") } }
        ) {
            DateRangePicker(state = rangeState, modifier = Modifier.height(450.dp), title = { Text("Choose date range", modifier = Modifier.padding(16.dp)) })
        }
    }

    // Preserve scroll position
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isBatchMode) "${selectedItemIds.size} selected" else "${items.size} items • ${dateBasis.label}",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    if (isBatchMode) {
                        SelectionActions(viewModel, items.size, { items.map { it.item.id } }, selectedItemIds)
                    } else {
                        // Grid/List toggle
                        IconButton(
                            onClick = {
                                val next = if (libraryLayout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST
                                viewModel.setLibraryLayout(next)
                            },
                            modifier = Modifier.testTag("toggle_layout_button")
                        ) {
                            Icon(
                                if (libraryLayout == LibraryLayout.LIST) Icons.Filled.GridView else Icons.AutoMirrored.Filled.List,
                                contentDescription = "Toggle Grid/List View"
                            )
                        }

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // Search Input Row with Mic button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
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
                        .fillMaxWidth()
                        .testTag("library_search_input")
                )
            }

            // Type filter strip
            TypeFilterChipStrip(
                selectedKind = selectedKind,
                onKindSelected = { viewModel.selectKind(it) }
            )

            // Controls row: Active Collection chip, Grouping, Date Basis / Filter, Sort
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Active collection chip
                if (selectedColId != null) {
                    val colName = when (selectedColId) {
                        "computed_favorites" -> "★ Favorites"
                        "computed_unfiled" -> "Unfiled"
                        "computed_due" -> "⏰ Due Reminders"
                        else -> collections.find { it.id == selectedColId }?.name ?: "Collection"
                    }
                    AssistChip(
                        onClick = { viewModel.selectCollection(null) },
                        label = { Text(colName, fontSize = 11.sp) },
                        trailingIcon = {
                            Icon(Icons.Filled.Close, contentDescription = "Clear collection", modifier = Modifier.size(13.dp))
                        },
                        modifier = Modifier.testTag("active_collection_chip")
                    )
                } else {
                    Spacer(modifier = Modifier.width(4.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Grouping dropdown
                    Box {
                        TextButton(
                            onClick = { showGroupingMenu = true },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.testTag("grouping_button")
                        ) {
                            Icon(Icons.Filled.Layers, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(if (dateGrouping == DateGrouping.NONE) "Group" else dateGrouping.label, fontSize = 11.sp)
                        }
                        DropdownMenu(
                            expanded = showGroupingMenu,
                            onDismissRequest = { showGroupingMenu = false }
                        ) {
                            Text("Date Grouping", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp)
                            for (dg in DateGrouping.values()) {
                                DropdownMenuItem(
                                    text = { Text(dg.label) },
                                    onClick = {
                                        viewModel.setDateGrouping(dg)
                                        showGroupingMenu = false
                                    }
                                )
                            }
                        }
                    }

                    // Date filter & basis menu
                    Box {
                        TextButton(
                            onClick = { showDateFilterMenu = true },
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.testTag("date_filter_button")
                        ) {
                            Icon(Icons.Outlined.CalendarToday, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(dateFilter.label, fontSize = 11.sp)
                        }
                        DropdownMenu(
                            expanded = showDateFilterMenu,
                            onDismissRequest = { showDateFilterMenu = false }
                        ) {
                            Text("Date Basis", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp)
                            DropdownMenuItem(
                                text = { Text(if (dateBasis == DateBasis.SAVED_DATE) "✓ Saved Date" else "Saved Date") },
                                onClick = {
                                    viewModel.setDateBasis(DateBasis.SAVED_DATE)
                                    showDateFilterMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(if (dateBasis == DateBasis.SOURCE_DATE) "✓ Source Date" else "Source Date") },
                                onClick = {
                                    viewModel.setDateBasis(DateBasis.SOURCE_DATE)
                                    showDateFilterMenu = false
                                }
                            )
                            HorizontalDivider()
                            Text("Filter Range", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), fontSize = 12.sp)
                            for (df in DateFilter.values()) {
                                DropdownMenuItem(
                                    text = { Text(df.label) },
                                    onClick = {
                                        if (df == DateFilter.CUSTOM) showCustomRangeDialog = true else viewModel.setDateFilter(df)
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
                            contentPadding = PaddingValues(horizontal = 6.dp),
                            modifier = Modifier.testTag("sort_order_button")
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(sortOrder.label, fontSize = 11.sp)
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

            // Items Content (List or Grid)
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
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
            } else if (libraryLayout == LibraryLayout.GRID) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp)
                        .testTag("library_grid"),
                    contentPadding = PaddingValues(bottom = 88.dp, top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (section in groupedSections) {
                        if (section.title.isNotBlank()) {
                            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(2) }) {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp)
                                )
                            }
                        }
                        items(section.items, key = { it.item.id }) { itemWithCols ->
                            ItemGridCard(
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
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("library_list"),
                    contentPadding = PaddingValues(bottom = 88.dp, top = 4.dp)
                ) {
                    for (section in groupedSections) {
                        if (section.title.isNotBlank()) {
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = section.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }
                        items(section.items, key = { it.item.id }) { itemWithCols ->
                            val snippet = remember(itemWithCols.item, searchQuery) {
                                viewModel.buildMatchSnippet(itemWithCols.item, searchQuery)
                            }
                            ItemCard(
                                itemWithCols = itemWithCols,
                                fileResolver = { hash -> viewModel.repository.assetStore.getBlobFile(hash) },
                                onClick = { onOpenItemDetail(itemWithCols) },
                                onFavoriteToggle = { viewModel.toggleFavorite(itemWithCols.item.id) },
                                onDelete = { viewModel.moveToTrash(itemWithCols.item.id) },
                                isSelectMode = isBatchMode,
                                isSelected = selectedItemIds.contains(itemWithCols.item.id),
                                onSelectToggle = { viewModel.toggleSelection(itemWithCols.item.id) },
                                matchingSnippet = snippet,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
