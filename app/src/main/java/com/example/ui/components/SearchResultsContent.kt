package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.ui.SemanticSearchResult
import com.example.ui.AiAnswerState
import com.example.ui.MainViewModel
import java.io.File

/** The same deduplicated additions drive rendering and Select All Visible. */
fun additionalAiMatches(local: List<ItemWithCollections>, ai: List<SemanticSearchResult>): List<SemanticSearchResult> {
    if (ai.isEmpty()) return emptyList()
    // Only allocate for the small AI result set, even with a very large local library.
    val additions = LinkedHashMap<String, SemanticSearchResult>()
    ai.forEach { additions.putIfAbsent(it.item.item.id, it) }
    local.forEach { additions.remove(it.item.id) }
    return additions.values.toList()
}

private data class SearchSelectionContext(
    val local: List<ItemWithCollections>,
    val ai: List<SemanticSearchResult>,
    val visible: Boolean
) {
    fun visibleIds(): List<String> = if (visible)
        local.map { it.item.id } + additionalAiMatches(local, ai).map { it.item.item.id }
    else emptyList()
}

/** Search's selection controls use the same logical result sections as the content below them. */
@Composable
fun SearchSelectionActions(
    viewModel: MainViewModel,
    query: String,
    localResults: List<ItemWithCollections>,
    aiResults: List<SemanticSearchResult>,
    resultsReady: Boolean,
    localError: String?,
    filtersActive: Boolean,
    selectedIds: Set<String>
) {
    val context = SearchSelectionContext(localResults, aiResults,
        resultsReady && localError == null && (query.isNotBlank() || filtersActive))
    val currentContext by rememberUpdatedState(context)
    val visibleCount = remember(context) {
        if (context.visible) context.local.size + additionalAiMatches(context.local, context.ai).size else 0
    }
    // SelectionActions invokes this provider on Default, keeping ID allocation off the UI thread.
    SelectionActions(viewModel, visibleCount, { currentContext.visibleIds() }, selectedIds)
}

@Composable
fun SearchResultsContent(
    query: String,
    localResults: List<ItemWithCollections>,
    aiResults: List<SemanticSearchResult>,
    resultsReady: Boolean,
    localError: String?,
    aiError: String?,
    isAiSearching: Boolean,
    libraryEmpty: Boolean,
    filtersActive: Boolean,
    isSelectMode: Boolean,
    selectedIds: Set<String>,
    fileResolver: (String) -> File?,
    snippet: (ItemEntity, String) -> String?,
    onOpen: (ItemWithCollections) -> Unit,
    onFavorite: (String) -> Unit,
    onTrash: (String) -> Unit,
    onSelect: (String) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
    answer: AiAnswerState? = null
) {
    val showResults = query.isNotBlank() || filtersActive
    val additions = remember(localResults, aiResults, resultsReady, localError, showResults) {
        if (resultsReady && localError == null && showResults) additionalAiMatches(localResults, aiResults) else emptyList()
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("search_results_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (resultsReady && localError == null && !libraryEmpty && showResults && localResults.isEmpty() &&
            (additions.isNotEmpty() || answer?.response?.status in setOf("ANSWERED", "CONFLICTING"))) {
            item("local_empty_with_ai") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("No local results", style = MaterialTheme.typography.titleMedium)
                    if (filtersActive) {
                        Text("No local keyword results match these filters.")
                        TextButton(onClick = onClearFilters, modifier = Modifier.testTag("empty_clear_filters_button")) { Text("Clear Filters") }
                    }
                }
            }
        } else if (!resultsReady || localError != null || libraryEmpty || (showResults && localResults.isEmpty())) {
            item("local_state") {
                LibraryResultState(resultsReady, localError, libraryEmpty, query, filtersActive, onClearFilters)
            }
        } else if (showResults) {
            item("local_heading") {
                Text("Local results (${localResults.size})", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("local_results_heading"))
            }
            items(localResults, key = { "local_${it.item.id}" }) { row ->
                ItemCard(
                    itemWithCols = row,
                    fileResolver = fileResolver,
                    onClick = { onOpen(row) },
                    onFavoriteToggle = { onFavorite(row.item.id) },
                    onDelete = { onTrash(row.item.id) },
                    isSelectMode = isSelectMode,
                    isSelected = row.item.id in selectedIds,
                    onSelectToggle = { onSelect(row.item.id) },
                    matchingSnippet = snippet(row.item, query)
                )
            }
        } else {
            item("search_hint") {
                Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Find anything in your personal archive", style = MaterialTheme.typography.titleMedium)
                    Text("Search keywords, filenames, and extracted text. Local search works offline.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (answer != null) item("ai_answer") { AiAnswerCard(answer, onOpen) }
        if (isAiSearching) item("ai_loading") {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("Finding relevant evidence and preparing an answer…")
            }
        }
        if (aiError != null) item("ai_error") {
            Text(aiError, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("ai_search_error"))
        }
        if (additions.isNotEmpty()) {
            item("ai_heading") {
                Text("AI related matches (${additions.size})", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold, modifier = Modifier.testTag("ai_results_heading"))
            }
            items(additions, key = { "ai_${it.item.item.id}" }) { result ->
                Column(Modifier.testTag("ai_result_${result.item.item.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${result.source}${result.pageNumber?.let { " • Page $it" } ?: ""}\n${result.explanation}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ItemCard(
                        itemWithCols = result.item,
                        fileResolver = fileResolver,
                        onClick = { onOpen(result.item) },
                        onFavoriteToggle = { onFavorite(result.item.item.id) },
                        onDelete = { onTrash(result.item.item.id) },
                        isSelectMode = isSelectMode,
                        isSelected = result.item.item.id in selectedIds,
                        onSelectToggle = { onSelect(result.item.item.id) },
                        matchingSnippet = result.matchingSnippet
                    )
                }
            }
        }
    }
}
