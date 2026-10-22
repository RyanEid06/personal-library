package com.memora.qa.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
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

/** Bounded release-APK environment checks; screenshots do not certify flashes, contrast or hardware. */
@RunWith(AndroidJUnit4::class)
class OfficialEnvironmentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private lateinit var marker: String
    private lateinit var evidenceDir: File
    private var guarded = false
    @get:Rule val testName = TestName()
    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) {
                persistOutcome("FAIL\n${error.stackTraceToString()}")
                runCatching { ui.checkpoint("FAILED") }
            }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) {
            if (guarded) persistOutcome("PASS\n")
            Log.i("MemoraQA", "${description.methodName}: PASS")
        }
    }

    @Before fun guardOfficialSyntheticEmulatorAndPauseAi() {
        marker = "qe${SystemClock.elapsedRealtime()}"
        evidenceDir = File(context.filesDir, "qa-evidence/${testName.methodName}-$marker")
        ui = ReleaseUi(instrumentation.uiAutomation, evidenceDir)
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the disposable emulator is allowed" }
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        requireOfficialOrPinnedCandidate(context, "Environment")
        guarded = true
        persistOutcome("STARTED\n")
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        restartProduct()
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
    }

    @Test fun T43_lightDarkPreferenceSurvivesColdLaunchWithScreenshots() {
        appearance()
        val original = listOf("Light", "Dark", "System").single { themeSelected(it) }
        restoring(listOf({ appearance(); ui.click(original); ui.await("Original theme must restore") { themeSelected(original) } })) {
            listOf("Light", "Dark").forEach { mode ->
                appearance()
                ui.click(mode)
                ui.await("Theme choice must be selected: $mode") { themeSelected(mode) }
                ui.checkpoint("T43-$mode-selected")
                restartProduct()
                ui.waitText("Settings")
                ui.checkpoint("T43-$mode-cold-library-flash-unclaimed")
                appearance()
                check(themeSelected(mode)) { "Saved theme changed across a force-stop launch: $mode" }
                ui.checkpoint("T43-$mode-persisted-selection")
            }
        }
    }

    @Test fun T45_largeFontSmallViewportRotationAndForcedRtlControlsRemainAccessible() {
        val title = "QA $marker Display"
        val body = "$marker display continuity café مرحبا 🚀"
        addNote(title, body)
        val font = setting("system", "font_scale")
        val autoRotate = setting("system", "accelerometer_rotation")
        val rotation = setting("system", "user_rotation")
        val rtlSetting = setting("global", "debug.force_rtl")
        val developerOptions = setting("global", "development_settings_enabled")
        val adbEnabled = setting("global", "adb_enabled")
        check(adbEnabled == "1") { "QA emulator debugging must already be enabled" }
        val originalRtlLayout = ui.shell("am get-config").contains("-ldrtl-")
        val rtlProperty = ui.shell("getprop debug.force_rtl").trim().also {
            check(it in setOf("0", "1", "false", "true")) { "Unexpected RTL property value" }
        }
        val overrideSize = Regex("Override size: (\\d+x\\d+)").find(ui.shell("wm size"))?.groupValues?.get(1)
        restoring(listOf(
            { restoreSetting("system", "font_scale", font) },
            { restoreSetting("system", "user_rotation", rotation) },
            { restoreSetting("system", "accelerometer_rotation", autoRotate) },
            {
                ui.shell("wm size ${overrideSize ?: "reset"}")
                val restored = Regex("Override size: (\\d+x\\d+)").find(ui.shell("wm size"))?.groupValues?.get(1)
                check(restored == overrideSize) { "Original viewport override must restore" }
            },
            {
                setNativeForcedRtl(originalRtlLayout)
                ui.await("Original actual layout direction must restore") {
                    ui.shell("am get-config").contains("-ldrtl-") == originalRtlLayout
                }
            },
            {
                restartProduct()
                // Stop native Settings observers before restoring a disabled developer-options baseline.
                ui.shell("am force-stop com.android.settings")
            },
            { restoreSetting("global", "development_settings_enabled", developerOptions) },
            { restoreSetting("global", "debug.force_rtl", rtlSetting) },
            { ui.shell("setprop debug.force_rtl $rtlProperty"); check(ui.shell("getprop debug.force_rtl").trim() == rtlProperty) },
            { restoreSetting("global", "adb_enabled", adbEnabled) },
            {
                restartProduct()
                ui.await("Actual layout must remain restored after all original settings") {
                    ui.shell("am get-config").contains("-ldrtl-") == originalRtlLayout
                }
            }
        )) {
            putSetting("system", "accelerometer_rotation", "0")
            putSetting("system", "user_rotation", "0")
            putSetting("system", "font_scale", "1.5")
            ui.shell("wm size 540x960")
            check(ui.shell("wm size").contains("Override size: 540x960")) { "Small viewport override was not applied" }
            restartProduct()
            searchAndOpen(title, body)
            ui.checkpoint("T45-font150-small-viewport-content")
            exerciseNavigationControls()

            putSetting("system", "user_rotation", "1")
            awaitOrientation(landscape = true)
            library()
            searchAndOpen(title, body)
            ui.checkpoint("T45-landscape-content")
            exerciseNavigationControls()

            putSetting("system", "user_rotation", "0")
            awaitOrientation(landscape = false)
            setNativeForcedRtl(true)
            ui.await("Native Force RTL must update the actual system configuration") {
                ui.shell("am get-config").contains("-ldrtl-")
            }
            restartProduct()
            // Require a visible mirrored toolbar, rather than treating a shell setting as RTL UI proof.
            val settings = Rect().also(checkNotNull(ui.exact("Settings"))::getBoundsInScreen)
            val width = screenshotSize().first
            check(settings.centerX() < width / 2) { "Forced RTL did not mirror the actual toolbar; no RTL pass claimed" }
            searchAndOpen(title, body)
            ui.checkpoint("T45-forced-rtl-content-localization-unclaimed")
            exerciseNavigationControls()
        }
    }

    @Test fun T46_offlineCreateOpenSearchCollectionAndReconnectRetainOneRecord() {
        val wifi = setting("global", "wifi_on")
        val data = setting("global", "mobile_data")
        check(wifi in setOf("0", "1") && data in setOf("0", "1")) { "Cannot restore unknown radio states" }
        val title = "QA $marker Offline"
        val body = "$marker offline searchable café مرحبا 🚀"
        val collection = "QA $marker Offline Collection"
        restoring(listOf(
            { ui.shell("svc wifi ${if (wifi == "1") "enable" else "disable"}"); ui.await("Wi-Fi state must restore") { setting("global", "wifi_on") == wifi } },
            { ui.shell("svc data ${if (data == "1") "enable" else "disable"}"); ui.await("Mobile data state must restore") { setting("global", "mobile_data") == data } }
        )) {
            ui.shell("svc wifi disable")
            ui.shell("svc data disable")
            ui.await("Both emulator radios must be disabled") { setting("global", "wifi_on") == "0" && setting("global", "mobile_data") == "0" }
            ui.await("Emulator must have no active default network") { ui.shell("dumpsys connectivity").contains("Active default network: none") }
            createCollection(collection)
            addNote(title, body)
            searchAndOpen(title, body)
            setMembership(collection)
            ui.checkpoint("T46-offline-created-opened-and-organized")
            restartProduct()
            collectionResult(collection, title, body)
            ui.checkpoint("T46-offline-restart-search-and-membership")
        }
        // Reconnection means restoration of the original emulator radios; AI remains paused.
        check(wifi == "1" || data == "1") { "No original enabled radio: reconnect subcase cannot pass" }
        ui.await("An active default network must return after restoring radios", 30000) {
            Regex("Active default network: \\d+").containsMatchIn(ui.shell("dumpsys connectivity"))
        }
        restartProduct()
        collectionResult(collection, title, body)
        library()
        searchAndOpen(title, body)
        ui.checkpoint("T46-reconnected-content-membership-no-duplicate")
    }

    @Test fun T48_boundedRapidNavigationBackgroundForegroundAndProcessRestarts() {
        val title = "QA $marker Navigation"
        val body = "$marker navigation persistence synthetic record"
        addNote(title, body)
        repeat(6) { index ->
            val pid = ui.shell("pidof $PRODUCT").trim()
            check(pid.matches(Regex("[0-9]+"))) { "Memora process must be alive before navigation" }
            exerciseNavigationControls()
            ui.shell("input keyevent KEYCODE_HOME")
            ui.await("Home must finish leaving Memora before relaunch") {
                instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()?.let { it != PRODUCT } == true
            }
            launchProduct()
            library()
            searchAndOpen(title, body)
            check(ui.shell("pidof $PRODUCT").trim() == pid) { "Unexpected product process restart during navigation" }
            if (index % 2 == 1) {
                restartProduct()
                searchAndOpen(title, body)
            }
        }
        ui.checkpoint("T48-bounded-navigation-restart-content-intact")
    }

    private fun launchProduct() {
        check(guarded)
        context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("Memora must be foreground") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == PRODUCT
        }
    }

    private fun setNativeForcedRtl(enabled: Boolean) {
        check(guarded)
        putSetting("global", "development_settings_enabled", "1")
        ui.shell("am start -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS")
        ui.await("Native Developer Options must be foreground") {
            instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == "com.android.settings"
        }
        ui.waitText("Developer options")
        ui.scrollTo("Force RTL layout direction", 32)
        // A shell property write alone leaves the running system configuration unchanged on this AVD.
        ui.setLabelledSwitch("Force RTL layout direction", !enabled)
        ui.setLabelledSwitch("Force RTL layout direction", enabled)
        ui.await("Native Force RTL switch must apply") {
            setting("global", "debug.force_rtl") == if (enabled) "1" else "0"
        }
    }

    private fun persistOutcome(result: String) {
        evidenceDir.mkdirs()
        File(evidenceDir, "outcome.txt").writeText(result)
    }

    private fun restartProduct() {
        check(guarded)
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
    }

    private fun library() {
        if (ui.exact("Edit Item") != null || ui.exact("Add to Library") != null) {
            ui.back()
            ui.await("Item dialog must dismiss") { ui.exact("Edit Item") == null && ui.exact("Add to Library") == null }
        }
        if (ui.exact("Appearance") != null || ui.exact("Local Storage Breakdown") != null) ui.back()
        ui.click("Library")
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
    }

    private fun appearance() {
        library()
        ui.click("Settings")
        repeat(14) {
            if (ui.exact("Light") != null) return
            if (!ui.scroll(forward = false)) return@repeat
            SystemClock.sleep(150)
        }
        ui.scrollTo("Light")
    }

    private fun themeSelected(label: String): Boolean {
        var node = ui.exact(label)
        repeat(5) {
            val current = node ?: return false
            if (current.isSelected || current.isChecked) return true
            node = current.parent
        }
        return false
    }

    private fun exerciseNavigationControls() {
        library()
        ui.click("Collections")
        ui.waitText("Add Collection")
        ui.click("Library")
        ui.waitText("Settings")
        appearance()
        ui.waitText("Appearance")
        ui.back()
        library()
    }

    private fun addNote(title: String, body: String) {
        library()
        ui.click("Add") // A missing accessible Add is a real test failure; no fixed-coordinate fallback.
        ui.waitText("Add to Library")
        ui.edit("Title (Optional)", title)
        ui.edit("Note Content / Text / Grades", body)
        ui.scrollTo("Save Note")
        ui.click("Save Note")
        ui.await("Add Note dialog must dismiss") { ui.exact("Add to Library") == null }
    }

    private fun searchAndOpen(title: String, body: String) {
        library()
        ui.edit(SEARCH, marker)
        ui.await("Current synthetic search must finish before revealing its result", 45000) {
            ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.matches(Regex("1 items • (Saved|Source) Date")) == true } &&
                ui.exact("Searching your library…") == null
        }
        ui.scrollTo(title)
        var stableSince = 0L
        ui.await("Exactly one current synthetic search result must survive", 45000) {
            val ready = ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.matches(Regex("1 items • (Saved|Source) Date")) == true } &&
                ui.exact(title) != null && ui.exact("Searching your library…") == null
            if (!ready) stableSince = 0L else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            ready && SystemClock.uptimeMillis() - stableSince >= 450
        }
        ui.click(title)
        ui.waitText("Edit Item")
        ui.scrollTo(body)
        ui.waitText(body)
    }

    private fun createCollection(name: String) {
        library()
        ui.click("Collections")
        ui.click("Add Collection")
        ui.waitText("New Collection")
        ui.edit("Collection Name", name)
        ui.click("Create")
        ui.await("Collection dialog must dismiss") { ui.exact("New Collection") == null }
        ui.scrollTo("Manage $name", 24)
    }

    private fun collectionResult(name: String, title: String, body: String) {
        library()
        ui.click("Collections")
        ui.scrollTo("Manage $name", 24)
        ui.click("Manage $name")
        ui.click("Open")
        ui.waitText("Collection: $name")
        // Keep the collection filter while asserting the unique record and its saved body.
        ui.edit(SEARCH, marker)
        var stableSince = 0L
        ui.await("Offline collection must contain exactly the saved note", 45000) {
            val ready = ui.exact(title) != null && ui.exact("Searching your library…") == null &&
                ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.matches(Regex("1 items • (Saved|Source) Date")) == true }
            if (!ready) stableSince = 0L else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            ready && SystemClock.uptimeMillis() - stableSince >= 450
        }
        ui.click(title)
        ui.waitText("Edit Item")
        ui.scrollTo(body)
        ui.waitText(body)
    }

    private fun setMembership(name: String) {
        ui.scrollTo(name, 24)
        fun checkbox(): AccessibilityNodeInfo? {
            val label = ui.exact(name) ?: return null
            val labelBounds = Rect().also(label::getBoundsInScreen)
            val aligned = ui.nodes().filter { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.isVisibleToUser && node.isCheckable && bounds.right <= labelBounds.left &&
                    bounds.top < labelBounds.bottom && bounds.bottom > labelBounds.top
            }.singleOrNull()
            if (aligned != null) return aligned
            fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> = listOf(node) +
                (0 until node.childCount).flatMap { index -> node.getChild(index)?.let(::descendants).orEmpty() }
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
        val target = checkNotNull(checkbox()) { "No unambiguous synthetic collection checkbox" }
        if (!target.isChecked) ui.clickNode(target)
        ui.await("Synthetic collection membership must update") { checkbox()?.isChecked == true }
    }

    private fun setting(namespace: String, key: String): String = ui.shell("settings get $namespace $key").trim()

    private fun putSetting(namespace: String, key: String, value: String) {
        check(guarded)
        check(value.matches(Regex("[0-9.]+")))
        ui.shell("settings put $namespace $key $value")
        ui.await("Setting $key must apply") { setting(namespace, key) == value }
    }

    private fun restoreSetting(namespace: String, key: String, value: String) {
        check(guarded)
        if (value == "null") ui.shell("settings delete $namespace $key") else putSetting(namespace, key, value)
        ui.await("Original setting $key must restore") { setting(namespace, key) == value }
    }

    private fun screenshotSize(): Pair<Int, Int> {
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Screenshot unavailable" }
        return try { bitmap.width to bitmap.height } finally { bitmap.recycle() }
    }

    private fun awaitOrientation(landscape: Boolean) = ui.await("Requested display orientation must apply") {
        val (width, height) = screenshotSize()
        (width > height) == landscape
    }

    private fun restoring(actions: List<() -> Unit>, block: () -> Unit) {
        var failure: Throwable? = null
        try { block() } catch (error: Throwable) {
            failure = error
            runCatching { ui.checkpoint("FAILED-before-environment-restore") }
            throw error
        }
        finally {
            // Attempt every restoration even when the test or an earlier restoration fails.
            var restoration: Throwable? = null
            actions.forEach { action -> runCatching(action).exceptionOrNull()?.let { error ->
                if (restoration == null) restoration = error else restoration!!.addSuppressed(error)
            } }
            restoration?.let { if (failure != null) failure!!.addSuppressed(it) else throw it }
        }
    }

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
    }
}
