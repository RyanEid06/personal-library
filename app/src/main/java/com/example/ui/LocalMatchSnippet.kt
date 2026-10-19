package com.example.ui

import com.example.data.ItemEntity
import com.personallibrary.core.model.SearchQuery

/** The existing snippet engine, shared by keyword and semantic result cards. */
internal fun buildLocalMatchSnippet(item: ItemEntity, query: String): String? {
    if (query.isBlank()) return null
    val tokens = SearchQuery.tokens(query)
    val candidates = listOfNotNull(
        item.title,
        item.content.takeIf { it.isNotBlank() },
        item.originalFileName,
        item.extractedText?.takeIf { it.isNotBlank() },
        item.aiDescription?.takeIf { it.isNotBlank() },
        item.aiConcepts?.takeIf { it.isNotBlank() }
    )
    fun findSnippet(requireTokenStart: Boolean): String? {
        for (text in candidates) {
            for (token in tokens) {
                // Keep indices in the original text: Unicode lowercasing can expand its length.
                var index = text.indexOf(token, ignoreCase = true)
                while (index != -1) {
                    if (!requireTokenStart || index == 0 || !Character.isLetterOrDigit(text.codePointBefore(index))) {
                        val start = maxOf(0, index - 40)
                        val end = minOf(text.length, index + token.length + 60)
                        val prefix = if (start > 0) "…" else ""
                        val suffix = if (end < text.length) "…" else ""
                        return prefix + text.substring(start, end).replace("\n", " ").trim() + suffix
                    }
                    index = text.indexOf(token, startIndex = index + 1, ignoreCase = true)
                }
            }
        }
        return null
    }
    // Prefer evidence consistent with FTS prefixes anywhere in the item. Substring evidence
    // remains useful for the existing LIKE fallback when no field has a word-start match.
    return findSnippet(requireTokenStart = true) ?: findSnippet(requireTokenStart = false)
}
