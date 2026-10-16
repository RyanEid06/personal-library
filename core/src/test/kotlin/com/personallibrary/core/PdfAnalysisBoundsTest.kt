package com.personallibrary.core

import org.junit.Assert.*
import org.junit.Test

class PdfAnalysisBoundsTest {
    @Test fun batchesNeverExceedTenPagesOrThePromptBudgetAndKeepReferences() {
        val pages = (1..100).map { ExtractedPage(it, "Page $it " + "x".repeat(5000)) }
        val batches = PdfAnalysisBounds.batches(pages)
        assertEquals(10, batches.size)
        assertTrue(batches.all { it.pageNumbers.size <= 10 && it.text.length <= 32000 && it.partial })
        assertEquals((1..100).toList(), batches.flatMap { it.pageNumbers })
        assertTrue(batches.last().text.contains("[Page 100]"))
    }

    @Test fun shortPagesRemainWholeAndUnnumberedEmptyInputCannotInventPages() {
        val batch = PdfAnalysisBounds.batches(listOf(ExtractedPage(9, "chemistry 🎓"))).single()
        assertEquals(listOf(9), batch.pageNumbers)
        assertTrue(batch.text.contains("chemistry 🎓"))
        assertFalse(batch.partial)
        assertTrue(PdfAnalysisBounds.batches(emptyList()).isEmpty())
    }
}
