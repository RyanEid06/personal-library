package com.example.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.example.model.ItemKind
import com.example.model.StorageStats
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

class AssetManager(private val context: Context) {

    private val originalsDir: File
        get() = File(context.filesDir, "originals").apply { if (!exists()) mkdirs() }

    fun importUri(uri: Uri, explicitKind: ItemKind? = null): ImportedAsset? {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"
            val originalName = queryFileName(uri) ?: "file_${System.currentTimeMillis()}"

            val inputStream = contentResolver.openInputStream(uri) ?: return null
            importStream(inputStream, originalName, mimeType, explicitKind)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun importStream(
        inputStream: InputStream,
        originalName: String,
        mimeType: String,
        explicitKind: ItemKind? = null
    ): ImportedAsset? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val sanitizedName = originalName.replace("[^a-zA-Z0-9._-]".toRegex(), "_")
            val targetFileName = "${UUID.randomUUID()}_$sanitizedName"
            val targetFile = File(originalsDir, targetFileName)

            var totalBytes = 0L
            val buffer = ByteArray(8192)
            inputStream.use { input ->
                FileOutputStream(targetFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        digest.update(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    output.flush()
                }
            }

            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val determinedKind = explicitKind ?: classifyKind(mimeType, originalName)

            ImportedAsset(
                fileName = originalName,
                localPath = targetFile.absolutePath,
                mimeType = mimeType,
                sizeBytes = totalBytes,
                hash = hash,
                kind = determinedKind
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun deleteFile(path: String?): Boolean {
        if (path == null) return false
        val file = File(path)
        return if (file.exists() && file.canonicalPath.startsWith(originalsDir.canonicalPath)) {
            file.delete()
        } else false
    }

    fun clearCache() {
        try {
            context.cacheDir.deleteRecursively()
            context.cacheDir.mkdirs()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun calculateStorage(dbHelper: LibraryDatabaseHelper): StorageStats {
        val originalsSize = originalsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val cacheSize = context.cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val allItems = dbHelper.getAllItems(includeTrash = true)
        val trashItems = allItems.filter { it.isTrash }
        val trashSize = trashItems.sumOf { it.fileSizeBytes }

        return StorageStats(
            originalsBytes = originalsSize,
            cacheBytes = cacheSize,
            trashBytes = trashSize,
            totalItems = allItems.size - trashItems.size,
            trashedItems = trashItems.size
        )
    }

    private fun queryFileName(uri: Uri): String? {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = it.getString(nameIndex)
                        }
                    }
                }
            } catch (_: Exception) {}
        }
        if (name == null) {
            name = uri.lastPathSegment
        }
        return name
    }

    fun classifyKind(mimeType: String?, fileName: String?): ItemKind {
        val lowerMime = mimeType?.lowercase() ?: ""
        val lowerName = fileName?.lowercase() ?: ""

        return when {
            lowerMime.contains("webp") && (lowerName.contains("sticker") || lowerName.contains("stickers")) -> ItemKind.STICKER
            lowerMime.startsWith("image/") -> {
                if (lowerName.endsWith(".webp") && lowerName.contains("sticker")) ItemKind.STICKER
                else ItemKind.PHOTO
            }
            lowerMime.startsWith("video/") -> ItemKind.VIDEO
            lowerMime.startsWith("audio/") -> ItemKind.AUDIO
            lowerMime.contains("pdf") || lowerName.endsWith(".pdf") -> ItemKind.DOCUMENT
            lowerMime.contains("text/plain") || lowerName.endsWith(".txt") || lowerName.endsWith(".json") || lowerName.endsWith(".csv") -> ItemKind.DOCUMENT
            lowerMime.contains("msword") || lowerMime.contains("officedocument") || lowerName.endsWith(".doc") || lowerName.endsWith(".docx") -> ItemKind.DOCUMENT
            else -> ItemKind.FILE
        }
    }
}

data class ImportedAsset(
    val fileName: String,
    val localPath: String,
    val mimeType: String,
    val sizeBytes: Long,
    val hash: String,
    val kind: ItemKind
)
