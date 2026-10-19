package com.example.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.example.ui.components.ActiveSearchFilters
import com.example.ui.components.ItemCard
import com.example.ui.components.ItemGridCard
import com.example.ui.screens.CollectionsScreen
import com.example.ui.screens.CreateCollectionDialog
import com.example.ui.theme.PersonalLibraryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic cards only: no real library records or external share targets are accessed. */
@RunWith(AndroidJUnit4::class)
class ItemCardParityUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun listCardActionsRemainIndependentAndDisabledInSelection() = verifyActions(grid = false)
    @Test fun gridCardActionsRemainIndependentAndDisabledInSelection() = verifyActions(grid = true)

    private fun verifyActions(grid: Boolean) {
        val item = ItemWithCollections(ItemEntity("parity", "Synthetic title", "Synthetic body", "TEXT",
            isFavorite = true, reminderTime = 1L), emptyList())
        val selecting = mutableStateOf(false)
        var shared: Intent? = null
        var opens = 0
        var favorites = 0
        var trash = 0
        show {
            val base = LocalContext.current
            val context = remember(base) {
                object : ContextWrapper(base) {
                    override fun startActivity(intent: Intent) { shared = intent }
                }
            }
            CompositionLocalProvider(LocalContext provides context) {
                if (grid) ItemGridCard(item, { null }, { opens++ }, { favorites++ }, { trash++ }, isSelectMode = selecting.value)
                else ItemCard(item, { null }, { opens++ }, { favorites++ }, { trash++ }, isSelectMode = selecting.value)
            }
        }
        compose.onNodeWithContentDescription("Reminder set").assertExists()
        compose.onNodeWithContentDescription("Remove from Favorites").assertExists()
        compose.onNodeWithTag("share_btn_parity").assertIsEnabled().performClick()
        compose.runOnIdle {
            val chooser = requireNotNull(shared)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("Synthetic title\n\nSynthetic body", send.getStringExtra(Intent.EXTRA_TEXT))
            assertEquals(0, opens)
        }
        compose.onNodeWithTag("fav_button_parity").performClick()
        compose.onNodeWithTag("trash_btn_parity").performClick()
        compose.onNodeWithTag(if (grid) "item_grid_card_parity" else "item_card_parity").performClick()
        compose.runOnIdle {
            assertEquals(1, opens)
            assertEquals(1, favorites)
            assertEquals(1, trash)
            selecting.value = true
        }
        listOf("fav_button_parity", "share_btn_parity", "trash_btn_parity").forEach {
            compose.onNodeWithTag(it).assertIsNotEnabled()
        }
    }

    @Test fun narrowGridAtLargeTextKeepsAllActionTargetsInsideTheCard() {
        verifyLargeTextTargets(grid = true, width = 160)
    }

    @Test fun narrowListAtLargeTextKeepsAllActionTargetsInsideTheCard() {
        verifyLargeTextTargets(grid = false, width = 280)
    }

    private fun verifyLargeTextTargets(grid: Boolean, width: Int) {
        val item = ItemWithCollections(ItemEntity("scaled", "Synthetic document", "Body", "DOCUMENT", reminderTime = 1L), emptyList())
        show {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                Box(Modifier.width(width.dp)) {
                    if (grid) ItemGridCard(item, { null }, {}, {}, {})
                    else ItemCard(item, { null }, {}, {}, {})
                }
            }
        }
        val card = compose.onNodeWithTag(if (grid) "item_grid_card_scaled" else "item_card_scaled").getUnclippedBoundsInRoot()
        listOf("fav_button_scaled", "share_btn_scaled", "trash_btn_scaled").forEach { tag ->
            compose.onNodeWithTag(tag).assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            val action = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag must fit horizontally", action.left >= card.left && action.right <= card.right)
        }
    }

    @Test fun computedCollectionUsesTruthfulRemindersName() {
        lateinit var vm: MainViewModel
        compose.runOnUiThread { vm = ViewModelProvider(compose.activity)[MainViewModel::class.java] }
        show { CollectionsScreen(vm, {}) }
        compose.onNodeWithTag("col_card_due").assertTextContains("Reminders", substring = true)
        compose.onNodeWithText("Due Reminders").assertDoesNotExist()
    }

    @Test fun activeCollectionFilterUsesTheSameRemindersName() {
        show {
            ActiveSearchFilters("computed_due", emptyList(), null, DateFilter.ALL_TIME, DateBasis.SAVED_DATE,
                null, null, {}, {}, {}, {})
        }
        compose.onNodeWithText("Collection: Reminders").assertIsDisplayed()
        compose.onNodeWithText("Collection: Due Reminders").assertDoesNotExist()
    }

    @Test fun collectionChoicesDescribeTheirMeaningAndKeepSelectedState() {
        show { CreateCollectionDialog({}, { _, _, _ -> }) }
        compose.onNodeWithContentDescription("Folder collection icon").assertIsSelected()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Blue collection color").assertIsSelected()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("School collection icon").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Folder collection icon").assertIsNotSelected()
        compose.onNodeWithContentDescription("Green collection color").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Blue collection color").assertIsNotSelected()
        compose.onNodeWithContentDescription("Icon folder").assertDoesNotExist()
        compose.onNodeWithContentDescription("Color #3B82F6").assertDoesNotExist()
    }

    private fun show(content: @Composable () -> Unit) {
        compose.runOnUiThread { compose.activity.setContent { PersonalLibraryTheme { content() } } }
    }
}
