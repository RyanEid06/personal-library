package com.example.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.MainAppScaffold
import com.example.data.ItemEntity
import com.example.reminder.ReminderReceiver
import com.example.model.DateFilter
import com.example.model.LibraryLayout
import com.personallibrary.core.model.ItemKind
import com.example.ui.theme.PersonalLibraryTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** All rows are synthetic; exercises the actual app's back dispatch and new-intent delivery. */
@RunWith(AndroidJUnit4::class)
class NavigationHierarchyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val ids = mutableListOf<String>()
    private val vm get() = ViewModelProvider(compose.activity)[MainViewModel::class.java]

    @After fun cleanup() {
        compose.runOnUiThread { vm.clearSelection(); vm.setSearchQuery(""); vm.clearFilters() }
        runBlocking { ids.forEach { vm.repository.deletePermanently(it) } }
    }

    @Test fun onlyThreeDestinationsAndFolderDetailStaysInCollections() {
        compose.onNodeWithTag("nav_item_library").assertIsSelected()
        compose.onNodeWithTag("nav_item_settings").assertExists()
        compose.onNodeWithTag("nav_item_search").assertDoesNotExist()
        compose.onNodeWithTag("nav_item_collections").performClick()
        compose.onNodeWithTag("col_card_favorites").performClick()
        compose.onNodeWithTag("nav_item_collections").assertIsSelected()
        compose.onNodeWithContentDescription("Back to Collections").assertIsDisplayed()
        compose.onNodeWithTag("clear_filters_button").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("computed_favorites", vm.selectedCollectionId.value) }
        compose.onNodeWithTag("nav_item_library").performClick()
        compose.runOnIdle { org.junit.Assert.assertNull(vm.selectedCollectionId.value) }
        compose.onNodeWithTag("nav_item_collections").performClick()
        compose.onNodeWithTag("nav_item_collections").assertIsSelected()
        compose.onNodeWithContentDescription("Back to Collections").performClick()
        compose.onNodeWithTag("collections_grid").assertIsDisplayed()
    }

    @Test fun switchingTabsAndFoldersKeepsEachQueryAndItsImmutableScope() {
        compose.onNodeWithTag("library_search_input").performTextReplacement("Synthetic Library query")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("nav_item_collections").performClick()
        compose.onNodeWithTag("col_card_favorites").performClick()
        compose.onNodeWithTag("library_search_input").performTextReplacement("Synthetic Favorites query")
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("clear_filters_button").performScrollTo().performClick()
        compose.runOnIdle { org.junit.Assert.assertEquals("computed_favorites", vm.selectedCollectionId.value) }
        compose.onNodeWithTag("nav_item_library").performClick()
        compose.onNodeWithTag("library_search_input").performScrollTo().assertTextContains("Synthetic Library query")
        compose.onNodeWithTag("nav_item_collections").performClick()
        compose.onNodeWithTag("library_search_input").performScrollTo().assertTextContains("Synthetic Favorites query")
        compose.onNodeWithContentDescription("Back to Collections").performClick()
        compose.onNodeWithTag("col_card_due").performClick()
        compose.runOnIdle {
            org.junit.Assert.assertEquals("", vm.searchQuery.value)
            org.junit.Assert.assertEquals("computed_due", vm.selectedCollectionId.value)
        }
        compose.onNodeWithContentDescription("Back to Collections").performClick()
        compose.onNodeWithTag("col_card_favorites").performClick()
        compose.onNodeWithTag("library_search_input").performScrollTo().assertTextContains("Synthetic Favorites query")
    }

    @Test fun trashBackAndVisibleUpReturnThroughSettings() {
        openTrash()
        compose.onNodeWithContentDescription("Back to Settings").assertHasClickAction()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Settings & Portability").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Library").performClick()
        compose.onNodeWithTag("settings_top_button").assertIsDisplayed()
        openTrash()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.onNodeWithText("Settings & Portability").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings_top_button").assertIsDisplayed()
    }

    @Test fun backDismissesTrashConfirmationBeforeNavigating() {
        val id = seed(trash = true)
        openTrash()
        compose.onNodeWithTag("trash_list").performScrollToNode(hasTestTag("perm_delete_btn_$id"))
        compose.onNodeWithTag("perm_delete_btn_$id").performClick()
        compose.onNodeWithText("Delete Forever?").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Delete Forever?").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to Settings").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithText("Settings & Portability").assertIsDisplayed()
    }

    @Test fun notificationDetailSurvivesRecreationButDismissedDeliveryDoesNotReopen() {
        val id = seed()
        compose.runOnUiThread {
            // Preserve the launch filter so ActivityScenario can observe the replacement instance.
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, Intent(compose.activity.intent)
                .putExtra(ReminderReceiver.EXTRA_ITEM_ID, id))
        }
        compose.onNodeWithTag("edit_item_button").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("edit_item_button").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("settings_top_button").assertIsDisplayed()
        seed()
        compose.waitForIdle()
        compose.onNodeWithTag("settings_top_button").assertIsDisplayed()
        compose.onNodeWithTag("edit_item_button").assertDoesNotExist()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("edit_item_button").assertDoesNotExist()
        compose.runOnUiThread {
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(compose.activity, Intent(compose.activity.intent)
                .putExtra(ReminderReceiver.EXTRA_ITEM_ID, id))
        }
        compose.onNodeWithTag("edit_item_button").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
    }

    @Test fun overlappingSharesReuseTheTaskQueueUntilConsumedAndWaitBehindAppLock() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val info = app.packageManager.getActivityInfo(android.content.ComponentName(app, MainActivity::class.java), 0)
        org.junit.Assert.assertEquals(android.content.pm.ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        val originalTask = compose.activity.taskId
        val lockWasEnabled = vm.appLockManager.isLockEnabled()
        try {
            compose.runOnUiThread { vm.appLockManager.setLockEnabled(false) }
            app.startActivity(Intent(Intent.ACTION_SEND).setClass(app, MainActivity::class.java).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Synthetic first shared note").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(10_000) { vm.incomingShare.value?.text == "Synthetic first shared note" }
            compose.onNodeWithText("Synthetic first shared note").assertIsDisplayed()
            org.junit.Assert.assertEquals(originalTask, compose.activity.taskId)
            // Deliver a second share while the first preview still owns its destination choice.
            app.startActivity(Intent(Intent.ACTION_SEND_MULTIPLE).setClass(app, MainActivity::class.java).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(
                    android.net.Uri.parse("content://synthetic-test/first"), android.net.Uri.parse("content://synthetic-test/second")))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            compose.waitForIdle()
            compose.onNodeWithText("Synthetic first shared note").assertIsDisplayed()
            compose.onNodeWithText("2 file(s) attached").assertDoesNotExist()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Synthetic first shared note").assertIsDisplayed()
            compose.onNodeWithText("Discard").performScrollTo().performClick()
            compose.waitUntil(10_000) { vm.incomingShare.value?.uris?.size == 2 }
            compose.onNodeWithText("2 file(s) attached").assertIsDisplayed()
            compose.onNodeWithText("Discard").performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("save_share_button").assertDoesNotExist()
            compose.runOnUiThread { vm.appLockManager.setLockEnabled(true) }
            app.startActivity(Intent(Intent.ACTION_SEND).setClass(app, MainActivity::class.java).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Synthetic locked share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            compose.waitUntil(10_000) { vm.incomingShare.value?.text == "Synthetic locked share" }
            compose.onNodeWithText("Memora is Locked").assertIsDisplayed()
            compose.onNodeWithTag("save_share_button").assertDoesNotExist()
            compose.runOnIdle {
                org.junit.Assert.assertEquals("Synthetic locked share", vm.incomingShare.value!!.text)
                vm.appLockManager.unlock()
            }
            compose.onNodeWithText("Synthetic locked share").assertIsDisplayed()
            org.junit.Assert.assertEquals(originalTask, compose.activity.taskId)
            compose.onNodeWithText("Discard").performScrollTo().performClick()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithTag("save_share_button").assertDoesNotExist()
        } finally {
            compose.runOnUiThread {
                repeat(3) { if (vm.incomingShare.value != null) vm.setIncomingShare(null) }
                vm.appLockManager.setLockEnabled(lockWasEnabled)
            }
        }
    }

    @Test fun largeTextRetainsScrollableLibraryControlsClearFiltersAndTrashActions() {
        compose.runOnUiThread {
            vm.setDateFilter(DateFilter.THIS_MONTH)
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    PersonalLibraryTheme { MainAppScaffold(vm, null) }
                }
            }
        }
        compose.onNodeWithTag("sort_order_button").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("clear_filters_button").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithText("Trash (logical content size)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View Trash").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to Settings").assertIsDisplayed().assertHasClickAction()
    }

    @Test fun shortViewportAtLargeTextKeepsListAndGridResultsAndFiltersReachable() {
        val id = seed()
        val title = "Synthetic navigation $id"
        compose.runOnUiThread {
            vm.setSearchQuery(id)
            compose.activity.setContent {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
                    PersonalLibraryTheme {
                        Box(Modifier.fillMaxWidth().height(260.dp)) { MainAppScaffold(vm, null) }
                    }
                }
            }
        }
        // A pending transient confirmation can cover this deliberately tiny viewport.
        compose.waitUntil(15_000) { vm.captureState.value.message == null && !vm.captureState.value.saving }
        for (layout in listOf(LibraryLayout.LIST, LibraryLayout.GRID)) {
            compose.runOnUiThread { vm.setLibraryLayout(layout); vm.selectKind(ItemKind.TEXT) }
            compose.waitUntil(10_000) { vm.resultsReady.value && vm.localSearchResults.value.singleOrNull()?.item?.id == id }
            val container = if (layout == LibraryLayout.LIST) "library_list" else "library_grid"
            scrollLibraryTo(container, hasText(title))
            clickLibraryTarget(container, hasText(title))
            compose.onNodeWithTag("edit_item_button").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.onNodeWithTag("edit_item_button").assertDoesNotExist()
            scrollLibraryTo(container, hasTestTag("active_type_chip"))
            clickLibraryTarget(container, hasTestTag("active_type_chip"))
            compose.runOnIdle { org.junit.Assert.assertNull(vm.selectedKind.value) }
            compose.runOnUiThread { vm.selectKind(ItemKind.TEXT) }
            scrollLibraryTo(container, hasTestTag("clear_filters_button"))
            clickLibraryTarget(container, hasTestTag("clear_filters_button"))
            compose.runOnIdle { org.junit.Assert.assertNull(vm.selectedKind.value) }
            scrollLibraryTo(container, hasTestTag("filter_chip_all"))
            compose.onNodeWithTag("filter_chip_all").assertIsDisplayed().assertHasClickAction()
            scrollLibraryTo(container, hasTestTag("sort_order_button"))
            compose.onNodeWithTag("sort_order_button").assertIsDisplayed().assertHasClickAction()
            scrollLibraryTo(container, hasTestTag("library_search_input"))
            compose.onNodeWithTag("library_search_input").assertIsDisplayed()
        }
    }

    @Test fun searchKeepsTypingFocusAcrossLoadingAndEmptyResultsInListAndGrid() {
        val id = seed()
        for (layout in listOf(LibraryLayout.LIST, LibraryLayout.GRID)) {
            compose.runOnUiThread { vm.setLibraryLayout(layout); vm.setSearchQuery(""); vm.clearFilters() }
            compose.waitUntil(10_000) { vm.resultsReady.value && vm.localSearchResults.value.any { it.item.id == id } }
            val search = compose.onNodeWithTag("library_search_input")
            search.performScrollTo().performClick().assertIsFocused()
            assertKeyboardVisible()
            search.performTextInput(id.take(1))
            compose.waitUntil(10_000) { vm.resultsReady.value && vm.searchQuery.value == id.take(1) }
            search.assertIsFocused()
            assertKeyboardVisible()
            search.performTextInput(id.drop(1))
            compose.waitUntil(10_000) { vm.resultsReady.value && vm.localSearchResults.value.singleOrNull()?.item?.id == id }
            search.assertIsFocused()
            assertKeyboardVisible()
            search.performTextInput("-no-match")
            compose.waitUntil(10_000) { vm.resultsReady.value && vm.localSearchResults.value.isEmpty() }
            search.assertIsFocused()
            assertKeyboardVisible()
            search.performTextClearance()
            compose.waitUntil(10_000) { vm.resultsReady.value && vm.localSearchResults.value.any { it.item.id == id } }
            search.assertIsFocused()
            assertKeyboardVisible()
        }
    }

    private fun assertKeyboardVisible() {
        compose.waitUntil(5_000) {
            var visible = false
            compose.runOnUiThread {
                visible = androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
    }

    private fun scrollLibraryTo(container: String, target: SemanticsMatcher) {
        val trace = StringBuilder()
        // Resolve the replacement list/grid without Compose's off-thread failure-tree dump.
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag(container).fetchSemanticsNodes().singleOrNull()?.let {
                it.boundsInRoot.height > 0 && it.config.contains(SemanticsActions.ScrollBy)
            } == true
        }
        // Scan half a viewport on the UI thread: tiny hosts defeat touch slop and skip targets on flings.
        for (towardTop in listOf(false, true)) {
            repeat(160) {
                compose.waitForIdle()
                val host = checkNotNull(compose.onAllNodesWithTag(container).fetchSemanticsNodes().singleOrNull()) {
                    "Expected one $container viewport while scrolling; $trace"
                }
                fun scrollBy(distance: Float) = compose.runOnUiThread {
                    check(host.config.contains(SemanticsActions.ScrollBy)) { "$container lost its scroll action; $trace" }
                    checkNotNull(host.config[SemanticsActions.ScrollBy].action)(0f, distance)
                }
                val targetNode = compose.onNode(target)
                if (targetNode.isDisplayed()) {
                    val viewport = host.boundsInRoot
                    val full = targetNode.getUnclippedBoundsInRoot()
                    val top = with(compose.density) { full.top.toPx() }
                    val bottom = with(compose.density) { full.bottom.toPx() }
                    val delta = if (bottom - top <= viewport.height) {
                        when { top < viewport.top -> top - viewport.top; bottom > viewport.bottom -> bottom - viewport.bottom; else -> 0f }
                    } else {
                        when { top > viewport.top -> top - viewport.top; bottom < viewport.bottom -> bottom - viewport.bottom; else -> 0f }
                    }
                    if (kotlin.math.abs(delta) <= 1f) return
                    scrollBy(delta)
                    return@repeat
                }
                trace.appendLine("towardTop=$towardTop step=$it host=${host.boundsInRoot}")
                val distance = maxOf(1f, host.boundsInRoot.height / 2)
                scrollBy(if (towardTop) -distance else distance)
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val evidence = java.io.File(instrumentation.targetContext.filesDir, "qa-ci-viewport").apply { mkdirs() }
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        if (bitmap != null) {
            java.io.File(evidence, "failed.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
        java.io.File(evidence, "failed.txt").writeText(trace.toString())
        org.junit.Assert.assertTrue(
            "Target $target unreachable in $container; layout=${vm.libraryLayout.value}; ready=${vm.resultsReady.value}; results=${vm.localSearchResults.value.size}\n$trace",
            compose.onNode(target).isDisplayed()
        )
    }

    private fun clickLibraryTarget(container: String, target: SemanticsMatcher) {
        val node = compose.onNode(target).assertIsDisplayed().assertHasClickAction()
        val visible = node.fetchSemanticsNode().boundsInRoot
            .intersect(compose.onNodeWithTag(container).fetchSemanticsNode().boundsInRoot)
        val fab = compose.onNodeWithTag("fab_add").fetchSemanticsNode().boundsInRoot
        val dx = minOf(8f, visible.width / 4)
        val dy = minOf(8f, visible.height / 4)
        val point = listOf(visible.center,
            Offset(visible.left + dx, visible.center.y), Offset(visible.right - dx, visible.center.y),
            Offset(visible.center.x, visible.top + dy), Offset(visible.center.x, visible.bottom - dy))
            .firstOrNull { !fab.contains(it) }
        org.junit.Assert.assertNotNull("No visible touch point outside Add: target=$visible Add=$fab", point)
        val root = compose.onRoot()
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        root.performTouchInput { click(requireNotNull(point) - origin) }
        compose.waitForIdle()
    }

    private fun openTrash() {
        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithText("View Trash").performScrollTo().performClick()
        compose.onNodeWithTag("trash_list").assertIsDisplayed()
    }

    private fun seed(trash: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        ids += id
        runBlocking { vm.repository.dao.insertItem(ItemEntity(
            id = id, kind = "TEXT", title = "Synthetic navigation $id", content = "Synthetic navigation content",
            createdAt = System.currentTimeMillis(), isTrash = trash,
            trashTimestamp = if (trash) System.currentTimeMillis() else null
        )) }
        compose.waitUntil(10_000) { if (trash) vm.trashItems.value.any { it.id == id } else vm.items.value.any { it.item.id == id } }
        return id
    }
}
