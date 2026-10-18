package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.CollectionEntity
import com.example.ui.MainViewModel
import kotlinx.coroutines.launch

/** Shared actions keep selection behavior identical in Library and Search. */
@Composable
fun SelectionActions(viewModel: MainViewModel, visibleCount: Int, visibleIds: () -> Collection<String>, selectedIds: Set<String>) {
    val collections by viewModel.collections.collectAsState()
    val resultsReady by viewModel.resultsReady.collectAsState()
    val selectingVisible by viewModel.selectingVisible.collectAsState()
    var menuOpen by remember { mutableStateOf(false) }
    var collectionAction by remember { mutableStateOf<Boolean?>(null) }
    var actionItemIds by remember { mutableStateOf(emptySet<String>()) }
    IconButton(
        onClick = viewModel::batchMoveToTrash,
        enabled = selectedIds.isNotEmpty() && !selectingVisible,
        modifier = Modifier.testTag("batch_delete_button")
    ) { Icon(Icons.Default.Delete, "Trash selected", tint = MaterialTheme.colorScheme.error) }
    Box {
        IconButton(onClick = { menuOpen = true }, modifier = Modifier.testTag("selection_more_button")) {
            Icon(Icons.Default.MoreVert, "Selection actions")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(if (selectingVisible) "Selecting…" else "Select All Visible ($visibleCount)") },
                onClick = { viewModel.selectAllVisible(visibleIds); menuOpen = false },
                enabled = visibleCount > 0 && resultsReady && !selectingVisible,
                modifier = Modifier.testTag("select_all_visible_button")
            )
            for (add in listOf(true, false)) {
                DropdownMenuItem(
                    text = { Text(if (add) "Add to collections" else "Remove from collections") },
                    enabled = selectedIds.isNotEmpty(),
                    onClick = {
                        actionItemIds = selectedIds.toSet()
                        collectionAction = add
                        menuOpen = false
                    },
                    modifier = Modifier.testTag(if (add) "bulk_add_collections" else "bulk_remove_collections")
                )
            }
        }
    }
    IconButton(onClick = viewModel::clearSelection, modifier = Modifier.testTag("batch_cancel_button")) {
        Icon(Icons.Default.Close, "Cancel selection")
    }
    collectionAction?.let { add ->
        BulkCollectionsDialog(
            collections = collections,
            add = add,
            itemCount = actionItemIds.size,
            onDismiss = { collectionAction = null },
            onApply = { ids -> viewModel.changeSelectedCollections(actionItemIds, ids, add) }
        )
    }
}

@Composable
private fun BulkCollectionsDialog(
    collections: List<CollectionEntity>,
    add: Boolean,
    itemCount: Int,
    onDismiss: () -> Unit,
    onApply: suspend (Set<String>) -> String?
) {
    var chosen by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (add) "Add $itemCount items to collections" else "Remove $itemCount items from collections") },
        text = {
            Column {
                Text(if (add) "Other memberships will stay." else "Saved items and other memberships will stay.")
                if (collections.isEmpty()) Text("Create a collection first.")
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(collections, key = { it.id }) { collection ->
                        fun toggle() {
                            chosen = if (collection.id in chosen) chosen - collection.id else chosen + collection.id
                        }
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !saving) { toggle() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(collection.id in chosen, onCheckedChange = { toggle() }, enabled = !saving)
                            Text(collection.name, Modifier.weight(1f))
                        }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = chosen.isNotEmpty() && !saving,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            error = onApply(chosen)
                            if (error == null) onDismiss()
                        } finally { saving = false }
                    }
                },
                modifier = Modifier.testTag("bulk_collections_apply")
            ) { Text(if (saving) "Saving…" else "Apply") }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = onDismiss) { Text("Cancel") } }
    )
}
