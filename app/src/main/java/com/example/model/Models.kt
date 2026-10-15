package com.example.model

typealias ItemKind = com.personallibrary.core.model.ItemKind

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
