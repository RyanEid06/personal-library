package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.model.SharePayload
import com.example.ui.components.EditItemDialog
import com.example.ui.components.ItemCard
import com.example.ui.components.ItemGridCard
import com.example.ui.components.SharePreviewDialog
import com.example.ui.screens.CollectionCard
import com.example.ui.screens.CreateCollectionDialog
import com.example.ui.screens.TrashScreen
import com.example.ui.theme.PersonalLibraryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic-only coverage for everyday organization and reversible recovery. */
@RunWith(AndroidJUnit4::class)
class LibraryManagementUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var viewModel: MainViewModel? = null
    private val seededItems = mutableListOf<String>()

    @After fun cleanup() {
        viewModel?.let { vm -> runBlocking { seededItems.forEach { vm.repository.deletePermanently(it) } } }
    }

    @Test fun collectionEditorRejectsDuplicateReservedAndBlankNamesAndTrimsSave() {
        val existing = CollectionEntity("editing", "Synthetic Original", "work", "#10B981")
        var saved: Triple<String, String, String>? = null
        show {
            CreateCollectionDialog(onDismiss = {}, onConfirm = { name, icon, color -> saved = Triple(name, icon, color) },
                initialCollection = existing, existingCollections = listOf(existing, CollectionEntity("other", "Other")))
        }
        compose.onNodeWithTag("new_col_name_input").assertTextContains("Synthetic Original")
        compose.onNodeWithTag("create_col_confirm_button").assertIsEnabled()
        for (invalid in listOf("  ", "other", " FaVoRiTeS ")) {
            compose.onNodeWithTag("new_col_name_input").performTextReplacement(invalid)
            compose.onNodeWithTag("create_col_confirm_button").assertIsNotEnabled()
        }
        compose.onNodeWithTag("new_col_name_input").performTextReplacement("  Renamed  ")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("create_col_confirm_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(Triple("Renamed", "work", "#10B981"), saved) }
    }

    @Test fun visibleCollectionOverflowExposesManagementAndInvokesEdit() {
        var edited = false
        show {
            CollectionCard("Synthetic", 3, Icons.Filled.Folder, Color.Blue, onClick = {},
                onEdit = { edited = true }, onSelectItems = {}, onAiPrivacy = {}, onDelete = {})
        }
        compose.onNodeWithContentDescription("Manage Synthetic").performClick()
        listOf("Edit Collection", "Open", "Select Items", "AI Privacy", "Delete Collection").forEach {
            compose.onNodeWithText(it).assertIsDisplayed()
        }
        compose.onNodeWithText("Edit Collection").performClick()
        compose.runOnIdle { assertTrue(edited) }
    }

    @Test fun computedCollectionMenuCannotRenameOrDelete() {
        show { CollectionCard("Favorites", 0, Icons.Filled.Folder, Color.Blue, onClick = {}, onSelectItems = {}) }
        compose.onNodeWithContentDescription("Manage Favorites").performClick()
        compose.onNodeWithText("Select Items").assertIsDisplayed()
        compose.onNodeWithText("Edit Collection").assertDoesNotExist()
        compose.onNodeWithText("Delete Collection").assertDoesNotExist()
    }

    @Test fun sharePreviewUsesResolvedDefaultEvenWhenDisplayNameWasRenamed() {
        var selected: List<String>? = null
        show {
            SharePreviewDialog(SharePayload(text = "Synthetic share"), listOf(CollectionEntity("stable-default", "Renamed default")),
                onDismiss = {}, onConfirmSave = { selected = it }, resolveDefaultCollectionId = { "stable-default" })
        }
        compose.onNodeWithTag("save_share_button").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("stable-default"), selected) }
    }

    @Test fun listLongPressSelectsAndNormalTapTogglesWithoutOpening() = verifySelection(grid = false)
    @Test fun gridLongPressSelectsAndNormalTapTogglesWithoutOpening() = verifySelection(grid = true)

    private fun verifySelection(grid: Boolean) {
        val item = ItemWithCollections(ItemEntity("synthetic-selection", "Synthetic selection", "Body", "TEXT"), emptyList())
        var opens = 0
        show {
            var mode by remember { mutableStateOf(false) }
            var selected by remember { mutableStateOf(false) }
            val toggle = { mode = true; selected = !selected }
            if (grid) ItemGridCard(item, { null }, { opens++ }, {}, {}, isSelectMode = mode,
                isSelected = selected, onSelectToggle = toggle, matchingSnippet = "Synthetic match")
            else ItemCard(item, { null }, { opens++ }, {}, {}, isSelectMode = mode,
                isSelected = selected, onSelectToggle = toggle)
        }
        val tag = if (grid) "item_grid_card_synthetic-selection" else "item_card_synthetic-selection"
        compose.onNodeWithTag(tag).performTouchInput { longClick() }
        compose.onNodeWithTag(tag).assertIsSelected()
        compose.onNodeWithTag(tag).performClick().assertIsNotSelected()
        compose.runOnIdle { assertEquals(0, opens) }
        if (grid) compose.onNodeWithTag("grid_snippet_synthetic-selection", useUnmergedTree = true).assertIsDisplayed().assertTextContains("Synthetic match")
    }

    @Test fun linkEditorKeepsFailureVisibleAndPreservesUserInput() {
        var saved: Pair<String, String>? = null
        var dismissed = false
        show {
            EditItemDialog(ItemEntity("synthetic-link", "Old", "https://example.test\nOld note", "LINK"),
                onDismiss = { dismissed = true }, onSave = { title, content -> saved = title to content; "Enter a valid URL" })
        }
        compose.onNodeWithTag("edit_item_title").performTextReplacement("Corrected")
        compose.onNodeWithTag("edit_item_url").performTextReplacement("malformed input")
        compose.onNodeWithTag("edit_item_body").performTextReplacement("Corrected note")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("edit_item_save").performClick()
        compose.onNodeWithTag("edit_item_error").assertTextContains("Enter a valid URL")
        compose.onNodeWithTag("edit_item_url").assertTextContains("malformed input")
        compose.runOnIdle {
            assertFalse(dismissed)
            assertEquals("Corrected" to "malformed input\nCorrected note", saved)
        }
    }

    @Test fun importedTextEditPreservesExactProvenanceSuffix() {
        val provenance = "\n\nWhatsApp provenance\nSender: Synthetic\nSource date text: 2026-01-01\nImport timezone: UTC"
        var content: String? = null
        show {
            EditItemDialog(ItemEntity("wa_synthetic", "Old", "Old body$provenance", "TEXT", sourceDate = 1L),
                onDismiss = {}, onSave = { _, body -> content = body; null })
        }
        compose.onNodeWithTag("edit_item_body").performTextReplacement("Corrected body")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("edit_item_save").performClick()
        compose.runOnIdle { assertEquals("Corrected body$provenance", content) }
    }

    @Test fun mediaEditorSavesTitleWithoutChangingContentOrExposingBody() {
        val original = ItemEntity("synthetic-media", "Old file title", "Original provenance", "VIDEO", contentHash = "synthetic")
        var saved: Pair<String, String>? = null
        show { EditItemDialog(original, {}, { title, body -> saved = title to body; null }) }
        compose.onNodeWithTag("edit_item_body").assertDoesNotExist()
        compose.onNodeWithTag("edit_item_title").performTextReplacement("New title")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("edit_item_save").performClick()
        compose.runOnIdle { assertEquals("New title" to original.content, saved) }
    }

    @Test fun restoreAllRequiresConfirmationThenRestoresSyntheticTrash() {
        lateinit var vm: MainViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        viewModel = vm
        // Never restore pre-existing records when someone runs this test on a non-empty installation.
        assumeTrue("Restore All test requires a synthetic-only empty Trash", runBlocking { vm.repository.dao.getAllItemsIncludingTrash().none { it.isTrash } })
        val id = "management-ui-${UUID.randomUUID()}"
        seededItems += id
        runBlocking { vm.repository.dao.insertItem(ItemEntity(id, "Synthetic restore all", "Fake only", "TEXT", isTrash = true, trashTimestamp = System.currentTimeMillis())) }
        show { TrashScreen(vm) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("restore_all_button").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("restore_all_button").performClick()
        compose.onNodeWithText("Restore All?").assertIsDisplayed()
        assertTrue(runBlocking { vm.repository.dao.getItemById(id)!!.isTrash })
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(runBlocking { vm.repository.dao.getItemById(id)!!.isTrash })
        compose.onNodeWithTag("restore_all_button").performClick()
        compose.onNodeWithTag("confirm_restore_all_button").performClick()
        compose.waitUntil(10_000) { runBlocking { vm.repository.dao.getItemById(id)?.isTrash == false } }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { PersonalLibraryTheme { content() } } }
    }
}
