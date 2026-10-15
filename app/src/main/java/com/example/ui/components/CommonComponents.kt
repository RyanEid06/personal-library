package com.example.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.example.data.CollectionEntity
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import com.personallibrary.core.model.ItemKind
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
                    Icon(Icons.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp))
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
        "VIDEO" -> Icons.Filled.PlayArrow
        "AUDIO" -> Icons.Filled.Notifications
        "TEXT" -> Icons.Filled.Edit
        "LINK" -> Icons.Filled.Share
        "DOCUMENT" -> Icons.Filled.Info
        else -> Icons.Filled.Folder
    }
}

fun getKindColor(kindStr: String): Color {
    return when (kindStr) {
        "PHOTO" -> Color(0xFF3B82F6)
        "STICKER" -> Color(0xFFEC4899)
        "VIDEO" -> Color(0xFFEF4444)
        "AUDIO" -> Color(0xFF8B5CF6)
        "TEXT" -> Color(0xFF10B981)
        "LINK" -> Color(0xFF06B6D4)
        "DOCUMENT" -> Color(0xFFF59E0B)
        else -> Color(0xFF6B7280)
    }
}

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
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
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
            .clickable {
                if (isSelectMode) onSelectToggle() else onClick()
            },
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
                            modifier = Modifier.size(24.dp).padding(end = 8.dp)
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

                    // Many-to-many collections pills
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
                        modifier = Modifier.size(32.dp).testTag("fav_button_${item.id}")
                    ) {
                        Icon(
                            imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                            contentDescription = if (item.isFavorite) "Favorited" else "Favorite",
                            tint = if (item.isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Visual Thumbnail
            if (blobFile != null && (item.kind == "PHOTO" || item.kind == "STICKER")) {
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

            // Content snippet
            if (item.content.isNotBlank() && item.kind != "PHOTO" && item.kind != "STICKER") {
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
                    fontSize = 11.sp
                )

                Row {
                    IconButton(
                        onClick = { shareItemEntity(context, item, blobFile) },
                        modifier = Modifier.size(28.dp).testTag("share_btn_${item.id}")
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
                        modifier = Modifier.size(28.dp).testTag("trash_btn_${item.id}")
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

fun shareItemEntity(context: Context, item: ItemEntity, file: File?) {
    try {
        if (file != null && file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = item.mimeType ?: "*/*"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, item.title)
                if (item.content.isNotBlank()) {
                    putExtra(Intent.EXTRA_TEXT, item.content)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share ${item.title}"))
            return
        }

        // Text or Link share
        val shareText = if (item.kind == "LINK") item.content else "${item.title}\n\n${item.content}"
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, item.title)
            putExtra(Intent.EXTRA_TEXT, shareText)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun openItemEntityExternally(context: Context, item: ItemEntity, file: File?) {
    try {
        if (item.kind == "LINK") {
            val url = if (item.content.startsWith("http")) item.content.lines().first() else "https://${item.content.lines().first()}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            return
        }

        if (file != null && file.exists()) {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
            return
        }

        Toast.makeText(context, "No external application can open this item", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
