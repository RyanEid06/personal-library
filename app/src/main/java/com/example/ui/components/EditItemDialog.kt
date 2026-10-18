package com.example.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.ItemEntity
import kotlinx.coroutines.launch

/** Edits display metadata only; originals, filenames, source dates and import provenance stay intact. */
@Composable
fun EditItemDialog(
    item: ItemEntity,
    onDismiss: () -> Unit,
    onSave: suspend (title: String, content: String) -> String?
) {
    var title by remember(item.id) { mutableStateOf(item.title) }
    val isLink = item.kind == "LINK" && item.contentHash == null
    val isEditableText = item.kind == "TEXT" && item.contentHash == null
    // WhatsApp stores its authoritative provenance in the content field. Keep that suffix exact.
    val provenanceStart = item.content.lastIndexOf("\n\nWhatsApp provenance\n")
    val provenance = if (provenanceStart >= 0) item.content.substring(provenanceStart) else ""
    val originalBody = if (provenanceStart >= 0) item.content.substring(0, provenanceStart) else item.content
    val originalUrl = originalBody.lineSequence().firstOrNull().orEmpty()
    val originalNote = originalBody.substringAfter('\n', "")
    var body by remember(item.id) { mutableStateOf(originalBody) }
    var url by remember(item.id) { mutableStateOf(originalUrl) }
    var linkNote by remember(item.id) { mutableStateOf(originalNote) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = title.isNotBlank() && (!isLink || url.isNotBlank())

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Edit Item") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") },
                    enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("edit_item_title"))
                when {
                    isLink -> {
                        OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") },
                            singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("edit_item_url"))
                        OutlinedTextField(value = linkNote, onValueChange = { linkNote = it }, label = { Text("Note") },
                            minLines = 3, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("edit_item_body"))
                    }
                    isEditableText -> OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Body") },
                        minLines = 4, enabled = !saving, modifier = Modifier.fillMaxWidth().testTag("edit_item_body"))
                    else -> Text("The original file and its saved provenance are preserved.")
                }
                if (provenance.isNotEmpty()) Text("Original sender, source and timestamp are preserved.", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("edit_item_error")) }
            }
        },
        confirmButton = {
            TextButton(enabled = valid && !saving, modifier = Modifier.testTag("edit_item_save"), onClick = {
                val content = when {
                    isLink && url == originalUrl && linkNote == originalNote -> item.content
                    isLink -> url.trim() + (if (linkNote.isNotEmpty()) "\n$linkNote" else "") + provenance
                    isEditableText -> body + provenance
                    else -> item.content
                }
                saving = true
                scope.launch {
                    error = onSave(title.trim(), content)
                    saving = false
                    if (error == null) onDismiss()
                }
            }) { Text(if (saving) "Saving…" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } }
    )
}
