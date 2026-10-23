package com.example

import android.Manifest
import android.content.Intent
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.ItemWithCollections
import com.example.model.SharePayload
import com.example.reminder.ReminderReceiver
import com.example.ui.MainViewModel
import com.example.ui.components.AddContentDialog
import com.example.ui.components.ItemDetailsDialog
import com.example.ui.components.EditItemDialog
import com.example.ui.components.SharePreviewDialog
import com.example.ui.components.VoiceSearchDialog
import com.example.ui.screens.CollectionsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SearchScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.TrashScreen
import com.example.ui.theme.PersonalLibraryTheme

enum class AppDestination(val label: String) {
    LIBRARY("Library"),
    COLLECTIONS("Collections"),
    SEARCH("Search"),
    SETTINGS("Settings"),
    TRASH("Trash")
}

class MainActivity : androidx.fragment.app.FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var notificationTargetItemId by mutableStateOf<String?>(null)
    private val appLockPreferences by lazy { getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE) }
    private val appLockPreferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == null || key == "lock_enabled") applyAppLockTaskPrivacy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        com.example.ui.theme.ThemePreferences.applyActivityTheme(this)
        super.onCreate(savedInstanceState)
        applyAppLockTaskPrivacy()
        appLockPreferences.registerOnSharedPreferenceChangeListener(appLockPreferenceListener)
        enableEdgeToEdge()
        notificationTargetItemId = intent?.getStringExtra(ReminderReceiver.EXTRA_ITEM_ID)

        if (savedInstanceState == null) handleIntent(intent)
        else if (savedInstanceState.getBoolean("share_preview_pending") && viewModel.incomingShare.value == null) {
            android.widget.Toast.makeText(this, "Share preview expired after restart. Share the files again to grant access.", android.widget.Toast.LENGTH_LONG).show()
        }

        setContent {
            val themeMode by viewModel.themeMode.collectAsState()
            val darkTheme = themeMode.isDark(isSystemInDarkTheme())
            SideEffect {
                val bars = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { darkTheme }
                enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
            }
            PersonalLibraryTheme(darkTheme = darkTheme) {
                val isLocked by viewModel.appLockManager.isLocked.collectAsState()
                if (isLocked) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(32.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = "Locked",
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "Memora is Locked",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Authenticate using your device biometric or screen lock PIN/password.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    viewModel.appLockManager.authenticate(
                                        this@MainActivity,
                                        onSuccess = {},
                                        onError = {}
                                    )
                                },
                                modifier = Modifier.testTag("unlock_app_button")
                            ) {
                                Icon(Icons.Filled.Fingerprint, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Unlock Library")
                            }
                        }
                    }
                } else {
                    MainAppScaffold(
                        viewModel = viewModel,
                        intentTargetItemId = notificationTargetItemId,
                        onIntentTargetConsumed = { itemId ->
                            if (notificationTargetItemId == itemId) {
                                notificationTargetItemId = null
                                intent?.removeExtra(ReminderReceiver.EXTRA_ITEM_ID)
                            }
                        }
                    )
                }
            }
        }
    }



    private fun applyAppLockTaskPrivacy() {
        // Protect authenticated sessions too: a task snapshot can precede the lock UI.
        val enabled = appLockPreferences.getBoolean("lock_enabled", false)
        // Some launchers still show a live Overview preview with only the Recents policy.
        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!enabled)
    }

    override fun onDestroy() {
        appLockPreferences.unregisterOnSharedPreferenceChangeListener(appLockPreferenceListener)
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // Some transitions skip the top-resumed callback.
        if (!isChangingConfigurations) {
            viewModel.appLockManager.lock()
        }
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        // Overview can remove the top position while keeping this activity resumed.
        if (!isTopResumedActivity && !isChangingConfigurations) {
            viewModel.appLockManager.lock()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("share_preview_pending", viewModel.incomingShare.value != null)
        super.onSaveInstanceState(outState)
    }

    private fun handleIntent(intent: Intent?) {
        notificationTargetItemId = intent?.getStringExtra(ReminderReceiver.EXTRA_ITEM_ID)
        val payload = intent?.let(com.example.capture.SharePayloadDecoder::decode) ?: return
        if (payload.text != null || payload.uris.isNotEmpty() || payload.rejectedCount > 0) viewModel.setIncomingShare(payload)
        // Consume delivery immediately. A configuration/process restore must not replay transient grants.
        setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
    }
}

