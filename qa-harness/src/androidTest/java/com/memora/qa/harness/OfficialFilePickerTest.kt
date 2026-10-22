package com.memora.qa.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
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
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.Random

/** Actual SAF flow into the official APK or an explicitly hash-pinned local candidate, using owned fixtures. */
@RunWith(AndroidJUnit4::class)
class OfficialFilePickerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private lateinit var token: String
    private lateinit var fixtureDirectory: File
    private var guarded = false
    private var captureFinished = false
    private val fixtures = mutableListOf<File>()
    private val publicFixtures = mutableMapOf<File, Uri>()
    @get:Rule val testName = TestName()
    @get:Rule val evidence = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) runCatching { ui.checkpoint("FAILED") }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) { Log.i("MemoraQA", "${description.methodName}: PASS") }
    }

    @Before fun guardOfficialSyntheticEmulatorAndPauseAi() {
        token = "QA-FP-${SystemClock.elapsedRealtime()}"
        ui = ReleaseUi(instrumentation.uiAutomation, File(context.filesDir, "qa-evidence/${testName.methodName}-$token"))
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the disposable emulator is allowed" }
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        requireOfficialOrPinnedCandidate(context, "File picker")
        guarded = true
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        fixtureDirectory = File(checkNotNull(context.getExternalFilesDir("picker-fixtures")), token)
        check(fixtureDirectory.mkdirs()) { "Owned fixture directory must be fresh" }
        restartProduct()
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
    }

    @After fun removeOnlyOwnedFixtureSources() {
        if (!guarded || !::fixtureDirectory.isInitialized) return
        context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().remove("sha256").commit()
        // Preserve sources when capture never reached its completed banner; it may still need the provider.
        if (!captureFinished) return
        fixtures.forEach { file ->
            publicPath(file) // Validate the owned fixture before deleting its Downloads row.
            publicFixtures.remove(file)?.let { check(context.contentResolver.delete(it, null, null) == 1) }
            check(file.parentFile?.canonicalFile == fixtureDirectory.canonicalFile)
            file.delete()
        }
        check(token.matches(Regex("QA-FP-\\d+")))
        ui.shell("rmdir '/sdcard/Download/MemoraQA/$token'")
        fixtureDirectory.delete()
    }

    @Test fun T16_ordinaryTextThroughAddFilesRemainsNormalDocumentAfterRestart() {
        val prefix = "$token ordinary synthetic ledger, not a WhatsApp export.\n"
        val body = prefix + "ordinary record\n".repeat(250)
        val file = fixture("$token-ordinary.txt", body.toByteArray(Charsets.UTF_8))
        selectThroughAdd(listOf(file))
        assertNoWhatsAppMigration()
        restartAfterConfirmedSave(1)
        open(file)
        ui.waitText("DOCUMENT")
        ui.waitText("File Content Preview")
        ui.waitText(body)
        assertMetadata(file)
        ui.checkpoint("T16-normal-text-document-metadata-after-restart")
        verifyExternalOriginalAndMime(file, "text/plain")
        restartProduct()
        open(file)
        ui.waitText("DOCUMENT")
        ui.waitText(body)
        assertNoWhatsAppMigration()
        ui.checkpoint("T16-ordinary-text-search-preview-persisted")
    }

    @Test fun T17_multiFileSafPreservesPdfPngAndMegabyteTextMetadataMimeAndOriginals() {
        val textPrefix = "$token ordinary large text fixture\n"
        val largeText = ByteArray(1_310_720) { 'x'.code.toByte() }.also {
            textPrefix.toByteArray(Charsets.UTF_8).copyInto(it)
        }
        val text = fixture("$token-large.txt", largeText)
        val pdf = fixture("$token-document.pdf", tinyPdf())
        val png = fixture("$token-image.png", tinyPng())
        val files = listOf(text, pdf, png)
        selectThroughAdd(files)
        restartAfterConfirmedSave(files.size)
        search(token, files.size)
        ui.checkpoint("T17-three-selected-files-persisted-after-explicit-restart-workaround")
        files.forEach { file ->
            open(file)
            when (file.extension) {
                "txt" -> { ui.waitText("DOCUMENT"); ui.waitText("File Content Preview") }
                "pdf" -> { ui.waitText("DOCUMENT"); ui.waitText("Page 1 of 1", 30000) }
                "png" -> ui.waitText("PHOTO")
            }
            assertMetadata(file)
            ui.checkpoint("T17-${file.extension}-name-size-hash-metadata")
            verifyExternalOriginalAndMime(file, when (file.extension) {
                "txt" -> "text/plain"
                "pdf" -> "application/pdf"
                else -> "image/png"
            })
        }
        restartProduct()
        search(token, files.size)
        files.forEach { search(it.name, 1); ui.waitText(it.name) }
        search(token, files.size)
        ui.checkpoint("T17-multifile-search-count-and-names-survive-second-restart")
    }

    private fun fixture(name: String, bytes: ByteArray): File {
        check(guarded && name.startsWith("$token-") && name.matches(Regex("[A-Za-z0-9._-]+")))
        val file = File(fixtureDirectory, name).apply { writeBytes(bytes) }
        fixtures += file
        publicPath(file)
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/MemoraQA/$token/")
            put(MediaStore.Downloads.MIME_TYPE, when (file.extension) {
                "pdf" -> "application/pdf"
                "png" -> "image/png"
                else -> "text/plain"
            })
            put(MediaStore.Downloads.IS_PENDING, 1)
        }))
        publicFixtures[file] = uri
        checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        val copied = checkNotNull(resolver.openInputStream(uri)).use { it.readBytes() }
        check(copied.size == bytes.size && hash(copied) == hash(bytes)) { "Owned Downloads fixture copy differs" }
        return file
    }

    private fun publicPath(file: File): String {
        check(guarded && token.matches(Regex("QA-FP-\\d+")))
        check(file.parentFile?.canonicalFile == fixtureDirectory.canonicalFile && file.name.startsWith("$token-"))
        check(file.name.matches(Regex("[A-Za-z0-9._-]+")))
        return "/sdcard/Download/MemoraQA/$token/${file.name}"
    }

    private fun selectThroughAdd(files: List<File>) {
        library()
        ui.click("Add")
        ui.waitText("Add to Library")
        ui.click("Import Files")
        ui.click("Choose Files from Device")
        ui.await("Android SAF picker must be foreground") { foreground() in PICKERS }
        pickerClick("Show roots", "Show navigation roots")
        ui.await("Picker roots drawer must open") {
            ui.nodes().any { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true }
        }
        fun downloadsRoot() = ui.nodes().filter {
            it.isVisibleToUser && it.viewIdResourceName == "android:id/title" &&
                it.text?.toString() in setOf("Downloads", "Download")
        }.singleOrNull()
        ui.await("Downloads drawer row must be loaded") {
            foreground() in PICKERS && downloadsRoot() != null
        }
        check(foreground() in PICKERS)
        ui.clickNode(checkNotNull(downloadsRoot()))
        ui.await("Downloads drawer must close") {
            foreground() in PICKERS && ui.nodes().none { it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/roots_list") == true }
        }
        ui.scrollTo("MemoraQA", 30)
        pickerClick("MemoraQA")
        ui.scrollTo(token, 30)
        pickerClick(token)
        ui.scrollTo(files.first().name, 30)
        var node = ui.exact(files.first().name)
        var selected = false
        repeat(10) {
            val current = node
            if (!selected && current != null && current.isLongClickable && current.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) selected = true
            node = current?.parent
        }
        if (!selected) {
            check(foreground() in PICKERS) { "Picker lost foreground before fixture selection" }
            val target = checkNotNull(ui.exact(files.first().name))
            check(target.isVisibleToUser && target.packageName?.toString() in PICKERS)
            val bounds = Rect().also(target::getBoundsInScreen)
            check(!bounds.isEmpty) { "Owned picker document has no visible bounds" }
            // DocumentsUI on API37 can omit the native long-click action. Use only this live fixture's bounds.
            ui.shell("input touchscreen swipe ${bounds.centerX()} ${bounds.centerY()} ${bounds.centerX()} ${bounds.centerY()} 800")
            selected = true
        }
        check(selected) { "Owned first picker document has no accessible multi-select action" }
        ui.waitText("1 selected")
        files.drop(1).forEachIndexed { index, file ->
            ui.scrollTo(file.name, 30)
            pickerClick(file.name)
            ui.waitText("${index + 2} selected")
        }
        ui.checkpoint("SAF-${files.size}-owned-files-selected")
        pickerClick("Select", "Open", "OPEN")
        ui.await("SAF selection must return to Memora") { foreground() == PRODUCT && ui.exact("Add to Library") == null }
    }

    private fun assertNoWhatsAppMigration() {
        check(ui.exact("WhatsApp Import Preview") == null && ui.exact("Import Chat Archive") == null &&
            ui.exact("Day first") == null && ui.exact("Month first") == null) { "Ordinary text unexpectedly opened a migration flow" }
    }

    private fun restartAfterConfirmedSave(expected: Int) {
        ui.waitText("Saved $expected item(s) to your library.", 60000)
        captureFinished = true
        ui.checkpoint("capture-confirmed-before-QA003-workaround")
        // QA-003: the unchanged official APK's Library observer can remain stale after CaptureService commits.
        // Restart only AFTER the actual completed-save banner; this does not count as fixing live refresh.
        Log.i("MemoraQA", "$token: QA-003 post-confirmed-save force-stop workaround; live Library refresh remains unverified")
        restartProduct()
        assertNoWhatsAppMigration()
    }

    private fun launchProduct() {
        check(guarded)
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

    private fun library() {
        launchIfExternal()
        if (ui.exact("Edit Item") != null) {
            ui.back()
            ui.await("Item details must dismiss") { ui.exact("Edit Item") == null }
        }
        ui.click("Library")
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
    }

    private fun launchIfExternal() { if (foreground() != PRODUCT) launchProduct() }

    private fun search(query: String, count: Int) {
        library()
        ui.edit(SEARCH, query)
        var stableSince = 0L
        ui.await("Expected $count current picker-import results", 45000) {
            val ready = ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.matches(Regex("$count items • (Saved|Source) Date")) == true } &&
                ui.exact("Searching your library…") == null
            if (!ready) stableSince = 0L else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            ready && SystemClock.uptimeMillis() - stableSince >= 450
        }
    }

    private fun open(file: File) {
        search(file.name, 1)
        ui.checkpoint("before-open-${file.extension}")
        ui.click(file.name)
        try {
            ui.waitText("Edit Item")
        } catch (error: AssertionError) {
            Log.e("MemoraQA", "${testName.methodName}: open-${file.extension} foreground=${foreground()}")
            throw error
        }
    }

    private fun assertMetadata(file: File) {
        assertDetail("Content Hash", hash(file.readBytes()).take(16) + "...")
        assertDetail("Original Name", file.name)
        val bytes = file.length()
        val size = if (bytes > 1024 * 1024) String.format(Locale.getDefault(), "%.2f MB", bytes / (1024.0 * 1024.0)) else "${bytes / 1024} KB"
        assertDetail("File Size", size)
    }

    private fun assertDetail(label: String, value: String) {
        ui.scrollTo(label, 30)
        ui.await("Expected detail row $label to display the original value") {
            val labelNode = ui.exact(label) ?: return@await false
            val bounds = Rect().also(labelNode::getBoundsInScreen)
            ui.nodes().any { node ->
                val other = Rect().also(node::getBoundsInScreen)
                node.isVisibleToUser && node.text?.toString() == value && other.top < bounds.bottom && other.bottom > bounds.top
            }
        }
    }

    private fun verifyExternalOriginalAndMime(file: File, mime: String) {
        val expected = hash(file.readBytes())
        val monitor = instrumentation.addMonitor(DonorActivity::class.java.name, null, false)
        check(context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().putString("sha256", expected).commit())
        try {
            repeat(24) { if (ui.exact("Open File") == null) { ui.scroll(forward = false); SystemClock.sleep(80) } }
            ui.scrollTo("Open File", 30)
            ui.click("Open File")
            ui.await("Original must open through the armed synthetic viewer", 30000) {
                when {
                    ui.exact("QA original verified $expected") != null -> true
                    ui.clickIfPresent("Memora QA Harness") -> false
                    ui.clickIfPresent("Just once") -> false
                    else -> false
                }
            }
            val viewer = checkNotNull(instrumentation.waitForMonitorWithTimeout(monitor, 5000)) { "Own external viewer Activity was not observed" }
            var received: Intent? = null
            instrumentation.runOnMainSync { received = Intent(viewer.intent) }
            val delivered = checkNotNull(received)
            check(delivered.type == mime) { "Open File MIME differs from the imported provider MIME" }
            check(delivered.data?.authority == "$PRODUCT.fileprovider" && delivered.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            ui.checkpoint("original-${file.extension}-sha-and-outbound-mime-verified")
        } finally {
            instrumentation.removeMonitor(monitor)
            check(context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().remove("sha256").commit())
        }
    }

    private fun foreground() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()

    private fun pickerClick(vararg labels: String) {
        check(foreground() in PICKERS) { "SAF action refused outside document picker" }
        ui.await("Picker action missing: ${labels.joinToString()}") { labels.any { ui.exact(it) != null } }
        ui.click(labels.first { ui.exact(it) != null })
    }

    private fun tinyPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            val random = Random(token.hashCode().toLong())
            for (y in 0 until 64) for (x in 0 until 64) bitmap.setPixel(x, y, random.nextInt() or 0xff000000.toInt())
            return ByteArrayOutputStream().use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)); output.toByteArray() }
        } finally { bitmap.recycle() }
    }

    private fun tinyPdf(): ByteArray {
        val output = ByteArrayOutputStream()
        fun write(text: String) { output.write(text.toByteArray(Charsets.US_ASCII)) }
        val stream = "BT /F1 14 Tf 24 100 Td ($token ORDINARY PICKER PDF) Tj ET\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 180] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Length ${stream.length} >>\nstream\n${stream}endstream")
        write("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body -> output.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = output.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(Locale.US, it)) }
        write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
        private const val CERTIFICATE = "98686d31bfe4370cc30024439b56d9e72de71c081951d31f5d4fdd31d4eb0598"
        private val PICKERS = setOf("com.android.documentsui", "com.google.android.documentsui")
    }
}
