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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.model.SharePayload
import com.personallibrary.core.migration.DateOrder
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

private data class ReminderChoice(val time: Long, val dateOnly: Boolean, val localDate: String?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemDetailsDialog(
    itemWithCols: ItemWithCollections,
    fileResolver: (hash: String) -> File?,
    collections: List<CollectionEntity>,
    onDismiss: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDelete: () -> Unit,
    onCollectionsChange: (List<String>) -> Unit,
    onSetReminder: (Long?, Boolean, Boolean, String?) -> Unit,
    aiExcluded: Boolean = false,
    onAiExcludedChange: (Boolean) -> Unit = {},
    onRetryAi: () -> Unit = {}
) {
    val context = LocalContext.current
    val item = itemWithCols.item
    val scrollState = rememberScrollState()
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val blobFile = remember(item.contentHash) {
        item.contentHash?.let { fileResolver(it) }
    }

    var showReminderPicker by remember { mutableStateOf(false) }
    var excludeAi by remember(item.id) { mutableStateOf(aiExcluded) }
    var pendingReminder by remember { mutableStateOf<ReminderChoice?>(null) }
    val scheduler = remember(context) { com.example.reminder.ReminderScheduler(context) }

    fun commitReminder(choice: ReminderChoice) {
        val exact = !choice.dateOnly && scheduler.canUseExactAlarms()
        onSetReminder(choice.time, exact, choice.dateOnly, choice.localDate)
        val message = when {
            !scheduler.notificationsEnabled() -> "Reminder saved. Notifications are blocked; enable them in Android Settings."
            choice.dateOnly -> "Date-only reminder saved for ${choice.localDate}. Notifications use 09:00 local time."
            !exact -> "Reminder saved. Exact alarm access is unavailable; delivery will be approximate."
            else -> "Reminder saved for ${dateFormatter.format(Date(choice.time))}"
        }
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingReminder?.let { commitReminder(it) }
        pendingReminder = null
    }
    fun chooseReminder(choice: ReminderChoice) {
        if (choice.time <= System.currentTimeMillis()) {
            Toast.makeText(context, "Choose a future reminder date or time", Toast.LENGTH_LONG).show()
            return
        }
        if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingReminder = choice
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        } else commitReminder(choice)
    }
    fun pickDate(dateOnly: Boolean) {
        showReminderPicker = false
        val calendar = Calendar.getInstance()
        DatePickerDialog(context, { _, year, month, day ->
            val localDate = java.time.LocalDate.of(year, month + 1, day).toString()
            if (dateOnly) {
                chooseReminder(ReminderChoice(com.personallibrary.core.ReminderDate.atNine(localDate), true, localDate))
            } else {
                calendar.set(year, month, day)
                TimePickerDialog(context, { _, hour, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hour)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    calendar.set(Calendar.MILLISECOND, 0)
                    chooseReminder(ReminderChoice(calendar.timeInMillis, false, null))
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), false).show()
            }
        }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
    }

    if (showReminderPicker) {
        AlertDialog(onDismissRequest = { showReminderPicker = false }, title = { Text("Reminder type") },
            text = { Text("Date-only reminders retain the calendar date and notify at 09:00 local time. Delivery may be approximate.") },
            confirmButton = { TextButton(onClick = { pickDate(true) }) { Text("Date only") } },
            dismissButton = { TextButton(onClick = { pickDate(false) }) { Text("Date + time") } })
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
                        text = item.kind,
                        color = getKindColor(item.kind),
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }

                Row {
                    IconButton(onClick = onFavoriteToggle) {
                        Icon(
                            if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = "Favorite",
                            tint = if (item.isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(onClick = {
                        shareItemEntity(context, item, blobFile)
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

            // Main Preview Dispatcher
            when {
                blobFile != null && (item.kind == "PHOTO" || item.kind == "STICKER" || item.kind == "GIF") -> {
                    AsyncImage(
                        model = blobFile,
                        contentDescription = item.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                }
                blobFile != null && item.kind == "DOCUMENT" -> {
                    val mime = (item.mimeType ?: "").lowercase()
                    val ext = (item.originalFileName ?: "").substringAfterLast('.', "").lowercase()
                    if (mime == "application/pdf" || ext == "pdf") {
                        ItemPdfPreview(item, blobFile)
                        Spacer(modifier = Modifier.height(14.dp))
                    } else {
                        ItemTextPreview(item, blobFile)
                        Spacer(modifier = Modifier.height(14.dp))
                    }
                }
                blobFile != null && item.kind == "AUDIO" -> {
                    ItemAudioPreview(item, blobFile)
                    Spacer(modifier = Modifier.height(14.dp))
                }
                blobFile != null && item.kind == "VIDEO" -> {
                    ItemVideoPreview(item, blobFile)
                    Spacer(modifier = Modifier.height(14.dp))
                }
                blobFile != null && (item.kind == "TEXT" || (item.mimeType ?: "").startsWith("text/")) -> {
                    ItemTextPreview(item, blobFile)
                    Spacer(modifier = Modifier.height(14.dp))
                }
            }

            Text(
                text = item.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Content body / URL / author text
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

            // Extracted text (OCR or PDF machine extraction)
            if (!item.extractedText.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "Extracted Text (OCR / Document Index)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = item.extractedText!!,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp,
                            maxLines = 12
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(
                                onClick = {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Extracted text", item.extractedText))
                                    Toast.makeText(context, "Extracted text copied", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Copy Extracted Text")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Primary Action Buttons: Open, Remind
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Exclude from AI", style = MaterialTheme.typography.bodyMedium)
                    Text("Local search and OCR remain available", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = excludeAi, onCheckedChange = { excludeAi = it; onAiExcludedChange(it) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { openItemEntityExternally(context, item, blobFile) },
                    modifier = Modifier.weight(1f).testTag("dialog_open_button")
                ) {
                    Icon(
                        if (item.kind == "LINK") Icons.Filled.OpenInBrowser else Icons.Filled.OpenInNew,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(if (item.kind == "LINK") "Open Link" else "Open File")
                }

                OutlinedButton(
                    onClick = { showReminderPicker = true },
                    modifier = Modifier.weight(1f).testTag("dialog_remind_button")
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = null, modifier = Modifier.size(18.dp))
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
                        Icon(Icons.Filled.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (item.isReminderDateOnly) "Due: ${item.reminderLocalDate} (date only)" else "Due: ${dateFormatter.format(Date(item.reminderTime))}${if (!item.isReminderExact) " (approximate)" else ""}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    TextButton(onClick = { onSetReminder(null, false, false, null) }) {
                        Text("Clear", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (!scheduler.notificationsEnabled()) {
                    Text("Notifications are blocked in Android Settings", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Multi-Collection Memberships (Many-to-Many!)
            Text("Collection Memberships", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(6.dp))

            val currentAssignedIds = remember(itemWithCols) {
                mutableStateListOf<String>().apply { addAll(itemWithCols.collections.map { it.id }) }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                    .padding(8.dp)
            ) {
                if (collections.isEmpty()) {
                    Text("No collections created yet", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                } else {
                    for (col in collections) {
                        val isChecked = currentAssignedIds.contains(col.id)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) currentAssignedIds.remove(col.id) else currentAssignedIds.add(col.id)
                                    onCollectionsChange(currentAssignedIds.toList())
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) currentAssignedIds.add(col.id) else currentAssignedIds.remove(col.id)
                                    onCollectionsChange(currentAssignedIds.toList())
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(col.name, style = MaterialTheme.typography.bodyMedium)
                        }
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
            if (item.contentHash != null) {
                DetailRow("Content Hash", item.contentHash.take(16) + "...")
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
            if (item.aiStatus == "NEEDS_RETRY" && !excludeAi) {
                TextButton(onClick = onRetryAi) { Text("Retry analysis") }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun AddContentDialog(
    collections: List<CollectionEntity>,
    onDismiss: () -> Unit,
    onCreateNote: (String, String, List<String>) -> Unit,
    onSaveLink: (String, String, String, List<String>) -> Unit,
    onImportFiles: (List<Uri>, List<String>) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) } // 0: Note, 1: Link, 2: Files
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val selectedCollectionIds = remember { mutableStateListOf<String>() }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onImportFiles(uris, selectedCollectionIds.toList())
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
                            text = "Import photos, screenshots, voice notes, stickers, PDFs, or files into content-addressed managed storage.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { filePicker.launch("*/*") },
                            modifier = Modifier.fillMaxWidth().testTag("select_files_button")
                        ) {
                            Icon(Icons.Filled.Folder, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Choose Files from Device")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Many-to-many collection selection
                Text("Assign to Collections:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(6.dp))
                for (col in collections) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (selectedCollectionIds.contains(col.id)) selectedCollectionIds.remove(col.id)
                                else selectedCollectionIds.add(col.id)
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedCollectionIds.contains(col.id),
                            onCheckedChange = { checked ->
                                if (checked) selectedCollectionIds.add(col.id) else selectedCollectionIds.remove(col.id)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(col.name, style = MaterialTheme.typography.bodyMedium)
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
                                    onCreateNote(title, content, selectedCollectionIds.toList())
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
                                    onSaveLink(url, title, content, selectedCollectionIds.toList())
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
    collections: List<CollectionEntity>,
    onDismiss: () -> Unit,
    onConfirmSave: (List<String>) -> Unit
) {
    val selectedCollectionIds = remember {
        val randomLinks = collections.find { it.name.equals("Random Links", true) }
        if (randomLinks != null) mutableStateListOf(randomLinks.id) else mutableStateListOf()
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
                    text = "Personal Library received content from another app. Save it locally into your library:",
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
                if (payload.rejectedCount > 0) Text("${payload.rejectedCount} attachment(s) cannot be read. Share files using a content provider.", color = MaterialTheme.colorScheme.error)

                Spacer(modifier = Modifier.height(14.dp))

                Text("Target Collections:", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))

                for (col in collections) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (selectedCollectionIds.contains(col.id)) selectedCollectionIds.remove(col.id)
                            else selectedCollectionIds.add(col.id)
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selectedCollectionIds.contains(col.id),
                            onCheckedChange = { checked ->
                                if (checked) selectedCollectionIds.add(col.id) else selectedCollectionIds.remove(col.id)
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(col.name, style = MaterialTheme.typography.bodyMedium)
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
                        onClick = { onConfirmSave(selectedCollectionIds.toList()) },
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
fun EncryptedBackupPassphraseDialog(
    title: String,
    actionButtonText: String,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var confirmPassphrase by remember { mutableStateOf("") }
    val isExport = actionButtonText.contains("Export", ignoreCase = true)

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isExport) "Enter a passphrase to encrypt your backup with AES-256-GCM authenticated encryption."
                    else "Enter the passphrase used to encrypt this backup archive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Backup Passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("backup_passphrase_input")
                )

                if (isExport) {
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = confirmPassphrase,
                        onValueChange = { confirmPassphrase = it },
                        label = { Text("Confirm Passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("backup_confirm_passphrase_input")
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    val canSubmit = if (isExport) passphrase.isNotBlank() && passphrase == confirmPassphrase else passphrase.isNotBlank()
                    Button(
                        onClick = {
                            onConfirm(passphrase.toCharArray())
                            onDismiss()
                        },
                        enabled = canSubmit,
                        modifier = Modifier.testTag("submit_passphrase_button")
                    ) {
                        Text(actionButtonText)
                    }
                }
            }
        }
    }
}
