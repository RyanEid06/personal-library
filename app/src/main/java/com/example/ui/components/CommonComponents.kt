package com.example.ui.components

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.example.ui.theme.LocalAppDarkTheme
import com.personallibrary.core.model.ItemKind
import com.personallibrary.core.storage.ContentClassifier
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TypeFilterChipStrip(
    selectedKind: ItemKind?,
    onKindSelected: (ItemKind?) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .fillMaxWidth()
            .testTag("type_filter_strip"),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            FilterChip(
                selected = selectedKind == null,
                onClick = { onKindSelected(null) },
                label = { Text("All") },
                leadingIcon = {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("filter_chip_all")
            )
        }
        items(ItemKind.values()) { kind ->
            FilterChip(
                selected = selectedKind == kind,
                onClick = { onKindSelected(kind) },
                label = { Text(kind.displayName) },
                leadingIcon = {
                    Icon(getKindIcon(kind.name), contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("filter_chip_${kind.name.lowercase()}")
            )
        }
    }
}

fun getKindIcon(kindStr: String): ImageVector {
    return when (kindStr) {
        "PHOTO" -> Icons.Filled.Photo
        "STICKER" -> Icons.Filled.Face
        "GIF" -> Icons.Filled.Gif
        "VIDEO" -> Icons.Filled.PlayArrow
        "AUDIO" -> Icons.Filled.Headphones
        "TEXT" -> Icons.Filled.Edit
        "LINK" -> Icons.Filled.Link
        "DOCUMENT" -> Icons.Filled.Description
        else -> Icons.Filled.Folder
    }
}

@Composable
fun getKindColor(kindStr: String): Color = kindColorForTheme(kindStr, LocalAppDarkTheme.current)

internal fun kindColorForTheme(kindStr: String, darkTheme: Boolean): Color {
    // Keep each type's hue, using readable foregrounds on both the card and its tinted badge.
    if (darkTheme) return when (kindStr) {
        "PHOTO" -> Color(0xFF93C5FD)
        "STICKER" -> Color(0xFFF9A8D4)
        "GIF" -> Color(0xFFF9A8D4)
        "VIDEO" -> Color(0xFFFCA5A5)
        "AUDIO" -> Color(0xFFC4B5FD)
        "TEXT" -> Color(0xFF6EE7B7)
        "LINK" -> Color(0xFF67E8F9)
        "DOCUMENT" -> Color(0xFFFCD34D)
        else -> Color(0xFFCBD5E1)
    }
    return when (kindStr) {
        "PHOTO" -> Color(0xFF1D4ED8)
        "STICKER" -> Color(0xFF9D174D)
        "GIF" -> Color(0xFF9D174D)
        "VIDEO" -> Color(0xFFB91C1C)
        "AUDIO" -> Color(0xFF6D28D9)
        "TEXT" -> Color(0xFF065F46)
        "LINK" -> Color(0xFF155E75)
        "DOCUMENT" -> Color(0xFF92400E)
        else -> Color(0xFF4B5563)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemCard(
    itemWithCols: ItemWithCollections,
    fileResolver: (hash: String) -> File?,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDelete: () -> Unit,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    matchingSnippet: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    val item = itemWithCols.item
    val kindColor = getKindColor(item.kind)
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

    val blobFile = remember(item.contentHash) {
        item.contentHash?.let { fileResolver(it) }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("item_card_${item.id}")
            .semantics { selected = isSelected }
            .combinedClickable(
                onClick = { if (isSelectMode) onSelectToggle() else onClick() },
                onLongClickLabel = "Select item",
                onLongClick = onSelectToggle
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Header: Type badge and Collections
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isSelectMode) {
                        Checkbox(
                            checked = isSelected,
                            onCheckedChange = { onSelectToggle() },
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    Surface(
                        color = kindColor.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                getKindIcon(item.kind),
                                contentDescription = null,
                                tint = kindColor,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = item.kind,
                                color = kindColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (itemWithCols.collections.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            items(itemWithCols.collections) { col ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = col.name,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        fontSize = 11.sp,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.reminderTime != null) {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = "Reminder set",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp).padding(end = 4.dp)
                        )
                    }
                    IconButton(
                        onClick = onFavoriteToggle,
                        enabled = !isSelectMode,
                        modifier = Modifier.size(48.dp).testTag("fav_button_${item.id}")
                    ) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = if (item.isFavorite) "Remove from Favorites" else "Add to Favorites",
                            tint = if (item.isFavorite) getKindColor("DOCUMENT") else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Visual Thumbnail
            if (blobFile != null && (item.kind == "PHOTO" || item.kind == "STICKER" || item.kind == "GIF")) {
                AsyncImage(
                    model = blobFile,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Title
            Text(
                text = item.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            // Matching text snippet
            if (!matchingSnippet.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = matchingSnippet,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 12.sp
                        )
                    }
                }
            } else if (item.content.isNotBlank() && item.kind != "PHOTO" && item.kind != "STICKER") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // AI Description snippet
            if (!item.aiDescription.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = "AI Concept",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = item.aiDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Saved ${dateFormatter.format(Date(item.createdAt))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                Row {
                    IconButton(
                        onClick = { shareItemEntity(context, item, blobFile, shareScope) },
                        enabled = !isSelectMode,
                        modifier = Modifier.size(48.dp).testTag("share_btn_${item.id}")
                    ) {
                        Icon(
                            Icons.Outlined.Share,
                            contentDescription = "Share",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        enabled = !isSelectMode,
                        modifier = Modifier.size(48.dp).testTag("trash_btn_${item.id}")
                    ) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = "Move to Trash",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ItemGridCard(
    itemWithCols: ItemWithCollections,
    fileResolver: (hash: String) -> File?,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    matchingSnippet: String? = null
) {
    val context = LocalContext.current
    val shareScope = rememberCoroutineScope()
    val item = itemWithCols.item
    val kindColor = getKindColor(item.kind)
    val blobFile = remember(item.contentHash) {
        item.contentHash?.let { fileResolver(it) }
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics { selected = isSelected }
            .combinedClickable(
                onClick = { if (isSelectMode) onSelectToggle() else onClick() },
                onLongClickLabel = "Select item",
                onLongClick = onSelectToggle
            )
            .testTag("item_grid_card_${item.id}"),
        colors = CardDefaults.cardColors(containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (isSelectMode) {
                Checkbox(checked = isSelected, onCheckedChange = { onSelectToggle() }, modifier = Modifier.size(48.dp))
            }
            // Visual header or icon
            if (blobFile != null && (item.kind == "PHOTO" || item.kind == "STICKER" || item.kind == "GIF")) {
                AsyncImage(
                    model = blobFile,
                    contentDescription = item.title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Surface(
                    color = kindColor.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().height(110.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(getKindIcon(item.kind), contentDescription = null, tint = kindColor, modifier = Modifier.size(28.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = matchingSnippet?.takeIf { it.isNotBlank() }
                    ?: item.content.takeUnless { item.kind in setOf("PHOTO", "STICKER", "GIF") }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("grid_snippet_${item.id}")
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.kind,
                    color = kindColor,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (item.reminderTime != null) {
                    Icon(Icons.Filled.Notifications, contentDescription = "Reminder set", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
                IconButton(
                    onClick = onFavoriteToggle,
                    enabled = !isSelectMode,
                    modifier = Modifier.size(48.dp).testTag("fav_button_${item.id}")
                ) {
                    Icon(
                        imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (item.isFavorite) "Remove from Favorites" else "Add to Favorites",
                        tint = if (item.isFavorite) getKindColor("DOCUMENT") else MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(
                    onClick = { shareItemEntity(context, item, blobFile, shareScope) },
                    enabled = !isSelectMode,
                    modifier = Modifier.size(48.dp).testTag("share_btn_${item.id}")
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = "Share", tint = MaterialTheme.colorScheme.outline)
                }
                IconButton(onClick = onDelete, enabled = !isSelectMode, modifier = Modifier.size(48.dp).testTag("trash_btn_${item.id}")) {
                    Icon(Icons.Outlined.Delete, contentDescription = "Move to Trash", tint = MaterialTheme.colorScheme.outline)
                }
            }
        }
    }
}

/** Keep exports after the chooser pauses Memora; receivers may read them asynchronously. */
internal fun prepareOriginalForExport(context: Context, item: ItemEntity, original: File): File {
    val root = File(context.cacheDir, "shared_originals").apply { mkdirs() }
    val expiry = System.currentTimeMillis() - 48L * 60 * 60 * 1000
    root.listFiles()?.filter { it.isDirectory && it.lastModified() < expiry && it.canonicalFile.parentFile == root.canonicalFile }
        ?.forEach { it.deleteRecursively() }
    val mimeExtension = item.mimeType?.substringBefore(';')?.let { android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(it.lowercase(Locale.ROOT)) }
    var name = (item.originalFileName?.takeIf { it.isNotBlank() } ?: item.title)
        .replace('\\', '/').substringAfterLast('/')
        .replace(Regex("[\\p{Cntrl}<>:\"/\\\\|?*]"), "_")
        .trim().trim('.').ifBlank { "original" }
    if (name.length > 180) {
        val suffix = name.substringAfterLast('.', "").takeIf { it.length in 1..12 }?.let { ".$it" }.orEmpty()
        name = name.take(180 - suffix.length) + suffix
    }
    if (name.substringAfterLast('.', "").isEmpty() && mimeExtension != null) name += ".$mimeExtension"
    val directory = File(root, UUID.randomUUID().toString()).apply { check(mkdirs()) }
    val target = File(directory, name)
    try { original.copyTo(target) } catch (error: Exception) { directory.deleteRecursively(); throw error }
    return target
}

private fun exportedOriginalUri(context: Context, item: ItemEntity, file: File): Uri =
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", prepareOriginalForExport(context, item, file))

internal fun originalMimeType(item: ItemEntity, providerMimeType: String?): String {
    val resolved = ContentClassifier.resolveMimeType(item.mimeType, item.originalFileName ?: item.title)
    return if (resolved.equals("application/octet-stream", ignoreCase = true)) providerMimeType ?: resolved else resolved
}

private fun launchPreparedIntent(context: Context, scope: CoroutineScope?, prepare: () -> Intent) {
    fun fail() { Toast.makeText(context, "Could not share or open this item. Please try again.", Toast.LENGTH_LONG).show() }
    if (scope == null) {
        try { context.startActivity(prepare()) } catch (_: Exception) { fail() }
    } else scope.launch {
        try {
            val intent = withContext(Dispatchers.IO) { prepare() }
            context.startActivity(intent)
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { fail() }
    }
}

fun shareItemEntity(context: Context, item: ItemEntity, file: File?, scope: CoroutineScope? = null) {
    launchPreparedIntent(context, scope) {
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            if (file != null && file.exists()) {
                val uri = exportedOriginalUri(context, item, file)
                type = originalMimeType(item, context.contentResolver.getType(uri))
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, item.originalFileName ?: item.title, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                check(item.contentHash == null) { "Original file is unavailable" }
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, if (item.kind == "LINK") item.content else "${item.title}\n\n${item.content}")
            }
        }
        Intent.createChooser(send, "Share via")
    }
}

fun openItemEntityExternally(context: Context, item: ItemEntity, file: File?, scope: CoroutineScope? = null) {
    try {
        if (item.kind == "LINK") {
            val url = item.content.lineSequence().first().trim()
            val parsed = Uri.parse(url)
            val scheme = parsed.scheme?.lowercase(Locale.ROOT)
            val uri = when {
                scheme == null -> Uri.parse("https://$url") // Preserve opening of older schemeless saved links.
                scheme in setOf("http", "https", "mailto", "tel", "sms", "geo", "ftp") -> parsed
                else -> {
                    Toast.makeText(context, "This link scheme cannot be opened", Toast.LENGTH_SHORT).show()
                    return
                }
            }
            val intent = Intent(Intent.ACTION_VIEW, uri)
            context.startActivity(intent)
            return
        }

        if (file != null && file.exists()) {
            launchPreparedIntent(context, scope) {
                val uri = exportedOriginalUri(context, item, file)
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, originalMimeType(item, context.contentResolver.getType(uri)))
                    clipData = ClipData.newUri(context.contentResolver, item.title, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            return
        }

        Toast.makeText(context, "No external application can open this item", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
