package com.example.ui

import android.content.SharedPreferences
import androidx.sqlite.db.SimpleSQLiteQuery
import com.example.ai.AiGatewayException
import com.example.data.AiCandidateState
import com.example.data.ItemWithCollections
import com.example.data.LibraryDao
import com.example.data.SearchDocumentEntity
import com.example.model.DateBasis
import com.example.model.DateFilter
import com.personallibrary.core.model.SearchQuery
import java.util.Calendar

internal data class AiPrivacySnapshot(
    val paused: Boolean,
    val wifiOnly: Boolean,
    val excludedItems: Set<String>,
    val excludedCollections: Set<String>
)

internal data class InteractiveAiSnapshot(
    val items: List<ItemWithCollections>,
    val documents: List<SearchDocumentEntity>,
    val privacy: AiPrivacySnapshot
) {
    // Collection names are unnecessary for the existing plan API. Omit all names, including
    // non-excluded ones: a privacy change during auth renewal cannot expose stale metadata.
    val knownCollectionNames: List<String> get() = emptyList()
}

/** Reuses existing FTS and versioned chunks; this is a bounded retrieval operation, not an index. */
internal class InteractiveAiRetrieval(private val dao: LibraryDao, private val prefs: SharedPreferences) {
    companion object {
        const val MAX_ITEMS = 160
        const val MAX_DOCUMENTS_PER_ITEM = 4
        const val MAX_DOCUMENTS = 480
    }

    private fun privacy() = AiPrivacySnapshot(
        prefs.getBoolean("ai_paused", false), prefs.getBoolean("ai_wifi_only", true),
        prefs.getStringSet("excluded_items", emptySet()).orEmpty().toSet(),
        prefs.getStringSet("excluded_collections", emptySet()).orEmpty().toSet()
    )

    suspend fun load(context: LocalSearchContext): InteractiveAiSnapshot {
        val capturedPrivacy = privacy()
        if (capturedPrivacy.paused) blocked()
        val (eligibility, arguments) = eligibility(context, capturedPrivacy)
        // Questions contain connecting words and possessives that need not occur in a
        // saved note. Reuse FTS prefixes as alternatives, then prefer more matched terms.
        val terms = SearchQuery.tokens(context.query.replace(Regex("['’][sS]\\b"), ""))
            .filter { it.length > 1 }.take(12).map { it.take(100) }.distinct()
        val fts = terms.mapNotNull { SearchQuery.fts(it) }.joinToString(" OR ").ifBlank { null }
        val candidateText = "lower(i.title || ' ' || i.content || ' ' || COALESCE(i.extractedText, '') || ' ' || " +
            "COALESCE(i.aiDescription, '') || ' ' || COALESCE(i.aiConcepts, '') || ' ' || COALESCE(i.originalFileName, ''))"
        val candidateRelevance = if (terms.isEmpty()) "0" else terms.joinToString(" + ") {
            "CASE WHEN instr($candidateText, lower(?)) > 0 THEN 1 ELSE 0 END"
        }
        val prioritized = if (fts == null) emptyList() else dao.getAiCandidates(SimpleSQLiteQuery(
            "SELECT i.* FROM items i JOIN items_fts ON i.rowid = items_fts.docid " +
                "WHERE $eligibility AND items_fts MATCH ? ORDER BY ($candidateRelevance) DESC, i.createdAt DESC, i.id LIMIT ?",
            (arguments + listOf(fts) + terms + listOf(MAX_ITEMS)).toTypedArray()
        ))
        val remaining = MAX_ITEMS - prioritized.size
        val fallback = if (remaining == 0) emptyList() else dao.getAiCandidates(SimpleSQLiteQuery(
            "SELECT i.* FROM items i WHERE $eligibility " +
                "AND instr(?, ',x' || hex(CAST(i.id AS BLOB)) || ',') = 0 " +
                "ORDER BY i.createdAt DESC, i.id LIMIT ?",
            (arguments + listOf(encodedIds(prioritized.map { it.item.id }), remaining)).toTypedArray()
        ))
        val items = prioritized + fallback
        val documents = mutableListOf<SearchDocumentEntity>()
        val snapshot = InteractiveAiSnapshot(items, documents, capturedPrivacy)
        requireCurrent(snapshot) { true }
        // Rank the existing chunks before applying the per-item bound. Otherwise early
        // embedded pages can crowd out a later relevant page that has only local text.
        val relevance = if (terms.isEmpty()) "0" else terms.joinToString(" + ") {
            "CASE WHEN instr(lower(sd.textContent), lower(?)) > 0 THEN 1 ELSE 0 END"
        }
        for (row in items) {
            val remainingDocuments = MAX_DOCUMENTS - documents.size
            if (remainingDocuments == 0) break
            // Filter again in SQL before loading vectors/text. Canonical IDs contain the
            // current item version; legacy or obsolete chunks cannot become semantic evidence.
            documents += dao.getAiCandidateDocuments(SimpleSQLiteQuery(
                "SELECT sd.* FROM search_documents sd JOIN items i ON i.id = sd.itemId " +
                    "WHERE $eligibility AND i.id = ? AND i.localIndexVersion = ? " +
                    "AND sd.id IN ('doc_' || i.id || '_' || i.localIndexVersion || '_' || sd.chunkIndex, " +
                    "'local_' || i.id || '_' || i.localIndexVersion || '_' || sd.chunkIndex) " +
                    "ORDER BY ($relevance) DESC, CASE WHEN sd.embedding IS NOT NULL AND sd.embeddingDimension = 768 " +
                    "AND sd.modelVersion = 'gemini-embedding-2' THEN 0 ELSE 1 END, sd.chunkIndex, sd.id LIMIT ?",
                (arguments + listOf(row.item.id, row.item.localIndexVersion) + terms +
                    listOf(minOf(MAX_DOCUMENTS_PER_ITEM, remainingDocuments))).toTypedArray()
            ))
        }
        requireCurrent(snapshot) { true }
        return snapshot.copy(documents = documents.toList())
    }

