package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personallibrary.core.backup.*
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryIntegrityTest {
    private lateinit var db: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        repository = LibraryRepository(context, db)
    }
    @After fun close() = db.close()
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private suspend fun failMemberships() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_membership BEFORE INSERT ON item_collection_cross_ref BEGIN SELECT RAISE(ABORT, 'injected membership failure'); END")
    }
    private suspend fun failItems() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_item BEFORE INSERT ON items BEGIN SELECT RAISE(ABORT, 'injected item failure'); END")
    }

    @Test fun captureMembershipFailureRollsBackItemRefcountAndNewBlob() = runBlocking {
        val bytes = "new blob rollback".toByteArray()
        failMemberships()
        try {
            repository.captureStream(bytes.inputStream(), "rollback.txt", "text/plain", collectionIds = listOf("any"))
            fail("Expected injected transaction failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(repository.dao.getAllItemsIncludingTrash().isEmpty())
        assertNull(repository.dao.getAssetByHash(hash(bytes)))
        assertNull(repository.assetStore.getBlobFile(hash(bytes)))
    }

    @Test fun captureItemFailurePreservesSharedBlobAndRefcount() = runBlocking {
        val bytes = "shared original rollback".toByteArray()
        val first = repository.captureStream(bytes.inputStream(), "first.txt", "text/plain")
        failItems()
        try {
            repository.captureStream(bytes.inputStream(), "second.txt", "text/plain")
            fail("Expected injected transaction failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(listOf(first.id), repository.dao.getAllItemsIncludingTrash().map { it.id })
        assertEquals(1, repository.dao.getAssetByHash(hash(bytes))!!.refCount)
        assertArrayEquals(bytes, repository.assetStore.getBlobFile(hash(bytes))!!.readBytes())
    }

    private fun preview(bytes: ByteArray, collectionName: String = "Restored collection"): RestorePreview {
        val hash = hash(bytes)
        val stage = File(context.cacheDir, "restore_test_${java.util.UUID.randomUUID()}")
        File(stage, "assets").mkdirs()
        File(stage, "assets/$hash").writeBytes(bytes)
        val item = BackupItemRecord("restored", "restored", "", ItemKind.FILE, false, false, null, 1L, null, null, false,
            contentHash = hash, originalFileName = "original.bin", mimeType = "application/octet-stream", fileSizeBytes = bytes.size.toLong(),
            aiDescription = null, aiConcepts = null, aiStatus = "READY")
        val manifest = BackupManifest(items = listOf(item), collections = listOf(BackupCollectionRecord("collision", collectionName, "folder", "#123456", 1L)),
            memberships = listOf(BackupMembershipRecord("restored", "collision")), reminders = emptyList(),
            assets = listOf(BackupAssetRecord(hash, "untrusted/path", bytes.size.toLong(), "application/octet-stream")))
        return RestorePreview(manifest, stage, 1, 1, 1, 0)
    }

    @Test fun restoreTransactionFailureCleansNewlyPublishedBlob() = runBlocking {
        val bytes = "restore rollback original".toByteArray()
        val preview = preview(bytes)
        failItems()
        try { repository.commitRestore(preview); fail("Expected injected failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertTrue(repository.dao.getAllItemsIncludingTrash().isEmpty())
        assertNull(repository.dao.getAssetByHash(hash(bytes)))
        assertNull(repository.assetStore.getBlobFile(hash(bytes)))
        assertFalse(preview.stagingDir.exists())
    }

    @Test fun restoreMissingStagedOriginalCannotCreateAssetReference() = runBlocking {
        val preview = preview("missing staged".toByteArray())
        File(preview.stagingDir, "assets/${preview.manifest.assets.single().hash}").delete()
        try { repository.commitRestore(preview); fail("Expected missing original failure") }
        catch (_: java.io.IOException) { }
        assertTrue(repository.dao.getAllItemsIncludingTrash().isEmpty())
        assertTrue(repository.dao.getAllAssets().isEmpty())
    }

    @Test fun restoreCollectionCollisionRemapsWithoutOverwritingOwnerCollection() = runBlocking {
        val owner = CollectionEntity("collision", "Owner collection", "star", "#ffffff", 2L)
        repository.dao.insertCollection(owner)
        repository.commitRestore(preview("collision bytes".toByteArray()))
        assertEquals(owner, repository.dao.getAllCollections().find { it.id == owner.id })
        val restored = repository.dao.getItemWithCollections("restored")!!
        assertEquals("Restored collection", restored.collections.single().name)
        assertNotEquals(owner.id, restored.collections.single().id)
        assertTrue(repository.dao.getAllAssets().single().relativePath.startsWith("blobs/"))
    }

    @Test fun whatsAppRepeatedAttachmentImportIsIdempotentAndPreservesProvenance() = runBlocking {
        val chat = "13/10/2026, 10:00 - Ryan: notes.pdf (file attached)\n13/10/2026, 10:01 - Ryan: notes.pdf (file attached)"
        val bytes = "same supplied original".toByteArray()
        repository.importWhatsAppMigration(chat, attachments = mapOf("notes.pdf" to bytes.inputStream()))
        val messages = repository.dao.getAllActiveItems().filter { it.originalFileName == "notes.pdf" }
        assertEquals(2, messages.size)
        assertTrue(messages.all { it.contentHash == hash(bytes) })
        assertEquals(2, repository.dao.getAssetByHash(hash(bytes))!!.refCount)
        repository.setFavorite(messages.first().id, true)
        val repeated = repository.importWhatsAppMigration(chat, attachments = mapOf("notes.pdf" to bytes.inputStream()))
        assertEquals(0, repeated.first)
        assertEquals(2, repository.dao.getAssetByHash(hash(bytes))!!.refCount)
        assertTrue(repository.dao.getItemById(messages.first().id)!!.isFavorite)
        val export = repository.dao.getAllActiveItems().single { it.originalFileName == "WhatsApp export.txt" }
        assertArrayEquals(chat.toByteArray(), repository.assetStore.getBlobFile(export.contentHash!!)!!.readBytes())
        assertTrue(messages.all { "Sender: Ryan" in it.content && "13/10/2026" in it.content })
    }

    @Test fun staleAiCannotReplaceNewerMetadataOrPageChunks() = runBlocking {
        val item = repository.createNote("Owner title", "New content", emptyList())
        repository.dao.updateExtractedText(item.id, "new page", "COMPLETE", item.localIndexVersion + 1)
        val page = SearchDocumentEntity("new-page", item.id, pageNumber = 7, textContent = "new page")
        repository.dao.insertSearchDocument(page)
        assertFalse(repository.publishAiResult(item.id, item.localIndexVersion, "stale", "stale", "READY", emptyList(), 1))
        assertNull(repository.dao.getItemById(item.id)!!.aiDescription)
        assertEquals(listOf(page), repository.dao.getSearchDocumentsForItem(item.id))
        assertTrue(repository.publishAiResult(item.id, item.localIndexVersion + 1, "fresh", "chemistry", "READY", listOf(page), 1))
        assertEquals("fresh", repository.dao.getItemById(item.id)!!.aiDescription)
        repository.moveToTrash(item.id)
        assertFalse(repository.publishAiResult(item.id, item.localIndexVersion + 1, "trashed", "stale", "READY", emptyList(), 1))
        assertEquals("fresh", repository.dao.getItemById(item.id)!!.aiDescription)
    }
}
