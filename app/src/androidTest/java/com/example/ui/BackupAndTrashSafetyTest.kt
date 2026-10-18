package com.example.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.MainActivity
import com.example.data.ItemEntity
import com.example.ui.components.EncryptedBackupPassphraseDialog
import com.example.ui.theme.PersonalLibraryTheme
import com.personallibrary.core.backup.BackupMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Synthetic-only regression coverage for the Phase 1 destructive-action boundaries. */
@RunWith(AndroidJUnit4::class)
class BackupAndTrashSafetyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var viewModel: MainViewModel
    private val syntheticItemIds = mutableListOf<String>()

    @After fun removeSyntheticItems() {
        if (::viewModel.isInitialized) {
            runBlocking {
                syntheticItemIds.forEach { viewModel.repository.deletePermanently(it) }
            }
        }
    }

    @Test fun settingsChooseDestinationRequiresTwelveMatchingCharacters() {
        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithTag("export_backup_button").performScrollTo().performClick()

        compose.onNodeWithText("Choose Destination").assertIsDisplayed()
        compose.onNodeWithText("cannot be recovered", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("backup_confirm_passphrase_input").assertIsDisplayed()
        compose.onNodeWithTag("submit_passphrase_button").assertIsNotEnabled()

        // Eleven matching characters must remain blocked; twelve is the engine boundary.
        replacePassphrases("12345678901", "12345678901")
        compose.onNodeWithTag("submit_passphrase_button").assertIsNotEnabled()
        replacePassphrases("123456789012", "")
        compose.onNodeWithTag("submit_passphrase_button").assertIsNotEnabled()
        replacePassphrases("12345678901a", "12345678901A")
        compose.onNodeWithTag("submit_passphrase_button").assertIsNotEnabled()
        replacePassphrases("123456789012", "123456789012")
        compose.onNodeWithTag("submit_passphrase_button").assertIsEnabled()

        // Do not open a system document picker or write a backup from this UI test.
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("backup_passphrase_input").assertDoesNotExist()
    }

    @Test fun explicitExportModeIgnoresRestoreLabelAndPreservesExactPassphrase() {
        val passphrase = "  Synthetic Mixed Case 42  "
        var submitted: String? = null
        showPassphraseDialog(BackupMode.EXPORT, "Inspect Archive") {
            submitted = it.concatToString()
            it.fill('\u0000')
        }

        compose.onNodeWithTag("backup_confirm_passphrase_input").assertIsDisplayed()
        replacePassphrases(passphrase, passphrase.trim())
        compose.onNodeWithTag("submit_passphrase_button").assertIsNotEnabled()
        compose.runOnIdle { assertNull(submitted) }

        replacePassphrases(passphrase, passphrase)
        compose.onNodeWithTag("submit_passphrase_button").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(passphrase, submitted) }
    }

    @Test fun explicitRestoreModeIgnoresExportLabelAndRequiresNoConfirmation() {
        val passphrase = "  Synthetic Restore 42  "
        var submitted: String? = null
        showPassphraseDialog(BackupMode.RESTORE, "Export Backup") {
            submitted = it.concatToString()
            it.fill('\u0000')
        }

        compose.onNodeWithTag("backup_confirm_passphrase_input").assertDoesNotExist()
        compose.onNodeWithTag("submit_passphrase_button").assertIsNotEnabled()
        compose.onNodeWithTag("backup_passphrase_input").performTextReplacement("12345678901")
        compose.onNodeWithTag("submit_passphrase_button").assertIsNotEnabled()
        compose.onNodeWithTag("backup_passphrase_input").performTextReplacement(passphrase)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("submit_passphrase_button").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(passphrase, submitted) }
    }

    @Test fun oneDeleteForeverTapOnlyAsksAndCancelPreservesTheDatabaseItem() {
        val saved = seedTrashedItem()
        openTrash(saved.id)

        compose.onNodeWithTag("perm_delete_btn_${saved.id}").performClick()
        compose.onNodeWithText("Delete Forever?").assertIsDisplayed()
        compose.onNodeWithText("cannot be undone", substring = true).assertIsDisplayed()
        assertSavedItemEquals(saved)

        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("confirm_perm_delete_button").assertDoesNotExist()
        compose.onNodeWithText(saved.title).assertIsDisplayed()
        assertSavedItemEquals(saved)
    }

    @Test fun confirmingDeleteForeverRemovesTheSelectedSyntheticItem() {
        val saved = seedTrashedItem()
        val retained = seedTrashedItem()
        openTrash(saved.id)

        compose.onNodeWithTag("perm_delete_btn_${saved.id}").performClick()
        assertSavedItemEquals(saved)
        compose.onNodeWithTag("confirm_perm_delete_button").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            runBlocking { viewModel.repository.dao.getItemById(saved.id) == null }
        }
        compose.onNodeWithText(saved.title).assertDoesNotExist()
        assertSavedItemEquals(retained)
    }

    @Test fun cancellingEmptyTrashPreservesItsSyntheticItems() {
        val first = seedTrashedItem()
        val second = seedTrashedItem()
        openTrash(first.id)

        compose.onNodeWithTag("empty_trash_button").performClick()
        compose.onNodeWithText("Empty Trash?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("confirm_empty_trash_button").assertDoesNotExist()
        assertSavedItemEquals(first)
        assertSavedItemEquals(second)
    }

    private fun replacePassphrases(passphrase: String, confirmation: String) {
        compose.onNodeWithTag("backup_passphrase_input").performTextReplacement(passphrase)
        compose.onNodeWithTag("backup_confirm_passphrase_input").performTextReplacement(confirmation)
        Espresso.closeSoftKeyboard()
    }

    private fun showPassphraseDialog(
        mode: BackupMode,
        actionButtonText: String,
        onConfirm: (CharArray) -> Unit
    ) {
        // Reuse the declared activity, including in the optimizedCheck test APK.
        compose.runOnUiThread {
            compose.activity.setContent {
                PersonalLibraryTheme {
                    EncryptedBackupPassphraseDialog(
                        title = "Synthetic backup safety test",
                        actionButtonText = actionButtonText,
                        mode = mode,
                        onDismiss = {},
                        onConfirm = onConfirm
                    )
                }
            }
        }
    }

    private fun seedTrashedItem(): ItemEntity {
        if (!::viewModel.isInitialized) {
            compose.runOnUiThread {
                viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            }
        }
        val id = "safety-ui-${UUID.randomUUID()}"
        val item = ItemEntity(
            id = id,
            title = "Synthetic safety item $id",
            content = "Synthetic text only",
            kind = "TEXT",
            isTrash = true,
            trashTimestamp = System.currentTimeMillis(),
            localIndexCoverage = "COMPLETE"
        )
        syntheticItemIds += id
        runBlocking { viewModel.repository.dao.insertItem(item) }
        return item
    }

    private fun openTrash(itemId: String) {
        compose.onNodeWithTag("settings_top_button").performClick()
        compose.onNodeWithTag("view_trash_button").performScrollTo().performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag("perm_delete_btn_$itemId").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("perm_delete_btn_$itemId").performScrollTo().assertIsDisplayed()
    }

    private fun assertSavedItemEquals(expected: ItemEntity) {
        assertEquals(expected, runBlocking { viewModel.repository.dao.getItemById(expected.id) })
    }
}
