package com.personallibrary.core

import com.personallibrary.core.backup.EncryptedBackupEngine
import com.personallibrary.core.backup.BackupManifest
import com.personallibrary.core.storage.ContentClassifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

class OwnerFeedbackMediaAndBackupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun whatsAppStickerNamesAreNotOrdinaryPhotos() {
        assertEquals("STICKER", ContentClassifier.classify("image/webp", "STK-20261024-WA0019.webp").name)
        assertEquals("STICKER", ContentClassifier.classify(null, "stk-20261024-wa0019.WEBP").name)
        assertEquals("PHOTO", ContentClassifier.classify("image/webp", "holiday.webp").name)
    }

    @Test fun identifiableGifHasItsOwnTypeButAnAmbiguousMp4StaysVideo() {
        assertEquals("GIF", ContentClassifier.classify("image/gif", "animation.gif").name)
        assertEquals("GIF", ContentClassifier.classify(null, "GIF-20261024.gif").name)
        assertEquals("VIDEO", ContentClassifier.classify("video/mp4", "animation.mp4").name)
    }

    @Test fun eightCharacterPasswordEncryptsAndSevenDoesNot() {
        val engine = EncryptedBackupEngine()
        val output = ByteArrayOutputStream()
        val manifest = BackupManifest(items = emptyList(), collections = emptyList(), memberships = emptyList(), reminders = emptyList(), assets = emptyList())
        engine.exportArchive(output, "12345678".toCharArray(), manifest) { null }
        assertTrue(output.size() > 0)
        try {
            engine.exportArchive(ByteArrayOutputStream(), "1234567".toCharArray(), manifest) { null }
            fail("Seven-character passwords must remain rejected")
        } catch (_: IllegalArgumentException) { }
    }
}
