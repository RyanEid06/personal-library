package com.memora.qa.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** UI-only management of unique synthetic records in the installed, permanent-signed APK. */
@RunWith(AndroidJUnit4::class)
class OfficialManagementTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private lateinit var marker: String
    private var guarded = false
    @get:Rule val testName = TestName()
    @get:Rule val failure = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) runCatching { ui.checkpoint("FAILED") }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) { Log.i("MemoraQA", "${description.methodName}: PASS") }
    }

    @Before fun verifyOfficialSyntheticEmulatorAndPauseAi() {
        val run = "${testName.methodName}-${SystemClock.elapsedRealtime()}"
        marker = "qm${SystemClock.elapsedRealtime()}"
        ui = ReleaseUi(instrumentation.uiAutomation, File(context.filesDir, "qa-evidence/$run"))
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the disposable emulator is allowed" }
        val names = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(names.isNotEmpty() && names.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        val info = context.packageManager.getPackageInfo(PRODUCT, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.versionName == "1.0.1" && info.longVersionCode == 3L) { "Expected official Memora 1.0.1/code3" }
        val signer = checkNotNull(info.signingInfo).apkContentsSigners.single().toByteArray()
        check(MessageDigest.getInstance("SHA-256").digest(signer).joinToString("") { "%02x".format(it) } ==
            "98686d31bfe4370cc30024439b56d9e72de71c081951d31f5d4fdd31d4eb0598") { "Expected permanent release signer" }
        guarded = true
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        // Reset transient navigation only; the product's files and existing synthetic records stay intact.
        restartProduct()
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
        Log.i("MemoraQA", "${testName.methodName}: guarded official-release management run; no reset")
    }

    @Test fun T18_T19_collectionCreateRenamePopulateReopenDeleteCancelAndRetainItems() {
        val original = "QA $marker Original"
        val renamed = "QA $marker Renamed"
        val retained = "QA $marker Retained"
        val title = "QA $marker Journal"
        val body = "$marker collection integrity café مرحبا 🚀"
        createCollection(original)
        createCollection(retained)
        addNote(title, body, listOf(original, retained))
        collectionResults(original, marker, 1, title)
        openResult(title, body)
        library()

        collectionMenu(original, "Edit Collection")
        ui.edit("Collection Name", "$renamed cancelled")
        dialogClick("Edit Collection", "Cancel")
        collectionMenu(original, "Edit Collection")
        ui.edit("Collection Name", renamed)
        dialogClick("Edit Collection", "Save")
        ui.await("Collection rename must persist") { ui.exact("Edit Collection") == null }
        collectionResults(renamed, marker, 1, title)
        openResult(title, body)
        library()

        collectionMenu(renamed, "Delete Collection")
        ui.waitText("Saved items will remain in Memora. Their membership in this collection will be removed. Membership in other collections will be preserved.")
        dialogClick("Delete Collection?", "Cancel")
        collectionResults(renamed, marker, 1, title)
        library()
        collectionMenu(renamed, "Delete Collection")
        dialogClick("Delete Collection?", "Delete")
        ui.await("Deleted collection must disappear") { ui.exact("Manage $renamed") == null && ui.exact("Delete Collection?") == null }
        collectionResults(retained, marker, 1, title)
        openResult(title, body)
        assertMembership(retained, true)
        check(ui.exact(renamed) == null) { "Deleted collection remains assignable" }
        library()
        search(marker, 1, title)
        ui.checkpoint("T18-T19-renamed-deleted-item-and-other-membership-retained")
    }

    @Test fun T20_noteAddCancelEditCancelAndPersistedFields() {
        val title = "QA $marker Original"
        val edited = "QA $marker Revised"
        val body = "$marker originalbody café مرحبا 🚀"
        val revised = "$marker revisedbody café مرحبا 🚀"
        addNote(title, body, cancel = true)
        search(marker, 0)
        addNote(title, body)
        search(marker, 1, title)
        openResult(title, body)
        ui.click("Edit Item")
        ui.edit("Title", "$edited cancelled")
        ui.edit("Body", "$marker cancelledbody")
        dialogClick("Edit Item", "Cancel")
        library()
        search("$marker cancelledbody", 0)
        search(marker, 1, title)
        openResult(title, body)
        ui.click("Edit Item")
        ui.edit("Title", edited)
        ui.edit("Body", revised)
        dialogClick("Edit Item", "Save")
        ui.await("Saved edit must dismiss") { ui.exact("Edit Item") == null }
        library()
        search("$marker originalbody", 0)
        search("$marker revisedbody", 1, edited)
        openResult(edited, revised)
        ui.checkpoint("T20-saved-title-body-and-search-updated")
    }

    @Test fun T21_manyToManyMembershipMovesPreserveOneLogicalItem() {
        val first = "QA $marker First"
        val second = "QA $marker Second"
        val title = "QA $marker Membership"
        val body = "$marker membership body café مرحبا 🚀"
        createCollection(first)
        createCollection(second)
        addNote(title, body, listOf(first, second))
        collectionResults(first, marker, 1, title)
        collectionResults(second, marker, 1, title)
        openResult(title, body)
        setMembership(first, false)
        assertMembership(second, true)
        library()
        collectionResults(first, marker, 0)
        collectionResults(second, marker, 1, title)
        openResult(title, body)
        setMembership(first, true)
        setMembership(second, false)
        library()
        collectionResults(first, marker, 1, title)
        collectionResults(second, marker, 0)
        library()
        search(marker, 1, title)
        openResult(title, body)
        assertMembership(first, true)
        assertMembership(second, false)
        ui.checkpoint("T21-membership-move-no-duplication-content-intact")
    }

    @Test fun T22_selectionDeselectCancelBulkMembershipAndTrashUndo() {
        val collection = "QA $marker Batch"
        val first = "QA $marker One"
        val second = "QA $marker Two"
        createCollection(collection)
        addNote(first, "$marker first synthetic batch body")
        addNote(second, "$marker second synthetic batch body")
        search(marker, 2, first, second)
        longSelect(first)
        ui.waitText("1 selected")
        ui.click(second)
        ui.waitText("2 selected")
        ui.click(first)
        ui.waitText("1 selected")
        ui.click("Cancel selection")
        assertResults(2, first, second)

        selectAll(first, 2)
        bulkMembership(collection, 2, add = true)
        ui.click("Cancel selection")
        collectionResults(collection, marker, 2, first, second)
        selectAll(first, 2)
        bulkMembership(collection, 2, add = false)
        ui.click("Cancel selection")
        assertResults(0)
        library()
        search(marker, 2, first, second)
        selectAll(first, 2)
        ui.click("Trash selected")
        ui.click("UNDO")
        assertResults(2, first, second)
        openResult(first, "$marker first synthetic batch body")
        library()
        search(marker, 2, first, second)
        openResult(second, "$marker second synthetic batch body")
        ui.checkpoint("T22-two-records-selection-bulk-and-undo-intact")
    }

    @Test fun T23_softDeleteUndoAndTrashRestorePreserveBodyAndMembership() {
        val collection = "QA $marker Restore"
        val title = "QA $marker Restore Note"
        val body = "$marker restored full note café مرحبا 🚀"
        createCollection(collection)
        addNote(title, body, listOf(collection))
        search(marker, 1, title)
        openResult(title, body)
        ui.click("Trash")
        ui.click("UNDO")
        assertResults(1, title)
        openResult(title, body)
        ui.click("Trash")
        assertResults(0)
        openTrash()
        ui.scrollTo(title)
        ui.checkpoint("T23-synthetic-note-in-trash")
        val count = trashCount()
        check(count > 0) { "Trash count must be known before restoring a synthetic record" }
        rowAction(title, "Restore")
        ui.await("Only the named synthetic record must leave Trash") { trashCount() == count - 1 && ui.exact(title) == null }
        library()
        search(marker, 1, title)
        openResult(title, body)
        assertMembership(collection, true)
        library()
        collectionResults(collection, marker, 1, title)
        openResult(title, body)
        ui.checkpoint("T23-restored-title-body-and-membership")
    }

    @Test fun T24_permanentDeleteAndEmptyTrashConfirmationsCancelSafely() {
        val title = "QA $marker Disposable"
        val body = "$marker disposable record only"
        addNote(title, body)
        search(marker, 1, title)
        openResult(title, body)
        ui.click("Trash")
        assertResults(0)
        openTrash()
        ui.scrollTo(title)
        val count = trashCount()
        check(count > 0) { "Trash count must be known before confirming deletion" }
        ui.click("Empty Trash")
        ui.waitText("All $count items in Trash will be permanently deleted along with their stored files. This action cannot be undone.")
        dialogClick("Empty Trash?", "Cancel")
        check(trashCount() == count) { "Cancelling Empty Trash changed the count" }
        ui.scrollTo(title)
        rowAction(title, "Delete Forever")
        ui.waitText("This item and its stored content will be permanently deleted. This action cannot be undone.")
        dialogClick("Delete Forever?", "Cancel")
        check(trashCount() == count) { "Cancelling item deletion changed the count" }
        ui.waitText(title)
        ui.checkpoint("T24-both-destructive-dialogs-cancelled")
        rowAction(title, "Delete Forever")
        dialogClick("Delete Forever?", "Delete Forever")
        ui.await("Only the disposable synthetic record must be permanently removed") { trashCount() == count - 1 && ui.exact(title) == null }
        restartProduct()
        search(marker, 0)
        openTrash()
        check(trashCount() == count - 1) { "Permanent deletion did not survive restart" }
        ui.checkpoint("T24-single-record-permanent-delete-survives-restart")
    }

    @Test fun T25_T26_rapidLatestQueryAndRestartIndexPersistence() {
        val first = "QA $marker Amber"
        val second = "QA $marker Violet"
        val firstBody = "$marker amberword café مرحبا 🚀"
        val secondBody = "$marker violetword synthetic journal"
        val collection = "QA $marker Persistent"
        createCollection(collection)
        addNote(first, firstBody, listOf(collection))
        addNote(second, secondBody)
        search(marker, 2, first, second)
        repeat(4) {
            listOf("$marker amber", "$marker absentword", "$marker violet", marker, "$marker amberword").forEach(::rapidQuery)
        }
        assertResults(1, first)
        check(ui.exact(second) == null) { "Stale result survived the final rapid query" }
        ui.checkpoint("T25-final-query-results")
        restartProduct()
        search("$marker amberword", 1, first)
        openResult(first, firstBody)
        assertMembership(collection, true)
        library()
        search("$marker violetword", 1, second)
        openResult(second, secondBody)
        library()
        search(marker, 2, first, second)
        ui.click("Toggle Grid/List View")
        assertResults(2, first, second)
        ui.click("Toggle Grid/List View")
        assertResults(2, first, second)
        collectionResults(collection, marker, 1, first)
        ui.checkpoint("T26-restart-search-body-membership-and-layout-consistency")
    }

    private fun launchProduct() {
        check(guarded)
        context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("Memora must be foreground") { ui.nodes().any { it.packageName?.toString() == PRODUCT } }
    }

    private fun restartProduct() {
        check(guarded)
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
    }

    private fun library() {
        if (trashCount() >= 0) {
            ui.click("Back to Settings")
            ui.waitText("Back to Library")
        }
        if (ui.exact("Back to Library") != null) {
            ui.click("Back to Library")
            ui.waitText("Library")
        }
        if (ui.nodes().any { it.contentDescription?.toString() == "Drag handle" }) {
            // The item sheet's Edit action can be scrolled out of view while checking memberships.
            ui.back()
            ui.await("Item sheet must dismiss") {
                ui.nodes().none { it.contentDescription?.toString() == "Drag handle" }
            }
            ui.waitText("Library")
        }
        if (ui.exact("Edit Item") != null || ui.exact("Add to Library") != null) {
            ui.back()
            ui.await("Item dialog must dismiss") { ui.exact("Edit Item") == null && ui.exact("Add to Library") == null }
        }
        if (ui.exact("Cancel selection") != null) ui.click("Cancel selection")
        ui.click("Library")
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
    }

    private fun collections() {
        library()
        ui.click("Collections")
        ui.waitText("Add Collection")
        repeat(20) { if (!ui.scroll(forward = false)) return; SystemClock.sleep(80) }
    }

    private fun createCollection(name: String) {
        collections()
        ui.click("Add Collection")
        ui.waitText("New Collection")
        ui.edit("Collection Name", name)
        dialogClick("New Collection", "Create")
        ui.await("Created collection dialog must dismiss") { ui.exact("New Collection") == null }
        ui.scrollTo("Manage $name", 24)
        ui.checkpoint("collection-created-${name.takeLast(12)}")
    }

    private fun collectionMenu(name: String, action: String) {
        collections()
        ui.scrollTo("Manage $name", 24)
        ui.click("Manage $name")
        ui.click(action)
    }

    private fun collectionResults(name: String, query: String, count: Int, vararg titles: String) {
        collections()
        ui.scrollTo("Manage $name", 24)
        // Open the unique collection by its manage menu, avoiding a same-named item or filter chip.
        ui.click("Manage $name")
        ui.click("Open")
        ui.waitText("Collection: $name")
        search(query, count, *titles)
    }

    private fun addNote(title: String, body: String, collections: List<String> = emptyList(), cancel: Boolean = false) {
        library()
        ui.click("Add")
        ui.waitText("Add to Library")
        ui.edit("Title (Optional)", title)
        ui.edit("Note Content / Text / Grades", body)
        collections.forEach { setMembership(it, true) }
        dialogClick("Add to Library", if (cancel) "Cancel" else "Save Note")
        ui.await("Add Note dialog must dismiss") { ui.exact("Add to Library") == null }
    }

    private fun search(query: String, count: Int, vararg titles: String) {
        ui.edit(SEARCH, query)
        assertResults(count, *titles)
    }

    private fun assertResults(count: Int, vararg titles: String) {
        var stableSince = 0L
        ui.await("Expected $count current saved results with the synthetic titles", 45000) {
            val ready = ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.matches(Regex("$count items • (Saved|Source) Date")) == true } &&
                ui.exact("Searching your library…") == null && titles.all { ui.exact(it) != null }
            if (!ready) stableSince = 0L else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            ready && SystemClock.uptimeMillis() - stableSince >= 450
        }
    }

    private fun openResult(title: String, body: String) {
        ui.click(title)
        ui.waitText("Edit Item")
        ui.scrollTo(body)
        ui.waitText(body)
    }

    private fun setMembership(name: String, checked: Boolean) {
        ui.scrollTo(name, 24)
        val checkbox = checkNotNull(membershipCheckbox(name)) { "No unambiguous collection checkbox: $name" }
        if (checkbox.isChecked != checked) ui.clickNode(checkbox)
        ui.await("Collection membership checkbox did not update: $name") { membershipCheckbox(name)?.isChecked == checked }
    }

    private fun assertMembership(name: String, checked: Boolean) {
        ui.scrollTo(name, 24)
        check(membershipCheckbox(name)?.isChecked == checked) { "Reopened membership differs from saved state: $name" }
    }

    private fun membershipCheckbox(name: String): AccessibilityNodeInfo? {
        val label = ui.exact(name) ?: return null
        val labelBounds = Rect().also(label::getBoundsInScreen)
        // Compose can flatten the checkbox and label; match their live row bounds first.
        val aligned = ui.nodes().filter { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            node.isVisibleToUser && node.isCheckable && bounds.right <= labelBounds.left &&
                bounds.top < labelBounds.bottom && bounds.bottom > labelBounds.top
        }.singleOrNull()
        if (aligned != null) return aligned
        var ancestor: AccessibilityNodeInfo? = label
        repeat(7) {
            val current = ancestor ?: return null
            val choices = descendants(current).filter { it.isVisibleToUser && it.isCheckable }
            if (choices.size == 1) return choices.single()
            if (choices.size > 1) return null
            ancestor = current.parent
        }
        return null
    }

    private fun longSelect(title: String) {
        ui.waitText(title)
        var ancestor = ui.exact(title)
        repeat(12) {
            val current = ancestor ?: throw AssertionError("No Select item action for synthetic title")
            if (current.isLongClickable && current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return
            ancestor = current.parent
        }
        throw AssertionError("Synthetic item has no accessible long-click selection")
    }

    private fun selectAll(title: String, count: Int) {
        longSelect(title)
        ui.waitText("1 selected")
        ui.click("Selection actions")
        ui.click("Select All Visible ($count)")
        ui.waitText("$count selected")
    }

    private fun bulkMembership(collection: String, count: Int, add: Boolean) {
        ui.click("Selection actions")
        ui.click(if (add) "Add to collections" else "Remove from collections")
        val title = if (add) "Add $count items to collections" else "Remove $count items from collections"
        ui.waitText(title)
        setMembership(collection, true)
        dialogClick(title, "Apply")
        ui.await("Bulk collection action must finish") { ui.exact(title) == null }
    }

    private fun openTrash() {
        library()
        ui.click("Settings")
        ui.scrollTo("View Trash", 24)
        ui.click("View Trash")
        ui.await("Trash screen must open") { ui.exact("Back to Settings") != null }
    }

    private fun trashCount(): Int = ui.nodes().mapNotNull { node ->
        if (node.isVisibleToUser) Regex("Trash \\((\\d+)\\)").matchEntire(node.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt() else null
    }.singleOrNull() ?: -1

    private fun rowAction(title: String, action: String) {
        ui.scrollTo(title, 24)
        val titleBounds = Rect().also(checkNotNull(ui.exact(title))::getBoundsInScreen)
        val aligned = ui.nodes().filter { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            node.isVisibleToUser && (node.text?.toString() == action || node.contentDescription?.toString() == action) &&
                bounds.left >= titleBounds.right && bounds.top < titleBounds.bottom && bounds.bottom > titleBounds.top
        }.singleOrNull()
        if (aligned != null) { ui.clickNode(aligned); return }
        var ancestor = ui.exact(title)
        repeat(10) {
            val current = ancestor ?: throw AssertionError("No action row for unique synthetic title")
            val matches = descendants(current).filter { it.isVisibleToUser && (it.text?.toString() == action || it.contentDescription?.toString() == action) }
            if (matches.size == 1) { ui.clickNode(matches.single()); return }
            check(matches.size < 2) { "Ambiguous action; refusing to touch another record" }
            ancestor = current.parent
        }
        throw AssertionError("No $action action belonging to the synthetic record")
    }

    private fun dialogClick(title: String, action: String) {
        // Collection choices may have scrolled the dialog heading offscreen.
        var remaining = 24
        while (ui.exact(title) == null && remaining-- > 0 && ui.scroll(forward = false)) {
            SystemClock.sleep(80)
        }
        ui.waitText(title)
        var ancestor = ui.exact(title)
        ui.scrollTo(action, 24)
        repeat(14) {
            val current = ancestor ?: throw AssertionError("No dialog scope for $title")
            val children = descendants(current).filter { it.isVisibleToUser }
            val matches = children.filter { it.text?.toString() == action || it.contentDescription?.toString() == action }
            if (children.any { it.text?.toString() == "Cancel" } && matches.size == 1) {
                ui.clickNode(matches.single())
                if (title == "Edit Item") {
                    ui.await("Edit form must close before returning to the item") {
                        ui.exact("Title") == null && ui.exact("Body") == null
                    }
                } else {
                    ui.await("$title dialog must close before reading the underlying screen") {
                        ui.exact(title) == null
                    }
                }
                return
            }
            ancestor = current.parent
        }
        throw AssertionError("No unambiguous $action action scoped to $title")
    }

    private fun rapidQuery(query: String) {
        val field = ui.nodes().filter { it.isVisibleToUser && it.isEditable }.single()
        check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, query)
        })) { "Rapid query was rejected" }
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { result += descendants(it) }
        return result
    }

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
    }
}
