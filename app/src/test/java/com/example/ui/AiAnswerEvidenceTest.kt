package com.example.ui

import com.example.ai.AnswerRequest
import com.example.data.*
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class AiAnswerEvidenceTest {
    private val privacy = AiPrivacySnapshot(false, false, emptySet(), emptySet())
    private fun row(id: String, title: String, body: String = "", kind: String = "TEXT") =
        ItemWithCollections(ItemEntity(id, title, body, kind), emptyList())
    private fun snapshot(rows: List<ItemWithCollections>, docs: List<SearchDocumentEntity> = emptyList()) =
        InteractiveAiSnapshot(rows, docs, privacy)

    @Test fun titleMatchIncludesActualNoteBodyAndOpaqueOpeningReference() {
        val note = row("stable-private-item-id", "Sarah birthday", "August 19")
        val selected = selectAnswerEvidence("When is Sarah's birthday?", snapshot(listOf(note)), emptyList())
        assertEquals(1, selected.evidence.size)
        val evidence = selected.evidence.single()
        assertTrue(evidence.text.contains("August 19"))
        assertEquals(note, selected.sourceItems[evidence.sourceId])
        assertFalse(evidence.sourceId.contains(note.item.id))
        assertFalse(evidence.itemRef.contains(note.item.id))
        val again = selectAnswerEvidence("When is Sarah's birthday?", snapshot(listOf(note)), emptyList())
        assertNotEquals(evidence.sourceId, again.evidence.single().sourceId)
        assertNotEquals(evidence.itemRef, again.evidence.single().itemRef)
    }

    @Test fun irrelevantRecentFallbackDoesNotBecomeAnswerContext() {
        val selected = selectAnswerEvidence("Sarah birthday", snapshot(listOf(row("recent", "Shopping", "Oranges and bread"))), emptyList())
        assertTrue(selected.evidence.isEmpty())
        assertTrue(selected.sourceItems.isEmpty())
    }

    @Test fun terminalQuestionPunctuationDoesNotHidePlainBirthdayEvidence() {
        val note = row("birthday-note", "birthday", "August 19")
        val selected = selectAnswerEvidence("When is birthday?", snapshot(listOf(note)), emptyList())
        assertEquals(1, selected.evidence.size)
        assertTrue(selected.evidence.single().text.contains("August 19"))
        assertEquals(note, selected.sourceItems[selected.evidence.single().sourceId])
    }

    @Test fun lateMatchingPageOutranksEarlyChunksAndPreservesPage() {
        val pdf = row("pdf", "Calendar", kind = "DOCUMENT")
        val docs = listOf(
            SearchDocumentEntity("early", "pdf", 0, 1, "Earlier unrelated page"),
            SearchDocumentEntity("late", "pdf", 18, 19, "Sarah birthday is August 19")
        )
        val selected = selectAnswerEvidence("When is Sarah's birthday?", snapshot(listOf(pdf), docs), emptyList())
        assertEquals(19, selected.evidence.first().pageNumber)
        assertTrue(selected.evidence.first().text.contains("August 19"))
        assertTrue(selected.evidence.none { it.text.contains("Earlier unrelated") })
    }

    @Test fun hugeBodyUsesExistingSnippetAroundLateMatch() {
        val note = row("long", "Archive", "unrelated ".repeat(5000) + "Sarah birthday is August 19")
        val selected = selectAnswerEvidence("Sarah birthday", snapshot(listOf(note)), emptyList())
        assertTrue(selected.evidence.single().text.contains("August 19"))
        assertTrue(selected.evidence.single().text.length <= 1200)
    }

    @Test fun semanticPageCanSupplyEvidenceWithoutLexicalMatch() {
        val pdf = row("pdf", "Dates", kind = "DOCUMENT")
        val docs = listOf(SearchDocumentEntity("doc", "pdf", 5, 6, "The celebration falls on August 19"))
        val semantic = SemanticSearchResult(pdf, "Semantic match", 6f, "The celebration", "Gemini semantic match", 6)
        val selected = selectAnswerEvidence("Sarah birthday", snapshot(listOf(pdf), docs), listOf(semantic))
        assertEquals(6, selected.evidence.single().pageNumber)
        assertTrue(selected.evidence.single().text.contains("August 19"))
    }

    @Test fun evidenceHonorsChunkDiversityAndActualEscapedJsonBudgets() {
        val rows = (1..15).map { row("stable-$it", "Sarah \"birthday\" " + "😀".repeat(120), "Sarah birthday " + "\u0001\"😀".repeat(290)) }
        val docs = rows.flatMap { row -> (0..6).map { SearchDocumentEntity("${row.item.id}-$it", row.item.id, it, it + 1,
            "Sarah birthday $it " + "\u0001\"😀".repeat(290)) } }
        val selected = selectAnswerEvidence("Sarah birthday", snapshot(rows, docs), emptyList())
        val evidence = selected.evidence
        assertTrue(evidence.size in 2..12)
        assertTrue(evidence.groupBy { it.itemRef }.values.all { it.size <= 3 })
        assertTrue(evidence.map { it.itemRef }.distinct().size >= 2)
        assertTrue(evidence.all { it.title.length <= 200 && it.text.length <= 1200 && it.text.isNotBlank() })
        assertTrue(evidence.sumOf { it.text.length } <= 12000)
        assertTrue(evidence.sumOf { it.text.toByteArray(Charsets.UTF_8).size } <= 32768)
        assertTrue(Json.encodeToString(AnswerRequest.serializer(), AnswerRequest("Sarah birthday", evidence)).toByteArray().size <= 65536)
        assertTrue(evidence.all { validSurrogates(it.title) && validSurrogates(it.text) })
        assertEquals(evidence.map { it.sourceId }.toSet(), selected.sourceItems.keys)
    }

    @Test fun defensivePrivacyFilteringRejectsExcludedAndTrashedRowsEvenWithSemanticResults() {
        val excluded = row("excluded", "Sarah birthday", "August 19")
        val trash = row("trash", "Sarah birthday", "August 19").let { it.copy(item = it.item.copy(isTrash = true)) }
        val privateCollection = row("collection", "Sarah birthday", "August 19").copy(collections = listOf(CollectionEntity("private", "Private")))
        val snap = snapshot(listOf(excluded, trash, privateCollection)).copy(privacy = privacy.copy(excludedItems = setOf("excluded"), excludedCollections = setOf("private")))
        assertTrue(selectAnswerEvidence("Sarah birthday", snap, snap.items.map { SemanticSearchResult(it, "match", 9f) }).evidence.isEmpty())
        assertTrue(selectAnswerEvidence("Sarah birthday", snap.copy(privacy = privacy.copy(paused = true)), emptyList()).evidence.isEmpty())
    }

    private fun validSurrogates(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            if (char.isHighSurrogate()) {
                if (index == value.length || !value[index++].isLowSurrogate()) return false
            } else if (char.isLowSurrogate()) return false
        }
        return true
    }
}
