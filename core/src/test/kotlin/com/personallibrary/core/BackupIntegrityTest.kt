package com.personallibrary.core

import com.personallibrary.core.backup.*
import com.google.crypto.tink.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.*
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.*

class BackupIntegrityTest {
    @Rule @JvmField val temp = TemporaryFolder()
    private val pass = "backup-regression-password".toCharArray()
    private val engine = EncryptedBackupEngine()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun manifest(assets: List<BackupAssetRecord>) = BackupManifest(items = emptyList(), collections = emptyList(), memberships = emptyList(), reminders = emptyList(), assets = assets)
    private fun asset(bytes: ByteArray) = BackupAssetRecord(hash(bytes), "unused-path", bytes.size.toLong(), "application/octet-stream")
    private fun export(bytes: ByteArray): Pair<ByteArray, BackupManifest> {
        val record = asset(bytes)
        val file = temp.newFile().apply { writeBytes(bytes) }
        val data = ByteArrayOutputStream()
        val manifest = manifest(listOf(record))
        engine.exportArchive(data, pass, manifest) { file }
        return data.toByteArray() to manifest
    }

    @Test fun missingOriginalFailsExport() {
        assertThrows(IOException::class.java) {
            engine.exportArchive(ByteArrayOutputStream(), pass, manifest(listOf(asset("expected".toByteArray())))) { null }
        }
    }

    @Test fun changedOriginalSizeFailsExport() {
        val bytes = "expected".toByteArray()
        val file = temp.newFile().apply { writeText("short") }
        assertThrows(IOException::class.java) {
            engine.exportArchive(ByteArrayOutputStream(), pass, manifest(listOf(asset(bytes)))) { file }
        }
    }

    @Test fun changedOriginalHashFailsExport() {
        val bytes = "expected".toByteArray()
        val file = temp.newFile().apply { writeText("modified") }
        assertThrows(IOException::class.java) {
            engine.exportArchive(ByteArrayOutputStream(), pass, manifest(listOf(asset(bytes)))) { file }
        }
    }

    @Test fun inspectRejectsFrameNotDeclaredInManifest() {
        val (backup, _) = export("payload".toByteArray())
        reject(rewriteManifest(backup, manifest(emptyList())))
    }

    @Test fun inspectRejectsMissingFrame() {
        val (backup, original) = export("payload".toByteArray())
        reject(rewriteManifest(backup, original.copy(assets = original.assets + asset("missing".toByteArray()))))
    }

    @Test fun inspectRejectsManifestSizeMismatch() {
        val (backup, original) = export("payload".toByteArray())
        reject(rewriteManifest(backup, original.copy(assets = original.assets.map { it.copy(sizeBytes = it.sizeBytes + 1) })))
    }

    @Test fun inspectRejectsDuplicateManifestAsset() {
        val (backup, original) = export("payload".toByteArray())
        reject(rewriteManifest(backup, original.copy(assets = original.assets + original.assets)))
    }

    @Test fun emptyOriginalRoundTripsWithVerifiedHash() {
        val (backup, original) = export(byteArrayOf())
        val preview = engine.inspectAndStage(backup.inputStream(), pass, temp.root.toPath(), emptySet())
        val file = File(preview.stagingDir, "assets/${original.assets.single().hash}")
        assertTrue(file.isFile)
        assertEquals(0L, file.length())
    }

    @Test fun dateOnlyReminderFieldsSurvivePortableJson() {
        val json = Json { ignoreUnknownKeys = true }
        val record = json.decodeFromString<BackupReminderRecord>("""{"id":"r","itemId":"i","title":"Due","dueMillis":1,"isDone":false,"isDateOnly":true,"localDate":"2026-10-25"}""")
        val encoded = json.encodeToString(record)
        assertTrue(encoded.contains("\"isDateOnly\":true"))
        assertTrue(encoded.contains("\"localDate\":\"2026-10-25\""))
        val oldRecord = json.decodeFromString<BackupReminderRecord>("""{"id":"r","itemId":"i","title":"Due","dueMillis":1,"isDone":false}""")
        assertFalse(json.encodeToString(oldRecord).contains("\"isDateOnly\":true"))
    }

    @Test fun dateOnlyItemFieldsSurvivePortableJson() {
        val json = Json { ignoreUnknownKeys = true }
        val record = json.decodeFromString<BackupItemRecord>("""{"id":"i","title":"Due","content":"","kind":"TEXT","isFavorite":false,"isTrash":false,"trashTimestamp":null,"createdAt":1,"sourceDate":null,"reminderTime":1,"isReminderDone":false,"contentHash":null,"originalFileName":null,"mimeType":null,"fileSizeBytes":0,"aiDescription":null,"aiConcepts":null,"aiStatus":"PENDING","isReminderDateOnly":true,"reminderLocalDate":"2026-10-25"}""")
        val encoded = json.encodeToString(record)
        assertTrue(encoded.contains("\"isReminderDateOnly\":true"))
        assertTrue(encoded.contains("\"reminderLocalDate\":\"2026-10-25\""))
    }

    private fun reject(bytes: ByteArray) {
        assertThrows(SecurityException::class.java) {
            engine.inspectAndStage(bytes.inputStream(), pass, temp.root.toPath(), emptySet())
        }
        assertTrue("Rejected restore must clean staging", temp.root.listFiles()!!.none { it.name.startsWith("restore_stage_") })
    }

    // Re-authenticate a deliberately inconsistent inner archive using the real format.
    // This tests structural validation independently from AEAD tamper detection.
    private fun rewriteManifest(backup: ByteArray, replacement: BackupManifest): ByteArray {
        val input = DataInputStream(backup.inputStream())
        val magic = ByteArray(8).also(input::readFully)
        val salt = ByteArray(16).also(input::readFully)
        val iv = ByteArray(12).also(input::readFully)
        val wrapped = ByteArray(input.readInt()).also(input::readFully)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, 600_000, 256)).encoded
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)); updateAAD(magic)
        }
        val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(cipher.doFinal(wrapped)))
        val aead = handle.getPrimitive(StreamingAead::class.java)
        val archive = DataInputStream(aead.newDecryptingStream(input, magic + salt))
        val prefix = ByteArray(12).also(archive::readFully)
        val oldManifest = ByteArray(archive.readInt()).also(archive::readFully)
        assertTrue(oldManifest.isNotEmpty())
        val rest = archive.readBytes()
        val output = ByteArrayOutputStream()
        output.write(backup, 0, 40 + wrapped.size)
        DataOutputStream(aead.newEncryptingStream(output, magic + salt)).use {
            it.write(prefix)
            val json = Json.encodeToString(replacement).toByteArray()
            it.writeInt(json.size); it.write(json); it.write(rest)
        }
        return output.toByteArray()
    }
}
