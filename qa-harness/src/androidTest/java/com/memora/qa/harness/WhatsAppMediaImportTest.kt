package com.memora.qa.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
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
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Guarded official/pinned-candidate SAF import of the repository's synthetic WhatsApp media fixture. */
@RunWith(AndroidJUnit4::class)
class WhatsAppMediaImportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private lateinit var token: String
    private lateinit var evidenceDir: File
    private val downloads = linkedMapOf<String, Uri>()
    private var guarded = false
    private var finished = false

    @get:Rule val testName = TestName()
    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) runCatching { ui.checkpoint("FAILED") }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) { Log.i("MemoraQA", "${description.methodName}: PASS") }
    }

    @Before fun guardPinnedSyntheticEmulatorAndPauseAi() {
        token = "QA-WA-${SystemClock.elapsedRealtime()}"
        evidenceDir = File(context.filesDir, "qa-evidence/${testName.methodName}-$token")
        ui = ReleaseUi(instrumentation.uiAutomation, evidenceDir)
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the disposable emulator is allowed" }
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        requireOfficialOrPinnedCandidate(context, "WhatsApp media import")
        guarded = true
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            eventTypes = eventTypes or AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
        }
        launchProduct()
        library()
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
    }

    @After fun removeOnlyOwnedDownloadsAfterCompletedVerification() {
        if (!guarded) return
        context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().remove("sha256").commit()
        // A failed run keeps its inputs for diagnosis; app records are left for the owner's snapshot rollback.
        if (!finished) return
        downloads.values.forEach { uri -> check(context.contentResolver.delete(uri, null, null) == 1) }
        check(token.matches(Regex("QA-WA-\\d+")))
        ui.shell("rmdir '/sdcard/Download/MemoraQA/$token'")
    }

    @Test fun T08_nativeSafImportsEightMessagesAndThreeExactOriginalsIncludingGif() {
        val chat = chatBytes()
        val images = listOf(
            "IMG-20261017-WA0001.jpg" to "4a58179bb3a10d75f72f5cf34173316882ceb3b745d8a30973361391b97eab8f",
            "IMG-20261017-WA0002.png" to "444664af7c9718b4210e87d9bd628f4c05b83b43acb6bab7417f3ebf130463c9",
            "GIF-20261017-WA0003.gif" to "ae62c9446af7671b8d95afd28494b66529f5d883bbb1c4e0daaf63ca55a042ed"
        ).map { (name, expected) ->
            val bytes = instrumentation.context.assets.open("media/$name").use { it.readBytes() }
            check(hash(bytes) == expected) { "Harness synthetic fixture differs from the approved $name bytes" }
            name to bytes
        }
        check(hash(chat) == "de058d71e85e98c5d34064ba8cee0a11947ebd85a5f601899c2bd5d35676eac5") {
            "Synthetic chat fixture differs from the approved Media_Example export"
        }

        val beforeCount = libraryCount()
        listOf("QA-MEDIA-001", "QA-MEDIA-003", "QA-MEDIA-005", "QA-MEDIA-007").forEach { search(it, 0) }
        val attachmentNames = listOf("IMG-20261017-WA0001.jpg", "IMG-20261017-WA0002.png",
            "GIF-20261017-WA0003.gif", "IMG-20261017-WA9999.jpg")
        val photoBaselines = attachmentNames.associateWith { name ->
            searchPhoto(name, null) to exactPhotoTitleCards(name)
        }
        search("", null)

        val txt = publish("_chat.txt", "text/plain", chat)
        images.forEach { (name, bytes) -> publish(name, mime(name), bytes) }
        library()
        ui.click("Settings")
        ui.scrollTo("Import Chat Archive", 30)
        ui.click("Import Chat Archive")
        ui.waitText("Import WhatsApp export")
        ui.click("Choose WhatsApp .txt")
        pickerToDownloads(txt.name)
        ui.await("Single-file SAF selection must return to the WhatsApp importer") {
            foreground() == PRODUCT && ui.exact("Import WhatsApp export") != null
        }

        // Returning from SAF precedes asynchronous text parsing. A visible label
        // alone does not mean the picker button is enabled yet.
        ui.await("Parsed eight-message preview and enabled attachment picker must be ready") {
            val nodes = ui.nodes()
            val parsed = nodes.any { it.isVisibleToUser &&
                Regex("\\bMessages: 8\\b").containsMatchIn(it.text?.toString().orEmpty()) }
            val label = ui.exact("Choose attachment files (optional)")
            val bounds = Rect().also { label?.getBoundsInScreen(it) }
            // Compose exposes this enabled button as a clickable View, not a Button class.
            parsed && label != null && !bounds.isEmpty && nodes.any { node ->
                val buttonBounds = Rect().also(node::getBoundsInScreen)
                node.packageName?.toString() == PRODUCT && node.isVisibleToUser &&
                    node.isEnabled && node.isClickable && buttonBounds.contains(bounds.centerX(), bounds.centerY())
            }
        }
        ui.click("Choose attachment files (optional)")
        ui.await("Multi-file SAF picker must be foreground") { foreground() in PICKERS }
        selectMultipleFromDownloads(images.map { it.first })
        ui.await("Selected attachments must return to the WhatsApp importer") {
            foreground() == PRODUCT && ui.exact("Import WhatsApp export") != null
        }
        awaitPreview()
        ui.checkpoint("T08-native-preview-8-messages-4-references-3-matches-1-missing-0-unmatched")

        clickAndAwaitProductToast(
            "Confirm import",
            "Imported 8 new messages. 1 messages had missing attachments."
        )
        ui.await("T08 must report the real import result and close the importer", 60000) {
            foreground() == PRODUCT && ui.exact("Import WhatsApp export") == null
        }
        // The exact result toast is observed as an accessibility notification event, not a window node.
        library()
        ui.await("T08 must leave eight new messages and the original export in the library", 60000) {
            libraryCountOrNull() == beforeCount + 9
        }
        ui.checkpoint("T08-confirmed-8-new-messages-1-missing")

        listOf("QA-MEDIA-001 |", "QA-MEDIA-003 |", "QA-MEDIA-005 |", "QA-MEDIA-007 |").forEach { titlePrefix ->
            search(titlePrefix.substringBefore(' '), null)
            ui.await("Imported message title must be searchable: $titlePrefix") {
                ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.startsWith(titlePrefix) == true }
            }
        }
        for (name in attachmentNames) {
            val (baseline, exactTitles) = checkNotNull(photoBaselines[name])
            searchPhoto(name, baseline + 1)
            ui.await("Import must add exactly one exact-title photo card for $name") {
                exactPhotoTitleCards(name) == exactTitles + 1
            }
        }

        images.forEach { (name, bytes) ->
            val (baseline, exactTitles) = checkNotNull(photoBaselines[name])
            verifyImportedOriginal(name, bytes, baseline + 1, exactTitles + 1)
        }
        verifyImportedExport(chat)
        val (missingBaseline, missingTitles) = checkNotNull(photoBaselines["IMG-20261017-WA9999.jpg"])
        verifyMissingAttachmentDoesNotBorrowOriginal(missingBaseline + 1, missingTitles + 1)

        restartProduct()
        val (gifBaseline, gifTitles) = checkNotNull(photoBaselines["GIF-20261017-WA0003.gif"])
        searchPhoto("GIF-20261017-WA0003.gif", gifBaseline + 1)
        ui.await("GIF search after restart must retain exactly one new exact-title card") {
            exactPhotoTitleCards("GIF-20261017-WA0003.gif") == gifTitles + 1
        }
        ui.click("GIF-20261017-WA0003.gif")
        ui.waitText("Edit Item")
        ui.waitText("PHOTO")
        assertImagePreview("GIF-20261017-WA0003.gif")
        assertDetail("Original Name", "GIF-20261017-WA0003.gif")
        assertDetail("Content Hash", hash(images.single { it.first == "GIF-20261017-WA0003.gif" }.second).take(16) + "...")
        dismissItemDetails()
        library()
        ui.await("Library count must remain unchanged after restart") { libraryCountOrNull() == beforeCount + 9 }
        ui.checkpoint("T08-GIF-search-and-count-persist-after-restart")
        finished = true
    }

    private fun awaitPreview() {
        ui.await("Importer preview must expose exact message, match and omission counts", 30000) {
            val summary = ui.nodes().mapNotNull { it.text?.toString() }.firstOrNull { it.contains("Messages: 8") }
            summary != null && summary.contains("Ambiguous dates: 0") && summary.contains("Unparseable dates: 0") &&
                summary.contains("Attachment references: 4") && summary.contains("Supplied matching files: 3") &&
                summary.contains("Messages missing attachments: 1") && summary.contains("Unmatched supplied files: 0")
        }
        ui.waitText("Missing: IMG-20261017-WA9999.jpg")
        ui.waitText("Confirm import")
    }

    private fun publish(name: String, mime: String, bytes: ByteArray): FileRef {
        check(name.matches(Regex("[A-Za-z0-9._-]+")) && name !in downloads)
        val uri = checkNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MemoraQA/$token/")
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        downloads[name] = uri
        checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) }
        check(context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) == 1)
        val copied = checkNotNull(context.contentResolver.openInputStream(uri)).use { it.readBytes() }
        check(copied.contentEquals(bytes)) { "Published synthetic source bytes differ: $name" }
        return FileRef(name, uri)
    }

    private fun pickerToDownloads(name: String) {
        ui.await("Android SAF picker must be foreground") { foreground() in PICKERS }
        pickerClick("Show roots", "Show navigation roots")
        ui.await("Picker roots drawer must open") {
            ui.nodes().any { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true }
        }
        openDownloadsRoot()
        ui.scrollTo("MemoraQA", 30)
        pickerClick("MemoraQA")
        ui.scrollTo(token, 30)
        pickerClick(token)
        ui.scrollTo(name, 30)
        pickerClick(name)
    }

    private fun selectMultipleFromDownloads(names: List<String>) {
        check(names.size == 3 && names.distinct().size == names.size && foreground() in PICKERS)
        pickerClick("Show roots", "Show navigation roots")
        ui.await("Picker roots drawer must open") {
            ui.nodes().any { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true }
        }
        openDownloadsRoot()
        ui.scrollTo("MemoraQA", 30)
        pickerClick("MemoraQA")
        ui.scrollTo(token, 30)
        pickerClick(token)
        ui.scrollTo(names.first(), 30)

        var first = ui.exact(names.first())
        var selected = false
        repeat(10) {
            val current = first
            if (!selected && current != null && current.isLongClickable && current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) selected = true
            first = current?.parent
        }
        if (!selected) {
            check(foreground() in PICKERS) { "Picker lost foreground before first attachment selection" }
            val target = checkNotNull(ui.exact(names.first()))
            check(target.isVisibleToUser && target.packageName?.toString() in PICKERS)
            val bounds = Rect().also(target::getBoundsInScreen)
            check(!bounds.isEmpty)
            ui.shell("input touchscreen swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 800")
        }
        ui.await("Picker must remain foreground with one attachment selected") {
            foreground() in PICKERS && ui.exact("1 selected") != null
        }
        names.drop(1).forEachIndexed { index, name ->
            ui.scrollTo(name, 30)
            pickerClick(name)
            ui.await("Picker must retain ${index + 2} selected attachments") {
                foreground() in PICKERS && ui.exact("${index + 2} selected") != null
            }
        }
        check(foreground() in PICKERS) { "Picker lost foreground before attachment confirmation" }
        ui.checkpoint("T08-SAF-three-attachment-files-selected")
        pickerClick("Select", "Open", "OPEN")
    }

    private fun verifyImportedOriginal(name: String, bytes: ByteArray, expectedPhotoCount: Int, expectedExactTitles: Int) {
        searchPhoto(name, expectedPhotoCount)
        ui.await("Imported original must have the expected exact-title photo card: $name") {
            exactPhotoTitleCards(name) == expectedExactTitles
        }
        ui.click(name)
        ui.waitText("Edit Item")
        ui.waitText("PHOTO")
        assertImagePreview(name)
        ui.checkpoint("T08-${name.substringAfterLast('.')}-image-preview-node")
        assertDetail("Original Name", name)
        assertDetail("Content Hash", hash(bytes).take(16) + "...")
        verifyExternalOriginal(name, bytes)
        ui.back()
        awaitReturnedItemDetails()
        dismissItemDetails()
    }

    private fun verifyImportedExport(bytes: ByteArray) {
        search("WhatsApp export", 1)
        ui.click("WhatsApp export")
        ui.waitText("Edit Item")
        ui.waitText("DOCUMENT")
        assertDetail("Original Name", "WhatsApp export.txt")
        assertDetail("Content Hash", hash(bytes).take(16) + "...")
        verifyExternalOriginal("WhatsApp export.txt", bytes)
        ui.back()
        awaitReturnedItemDetails()
        dismissItemDetails()
    }

    private fun awaitReturnedItemDetails() {
        // Returning preserves the sheet's scroll position; memberships can hide Edit Item.
        ui.await("Synthetic viewer must return to the product item sheet") {
            foreground() == PRODUCT && ui.exact("Open File") != null &&
                ui.nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == "Drag handle" }
        }
    }

    private fun verifyMissingAttachmentDoesNotBorrowOriginal(expectedPhotoCount: Int, expectedExactTitles: Int) {
        val name = "IMG-20261017-WA9999.jpg"
        searchPhoto(name, expectedPhotoCount)
        ui.await("Missing attachment must have the expected exact-title photo card") {
            exactPhotoTitleCards(name) == expectedExactTitles
        }
        ui.click(name)
        ui.waitText("Edit Item")
        ui.waitText("PHOTO")
        assertDetail("Original Name", name)
        ui.scrollTo("Storage & AI Details", 30)
        check(ui.exact("Content Hash") == null) { "Missing attachment unexpectedly has original content bytes" }
        check(ui.exact("File Size") == null) { "Missing attachment unexpectedly has stored original size" }
        ui.checkpoint("T08-missing-row-has-no-borrowed-original")
        verifyMissingOriginalCannotOpen()
        dismissItemDetails()
    }

    private fun assertImagePreview(name: String) {
        ui.await("Visible image-preview accessibility node must be tied to $name") {
            ui.nodes().any { node ->
                val bounds = Rect().also(node::getBoundsInScreen)
                node.isVisibleToUser && node.contentDescription?.toString() == name &&
                    bounds.width() >= 80 && bounds.height() >= 80
            }
        }
    }

    private fun verifyMissingOriginalCannotOpen() {
        val monitor = instrumentation.addMonitor(DonorActivity::class.java.name, null, false)
        try {
            ui.scrollTo("Open File", 30)
            clickAndAwaitProductToast("Open File", "No external application can open this item", 5000)
            ui.await("Unavailable-original action must leave Memora in front", 5000) { foreground() == PRODUCT }
            check(instrumentation.waitForMonitorWithTimeout(monitor, 500) == null) {
                "Missing attachment unexpectedly launched the synthetic external viewer"
            }
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun clickAndAwaitProductToast(label: String, message: String, timeout: Long = 10000) {
        check(foreground() == PRODUCT) { "Toast-producing action must start in Memora" }
        val observed = CountDownLatch(1)
        instrumentation.uiAutomation.setOnAccessibilityEventListener { event ->
            if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                event.packageName?.toString() == PRODUCT && event.text.any { it.toString() == message }) {
                observed.countDown()
            }
        }
        try {
            ui.click(label)
            check(observed.await(timeout, TimeUnit.MILLISECONDS)) { "Expected product toast accessibility event was not observed" }
        } finally {
            instrumentation.uiAutomation.setOnAccessibilityEventListener(null)
        }
    }

    private fun openDownloadsRoot() {
        fun downloadsRoot() = ui.nodes().filter {
            it.isVisibleToUser && it.viewIdResourceName == "android:id/title" &&
                it.text?.toString() in setOf("Downloads", "Download")
        }.singleOrNull()
        ui.await("Downloads root row must be loaded") { foreground() in PICKERS && downloadsRoot() != null }
        check(foreground() in PICKERS)
        ui.clickNode(checkNotNull(downloadsRoot()))
        ui.await("Downloads drawer must close in the SAF picker") {
            foreground() in PICKERS && ui.nodes().none { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true }
        }
    }

    private fun verifyExternalOriginal(name: String, bytes: ByteArray) {
        val expected = hash(bytes)
        val monitor = instrumentation.addMonitor(DonorActivity::class.java.name, null, false)
        check(context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().putString("sha256", expected).commit())
        try {
            ui.scrollTo("Open File", 30)
            ui.click("Open File")
            ui.await("$name must reopen through the synthetic byte-verifying viewer", 30000) {
                when {
                    ui.exact("QA original verified $expected") != null -> true
                    ui.clickIfPresent("Memora QA Harness") -> false
                    ui.clickIfPresent("Just once") -> false
                    else -> false
                }
            }
            val activity = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000)) {
                "Synthetic external viewer Activity was not observed for $name"
            }
            var launched: Intent? = null
            instrumentation.runOnMainSync { launched = Intent(activity.intent) }
            val intent = checkNotNull(launched)
            check(intent.type == mime(name)) { "Reopened MIME differs for $name" }
            check(intent.data?.authority == "$PRODUCT.fileprovider" && intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                "Reopened original lacks the expected FileProvider read grant for $name"
            }
            ui.checkpoint("T08-original-${name.substringAfterLast('.')}-sha256-and-mime-verified")
        } finally {
            instrumentation.removeMonitor(monitor)
            check(context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().remove("sha256").commit())
        }
    }

    private fun assertDetail(label: String, value: String) {
        ui.scrollTo(label, 30)
        ui.await("$label must show the imported original value") {
            val row = ui.exact(label) ?: return@await false
            val bounds = Rect().also(row::getBoundsInScreen)
            ui.nodes().any { node ->
                val other = Rect().also(node::getBoundsInScreen)
                node.isVisibleToUser && node.text?.toString() == value && other.top < bounds.bottom && other.bottom > bounds.top
            }
        }
    }

    private fun search(query: String, expected: Int?) {
        library()
        ui.edit(SEARCH, query)
        ui.await("Search '$query' must settle to ${expected ?: "the current"} result count", 45000) {
            val count = libraryCountOrNull()
            count != null && (expected == null || count == expected) && ui.exact("Searching your library…") == null
        }
    }

    private fun searchPhoto(query: String, expected: Int?): Int {
        library()
        ui.edit(SEARCH, query)
        ui.scrollTo("Photos", 30)
        ui.click("Photos")
        ui.await("Photo-filtered search '$query' must settle to $expected results", 45000) {
            val count = libraryCountOrNull()
            count != null && (expected == null || count == expected) && ui.exact("Searching your library…") == null
        }
        return checkNotNull(libraryCountOrNull())
    }

    private fun exactPhotoTitleCards(name: String): Int = ui.nodes().filter {
        it.isVisibleToUser && it.packageName?.toString() == PRODUCT && it.text?.toString() == name
    }.mapNotNull { title ->
        val card = generateSequence(title) { it.parent }.firstOrNull { it.isLongClickable } ?: return@mapNotNull null
        Rect().also(card::getBoundsInScreen).takeUnless { it.isEmpty }
    }.distinct().size

    private fun libraryCount(): Int {
        ui.await("Library count must be available") { libraryCountOrNull() != null }
        return checkNotNull(libraryCountOrNull())
    }

    private fun libraryCountOrNull(): Int? = ui.nodes().filter { it.isVisibleToUser }.mapNotNull { node ->
        Regex("(\\d+) items • (Saved|Source) Date").matchEntire(node.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
    }.singleOrNull()

    private fun library() {
        if (foreground() != PRODUCT) launchProduct()
        dismissItemDetails()
        if (ui.exact("Import WhatsApp export") != null || ui.exact("Add to Library") != null) {
            ui.back()
            ui.await("Open import/add dialog must dismiss") {
                ui.exact("Import WhatsApp export") == null && ui.exact("Add to Library") == null
            }
        }
        if (ui.exact("Back to Library") != null) {
            ui.click("Back to Library")
            ui.await("Settings must return to Library") { ui.exact("Back to Library") == null && ui.exact("Library") != null }
        } else if (ui.exact("Back to Settings") != null) {
            ui.back()
            ui.await("Trash must return to Settings") { ui.exact("Back to Library") != null }
            ui.click("Back to Library")
            ui.await("Settings must return to Library") { ui.exact("Back to Library") == null && ui.exact("Library") != null }
        }
        ui.click("Library")
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
    }

    private fun dismissItemDetails() {
        // Memberships can scroll Edit Item out of view while the sheet drag handle remains live.
        repeat(3) {
            if (ui.nodes().any { it.contentDescription?.toString() == "Drag handle" }) {
                if (!ui.clickIfPresent("Close sheet")) ui.back()
                // Expanded sheets first collapse; let that transition settle before dismissal.
                SystemClock.sleep(700)
            }
        }
        ui.await("Item sheet must dismiss before Library navigation") {
            ui.nodes().none { it.contentDescription?.toString() == "Drag handle" }
        }
        if (ui.exact("Edit Item") != null) {
            ui.back()
            ui.await("Item details must dismiss") { ui.exact("Edit Item") == null }
        }
    }

    private fun launchProduct() {
        context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.await("Memora must be foreground") { foreground() == PRODUCT }
    }

    private fun restartProduct() {
        check(guarded)
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
    }

    private fun foreground() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    private fun pickerClick(vararg labels: String) {
        check(foreground() in PICKERS) { "SAF action refused outside the document picker" }
        ui.await("Picker action missing: ${labels.joinToString()}") { labels.any { ui.exact(it) != null } }
        ui.click(labels.first { ui.exact(it) != null })
    }

    private fun chatBytes() = instrumentation.context.assets.open("media/_chat.txt").use { it.readBytes() }

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "txt" -> "text/plain"
        else -> error("Unexpected synthetic fixture: $name")
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class FileRef(val name: String, val uri: Uri)

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
        private val PICKERS = setOf("com.android.documentsui", "com.google.android.documentsui")
    }
}
