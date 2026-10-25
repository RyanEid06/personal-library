package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personallibrary.core.migration.DateOrder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryRepositoryTest {

    private lateinit var context: Context
    private lateinit var repository: LibraryRepository

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        repository = LibraryRepository(context)
    }

    @Test
    fun testCaptureStreamDeduplicatesIdenticalBlobs() = runBlocking {
        val fileBytes = "Unique file payload for deduplication test 123456789".toByteArray()

        val item1 = repository.captureStream(
            input = ByteArrayInputStream(fileBytes),
            originalName = "lecture_notes.pdf",
            declaredMime = "application/pdf"
        )

        val item2 = repository.captureStream(
            input = ByteArrayInputStream(fileBytes),
            originalName = "backup_lecture_notes.pdf",
            declaredMime = "application/pdf"
        )

        assertNotNull(item1.contentHash)
        assertNotNull(item2.contentHash)
        assertEquals("Identical byte streams must produce identical content hashes", item1.contentHash, item2.contentHash)
        assertNotEquals("Items must have distinct primary keys", item1.id, item2.id)

        // Asset record must have refCount = 2
        val asset = repository.dao.getAssetByHash(item1.contentHash!!)
        assertNotNull(asset)
        assertEquals(2, asset!!.refCount)

        // Stored physical file exists
        val blob = repository.getBlobFile(item1.contentHash!!)
        assertNotNull(blob)
        assertTrue(blob!!.exists())
        assertEquals(fileBytes.size.toLong(), blob.length())

        // Delete first item -> refCount drops to 1, physical file remains
        repository.deletePermanently(item1.id)
        val assetAfter1 = repository.dao.getAssetByHash(item1.contentHash!!)
        assertNotNull(assetAfter1)
        assertEquals(1, assetAfter1!!.refCount)
        assertTrue(blob.exists())

        // Delete second item -> refCount reaches 0, asset row and physical file removed
        repository.deletePermanently(item2.id)
        val assetAfter2 = repository.dao.getAssetByHash(item1.contentHash!!)
        assertNull(assetAfter2)
        assertFalse(blob.exists())
    }

    @Test
    fun testTrashAndRestoreFlow() = runBlocking {
        val item = repository.captureStream(
            input = ByteArrayInputStream("Temporary Note Content".toByteArray()),
            originalName = "temp.txt",
            declaredMime = "text/plain"
        )

        val activeBefore = repository.observeActiveItemsWithCollections().first()
        assertTrue(activeBefore.any { it.item.id == item.id })

        repository.moveToTrash(item.id)
        val activeAfter = repository.observeActiveItemsWithCollections().first()
        assertFalse(activeAfter.any { it.item.id == item.id })

        val trashAfter = repository.observeTrashItems().first()
        assertTrue(trashAfter.any { it.id == item.id })

        repository.restoreFromTrash(item.id)
        val activeRestored = repository.observeActiveItemsWithCollections().first()
        assertTrue(activeRestored.any { it.item.id == item.id })
    }

    @Test
    fun testWhatsAppMigrationWithStrictDateOrder() = runBlocking {
        val chatContent = """
            [13/10/2026, 14:32:10] Ryan: Review chemistry syllabus https://chem.university.edu/syllabus
            [13/10/2026, 14:35:22] Ryan: Grade: 94/100
        """.trimIndent()

        val (imported, ambiguous) = repository.importWhatsAppMigration(chatContent, DateOrder.DAY_FIRST)
        assertEquals(2, imported)
        assertEquals(0, ambiguous)

        val collections = repository.observeCollections().first()
        assertTrue("Import must not create a default folder", collections.isEmpty())

        val items = repository.observeActiveItemsWithCollections().first()
        val syllabusItem = items.find { it.item.content.contains("https://chem.university.edu/syllabus") }
        assertNotNull(syllabusItem)
        assertTrue(syllabusItem!!.collections.isEmpty())
    }
}
