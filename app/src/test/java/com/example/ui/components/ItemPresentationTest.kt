package com.example.ui.components

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.test.core.app.ApplicationProvider
import com.example.data.ItemEntity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ItemPresentationTest {
    @Test fun namedOriginalExportPreservesBytesSanitizesPathAndKeepsPreviousExportAlive() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val source = java.io.File(app.cacheDir, "synthetic-blob.bin")
        val bytes = byteArrayOf(71, 73, 70, 56, 57, 97, 0, 13, 127)
        source.writeBytes(bytes)
        val item = ItemEntity("gif", "Birthday", "", "GIF", mimeType = "image/gif", originalFileName = "../private/birthday.gif")
        val first = prepareOriginalForExport(app, item, source)
        val second = prepareOriginalForExport(app, item, source)
        try {
            assertEquals("birthday.gif", first.name)
            assertTrue(first.canonicalPath.startsWith(java.io.File(app.cacheDir, "shared_originals").canonicalPath + java.io.File.separator))
            assertArrayEquals(bytes, first.readBytes())
            assertArrayEquals(bytes, second.readBytes())
            assertNotEquals(first, second)
            assertTrue("Opening another chooser must not delete a receiver's original", first.exists())
        } finally { source.delete(); first.parentFile!!.deleteRecursively(); second.parentFile!!.deleteRecursively() }
    }

    @Test fun extensionlessOriginalUsesItsKnownMimeExtension() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        // Robolectric's MIME map starts empty; Android's framework provides this mapping.
        org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowMimeTypeMap>(android.webkit.MimeTypeMap.getSingleton())
            .addExtensionMimeTypeMapping("pdf", "application/pdf")
        val source = java.io.File(app.cacheDir, "synthetic-no-extension.bin").apply { writeText("synthetic") }
        val exported = prepareOriginalForExport(app, ItemEntity("pdf", "Document", "", "DOCUMENT", mimeType = "application/pdf", originalFileName = "report"), source)
        try { assertEquals("report.pdf", exported.name) }
        finally { source.delete(); exported.parentFile!!.deleteRecursively() }
    }

    @Test fun legacyGenericOriginalMimeUsesItsKnownFilenameOrProviderType() {
        assertEquals("image/jpeg", originalMimeType(ItemEntity("jpg", "Photo", "", "PHOTO",
            mimeType = "application/octet-stream", originalFileName = "photo.jpg"), "image/jpeg"))
        assertEquals("image/png", originalMimeType(ItemEntity("png", "Photo", "", "PHOTO",
            mimeType = "application/octet-stream", originalFileName = "unknown"), "image/png"))
        assertEquals("image/gif", originalMimeType(ItemEntity("gif", "Animation", "", "GIF",
            mimeType = null, originalFileName = "original.gif"), null))
    }

    @Test fun explicitOriginalMimeIsPreservedAndUnknownOriginalStaysGeneric() {
        assertEquals("application/x-synthetic", originalMimeType(ItemEntity("document", "Document", "", "DOCUMENT",
            mimeType = "application/x-synthetic", originalFileName = "report.pdf"), "application/pdf"))
        assertEquals("application/octet-stream", originalMimeType(ItemEntity("unknown", "Unknown", "", "FILE",
            mimeType = "application/octet-stream", originalFileName = "unknown"), null))
    }

    @Test fun kindLabelsMeetTextContrastOnTheirCardAndTintedBadgeInBothThemes() {
        for (dark in listOf(false, true)) {
            for (kind in listOf("PHOTO", "STICKER", "GIF", "VIDEO", "AUDIO", "TEXT", "LINK", "DOCUMENT", "UNKNOWN")) {
                val foreground = kindColorForTheme(kind, dark)
                val surfaces = if (dark) listOf(Color(0xFF1E293B)) else listOf(Color.White, Color(0xFFF1F5F9))
                for (surface in surfaces) {
                    for (background in listOf(surface, foreground.copy(alpha = 0.15f).compositeOver(surface))) {
                        val first = foreground.luminance()
                        val second = background.luminance()
                        val ratio = (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
                        assertTrue("$kind contrast $ratio in ${if (dark) "dark" else "light"} theme", ratio >= 4.5f)
                    }
                }
            }
        }
    }

    @Test fun audioUsesHeadphonesInsteadOfReminderIcon() {
        assertEquals(Icons.Filled.Headphones, getKindIcon("AUDIO"))
    }

    @Test fun linkUsesLinkInsteadOfSharingActionIcon() {
        assertEquals(Icons.Filled.Link, getKindIcon("LINK"))
    }

    @Test fun documentUsesDocumentInsteadOfInformationIcon() {
        assertEquals(Icons.Filled.Description, getKindIcon("DOCUMENT"))
    }

    @Test fun textAndLinkSharingKeepTheOriginalPayloadInASystemChooser() {
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            var launched: Intent? = null
            override fun startActivity(intent: Intent) { launched = intent }
        }
        for (kind in listOf("TEXT", "LINK")) {
            val content = if (kind == "LINK") "https://example.test/synthetic" else "Synthetic body"
            val item = ItemEntity("synthetic-share", "Synthetic title", content, kind)
            shareItemEntity(context, item, null)
            val chooser = requireNotNull(context.launched)
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(item.title, send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(if (kind == "LINK") content else "${item.title}\n\n$content", send.getStringExtra(Intent.EXTRA_TEXT))
        }
    }
}
