package com.personallibrary.core.storage

import com.personallibrary.core.model.StoredAsset
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

class ManagedAssetStore(val root: Path) {

    private val blobsDir = root.resolve("blobs").toFile()
    private val stagingDir = root.resolve("staging").toFile()

    init {
        if (!blobsDir.exists()) blobsDir.mkdirs()
        if (!stagingDir.exists()) stagingDir.mkdirs()
    }

    /**
     * Imports a stream into content-addressed managed storage.
     * Guarantees:
     * - Identical bytes reuse the exact same blob (deduplication)
     * - Display names cannot escape root
     * - Stream failure leaves NO staging or final file
     * - Exceeding availableBytes throws and deletes staging
     */
    fun import(
        input: InputStream,
        originalName: String?,
        declaredMime: String?,
        explicitSticker: Boolean = false,
        availableBytes: Long = (root.toFile().usableSpace - 16L * 1024 * 1024).coerceAtLeast(0),
        knownSizeBytes: Long? = null
    ): StoredAsset {
        // Keep room for metadata/other app writes. Provider sizes are advisory;
        // the streaming budget below still applies if they are absent or wrong.
        if (knownSizeBytes != null && knownSizeBytes >= 0 && knownSizeBytes > availableBytes) {
            throw IOException("Insufficient storage capacity for import")
        }
        val safeOriginalName = sanitizeFileName(originalName ?: "unnamed_file")
        val stagingFile = File(stagingDir, "stage_${UUID.randomUUID()}_$safeOriginalName")

        val digest = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L

        try {
            FileOutputStream(stagingFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    totalBytes += bytesRead
                    if (totalBytes > availableBytes) {
                        throw IOException("Insufficient storage capacity: import exceeds available budget ($availableBytes bytes)")
                    }
                    digest.update(buffer, 0, bytesRead)
                    output.write(buffer, 0, bytesRead)
                }
                output.flush()
                output.fd.sync()
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }

            // Content-addressed path: blobs/<first 2 chars of hash>/<hash>
            val prefix = hash.take(2)
            val bucketDir = File(blobsDir, prefix)
            if (!bucketDir.exists()) bucketDir.mkdirs()

            val finalFile = File(bucketDir, hash)
            val relativePath = "blobs/$prefix/$hash"

            if (finalFile.exists() && finalFile.length() == totalBytes) {
                // Deduplicated! Identical bytes already stored. Delete staging and reuse existing blob.
                stagingFile.delete()
            } else {
                // Atomic move to final location
                Files.move(stagingFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }

            val resolvedMime = ContentClassifier.resolveMimeType(declaredMime, safeOriginalName)
            val kind = ContentClassifier.classify(resolvedMime, safeOriginalName, explicitSticker)

            return StoredAsset(
                hash = hash,
                relativePath = relativePath,
                sizeBytes = totalBytes,
                originalName = safeOriginalName,
                mimeType = resolvedMime,
                kind = kind
            )
        } catch (e: Exception) {
            // Clean up staging on any failure
            if (stagingFile.exists()) {
                stagingFile.delete()
            }
            throw e
        }
    }

    fun getBlobFile(hash: String): File? {
        val prefix = hash.take(2)
        val file = File(blobsDir, "$prefix/$hash")
        return if (file.exists()) file else null
    }

    fun deleteBlob(hash: String): Boolean {
        val file = getBlobFile(hash) ?: return false
        val deleted = file.delete()
        // Clean parent bucket if empty
        file.parentFile?.let { bucket ->
            if (bucket.exists() && bucket.list()?.isEmpty() == true) {
                bucket.delete()
            }
        }
        return deleted
    }

    fun cleanStaging() {
        stagingDir.listFiles()?.filter { it.isFile && it.name.startsWith("stage_") }?.forEach { it.delete() }
    }

    /** Recover only our own incomplete staging files and unreferenced content-addressed blobs. */
    fun reconcile(referencedHashes: Set<String>) {
        cleanStaging()
        blobsDir.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[0-9a-f]{2}")) }?.forEach { bucket ->
            bucket.listFiles()?.filter {
                it.isFile && it.name.matches(Regex("[0-9a-f]{64}")) && it.name.take(2) == bucket.name && it.name !in referencedHashes
            }?.forEach { it.delete() }
            if (bucket.list()?.isEmpty() == true) bucket.delete()
        }
    }

    fun sanitizeFileName(rawName: String): String {
        // Strip directory separators and traversal characters
        val nameOnly = rawName.substringAfterLast('/').substringAfterLast('\\')
        val sanitized = nameOnly.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
        return if (sanitized.isBlank()) "file_${System.currentTimeMillis()}" else sanitized.take(120)
    }
}
