package com.example

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        handleIntent(intent)

        setContent {
            PersonalLibraryTheme {
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
                                "Personal Library is Locked",
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
                        intentTargetItemId = intent?.getStringExtra(ReminderReceiver.EXTRA_ITEM_ID)
                    )
                }
            }
        }
    }

    private var lastHandledIntentHash: Int = 0

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            viewModel.appLockManager.lock()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        // Prevent duplicate processing of the same intent on configuration change
        val intentHash = System.identityHashCode(intent)
        if (intentHash == lastHandledIntentHash) return
        lastHandledIntentHash = intentHash

        val type = intent.type ?: "application/octet-stream"
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)

        val uris = mutableListOf<String>()

        // 1. ClipData items (standard, robust modern Android capture)
        val clipData = intent.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val clipUri = clipData.getItemAt(i).uri
                if (clipUri != null && !uris.contains(clipUri.toString())) {
                    uris.add(clipUri.toString())
                }
            }
        }

        // 2. EXTRA_STREAM single send
        if (action == Intent.ACTION_SEND) {
            val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
            }
            if (streamUri != null && !uris.contains(streamUri.toString())) {
                uris.add(streamUri.toString())
            }
        }

        // 3. EXTRA_STREAM multiple send
        if (action == Intent.ACTION_SEND_MULTIPLE) {
            val streamUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }
            if (streamUris != null) {
                for (u in streamUris) {
                    if (u != null && !uris.contains(u.toString())) {
                        uris.add(u.toString())
                    }
                }
            }
        }

        if (text != null || uris.isNotEmpty()) {
            viewModel.setIncomingShare(
                SharePayload(
                    text = text,
                    uris = uris,
                    mimeType = type
                )
            )
        }
    }
}

@Composable
fun MainAppScaffold(
    viewModel: MainViewModel,
    intentTargetItemId: String?
) {
    var currentDestination by remember { mutableStateOf(AppDestination.LIBRARY) }
    var activeDetailItem by remember { mutableStateOf<ItemWithCollections?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showVoiceSearchDialog by remember { mutableStateOf(false) }

    val incomingShare by viewModel.incomingShare.collectAsState()
    val collections by viewModel.collections.collectAsState()
    val allItems by viewModel.items.collectAsState()

    // Handle launch from notification
    LaunchedEffect(intentTargetItemId, allItems) {
        if (intentTargetItemId != null && activeDetailItem == null) {
            val item = allItems.find { it.item.id == intentTargetItemId }
            if (item != null) {
                activeDetailItem = item
            }
        }
    }

    // BackHandler: If on a sub-screen, return to LIBRARY
    BackHandler(enabled = currentDestination != AppDestination.LIBRARY) {
        currentDestination = AppDestination.LIBRARY
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.systemBars,
        bottomBar = {
            if (currentDestination in listOf(AppDestination.LIBRARY, AppDestination.COLLECTIONS, AppDestination.SEARCH)) {
                NavigationBar(modifier = Modifier.testTag("bottom_nav_bar")) {
                    NavigationBarItem(
                        selected = currentDestination == AppDestination.LIBRARY,
                        onClick = { currentDestination = AppDestination.LIBRARY },
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
                        onClick = { currentDestination = AppDestination.COLLECTIONS },
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
                        onClick = { currentDestination = AppDestination.SEARCH },
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
            when (currentDestination) {
                AppDestination.LIBRARY -> {
                    LibraryScreen(
                        viewModel = viewModel,
                        onOpenItemDetail = { activeDetailItem = it },
                        onOpenVoiceSearch = { showVoiceSearchDialog = true },
                        onOpenSettings = { currentDestination = AppDestination.SETTINGS },
                        onOpenAddDialog = { showAddDialog = true }
                    )
                }
                AppDestination.COLLECTIONS -> {
                    CollectionsScreen(
                        viewModel = viewModel,
                        onCollectionSelected = { colId ->
                            viewModel.selectCollection(colId)
                            currentDestination = AppDestination.LIBRARY
                        }
                    )
                }
                AppDestination.SEARCH -> {
                    SearchScreen(
                        viewModel = viewModel,
                        onOpenItemDetail = { activeDetailItem = it },
                        onOpenVoiceSearch = { showVoiceSearchDialog = true }
                    )
                }
                AppDestination.SETTINGS -> {
                    SettingsScreen(
                        viewModel = viewModel,
                        onNavigateToTrash = { currentDestination = AppDestination.TRASH }
                    )
                }
                AppDestination.TRASH -> {
                    TrashScreen(
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    // Modal: Item Details Bottom Sheet
    if (activeDetailItem != null) {
        val currentItem = allItems.find { it.item.id == activeDetailItem!!.item.id } ?: activeDetailItem!!
        ItemDetailsDialog(
            itemWithCols = currentItem,
            fileResolver = { hash ->
                viewModel.repository.assetStore.getBlobFile(hash)
            },
            collections = collections,
            onDismiss = { activeDetailItem = null },
            onFavoriteToggle = { viewModel.toggleFavorite(currentItem.item.id) },
            onDelete = {
                viewModel.moveToTrash(currentItem.item.id)
                activeDetailItem = null
            },
            onCollectionsChange = { newColIds ->
                viewModel.setItemCollections(currentItem.item.id, newColIds)
            },
            onSetReminder = { timeMillis ->
                viewModel.setReminder(currentItem.item.id, timeMillis)
            }
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
                if (payload.uris.isNotEmpty()) {
                    val uriList = payload.uris.map { Uri.parse(it) }
                    viewModel.importUris(uriList, targetCollectionId)
                }
                if (payload.text != null) {
                    val text = payload.text
                    if (text.startsWith("http://") || text.startsWith("https://")) {
                        viewModel.saveLink(text, "", "", targetCollectionId)
                    } else {
                        viewModel.createNote("", text, targetCollectionId)
                    }
                }
                viewModel.setIncomingShare(null)
            }
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
