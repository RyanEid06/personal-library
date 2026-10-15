package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import com.example.model.CollectionItem
import com.example.model.ItemKind
import com.example.model.LibraryItem
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
                    Icon(Icons.Filled.AllInclusive, contentDescription = null, modifier = Modifier.size(16.dp))
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
                    Icon(getKindIcon(kind), contentDescription = null, modifier = Modifier.size(16.dp))
                },
                modifier = Modifier.testTag("filter_chip_${kind.name.lowercase()}")
            )
        }
    }
}

fun getKindIcon(kind: ItemKind): ImageVector {
    return when (kind) {
        ItemKind.PHOTO -> Icons.Filled.PhotoLibrary
        ItemKind.STICKER -> Icons.Filled.SentimentVerySatisfied
        ItemKind.VIDEO -> Icons.Filled.VideoLibrary
        ItemKind.AUDIO -> Icons.Filled.Audiotrack
        ItemKind.TEXT -> Icons.Filled.Notes
        ItemKind.LINK -> Icons.Filled.Link
        ItemKind.DOCUMENT -> Icons.Filled.Description
        ItemKind.FILE -> Icons.Filled.FolderZip
    }
}

fun getKindColor(kind: ItemKind): Color {
    return when (kind) {
        ItemKind.PHOTO -> Color(0xFF3B82F6)
        ItemKind.STICKER -> Color(0xFFEC4899)
        ItemKind.VIDEO -> Color(0xFFEF4444)
        ItemKind.AUDIO -> Color(0xFF8B5CF6)
        ItemKind.TEXT -> Color(0xFF10B981)
        ItemKind.LINK -> Color(0xFF06B6D4)
        ItemKind.DOCUMENT -> Color(0xFFF59E0B)
        ItemKind.FILE -> Color(0xFF6B7280)
    }
}

@Composable
fun ItemCard(
    item: LibraryItem,
    onClick: () -> Unit,
    onFavoriteToggle: () -> Unit,
    onDelete: () -> Unit,
    isSelectMode: Boolean = false,
    isSelected: Boolean = false,
    onSelectToggle: () -> Unit = {},
    collectionName: String? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val kindColor = getKindColor(item.kind)
    val dateFormatter = remember { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()) }

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
            // Header: Type badge, collection tag, and actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                text = item.kind.displayName,
                                color = kindColor,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    if (collectionName != null) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = collectionName,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.reminderTime != null) {
                        Icon(
                            imageVector = Icons.Filled.Alarm,
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
                            imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                            contentDescription = if (item.isFavorite) "Favorited" else "Favorite",
                            tint = if (item.isFavorite) Color(0xFFF59E0B) else MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Visual Thumbnail or Icon Preview
            if (item.localFilePath != null && (item.kind == ItemKind.PHOTO || item.kind == ItemKind.STICKER)) {
                AsyncImage(
                    model = File(item.localFilePath),
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
            if (item.content.isNotBlank() && item.kind != ItemKind.PHOTO && item.kind != ItemKind.STICKER) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // AI Description snippet if available
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
                        Icons.Filled.AutoAwesome,
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

            // Footer: Date and Share / Delete actions
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
                        onClick = {
                            shareItem(context, item)
                        },
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

fun shareItem(context: Context, item: LibraryItem) {
    try {
        if (item.localFilePath != null) {
            val file = File(item.localFilePath)
            if (file.exists()) {
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
        }

        // Text or Link share
        val shareText = if (item.kind == ItemKind.LINK) item.content else "${item.title}\n\n${item.content}"
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

fun openItemExternally(context: Context, item: LibraryItem) {
    try {
        if (item.kind == ItemKind.LINK) {
            val url = if (item.content.startsWith("http")) item.content.lines().first() else "https://${item.content.lines().first()}"
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            context.startActivity(intent)
            return
        }

        if (item.localFilePath != null) {
            val file = File(item.localFilePath)
            if (file.exists()) {
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
        }

        Toast.makeText(context, "No external application can open this item", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
