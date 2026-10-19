package com.example.ui

import com.example.data.ItemEntity
import org.junit.Assert.*
import org.junit.Test

class LocalMatchSnippetTest {
    private val item = ItemEntity("synthetic", "Receipt", "", "FILE", originalFileName = "birthday.pdf", extractedText = "A synthetic birthday invitation")

    @Test fun oneCharacterPrefixHasTheSameEvidenceAsLongerPrefixes() {
        for (query in listOf("b", "bi", "bir", "birth", "birthday")) {
            assertEquals("birthday.pdf", buildLocalMatchSnippet(item, query))
        }
    }

    @Test fun extractedTextEvidenceIsConciseAndWhitespaceIsFlattened() {
        val text = "z".repeat(100) + "\nbirthday\ninvitation " + "z".repeat(100)
        val snippet = buildLocalMatchSnippet(item.copy(originalFileName = null, extractedText = text), "birth")!!
        assertTrue(snippet.contains("birthday invitation"))
        assertFalse(snippet.contains("\n"))
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.endsWith("…"))
        assertTrue(snippet.length <= 120)
    }

    @Test fun blankOrUnmatchedQueriesHaveNoEvidence() {
        assertNull(buildLocalMatchSnippet(item, " "))
        assertNull(buildLocalMatchSnippet(item, "unmatched"))
    }

    @Test fun prefixEvidenceInLaterFieldWinsOverEarlierMidWordSubstring() {
        val document = item.copy(title = "Heart notes", originalFileName = null, extractedText = "Earmark details")
        assertEquals("Earmark details", buildLocalMatchSnippet(document, "ear"))
    }

    @Test fun singleCharacterPrefixEvidenceWinsOverEarlierMidWordSubstring() {
        assertEquals("birthday.pdf", buildLocalMatchSnippet(item.copy(title = "Absent"), "b"))
    }

    @Test fun laterPrefixWithinTheSameFieldWinsOverItsEarlierMidWordOccurrence() {
        val document = item.copy(title = "Heart notes. " + "z".repeat(100) + " Earmark details", originalFileName = null, extractedText = null)
        val snippet = buildLocalMatchSnippet(document, "ear")!!
        assertTrue(snippet.contains("Earmark details"))
        assertFalse(snippet.contains("Heart notes"))
    }

    @Test fun substringFallbackRemainsAvailableWhenNoFieldContainsPrefixEvidence() {
        val document = item.copy(title = "Heart notes", originalFileName = null, extractedText = "Unrelated details")
        assertEquals("Heart notes", buildLocalMatchSnippet(document, "ear"))
    }

    @Test fun expandingUnicodeCaseMappingKeepsSnippetOffsetsInOriginalText() {
        val document = item.copy(title = "İ".repeat(100) + " birthday", originalFileName = null, extractedText = null)
        val snippet = buildLocalMatchSnippet(document, "birth")!!
        assertTrue(snippet.startsWith("…"))
        assertTrue(snippet.contains("birthday"))
        assertTrue(snippet.length <= 120)
    }

    @Test fun removedFtsOperatorsAreNeverReturnedAsMatchEvidence() {
        val document = item.copy(title = "ORGANIC", originalFileName = null, extractedText = "birthday cat")
        assertEquals("birthday cat", buildLocalMatchSnippet(document, "bir OR cat"))
        assertNull(buildLocalMatchSnippet(document, "OR AND NOT"))
    }

    @Test fun supplementaryLetterIsNotAWordBoundary() {
        val document = item.copy(title = "𐐀ear notes", originalFileName = null, extractedText = "ear details")
        assertEquals("ear details", buildLocalMatchSnippet(document, "ear"))
    }
}
