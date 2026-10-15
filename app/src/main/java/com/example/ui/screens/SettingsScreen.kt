package com.example.ui.screens

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.MainViewModel
import com.example.ui.components.EncryptedBackupPassphraseDialog
import com.example.ui.components.WhatsAppMigrationDialog
import com.personallibrary.core.backup.RestorePreview
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateToTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val stats by viewModel.storageStats.collectAsState()

    var showMigrationDialog by remember { mutableStateOf(false) }
    var showExportPassphraseDialog by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf<CharArray?>(null) }

    var showRestorePassphraseDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePreviewState by remember { mutableStateOf<RestorePreview?>(null) }
    var showRestorePreviewDialog by remember { mutableStateOf(false) }

    var isLockEnabled by remember { mutableStateOf(viewModel.appLockManager.isLockEnabled()) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null && pendingExportPassphrase != null) {
            coroutineScope.launch {
                val success = viewModel.exportEncryptedBackup(uri, pendingExportPassphrase!!)
                pendingExportPassphrase = null
                if (success) {
                    Toast.makeText(context, "Encrypted backup exported successfully (.plb)", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(context, "Failed to export encrypted backup", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val restoreBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreUri = uri
            showRestorePassphraseDialog = true
        }
    }

    if (showExportPassphraseDialog) {
        EncryptedBackupPassphraseDialog(
            title = "Export Encrypted Backup",
            actionButtonText = "Choose Destination",
            onDismiss = { showExportPassphraseDialog = false },
            onConfirm = { pass ->
                pendingExportPassphrase = pass
                showExportPassphraseDialog = false
                exportBackupLauncher.launch("PersonalLibrary_Backup_${System.currentTimeMillis()}.plb")
            }
        )
    }

    if (showRestorePassphraseDialog) {
        EncryptedBackupPassphraseDialog(
            title = "Decrypt & Inspect Backup",
            actionButtonText = "Inspect Archive",
            onDismiss = {
                showRestorePassphraseDialog = false
                pendingRestoreUri = null
            },
            onConfirm = { pass ->
                showRestorePassphraseDialog = false
                val targetUri = pendingRestoreUri
                if (targetUri != null) {
                    coroutineScope.launch {
                        val preview = viewModel.inspectBackup(targetUri, pass)
                        if (preview != null) {
                            restorePreviewState = preview
                            showRestorePreviewDialog = true
                        } else {
                            Toast.makeText(context, "Incorrect passphrase or invalid encrypted backup archive", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        )
    }

    if (showRestorePreviewDialog && restorePreviewState != null) {
        val preview = restorePreviewState!!
        val newItems = (preview.manifest.items.size - preview.existingDuplicateCount).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = {
                preview.stagingDir.deleteRecursively()
                showRestorePreviewDialog = false
                restorePreviewState = null
                pendingRestoreUri = null
            },
            title = { Text("Restore Backup Inspection") },
            text = {
                Column {
                    Text("Verified encrypted backup integrity:\n", fontWeight = FontWeight.SemiBold)
                    Text("• Total items in archive: ${preview.manifest.items.size}")
                    Text("• New items to restore: $newItems")
                    Text("• Duplicate items skipped: ${preview.existingDuplicateCount}")
                    Text("• Collections in backup: ${preview.manifest.collections.size}")
                    Text("• Managed assets: ${preview.manifest.assets.size}")
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Proceed with restoring records into your local personal library?", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        coroutineScope.launch {
                            val count = viewModel.commitRestore(preview)
                            showRestorePreviewDialog = false
                            restorePreviewState = null
                            pendingRestoreUri = null
                            Toast.makeText(context, "Successfully restored $count items into library", Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.testTag("confirm_restore_button")
                ) {
                    Text("Commit Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        preview.stagingDir.deleteRecursively()
                        showRestorePreviewDialog = false
                        restorePreviewState = null
                        pendingRestoreUri = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMigrationDialog) {
        WhatsAppMigrationDialog(
            onDismiss = { showMigrationDialog = false },
            onImport = { chatText, dateOrder ->
                coroutineScope.launch {
                    val (imported, ambiguous) = viewModel.importWhatsAppMigration(chatText, dateOrder)
                    val ambiguousMsg = if (ambiguous > 0) " ($ambiguous ambiguous dates flagged)" else ""
                    Toast.makeText(context, "Imported $imported messages into 'Random Links'$ambiguousMsg", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Storage",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Storage Statistics Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PieChart, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Local Storage Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    StorageStatRow("Original Files", formatBytes(stats.originalsBytes))
                    StorageStatRow("Cache & Previews", formatBytes(stats.cacheBytes))
                    StorageStatRow("Trash", formatBytes(stats.trashBytes))
                    StorageStatRow("Active Items", "${stats.totalItems} items")

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        OutlinedButton(
                            onClick = {
                                viewModel.clearCache()
                                Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("clear_cache_button")
                        ) {
                            Icon(Icons.Outlined.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Clear Cache")
                        }
                    }
                }
            }

            // WhatsApp Random Links Migration
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Chat, contentDescription = null, tint = Color(0xFF10B981))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("WhatsApp 'Random Links' Migration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Migrate your existing Random Links chat export directly into organized collections, preserving dates, notes, and attachment links with strict date parsing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { showMigrationDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("open_migration_button")
                    ) {
                        Icon(Icons.Filled.Input, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Import Chat Archive")
                    }
                }
            }

            // Encrypted Backup & Portability Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Security, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Encrypted Backup & Portability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Export a passphrase-protected, AES-256-GCM encrypted portable backup (.plb) containing original files, metadata, collections, and reminders. Inspect archive before committing on restore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showExportPassphraseDialog = true },
                            modifier = Modifier.weight(1f).testTag("export_backup_button")
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export Backup")
                        }

                        OutlinedButton(
                            onClick = {
                                restoreBackupLauncher.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.weight(1f).testTag("restore_backup_button")
                        ) {
                            Icon(Icons.Filled.FileDownload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Restore")
                        }
                    }
                }
            }

            // Security & App Lock Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Biometric App Lock", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = if (isLockEnabled) "App locks on background and requires authentication" else "No lock enabled",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }

                    Switch(
                        checked = isLockEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                if (viewModel.appLockManager.canAuthenticate()) {
                                    viewModel.appLockManager.setLockEnabled(true)
                                    isLockEnabled = true
                                    Toast.makeText(context, "App lock enabled", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Device has no biometric or screen lock configured", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                viewModel.appLockManager.setLockEnabled(false)
                                isLockEnabled = false
                                Toast.makeText(context, "App lock disabled", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.testTag("app_lock_switch")
                    )
                }
            }

            // Trash Management Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("Trash & 30-Day Purge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("${stats.trashedItems} items currently in trash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                    }

                    Button(
                        onClick = onNavigateToTrash,
                        modifier = Modifier.testTag("view_trash_button")
                    ) {
                        Text("View Trash")
                    }
                }
            }

            // AI Information Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI Enrichment & Gateway", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "• Backend Gateway: Dedicated authenticated Node/TypeScript server\n• No API Keys in APK: Gemini keys remain server-side only\n• Authority: Originals, metadata, and vectors stay 100% on your device\n• Offline search: Full text search operates fully offline",
                        style = MaterialTheme.typography.bodySmall,
                        lineHeight = 20.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun StorageStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.outline)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1024 * 1024 -> String.format("%.2f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
