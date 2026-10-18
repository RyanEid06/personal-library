package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.MainViewModel
import com.example.data.CollectionEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CollectionsScreen(
    viewModel: MainViewModel,
    onCollectionSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    onSelectCollectionItems: (String?) -> Unit = onCollectionSelected
) {
    val collections by viewModel.collections.collectAsState()
    val allItems by viewModel.items.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) {
        viewModel.libraryMessages.collect { snackbarHost.showSnackbar(it, withDismissAction = true) }
    }

    val favoritesCount = allItems.count { it.item.isFavorite }
    val unfiledCount = allItems.count { it.collections.isEmpty() }
    val dueCount = allItems.count { it.item.reminderTime != null && !it.item.isReminderDone }

    var showCreateDialog by remember { mutableStateOf(false) }
    var editingCollection by remember { mutableStateOf<CollectionEntity?>(null) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (showCreateDialog || editingCollection != null) {
        CreateCollectionDialog(
            onDismiss = { if (!saving) { showCreateDialog = false; editingCollection = null; saveError = null } },
            onConfirm = { name, icon, color ->
                saving = true
                scope.launch {
                    saveError = viewModel.saveCollection(editingCollection?.id, name, icon, color)
                    saving = false
                    if (saveError == null) { showCreateDialog = false; editingCollection = null }
                }
            },
            initialCollection = editingCollection,
            existingCollections = collections,
            errorMessage = saveError,
            isSaving = saving
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Collections",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(
                        onClick = { showCreateDialog = true },
                        modifier = Modifier.testTag("add_collection_button")
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add Collection")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .testTag("collections_grid"),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Computed: Favorites
            item {
                CollectionCard(
                    title = "Favorites",
                    itemCount = favoritesCount,
                    icon = Icons.Filled.Star,
                    color = Color(0xFFF59E0B),
                    onClick = { onCollectionSelected("computed_favorites") },
                    onSelectItems = { onSelectCollectionItems("computed_favorites") },
                    modifier = Modifier.testTag("col_card_favorites")
                )
            }

            // Computed: Unfiled
            item {
                CollectionCard(
                    title = "Unfiled",
                    itemCount = unfiledCount,
                    icon = Icons.Filled.Inbox,
                    color = Color(0xFF6B7280),
                    onClick = { onCollectionSelected("computed_unfiled") },
                    onSelectItems = { onSelectCollectionItems("computed_unfiled") },
                    modifier = Modifier.testTag("col_card_unfiled")
                )
            }

            // Computed: Due Reminders
            item {
                CollectionCard(
                    title = "Due Reminders",
                    itemCount = dueCount,
                    icon = Icons.Filled.Alarm,
                    color = Color(0xFF0D9488),
                    onClick = { onCollectionSelected("computed_due") },
                    onSelectItems = { onSelectCollectionItems("computed_due") },
                    modifier = Modifier.testTag("col_card_due")
                )
            }

            // User Collections
            items(collections, key = { it.id }) { col ->
                val count = allItems.count { itemWithCols -> itemWithCols.collections.any { it.id == col.id } }
                var showDeleteConfirm by remember { mutableStateOf(false) }

                if (showDeleteConfirm) {
                    AlertDialog(
                        onDismissRequest = { showDeleteConfirm = false },
                        title = { Text("Delete Collection?") },
                        text = { Text("Saved items will remain in Memora. Their membership in this collection will be removed. Membership in other collections will be preserved.") },
                        confirmButton = {
                            Button(
                                onClick = {
                                    viewModel.deleteCollection(col.id)
                                    showDeleteConfirm = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Delete")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                        }
                    )
                }

                var showPrivacy by remember { mutableStateOf(false) }
                var excluded by remember(col.id, showPrivacy) { mutableStateOf(viewModel.isCollectionExcludedFromAi(col.id)) }
                if (showPrivacy) {
                    AlertDialog(
                        onDismissRequest = { showPrivacy = false },
                        title = { Text("AI Privacy") },
                        text = {
                            Column {
                                Text("Exclude '${col.name}' from AI analysis and answers. Items remain available in local search.")
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("Exclude from AI", modifier = Modifier.weight(1f))
                                    Switch(checked = excluded, onCheckedChange = { excluded = it }, modifier = Modifier.testTag("collection_ai_switch"))
                                }
                            }
                        },
                        confirmButton = { TextButton(onClick = { viewModel.setCollectionAiExcluded(col.id, excluded); showPrivacy = false }) { Text("Save") } },
                        dismissButton = { TextButton(onClick = { showPrivacy = false }) { Text("Cancel") } }
                    )
                }
                CollectionCard(
                    title = col.name,
                    itemCount = count,
                    icon = parseIconName(col.iconName),
                    color = parseColorHex(col.colorHex),
                    onClick = { onCollectionSelected(col.id) },
                    onEdit = { saveError = null; editingCollection = col },
                    onSelectItems = { onSelectCollectionItems(col.id) },
                    onAiPrivacy = { showPrivacy = true },
                    onDelete = { showDeleteConfirm = true },
                    modifier = Modifier.testTag("col_card_${col.id}")
                )
            }
        }
    }
}

@Composable
fun CollectionCard(
    title: String,
    itemCount: Int,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onEdit: (() -> Unit)? = null,
    onSelectItems: (() -> Unit)? = null,
    onAiPrivacy: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 152.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp).background(color.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
                }
                if (onEdit != null || onSelectItems != null || onAiPrivacy != null || onDelete != null) {
                    Box {
                        IconButton(onClick = { showMenu = true }, modifier = Modifier.testTag("collection_overflow")) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Manage $title")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            onEdit?.let { DropdownMenuItem(text = { Text("Edit Collection") }, onClick = { showMenu = false; it() }) }
                            DropdownMenuItem(text = { Text("Open") }, onClick = { showMenu = false; onClick() })
                            onSelectItems?.let { DropdownMenuItem(text = { Text("Select Items") }, onClick = { showMenu = false; it() }) }
                            onAiPrivacy?.let { DropdownMenuItem(text = { Text("AI Privacy") }, onClick = { showMenu = false; it() }) }
                            onDelete?.let { DropdownMenuItem(text = { Text("Delete Collection") }, onClick = { showMenu = false; it() }) }
                        }
                    }
                }
            }

            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "$itemCount item${if (itemCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
