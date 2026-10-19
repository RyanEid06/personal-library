package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.data.CollectionEntity
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.personallibrary.core.model.ItemKind
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

fun hasSearchFilters(collectionId: String?, kind: ItemKind?, dateFilter: DateFilter): Boolean =
    collectionId != null || kind != null || dateFilter != DateFilter.ALL_TIME

/** Shared by Library and Search so inherited constraints are always visible. */
@Composable
fun ActiveSearchFilters(
    collectionId: String?,
    collections: List<CollectionEntity>,
    kind: ItemKind?,
    dateFilter: DateFilter,
    dateBasis: DateBasis,
    customStart: Long?,
    customEnd: Long?,
    onClearCollection: () -> Unit,
    onClearKind: () -> Unit,
    onClearDate: () -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!hasSearchFilters(collectionId, kind, dateFilter)) return
    Column(modifier.fillMaxWidth().testTag("active_search_filters")) {
        // Keep Clear Filters outside the scrolling row, including at large font sizes.
        TextButton(onClick = onClearFilters, modifier = Modifier.align(Alignment.End).testTag("clear_filters_button")) {
            Text("Clear Filters")
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().testTag("active_filter_chips")
        ) {
            if (collectionId != null) item {
                val name = when (collectionId) {
                    "computed_favorites" -> "Favorites"
                    "computed_unfiled" -> "Unfiled"
                    "computed_due" -> "Due Reminders"
                    else -> collections.find { it.id == collectionId }?.name ?: "Collection"
                }
                ActiveFilterChip("Collection: $name", "active_collection_chip", "Clear collection", onClearCollection)
            }
            if (kind != null) item {
                ActiveFilterChip("Type: ${kind.displayName}", "active_type_chip", "Clear type", onClearKind)
            }
            if (dateFilter != DateFilter.ALL_TIME) item {
                val range = if (dateFilter == DateFilter.CUSTOM && customStart != null && customEnd != null) {
                    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                    val zone = ZoneId.systemDefault()
                    "${formatter.format(Instant.ofEpochMilli(customStart).atZone(zone))} – ${formatter.format(Instant.ofEpochMilli(customEnd).atZone(zone))}"
                } else dateFilter.label
                ActiveFilterChip("${dateBasis.label}: $range", "active_date_chip", "Clear date", onClearDate)
            }
        }
    }
}

@Composable
private fun ActiveFilterChip(label: String, tag: String, clearDescription: String, onClear: () -> Unit) {
    InputChip(
        selected = true,
        onClick = onClear,
        label = { Text(label) },
        trailingIcon = { Icon(Icons.Filled.Close, contentDescription = clearDescription, modifier = Modifier.size(16.dp)) },
        modifier = Modifier.testTag(tag)
    )
}

/** Loading and errors take precedence over empty results; no false-empty flash on refresh. */
@Composable
fun LibraryResultState(
    resultsReady: Boolean,
    error: String?,
    libraryEmpty: Boolean,
    query: String,
    filtersActive: Boolean,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp).testTag("library_result_state"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when {
            error != null -> Text(error, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.testTag("local_search_error"))
            !resultsReady -> {
                CircularProgressIndicator(modifier = Modifier.testTag("local_search_loading"))
                Text("Searching your library…")
            }
            else -> {
                Text(
                    when {
                        libraryEmpty -> "Your library is empty"
                        filtersActive -> "No items match these filters"
                        else -> "No results found"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    if (libraryEmpty) "Add a note, save a link, or import files to get started."
                    else if (query.isNotBlank()) "Try different keywords or clear your filters."
                    else "Clear your filters to see more items.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                if (filtersActive) TextButton(onClick = onClearFilters, modifier = Modifier.testTag("empty_clear_filters_button")) { Text("Clear Filters") }
            }
        }
    }
}
