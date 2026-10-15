package com.personallibrary.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

class TextExtractionTest {

    @Test
    fun unicodeAndUtf8BomRetainCompleteText() {
        val text = "Grades 🎓\nChemistry: 97/100\nدرجات"
        val source = ByteArrayInputStream(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + text.toByteArray())
        assertEquals(ExtractedText(text, "complete"), TextExtraction.read(source))
    }

    @Test
    fun aBudgetCutInsideAnEmojiReturnsOnlyValidTextAndPartialCoverage() {
        val source = ByteArrayInputStream("Grades 🎓 next".toByteArray())
        val result = TextExtraction.read(source, 9)
        assertEquals("Grades ", result.text)
        assertEquals("partial", result.coverage)
        assertFalse(result.text.contains('\uFFFD'))
    }

    @Test
    fun utf16BomTextIsDecodedWithinTheSameByteBudget() {
        val source = byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "Grade".toByteArray(Charsets.UTF_16LE)
        assertEquals(ExtractedText("Grade", "complete"), TextExtraction.read(ByteArrayInputStream(source)))
        assertEquals(ExtractedText("Gra", "partial"), TextExtraction.read(ByteArrayInputStream(source), 9))
    }

    @Test
    fun malformedOrBinaryContentIsHonestAboutUnsupportedCoverage() {
        assertEquals(ExtractedText("", "unsupported"), TextExtraction.read(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28))))
        assertEquals(ExtractedText("", "unsupported"), TextExtraction.read(ByteArrayInputStream(byteArrayOf(65, 0, 66))))
    }

    @Test
    fun aLargeSourceStopsAfterTheBudgetAndItsOneByteLookahead() {
        var reads = 0
        var closed = false
        val source = object : InputStream() {
            override fun read(): Int { reads++; return 65 }
            override fun close() { closed = true }
        }
        assertEquals(ExtractedText("A".repeat(100), "partial"), TextExtraction.read(source, 100))
        assertEquals(101, reads)
        assertTrue(closed)
    }

    @Test
    fun aFailingSourceIsClosedAndAnEmptyFileIsComplete() {
        var closed = false
        try {
            TextExtraction.read(object : InputStream() {
                override fun read(): Int = throw IOException("Synthetic read failure")
                override fun close() { closed = true }
            })
        } catch (_: IOException) {
            // Expected
        }
        assertTrue(closed)
        assertEquals(ExtractedText("", "complete"), TextExtraction.read(ByteArrayInputStream(byteArrayOf())))
    }
}
