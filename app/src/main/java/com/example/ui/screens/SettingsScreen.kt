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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Input
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.ui.MainViewModel
import com.example.ui.components.EncryptedBackupPassphraseDialog
import com.example.ui.components.WhatsAppMigrationDialog
import com.personallibrary.core.backup.RestorePreview
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

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
    val isPaired by viewModel.isBackendPaired.collectAsState()
    val lastBackup by viewModel.lastSuccessfulBackup.collectAsState()

    val prefs = remember { context.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE) }
    var pauseAi by remember { mutableStateOf(prefs.getBoolean("ai_paused", false)) }
    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("ai_wifi_only", true)) }

    var showMigrationDialog by remember { mutableStateOf(false) }
    var showExportPassphraseDialog by remember { mutableStateOf(false) }
    var pendingExportPassphrase by remember { mutableStateOf<CharArray?>(null) }

    var showRestorePassphraseDialog by remember { mutableStateOf(false) }
    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePreviewState by remember { mutableStateOf<RestorePreview?>(null) }
    var showRestorePreviewDialog by remember { mutableStateOf(false) }

    var showPairingDialog by remember { mutableStateOf(false) }
    var pairingSecretInput by remember { mutableStateOf("") }
    var isPairingInProgress by remember { mutableStateOf(false) }

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
                showRestorePreviewDialog = false
                restorePreviewState = null
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
                        showRestorePreviewDialog = false
                        restorePreviewState = null
                        if (target != null) {
                            coroutineScope.launch {
                                val count = viewModel.commitRestore(target)
                                Toast.makeText(context, "Restored $count items successfully", Toast.LENGTH_LONG).show()
                            }
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
                        showRestorePreviewDialog = false
                        restorePreviewState = null
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPairingDialog) {
        Dialog(onDismissRequest = { if (!isPairingInProgress) showPairingDialog = false }) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("Pair Backend AI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter the Device Enrollment Secret configured on your backend to establish cryptographic Keystore identity pairing. The secret is never saved to disk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    OutlinedTextField(
                        value = pairingSecretInput,
                        onValueChange = { pairingSecretInput = it },
                        label = { Text("Enrollment Secret") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("enrollment_secret_input")
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { showPairingDialog = false },
                            enabled = !isPairingInProgress
                        ) {
                            Text("Cancel")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                isPairingInProgress = true
                                coroutineScope.launch {
                                    val success = viewModel.pairBackend(pairingSecretInput.trim())
                                    isPairingInProgress = false
                                    pairingSecretInput = ""
                                    if (success) {
                                        showPairingDialog = false
                                        Toast.makeText(context, "Device paired successfully with backend", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Pairing failed. Check secret or backend connectivity.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = pairingSecretInput.isNotBlank() && !isPairingInProgress,
                            modifier = Modifier.testTag("submit_pair_button")
                        ) {
                            if (isPairingInProgress) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Text("Pair Device")
                        }
                    }
                }
            }
        }
    }

    if (showMigrationDialog) {
        WhatsAppMigrationDialog(
            onDismiss = { showMigrationDialog = false },
            onImport = { chatText, dateOrder ->
                coroutineScope.launch {
                    val (imported, ambiguous) = viewModel.importWhatsAppMigration(chatText, dateOrder)
                    val msg = if (ambiguous > 0) {
                        "Imported $imported items. $ambiguous dates were ambiguous and preserved as unparseable to avoid guessing."
                    } else {
                        "Imported $imported items successfully."
                    }
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
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
                        text = "Settings & Portability",
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

                    StorageStatRow("Original Files (Deduplicated)", formatBytes(stats.originalsBytes))
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

            // Backend AI Pairing Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (isPaired) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                                contentDescription = null,
                                tint = if (isPaired) Color(0xFF10B981) else MaterialTheme.colorScheme.outline
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Backend AI Pairing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (isPaired) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = if (isPaired) "Paired" else "Not Paired",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPaired) Color(0xFF047857) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Endpoint: ${viewModel.aiGateway.backendBaseUrl}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        if (isPaired) {
                            OutlinedButton(
                                onClick = {
                                    viewModel.unpairBackend()
                                    Toast.makeText(context, "Device unpaired", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Text("Unpair")
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Button(
                            onClick = { showPairingDialog = true },
                            modifier = Modifier.testTag("pair_backend_button")
                        ) {
                            Text(if (isPaired) "Reconnect" else "Pair Device")
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
                            Text("Suspends all automatic AI analysis and embedding jobs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = pauseAi,
                            onCheckedChange = {
                                pauseAi = it
                                prefs.edit().putBoolean("ai_paused", it).apply()
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
                            Text("Require unmetered network connection for Gemini calls", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline)
                        }
                        Switch(
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                prefs.edit().putBoolean("ai_wifi_only", it).apply()
                            }
                        )
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
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = Color(0xFF10B981))
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
