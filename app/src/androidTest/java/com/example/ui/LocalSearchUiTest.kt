package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.model.DateFilter
import com.example.model.DateBasis
import com.example.model.DateGrouping
import com.example.model.LibraryLayout
import com.example.ui.components.LibraryResultState
import com.example.ui.components.SearchResultsContent
import com.example.ui.components.SearchSelectionActions
import com.example.ui.components.additionalAiMatches
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.theme.PersonalLibraryTheme
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Synthetic rows only. No provider calls, private-field reflection, or test-only production hooks. */
@RunWith(AndroidJUnit4::class)
class LocalSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MainViewModel
    private val itemIds = mutableListOf<String>()
    private val collectionIds = mutableListOf<String>()
    private val token = "searchui" + UUID.randomUUID().toString().replace("-", "")

    @After fun cleanup() {
        if (!::vm.isInitialized) return
        compose.runOnUiThread { vm.clearSelection(); vm.clearFilters(); vm.setSearchQuery(""); vm.setLibraryLayout(LibraryLayout.LIST) }
        runBlocking {
            itemIds.forEach { vm.repository.deletePermanently(it) }
            collectionIds.forEach {
                val deletion = vm.repository.deleteCollection(it, undoMillis = 0)
                check(vm.repository.finalizeCollectionDeletion(deletion.operationId))
            }
        }
    }

    @Test fun libraryShowsInheritedFiltersAndClearPreservesQuery() = checkFilters(searchScreen = false)

    @Test fun searchShowsInheritedFiltersAndClearPreservesQuery() = checkFilters(searchScreen = true)

    private fun checkFilters(searchScreen: Boolean) {
        prepare()
        val row = seed()
        val collection = CollectionEntity("search-ui-col-${UUID.randomUUID()}", "Synthetic chosen collection")
        collectionIds += collection.id
        runBlocking { vm.repository.dao.insertCollection(collection) }
        compose.runOnUiThread {
            vm.selectCollection(collection.id)
            vm.selectKind(ItemKind.TEXT)
            vm.setCustomDateRange(1L, 2L)
            compose.activity.setContent {
                PersonalLibraryTheme {
                    if (searchScreen) SearchScreen(vm, {}, {}) else LibraryScreen(vm, {}, {}, {}, {})
                }
            }
        }
        waitForResults(emptySet())
        compose.onNodeWithText("No items match these filters").assertIsDisplayed()
        compose.onNodeWithText("Your library is empty").assertDoesNotExist()
        compose.onNodeWithTag("active_filter_chips").performScrollToNode(hasTestTag("active_collection_chip"))
        compose.onNodeWithTag("active_collection_chip").assertTextContains("Collection: Synthetic chosen collection")
        compose.onNodeWithTag("active_filter_chips").performScrollToNode(hasTestTag("active_type_chip"))
        compose.onNodeWithTag("active_type_chip").assertTextContains("Type: ${ItemKind.TEXT.displayName}")
        compose.onNodeWithTag("active_filter_chips").performScrollToNode(hasTestTag("active_date_chip"))
        compose.onNodeWithTag("active_date_chip").assertIsDisplayed()
        compose.onNodeWithTag("clear_filters_button").performClick()
        waitForResults(setOf(row.id))
        compose.runOnIdle {
            assertEquals(token, vm.searchQuery.value)
            assertNull(vm.selectedCollectionId.value)
            assertNull(vm.selectedKind.value)
            assertEquals(DateFilter.ALL_TIME, vm.dateFilter.value)
            assertNull(vm.customDateStart.value)
            assertNull(vm.customDateEnd.value)
        }
        compose.onNodeWithTag("active_search_filters").assertDoesNotExist()
        compose.onNodeWithTag("item_card_${row.id}").assertIsDisplayed()
    }

    @Test fun prefixResultsKeepSnippetsInListAndGrid() {
        prepare()
        val bike = seed("bike repair")
        val biology = seed("biology lesson")
        val birth = seed("birth record")
        val birthday = seed("birthday picnic")
        compose.runOnUiThread {
            compose.activity.setContent { PersonalLibraryTheme { LibraryScreen(vm, {}, {}, {}, {}) } }
        }
        val all = setOf(bike.id, biology.id, birth.id, birthday.id)
        val birthMatches = setOf(birth.id, birthday.id)
        // Include deletion as well as narrowing: a stale previous query cannot satisfy these sets.
        val cases = listOf(
            "b" to all, "bi" to all, "bir" to birthMatches, "birth" to birthMatches,
            "birthday" to setOf(birthday.id), "birth" to birthMatches, "bi" to all,
            "birthday" to setOf(birthday.id)
        )
        for ((prefix, expected) in cases) {
            compose.onNodeWithTag("library_list").performScrollToNode(hasTestTag("library_search_input"))
            compose.onNodeWithTag("library_search_input").performTextReplacement("$token $prefix")
            Espresso.closeSoftKeyboard()
            waitForResults(expected)
            compose.onNodeWithText("Library • ${expected.size} items").assertIsDisplayed()
        }
        compose.onNodeWithTag("library_list").performScrollToNode(hasTestTag("item_card_${birthday.id}"))
        compose.onNodeWithTag("item_card_${birthday.id}", useUnmergedTree = true).assert(hasAnyDescendant(hasText("birthday", substring = true)))
        compose.onNodeWithTag("toggle_layout_button").performClick()
        compose.onNodeWithTag("library_grid").assertIsDisplayed()
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("item_grid_card_${birthday.id}"))
        compose.onNodeWithTag("grid_snippet_${birthday.id}", useUnmergedTree = true).assertIsDisplayed().assertTextContains("birthday", substring = true)
    }

    @Test fun emptyStatesDistinguishLibraryQueryFiltersLoadingAndFailure() {
        var ready by mutableStateOf(true)
        var error by mutableStateOf<String?>(null)
        var libraryEmpty by mutableStateOf(true)
        var filters by mutableStateOf(false)
        var cleared = false
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme { LibraryResultState(ready, error, libraryEmpty, "synthetic", filters, { cleared = true }) }
            }
        }
        compose.onNodeWithText("Your library is empty").assertIsDisplayed()
        compose.runOnIdle { libraryEmpty = false }
        compose.onNodeWithText("No results found").assertIsDisplayed()
        compose.runOnIdle { filters = true }
        compose.onNodeWithText("No items match these filters").assertIsDisplayed()
        compose.onNodeWithTag("empty_clear_filters_button").performClick()
        compose.runOnIdle { assertTrue(cleared); ready = false }
        compose.onNodeWithTag("local_search_loading").assertIsDisplayed()
        compose.onNodeWithText("No items match these filters").assertDoesNotExist()
        compose.runOnIdle { error = "Could not search your library. Try again." }
        compose.onNodeWithTag("local_search_error").assertIsDisplayed()
        compose.onNodeWithTag("local_search_loading").assertDoesNotExist()
        compose.onNodeWithText("No results found").assertDoesNotExist()
    }

    @Test fun aiMatchesAddToLocalResultsDeduplicateAndRetainCardSelection() {
        val local = ItemWithCollections(ItemEntity("synthetic-local", "Synthetic local", "birthday picnic", "TEXT"), emptyList())
        val related = ItemWithCollections(ItemEntity("synthetic-related", "Synthetic related", "celebration", "TEXT"), emptyList())
        val ai = listOf(
            SemanticSearchResult(local, "Duplicate local", 3f),
            SemanticSearchResult(related, "Synthetic semantic evidence", 2f, matchingSnippet = "Synthetic related snippet"),
            SemanticSearchResult(related, "Duplicate semantic", 1f)
        )
        val selected = mutableSetOf<String>()
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme {
                    SearchResultsContent(
                        "birthday", listOf(local), ai, true, null, null, false, false, false,
                        false, emptySet(), { File(compose.activity.cacheDir, it) }, { _, _ -> "birthday picnic" },
                        {}, {}, {}, { selected += it }, {}
                    )
                }
            }
        }
        compose.onNodeWithTag("local_results_heading").assertTextEquals("Local results (1)")
        compose.onNodeWithTag("item_card_synthetic-local").assertIsDisplayed()
        compose.onNodeWithTag("search_results_list").performScrollToNode(hasTestTag("ai_results_heading"))
        compose.onNodeWithTag("ai_results_heading").assertTextEquals("AI related matches (1)")
        compose.onNodeWithTag("search_results_list").performScrollToNode(hasTestTag("item_card_synthetic-related"))
        compose.onNodeWithTag("item_card_synthetic-related").performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(setOf(related.item.id), selected)
            val union = listOf(local).map { it.item.id } + additionalAiMatches(listOf(local), ai).map { it.item.item.id }
            assertEquals(listOf(local.item.id, related.item.id), union)
        }
        compose.onNodeWithTag("ai_result_synthetic-local").assertDoesNotExist()
    }

    @Test fun aiUnavailableIsSeparateAndDoesNotRemoveLocalMatches() {
        val local = ItemWithCollections(ItemEntity("synthetic-offline", "Synthetic offline result", "birthday", "TEXT"), emptyList())
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme {
                    SearchResultsContent(
                        "birthday", listOf(local), emptyList(), true, null, "AI search is unavailable. Local search still works.",
                        false, false, false, false, emptySet(), { File(compose.activity.cacheDir, it) }, { _, _ -> "birthday" },
                        {}, {}, {}, {}, {}
                    )
                }
            }
        }
        compose.onNodeWithTag("item_card_synthetic-offline").assertIsDisplayed()
        compose.onNodeWithTag("search_results_list").performScrollToNode(hasTestTag("ai_search_error"))
        compose.onNodeWithTag("ai_search_error").assertIsDisplayed()
        compose.onNodeWithText("No results found").assertDoesNotExist()
    }

    @Test fun aiRelatedMatchesWithNoLocalMatchesDoNotClaimAllResultsAreEmpty() {
        val related = ItemWithCollections(ItemEntity("synthetic-ai-only", "Synthetic related", "celebration", "TEXT"), emptyList())
        var filtersActive by mutableStateOf(false)
        var cleared = false
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme {
                    SearchResultsContent(
                        "birthday", emptyList(), listOf(SemanticSearchResult(related, "Related synthetic concept", 1f)),
                        true, null, null, false, false, filtersActive, false, emptySet(), { null }, { _, _ -> null },
                        {}, {}, {}, {}, { cleared = true }
                    )
                }
            }
        }
        compose.onNodeWithText("No local results").assertIsDisplayed()
        compose.onNodeWithText("No results found").assertDoesNotExist()
        compose.onNodeWithTag("search_results_list").performScrollToNode(hasTestTag("item_card_synthetic-ai-only"))
        compose.onNodeWithTag("item_card_synthetic-ai-only").assertIsDisplayed()
        compose.runOnIdle { filtersActive = true }
        compose.onNodeWithTag("search_results_list").performScrollToIndex(0)
        compose.onNodeWithText("No local keyword results match these filters.").assertIsDisplayed()
        compose.onNodeWithTag("empty_clear_filters_button").performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test fun selectAllVisibleActionSelectsRenderedLocalAndUniqueAiUnionOnly() {
        prepare()
        val local = seed()
        val related = seed("celebration", indexedToken = false)
        val hidden = seed("different topic", indexedToken = false)
        val relatedRow = ItemWithCollections(related, emptyList())
        var fakeAi by mutableStateOf(listOf(
            SemanticSearchResult(ItemWithCollections(local, emptyList()), "Local duplicate", 3f),
            SemanticSearchResult(relatedRow, "Synthetic related match", 2f),
            SemanticSearchResult(relatedRow, "AI duplicate", 1f)
        ))
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme {
                    val results by vm.localSearchResults.collectAsState()
                    val ready by vm.resultsReady.collectAsState()
                    val query by vm.searchQuery.collectAsState()
                    val selected by vm.selectedItemIds.collectAsState()
                    val mode by vm.isBatchMode.collectAsState()
                    Column {
                        if (mode) Row {
                            SearchSelectionActions(vm, query, results, fakeAi, ready, null, false, selected)
                        }
                        SearchResultsContent(
                            query, results, fakeAi, ready, null, null, false, false, false, mode, selected,
                            { null }, vm::buildMatchSnippet, {}, {}, {}, vm::toggleSelection, {}
                        )
                    }
                }
            }
        }
        waitForResults(setOf(local.id))
        val localCard = compose.onNodeWithTag("item_card_${local.id}")
        compose.waitUntil(10_000) { localCard.isDisplayed() }
        localCard.performTouchInput { longClick() }
        selectAllVisible()
        compose.runOnIdle {
            assertEquals(setOf(local.id, related.id), vm.selectedItemIds.value)
            assertFalse(hidden.id in vm.selectedItemIds.value)
            vm.clearSelection()
            fakeAi = emptyList()
        }
        compose.waitUntil(10_000) { localCard.isDisplayed() }
        localCard.performTouchInput { longClick() }
        selectAllVisible()
        compose.runOnIdle { assertEquals(setOf(local.id), vm.selectedItemIds.value) }
    }

    private fun prepare() {
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            vm.clearSelection()
            vm.clearFilters()
            vm.setLibraryLayout(LibraryLayout.LIST)
            vm.setDateBasis(DateBasis.SAVED_DATE)
            vm.setDateGrouping(DateGrouping.NONE)
            vm.setSearchQuery(token)
        }
    }

    private fun seed(body: String = "birthday picnic", indexedToken: Boolean = true): ItemEntity {
        val row = ItemEntity("search-ui-${UUID.randomUUID()}", "Synthetic indexed note", if (indexedToken) "$token $body" else body, "TEXT")
        itemIds += row.id
        runBlocking { vm.repository.dao.insertItem(row) }
        return row
    }

    private fun waitForResults(expected: Set<String>) {
        compose.waitUntil(10_000) { vm.resultsReady.value && vm.localSearchResults.value.map { it.item.id }.toSet() == expected }
        compose.waitForIdle()
    }

    private fun selectAllVisible() {
        compose.onNodeWithTag("selection_more_button").performClick()
        compose.onNodeWithTag("select_all_visible_button").performClick()
        compose.waitUntil(10_000) { !vm.selectingVisible.value }
    }
}
