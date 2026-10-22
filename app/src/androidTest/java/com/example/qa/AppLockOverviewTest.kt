package com.example.qa

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import com.example.data.ItemEntity
import com.example.data.LibraryRepository
import com.example.security.AppLockManager
import com.example.ui.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Debug state fixture tests OS snapshots; actual native authentication is a separate guarded flow. */
@RunWith(AndroidJUnit4::class)
class AppLockOverviewTest {
    @Test fun enabledAppLockHidesAuthenticatedLibraryFromNativeOverview() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue("Owned synthetic Overview fixture requires explicit opt-in",
            InstrumentationRegistry.getArguments().getString("allowSyntheticOverviewFixture") == "true")
        val automation = instrumentation.uiAutomation
        fun shell(command: String): String = automation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().use { reader -> reader.readText() }
        }
        assumeTrue("Debug API33+ state-fixture check; release/older-platform acceptance is separate",
            contextIsDebuggable(instrumentation.targetContext) && Build.VERSION.SDK_INT >= 33)
        check(shell("getprop ro.kernel.qemu").trim() == "1")
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name").map { shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" })
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(!AppLockManager(context).isLockEnabled()) { "Synthetic baseline must have App Lock off" }
        val lockPreferences = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
        val hadLockPreference = lockPreferences.contains("lock_enabled")
        val preferences = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val hadPause = preferences.contains("ai_paused")
        val paused = preferences.getBoolean("ai_paused", false)
        check(preferences.edit().putBoolean("ai_paused", true).commit())
        val id = UUID.randomUUID().toString()
        val title = "QA010 protected overview $id"
        val directory = File(context.filesDir, "qa-evidence/QA010-overview-${SystemClock.elapsedRealtime()}").apply { mkdirs() }
        val repository = LibraryRepository(context)
        val previous = automation.serviceInfo
        val previousFlags = previous.flags
        automation.serviceInfo = previous.apply { flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS }
        fun markerRendered(): Boolean {
            automation.clearCache()
            @Suppress("DEPRECATION")
            fun visit(node: AccessibilityNodeInfo, depth: Int): Boolean {
                try {
                    if (depth > 50) return false
                    if (node.isVisibleToUser && node.packageName?.toString() == context.packageName && node.text?.toString() == title) return true
                    for (index in 0 until node.childCount) node.getChild(index)?.let { if (visit(it, depth + 1)) return true }
                    return false
                } finally { node.recycle() }
            }
            return automation.rootInActiveWindow?.let { visit(it, 0) } == true
        }
        fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (condition()) return
                SystemClock.sleep(100)
            }
            throw AssertionError(message)
        }
        fun capture(name: String) {
            val bitmap = checkNotNull(automation.takeScreenshot())
            try { File(directory, "$name.png").outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
        try {
            runBlocking { repository.dao.insertItem(ItemEntity(id, title, "Artificial QA010 privacy fixture", "TEXT", createdAt = System.currentTimeMillis())) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                    vm.setSearchQuery(id)
                    // Exercise the persisted enabled policy independently of authentication state.
                    vm.appLockManager.setLockEnabled(true)
                    vm.appLockManager.unlock()
                }
                await("Synthetic item must be rendered in the authenticated Library before Overview") {
                    markerRendered()
                }
                capture("authenticated-library-before-overview")
                shell("input keyevent KEYCODE_APP_SWITCH")
                val launchers = setOf("com.android.launcher3", "com.google.android.apps.nexuslauncher")
                await("Native Overview launcher required") { automation.rootInActiveWindow?.packageName?.toString() in launchers }
                SystemClock.sleep(1000)
                check(automation.rootInActiveWindow?.packageName?.toString() in launchers)
                capture("native-overview-await-visual-review")
                File(directory, "coverage.txt").writeText("Synthetic title=$title\nApp Lock enabled and session explicitly unlocked by test fixture; no credential setup or authentication claim.\nActual Overview capture requires independent visual review; test completion alone is not a thumbnail privacy PASS.\n")
            }
        } finally {
            AppLockManager(context).setLockEnabled(false)
            if (!hadLockPreference) check(lockPreferences.edit().remove("lock_enabled").commit())
            runBlocking { repository.deletePermanently(id) }
            val edit = preferences.edit()
            if (hadPause) edit.putBoolean("ai_paused", paused) else edit.remove("ai_paused")
            check(edit.commit())
            previous.flags = previousFlags
            automation.serviceInfo = previous
        }
    }

    private fun contextIsDebuggable(context: Context) =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
