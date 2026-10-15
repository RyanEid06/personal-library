package com.personallibrary.core.backup

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.streamingaead.StreamingAeadConfig
import com.personallibrary.core.model.ItemKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupManifest(
    val version: Int = 1,
    val app: String = "Personal Library",
    val exportTimestamp: Long = System.currentTimeMillis(),
    val items: List<BackupItemRecord>,
    val collections: List<BackupCollectionRecord>,
    val memberships: List<BackupMembershipRecord>,
    val reminders: List<BackupReminderRecord>,
    val assets: List<BackupAssetRecord>
)

@Serializable
data class BackupItemRecord(
    val id: String,
    val title: String,
    val content: String,
    val kind: ItemKind,
    val isFavorite: Boolean,
    val isTrash: Boolean,
    val trashTimestamp: Long?,
    val createdAt: Long,
    val sourceDate: Long?,
    val reminderTime: Long?,
    val isReminderDone: Boolean,
    val isReminderExact: Boolean = true,
    val contentHash: String?,
    val originalFileName: String?,
    val mimeType: String?,
    val fileSizeBytes: Long,
    val extractedText: String? = null,
    val aiDescription: String?,
    val aiConcepts: String?,
    val aiStatus: String,
    val isReminderDateOnly: Boolean = false,
    val reminderLocalDate: String? = null
)

@Serializable
data class BackupCollectionRecord(
    val id: String,
    val name: String,
    val iconName: String,
    val colorHex: String,
    val createdAt: Long
)

@Serializable
data class BackupMembershipRecord(
    val itemId: String,
    val collectionId: String
)

@Serializable
data class BackupReminderRecord(
    val id: String,
    val itemId: String,
    val title: String,
    val dueMillis: Long,
    val isDone: Boolean,
    val isExact: Boolean = true,
    val isDateOnly: Boolean = false,
    val localDate: String? = null
)

@Serializable
data class BackupAssetRecord(
    val hash: String,
    val relativePath: String,
    val sizeBytes: Long,
    val mimeType: String,
    val originalName: String? = null,
    val kind: String? = null
)

data class RestorePreview(
    val manifest: BackupManifest,
    val stagingDir: File,
    val totalItems: Int,
    val totalCollections: Int,
    val totalAssets: Int,
    val existingDuplicateCount: Int
)

/**
 * Hardened backup engine implementing the Codex format principles:
 * - Minimum 12-character passphrase
 * - PBKDF2-HMAC-SHA256 with 600,000 iterations for master key derivation
 * - Tink AES-256-GCM key wrapping
 * - Tink AES256_GCM_HKDF_1MB streaming AEAD
 * - Fresh salt/keyset per export
 * - Authenticated prefix PLIBBK01
 * - Framed PLIBAR01 inner archive (fixed magic, exact 32-byte SHA-256 binary hashes, declared lengths, strict end marker)
 * - Zero reliance on vulnerable ZIP path extraction
 */
class EncryptedBackupEngine {

