package com.example.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.personallibrary.core.TextExtraction
import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.migration.WhatsAppExportParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun WhatsAppFileImportDialog(
    onDismiss: () -> Unit,
    onImport: suspend (String, DateOrder?, Map<String, Uri>, Uri) -> Pair<Int, Int>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportUri by remember { mutableStateOf<Uri?>(null) }
    var chat by remember { mutableStateOf("") }
    var supplied by remember { mutableStateOf<List<Pair<String, Uri>>>(emptyList()) }
    var order by remember { mutableStateOf<DateOrder?>(null) }
    var acknowledgedDates by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var initiallyAmbiguous by remember { mutableStateOf(false) }
    val parsed by produceState(WhatsAppExportParser.parse(""), chat, order) {
        value = withContext(Dispatchers.Default) { WhatsAppExportParser.parse(chat, order, java.util.TimeZone.getDefault()) }
    }
    val references = parsed.messages.mapNotNull { it.attachmentFileName }.toSet()
    val uniqueFiles = supplied.groupBy { it.first }.filterValues { it.size == 1 }.mapValues { it.value.single().second }
    val matched = references.intersect(uniqueFiles.keys)
    val missing = references - matched
    val missingMessages = parsed.messages.count { it.isMediaOmitted || (it.attachmentFileName != null && it.attachmentFileName !in matched) }
    val unmatched = supplied.filter { it.first !in matched }.map { it.first }

    fun retain(uri: Uri) {
        try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: SecurityException) { }
    }
    fun fileName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: "unnamed supplied file"

    val pickChat = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            retain(uri)
            scope.launch {
                busy = true
                try {
                    val text = withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read export")
                        val extracted = TextExtraction.read(input)
                        require(extracted.coverage == "complete") { "Choose a UTF-8/UTF-16 text export up to 1 MiB" }
                        extracted.text
                    }
                    val preview = withContext(Dispatchers.Default) { WhatsAppExportParser.parse(text) }
                    require(preview.totalMessages > 0) { "No WhatsApp messages found in this export" }
                    initiallyAmbiguous = preview.ambiguousDateCount > 0
                    chat = text; exportUri = uri; order = null; acknowledgedDates = false; message = null
                } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = "Cannot parse export. Choose a WhatsApp .txt export up to 1 MiB; ZIP packages must be extracted first." }
                finally { busy = false }
            }
        }
    }
    val pickAttachments = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            busy = true
            try {
                supplied = withContext(Dispatchers.IO) { uris.map { retain(it); fileName(it) to it } }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not read supplied file names. Choose the files again." }
            finally { busy = false }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Import WhatsApp export") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose the exported .txt file, then any attachment files supplied with it. Extract ZIP packages before selecting files.")
                OutlinedButton(onClick = { pickChat.launch(arrayOf("text/plain", "application/octet-stream")) }, enabled = !busy) { Text("Choose WhatsApp .txt") }
                OutlinedButton(onClick = { pickAttachments.launch(arrayOf("*/*")) }, enabled = !busy) { Text("Choose attachment files (optional)") }
                if (exportUri != null) {
                    Text("Preview before import")
                    Text("Messages: ${parsed.totalMessages}\nAmbiguous dates: ${parsed.ambiguousDateCount}\nUnparseable dates: ${parsed.unparseableDateCount}\nAttachment references: ${parsed.mediaReferencesCount}\nSupplied matching files: ${matched.size}\nMessages missing attachments: $missingMessages\nUnmatched supplied files: ${unmatched.size}")
                    if (missing.isNotEmpty()) Text("Missing: ${missing.take(8).joinToString()}")
                    if (unmatched.isNotEmpty()) Text("Unmatched or duplicate names: ${unmatched.take(8).joinToString()}")
                    if (initiallyAmbiguous) {
                        Text("Choose the export's date order, or keep uncertain dates unset.")
                        for ((value, label) in listOf(DateOrder.DAY_FIRST to "Day first", DateOrder.MONTH_FIRST to "Month first", null to "Keep uncertain dates unset")) {
                            TextButton(onClick = { order = value; acknowledgedDates = true }, enabled = !busy) { Text(if (acknowledgedDates && order == value) "✓ $label" else label) }
                        }
                    }
                    Text("Dates use this phone's current timezone (${java.util.TimeZone.getDefault().id}). Import saves messages and the original export to Random Links. Missing attachment bytes are not imported. Local indexing and queued AI analysis follow.")
                }
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = exportUri != null && !busy && (!initiallyAmbiguous || acknowledgedDates), modifier = Modifier.testTag("migration_start_button"), onClick = {
                scope.launch {
                    busy = true
                    try {
                        val result = onImport(chat, order, uniqueFiles.filterKeys { it in matched }, exportUri!!)
                        android.widget.Toast.makeText(context, "Imported ${result.first} new messages. $missingMessages messages had missing attachments.", android.widget.Toast.LENGTH_LONG).show()
                        onDismiss()
                    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Import could not finish. Originals remain available; retry with the supplied files." }
                    finally { busy = false }
                }
            }) { Text("Confirm import") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } }
    )
}
