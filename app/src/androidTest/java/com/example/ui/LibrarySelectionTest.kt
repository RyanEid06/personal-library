package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemCollectionCrossRef
import com.example.model.DateFilter
import com.example.model.LibraryLayout
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
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.database.sqlite.SQLiteDatabase
import android.os.Looper

/** Current-screen selection and bulk actions, using only uniquely identified synthetic rows. */
@RunWith(AndroidJUnit4::class)
class LibrarySelectionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var vm: MainViewModel
    private val itemIds = mutableListOf<String>()
    private val collectionIds = mutableListOf<String>()
    private val token = "selection" + UUID.randomUUID().toString().replace("-", "")

    @After fun cleanup() {
        if (!::vm.isInitialized) return
        compose.runOnUiThread { vm.clearSelection(); vm.setSearchQuery(""); vm.selectKind(null); vm.selectCollection(null); vm.setDateFilter(DateFilter.ALL_TIME) }
        runBlocking {
            itemIds.forEach { vm.repository.deletePermanently(it) }
            collectionIds.forEach { vm.repository.deleteCollection(it) }
        }
    }

    @Test fun listLongPressStartsSelectionTapTogglesAndBackExitsWithoutDeleting() {
        prepare()
        val first = seed("first")
        val second = seed("second")
        showLibrary(LibraryLayout.LIST)
        waitForVisible(setOf(first.id, second.id))
        compose.onNodeWithTag("item_card_${first.id}").performTouchInput { longClick() }
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        compose.onNodeWithTag("item_card_${second.id}").performClick()
        compose.onNodeWithText("2 selected").assertIsDisplayed()
        compose.onNodeWithTag("item_card_${first.id}").performClick()
        compose.onNodeWithText("1 selected").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("batch_cancel_button").assertDoesNotExist()
        assertActive(first.id, second.id)
    }

    @Test fun gridSelectAllVisibleHonorsQueryCollectionTypeAndDateThenUndoRestoresOnlyThoseRows() {
        prepare()
        val target = collection("Target")
        val first = seed("first", collectionId = target.id)
        val second = seed("second", collectionId = target.id)
        val otherCollection = seed("other collection")
        val otherType = seed("photo", kind = "PHOTO", collectionId = target.id)
        val old = seed("old", createdAt = System.currentTimeMillis() - 7 * 86_400_000L, collectionId = target.id)
        val otherQuery = seed("unmatched", body = "Different synthetic text", collectionId = target.id)
        compose.runOnUiThread {
            vm.selectCollection(target.id)
            vm.selectKind(ItemKind.TEXT)
            vm.setCustomDateRange(System.currentTimeMillis() - 86_400_000L, System.currentTimeMillis() + 86_400_000L)
        }
        showLibrary(LibraryLayout.GRID)
        waitForVisible(setOf(first.id, second.id))
        compose.onNodeWithTag("item_grid_card_${first.id}").performTouchInput { longClick() }
        selectAllVisible()
        compose.runOnIdle { assertEquals(setOf(first.id, second.id), vm.selectedItemIds.value) }
        compose.onNodeWithTag("batch_delete_button").performClick()
        compose.waitUntil(10_000) { runBlocking { vm.repository.dao.getItemById(first.id)?.isTrash == true && vm.repository.dao.getItemById(second.id)?.isTrash == true } }
        assertActive(otherCollection.id, otherType.id, old.id, otherQuery.id)
        compose.onNodeWithText("UNDO").performClick()
        compose.waitUntil(10_000) { runBlocking { vm.repository.dao.getItemById(first.id)?.isTrash == false && vm.repository.dao.getItemById(second.id)?.isTrash == false } }
        assertActive(first.id, second.id)
    }

    @Test fun searchSelectAllVisibleUsesCurrentLocalResults() {
        prepare()
        val first = seed("first")
        val second = seed("second")
        val hidden = seed("unmatched", body = "Different synthetic text")
        compose.runOnUiThread {
            compose.activity.setContent { PersonalLibraryTheme { SearchScreen(vm, {}, {}) } }
        }
        waitForVisible(setOf(first.id, second.id))
        compose.onNodeWithTag("item_card_${first.id}").performTouchInput { longClick() }
        selectAllVisible()
        compose.runOnIdle {
            assertEquals(setOf(first.id, second.id), vm.selectedItemIds.value)
            assertFalse(hidden.id in vm.selectedItemIds.value)
        }
        compose.onNodeWithTag("batch_cancel_button").performClick()
        compose.runOnIdle { assertTrue(vm.selectedItemIds.value.isEmpty()); assertFalse(vm.isBatchMode.value) }
    }

    @Test fun bulkMoveReplacesPreviousFolderAndRemoveReturnsSavedItemsToInbox() {
        prepare()
        val retained = collection("Retained")
        val added = collection("Added")
        val first = seed("first", collectionId = retained.id)
        val second = seed("second", collectionId = retained.id)
        showLibrary(LibraryLayout.LIST)
        waitForVisible(setOf(first.id, second.id))
        compose.onNodeWithTag("item_card_${first.id}").performTouchInput { longClick() }
        selectAllVisible()
        collectionAction("bulk_add_collections", added.name)
        compose.waitUntil(10_000) { runBlocking {
            vm.repository.dao.getCollectionIdsForItem(first.id).toSet() == setOf(added.id) &&
                vm.repository.dao.getCollectionIdsForItem(second.id).toSet() == setOf(added.id)
        } }
        collectionAction("bulk_remove_collections", added.name)
        compose.waitUntil(10_000) { runBlocking { vm.repository.dao.getCollectionIdsForItem(first.id).isEmpty() && vm.repository.dao.getCollectionIdsForItem(second.id).isEmpty() } }
        assertActive(first.id, second.id)
    }

    @Test fun collectionSelectionCannotSelectThePreviousCollectionDuringRefresh() {
        prepare()
        val target = collection("Target")
        val inside = seed("inside", collectionId = target.id)
        val outside = seed("outside")
        showLibrary(LibraryLayout.LIST)
        waitForVisible(setOf(inside.id, outside.id))
        compose.runOnUiThread {
            vm.selectCollectionItems(target.id)
            assertFalse(vm.resultsReady.value)
            vm.selectAllVisible { listOf(inside.id, outside.id) }
            assertTrue(vm.selectedItemIds.value.isEmpty())
        }
        waitForVisible(setOf(inside.id))
        selectAllVisible()
        compose.waitUntil(10_000) { vm.selectedItemIds.value == setOf(inside.id) }
    }

    @Test fun largeSelectAllBuildsOffMainAndClosingSelectionRejectsItsLateResult() {
        prepare()
        val first = seed("first")
        showLibrary(LibraryLayout.LIST)
        waitForVisible(setOf(first.id))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val offMain = AtomicBoolean(false)
        compose.runOnUiThread {
            vm.beginSelection()
            vm.selectAllVisible {
                offMain.set(Looper.myLooper() != Looper.getMainLooper())
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                (1..25_000).map { "synthetic-visible-$it" }
            }
        }
        try {
            assertTrue(started.await(5, TimeUnit.SECONDS))
            compose.runOnUiThread { vm.clearSelection(); vm.beginSelection() }
        } finally { release.countDown() }
        compose.waitUntil(10_000) { !vm.selectingVisible.value }
        compose.runOnIdle { assertTrue(offMain.get()); assertTrue(vm.selectedItemIds.value.isEmpty()) }
    }

    @Test fun bulkTrashDatabaseFailureKeepsSelectionAndShowsAnError() {
        prepare()
        val first = seed("first")
        showLibrary(LibraryLayout.LIST)
        waitForVisible(setOf(first.id))
        compose.onNodeWithTag("item_card_${first.id}").performTouchInput { longClick() }
        val trigger = "synthetic_selection_failure"
        val rawDb = SQLiteDatabase.openDatabase(compose.activity.getDatabasePath("personal_library_room.db").path, null, SQLiteDatabase.OPEN_READWRITE)
        try {
            rawDb.execSQL("CREATE TRIGGER $trigger BEFORE UPDATE OF isTrash ON items WHEN NEW.id = '${first.id}' AND NEW.isTrash = 1 BEGIN SELECT RAISE(ABORT, 'synthetic failure'); END")
            compose.onNodeWithTag("batch_delete_button").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Could not move the selected items to Trash. Try again.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Could not move the selected items to Trash. Try again.").assertIsDisplayed()
            compose.runOnIdle { assertEquals(setOf(first.id), vm.selectedItemIds.value); assertTrue(vm.isBatchMode.value) }
            assertActive(first.id)
        } finally {
            rawDb.execSQL("DROP TRIGGER IF EXISTS $trigger")
            rawDb.close()
        }
    }

    private fun prepare() {
        compose.runOnUiThread {
            vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            vm.clearSelection()
            vm.selectKind(null)
            vm.selectCollection(null)
            vm.setDateFilter(DateFilter.ALL_TIME)
            vm.setSearchQuery(token)
        }
    }

    private fun seed(label: String, kind: String = "TEXT", body: String = token, createdAt: Long = System.currentTimeMillis(), collectionId: String? = null): ItemEntity {
        val id = "selection-ui-${UUID.randomUUID()}"
        val row = ItemEntity(id, "Synthetic $label", body, kind, createdAt = createdAt)
        itemIds += id
        runBlocking {
            vm.repository.dao.insertItem(row)
            if (collectionId != null) vm.repository.dao.insertItemCollectionCrossRef(ItemCollectionCrossRef(id, collectionId))
        }
        return row
    }

    private fun collection(label: String): CollectionEntity {
        val row = CollectionEntity("selection-col-${UUID.randomUUID()}", "$label ${token.takeLast(8)}")
        collectionIds += row.id
        runBlocking { vm.repository.dao.insertCollection(row) }
        return row
    }

    private fun showLibrary(layout: LibraryLayout) {
        compose.runOnUiThread {
            vm.setLibraryLayout(layout)
            compose.activity.setContent { PersonalLibraryTheme { LibraryScreen(vm, {}, {}, {}, {}) } }
        }
    }

    private fun waitForVisible(expected: Set<String>) {
        compose.waitUntil(10_000) { vm.resultsReady.value && vm.localSearchResults.value.map { it.item.id }.toSet() == expected }
        compose.waitForIdle()
    }

    private fun selectAllVisible() {
        compose.onNodeWithTag("selection_more_button").performClick()
        compose.onNodeWithTag("select_all_visible_button").performClick()
        compose.waitUntil(10_000) { !vm.selectingVisible.value }
    }

    private fun collectionAction(tag: String, name: String) {
        compose.onNodeWithTag("selection_more_button").performClick()
        compose.onNodeWithTag(tag).performClick()
        compose.onNode(hasText(name) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("bulk_collections_apply").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("bulk_collections_apply").fetchSemanticsNodes().isEmpty() }
    }

    private fun assertActive(vararg ids: String) = runBlocking {
        ids.forEach { id -> assertEquals(false, vm.repository.dao.getItemById(id)?.isTrash) }
    }
}
