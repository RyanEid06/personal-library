package com.example.ui

import com.example.ai.AnswerEvidence
import com.example.ai.AnswerProtocol
import com.example.ai.AnswerRequest
import com.example.ai.AiGatewayException
import com.example.data.ItemEntity
import com.example.data.ItemWithCollections
import java.util.UUID

internal data class SelectedAnswerEvidence(
    val evidence: List<AnswerEvidence>,
    val sourceItems: Map<String, ItemWithCollections>
)

/** Selects cached evidence only. It never reads an original file or builds another index. */
internal fun selectAnswerEvidence(
    question: String,
    snapshot: InteractiveAiSnapshot,
    semanticResults: List<SemanticSearchResult>
): SelectedAnswerEvidence {
    if (snapshot.privacy.paused || question.isBlank() || question.length > 2000) {
        return SelectedAnswerEvidence(emptyList(), emptyMap())
    }
    // FTS treats terminal question punctuation as a token boundary. Match the
    // cached evidence with that same word, without changing ordinary SearchQuery.
    val terms = interactiveAiQuestionTerms(question).map { it.replace(terminalPunctuation, "") }
        .filter { it.isNotBlank() && it.lowercase() !in questionWords }.distinct()
    val snippetQuery = terms.joinToString(" ")
    val documents = snapshot.documents.groupBy { it.itemId }
    val semantic = semanticResults.filter { it.score.isFinite() && it.score > 0f }.associateBy { it.item.item.id }
    data class Chunk(val text: String, val page: Int?, val relevance: Int)
    data class Candidate(val row: ItemWithCollections, val chunks: List<Chunk>, val score: Float)
    val candidates = snapshot.items.mapNotNull { row ->
        val item = row.item
        if (item.isTrash || item.id in snapshot.privacy.excludedItems ||
            row.collections.any { it.id in snapshot.privacy.excludedCollections }) return@mapNotNull null
        val semanticMatch = semantic[item.id]
        val fields = listOfNotNull(item.content.takeIf { it.isNotBlank() }, item.extractedText,
            item.aiDescription, item.aiConcepts, item.originalFileName).filter { it.isNotBlank() }
        val titleScore = answerRelevance(item.title, terms)
        val bodyScore = fields.maxOfOrNull { answerRelevance(it, terms) } ?: 0
        val itemDocs = documents[item.id].orEmpty()
        val docScore = itemDocs.maxOfOrNull { answerRelevance(it.textContent, terms) } ?: 0
        if (maxOf(titleScore, bodyScore, docScore) == 0 && semanticMatch == null) return@mapNotNull null

        val chunks = mutableListOf<Chunk>()
        // A matching title must not suppress the owner's answer-bearing body (e.g. a date).
        if (item.kind == "TEXT" || item.kind == "LINK" || itemDocs.isEmpty()) {
            val body = fields.maxByOrNull { answerRelevance(it, terms) }
            if (body != null) chunks += Chunk(answerExcerpt(body, snippetQuery), null, maxOf(titleScore, bodyScore))
        }
        for (doc in itemDocs) {
            val relevance = answerRelevance(doc.textContent, terms)
            val semanticPage = semanticMatch != null && semanticMatch.pageNumber == doc.pageNumber
            if (doc.textContent.isNotBlank() && (relevance > 0 || semanticPage || titleScore > 0 && docScore == 0)) {
                chunks += Chunk(answerExcerpt(doc.textContent, snippetQuery), doc.pageNumber,
                    relevance + if (semanticPage) 1 else 0)
            }
        }
        if (chunks.isEmpty()) {
            val text = fields.maxByOrNull { answerRelevance(it, terms) }
                ?: item.title.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            chunks += Chunk(answerExcerpt(text, snippetQuery), null, maxOf(titleScore, bodyScore))
        }
        val ranked = chunks.filter { it.text.isNotBlank() }.distinctBy { it.text to it.page }
            .sortedByDescending { it.relevance }.take(3)
        if (ranked.isEmpty()) return@mapNotNull null
        Candidate(row, ranked, maxOf(titleScore, bodyScore, docScore) * 10f +
            (semanticMatch?.score?.coerceIn(0f, 9f) ?: 0f))
    }.sortedByDescending { it.score }

    val evidence = mutableListOf<AnswerEvidence>()
    val sources = linkedMapOf<String, ItemWithCollections>()
    val itemReferences = candidates.associate { it.row.item.id to UUID.randomUUID().toString() }
    // First take one chunk from each relevant source; only then allow a second/third.
    for (chunkIndex in 0..2) {
        for (candidate in candidates) {
            if (evidence.size == 12) break
            val chunk = candidate.chunks.getOrNull(chunkIndex) ?: continue
            val title = unicodePrefix(candidate.row.item.title.ifBlank { "Saved item" }, 200)
            val selected = AnswerEvidence(UUID.randomUUID().toString(), itemReferences.getValue(candidate.row.item.id),
                title, chunk.text, chunk.page?.takeIf { it > 0 })
            // Include JSON escaping and Unicode byte size in the actual wire budget.
            try { AnswerProtocol.encode(AnswerRequest(question, evidence + selected)) }
            catch (_: AiGatewayException.ValidationException) { continue }
            evidence += selected
            sources[selected.sourceId] = candidate.row
        }
    }
    return SelectedAnswerEvidence(evidence.toList(), sources.toMap())
}

private val questionWords = setOf("when", "where", "what", "which", "who", "whose", "why", "how", "is", "are",
    "was", "were", "the", "an", "and", "or", "of", "to", "for", "do", "does", "did", "can", "could", "please", "me", "my")

// Unicode categories preserve supplementary letters/digits as complete code points.
private val terminalPunctuation = Regex("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$")

private fun answerRelevance(text: String, terms: List<String>): Int = terms.count { term ->
    var index = text.indexOf(term, ignoreCase = true)
    while (index >= 0 && index != 0 && Character.isLetterOrDigit(text.codePointBefore(index))) {
        index = text.indexOf(term, index + 1, ignoreCase = true)
    }
    index >= 0
}

private fun answerExcerpt(text: String, query: String): String {
    if (text.length <= 1200) return unicodePrefix(text, 1200)
    // Reuse the established matching/snippet engine for matches late in large cached text.
    val snippet = buildLocalMatchSnippet(ItemEntity("", "", text, "TEXT"), query)
    return unicodePrefix(snippet ?: text, 1200)
}

/** Keep UTF-16 limits without sending unpaired surrogate halves from a snippet boundary. */
private fun unicodePrefix(text: String, limit: Int): String = buildString {
    var index = 0
    while (index < text.length && length < limit) {
        val char = text[index++]
        when {
            char.isHighSurrogate() -> if (index < text.length && text[index].isLowSurrogate()) {
                if (length + 2 > limit) break
                append(char); append(text[index++])
            }
            !char.isLowSurrogate() -> append(char)
        }
    }
}
