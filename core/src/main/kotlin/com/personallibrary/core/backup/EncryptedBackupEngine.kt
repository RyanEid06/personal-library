package com.personallibrary.core.backup

import com.personallibrary.core.model.ItemKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
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
    val contentHash: String?,
    val originalFileName: String?,
    val mimeType: String?,
    val fileSizeBytes: Long,
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
    val isDone: Boolean
)

@Serializable
data class BackupAssetRecord(
    val hash: String,
    val relativePath: String,
    val sizeBytes: Long,
    val mimeType: String
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
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    /**
     * Exports entire library into a portable, password-encrypted archive stream.
     * Uses PBKDF2 + AES-256-GCM.
     */
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
                for (asset in manifest.assets) {
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

    /**
     * Inspects, authenticates, and decrypts an archive into a temporary staging directory.
     * Throws SecurityException on wrong password, tampering, or path traversal.
     */
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

        try {
            var manifest: BackupManifest? = null
            CipherInputStream(inputStream, cipher).use { cipherIn ->
                ZipInputStream(BufferedInputStream(cipherIn)).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        val entryName = entry.name

                        // Guard against zip path traversal
                        val targetFile = File(stagingDir, entryName)
                        if (!targetFile.canonicalPath.startsWith(stagingDir.canonicalPath)) {
                            throw SecurityException("Malicious zip entry path traversal: $entryName")
                        }

                        targetFile.parentFile?.mkdirs()

                        if (entryName == "manifest.json") {
                            val baos = ByteArrayOutputStream()
                            zipIn.copyTo(baos)
                            val manifestJson = baos.toString(Charsets.UTF_8)
                            manifest = json.decodeFromString<BackupManifest>(manifestJson)
                        } else {
                            FileOutputStream(targetFile).use { fos ->
                                zipIn.copyTo(fos)
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

            val duplicateCount = manifest!!.items.count { existingItemIds.contains(it.id) }

            return RestorePreview(
                manifest = manifest!!,
                stagingDir = stagingDir,
                totalItems = manifest!!.items.size,
                totalCollections = manifest!!.collections.size,
                totalAssets = manifest!!.assets.size,
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

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}
