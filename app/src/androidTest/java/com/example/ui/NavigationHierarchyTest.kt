package com.example.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
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

    @Test fun trashBackAndVisibleUpReturnThroughSettings() {
        openTrash()
        compose.onNodeWithContentDescription("Back to Settings").assertHasClickAction()
        Espresso.pressBack()
        compose.onNodeWithText("Settings & Portability").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to Library").performClick()
        compose.onNodeWithTag("settings_top_button").assertIsDisplayed()
        openTrash()
        compose.onNodeWithContentDescription("Back to Settings").performClick()
        compose.onNodeWithText("Settings & Portability").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithTag("settings_top_button").assertIsDisplayed()
    }

    @Test fun backDismissesTrashConfirmationBeforeNavigating() {
        val id = seed(trash = true)
        openTrash()
        compose.onNodeWithTag("trash_list").performScrollToNode(hasTestTag("perm_delete_btn_$id"))
        compose.onNodeWithTag("perm_delete_btn_$id").performClick()
        compose.onNodeWithText("Delete Forever?").assertIsDisplayed()
        Espresso.pressBack()
        compose.onNodeWithText("Delete Forever?").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to Settings").assertIsDisplayed()
        Espresso.pressBack()
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
        Espresso.pressBack()
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
        Espresso.pressBack()
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
        compose.onNodeWithTag("clear_filters_button").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithText("Trash (logical content size)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("View Trash").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Back to Settings").assertIsDisplayed().assertHasClickAction()
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
