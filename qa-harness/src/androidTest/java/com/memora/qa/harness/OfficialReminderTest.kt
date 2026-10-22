package com.memora.qa.harness

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale

/** Real picker/alarm/notification flows in the guarded official APK; no clock or database changes. */
@RunWith(AndroidJUnit4::class)
class OfficialReminderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private lateinit var token: String
    private var guarded = false
    private data class Posted(val title: String, val text: String, val at: Long)
    private val posted = Collections.synchronizedList(mutableListOf<Posted>())
    @get:Rule val testName = TestName()
    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) runCatching {
                if (foreground() in setOf("com.android.launcher3", "com.google.android.apps.nexuslauncher")) {
                    ui.shell("cmd statusbar expand-notifications")
                    ui.await("Expose Android notification state for timeout evidence", 3000) { foreground() == "com.android.systemui" }
                }
                ui.checkpoint("FAILED")
            }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) { Log.i("MemoraQA", "${description.methodName}: PASS") }
    }

    @Before fun guardOfficialSyntheticEmulatorAndPauseAi() {
        token = "qr${SystemClock.elapsedRealtime()}"
        ui = ReleaseUi(instrumentation.uiAutomation, File(context.filesDir, "qa-evidence/${testName.methodName}-$token"))
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
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            eventTypes = eventTypes or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        }
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.packageName?.toString() == PRODUCT && event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
                val notification = event.parcelableData as? Notification
                val title = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                if (title.startsWith("Reminder: QA $token ")) {
                    posted += Posted(title, notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(), System.currentTimeMillis())
                }
            }
        }
        launchProduct()
        dismissTransientDialogs()
        library()
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
    }

    @After fun detachListenerAndCloseShade() {
        instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        if (guarded && foreground() == "com.android.systemui") ui.shell("cmd statusbar collapse")
    }

    @Test fun T31_nativeReminderCreateEditPickerCancelAndClearPersist() {
        val title = "QA $token Editable"
        val body = "$token synthetic editable reminder café مرحبا"
        addNoteAndOpen(title, body)
        val first = futureMinute(10)
        chooseDateAndTime(first)
        assertReminder(first)
        reopen(title, body)
        assertReminder(first)

        ui.scrollTo("Edit Reminder")
        ui.click("Edit Reminder")
        ui.click("Date + time")
        awaitDatePicker()
        nativeClick("button2")
        assertReminder(first)

        val replacement = futureMinute(20)
        chooseDateAndTime(replacement)
        assertReminder(replacement)
        reopen(title, body)
        assertReminder(replacement)
        remindersResult(1, title)
        ui.checkpoint("T31-edited-reminder-saved-and-filtered")
        openResult(title, body)
        clearReminder()
        reopen(title, body)
        ui.scrollTo("Remind Me")
        check(dueLabels().isEmpty()) { "Cleared reminder still has a due label" }
        remindersResult(0)
        ui.checkpoint("T31-clear-removes-reminder-membership")
    }

    @Test fun T32_T33_realDueBoundaryNotificationContentAndTapOpensCorrectItem() {
        val title = "QA $token Delivery"
        val body = "$token actual alarm notification opens this synthetic body"
        addNoteAndOpen(title, body)
        val due = nextMinuteWithUiHeadroom()
        chooseDateAndTime(due)
        assertReminder(due)
        check(System.currentTimeMillis() < due) { "No upcoming interval remained after picker input" }
        remindersResult(1, title)
        ui.checkpoint("T32-upcoming-in-computed-reminders")
        library()
        ui.shell("input keyevent KEYCODE_HOME")
        awaitNotification(title, due)
        openDeliveredNotification(title, body)
        assertReminder(due)
        check(System.currentTimeMillis() >= due) { "Notification delivered before the selected minute" }
        remindersResult(1, title)
        // The product exposes one pending Reminders collection, without separate Due/Upcoming sections.
        ui.checkpoint("T32-due-still-in-computed-reminders-no-separate-classification-ui")
        openResult(title, body)
        clearReminder()
    }

    @Test fun T34_trashAcrossDeadlineSuppressesNotificationAndRestorePreservesPastDueFields() {
        val title = "QA $token Cancelled"
        val body = "$token trash cancellation synthetic body"
        addNoteAndOpen(title, body)
        val due = nextMinuteWithUiHeadroom()
        chooseDateAndTime(due)
        assertReminder(due)
        trashCurrentItem(title)
        ui.checkpoint("T34-cancelled-reminder-item-in-trash")
        ui.shell("input keyevent KEYCODE_HOME")
        // Negative delivery evidence is bounded to this real deadline plus fifteen seconds.
        ui.await("Reach the cancelled reminder deadline without any matching notification", 90000) {
            check(notificationFor(title) == null) { "A trashed synthetic reminder posted a notification" }
            System.currentTimeMillis() >= due + 15000
        }
        expandNotificationShade()
        check(ui.exact("Reminder: $title") == null) { "Trashed reminder remains visible in Android notifications" }
        ui.checkpoint("T34-no-notification-through-deadline-plus-15s")
        ui.shell("cmd statusbar collapse")
        launchProduct()
        restoreNamedTrashItem(title)
        reopen(title, body)
        assertReminder(due)
        remindersResult(1, title)
        ui.checkpoint("T34-past-due-restore-retains-time-and-pending-membership")
        openResult(title, body)
        clearReminder()
    }

    @Test fun T34_restoreBeforeDeadlineActuallyDeliversAndOpensRestoredItem() {
        val title = "QA $token Restored"
        val body = "$token restored reminder real delivery café مرحبا"
        addNoteAndOpen(title, body)
        val due = nextMinuteWithUiHeadroom()
        chooseDateAndTime(due)
        assertReminder(due)
        trashCurrentItem(title)
        check(System.currentTimeMillis() < due - 5000) { "No safe pre-deadline restore interval remains" }
        restoreNamedTrashItem(title)
        reopen(title, body)
        assertReminder(due)
        check(System.currentTimeMillis() < due) { "Restore did not complete before the reminder deadline" }
        ui.checkpoint("T34-restored-before-original-deadline")
        library()
        ui.shell("input keyevent KEYCODE_HOME")
        awaitNotification(title, due)
        openDeliveredNotification(title, body)
        assertReminder(due)
        ui.checkpoint("T34-original-restored-alarm-delivered-and-opened")
        clearReminder()
    }

    private fun launchProduct() {
        check(guarded)
        // A failure checkpoint can leave the notification shade over the next launch.
        if (foreground() == "com.android.systemui") ui.shell("cmd statusbar collapse")
        context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("Official Memora must be foreground") { foreground() == PRODUCT }
    }

    private fun dismissTransientDialogs() {
        repeat(8) {
            if (ui.exact("Edit Item") != null || ui.exact("Add to Library") != null || ui.exact("Reminder type") != null ||
                nativeNode("datePicker", "timePicker", "toggle_mode") != null) {
                ui.back()
                SystemClock.sleep(150)
            } else return
        }
    }

    private fun library() {
        if (ui.exact("Back to Settings") != null) {
            ui.click("Back to Settings")
            ui.waitText("Back to Library")
        }
        if (ui.exact("Back to Library") != null) {
            ui.click("Back to Library")
            ui.waitText("Library")
        }
        repeat(3) {
            if (ui.nodes().any { it.contentDescription?.toString() == "Drag handle" }) {
                ui.back()
                SystemClock.sleep(200)
            }
        }
        check(ui.nodes().none { it.contentDescription?.toString() == "Drag handle" }) { "Item sheet must dismiss" }
        if (ui.exact("Edit Item") != null || ui.exact("Add to Library") != null) {
            ui.back()
            ui.await("Item dialog must close") { ui.exact("Edit Item") == null && ui.exact("Add to Library") == null }
        }
        if (ui.exact("Cancel selection") != null) ui.click("Cancel selection")
        ui.click("Library")
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
    }

    private fun addNoteAndOpen(title: String, body: String) {
        library()
        ui.click("Add")
        ui.waitText("Add to Library")
        ui.edit("Title (Optional)", title)
        ui.edit("Note Content / Text / Grades", body)
        ui.scrollTo("Save Note", 24)
        ui.click("Save Note")
        ui.await("Synthetic note must save") { ui.exact("Add to Library") == null }
        reopen(title, body)
    }

    private fun reopen(title: String, body: String) {
        library()
        ui.edit(SEARCH, token)
        assertResults(1, title)
        openResult(title, body)
    }

    private fun openResult(title: String, body: String) {
        ui.click(title)
        ui.waitText("Edit Item")
        ui.scrollTo(body)
        ui.waitText(body)
    }

    private fun remindersResult(count: Int, title: String? = null) {
        library()
        ui.click("Collections")
        ui.waitText("Add Collection")
        repeat(20) {
            if (ui.exact("Manage Reminders") != null) return@repeat
            if (ui.scroll(forward = false)) SystemClock.sleep(100)
        }
        ui.waitText("Manage Reminders")
        ui.click("Manage Reminders")
        ui.click("Open")
        ui.waitText("Collection: Reminders")
        ui.edit(SEARCH, token)
        assertResults(count, title)
    }

    private fun assertResults(count: Int, title: String?) {
        ui.await("Expected $count saved synthetic reminder results", 45000) {
            ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.matches(Regex("$count items • (Saved|Source) Date")) == true } &&
                ui.exact("Searching your library…") == null && (title == null || ui.exact(title) != null)
        }
    }

    private fun futureMinute(minutes: Int): Long = Calendar.getInstance().apply {
        add(Calendar.MINUTE, minutes)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun nextMinuteWithUiHeadroom(): Long {
        // Waiting near a minute boundary avoids changing emulator time or rounding a picker into the past.
        if (Calendar.getInstance().get(Calendar.SECOND) >= 35) {
            ui.await("Align a real next-minute reminder with enough UI headroom", 28000) {
                Calendar.getInstance().get(Calendar.SECOND) < 5
            }
        }
        return futureMinute(1).also {
            check(it - System.currentTimeMillis() >= 20000) { "Insufficient time for a real picker flow" }
        }
    }

    private fun chooseDateAndTime(due: Long) {
        val target = Calendar.getInstance().apply { timeInMillis = due }
        val now = Calendar.getInstance()
        check(target.get(Calendar.YEAR) == now.get(Calendar.YEAR) && target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)) {
            "This bounded picker flow requires a same-day future minute; rerun away from midnight"
        }
        val action = reminderAction()
        ui.click(action)
        ui.click("Date + time")
        awaitDatePicker()
        // The production DatePickerDialog initially selects today; the guard above keeps this exact.
        nativeClick("button1")
        ui.await("Native Android time picker must open") { nativeNode("timePicker", "toggle_mode", "input_hour") != null }
        if (nativeNode("input_hour") == null) nativeClick("toggle_mode")
        ui.await("Native clock text fields must be available") { nativeNode("input_hour")?.isEditable == true && nativeNode("input_minute")?.isEditable == true }
        setNativeText("input_hour", (target.get(Calendar.HOUR).takeIf { it != 0 } ?: 12).toString())
        setNativeText("input_minute", "%02d".format(Locale.US, target.get(Calendar.MINUTE)))
        setNativePeriod(if (target.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
        check(due > System.currentTimeMillis()) { "Real selected minute passed before confirmation" }
        ui.checkpoint("native-time-picker-before-confirm")
        nativeClick("button1")
        ui.await("Native picker/permission must return to the item", 20000) {
            if (foreground() in PERMISSION_PACKAGES && ui.exact("Allow") != null) ui.click("Allow")
            foreground() == PRODUCT && nativeNode("timePicker", "input_hour") == null
        }
        assertReminder(due)
        check(context.packageManager.checkPermission(Manifest.permission.POST_NOTIFICATIONS, PRODUCT) == PackageManager.PERMISSION_GRANTED) {
            "OS notification permission was not granted; delivery cannot be certified"
        }
    }

    private fun awaitDatePicker() = ui.await("Native Android date picker must open") {
        nativeNode("datePicker", "date_picker_header_date", "date_picker_header_year", "month_view") != null && nativeNode("button1") != null
    }

    private fun nativeNode(vararg ids: String): AccessibilityNodeInfo? = ui.nodes().firstOrNull {
        it.isVisibleToUser && ids.any { id -> it.viewIdResourceName == "android:id/$id" }
    }

    private fun nativeClick(id: String) {
        ui.await("Native Android control missing: $id") { nativeNode(id) != null }
        ui.clickNode(checkNotNull(nativeNode(id)))
    }

    private fun setNativeText(id: String, value: String) {
        val field = checkNotNull(nativeNode(id)) { "Missing native time field $id" }
        check(field.isEditable && field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        })) { "Native time field rejected $id" }
        ui.await("Native time field value not accepted: $id") { nativeNode(id)?.text?.toString()?.toIntOrNull() == value.toInt() }
    }

    private fun setNativePeriod(period: String) {
        fun spinner() = nativeNode("input_am_pm_spinner", "am_pm_spinner")
        fun selected(): String? = spinner()?.let(::descendants)?.flatMap { listOf(it.text?.toString(), it.contentDescription?.toString()) }
            ?.filter { it in setOf("AM", "PM") }?.distinct()?.singleOrNull()
        check(spinner() != null && selected() != null) { "Native 12-hour period selector is not exposed unambiguously" }
        if (selected() != period) {
            ui.clickNode(checkNotNull(spinner()))
            ui.click(period)
        }
        ui.await("Native AM/PM selection did not update") { selected() == period }
    }

    private fun dueLabel(due: Long) = "Due: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(due))}"
    private fun dueLabels(): List<String> = ui.nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString()?.takeIf { text -> text.startsWith("Due: ") } }

    private fun reminderAction(): String {
        // Picker/permission return may retain a scroll position below the reminder fields.
        for (forward in listOf(false, true)) {
            for (attempt in 0 until 24) {
                if (ui.exact("Edit Reminder") != null) return "Edit Reminder"
                if (ui.exact("Remind Me") != null) return "Remind Me"
                if (!ui.scroll(forward)) break
                SystemClock.sleep(100)
            }
        }
        throw AssertionError("Open item reminder action was not reachable")
    }

    private fun assertReminder(due: Long) {
        check(reminderAction() == "Edit Reminder") { "Saved reminder no longer exposes Edit Reminder" }
        ui.await("Saved due timestamp must match the actual picker minute") {
            val labels = dueLabels()
            if (labels.isNotEmpty()) labels.any { it == dueLabel(due) || it == "${dueLabel(due)} (approximate)" }
            else { ui.scroll(); false }
        }
    }

    private fun clearReminder() {
        check(reminderAction() == "Edit Reminder")
        ui.scrollTo("Clear")
        ui.click("Clear")
        ui.await("Clearing reminder must restore Remind Me") { ui.exact("Remind Me") != null && dueLabels().isEmpty() }
    }

    private fun notificationFor(title: String): Posted? = synchronized(posted) { posted.firstOrNull { it.title == "Reminder: $title" } }

    private fun awaitNotification(title: String, due: Long) {
        val timeout = (due - System.currentTimeMillis()).coerceAtLeast(0) + 90000
        // Observe the actual OS surface as well: accessibility callbacks may omit the Notification payload.
        expandNotificationShade()
        ui.await("Actual Android reminder must appear by the selected deadline plus 90 seconds", timeout) {
            val node = ui.exact("Reminder: $title")
            if (node != null) {
                var ancestor: AccessibilityNodeInfo? = node
                var matchingBody = false
                repeat(6) {
                    val current = ancestor
                    if (current != null && !matchingBody) {
                        matchingBody = descendants(current).any {
                            it.isVisibleToUser && it.text?.toString() == "Tap to view this saved item in Memora"
                        }
                    }
                    ancestor = current?.parent
                }
                check(matchingBody) { "The actual notification must contain the expected body" }
                check(System.currentTimeMillis() >= due) { "Native reminder appeared before the selected due time" }
                true
            } else false
        }
        notificationFor(title)?.let {
            check(it.at >= due) { "A notification event was posted before the selected due time" }
            check(it.text == "Tap to view this saved item in Memora") { "Actual notification content differed" }
        }
        Log.i("MemoraQA", "${testName.methodName}: native Android notification observed; latenessMs=${System.currentTimeMillis() - due}")
    }

    private fun expandNotificationShade() {
        check(guarded)
        ui.shell("cmd statusbar expand-notifications")
        ui.await("Android notification shade must be visible") { foreground() == "com.android.systemui" }
    }

    private fun openDeliveredNotification(title: String, body: String) {
        expandNotificationShade()
        ui.scrollTo("Reminder: $title", 16)
        ui.waitText("Tap to view this saved item in Memora")
        ui.checkpoint("T33-system-notification-title-and-content")
        ui.click("Reminder: $title")
        ui.await("Tapping the actual notification must open Memora") { foreground() == PRODUCT }
        ui.waitText("Edit Item")
        ui.scrollTo(body)
        ui.waitText(body)
        ui.waitText(title)
        ui.checkpoint("T33-notification-opened-exact-synthetic-item")
    }

    private fun trashCurrentItem(title: String) {
        // The unique synthetic item is already open; no bulk Trash action is used.
        var attempts = 20
        while (ui.exact("Trash") == null && attempts-- > 0 && ui.scroll(forward = false)) SystemClock.sleep(100)
        ui.click("Trash")
        ui.await("Synthetic item detail must close after Trash") { ui.exact("Edit Item") == null }
        openTrash()
        ui.scrollTo(title, 24)
        ui.waitText(title)
    }

    private fun openTrash() {
        library()
        ui.click("Settings")
        ui.scrollTo("View Trash", 24)
        ui.click("View Trash")
        ui.waitText("Back to Settings")
    }

    private fun restoreNamedTrashItem(title: String) {
        if (ui.exact("Back to Settings") == null) openTrash()
        ui.scrollTo(title, 24)
        var node = ui.exact(title)
        repeat(10) {
            val current = node ?: throw AssertionError("No scoped synthetic Trash row")
            val actions = descendants(current).filter { it.isVisibleToUser && it.contentDescription?.toString() == "Restore" }
            if (actions.size == 1) {
                ui.clickNode(actions.single())
                ui.await("Restored synthetic record must leave Trash") { ui.exact(title) == null }
                return
            }
            check(actions.size < 2) { "Ambiguous Restore action; refusing unrelated records" }
            node = current.parent
        }
        throw AssertionError("No Restore action for the unique synthetic title")
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { result += descendants(it) }
        return result
    }

    private fun foreground() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
        private val PERMISSION_PACKAGES = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    }
}
