package com.personallibrary.core

import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.migration.WhatsAppExportParser
import com.personallibrary.core.model.ItemKind
import com.personallibrary.core.storage.ContentClassifier
import com.personallibrary.core.storage.ManagedAssetStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WhatsAppMediaRegressionTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun suppliedGifIsRecognizedAsAnAttachment() {
        val result = WhatsAppExportParser.parse("17/10/2026, 14:06 - Nour: GIF-20261017-WA0003.gif (file attached)", DateOrder.DAY_FIRST)
        assertEquals("GIF-20261017-WA0003.gif", result.messages.single().attachmentFileName)
        assertEquals(1, result.mediaReferencesCount)
    }

    @Test fun photosWithoutProviderMimeKeepTheirMediaClassification() {
        for (name in listOf("photo.jpg", "photo.jpeg", "photo.png")) {
            assertEquals(name, ItemKind.PHOTO, ContentClassifier.classify(null, name))
            assertEquals(name, ItemKind.PHOTO, ContentClassifier.classify("application/octet-stream", name))
        }
    }

    @Test fun originalsWithoutProviderMimeKeepAnOpenableMediaMime() {
        val store = ManagedAssetStore(temp.root.toPath())
        for ((name, mime) in mapOf("photo.jpg" to "image/jpeg", "photo.png" to "image/png", "animation.gif" to "image/gif")) {
            val stored = store.import("synthetic $name".byteInputStream(), name, null)
            assertEquals(name, mime, stored.mimeType)
            assertEquals(name, if (name.endsWith(".gif")) ItemKind.GIF else ItemKind.PHOTO, stored.kind)
        }
        assertEquals("application/octet-stream", store.import("unknown".byteInputStream(), "unknown.bin", null).mimeType)
        assertEquals("text/plain", store.import("declared".byteInputStream(), "declared.jpg", "text/plain").mimeType)
        assertEquals("TEXT/PLAIN; charset=UTF-8", store.import("declared parameters".byteInputStream(), "declared-parameters.jpg", "TEXT/PLAIN; charset=UTF-8").mimeType)
    }
}
