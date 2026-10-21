package com.example.qa

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** Native accessibility tree: Compose test-tag queries cannot certify the label seen by services. */
@RunWith(AndroidJUnit4::class)
class LibraryAccessibilityTest {
    @Test fun libraryAddHasAnAccessibleNameAndOpensTheAddDialog() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val previous = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        val hadPaused = settings.contains("ai_paused")
        val paused = settings.getBoolean("ai_paused", false)
        check(settings.edit().putBoolean("ai_paused", true).commit())
        fun named(label: String): AccessibilityNodeInfo? {
            if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
            fun visit(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
                if (depth > 50) return null
                if (node.isVisibleToUser && (node.text?.toString() == label || node.contentDescription?.toString() == label)) return node
                for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1)?.let { result -> return result } }
                return null
            }
            return automation.rootInActiveWindow?.let { visit(it, 0) }
        }
        fun awaitName(label: String): AccessibilityNodeInfo {
            val deadline = SystemClock.uptimeMillis() + 10_000
            var found: AccessibilityNodeInfo? = null
            while (found == null && SystemClock.uptimeMillis() < deadline) {
                found = named(label)
                if (found == null) SystemClock.sleep(100)
            }
            assertNotNull("Native accessibility tree must expose $label", found)
            return checkNotNull(found)
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                awaitName("Library")
                var action: AccessibilityNodeInfo? = awaitName("Add")
                while (action != null && !action.isClickable) action = action.parent
                check(action?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) {
                    "Named Add control must have a native click action"
                }
                awaitName("Add to Library")
            }
        } finally {
            automation.serviceInfo = previous
            val editor = settings.edit()
            if (hadPaused) editor.putBoolean("ai_paused", paused) else editor.remove("ai_paused")
            check(editor.commit())
        }
    }
}
