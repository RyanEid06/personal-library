package com.personallibrary.core

import com.personallibrary.core.backup.*
import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.migration.WhatsAppExportParser
import com.personallibrary.core.model.ItemKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.TimeZone

class WhatsAppExportParserTest {

    private val sampleChat = """
        [13/10/2026, 14:30:15] Messages and calls are end-to-end encrypted. No one outside of this chat, not even WhatsApp, can read or listen to them.
        [13/10/2026, 14:32:10] Ryan: Here is the chemistry syllabus https://chem.university.edu/syllabus
        [13/10/2026, 14:35:22] Ryan: Grade for midterm: 94/100
        This was the hardest exam of the semester.
        [13/10/2026, 14:40:00] Ryan: <Media omitted>
        [13/10/2026, 14:42:00] Ryan: chemistry_grades_sheet.pdf (file attached)
    """.trimIndent()

    @Test
    fun testMultilineAndSystemMessageParsing() {
        val result = WhatsAppExportParser.parse(sampleChat, timeZone = TimeZone.getTimeZone("UTC"))

        assertEquals(5, result.totalMessages)
        assertEquals(1, result.systemMessageCount)

        // Find the multiline message
        val multiline = result.messages.find { it.rawText.contains("Grade for midterm") }
        assertNotNull(multiline)
        assertTrue(multiline!!.rawText.contains("This was the hardest exam of the semester."))
        assertEquals(ItemKind.TEXT, multiline.kind)

        // Link message
        val linkMsg = result.messages.find { it.extractedUrl != null }
        assertNotNull(linkMsg)
        assertEquals(ItemKind.LINK, linkMsg!!.kind)
        assertEquals("https://chem.university.edu/syllabus", linkMsg.extractedUrl)

        // Media attachment
        val pdfMsg = result.messages.find { it.attachmentFileName == "chemistry_grades_sheet.pdf" }
        assertNotNull(pdfMsg)
        assertEquals(ItemKind.DOCUMENT, pdfMsg!!.kind)
    }

    @Test
    fun testAmbiguousDateNeverGuessesToday() {
        // 05/06/2026 could be May 6 or June 5. Without specified DateOrder, must NOT guess!
        val ambiguousChat = """
            05/06/2026, 10:15 - Ryan: Ambiguous date test
        """.trimIndent()

        val result = WhatsAppExportParser.parse(ambiguousChat, dateOrder = null)
        val msg = result.messages.first()

        assertTrue("Date must be flagged as ambiguous", msg.isDateAmbiguous)
        assertNull("Ambiguous date must not be parsed to a guess", msg.parsedTimestamp)

        // Now resolve with DateOrder.DAY_FIRST (5 June)
        val resultDayFirst = WhatsAppExportParser.parse(ambiguousChat, dateOrder = DateOrder.DAY_FIRST)
        assertFalse(resultDayFirst.messages.first().isDateAmbiguous)
        assertNotNull(resultDayFirst.messages.first().parsedTimestamp)

        // Now resolve with DateOrder.MONTH_FIRST (6 May)
        val resultMonthFirst = WhatsAppExportParser.parse(ambiguousChat, dateOrder = DateOrder.MONTH_FIRST)
        assertFalse(resultMonthFirst.messages.first().isDateAmbiguous)
        assertNotNull(resultMonthFirst.messages.first().parsedTimestamp)
        assertNotEquals(resultDayFirst.messages.first().parsedTimestamp, resultMonthFirst.messages.first().parsedTimestamp)
    }

    @Test
    fun testIdempotentImportKeys() {
        val result1 = WhatsAppExportParser.parse(sampleChat)
        val result2 = WhatsAppExportParser.parse(sampleChat)

        assertEquals(result1.messages.size, result2.messages.size)
        for (i in result1.messages.indices) {
            assertEquals(result1.messages[i].importKey, result2.messages[i].importKey)
        }
    }

    @Test
    fun testEqualMonthAndDayDateNotAmbiguous() {
        // 10/10/2026 has equal day and month (10 and 10), so both orders yield Oct 10. Must NOT be marked ambiguous!
        val chat = """
            10/10/2026, 12:00 - Ryan: Test message on tenth of October
        """.trimIndent()

        val result = WhatsAppExportParser.parse(chat, dateOrder = null, timeZone = TimeZone.getTimeZone("UTC"))
        val msg = result.messages.first()

        assertFalse("10/10/2026 must not be ambiguous", msg.isDateAmbiguous)
        assertNotNull("10/10/2026 must have a parsed timestamp", msg.parsedTimestamp)
    }
}

class EncryptedBackupTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun testEncryptedBackupRoundTrip() {
        val engine = EncryptedBackupEngine()
        val passphrase = "SecretPassword123!".toCharArray()

        // Create a test asset file
        val assetFile = tempFolder.newFile("sample_asset.jpg")
        val sampleBytes = "Fake image binary data 123456789".toByteArray()
        assetFile.writeBytes(sampleBytes)

        val hash = "1b645fd236f1ba230790cf5c22e20440e1e54ebe017ae02f1387ef712e0f35ac"

        val manifest = BackupManifest(
            items = listOf(
                BackupItemRecord(
                    id = "item_1",
                    title = "Chemistry Midterm",
                    content = "Score: 94/100",
                    kind = ItemKind.TEXT,
                    isFavorite = true,
                    isTrash = false,
                    trashTimestamp = null,
                    createdAt = 1700000000000L,
                    sourceDate = null,
                    reminderTime = null,
                    isReminderDone = false,
                    contentHash = hash,
                    originalFileName = "sample_asset.jpg",
                    mimeType = "image/jpeg",
                    fileSizeBytes = sampleBytes.size.toLong(),
                    aiDescription = "Academic midterm grades note",
                    aiConcepts = "chemistry, grades",
                    aiStatus = "READY"
                )
            ),
            collections = listOf(
                BackupCollectionRecord("col_uni", "University", "school", "#3B82F6", 1700000000000L)
            ),
            memberships = listOf(
                BackupMembershipRecord("item_1", "col_uni")
            ),
            reminders = emptyList(),
            assets = listOf(
                BackupAssetRecord(hash, "blobs/e3/$hash", sampleBytes.size.toLong(), "image/jpeg")
            )
        )

        val baos = ByteArrayOutputStream()
        engine.exportArchive(baos, passphrase, manifest) { h -> if (h == hash) assetFile else null }

        val encryptedBytes = baos.toByteArray()
        assertTrue(encryptedBytes.isNotEmpty())

        // Test restore inspection
        val preview = engine.inspectAndStage(
            ByteArrayInputStream(encryptedBytes),
            passphrase,
            tempFolder.root.toPath(),
            existingItemIds = emptySet()
        )

        assertEquals(1, preview.totalItems)
        assertEquals(1, preview.totalCollections)
        assertEquals(1, preview.totalAssets)
        assertEquals("Chemistry Midterm", preview.manifest.items.first().title)

        // Verify extracted asset
        val extractedAsset = File(preview.stagingDir, "assets/$hash")
        assertTrue(extractedAsset.exists())
        assertEquals(sampleBytes.size.toLong(), extractedAsset.length())

        preview.stagingDir.deleteRecursively()
    }

    @Test
    fun testWrongPasswordRejection() {
        val engine = EncryptedBackupEngine()
        val correctPass = "Correct123".toCharArray()
        val wrongPass = "WrongPassword999".toCharArray()

        val manifest = BackupManifest(items = emptyList(), collections = emptyList(), memberships = emptyList(), reminders = emptyList(), assets = emptyList())
        val baos = ByteArrayOutputStream()
        engine.exportArchive(baos, correctPass, manifest) { null }

        try {
            engine.inspectAndStage(
                ByteArrayInputStream(baos.toByteArray()),
                wrongPass,
                tempFolder.root.toPath(),
                existingItemIds = emptySet()
            )
            fail("Expected SecurityException on wrong password")
        } catch (e: SecurityException) {
            // Success: AEADBadTagException caught and reported as SecurityException
            assertTrue(e.message!!.contains("Invalid password", ignoreCase = true))
        }
    }

    @Test
    fun testTamperedCiphertextRejection() {
        val engine = EncryptedBackupEngine()
        val pass = "Password123".toCharArray()

        val manifest = BackupManifest(items = emptyList(), collections = emptyList(), memberships = emptyList(), reminders = emptyList(), assets = emptyList())
        val baos = ByteArrayOutputStream()
        engine.exportArchive(baos, pass, manifest) { null }

        val bytes = baos.toByteArray()
        // Tamper with byte in the ciphertext payload
        bytes[bytes.size - 5] = (bytes[bytes.size - 5] + 1).toByte()

        try {
            engine.inspectAndStage(
                ByteArrayInputStream(bytes),
                pass,
                tempFolder.root.toPath(),
                existingItemIds = emptySet()
            )
            fail("Expected SecurityException on tampered ciphertext")
        } catch (e: SecurityException) {
            // Success: GCM Authentication tag fails
        }
    }
}
