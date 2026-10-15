package com.personallibrary.core

import com.personallibrary.core.model.*
import com.personallibrary.core.storage.ContentClassifier
import com.personallibrary.core.storage.ManagedAssetStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId

class ContentClassifierTest {

    @Test
    fun testWebPClassification() {
        // Spec requirement: WebP remains PHOTO unless explicitly marked
        val kindPhoto = ContentClassifier.classify("image/webp", "photo.webp", explicitSticker = false)
        assertEquals(ItemKind.PHOTO, kindPhoto)

        val kindStickerExplicit = ContentClassifier.classify("image/webp", "photo.webp", explicitSticker = true)
        assertEquals(ItemKind.STICKER, kindStickerExplicit)

        val kindStickerName = ContentClassifier.classify("image/webp", "sticker_cat.webp", explicitSticker = false)
        assertEquals(ItemKind.STICKER, kindStickerName)
    }

    @Test
    fun testDocumentAndMimeNormalization() {
        val kindDoc = ContentClassifier.classify("application/octet-stream", "report.json")
        assertEquals(ItemKind.DOCUMENT, kindDoc)

        val kindMimeCase = ContentClassifier.classify("TEXT/PLAIN; charset=utf-8", "notes.txt")
        assertEquals(ItemKind.DOCUMENT, kindMimeCase)

        val kindPdf = ContentClassifier.classify("application/pdf", "exam.pdf")
        assertEquals(ItemKind.DOCUMENT, kindPdf)
    }

    @Test
    fun testMediaAndUnknownBinary() {
        assertEquals(ItemKind.VIDEO, ContentClassifier.classify("video/mp4", "movie.mp4"))
        assertEquals(ItemKind.AUDIO, ContentClassifier.classify("audio/opus", "voice_note.opus"))
        assertEquals(ItemKind.FILE, ContentClassifier.classify("application/octet-stream", "binary_data.bin"))
    }
}

class ManagedAssetStoreTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    @Test
    fun testDeduplicationIdenticalBytesReuseSingleBlob() {
        val store = ManagedAssetStore(tempFolder.root.toPath())
        val data = "Hello, Personal Library deduplication test!".toByteArray()

        val asset1 = store.import(ByteArrayInputStream(data), "file1.txt", "text/plain")
        val asset2 = store.import(ByteArrayInputStream(data), "file2.txt", "text/plain")

        assertEquals(asset1.hash, asset2.hash)
        assertEquals(asset1.relativePath, asset2.relativePath)

        // Only one physical file exists on disk
        val blob = store.getBlobFile(asset1.hash)
        assertNotNull(blob)
        assertTrue(blob!!.exists())
        assertEquals(data.size.toLong(), blob.length())
    }

    @Test
    fun testPathTraversalSanitization() {
        val store = ManagedAssetStore(tempFolder.root.toPath())
        val data = "safe content".toByteArray()
        val asset = store.import(ByteArrayInputStream(data), "../../../etc/passwd", "text/plain")

        assertFalse(asset.originalName.contains(".."))
        assertFalse(asset.originalName.contains("/"))
    }

    @Test
    fun testFailingStreamCleansUpStaging() {
        val store = ManagedAssetStore(tempFolder.root.toPath())
        val failingStream = object : InputStream() {
            var readCount = 0
            override fun read(): Int {
                readCount++
                if (readCount > 10) throw IOException("Network connection severed during capture")
                return 42
            }
        }

        try {
            store.import(failingStream, "broken.bin", "application/octet-stream")
            fail("Expected IOException")
        } catch (e: IOException) {
            // Success: Exception thrown
        }

        val stagingDir = tempFolder.root.toPath().resolve("staging").toFile()
        assertEquals(0, stagingDir.listFiles()?.size ?: 0)
    }

    @Test
    fun testStorageLimitRejection() {
        val store = ManagedAssetStore(tempFolder.root.toPath())
        val data = ByteArray(1024 * 100) // 100 KB
        try {
            store.import(ByteArrayInputStream(data), "big.bin", "application/octet-stream", availableBytes = 50 * 1024)
            fail("Expected IOException due to storage limits")
        } catch (e: IOException) {
            // Expected
        }
    }
}

class SearchQueryTest {

    @Test fun operatorSubstringsDoNotDestroyWordsOrUnicode() {
        assertEquals("\"CANDOR\"* \"ORGANIC\"* \"café\"* \"كيمياء\"*", SearchQuery.fts("CANDOR ORGANIC café كيمياء"))
    }

    @Test
    fun testSearchQueryFtsEscaping() {
        val query = "chemistry: \"grades\" -midterm OR 94/100"
        val fts = SearchQuery.fts(query)
        assertNotNull(fts)
        assertTrue(fts!!.contains("\"chemistry\"*"))
        assertTrue(fts.contains("\"grades\"*"))
        assertTrue(fts.contains("\"94/100\"*"))
        assertFalse(fts.contains("OR"))
    }

    @Test
    fun testEmojiAndNonLatinSearch() {
        val fts = SearchQuery.fts("📚 university notes")
        assertNotNull(fts)
        assertTrue(fts!!.contains("\"📚\"*"))
        assertTrue(fts.contains("\"university\"*"))
    }

    @Test
    fun testEmptyOrPunctuationQueryReturnsNull() {
        assertNull(SearchQuery.fts(""))
        assertNull(SearchQuery.fts("   "))
        assertNull(SearchQuery.fts("*:\"\"--"))
    }
}

class DateRangeTest {

    @Test fun customCalendarDaysIncludeEndDayAcrossDst() {
        val range = DateRange.days(java.time.LocalDate.of(2026, 3, 8), java.time.LocalDate.of(2026, 3, 8), ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), range.startInclusive)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), range.endExclusive)
        assertTrue(range.contains(Instant.parse("2026-03-09T03:59:59.999Z")))
        assertFalse(range.contains(Instant.parse("2026-03-09T04:00:00Z")))
        assertThrows(IllegalArgumentException::class.java) {
            DateRange.days(java.time.LocalDate.of(2026, 3, 9), java.time.LocalDate.of(2026, 3, 8), ZoneId.of("UTC"))
        }
    }

    @Test
    fun testMonthBoundary() {
        val zone = ZoneId.of("UTC")
        val range = DateRange.month(2026, 10, zone)

        val oct1 = Instant.parse("2026-10-01T00:00:00Z")
        val oct31 = Instant.parse("2026-10-31T23:59:59Z")
        val nov1 = Instant.parse("2026-11-01T00:00:00Z")
        val sep30 = Instant.parse("2026-09-30T23:59:59Z")

        assertTrue(range.contains(oct1))
        assertTrue(range.contains(oct31))
        assertFalse(range.contains(nov1))
        assertFalse(range.contains(sep30))
    }
}

class SemanticVectorMathTest {

    @Test
    fun testCosineSimilarityIdenticalAndOrthogonal() {
        val v1 = FloatArray(SemanticVectorMath.DIMENSION) { 1.0f }
        val v2 = FloatArray(SemanticVectorMath.DIMENSION) { 1.0f }

        val simIdentical = SemanticVectorMath.cosineSimilarity(v1, v2)
        assertEquals(1.0f, simIdentical, 0.001f)

        val v3 = FloatArray(SemanticVectorMath.DIMENSION) { if (it < 384) 1.0f else 0.0f }
        val v4 = FloatArray(SemanticVectorMath.DIMENSION) { if (it >= 384) 1.0f else 0.0f }
        val simOrthogonal = SemanticVectorMath.cosineSimilarity(v3, v4)
        assertEquals(0.0f, simOrthogonal, 0.001f)
    }
}