@Composable
fun MainAppScaffold(
    viewModel: MainViewModel,
    intentTargetItemId: String?,
    onIntentTargetConsumed: (String) -> Unit = {}
) {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestination.LIBRARY) }
    var activeDetailItemId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingItem by remember { mutableStateOf<ItemWithCollections?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showVoiceSearchDialog by remember { mutableStateOf(false) }
    val destinationStates = rememberSaveableStateHolder()

    val incomingShare by viewModel.incomingShare.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val allItems by viewModel.items.collectAsState()
    val activeDetailItem = allItems.find { it.item.id == activeDetailItemId }
    val capture by viewModel.captureState.collectAsState()
    val isBatchMode by viewModel.isBatchMode.collectAsState()
    var consumedTargetItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val latestOnIntentTargetConsumed by rememberUpdatedState(onIntentTargetConsumed)

    // Wait for the item to load, then consume this delivery before later database emissions.
    LaunchedEffect(intentTargetItemId, allItems, activeDetailItemId) {
        if (intentTargetItemId == null) {
            consumedTargetItemId = null
        } else if (intentTargetItemId != consumedTargetItemId && activeDetailItemId == null) {
            val item = allItems.find { it.item.id == intentTargetItemId }
            if (item != null) {
                consumedTargetItemId = intentTargetItemId
                activeDetailItemId = item.item.id
                latestOnIntentTargetConsumed(intentTargetItemId)
            }
        }
    }

    fun navigateUp() {
        currentDestination = if (currentDestination == AppDestination.TRASH) AppDestination.SETTINGS else AppDestination.LIBRARY
    }

    // Dialogs handle their own dismissal; screen selection handles Back before navigation.
    val hasModal = activeDetailItem != null || editingItem != null || showAddDialog ||
        showVoiceSearchDialog || incomingShare != null
    BackHandler(enabled = currentDestination != AppDestination.LIBRARY && !hasModal && !isBatchMode) {
        navigateUp()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            if (capture.saving || capture.message != null) {
                Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(if (capture.saving) "Saving… ${capture.saved} committed, ${capture.failed} failed of ${capture.total}" else capture.message.orEmpty(),
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { if (capture.saving) viewModel.cancelCapture() else viewModel.dismissCaptureMessage() }) {
                            Text(if (capture.saving) "Cancel" else "Dismiss")
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (currentDestination in listOf(AppDestination.LIBRARY, AppDestination.COLLECTIONS, AppDestination.SEARCH)) {
                NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.LIBRARY,
                        onClick = { viewModel.clearSelection(); currentDestination = AppDestination.LIBRARY },
                        icon = {
                            Icon(
                                if (currentDestination == AppDestination.LIBRARY) Icons.Filled.LocalLibrary else Icons.Outlined.LocalLibrary,
                                contentDescription = "Library"
                            )
                        },
                        label = { Text("Library") },
                        modifier = Modifier.testTag("nav_item_library")
                    )

                    NavigationBarItem(
                        selected = currentDestination == AppDestination.COLLECTIONS,
                        onClick = { viewModel.clearSelection(); currentDestination = AppDestination.COLLECTIONS },
                        icon = {
                            Icon(
                                if (currentDestination == AppDestination.COLLECTIONS) Icons.Filled.Folder else Icons.Outlined.Folder,
                                contentDescription = "Collections"
                            )
                        },
                        label = { Text("Collections") },
                        modifier = Modifier.testTag("nav_item_collections")
                    )

                    NavigationBarItem(
                        selected = currentDestination == AppDestination.SEARCH,
                        onClick = { viewModel.clearSelection(); currentDestination = AppDestination.SEARCH },
                        icon = {
                            Icon(
                                if (currentDestination == AppDestination.SEARCH) Icons.Filled.Search else Icons.Outlined.Search,
                                contentDescription = "Search"
                            )
                        },
                        label = { Text("Search") },
                        modifier = Modifier.testTag("nav_item_search")
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            destinationStates.SaveableStateProvider(currentDestination.name) {
            when (currentDestination) {
                AppDestination.LIBRARY -> {
                    LibraryScreen(
                        viewModel = viewModel,
                        onOpenItemDetail = { activeDetailItemId = it.item.id },
                        onOpenVoiceSearch = { showVoiceSearchDialog = true },
                        onOpenSettings = { viewModel.clearSelection(); currentDestination = AppDestination.SETTINGS },
                        onOpenAddDialog = { showAddDialog = true }
                    )
                }
                AppDestination.COLLECTIONS -> {
                    CollectionsScreen(
                        viewModel = viewModel,
                        onCollectionSelected = { colId ->
                            viewModel.selectCollection(colId)
                            currentDestination = AppDestination.LIBRARY
                        },
                        onSelectCollectionItems = { colId ->
                            viewModel.selectCollectionItems(colId)
                            currentDestination = AppDestination.LIBRARY
                        }
                    )
                }
                AppDestination.SEARCH -> {
                    SearchScreen(
                        viewModel = viewModel,
                        onOpenItemDetail = { activeDetailItemId = it.item.id },
                        onOpenVoiceSearch = { showVoiceSearchDialog = true }
                    )
                }
                AppDestination.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateToTrash = { currentDestination = AppDestination.TRASH },
                        onNavigateBack = ::navigateUp
                    )
                }
                AppDestination.TRASH -> {
                    TrashScreen(
                        viewModel = viewModel,
                        onNavigateBack = ::navigateUp
                    )
                }
            }
            }
        }
    }

    // Modal: Item Details Bottom Sheet
    if (activeDetailItem != null) {
        val currentItem = activeDetailItem
        ItemDetailsDialog(
            itemWithCols = currentItem,
            fileResolver = { hash ->
                viewModel.repository.assetStore.getBlobFile(hash)
            },
            collections = collections,
            onDismiss = { activeDetailItemId = null },
            onFavoriteToggle = { viewModel.toggleFavorite(currentItem.item.id) },
            onDelete = {
                viewModel.moveToTrash(currentItem.item.id)
                activeDetailItemId = null
            },
            onCollectionsChange = { newColIds ->
                viewModel.setItemCollections(currentItem.item.id, newColIds)
            },
            onSetReminder = { timeMillis, isExact, isDateOnly, localDate ->
                viewModel.setReminder(currentItem.item.id, timeMillis, isExact, isDateOnly, localDate)
            },
            aiExcluded = viewModel.isItemExcludedFromAi(currentItem.item.id),
            onAiExcludedChange = { viewModel.setItemAiExcluded(currentItem.item.id, it) },
            onRetryAi = { viewModel.retryAiAnalysis(currentItem.item.id) },
            onEdit = { editingItem = currentItem; activeDetailItemId = null }
        )
    }

    editingItem?.let { selected ->
        EditItemDialog(
            item = selected.item,
            onDismiss = { editingItem = null },
            onSave = { title, content -> viewModel.editItem(selected.item.id, title, content) }
        )
    }

    // Modal: Add Content Dialog
    if (showAddDialog) {
        AddContentDialog(
            collections = collections,
            onDismiss = { showAddDialog = false },
            onCreateNote = { title, content, colId ->
                viewModel.createNote(title, content, colId)
            },
            onSaveLink = { url, title, note, colId ->
                viewModel.saveLink(url, title, note, colId)
            },
            onImportFiles = { uris, colId ->
                viewModel.importUris(uris, colId)
            }
        )
    }

    // Modal: Share Preview Dialog (when launched with ACTION_SEND)
    if (incomingShare != null) {
        val payload = incomingShare!!
        SharePreviewDialog(
            payload = payload,
            collections = collections,
            onDismiss = { viewModel.setIncomingShare(null) },
            onConfirmSave = { targetCollectionId ->
                viewModel.confirmShare(targetCollectionId)
            },
            resolveDefaultCollectionId = { viewModel.getOrCreateRandomLinksCollection().id }
        )
    }

    // Modal: Voice Search Dialog
    if (showVoiceSearchDialog) {
        VoiceSearchDialog(
            onDismiss = { showVoiceSearchDialog = false },
            onQueryConfirmed = { query ->
                viewModel.setSearchQuery(query)
                currentDestination = AppDestination.SEARCH
            }
        )
    }
}
