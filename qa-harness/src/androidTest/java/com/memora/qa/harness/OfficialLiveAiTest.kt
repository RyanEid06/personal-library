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
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/** One real text control and one Ask action. Existing synthetic records must remain excluded. */
@RunWith(AndroidJUnit4::class)
class OfficialLiveAiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private lateinit var token: String
    private lateinit var title: String
    private lateinit var body: String
    private var guarded = false
    private var askActions = 0
    private var resumeControl = false

    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) runCatching { ui.checkpoint("FAILED-paused-final-state") }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) { Log.i("MemoraQA", "${description.methodName}: PASS") }
    }

    @Before fun verifyOfficialSyntheticEmulator() {
        val priorControl = InstrumentationRegistry.getArguments().getString("resumeControlToken")
        check(priorControl == null || priorControl.matches(Regex("QALIVE[0-9]+"))) { "Only a known synthetic control token may resume" }
        resumeControl = priorControl != null
        token = priorControl ?: "QALIVE${SystemClock.elapsedRealtime()}"
        title = "$token Silver orchard control"
        body = "$token: The silver orchard meeting is on Thursday at 14:20. The entry code is EMBER57. This is artificial QA data."
        ui = ReleaseUi(instrumentation.uiAutomation, File(context.filesDir, "qa-evidence/$token"))
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the synthetic emulator is allowed" }
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        val info = context.packageManager.getPackageInfo(PRODUCT, PackageManager.GET_SIGNING_CERTIFICATES)
        check(info.versionName == "1.0.1" && info.longVersionCode == 3L) { "Expected official Memora 1.0.1/code3" }
        check(hash(checkNotNull(info.signingInfo).apkContentsSigners.single().toByteArray()) == CERTIFICATE) {
            "Expected permanent release signer"
        }
        guarded = true
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        }
        launchProduct()
    }

    @Test fun T27_T28_oneIsolatedLiveTextEnrichmentAnswerAndExactSource() {
        var primaryFailure: Throwable? = null
        try {
            setPaused(true, requirePaired = true)
            verifyIsolation(existing = EXISTING, controls = if (resumeControl) 1 else 0)
            if (!resumeControl) createUnfiledControl()
            // QA-003 on the official APK can hide committed rows. Restart only after the Add dialog closed.
            restartProduct()
            verifyIsolation(existing = EXISTING, controls = 1)
            openControl()
            ui.scrollTo("Exclude from AI")
            check(labelledCheckable("Exclude from AI", right = true)?.isChecked == false) {
                "The one new control must be eligible; never change old item privacy"
            }
            ui.checkpoint("T27-only-new-control-eligible")

            setPaused(false, requirePaired = true)
            openControl()
            ui.scrollTo("AI Status", 24)
            val deadline = SystemClock.uptimeMillis() + 120000
            var observedStatus = detailValue("AI Status")
            while (SystemClock.uptimeMillis() < deadline && observedStatus !in setOf("READY", "PARTIAL", "NEEDS_RETRY", "UNSUPPORTED")) {
                SystemClock.sleep(500)
                observedStatus = detailValue("AI Status")
            }
            ui.checkpoint("T27-enrichment-observation-before-pause")
            setPaused(true)
            // Refresh the published row after bounded background work; do not press Retry analysis.
            restartProduct()
            openControl()
            ui.scrollTo("Storage & AI Details", 24)
            var description = ""
            var finalStatus = ""
            repeat(12) {
                if (description.isBlank()) description = detailValue("AI Description")
                if (finalStatus.isBlank()) finalStatus = detailValue("AI Status")
                if (finalStatus.isBlank()) { ui.scroll(); SystemClock.sleep(150) }
            }
            val enriched = finalStatus == "READY" && description.isNotBlank() &&
                listOf("Thursday", "14:20", "EMBER57").any { description.contains(it, ignoreCase = true) }
            Log.i("MemoraQA", "T27 real control status=$finalStatus descriptionPresent=${description.isNotBlank()} matchedControlDetail=$enriched")
            ui.checkpoint("T27-published-control-ai-details")

            // Recheck the whole permitted set immediately before the only interactive request.
            verifyIsolation(existing = EXISTING, controls = 1)
            setPaused(false, requirePaired = true)
            library()
            ui.click("Search")
            ui.waitText("Search Library")
            val question = "For the silver orchard meeting in $token, what day and time is it and what is the entry code?"
            ui.edit(ASK_INPUT, question)
            ui.await("The question must settle before the live request") {
                ui.nodes().any { it.isEditable && it.text?.toString() == question } && ui.exact("Searching your library…") == null
            }
            check(askActions == 0) { "Only one Ask action is authorized" }
            askActions++
            ui.click("Ask AI")
            val outcome = awaitAnswerOutcome()
            ui.checkpoint("T28-live-answer-or-provider-outcome")
            if (outcome != "AI answer") {
                Log.e("MemoraQA", "T28 live provider/backend outcome: $outcome; no fake response or retry")
                throw AssertionError("Live provider/backend did not return an answer: $outcome (T27 status=$finalStatus)")
            }
            val answer = answerText()
            val normalized = answer.lowercase(Locale.ROOT)
            check(normalized.contains("thursday") && normalized.contains("ember57") &&
                Regex("14[:.]20|2[:.]20\\s*(p\\.?m\\.?|in the afternoon)").containsMatchIn(normalized)) {
                "Actual answer omitted or contradicted the control's day/time/code"
            }
            ui.checkpoint("T28-answer-facts-and-source-association")
            ui.scrollTo("Open source: $title", 24)
            ui.click("Open source: $title")
            ui.waitText("Edit Item")
            ui.waitText(title)
            ui.scrollTo(body, 24)
            ui.waitText(body)
            ui.checkpoint("T28-exact-control-opened-from-answer")
            Log.i("MemoraQA", "T28 one real Ask action returned the control facts and opened its exact source; T27 enriched=$enriched")
            check(enriched) { "T28 answered, but T27 real background enrichment was not confirmed (status=$finalStatus)" }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            if (guarded) {
                try {
                    setPaused(true)
                    ui.checkpoint("FINAL-Pause-Background-AI-ON")
                    Log.i("MemoraQA", "Live control preserved; Pause Background AI verified ON; Ask actions=$askActions")
                } catch (cleanupFailure: Throwable) {
                    Log.e("MemoraQA", "CRITICAL: could not verify final Pause Background AI ON")
                    if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure) else throw cleanupFailure
                }
            }
        }
    }

    private fun launchProduct() {
        context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        ui.await("Memora must be foreground") { instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() == PRODUCT }
    }

    private fun restartProduct() {
        check(guarded)
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
    }

    private fun library() {
        for (attempt in 0 until 8) {
            if (ui.exact("Back to Settings") != null) {
                ui.click("Back to Settings")
                ui.await("Nested Settings page must close") { ui.exact("Back to Library") != null }
            } else if (ui.exact("Back to Library") != null) {
                ui.click("Back to Library")
                ui.await("Settings must return to Library") { ui.exact("Library") != null }
            } else if (ui.exact("Edit Item") != null || ui.exact("Storage & AI Details") != null || ui.exact("AI Status") != null ||
                ui.exact("Exclude from AI") != null || ui.exact("Add to Library") != null || ui.exact("AI Privacy") != null) {
                ui.back()
                SystemClock.sleep(200)
            } else if (ui.exact("Library") != null) break
        }
        if (ui.exact("Cancel selection") != null) ui.click("Cancel selection")
        ui.click("Library")
        ui.await("Library search must be available") {
            ui.exact("Settings") != null && ui.nodes().any { it.isVisibleToUser && it.isEditable }
        }
        ui.edit(SEARCH_INPUT, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
        ui.await("Library must finish its current query", 45000) { libraryCount() != null && ui.exact("Searching your library…") == null }
    }

    private fun setPaused(paused: Boolean, requirePaired: Boolean = false) {
        library()
        ui.click("Settings")
        ui.await("Settings must be ready") { ui.nodes().any { it.isVisibleToUser && it.isScrollable } }
        scrollTop()
        if (requirePaired) ui.scrollTo("Paired", 24)
        ui.setLabelledSwitch("Pause Background AI", paused)
        check(labelledCheckable("Pause Background AI", right = true)?.isChecked == paused) { "Pause state must be verified through UI" }
    }

    private fun verifyIsolation(existing: Int, controls: Int) {
        library()
        ui.await("Refuse live calls unless the exact synthetic item count matches", 45000) { libraryCount() == existing + controls }
        ui.click("Collections")
        ui.waitText("Add Collection")
        scrollTop()
        check(collectionCount("Unfiled") == controls) { "Unfiled must contain only the newly generated control" }
        ui.scrollTo("Manage Random Links", 24)
        check(collectionCount("Random Links") == existing) { "Every preexisting active item must belong to excluded Random Links" }
        ui.click("Manage Random Links")
        ui.click("AI Privacy")
        ui.waitText("Exclude 'Random Links' from AI analysis and answers. Items remain available in local search.")
        check(labelledCheckable("Exclude from AI", right = true)?.isChecked == true) {
            "Refuse live calls: existing Random Links privacy must already be excluded"
        }
        ui.checkpoint("privacy-existing-$existing-excluded-control-count-$controls")
        ui.click("Cancel")
        ui.await("Privacy inspection must close") { ui.exact("AI Privacy") == null }
    }

    private fun createUnfiledControl() {
        library()
        ui.click("Add")
        ui.waitText("Add to Library")
        ui.edit("Title (Optional)", title)
        ui.edit("Note Content / Text / Grades", body)
        // The existing Add dialog starts with no selected collections; do not assign this control.
        ui.scrollTo("Save Note", 24)
        ui.click("Save Note")
        ui.await("Add dialog must dismiss", 45000) { ui.exact("Add to Library") == null }
        // The caller restarts before querying, preserving independent QA despite official QA-003.
    }

    private fun openControl() {
        library()
        ui.edit(SEARCH_INPUT, token)
        ui.await("Exactly one committed control must be visible", 45000) {
            libraryCount() == 1 && ui.exact(title) != null && ui.exact("Searching your library…") == null
        }
        ui.click(title)
        ui.waitText("Edit Item")
        ui.waitText(title)
        ui.scrollTo(body, 24)
        ui.waitText(body)
    }

    private fun libraryCount(): Int? = ui.nodes().filter { it.isVisibleToUser }.mapNotNull {
        Regex("(\\d+) items • (Saved|Source) Date").matchEntire(it.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
    }.singleOrNull()

    private fun collectionCount(name: String): Int {
        ui.scrollTo(name, 24)
        var ancestor = ui.exact(name)
        repeat(8) {
            val current = ancestor ?: throw AssertionError("Collection card missing: $name")
            val counts = descendants(current).filter { it.isVisibleToUser }.mapNotNull {
                Regex("(\\d+) items?").matchEntire(it.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
            }.distinct()
            if (counts.size == 1) return counts.single()
            check(counts.size < 2) { "Ambiguous collection count; refusing live calls" }
            ancestor = current.parent
        }
        throw AssertionError("No count scoped to collection $name")
    }

    private fun labelledCheckable(label: String, right: Boolean): AccessibilityNodeInfo? {
        val labelled = ui.exact(label) ?: return null
        val labelBounds = Rect().also(labelled::getBoundsInScreen)
        val aligned = ui.nodes().filter {
            val bounds = Rect().also(it::getBoundsInScreen)
            it.isVisibleToUser && it.isCheckable && (if (right) bounds.left >= labelBounds.right else bounds.right <= labelBounds.left) &&
                bounds.top < labelBounds.bottom && bounds.bottom > labelBounds.top
        }.singleOrNull()
        if (aligned != null) return aligned
        var ancestor: AccessibilityNodeInfo? = labelled
        repeat(6) {
            val current = ancestor ?: return null
            val candidates = descendants(current).filter { it.isVisibleToUser && it.isCheckable }
            if (candidates.size == 1) return candidates.single()
            if (candidates.size > 1) return null
            ancestor = current.parent
        }
        return null
    }

    private fun detailValue(label: String): String {
        val labelled = ui.exact(label) ?: return ""
        val bounds = Rect().also(labelled::getBoundsInScreen)
        val values = ui.nodes().filter {
            val other = Rect().also(it::getBoundsInScreen)
            it.isVisibleToUser && !it.text.isNullOrBlank() && it.text?.toString() != label &&
                other.left >= bounds.right && other.top < bounds.bottom && other.bottom > bounds.top
        }.map { it.text.toString() }.distinct()
        return values.singleOrNull().orEmpty()
    }

    private fun awaitAnswerOutcome(): String {
        var outcome: String? = null
        var lastScroll = 0L
        var scrollStep = 0
        ui.await("One live Ask must produce an answer or a visible backend failure", 120000) {
            outcome = when {
                ui.exact("AI answer") != null -> "AI answer"
                else -> ui.nodes().filter { it.isVisibleToUser }.mapNotNull { it.text?.toString() }.firstOrNull { text ->
                    ERROR_PREFIXES.any(text::startsWith) || text == "Conflicting evidence"
                }
            }
            if (outcome == null && SystemClock.uptimeMillis() - lastScroll > 1000) {
                // An asynchronous answer is inserted above related matches; revisit both ends.
                ui.scroll(forward = (scrollStep++ / 5) % 2 == 0)
                lastScroll = SystemClock.uptimeMillis()
            }
            outcome != null
        }
        return checkNotNull(outcome)
    }

    private fun answerText(): String {
        var ancestor = ui.exact("AI answer")
        repeat(9) {
            val current = ancestor ?: throw AssertionError("Actual AI answer card missing")
            val nodes = descendants(current)
            if (nodes.any { it.contentDescription?.toString() == "Open source: $title" }) {
                check(nodes.none { it.isEditable }) { "Refuse to use the search input or local-result text as the AI answer" }
                return nodes.mapNotNull { it.text?.toString() }.distinct()
                    .filter { it !in setOf("AI answer", "Sources", title) && !it.startsWith("Open source:") }.joinToString("\n")
            }
            ancestor = current.parent
        }
        throw AssertionError("Answer must have a source button associated with the exact control")
    }

    private fun scrollTop() { repeat(18) { if (!ui.scroll(false)) return; SystemClock.sleep(100) } }
    private fun descendants(node: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf(node)
        for (index in 0 until node.childCount) node.getChild(index)?.let { result += descendants(it) }
        return result
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val EXISTING = 235
        private const val CERTIFICATE = "98686d31bfe4370cc30024439b56d9e72de71c081951d31f5d4fdd31d4eb0598"
        private const val SEARCH_INPUT = "Search notes, photos, links..."
        private const val ASK_INPUT = "Search by concept, note, file, or grade..."
        private val ERROR_PREFIXES = listOf("Direct answers are not available", "AI search is unavailable", "AI search needs", "AI search stopped",
            "There isn't enough evidence in the selected library items to answer this question.")
    }
}
