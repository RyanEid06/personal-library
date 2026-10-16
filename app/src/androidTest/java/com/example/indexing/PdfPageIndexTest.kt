package com.example.indexing

import android.content.Context
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.LibraryRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PdfPageIndexTest {
    @Test fun excludedPdfRetainsPageReferencesAndLocalFtsWithoutRemoteAi() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = File.createTempFile("pages-", ".pdf", context.cacheDir)
        PdfDocument().use { pdf ->
            for (number in 1..2) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 800, number).create())
                page.canvas.drawText(if (number == 1) "Chemistry on page one" else "Biology on page two", 40f, 80f, Paint().apply { textSize = 28f })
                pdf.finishPage(page)
            }
            source.outputStream().use(pdf::writeTo)
        }
        val repository = LibraryRepository(context)
        val item = repository.captureStream(source.inputStream(), "synthetic-pages.pdf", "application/pdf")
        try {
            context.getSharedPreferences("personal_library_settings", 0).edit().putStringSet("excluded_items", setOf(item.id)).commit()
            val extraction = LocalIndexer(context, repository).indexItem(item)
            assertEquals(listOf(1, 2), extraction.pages.map { it.number })
            val documents = repository.dao.getSearchDocumentsForItem(item.id)
            assertEquals(setOf(1, 2), documents.mapNotNull { it.pageNumber }.toSet())
            assertTrue(documents.filter { it.pageNumber == 1 }.any { it.textContent.contains("Chemistry", true) })
            assertTrue(repository.dao.searchFts("chemistry").any { it.id == item.id })
            assertEquals("EXCLUDED", repository.dao.getItemById(item.id)!!.aiStatus)
        } finally { repository.deletePermanently(item.id); source.delete() }
    }
}