fun CreateCollectionDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit,
    initialCollection: CollectionEntity? = null,
    existingCollections: List<CollectionEntity> = emptyList(),
    errorMessage: String? = null,
    isSaving: Boolean = false
) {
    var name by remember(initialCollection?.id) { mutableStateOf(initialCollection?.name.orEmpty()) }
    var selectedIcon by remember(initialCollection?.id) { mutableStateOf(initialCollection?.iconName ?: "folder") }
    var selectedColor by remember(initialCollection?.id) { mutableStateOf(initialCollection?.colorHex ?: "#3B82F6") }
    val trimmedName = name.trim()
    val validationError = when {
        trimmedName.isEmpty() -> "Enter a collection name"
        existingCollections.any { it.id != initialCollection?.id && it.name.trim().equals(trimmedName, ignoreCase = true) } -> "A collection with this name already exists"
        listOf("Favorites", "Unfiled", "Reminders", "Due Reminders").any { it.equals(trimmedName, ignoreCase = true) } -> "This name is reserved for a system collection"
        else -> null
    }

    val iconOptions = listOf(
        "folder" to Icons.Filled.Folder,
        "school" to Icons.Filled.School,
        "chat" to Icons.AutoMirrored.Filled.Chat,
        "star" to Icons.Filled.Star,
        "mood" to Icons.Filled.Mood,
        "work" to Icons.Filled.Work,
        "favorite" to Icons.Filled.Favorite
    )

    val colorOptions = listOf(
        "#3B82F6", "#10B981", "#F59E0B", "#EC4899", "#8B5CF6", "#06B6D4", "#EF4444"
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(if (initialCollection == null) "New Collection" else "Edit Collection", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    enabled = !isSaving,
                    isError = validationError != null && name.isNotEmpty(),
                    supportingText = { if (name.isNotEmpty() && validationError != null) Text(validationError) },
                    label = { Text("Collection Name") },
                    placeholder = { Text("e.g. Physics, Random Links, Memes") },
                    modifier = Modifier.fillMaxWidth().testTag("new_col_name_input")
                )

                Spacer(modifier = Modifier.height(14.dp))

                Text("Icon:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for ((iconName, vector) in iconOptions) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(if (selectedIcon == iconName) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                                .semantics { selected = selectedIcon == iconName }
                                .clickable(enabled = !isSaving) { selectedIcon = iconName },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                vector,
                                contentDescription = "Icon $iconName",
                                modifier = Modifier.size(18.dp),
                                tint = if (selectedIcon == iconName) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Color:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (hex in colorOptions) {
                        val parsed = parseColorHex(hex)
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(parsed)
                                .semantics { contentDescription = "Color $hex"; selected = selectedColor == hex }
                                .clickable(enabled = !isSaving) { selectedColor = hex },
                            contentAlignment = Alignment.Center
                        ) {
                            if (selectedColor == hex) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                if (errorMessage != null) Text(errorMessage, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("collection_save_error"))
                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !isSaving) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (validationError == null) onConfirm(trimmedName, selectedIcon, selectedColor)
                        },
                        enabled = validationError == null && !isSaving,
                        modifier = Modifier.testTag("create_col_confirm_button")
                    ) {
                        Text(if (isSaving) "Saving…" else if (initialCollection == null) "Create" else "Save")
                    }
                }
            }
        }
    }
}

fun parseIconName(name: String): ImageVector {
    return when (name) {
        "school" -> Icons.Filled.School
        "chat" -> Icons.AutoMirrored.Filled.Chat
        "star" -> Icons.Filled.Star
        "mood" -> Icons.Filled.Mood
        "work" -> Icons.Filled.Work
        "favorite" -> Icons.Filled.Favorite
        else -> Icons.Filled.Folder
    }
}

fun parseColorHex(hex: String): Color {
    return try {
        Color(android.graphics.Color.parseColor(hex))
    } catch (_: Exception) {
        Color(0xFF3B82F6)
    }
}
