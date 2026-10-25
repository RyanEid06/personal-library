package com.example.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.ItemEntity
import com.example.ui.components.shareItemEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android paths exercise FileProvider, which cannot resolve Windows filesystem separators in Robolectric. */
@RunWith(AndroidJUnit4::class)
class OriginalExportTest {
    @Test fun originalsShareWithNamedReadableStreamMimeClipAndExactBytes() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val context = object : ContextWrapper(app) {
            var launched: Intent? = null
            override fun startActivity(intent: Intent) { launched = intent }
        }
        val source = java.io.File(app.filesDir, "storage_root/blobs/synthetic-original.bin")
        source.parentFile!!.mkdirs()
        for ((name, mime, kind) in listOf(
            Triple("synthetic-birthday.gif", "image/gif", "GIF"),
            Triple("synthetic-photo.jpg", "image/jpeg", "PHOTO"),
            Triple("synthetic-image.png", "image/png", "PHOTO"),
            Triple("synthetic-sticker.webp", "image/webp", "STICKER"),
            Triple("synthetic-document.pdf", "application/pdf", "DOCUMENT"),
            Triple("synthetic-audio.mp3", "audio/mpeg", "AUDIO"),
            Triple("synthetic-video.mp4", "video/mp4", "VIDEO")
        )) {
            for (declaredMime in listOf(mime, "application/octet-stream", null)) {
                val bytes = "Synthetic original bytes for $name\u0000\r\n".toByteArray()
                source.writeBytes(bytes)
                var exportedDirectory: java.io.File? = null
                try {
                    shareItemEntity(context, ItemEntity(name, "Synthetic export", "", kind,
                        mimeType = declaredMime, originalFileName = name), source)
                    @Suppress("DEPRECATION")
                    val send = context.launched!!.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                    @Suppress("DEPRECATION")
                    val uri = send.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)!!
                    exportedDirectory = java.io.File(app.cacheDir, "shared_originals/${uri.pathSegments[1]}")
                    assertEquals(mime, send.type)
                    assertEquals(mime, app.contentResolver.getType(uri))
                    assertEquals(uri, send.clipData!!.getItemAt(0).uri)
                    assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                    app.contentResolver.query(uri, null, null, null, null)!!.use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(name, cursor.getString(cursor.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME)))
                    }
                    assertArrayEquals(bytes, app.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                } finally { source.delete(); exportedDirectory?.deleteRecursively() }
            }
        }
    }

}