    /** Must run for EACH concrete dispatch, including auth renewal/retry, and publication. */
    suspend fun requireCurrent(snapshot: InteractiveAiSnapshot, isCurrent: () -> Boolean) {
        if (!isCurrent() || snapshot.privacy.paused || privacy() != snapshot.privacy) blocked()
        if (snapshot.items.isNotEmpty()) {
            // One SQLite snapshot checks versions, Trash, filter fields and memberships without
            // rereading any item text. Room-flow delivery is deliberately not part of this guard.
            val current = dao.getAiCandidateStates(snapshot.items.map { it.item.id }).associateBy { it.id }
            for (row in snapshot.items) {
                val state = current[row.item.id] ?: blocked()
                if (!matches(row, state)) blocked()
            }
        }
        if (!isCurrent() || privacy() != snapshot.privacy) blocked()
    }

    private fun matches(row: ItemWithCollections, state: AiCandidateState): Boolean = with(row.item) {
        !state.isTrash && state.localIndexVersion == localIndexVersion && state.kind == kind &&
            state.isFavorite == isFavorite && state.createdAt == createdAt && state.sourceDate == sourceDate &&
            state.reminderTime == reminderTime && state.isReminderDone == isReminderDone &&
            state.collectionIds?.split(',')?.toSet().orEmpty() == row.collections.map { hex(it.id) }.toSet()
    }

    private fun blocked(): Nothing = throw AiGatewayException.RequestBlockedException()

    private fun eligibility(context: LocalSearchContext, privacy: AiPrivacySnapshot): Pair<String, List<Any>> {
        val clauses = mutableListOf(
            "i.isTrash = 0",
            "instr(?, ',x' || hex(CAST(i.id AS BLOB)) || ',') = 0",
            "NOT EXISTS (SELECT 1 FROM item_collection_cross_ref excluded WHERE excluded.itemId = i.id " +
                "AND instr(?, ',x' || hex(CAST(excluded.collectionId AS BLOB)) || ',') > 0)"
        )
        val arguments = mutableListOf<Any>(encodedIds(privacy.excludedItems), encodedIds(privacy.excludedCollections))
        context.kind?.let { clauses += "i.kind = ?"; arguments += it.name }
        when (val collection = context.collectionId) {
            null -> Unit
            "computed_favorites" -> clauses += "i.isFavorite = 1"
            "computed_due" -> clauses += "i.reminderTime IS NOT NULL AND i.isReminderDone = 0"
            "computed_unfiled" -> clauses += "NOT EXISTS (SELECT 1 FROM item_collection_cross_ref r WHERE r.itemId = i.id)"
            else -> {
                clauses += "EXISTS (SELECT 1 FROM item_collection_cross_ref r WHERE r.itemId = i.id AND r.collectionId = ?)"
                arguments += collection
            }
        }
        val dateColumn = if (context.basis == DateBasis.SOURCE_DATE) "i.sourceDate" else "i.createdAt"
        when (context.dateFilter) {
            DateFilter.ALL_TIME -> Unit
            DateFilter.CUSTOM -> {
                if (context.customStart == null || context.customEnd == null) clauses += "0"
                else {
                    clauses += "$dateColumn >= ? AND $dateColumn <= ?"
                    arguments += context.customStart
                    arguments += context.customEnd
                }
            }
            DateFilter.THIS_MONTH, DateFilter.THIS_YEAR -> {
                val calendar = Calendar.getInstance()
                val year = calendar.get(Calendar.YEAR)
                val month = if (context.dateFilter == DateFilter.THIS_YEAR) Calendar.JANUARY else calendar.get(Calendar.MONTH)
                calendar.clear()
                calendar.set(year, month, 1)
                val start = calendar.timeInMillis
                calendar.add(if (context.dateFilter == DateFilter.THIS_YEAR) Calendar.YEAR else Calendar.MONTH, 1)
                clauses += "$dateColumn >= ? AND $dateColumn < ?"
                arguments += start
                arguments += calendar.timeInMillis
            }
        }
        return clauses.joinToString(" AND ") to arguments
    }
}

// Hex + a non-hex prefix makes each ID an exact, delimiter-safe member even for arbitrary IDs.
// A single bind per set avoids SQLite's bind-count ceiling for large privacy sets.
private fun hex(value: String): String = value.toByteArray(Charsets.UTF_8).joinToString("") { "%02X".format(it.toInt() and 255) }
private fun encodedIds(values: Collection<String>): String = values.joinToString(",", ",", ",") { "x${hex(it)}" }