    companion object {
        init {
            StreamingAeadConfig.register()
        }

        val ENVELOPE_MAGIC = "PLIBBK01".toByteArray(Charsets.US_ASCII)
        val ARCHIVE_MAGIC = "PLIBAR01".toByteArray(Charsets.US_ASCII)
        val ARCHIVE_END_MARKER = "PLIBAR01_END".toByteArray(Charsets.US_ASCII)

        const val MIN_PASSPHRASE_LENGTH = 12
        const val PBKDF2_ITERATIONS = 600_000
        const val SALT_LENGTH = 16
        const val GCM_IV_LENGTH = 12
        const val GCM_TAG_LENGTH_BITS = 128
        const val KEY_LENGTH_BITS = 256

        const val MAX_MANIFEST_BYTES = 10 * 1024 * 1024L // 10 MB
        const val MAX_SINGLE_ENTRY_BYTES = 500 * 1024 * 1024L // 500 MB
        const val MAX_TOTAL_UNCOMPRESSED_BYTES = 2048 * 1024 * 1024L // 2 GB
        const val MAX_ASSET_COUNT = 50_000
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun exportArchive(
        outputStream: OutputStream,
        passphrase: CharArray,
        manifest: BackupManifest,
        assetResolver: (hash: String) -> File?
    ) {
        if (passphrase.size < MIN_PASSPHRASE_LENGTH) {
            throw IllegalArgumentException("Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters long")
        }

        validateManifest(manifest)
        val resolvedAssets = manifest.assets.associate { asset ->
            val file = assetResolver(asset.hash)
                ?: throw IOException("Expected original is missing: ${asset.hash}")
            if (!file.isFile || file.length() != asset.sizeBytes) {
                throw IOException("Expected original is missing or its size changed: ${asset.hash}")
            }
            asset.hash to file
        }

        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { random.nextBytes(it) }

        // 1. Generate fresh Tink StreamingAead Keyset (AES256_GCM_HKDF_1MB)
        val keysetHandle = KeysetHandle.generateNew(KeyTemplates.get("AES256_GCM_HKDF_1MB"))
        val rawKeysetBaos = ByteArrayOutputStream()
        CleartextKeysetHandle.write(keysetHandle, BinaryKeysetWriter.withOutputStream(rawKeysetBaos))
        val rawKeysetBytes = rawKeysetBaos.toByteArray()

        // 2. Wrap keyset bytes with PBKDF2-derived master key using AES-256-GCM
        val derivedKey = deriveKey(passphrase, salt)
        val wrappingCipher = Cipher.getInstance("AES/GCM/NoPadding")
        wrappingCipher.init(Cipher.ENCRYPT_MODE, derivedKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        wrappingCipher.updateAAD(ENVELOPE_MAGIC)
        val wrappedKeyset = wrappingCipher.doFinal(rawKeysetBytes)

        // 3. Write outer envelope header: MAGIC (8 bytes) + SALT (16 bytes) + IV (12 bytes) + WRAPPED_KEYSET_LEN (4 bytes) + WRAPPED_KEYSET
        val dos = DataOutputStream(outputStream)
        dos.write(ENVELOPE_MAGIC)
        dos.write(salt)
        dos.write(iv)
        dos.writeInt(wrappedKeyset.size)
        dos.write(wrappedKeyset)
        dos.flush()

        // 4. Stream encrypted payload using Tink StreamingAead
        val streamingAead = keysetHandle.getPrimitive(StreamingAead::class.java)
        val associatedData = ENVELOPE_MAGIC + salt
        val encryptingStream = streamingAead.newEncryptingStream(outputStream, associatedData)

        try {
            val archiveDos = DataOutputStream(BufferedOutputStream(encryptingStream))

            // Write framed inner archive header: ARCHIVE_MAGIC + version (1)
            archiveDos.write(ARCHIVE_MAGIC)
            archiveDos.writeInt(1)

            // Manifest
            val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
            require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Manifest exceeds backup limit" }
            archiveDos.writeInt(manifestBytes.size)
            archiveDos.write(manifestBytes)

            // Distinct assets
            val distinctAssets = manifest.assets
            archiveDos.writeInt(distinctAssets.size)

            val buffer = ByteArray(32768)
            for (asset in distinctAssets) {
                val hashBytes = hexToBytes(asset.hash)
                archiveDos.write(hashBytes) // exact 32 bytes

                val file = resolvedAssets.getValue(asset.hash)
                val fileLen = asset.sizeBytes
                archiveDos.writeLong(fileLen)
                val digest = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                FileInputStream(file).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        copied += read
                        if (copied > fileLen) throw IOException("Original changed during export: ${asset.hash}")
                        digest.update(buffer, 0, read)
                        archiveDos.write(buffer, 0, read)
                    }
                }
                if (copied != fileLen || bytesToHex(digest.digest()) != asset.hash) {
                    throw IOException("Original checksum or size changed: ${asset.hash}")
                }
            }

            // End marker
            archiveDos.write(ARCHIVE_END_MARKER)
            archiveDos.flush()
        } finally {
            encryptingStream.close()
        }
    }

