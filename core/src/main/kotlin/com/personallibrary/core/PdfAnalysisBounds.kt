package com.personallibrary.core

data class PdfAnalysisBatch(val pageNumbers: List<Int>, val text: String, val partial: Boolean)

object PdfAnalysisBounds {
    const val MAX_PROMPT_CHARS = 32000
    fun batches(pages: List<ExtractedPage>): List<PdfAnalysisBatch> = pages.take(100).chunked(10).map { batch ->
        PdfAnalysisBatch(
            batch.map { it.number },
            batch.joinToString("\n\n") { "[Page ${it.number}]\n${it.text.take(3000)}" }.take(MAX_PROMPT_CHARS),
            batch.any { it.text.length > 3000 }
        )
    }
}
