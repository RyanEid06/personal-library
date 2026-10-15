package com.example.model

import kotlinx.serialization.Serializable

enum class ItemKind(val displayName: String) {
    PHOTO("Photos"),
    STICKER("Stickers"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    TEXT("Notes & Text"),
    LINK("Links"),
    DOCUMENT("Documents"),
    FILE("Other Files")
}

@Serializable
data class LibraryItem(
    val id: String,
    val title: String,
    val content: String,
    val kind: ItemKind,
    val isFavorite: Boolean = false,
    val isTrash: Boolean = false,
    val trashTimestamp: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sourceDate: Long? = null,
    val reminderTime: Long? = null,
    val isReminderDone: Boolean = false,
    val collectionId: String? = null,
    val localFilePath: String? = null,
    val originalFileName: String? = null,
    val mimeType: String? = null,
    val fileSizeBytes: Long = 0L,
    val aiDescription: String? = null,
    val aiConcepts: String? = null,
    val aiStatus: String = "READY"
)

@Serializable
data class CollectionItem(
    val id: String,
    val name: String,
    val iconName: String = "folder",
    val colorHex: String = "#3B82F6",
    val createdAt: Long = System.currentTimeMillis()
)

enum class SortOrder(val label: String) {
    SAVED_NEWEST("Newest Saved"),
    SAVED_OLDEST("Oldest Saved"),
    SOURCE_NEWEST("Source Date"),
    TITLE_AZ("Title (A-Z)")
}

enum class DateFilter(val label: String) {
    ALL_TIME("All Time"),
    THIS_MONTH("This Month"),
    THIS_YEAR("This Year"),
    CUSTOM("Custom Range")
}

data class StorageStats(
    val originalsBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val trashBytes: Long = 0L,
    val totalItems: Int = 0,
    val trashedItems: Int = 0
)

data class SharePayload(
    val text: String? = null,
    val uris: List<String> = emptyList(),
    val mimeType: String? = null
)
