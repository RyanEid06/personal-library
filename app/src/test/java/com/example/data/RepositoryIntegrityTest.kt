package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personallibrary.core.backup.*
import com.personallibrary.core.model.ItemKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
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

    @Test fun exportUsesOneMetadataSnapshotDuringConcurrentCollectionAssignment() = runBlocking {
        val armed = java.util.concurrent.atomic.AtomicBoolean(false)
        val changed = java.util.concurrent.CountDownLatch(1)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        lateinit var exporting: LibraryRepository
        lateinit var itemId: String
        val snapshotDb = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK)
            .setQueryCallback({ sql, _ ->
                if (sql.contains("SELECT * FROM reminders", true) && armed.compareAndSet(true, false)) {
                    scope.launch {
                        try {
                            val collection = exporting.createCollection("Created during export", "folder", "#ffffff")
                            exporting.setItemCollections(itemId, listOf(collection.id))
                        } finally { changed.countDown() }
                    }
                    changed.await(1, java.util.concurrent.TimeUnit.SECONDS)
                }
            }, java.util.concurrent.Executor { it.run() }).build()
        exporting = LibraryRepository(context, snapshotDb)
        val destination = File.createTempFile("snapshot-", ".plb", context.cacheDir)
        try {
            itemId = exporting.createNote("Snapshot", "Concurrent metadata", emptyList()).id
            armed.set(true)
            assertTrue(exporting.exportEncryptedBackup(android.net.Uri.fromFile(destination), "snapshot-passphrase".toCharArray()))
            val preview = exporting.inspectBackup(android.net.Uri.fromFile(destination), "snapshot-passphrase".toCharArray())!!
            try {
                val collectionIds = preview.manifest.collections.map { it.id }.toSet()
                assertTrue("Every exported membership must belong to the same snapshot", preview.manifest.memberships.all { it.collectionId in collectionIds })
            } finally { preview.stagingDir.deleteRecursively() }
            assertTrue(changed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { scope.cancel(); snapshotDb.close(); destination.delete() }
    }

    @Test fun collectionReplacementFailureKeepsPreviousPrivateMembership() = runBlocking {
        val private = repository.createCollection("Private", "folder", "#ffffff")
        val extra = repository.createCollection("Extra", "folder", "#ffffff")
        val item = repository.createNote("Private note", "Private content", listOf(private.id))
        failMemberships()
        try {
            repository.setItemCollections(item.id, listOf(private.id, extra.id))
            fail("Expected injected membership failure")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(listOf(private.id), repository.dao.getCollectionIdsForItem(item.id))
    }

    @Test fun whatsAppReplacementInvalidatesOldExtractionChunksAndAnalysis() = runBlocking {
        val chat = "13/10/2026, 09:00 - Ryan: notes.txt (file attached)"
        repository.importWhatsAppMigration(chat, attachments = mapOf("notes.txt" to "old original".byteInputStream()))
        val item = repository.dao.getAllActiveItems().single { it.originalFileName == "notes.txt" }
        assertTrue(repository.publishLocalIndex(item.id, item.localIndexVersion, "old extraction", "COMPLETE", emptyList()))
        val indexed = repository.dao.getItemById(item.id)!!
        assertTrue(repository.publishAiResult(item.id, indexed.localIndexVersion, "old description", "old concepts", "READY",
            listOf(SearchDocumentEntity("old_chunk", item.id, 0, textContent = "old original")), 1))
        repository.dao.updateItem(indexed.copy(isFavorite = true, aiDescription = "old description", aiConcepts = "old concepts", aiStatus = "READY"))
        repository.importWhatsAppMigration(chat, attachments = mapOf("notes.txt" to "replacement original".byteInputStream()))
        val replaced = repository.dao.getItemById(item.id)!!
        assertEquals(indexed.localIndexVersion + 1, replaced.localIndexVersion)
        assertNull(replaced.extractedText)
        assertNull(replaced.aiDescription)
        assertEquals("PENDING", replaced.localIndexCoverage)
        assertEquals("PENDING", replaced.aiStatus)
        assertTrue(replaced.isFavorite)
        assertTrue(repository.dao.getSearchDocumentsForItem(item.id).isEmpty())
        assertNull(repository.dao.getAnalysisJob(item.id))
        assertFalse(repository.publishAiResult(item.id, indexed.localIndexVersion, "stale", "stale", "READY", emptyList(), 1))
        assertFalse(repository.publishLocalIndex(item.id, indexed.localIndexVersion, "stale", "COMPLETE", emptyList()))
        assertFalse(repository.checkpointLocalIndex(item.id, indexed.localIndexVersion, "stale progress", "PARTIAL"))
        assertNull(repository.dao.getItemById(item.id)!!.extractedText)
    }

    @Test fun supplyingPreviouslyMissingWhatsAppAttachmentInvalidatesTextOnlyVersion() = runBlocking {
        val chat = "13/10/2026, 09:00 - Ryan: missing.txt (file attached)"
        repository.importWhatsAppMigration(chat)
        val item = repository.dao.getAllActiveItems().single { it.originalFileName == "missing.txt" }
        repository.publishLocalIndex(item.id, item.localIndexVersion, null, "COMPLETE", emptyList())
        val before = repository.dao.getItemById(item.id)!!
        repository.importWhatsAppMigration(chat, attachments = mapOf("missing.txt" to "found original".byteInputStream()))
        val after = repository.dao.getItemById(item.id)!!
        assertEquals(before.localIndexVersion + 1, after.localIndexVersion)
        assertEquals("PENDING", after.localIndexCoverage)
        assertFalse(repository.publishAiResult(item.id, before.localIndexVersion, "stale", "stale", "READY", emptyList(), 1))
    }

    @Test fun repeatedWhatsAppAttachmentRepairsLegacyMediaMetadataWithoutReplacingOwnerState() = runBlocking {
        val chat = "13/10/2026, 09:00 - Synthetic: legacy-photo.jpg (file attached)"
        val bytes = "synthetic unchanged jpeg original".toByteArray()
        assertEquals(1 to 0, repository.importWhatsAppMigration(chat, attachments = mapOf("legacy-photo.jpg" to bytes.inputStream())))
        val imported = repository.dao.getAllActiveItems().single { it.originalFileName == "legacy-photo.jpg" }
        val extra = repository.createCollection("Owner media", "folder", "#ffffff")
        repository.changeItemCollections(listOf(imported.id), listOf(extra.id), add = true)
        val memberships = repository.dao.getCollectionIdsForItem(imported.id).toSet()
        val legacy = imported.copy(title = "Owner's photo caption", kind = "FILE", mimeType = "application/octet-stream",
            isFavorite = true, extractedText = "stale generic-file extraction", localIndexCoverage = "COMPLETE",
            aiDescription = "stale file analysis", aiConcepts = "stale concepts", aiStatus = "READY")
        repository.dao.updateItem(legacy)
        repository.dao.insertSearchDocument(SearchDocumentEntity("legacy-media-chunk", imported.id, textContent = "stale file chunk"))
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(imported.id, status = "COMPLETED"))
        val asset = repository.dao.getAssetByHash(hash(bytes))!!
        repository.dao.updateAsset(asset.copy(kind = "FILE", mimeType = "application/octet-stream"))

        assertEquals(0 to 0, repository.importWhatsAppMigration(chat, attachments = mapOf("legacy-photo.jpg" to bytes.inputStream())))
        val repaired = repository.dao.getItemById(imported.id)!!
        assertEquals("PHOTO", repaired.kind)
        assertEquals("image/jpeg", repaired.mimeType)
        assertEquals(legacy.copy(kind = "PHOTO", mimeType = "image/jpeg", localIndexVersion = legacy.localIndexVersion + 1,
            extractedText = null, localIndexCoverage = "PENDING", aiDescription = null, aiConcepts = null, aiStatus = "PENDING"), repaired)
        assertEquals(memberships, repository.dao.getCollectionIdsForItem(imported.id).toSet())
        assertEquals(asset.copy(kind = "PHOTO", mimeType = "image/jpeg"), repository.dao.getAssetByHash(hash(bytes)))
        assertArrayEquals(bytes, repository.assetStore.getBlobFile(hash(bytes))!!.readBytes())
        assertTrue(repository.dao.getSearchDocumentsForItem(imported.id).isEmpty())
        assertNull(repository.dao.getAnalysisJob(imported.id))
        assertFalse(repository.publishLocalIndex(imported.id, legacy.localIndexVersion, "stale worker", "COMPLETE", emptyList()))
        assertFalse(repository.publishAiResult(imported.id, legacy.localIndexVersion, "stale", "stale", "READY", emptyList(), 1))

        // Once repaired, the same original must not bump the version or discard fresh owner state again.
        val indexed = repaired.copy(extractedText = "current image extraction", localIndexCoverage = "COMPLETE")
        repository.dao.updateItem(indexed)
        assertEquals(0 to 0, repository.importWhatsAppMigration(chat, attachments = mapOf("legacy-photo.jpg" to bytes.inputStream())))
        assertEquals(indexed, repository.dao.getItemById(imported.id))
    }

    @Test fun whatsAppMediaMetadataRepairPreservesSpecificSharedAssetMetadataAndLinkSemantics() = runBlocking {
        val bytes = "synthetic shared bytes with two explicit interpretations".toByteArray()
        val owner = repository.captureStream(bytes.inputStream(), "owner.pdf", "application/pdf")
        val specific = repository.dao.getAssetByHash(hash(bytes))!!
        val chat = "13/10/2026, 09:00 - Synthetic: https://example.com/image legacy-photo.jpg (file attached)"
        repository.importWhatsAppMigration(chat, attachments = mapOf("legacy-photo.jpg" to bytes.inputStream()))
        val imported = repository.dao.getAllActiveItems().single { it.originalFileName == "legacy-photo.jpg" }
        repository.dao.updateItem(imported.copy(kind = "FILE", mimeType = "application/octet-stream"))
        assertEquals(0 to 0, repository.importWhatsAppMigration(chat, attachments = mapOf("legacy-photo.jpg" to bytes.inputStream())))
        val repaired = repository.dao.getItemById(imported.id)!!
        assertEquals("LINK", repaired.kind)
        assertEquals("image/jpeg", repaired.mimeType)
        assertEquals(specific.copy(refCount = 2), repository.dao.getAssetByHash(hash(bytes)))
        assertEquals(owner, repository.dao.getItemById(owner.id))
        assertArrayEquals(bytes, repository.assetStore.getBlobFile(hash(bytes))!!.readBytes())
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

    @Test fun restoredOriginalReusesSharedBlobAndCountsBothCommittedItems() = runBlocking {
        val bytes = "shared restore original".toByteArray()
        val first = repository.captureStream(bytes.inputStream(), "first.bin", "application/octet-stream")
        repository.commitRestore(preview(bytes))
        assertEquals(2, repository.dao.getAssetByHash(hash(bytes))!!.refCount)
        repository.deletePermanently("restored")
        assertEquals(1, repository.dao.getAssetByHash(hash(bytes))!!.refCount)
        assertArrayEquals(bytes, repository.assetStore.getBlobFile(first.contentHash!!)!!.readBytes())
    }
}
