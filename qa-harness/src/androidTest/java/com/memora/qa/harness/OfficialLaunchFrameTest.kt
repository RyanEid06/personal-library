package com.memora.qa.harness

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Decode only a host-owned synthetic launch recording; decoded frames require visual review. */
@RunWith(AndroidJUnit4::class)
class OfficialLaunchFrameTest {
    @Test fun T43_decodeOwnedColdLaunchRecordingWithoutSkippingFrames() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val arguments = InstrumentationRegistry.getArguments()
        check(arguments.getString("decodeSyntheticLaunchRecording") == "true")
        val name = arguments.getString("recordingName").orEmpty()
        check(name.matches(Regex("memora-qa-launch-(light|dark)-[0-9-]+\\.mp4")))
        val expectedHash = arguments.getString("recordingSha256").orEmpty()
        check(expectedHash.matches(Regex("[0-9a-fA-F]{64}"))) { "Host-recorded video SHA256 required" }
        val evidence = File(context.filesDir, "qa-evidence/T43-frames-${name.removeSuffix(".mp4")}")
        check(!evidence.exists()) { "Never overwrite decoded launch evidence" }
        val ui = ReleaseUi(instrumentation.uiAutomation, evidence)
        check(ui.shell("getprop ro.kernel.qemu").trim() == "1")
        val avds = listOf("ro.boot.qemu.avd_name", "ro.kernel.qemu.avd_name")
            .map { ui.shell("getprop $it").trim() }.filter { it.isNotEmpty() }
        check(avds.isNotEmpty() && avds.all { it == "Memora-QA-API37" })
        requireOfficialOrPinnedCandidate(context, "Launch recording decode")
        val source = "/sdcard/Download/$name"
        val size = ui.shell("stat -c %s $source").trim().toLongOrNull() ?: error("Owned recording missing")
        check(size in 100..16L * 1024 * 1024) { "Recording outside bounded synthetic byte budget" }
        check(evidence.mkdirs())
        val local = File(context.cacheDir, name)
        check(!local.exists()) { "Owned decoder cache must be fresh" }
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("cat $source")).use { input ->
                local.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        check(copied <= size) { "Recording grew during bounded copy" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    check(copied == size) { "Incomplete recording read" }
                }
            }
            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            check(actualHash.equals(expectedHash, ignoreCase = true)) { "Video differs from the host-recorded artifact" }
            MediaMetadataRetriever().use { video ->
                video.setDataSource(local.absolutePath)
                val duration = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                val count = checkNotNull(video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)).toInt()
                check(duration in 1000..15000 && count in 1..450) { "Unexpected launch recording duration/frame count" }
                val inventory = StringBuilder("source_sha256=$actualHash\nsource_bytes=$size\nduration_ms=$duration\nframes=$count\nNo visual pass is implied by decoding.\n")
                var outputBytes = 0L
                var decodedPixels = 0L
                for (index in 0 until count) {
                    val frame = checkNotNull(video.getFrameAtIndex(index)) { "A recorded launch frame could not be decoded" }
                    try {
                        check(frame.width in 200..1920 && frame.height in 200..1920)
                        decodedPixels += frame.width.toLong() * frame.height
                        check(decodedPixels <= 450_000_000L) { "Decoded pixel budget exceeded; capture is incomplete" }
                        val frameFile = File(evidence, "frame-%04d.png".format(index))
                        frameFile.outputStream().use {
                            check(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        if (outputBytes + frameFile.length() > 128L * 1024 * 1024) {
                            check(frameFile.delete())
                            error("PNG evidence budget exceeded; capture is incomplete")
                        }
                        outputBytes += frameFile.length()
                        inventory.append("frame-%04d.png\n".format(index))
                    } finally { frame.recycle() }
                }
                File(evidence, "frame-inventory.txt").writeText(inventory.toString())
            }
        } finally { check(local.delete()) { "Owned decoder cache cleanup failed" } }
    }
}
