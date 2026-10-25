package com.example.ui.screens

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Input
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.MainViewModel
import com.example.ui.theme.ThemeMode
import com.example.ui.components.EncryptedBackupPassphraseDialog
import com.example.ui.components.WhatsAppFileImportDialog
import com.personallibrary.core.backup.BackupMode
import com.personallibrary.core.backup.RestorePreview
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onNavigateToTrash: () -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val stats by viewModel.storageStats.collectAsState()
    val lastBackup by viewModel.lastSuccessfulBackup.collectAsState()
    val themeMode by viewModel.themeMode.collectAsState()

    val prefs = remember { context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE) }
    var pauseAi by remember { mutableStateOf(prefs.getBoolean("ai_paused", false)) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("ai_wifi_only", false)) }

    var showMigrationDialog by remember { mutableStateOf(false) }
    var showExportPassphraseDialog by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf<CharArray?>(null) }

    var showRestorePassphraseDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePreviewState by remember { mutableStateOf<RestorePreview?>(null) }
    var showRestorePreviewDialog by remember { mutableStateOf(false) }
    var restoreCommitStarted by remember { mutableStateOf(false) }
    val latestRestorePreview by rememberUpdatedState(restorePreviewState)
    val latestRestoreCommitStarted by rememberUpdatedState(restoreCommitStarted)

    DisposableEffect(Unit) {
        onDispose {
            latestRestorePreview?.takeUnless { latestRestoreCommitStarted }?.let(viewModel::discardRestorePreview)
            pendingExportPassphrase?.fill('\u0000')
            pendingExportPassphrase = null
        }
    }

    DisposableEffect(restorePreviewState) {
        val preview = restorePreviewState
        onDispose {
            if (preview != null && !latestRestoreCommitStarted) viewModel.discardRestorePreview(preview)
        }
    }


    var isLockEnabled by remember { mutableStateOf(viewModel.appLockManager.isLockEnabled()) }

    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        val passphrase = pendingExportPassphrase
        pendingExportPassphrase = null
        if (uri != null && passphrase != null) {
            coroutineScope.launch {
                try {
                    val success = viewModel.exportEncryptedBackup(uri, passphrase)
                    if (success) {
                        Toast.makeText(context, "Encrypted backup exported successfully (.plb)", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(context, "Failed to export encrypted backup", Toast.LENGTH_SHORT).show()
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    Toast.makeText(context, "Failed to export encrypted backup", Toast.LENGTH_LONG).show()
                } finally {
                    passphrase.fill('\u0000')
                }
            }
        } else {
            passphrase?.fill('\u0000')
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
            mode = BackupMode.EXPORT,
            onDismiss = { showExportPassphraseDialog = false },
            onConfirm = { pass ->
                pendingExportPassphrase = pass
                showExportPassphraseDialog = false
                try {
                    exportBackupLauncher.launch("PersonalLibrary_Backup_${System.currentTimeMillis()}.plb")
                } catch (_: Exception) {
                    pendingExportPassphrase?.fill('\u0000')
                    pendingExportPassphrase = null
                    Toast.makeText(context, "Could not open the backup destination picker", Toast.LENGTH_LONG).show()
                }
            }
        )
    }

    if (showRestorePassphraseDialog) {
        EncryptedBackupPassphraseDialog(
            title = "Decrypt & Inspect Backup",
            actionButtonText = "Inspect Archive",
            mode = BackupMode.RESTORE,
            onDismiss = {
                showRestorePassphraseDialog = false
                pendingRestoreUri = null
            },
            onConfirm = { pass ->
                showRestorePassphraseDialog = false
                val targetUri = pendingRestoreUri
                pendingRestoreUri = null
                if (targetUri != null) {
                    coroutineScope.launch {
                        val callerContext = currentCoroutineContext()
                        var stagedPreview: RestorePreview? = null
                        var previewTransferred = false
                        try {
                            stagedPreview = withContext(NonCancellable) {
                                viewModel.inspectBackup(targetUri, pass)
                            }
                            callerContext.ensureActive()
                            if (stagedPreview != null) {
                                restorePreviewState = stagedPreview
                                restoreCommitStarted = false
                                showRestorePreviewDialog = true
                                previewTransferred = true
                            } else {
                                Toast.makeText(context, "Could not inspect the backup. Check the passphrase and archive, then try again.", Toast.LENGTH_LONG).show()
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            Toast.makeText(context, "Could not inspect the backup. Check the passphrase and archive, then try again.", Toast.LENGTH_LONG).show()
                        } finally {
                            if (!previewTransferred) stagedPreview?.let(viewModel::discardRestorePreview)
                            pass.fill('\u0000')
                        }
                    }
                } else {
                    pass.fill('\u0000')
                }
            }
        )
    }

    if (showRestorePreviewDialog && restorePreviewState != null) {
        val preview = restorePreviewState!!
        val newItems = (preview.manifest.items.size - preview.existingDuplicateCount).coerceAtLeast(0)
        AlertDialog(
            onDismissRequest = {
                if (!restoreCommitStarted) {
                    showRestorePreviewDialog = false
                    restorePreviewState = null
                }
            },
            title = { Text("Restore Archive Preview") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("• Total Items: ${preview.totalItems}")
                    Text("• Collections: ${preview.totalCollections}")
                    Text("• Attached Blobs: ${preview.totalAssets}")
                    Text("• Existing Duplicates: ${preview.existingDuplicateCount} (will be preserved/skipped)")
                    Text("• New Items to Restore: $newItems")
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "Restoring will safely import items, blobs, and collections. All data is verified before committing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = restorePreviewState
                        if (target != null && !restoreCommitStarted) {
                            restoreCommitStarted = true
                            coroutineScope.launch {
                                try {
                                    val count = viewModel.commitRestore(target)
                                    Toast.makeText(context, "Restored $count items successfully", Toast.LENGTH_LONG).show()
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    Toast.makeText(context, "Restore failed. Please retry with the same backup.", Toast.LENGTH_LONG).show()
                                } finally {
                                    viewModel.discardRestorePreview(target)
                                    restorePreviewState = null
                                    showRestorePreviewDialog = false
                                    restoreCommitStarted = false
                                }
                            }
                        }
                    },
                    enabled = !restoreCommitStarted,
                    modifier = Modifier.testTag("confirm_restore_button")
                ) {
                    Text(if (restoreCommitStarted) "Restoring…" else "Commit Restore")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !restoreCommitStarted,
                    onClick = {
                        showRestorePreviewDialog = false
                        restorePreviewState = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showMigrationDialog) {
        WhatsAppFileImportDialog(
            onDismiss = { showMigrationDialog = false },
            onImport = { chatText, dateOrder, attachments, exportUri ->
                viewModel.importWhatsAppFiles(chatText, dateOrder, attachments, exportUri)
            }
        )
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings & Portability",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to Library")
                    }
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("System follows your phone's light or dark setting.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Column(Modifier.selectableGroup()) {
                        ThemeMode.entries.forEach { mode ->
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(selected = themeMode == mode, role = Role.RadioButton,
                                        onClick = { viewModel.setThemeMode(mode) })
                                    .testTag("theme_mode_${mode.name.lowercase(Locale.ROOT)}"),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = themeMode == mode, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Text(mode.label, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }

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

                    StorageStatRow("Original Files (Deduplicated)", formatBytes(stats.originalsBytes))
                    StorageStatRow("Cache & Previews", formatBytes(stats.cacheBytes))
                    StorageStatRow("Trash (logical content size)", formatBytes(stats.trashBytes))
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

            // AI Controls & Automation Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI Automation & Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Pause Background AI", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Pauses automatic analysis. You can still Ask AI.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = pauseAi,
                            onCheckedChange = {
                                pauseAi = it
                                viewModel.setAiPaused(it)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Wi-Fi Only for AI Network Calls", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text("Use Wi-Fi only instead of Wi-Fi and mobile data.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                prefs.edit().putBoolean("ai_wifi_only", it).apply()
                                viewModel.resumePendingAiJobs()
                            }
                        )
                    }
                }
            }

            // WhatsApp import
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = Color(0xFF10B981))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("WhatsApp Import", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Import a WhatsApp chat export into Inbox, preserving dates, notes, and attachment links. You can move items into a folder afterward.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = { showMigrationDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("open_migration_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Input, contentDescription = null)
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

                    Spacer(modifier = Modifier.height(6.dp))

                    val lastBackupText = if (lastBackup != null) {
                        "Last successful backup: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(lastBackup!!))}"
                    } else {
                        "Last successful backup: Never"
                    }
                    Text(
                        text = lastBackupText,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
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
                                text = if (isLockEnabled) "Locks in background; hides screenshots and app previews" else "No lock enabled",
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
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
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
        }
    }
}

@Composable
fun StorageStatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
