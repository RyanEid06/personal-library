package com.memora.qa.harness

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.sin

/** Real cross-app intents into the installed permanent-signed release; never instruments Memora. */
@RunWith(AndroidJUnit4::class)
class OfficialShareTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext
    private lateinit var ui: ReleaseUi
    private var guarded = false
    private lateinit var run: String
    @get:Rule val testName = TestName()
    @get:Rule val failure = object : TestWatcher() {
        override fun failed(error: Throwable, description: Description) {
            if (guarded) runCatching { ui.checkpoint("FAILED") }
            Log.e("MemoraQA", "${description.methodName}: FAIL ${error.javaClass.simpleName}")
        }
        override fun succeeded(description: Description) { Log.i("MemoraQA", "${description.methodName}: PASS") }
    }

    @Before fun verifySyntheticEmulatorAndPauseBackgroundAiThroughUi() {
        run = "${testName.methodName}-${SystemClock.elapsedRealtime()}"
        ui = ReleaseUi(instrumentation.uiAutomation, File(context.filesDir, "qa-evidence/$run"))
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1") { "Only the disposable emulator is allowed" }
        val names = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name").map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(names.isNotEmpty() && names.all { it == "Memora-QA-API37" }) { "Wrong or unidentified AVD" }
        requireOfficialOrPinnedCandidate(context, "Share")
        guarded = true
        instrumentation.uiAutomation.serviceInfo = instrumentation.uiAutomation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        launchProduct()
        library()
        ui.click("Settings")
        ui.setLabelledSwitch("Pause Background AI", true)
        ui.back()
        library()
        Log.i("MemoraQA", "${testName.methodName}: guarded synthetic intent-receive run (artifact identity logged above); no reset")
    }

    @Test fun T10_plainTextIntentReceiveSaveAndReopen() {
        val body = "QA-T10-$run synthetic text café مرحبا 🚀"
        send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body))
        savePreview(text = body)
        openSaved(body, "Untitled Note")
        ui.waitText(body)
        ui.waitText("TEXT")
        ui.checkpoint("T10-text-reopened")
    }

    @Test fun T10_manyCollectionsKeepDiscardSaveAndLastMembershipReachable() {
        val token = "QAC${System.currentTimeMillis()}"
        val names = (0 until 24).map { "$token ${it.toString().padStart(2, '0')}" }
        ui.click("Collections")
        names.forEach { name ->
            ui.click("Add Collection")
            ui.waitText("New Collection")
            ui.edit("Collection Name", name)
            ui.click("Create")
            ui.await("Synthetic collection dialog must close") { ui.exact("New Collection") == null }
            ui.scrollTo("Manage $name", 40)
        }
        val body = "QA-T10-$token synthetic many-collection preview control"
        library()
        send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body))
        ui.waitText(body)
        ui.scrollTo(names.last(), 40)
        ui.click(names.last())
        ui.scrollTo("Discard", 40)
        ui.click("Discard")
        ui.await("Discard must close the actual incoming preview") { ui.exact("Save from WhatsApp / Share") == null }
        library()
        assertSearchResults(token, 0)
        ui.checkpoint("T10-many-collections-discard-no-record")
        library()
        send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body))
        ui.waitText(body)
        ui.scrollTo(names.last(), 40)
        ui.click(names.last())
        savePreview()
        openSaved(token, "Untitled Note")
        ui.waitText(body)
        library()
        ui.click("Collections")
        ui.scrollTo("Manage ${names.last()}", 40)
        ui.click("Manage ${names.last()}")
        ui.click("Open")
        ui.waitText("Collection: ${names.last()}")
        assertSearchResults(token, 1)
        ui.click("Untitled Note")
        ui.waitText("Edit Item")
        ui.waitText(body)
        ui.checkpoint("T10-many-collections-last-membership-reopened")
        library()
        // Keep these owned synthetic collections so subsequent real media shares also face the long preview.
    }

    @Test fun T10_linkChooserSelectionSaveAndReopenWithoutNetwork() {
        val url = "https://example.com/qa/$run"
        send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), chooser = true)
        savePreview(text = url)
        openSaved(url, "example.com")
        ui.waitText(url)
        ui.scrollTo("Open Link")
        ui.checkpoint("T10-link-reopened-no-navigation")
    }

    @Test fun T11_jpegAndPngProviderSharesPreviewAndOriginalGrantIntegrity() {
        listOf("IMG-20261017-WA0001.jpg" to "image/jpeg", "IMG-20261017-WA0002.png" to "image/png").forEach { (source, mime) ->
            val file = assetFile("QA-T11-$run-$source", source)
            shareFile(file, mime)
            openSaved(file.name, file.name)
            ui.waitText("PHOTO")
            ui.checkpoint("T11-${file.extension}-preview")
            verifyExternalOriginal(file)
        }
    }

    @Test fun T14_gifProviderSharePreviewAndOriginalGrantIntegrity() {
        val file = assetFile("QA-T14-$run.gif", "GIF-20261017-WA0003.gif")
        shareFile(file, "image/gif")
        openSaved(file.name, file.name)
        ui.waitText("PHOTO")
        ui.checkpoint("T14-gif-preview-animation-unclaimed")
        verifyExternalOriginal(file)
    }

    @Test fun T12_pdfShareRendersPageAndReopensOriginal() {
        val file = output("QA-T12-$run.pdf", tinyPdf())
        shareFile(file, "application/pdf")
        openSaved(file.name, file.name)
        ui.waitText("Page 1 of 1", 30000)
        ui.checkpoint("T12-pdf-rendered")
        verifyExternalOriginal(file)
    }

    @Test fun T13_wavSharePlaysAndReopensOriginal() {
        val file = output("QA-T13-$run.wav", tinyWav())
        shareFile(file, "audio/wav")
        openSaved(file.name, file.name)
        ui.waitText("Play")
        ui.click("Play")
        ui.waitText("Playing audio voice note", 3000)
        ui.checkpoint("T13-wav-playing")
        ui.click("Pause")
        verifyExternalOriginal(file)
    }

    @Test fun T15_multipleProviderFilesWithClipDataSaveBothAndReopen() {
        val marker = "QA-T15-$run"
        val files = listOf(assetFile("$marker-a.jpg", "IMG-20261017-WA0001.jpg"), assetFile("$marker-b.png", "IMG-20261017-WA0002.png"))
        val uris = files.map(::uri)
        send(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").apply {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = clips(uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        savePreview(attachments = 2)
        library()
        ui.edit(SEARCH, marker)
        ui.await("Both files must appear as two saved logical items", 45000) { ui.exact("2 items • Saved Date") != null }
        ui.checkpoint("T15-two-items")
        files.forEach { file -> openSaved(file.name, file.name); verifyExternalOriginal(file) }
    }

    @Test fun T13_generatedMp4ShareShowsThumbnailAndReopensExactOriginal() {
        val file = tinyMp4("QA-video-${SystemClock.elapsedRealtime()}.mp4")
        shareFile(file, "video/mp4")
        openSaved(file.name, file.name)
        ui.waitText("VIDEO")
        ui.waitText("Video thumbnail", 30000)
        ui.waitText("Play")
        ui.checkpoint("T13-video-thumbnail-external-playback-unclaimed")
        verifyExternalOriginal(file)
    }

    @Test fun T14_webpImageAndStickerNamedWebpClassifyAndPreserveOriginals() {
        val marker = "QA-webp-${SystemClock.elapsedRealtime()}"
        val bytes = tinyWebp()
        listOf("$marker-image.webp" to "PHOTO", "$marker-sticker.webp" to "STICKER").forEach { (name, kind) ->
            val file = output(name, bytes)
            shareFile(file, "image/webp")
            openSaved(file.name, file.name)
            ui.waitText(kind)
            ui.await("Saved WebP must expose its image preview") {
                ui.nodes().any { it.isVisibleToUser && it.contentDescription?.toString() == file.name }
            }
            ui.checkpoint("T14-$kind-webp-preview-visual-decode-unclaimed")
            verifyExternalOriginal(file)
        }
    }

    @Test fun T14_corruptAudioAndUnsupportedVideoContainerFallBackWithoutLosingOriginals() {
        val marker = "QA-corrupt-${SystemClock.elapsedRealtime()}"
        // Ingest preserves bytes; preview decoding, rather than capture, rejects these artificial containers.
        val cases = listOf(
            Triple(output("$marker.wav", "$marker not a WAV container".toByteArray()), "audio/wav", "Audio preview unavailable. Use Open to play original."),
            Triple(output("$marker.mp4", "$marker not an MP4 container".toByteArray()), "video/mp4", "Saved Video Recording")
        )
        cases.forEach { (file, mime, fallback) ->
            shareFile(file, mime)
            openSaved(file.name, file.name)
            ui.waitText(if (file.extension == "wav") "AUDIO" else "VIDEO")
            ui.waitText(fallback, 30000)
            ui.checkpoint("T14-${file.extension}-unavailable-preview-original-retained")
            verifyExternalOriginal(file)
        }
        launchProduct()
        library()
        assertSearchResults(marker, 2)
        ui.checkpoint("T14-failed-preview-records-remain-searchable")
    }

    @Test fun T15_partialProviderFailureKeepsReadableOriginalAndReportsOneFailure() {
        val marker = "QA-partial-${SystemClock.elapsedRealtime()}"
        val good = assetFile("$marker-good.png", "IMG-20261017-WA0002.png")
        val vanished = output("$marker-vanished.png", byteArrayOf(1, 2, 3))
        library()
        val before = libraryCount()
        sendFiles(listOf(uri(good), uri(vanished)))
        ui.waitText("2 file(s) attached")
        check(vanished.delete()) { "Could not remove the harness-owned source before capture" }
        savePreview(attachments = 2)
        ui.waitText("Saved 1 item(s). 1 file(s) could not be saved; share them again.", 45000)
        library()
        awaitLibraryCount(before + 1)
        assertSearchResults(vanished.name, 0)
        openSaved(good.name, good.name)
        verifyExternalOriginal(good)
        launchProduct()
        library()
        assertSearchResults(marker, 1)
        ui.checkpoint("T15-one-provider-failed-one-original-searchable")
    }

    @Test fun T15_repeatUriWithinBatchDeduplicatesButSeparateShareCreatesNewLogicalItem() {
        val marker = "QA-repeat-${SystemClock.elapsedRealtime()}"
        val file = assetFile("$marker.png", "IMG-20261017-WA0002.png")
        library()
        val before = libraryCount()
        sendFiles(listOf(uri(file), uri(file)))
        savePreview(attachments = 1)
        ui.waitText("Saved 1 item(s) to your library.", 45000)
        library()
        awaitLibraryCount(before + 1)
        openSaved(file.name, file.name)
        verifyExternalOriginal(file)
        shareFile(file, "image/png")
        ui.waitText("Saved 1 item(s) to your library.", 45000)
        library()
        awaitLibraryCount(before + 2)
        // Regular capture deliberately creates one logical item per separate share, even for identical bytes.
        repeat(2) { index ->
            launchProduct()
            library()
            assertSearchResults(marker, 2)
            // A photo card can expose the filename as both its title and its extracted body.
            val cards = ui.nodes().filter { it.isVisibleToUser && !it.isEditable && it.text?.toString() == file.name }
                .mapNotNull { title -> generateSequence(title) { it.parent }.firstOrNull { it.isLongClickable } }
                .distinctBy { node -> Rect().also(node::getBoundsInScreen) }
                .sortedWith(compareBy(
                    { node -> Rect().also(node::getBoundsInScreen).top },
                    { node -> Rect().also(node::getBoundsInScreen).left }
                ))
            check(cards.size == 2) { "Both separately saved logical records must have distinct visible cards" }
            ui.clickNode(cards[index])
            ui.waitText("Edit Item")
            verifyExternalOriginal(file)
        }
        ui.checkpoint("T15-two-logical-records-identical-external-originals")
    }

    private fun launchProduct() {
        // The byte-verifying external viewer can remain above Memora in its existing task.
        context.startActivity(Intent.makeMainActivity(ComponentName(PRODUCT, "com.example.MainActivity"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        ui.await("Memora library must be ready") { ui.exact("Library") != null && ui.nodes().any { it.packageName?.toString() == PRODUCT } }
    }

    private fun library() {
        if (instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString() != PRODUCT) launchProduct()
        // The long membership list can scroll the sheet title out of view. Wait
        // for its actual handle to disappear before another Back can leave the app.
        repeat(2) {
            if (ui.nodes().any { it.contentDescription?.toString() == "Drag handle" }) {
                ui.back(); SystemClock.sleep(200)
            }
        }
        ui.await("Owned item sheet must close before Library navigation") {
            ui.nodes().none { it.contentDescription?.toString() == "Drag handle" }
        }
        if (ui.exact("Edit Item") != null || ui.exact("Save from WhatsApp / Share") != null) {
            ui.back()
            ui.await("Item/share dialog must close") { ui.exact("Edit Item") == null && ui.exact("Save from WhatsApp / Share") == null }
        }
        ui.waitText("Library")
        ui.click("Library")
        ui.edit(SEARCH, "")
        if (ui.exact("Clear Filters") != null) ui.click("Clear Filters")
    }

    private fun send(payload: Intent, chooser: Boolean = false) {
        val intent = if (chooser) Intent.createChooser(payload, "QA synthetic share chooser") else payload.setComponent(ComponentName(PRODUCT, "com.example.MainActivity"))
        instrumentation.runOnMainSync { DonorActivity.pendingShare = intent }
        context.startActivity(Intent(context, DonorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        ui.click("SEND SYNTHETIC FIXTURE")
        if (chooser) {
            ui.waitText("Memora")
            ui.checkpoint("chooser-Memora-visible")
            ui.click("Memora")
            ui.clickIfPresent("Just once")
        }
        ui.waitText("Save from WhatsApp / Share")
    }

    private fun savePreview(text: String? = null, attachments: Int = 0) {
        text?.let { ui.waitText(it) }
        if (attachments > 0) ui.waitText("$attachments file(s) attached")
        ui.checkpoint("incoming-preview")
        ui.scrollTo("Save to Library", 40)
        ui.click("Save to Library")
        ui.await("Share preview should dismiss after Save", 45000) { ui.exact("Save from WhatsApp / Share") == null }
        ui.await("Capture must report a completed save", 45000) {
            ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.let { text ->
                text.startsWith("Saved ") && text.contains("item(s)")
            } == true }
        }
        // QA-003 remains failed; restart only after commit confirmation for independent share checks.
        Log.w("MemoraQA", "QA-003 workaround: confirmed save then restart for persisted share assertions")
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
    }

    private fun shareFile(file: File, mime: String) {
        val source = uri(file)
        send(Intent(Intent.ACTION_SEND).setType(mime).apply {
            putExtra(Intent.EXTRA_STREAM, source)
            clipData = clips(listOf(source))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        savePreview(attachments = 1)
    }

    private fun sendFiles(uris: List<Uri>) = send(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").apply {
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        clipData = clips(uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    })

    private fun libraryCount(): Int {
        var count: Int? = null
        ui.await("Current library item count must be available") {
            count = ui.nodes().mapNotNull { node ->
                if (node.isVisibleToUser) Regex("(\\d+) items • (Saved|Source) Date")
                    .matchEntire(node.text?.toString().orEmpty())?.groupValues?.get(1)?.toInt() else null
            }.singleOrNull()
            count != null && ui.exact("Searching your library…") == null
        }
        return checkNotNull(count)
    }

    private fun awaitLibraryCount(expected: Int) {
        // QA-003 remains failed; restart after the confirmed result to test independent count semantics.
        Log.w("MemoraQA", "QA-003 workaround: restart before independent batch-count assertions")
        ui.shell("am force-stop $PRODUCT")
        launchProduct()
        library()
        ui.await("Expected $expected logical items", 45000) { libraryCount() == expected }
    }

    private fun assertSearchResults(query: String, expected: Int) {
        ui.edit(SEARCH, query)
        var stableSince = 0L
        ui.await("Expected $expected settled synthetic search results", 45000) {
            val ready = ui.nodes().any { it.isVisibleToUser && it.text?.toString()?.matches(Regex("$expected items • (Saved|Source) Date")) == true } &&
                ui.exact("Searching your library…") == null
            if (!ready) stableSince = 0L else if (stableSince == 0L) stableSince = SystemClock.uptimeMillis()
            ready && SystemClock.uptimeMillis() - stableSince >= 450
        }
    }

    private fun openSaved(query: String, title: String) {
        launchProduct()
        library()
        ui.edit(SEARCH, query)
        ui.await("Expected one saved search result for synthetic record", 45000) { ui.exact("1 items • Saved Date") != null }
        ui.click(title)
        ui.waitText("Edit Item")
    }

    private fun verifyExternalOriginal(file: File) {
        val expected = hash(file.readBytes())
        check(context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().putString("sha256", expected).commit())
        try {
            ui.scrollTo("Open File")
            ui.click("Open File")
            ui.await("Original must open in synthetic byte-verifying viewer", 20000) {
                when {
                    ui.exact("QA original verified $expected") != null -> true
                    ui.clickIfPresent("Memora QA Harness") -> false
                    ui.clickIfPresent("Just once") -> false
                    else -> false
                }
            }
            ui.checkpoint("original-${file.extension}-sha256-verified")
        } finally {
            check(context.getSharedPreferences("qa_expected", Context.MODE_PRIVATE).edit().remove("sha256").commit())
        }
    }

    private fun uri(file: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    private fun clips(uris: List<Uri>) = ClipData.newRawUri("Synthetic QA fixture", uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    private fun assetFile(name: String, source: String) = output(name, instrumentation.context.assets.open("media/$source").use { it.readBytes() })
    private fun output(name: String, bytes: ByteArray) = File(context.filesDir, "synthetic-shares/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun tinyWebp(): ByteArray {
        val bitmap = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 48) for (x in 0 until 48) bitmap.setPixel(x, y,
                if ((x / 8 + y / 8) % 2 == 0) Color.rgb(24, 170, 210) else Color.rgb(230, 90, 32))
            return ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
    }

    private fun tinyMp4(name: String): File {
        val file = File(context.filesDir, "synthetic-shares/$name").apply { parentFile!!.mkdirs() }
        val codec = MediaCodec.createEncoderByType("video/avc")
        var muxer: MediaMuxer? = null
        var codecStarted = false
        var muxerStarted = false
        var complete = false
        try {
            val format = MediaFormat.createVideoFormat("video/avc", 64, 64).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, 64000)
                setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            codecStarted = true
            val writer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = writer
            var track = -1
            var frame = 0
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.uptimeMillis() + 20000
            while (!outputDone && SystemClock.uptimeMillis() < deadline) {
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(10000)
                    if (input >= 0) {
                        if (frame == 12) {
                            codec.queueInputBuffer(input, 0, 0, frame * 100000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val image = checkNotNull(codec.getInputImage(input)) { "Encoder has no flexible YUV input image" }
                            try {
                                image.planes.forEachIndexed { planeIndex, plane ->
                                    val side = if (planeIndex == 0) 64 else 32
                                    val base = plane.buffer.position()
                                    for (y in 0 until side) for (x in 0 until side) {
                                        val value = if (planeIndex == 0) 40 + (x + y + frame * 8) % 170 else if (planeIndex == 1) 90 else 170
                                        plane.buffer.put(base + y * plane.rowStride + x * plane.pixelStride, value.toByte())
                                    }
                                }
                            } finally { image.close() }
                            codec.queueInputBuffer(input, 0, 64 * 64 * 3 / 2, frame * 100000L, 0)
                            frame++
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    check(!muxerStarted)
                    track = writer.addTrack(codec.outputFormat)
                    writer.start()
                    muxerStarted = true
                } else if (output >= 0) {
                    try {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                        if (info.size > 0) {
                            check(muxerStarted && track >= 0)
                            val buffer = checkNotNull(codec.getOutputBuffer(output))
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            writer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    } finally { codec.releaseOutputBuffer(output, false) }
                }
            }
            check(outputDone && muxerStarted) { "Synthetic video encoding did not finish within its budget" }
            writer.stop()
            muxerStarted = false
            check(file.length() in 1..(2L * 1024 * 1024)) { "Synthetic video exceeds external viewer budget" }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                check((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) >= 900L)
                val thumbnail = checkNotNull(retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)) { "Generated fixture must decode before sharing" }
                thumbnail.recycle()
            } finally { retriever.release() }
            complete = true
            return file
        } finally {
            if (codecStarted) runCatching { codec.stop() }
            codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }
            muxer?.release()
            if (!complete) file.delete()
        }
    }

    private fun tinyWav(): ByteArray {
        val samples = 8000 * 5
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort((sin(2 * Math.PI * 440 * it / 8000) * 3000).toInt().toShort()) }
        }.array()
    }

    private fun tinyPdf(): ByteArray {
        val output = ByteArrayOutputStream()
        fun write(text: String) { output.write(text.toByteArray(Charsets.US_ASCII)) }
        val stream = "BT /F1 18 Tf 24 100 Td (MEMORA SYNTHETIC QA PDF) Tj ET\n"
        val objects = listOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 320 180] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>", "<< /Length ${stream.length} >>\nstream\n${stream}endstream")
        write("%PDF-1.4\n")
        val offsets = objects.mapIndexed { index, body -> output.size().also { write("${index + 1} 0 obj\n$body\nendobj\n") } }
        val xref = output.size()
        write("xref\n0 6\n0000000000 65535 f \n")
        offsets.forEach { write("%010d 00000 n \n".format(Locale.US, it)) }
        write("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        return output.toByteArray()
    }

    companion object {
        private const val PRODUCT = "com.personallibrary.app"
        private const val SEARCH = "Search notes, photos, links..."
    }
}
