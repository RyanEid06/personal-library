package com.personallibrary.core.backup

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
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
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
    val aiStatus: String
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
    val isExact: Boolean = true
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

class EncryptedBackupEngine {

    companion object {
        private val MAGIC_HEADER = "PLIB".toByteArray(Charsets.US_ASCII)
        private const val BACKUP_VERSION = 1
        private const val SALT_LENGTH = 16
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val PBKDF2_ITERATIONS = 65536
        private const val KEY_LENGTH_BITS = 256

        // Hard security limits for backup extraction
        private const val MAX_MANIFEST_BYTES = 10 * 1024 * 1024L // 10 MB
        private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 500 * 1024 * 1024L // 500 MB
        private const val MAX_SINGLE_ENTRY_BYTES = 200 * 1024 * 1024L // 200 MB
        private const val MAX_ZIP_ENTRIES = 20_000
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
        val random = SecureRandom()
        val salt = ByteArray(SALT_LENGTH).also { random.nextBytes(it) }
        val iv = ByteArray(GCM_IV_LENGTH).also { random.nextBytes(it) }

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        // Write header: MAGIC + VERSION + SALT + IV
        outputStream.write(MAGIC_HEADER)
        val dos = DataOutputStream(outputStream)
        dos.writeInt(BACKUP_VERSION)
        dos.write(salt)
        dos.write(iv)
        dos.flush()

        // Encrypt the Zip payload
        CipherOutputStream(outputStream, cipher).use { encryptedOut ->
            ZipOutputStream(BufferedOutputStream(encryptedOut)).use { zipOut ->
                // Write manifest.json
                zipOut.putNextEntry(ZipEntry("manifest.json"))
                val manifestBytes = json.encodeToString(manifest).toByteArray(Charsets.UTF_8)
                zipOut.write(manifestBytes)
                zipOut.closeEntry()

                // Write asset files
                val seenHashes = mutableSetOf<String>()
                for (asset in manifest.assets) {
                    if (!seenHashes.add(asset.hash)) continue
                    val file = assetResolver(asset.hash)
                    if (file != null && file.exists()) {
                        zipOut.putNextEntry(ZipEntry("assets/${asset.hash}"))
                        FileInputStream(file).use { fileIn ->
                            fileIn.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    }
                }
                zipOut.flush()
            }
        }
    }

    fun inspectAndStage(
        inputStream: InputStream,
        passphrase: CharArray,
        stagingRoot: Path,
        existingItemIds: Set<String>
    ): RestorePreview {
        val magic = ByteArray(MAGIC_HEADER.size)
        val bytesRead = inputStream.read(magic)
        if (bytesRead != MAGIC_HEADER.size || !magic.contentEquals(MAGIC_HEADER)) {
            throw IllegalArgumentException("Not a valid Personal Library backup file (invalid magic header)")
        }

        val dis = DataInputStream(inputStream)
        val version = dis.readInt()
        if (version != BACKUP_VERSION) {
            throw IllegalArgumentException("Unsupported backup version: $version (expected $BACKUP_VERSION)")
        }

        val salt = ByteArray(SALT_LENGTH)
        dis.readFully(salt)

        val iv = ByteArray(GCM_IV_LENGTH)
        dis.readFully(iv)

        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))

        val stagingDir = Files.createTempDirectory(stagingRoot, "restore_stage_").toFile()
        val stagingPath = stagingDir.toPath().toAbsolutePath().normalize()

        try {
            var manifest: BackupManifest? = null
            var totalStagedBytes = 0L
            var entryCount = 0
            val seenEntries = mutableSetOf<String>()

            CipherInputStream(inputStream, cipher).use { cipherIn ->
                ZipInputStream(BufferedInputStream(cipherIn)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            throw SecurityException("Backup contains too many entries (exceeds $MAX_ZIP_ENTRIES)")
                        }

                        val entryName = entry.name
                        if (!seenEntries.add(entryName)) {
                            throw SecurityException("Duplicate zip entry detected: $entryName")
                        }

                        // Strict path traversal defense: canonical/normalized path check
                        val targetFile = File(stagingDir, entryName)
                        val targetPath = targetFile.toPath().toAbsolutePath().normalize()
                        if (!targetPath.startsWith(stagingPath)) {
                            throw SecurityException("Malicious zip entry path traversal: $entryName")
                        }

                        targetFile.parentFile?.mkdirs()

                        if (entryName == "manifest.json") {
                            val baos = ByteArrayOutputStream()
                            var manifestBytesCount = 0L
                            val buffer = ByteArray(8192)
                            var read: Int
                            while (zipIn.read(buffer).also { read = it } != -1) {
                                manifestBytesCount += read
                                if (manifestBytesCount > MAX_MANIFEST_BYTES) {
                                    throw SecurityException("Manifest exceeds size limit of $MAX_MANIFEST_BYTES bytes")
                                }
                                baos.write(buffer, 0, read)
                            }
                            val manifestJson = baos.toString(Charsets.UTF_8)
                            manifest = json.decodeFromString<BackupManifest>(manifestJson)
                        } else {
                            var entryBytesCount = 0L
                            FileOutputStream(targetFile).use { fos ->
                                val buffer = ByteArray(8192)
                                var read: Int
                                while (zipIn.read(buffer).also { read = it } != -1) {
                                    entryBytesCount += read
                                    totalStagedBytes += read
                                    if (entryBytesCount > MAX_SINGLE_ENTRY_BYTES) {
                                        throw SecurityException("Archive entry $entryName exceeds maximum entry limit")
                                    }
                                    if (totalStagedBytes > MAX_TOTAL_UNCOMPRESSED_BYTES) {
                                        throw SecurityException("Total uncompressed size exceeds limit of $MAX_TOTAL_UNCOMPRESSED_BYTES bytes")
                                    }
                                    fos.write(buffer, 0, read)
                                }
                            }
                        }
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                    }
                }
            }

            if (manifest == null) {
                stagingDir.deleteRecursively()
                throw IllegalArgumentException("Backup archive is corrupted: manifest.json is missing")
            }

            // Verify staged assets against manifest declarations (SHA-256 and size)
            for (asset in manifest.assets) {
                val assetFile = File(stagingDir, "assets/${asset.hash}")
                if (assetFile.exists()) {
                    if (assetFile.length() != asset.sizeBytes) {
                        throw SecurityException("Asset size mismatch for ${asset.hash}: expected ${asset.sizeBytes}, got ${assetFile.length()}")
                    }
                    val actualHash = sha256(assetFile)
                    if (!actualHash.equals(asset.hash, ignoreCase = true)) {
                        throw SecurityException("Asset SHA-256 checksum mismatch for ${asset.hash}: computed $actualHash")
                    }
                }
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
            if (e is javax.crypto.AEADBadTagException || e.cause is javax.crypto.AEADBadTagException) {
                throw SecurityException("Invalid password or backup file has been tampered with")
            }
            throw e
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        val keySpec = SecretKeySpec(keyBytes, "AES")
        Arrays.fill(keyBytes, 0.toByte())
        return keySpec
    }
}
