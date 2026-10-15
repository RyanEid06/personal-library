package com.example.ui.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.model.CollectionItem
import com.example.model.ItemKind
import com.example.model.LibraryItem
import com.example.model.SharePayload
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailsDialog(
    item: LibraryItem,
    collections: List<CollectionItem>,
    onDismiss: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDelete: () -> Unit,
    onCollectionChange: (String?) -> Unit,
    onSetReminder: (Long?) -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var showReminderPicker by remember { mutableStateOf(false) }

    if (showReminderPicker) {
        val calendar = Calendar.getInstance()
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                TimePickerDialog(
                    context,
                    { _, hourOfDay, minute ->
                        calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                        calendar.set(Calendar.MINUTE, minute)
                        calendar.set(Calendar.SECOND, 0)
                        onSetReminder(calendar.timeInMillis)
                        showReminderPicker = false
                        Toast.makeText(context, "Reminder set for ${dateFormatter.format(calendar.time)}", Toast.LENGTH_SHORT).show()
                    },
                    calendar.get(Calendar.HOUR_OF_DAY),
                    calendar.get(Calendar.MINUTE),
                    false
                ).show()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .verticalScroll(scrollState)
                .padding(bottom = 32.dp)
        ) {
            // Header bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    color = getKindColor(item.kind).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = item.kind.displayName,
                        color = getKindColor(item.kind),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Row {
                    IconButton(onClick = onFavoriteToggle) {
                        Icon(
                            if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = "Favorite",
                            tint = if (item.isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = {
                        shareItem(context, item)
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = {
                        onDelete()
                        onDismiss()
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Trash", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Main Preview
            if (item.localFilePath != null && (item.kind == ItemKind.PHOTO || item.kind == ItemKind.STICKER)) {
                AsyncImage(
                    model = File(item.localFilePath),
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.Fit
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Content body / URL / extracted text
            if (item.content.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = item.content,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 22.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Item text", item.content))
                                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Text")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Action Buttons: Open, Remind
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { openItemExternally(context, item) },
                    modifier = Modifier.weight(1f).testTag("dialog_open_button")
                ) {
                    Icon(
                        if (item.kind == ItemKind.LINK) Icons.Filled.OpenInBrowser else Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (item.kind == ItemKind.LINK) "Open Link" else "Open File")
                }

                OutlinedButton(
                    onClick = { showReminderPicker = true },
                    modifier = Modifier.weight(1f).testTag("dialog_remind_button")
                ) {
                    Icon(Icons.Filled.Alarm, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (item.reminderTime != null) "Edit Reminder" else "Remind Me")
                }
            }

            if (item.reminderTime != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.NotificationImportant, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Due: ${dateFormatter.format(Date(item.reminderTime))}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    TextButton(onClick = { onSetReminder(null) }) {
                        Text("Clear", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Collection selection
            Text("Collection", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))
            var collectionMenuExpanded by remember { mutableStateOf(false) }
            val currentCollection = collections.find { it.id == item.collectionId }

            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedCard(
                    onClick = { collectionMenuExpanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(currentCollection?.name ?: "Unfiled (None)")
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
                DropdownMenu(
                    expanded = collectionMenuExpanded,
                    onDismissRequest = { collectionMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("None (Unfiled)") },
                        onClick = {
                            onCollectionChange(null)
                            collectionMenuExpanded = false
                        }
                    )
                    for (col in collections) {
                        DropdownMenuItem(
                            text = { Text(col.name) },
                            onClick = {
                                onCollectionChange(col.id)
                                collectionMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Metadata & AI Analysis Section
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))
            Text("Storage & AI Details", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(6.dp))

            if (!item.aiDescription.isNullOrBlank()) {
                DetailRow("AI Description", item.aiDescription)
            }
            if (!item.aiConcepts.isNullOrBlank()) {
                DetailRow("Key Concepts", item.aiConcepts)
            }
            if (item.originalFileName != null) {
                DetailRow("Original Name", item.originalFileName)
            }
            if (item.fileSizeBytes > 0) {
                val sizeStr = if (item.fileSizeBytes > 1024 * 1024) "%.2f MB".format(item.fileSizeBytes / (1024.0 * 1024.0)) else "${item.fileSizeBytes / 1024} KB"
                DetailRow("File Size", sizeStr)
            }
            DetailRow("Saved Date", dateFormatter.format(Date(item.createdAt)))
            if (item.sourceDate != null) {
                DetailRow("Source Date", dateFormatter.format(Date(item.sourceDate)))
            }
            DetailRow("AI Status", item.aiStatus)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.width(16.dp))
        Text(text = value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AddContentDialog(
    collections: List<CollectionItem>,
    onDismiss: () -> Unit,
    onCreateNote: (String, String, String?) -> Unit,
    onSaveLink: (String, String, String, String?) -> Unit,
    onImportFiles: (List<Uri>, String?) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Note, 1: Link, 2: Files
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var selectedCollectionId by remember { mutableStateOf<String?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onImportFiles(uris, selectedCollectionId)
            onDismiss()
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Add to Library",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(14.dp))

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Note") }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Link") }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Import Files") }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                when (selectedTab) {
                    0 -> {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title (Optional)") },
                            modifier = Modifier.fillMaxWidth().testTag("add_note_title")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("Note Content / Text / Grades") },
                            modifier = Modifier.fillMaxWidth().height(140.dp).testTag("add_note_content")
                        )
                    }
                    1 -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Web URL (https://...)") },
                            placeholder = { Text("https://example.com") },
                            modifier = Modifier.fillMaxWidth().testTag("add_link_url")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("Title / Bookmark Name") },
                            modifier = Modifier.fillMaxWidth().testTag("add_link_title")
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = content,
                            onValueChange = { content = it },
                            label = { Text("Accompanying Note (Optional)") },
                            modifier = Modifier.fillMaxWidth().height(90.dp).testTag("add_link_note")
                        )
                    }
                    2 -> {
                        Text(
                            text = "Import photos, screenshots, voice notes, stickers, PDFs, or files from your device into local storage.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.fillMaxWidth().testTag("select_files_button")
                        ) {
                            Icon(Icons.Filled.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Choose Files from Device")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Collection selector
                var colMenuOpen by remember { mutableStateOf(false) }
                val currentColName = collections.find { it.id == selectedCollectionId }?.name ?: "Unfiled (None)"
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(
                        onClick = { colMenuOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Collection: $currentColName", style = MaterialTheme.typography.bodyMedium)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                    }
                    DropdownMenu(expanded = colMenuOpen, onDismissRequest = { colMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("None (Unfiled)") },
                            onClick = { selectedCollectionId = null; colMenuOpen = false }
                        )
                        for (c in collections) {
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { selectedCollectionId = c.id; colMenuOpen = false }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    if (selectedTab == 0) {
                        Button(
                            onClick = {
                                if (content.isNotBlank()) {
                                    onCreateNote(title, content, selectedCollectionId)
                                    onDismiss()
                                }
                            },
                            enabled = content.isNotBlank(),
                            modifier = Modifier.testTag("submit_add_note")
                        ) {
                            Text("Save Note")
                        }
                    } else if (selectedTab == 1) {
                        Button(
                            onClick = {
                                if (url.isNotBlank()) {
                                    onSaveLink(url, title, content, selectedCollectionId)
                                    onDismiss()
                                }
                            },
                            enabled = url.isNotBlank(),
                            modifier = Modifier.testTag("submit_add_link")
                        ) {
                            Text("Save Link")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SharePreviewDialog(
    payload: SharePayload,
    collections: List<CollectionItem>,
    onDismiss: () -> Unit,
    onConfirmSave: (String?) -> Unit
) {
    var selectedCollectionId by remember {
        mutableStateOf(collections.find { it.name.equals("Random Links", true) }?.id)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Save from WhatsApp / Share",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Personal Library received content from another app. Save it locally to your library:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))

                if (payload.text != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Text(
                            text = payload.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 4,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                if (payload.uris.isNotEmpty()) {
                    Text(
                        text = "${payload.uris.size} file(s) attached",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("Target Collection:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))

                var colMenuOpen by remember { mutableStateOf(false) }
                val currentName = collections.find { it.id == selectedCollectionId }?.name ?: "Unfiled"
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedCard(onClick = { colMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(currentName)
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                        }
                    }
                    DropdownMenu(expanded = colMenuOpen, onDismissRequest = { colMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Unfiled") },
                            onClick = { selectedCollectionId = null; colMenuOpen = false }
                        )
                        for (c in collections) {
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                onClick = { selectedCollectionId = c.id; colMenuOpen = false }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Discard") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onConfirmSave(selectedCollectionId) },
                        modifier = Modifier.testTag("save_share_button")
                    ) {
                        Text("Save to Library")
                    }
                }
            }
        }
    }
}

@Composable
fun WhatsAppMigrationDialog(
    onDismiss: () -> Unit,
    onImport: (String) -> Unit
) {
    var chatText by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Chat,
                        contentDescription = null,
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Import Random Links Archive",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Export your 'Random Links' chat from WhatsApp without media (or with text) and paste the chat content here:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = chatText,
                    onValueChange = { chatText = it },
                    label = { Text("WhatsApp chat export text") },
                    placeholder = { Text("13/10/2026, 14:32 - Ryan: syllabus link...\n13/10/2026, 14:35 - Ryan: <Media omitted>") },
                    modifier = Modifier.fillMaxWidth().height(160.dp).testTag("migration_chat_input")
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (chatText.isNotBlank()) {
                                onImport(chatText)
                                onDismiss()
                            }
                        },
                        enabled = chatText.isNotBlank(),
                        modifier = Modifier.testTag("migration_start_button")
                    ) {
                        Text("Start Import")
                    }
                }
            }
        }
    }
}