    fun inspectAndStage(
        inputStream: InputStream,
        passphrase: CharArray,
        stagingRoot: Path,
        existingItemIds: Set<String>
    ): RestorePreview {
        if (passphrase.size < MIN_PASSPHRASE_LENGTH) {
            throw IllegalArgumentException("Passphrase must be at least $MIN_PASSPHRASE_LENGTH characters long")
        }

        // 1. Read and verify envelope header
        val dis = DataInputStream(inputStream)
        val magic = ByteArray(ENVELOPE_MAGIC.size)
        dis.readFully(magic)
        if (!magic.contentEquals(ENVELOPE_MAGIC)) {
            throw IllegalArgumentException("Not a valid Personal Library backup file (header magic mismatch)")
        }

        val salt = ByteArray(SALT_LENGTH)
        dis.readFully(salt)

        val iv = ByteArray(GCM_IV_LENGTH)
        dis.readFully(iv)

        val wrappedKeysetLen = dis.readInt()
        if (wrappedKeysetLen <= 0 || wrappedKeysetLen > 65536) {
            throw SecurityException("Corrupted or oversized wrapped keyset in backup")
        }
        val wrappedKeyset = ByteArray(wrappedKeysetLen)
        dis.readFully(wrappedKeyset)

        // 2. Unwrap keyset using passphrase
        val derivedKey = deriveKey(passphrase, salt)
        val rawKeysetBytes: ByteArray
        try {
            val unwrappingCipher = Cipher.getInstance("AES/GCM/NoPadding")
            unwrappingCipher.init(Cipher.DECRYPT_MODE, derivedKey, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            unwrappingCipher.updateAAD(ENVELOPE_MAGIC)
            rawKeysetBytes = unwrappingCipher.doFinal(wrappedKeyset)
        } catch (_: Exception) {
            throw SecurityException("Invalid password or backup file has been tampered with")
        }

        val keysetHandle: KeysetHandle
        try {
            keysetHandle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(rawKeysetBytes))
        } catch (e: Exception) {
            throw SecurityException("Invalid encryption keyset in backup archive: ${e.message}")
        }

        val stagingDir = Files.createTempDirectory(stagingRoot, "restore_stage_").toFile()
        val assetsDir = File(stagingDir, "assets").apply { mkdirs() }

        try {
            val streamingAead = keysetHandle.getPrimitive(StreamingAead::class.java)
            val associatedData = ENVELOPE_MAGIC + salt
            val decryptingStream = streamingAead.newDecryptingStream(inputStream, associatedData)

            val archiveDis = DataInputStream(BufferedInputStream(decryptingStream))

            // Verify inner archive magic
            val archiveMagic = ByteArray(ARCHIVE_MAGIC.size)
            archiveDis.readFully(archiveMagic)
            if (!archiveMagic.contentEquals(ARCHIVE_MAGIC)) {
                throw SecurityException("Archive header mismatch: corrupted inner archive")
            }

            val version = archiveDis.readInt()
            if (version != 1) {
                throw SecurityException("Unsupported archive version: $version")
            }

            // Read manifest
            val manifestLen = archiveDis.readInt()
            if (manifestLen <= 0 || manifestLen > MAX_MANIFEST_BYTES) {
                throw SecurityException("Manifest length $manifestLen exceeds limit of $MAX_MANIFEST_BYTES bytes")
            }
            val manifestBytes = ByteArray(manifestLen)
            archiveDis.readFully(manifestBytes)
            val manifest = json.decodeFromString<BackupManifest>(String(manifestBytes, Charsets.UTF_8))
            validateManifest(manifest)
            val expectedAssets = manifest.assets.associateBy { it.hash }

            // Read assets
            val assetCount = archiveDis.readInt()
            if (assetCount < 0 || assetCount > MAX_ASSET_COUNT) {
                throw SecurityException("Asset count $assetCount exceeds limit")
            }
            if (assetCount != expectedAssets.size) throw SecurityException("Manifest and framed asset counts differ")

            val seenHashes = mutableSetOf<String>()
            val buffer = ByteArray(32768)
            var totalStagedBytes = 0L

            for (i in 0 until assetCount) {
                val hashBytes = ByteArray(32)
                archiveDis.readFully(hashBytes)
                val hexHash = bytesToHex(hashBytes).lowercase()

                if (!seenHashes.add(hexHash)) {
                    throw SecurityException("Duplicate asset hash in archive: $hexHash")
                }

                val declaredLen = archiveDis.readLong()
                val expected = expectedAssets[hexHash] ?: throw SecurityException("Undeclared asset $hexHash")
                if (declaredLen != expected.sizeBytes) throw SecurityException("Manifest size mismatch for $hexHash")
                if (declaredLen < 0 || declaredLen > MAX_SINGLE_ENTRY_BYTES) {
                    throw SecurityException("Asset $hexHash declared length $declaredLen exceeds limit")
                }

                val targetFile = File(assetsDir, hexHash)
                val md = MessageDigest.getInstance("SHA-256")

                FileOutputStream(targetFile).use { fos ->
                    var remaining = declaredLen
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val read = archiveDis.read(buffer, 0, toRead)
                        if (read == -1) {
                            throw EOFException("Unexpected EOF while reading asset $hexHash")
                        }
                        md.update(buffer, 0, read)
                        fos.write(buffer, 0, read)
                        remaining -= read
                        totalStagedBytes += read
                        if (totalStagedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                            throw SecurityException("Total uncompressed size exceeds limit")
                        }
                    }
                }

                val computedHash = bytesToHex(md.digest()).lowercase()
                if (computedHash != hexHash) {
                    throw SecurityException("Checksum mismatch for $hexHash: computed $computedHash")
                }
            }
            if (seenHashes != expectedAssets.keys) throw SecurityException("Manifest asset set mismatch")

            // Verify end marker
            val endMarker = ByteArray(ARCHIVE_END_MARKER.size)
            archiveDis.readFully(endMarker)
            if (!endMarker.contentEquals(ARCHIVE_END_MARKER)) {
                throw SecurityException("Missing archive end marker: corrupted or truncated backup")
            }

            // Ensure no trailing bytes in decrypted stream
            val extra = archiveDis.read()
            if (extra != -1) {
                throw SecurityException("Trailing unauthenticated data detected in archive")
            }

            val duplicateCount = manifest.items.count { existingItemIds.contains(it.id) }

            return RestorePreview(
                manifest = manifest,
                stagingDir = stagingDir,
                totalItems = manifest.items.size,
                totalCollections = manifest.collections.size,
                totalAssets = manifest.assets.size,
                existingDuplicateCount = duplicateCount
            )
        } catch (e: Exception) {
            stagingDir.deleteRecursively()
            if (e is SecurityException) throw e
            if (e is IOException && e.cause is SecurityException) throw (e.cause as SecurityException)
            throw SecurityException("Invalid password or backup file has been tampered with: ${e.message}")
        }
    }

    private fun validateManifest(manifest: BackupManifest) {
        require(manifest.version == 1) { "Unsupported manifest version" }
        require(manifest.assets.size <= MAX_ASSET_COUNT) { "Too many manifest assets" }
        require(manifest.assets.map { it.hash }.toSet().size == manifest.assets.size) { "Duplicate manifest asset" }
        var total = 0L
        for (asset in manifest.assets) {
            require(asset.hash.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 asset hash" }
            require(asset.sizeBytes in 0..MAX_SINGLE_ENTRY_BYTES) { "Invalid manifest asset size" }
            total += asset.sizeBytes
            require(total <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Manifest assets exceed size limit" }
        }
        val assets = manifest.assets.associateBy { it.hash }
        require(manifest.items.map { it.id }.toSet().size == manifest.items.size) { "Duplicate item ID" }
        require(manifest.collections.map { it.id }.toSet().size == manifest.collections.size) { "Duplicate collection ID" }
        for (item in manifest.items) {
            item.contentHash?.let { hash ->
                require(assets[hash]?.sizeBytes == item.fileSizeBytes) { "Item references missing or inconsistent original" }
            }
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        val keySpec = SecretKeySpec(keyBytes, "AES")
        Arrays.fill(keyBytes, 0.toByte())
        return keySpec
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val len = clean.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(clean[i], 16) shl 4) + Character.digit(clean[i + 1], 16)).toByte()
        }
        return data
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append("%02x".format(b))
        }
        return sb.toString()
    }
}
