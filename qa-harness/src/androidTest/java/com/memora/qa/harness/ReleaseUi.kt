package com.memora.qa.harness

import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

/** Bounded accessibility operations; touch fallback always derives coordinates from the live tree. */
class ReleaseUi(private val automation: UiAutomation, private val evidence: File) {
    fun nodes(): List<AccessibilityNodeInfo> {
        if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
        val result = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            if (depth > 50 || result.size >= 2000) return
            result += node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it, depth + 1) }
        }
        automation.rootInActiveWindow?.let { visit(it, 0) }
        return result
    }

    fun exact(label: String): AccessibilityNodeInfo? = nodes().filter {
        it.isVisibleToUser && (it.text?.toString() == label || it.contentDescription?.toString() == label)
    }.minByOrNull { if (it.isEditable) 2 else if (it.text?.toString() == label) 0 else 1 }

    fun await(message: String, timeout: Long = 20000, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(150)
        }
        runCatching { checkpoint("timeout") }
        throw AssertionError(message)
    }

    fun waitText(label: String, timeout: Long = 20000) = await("UI missing exact text/description: $label", timeout) { exact(label) != null }

    fun click(label: String) {
        if (label == "Add") {
            var assisted = false
            await("Library Add action must be available") {
                exact(label) != null || clickUnlabelledLibraryAdd().also { assisted = it }
            }
            if (assisted) return
        }
        await("UI missing exact text/description: $label") { clickIfPresent(label) }
    }

    fun clickIfPresent(label: String): Boolean {
        val target = exact(label) ?: return false
        clickNode(target)
        return true
    }

    private fun clickUnlabelledLibraryAdd(): Boolean {
        if (automation.rootInActiveWindow?.packageName?.toString() != "com.personallibrary.app") return false
        val navigation = exact("Library") ?: return false
        val navBounds = Rect().also(navigation::getBoundsInScreen)
        val rootBounds = Rect().also { automation.rootInActiveWindow?.getBoundsInScreen(it) }
        val floating = nodes().filter { node ->
            val bounds = Rect().also(node::getBoundsInScreen)
            node.isVisibleToUser && node.isEnabled && node.className?.toString() == "android.widget.Button" &&
                node.text.isNullOrEmpty() && node.contentDescription.isNullOrEmpty() &&
                bounds.width() > bounds.height() * 1.4 && bounds.width() > rootBounds.width() * 0.15 &&
                bounds.right > rootBounds.right * 0.8 &&
                bounds.top > navBounds.top * 0.65 && bounds.bottom < navBounds.top
        }.singleOrNull() ?: return false
        // Continue assisted flows using a unique live-tree target; this does not pass label accessibility.
        android.util.Log.w("MemoraQA", "Unlabelled Library Add FAB: assisted live-bounds selection; accessibility finding remains")
        checkpoint("unlabelled-library-add")
        clickNode(floating)
        return true
    }

    fun clickNode(node: AccessibilityNodeInfo) {
        var candidate: AccessibilityNodeInfo? = node
        repeat(12) {
            val current = candidate ?: return@repeat
            if (current.isEnabled && current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            candidate = current.parent
        }
        val bounds = Rect().also(node::getBoundsInScreen)
        check(!bounds.isEmpty && node.isVisibleToUser) { "Target has no visible bounds" }
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { i, action ->
            MotionEvent.obtain(now, now + i * 50L, action, bounds.exactCenterX(), bounds.exactCenterY(), 0).useEvent {
                it.source = InputDevice.SOURCE_TOUCHSCREEN
                check(automation.injectInputEvent(it, true))
            }
        }
    }

    fun edit(label: String, value: String) {
        if (label == "Search notes, photos, links...") {
            // Library search is a scrollable header on short displays; reveal it without changing its query.
            for (attempt in 0 until 24) {
                if (librarySearchVisible(label)) break
                if (!scroll(forward = false)) break
                SystemClock.sleep(150)
            }
            revealLibrarySearchWithLiveTouch(label)
        }
        await("Editable field missing: $label") {
            val labelled = nodes().firstOrNull { it.text?.toString() == label || it.hintText?.toString() == label || it.contentDescription?.toString() == label }
            var ancestor = labelled
            var field: AccessibilityNodeInfo? = null
            repeat(5) {
                val current = ancestor
                if (current != null && field == null) field = editableWithin(current)
                ancestor = current?.parent
            }
            if (field == null) field = nodes().filter { it.isEditable && it.isVisibleToUser }.singleOrNull()
            field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
            }) == true
        }
        await("Field value was not accepted") { nodes().any { it.isEditable && it.text?.toString() == value } }
    }

    private fun revealLibrarySearchWithLiveTouch(label: String) {
        fun visibleSearch() = librarySearchVisible(label)
        if (visibleSearch()) return
        // Rotation can leave the lazy grid between its headers and cards. Use a
        // normal gesture only inside the live Library content, never a dialog.
        repeat(12) { attempt ->
            if (visibleSearch()) {
                checkpoint("library-search-recovered-after-$attempt-live-scrolls")
                return
            }
            val root = automation.rootInActiveWindow ?: return
            if (root.packageName?.toString() != "com.personallibrary.app" ||
                nodes().any { it.contentDescription?.toString() == "Drag handle" }) return
            val screen = Rect().also(root::getBoundsInScreen)
            fun bar(labelled: AccessibilityNodeInfo?): Rect? {
                var ancestor = labelled
                repeat(8) {
                    val current = ancestor ?: return null
                    val bounds = Rect().also(current::getBoundsInScreen)
                    if (bounds.width() >= screen.width() * 0.8 && bounds.height() in 1..(screen.height() / 2)) return bounds
                    ancestor = current.parent
                }
                return null
            }
            val toolbar = bar(exact("Settings")) ?: return
            val navigation = bar(exact("Library")) ?: return
            val height = navigation.top - toolbar.bottom
            if (height < 48) return
            evidence.mkdirs()
            File(evidence, "library-search-scroll-nodes-$attempt.txt").writeText(nodes().filter { it.isVisibleToUser && it.isScrollable }.joinToString("\n") {
                "class=${it.className} id=${it.viewIdResourceName} bounds=${Rect().also(it::getBoundsInScreen)} actions=${it.actionList.map { action -> action.id }}"
            })
            val x = screen.left + screen.width() / 3
            shell("input touchscreen swipe $x ${toolbar.bottom + height / 4} $x ${toolbar.bottom + height * 3 / 4} 350")
            SystemClock.sleep(200)
        }
        if (visibleSearch()) checkpoint("library-search-recovered-after-12-live-scrolls")
    }

    private fun librarySearchVisible(label: String): Boolean {
        val visible = nodes().filter { it.isVisibleToUser }
        val fields = visible.filter { it.isEditable }
        if (fields.any { it.hintText?.toString() == label || it.text?.toString() == label }) return true
        // Compose can omit the hint once a query is nonempty. The existing edit
        // fallback already uses the unique editor; don't scroll a visible one away.
        return automation.rootInActiveWindow?.packageName?.toString() == "com.personallibrary.app" &&
            fields.size == 1 && visible.any { it.contentDescription?.toString() == "Settings" } &&
            visible.any { it.text?.toString() == "Library" } &&
            visible.none { it.contentDescription?.toString() == "Drag handle" }
    }

    private fun editableWithin(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        if (depth >= 5) return null
        for (i in 0 until node.childCount) node.getChild(i)?.let { child ->
            editableWithin(child, depth + 1)?.let { return it }
        }
        return null
    }

    fun scrollTo(label: String, limit: Int = 12) {
        // A returning screen may retain its scroll position below the requested control.
        for (forward in listOf(true, false)) {
            for (attempt in 0 until limit) {
                if (exact(label) != null) return
                if (expandItemSheet()) {
                    SystemClock.sleep(200)
                    if (exact(label) != null) return
                }
                await("No scrollable UI while finding $label", 5000) {
                    exact(label) != null || nodes().any { it.isVisibleToUser && it.isScrollable }
                }
                if (exact(label) != null) return
                if (!scroll(forward)) break
                SystemClock.sleep(200)
            }
        }
        waitText(label, 1000)
    }

    fun scroll(forward: Boolean = true): Boolean = nodes().filter { it.isVisibleToUser && it.isScrollable }.sortedBy {
        // Native Settings wraps its real list in a scrolling header that consumes actions without advancing rows.
        if (it.className?.toString() == "androidx.recyclerview.widget.RecyclerView") 0 else 1
    }.any {
        it.performAction(if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
    }

    private fun expandItemSheet(): Boolean {
        var ancestor = nodes().singleOrNull {
            it.packageName?.toString() == "com.personallibrary.app" &&
                it.contentDescription?.toString() == "Drag handle"
        }
        repeat(6) {
            val current = ancestor ?: return false
            if (current.actionList.any { it.id == AccessibilityNodeInfo.ACTION_EXPAND }) {
                check(current.performAction(AccessibilityNodeInfo.ACTION_EXPAND)) { "Item sheet rejected Expand" }
                return true
            }
            ancestor = current.parent
        }
        return false
    }

    fun setLabelledSwitch(label: String, checked: Boolean) {
        scrollTo(label)
        // Compose may flatten a Row's label and switch into sibling accessibility nodes.
        fun alignedSwitch(): AccessibilityNodeInfo? {
            val labelled = exact(label) ?: return null
            val labelBounds = Rect().also(labelled::getBoundsInScreen)
            return nodes().filter { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.isVisibleToUser && node.isCheckable &&
                    (bounds.left >= labelBounds.right || bounds.right <= labelBounds.left) &&
                    bounds.top < labelBounds.bottom && bounds.bottom > labelBounds.top
            }.singleOrNull()
        }
        alignedSwitch()?.let {
            if (it.isChecked != checked) clickNode(it)
            await("Switch state did not change: $label") { alignedSwitch()?.isChecked == checked }
            return
        }
        var ancestor = checkNotNull(exact(label))
        repeat(6) {
            val switches = descendants(ancestor).filter { it.isCheckable }
            if (switches.size == 1) {
                if (switches.single().isChecked != checked) clickNode(switches.single())
                await("Switch state did not change: $label") {
                    var fresh = exact(label)
                    repeat(6) {
                        val node = fresh
                        val match = node?.let(::descendants)?.filter { it.isCheckable }.orEmpty()
                        if (match.size == 1) return@await match.single().isChecked == checked
                        fresh = node?.parent
                    }
                    false
                }
                return
            }
            ancestor = ancestor.parent ?: throw AssertionError("No switch for $label")
        }
        throw AssertionError("No unambiguous switch for $label")
    }

    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { result += descendants(it) }
        return result
    }

    fun back() {
        val now = SystemClock.uptimeMillis()
        check(automation.injectInputEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0), true))
        check(automation.injectInputEvent(KeyEvent(now, now + 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0), true))
    }

    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    fun checkpoint(name: String) {
        val nodes = nodes()
        val pkg = automation.rootInActiveWindow?.packageName?.toString().orEmpty()
        check(pkg in setOf("com.personallibrary.app", "com.memora.qa.harness", "android", "com.android.intentresolver", "com.android.permissioncontroller", "com.google.android.permissioncontroller", "com.android.systemui", "com.android.settings", "com.android.documentsui", "com.google.android.documentsui")) {
            "Evidence refused for unexpected foreground package"
        }
        evidence.mkdirs()
        val safe = name.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        File(evidence, "$safe.txt").writeText(nodes.joinToString("\n") {
            val bounds = Rect().also(it::getBoundsInScreen)
            val text = if (it.isPassword) "[redacted]" else it.text?.toString().orEmpty().replace('\n', ' ').take(300)
            val description = if (it.isPassword) "[redacted]" else it.contentDescription?.toString().orEmpty().take(160)
            "${it.className} ${bounds.toShortString()} text=$text desc=$description"
        })
        automation.takeScreenshot()?.let { bitmap ->
            try { File(evidence, "$safe.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
        }
    }

    private inline fun MotionEvent.useEvent(action: (MotionEvent) -> Unit) { try { action(this) } finally { recycle() } }
}
