package com.example.storage

import android.content.Context
import android.net.Uri
import com.example.model.CollectionItem
import com.example.model.LibraryItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val exportTimestamp: Long = System.currentTimeMillis(),
    val app: String = "Personal Library",
    val collections: List<CollectionItem>,
    val items: List<LibraryItem>
)

data class RestoreSummary(
    val totalInBackup: Int,
    val importedCount: Int,
    val skippedDuplicates: Int
)

class BackupManager(private val context: Context, private val dbHelper: LibraryDatabaseHelper) {

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun exportBackup(destinationUri: Uri): Boolean {
        return try {
            val collections = dbHelper.getAllCollections()
            val items = dbHelper.getAllItems(includeTrash = false)
            val payload = BackupPayload(
                collections = collections,
                items = items
            )
            val jsonString = json.encodeToString(payload)

            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    writer.write(jsonString)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun restoreBackup(sourceUri: Uri): RestoreSummary? {
        return try {
            val contentResolver = context.contentResolver
            val stringBuilder = StringBuilder()
            contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        stringBuilder.append(line)
                        line = reader.readLine()
                    }
                }
            }

            val payload = json.decodeFromString<BackupPayload>(stringBuilder.toString())

            // Restore collections
            for (col in payload.collections) {
                dbHelper.insertCollection(col)
            }

            var imported = 0
            var skipped = 0
            val existing = dbHelper.getAllItems(includeTrash = true).associateBy { it.id }

            for (item in payload.items) {
                if (existing.containsKey(item.id)) {
                    skipped++
                } else {
                    dbHelper.insertItem(item)
                    imported++
                }
            }

            RestoreSummary(
                totalInBackup = payload.items.size,
                importedCount = imported,
                skippedDuplicates = skipped
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
