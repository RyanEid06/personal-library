package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OwnerFeedbackCollectionsTest {
    private lateinit var db: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().putBoolean("ai_paused", true).commit()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context,
            androidx.work.Configuration.Builder().setExecutor(androidx.work.testing.SynchronousExecutor()).build())
        db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        repository = LibraryRepository(context, db)
    }
    @After fun close() = db.close()

    @Test fun movingAnItemReplacesItsPreviousFolder() = runBlocking {
        val a = repository.createCollection("Work", "folder", "#ffffff")
        val b = repository.createCollection("Home", "folder", "#ffffff")
        val item = repository.createNote("Task", "Owner content", listOf(a.id))
        repository.changeItemCollections(listOf(item.id), listOf(b.id), true)
        assertEquals(listOf(b.id), repository.dao.getCollectionIdsForItem(item.id))
        assertEquals("Owner content", repository.dao.getItemById(item.id)!!.content)
    }

    @Test fun deletingAFolderAlsoRemovesItsItemsFromTheActiveLibrary() = runBlocking {
        val folder = repository.createCollection("Delete me", "folder", "#ffffff")
        val item = repository.createNote("Task", "Keep bytes recoverable", listOf(folder.id))
        repository.deleteCollection(folder.id)
        assertTrue(repository.dao.getItemById(item.id)!!.isTrash)
        assertTrue(repository.dao.getAllActiveItems().isEmpty())
        assertFalse(repository.dao.getAllCollections().any { it.id == folder.id })
    }

    @Test fun whatsAppImportDoesNotRecreateRandomLinks() = runBlocking {
        repository.importWhatsAppMigration("13/10/2026, 09:00 - Ryan: synthetic text")
        assertTrue(repository.dao.getAllCollections().isEmpty())
        assertTrue(repository.dao.getAllActiveItems().all { repository.dao.getCollectionIdsForItem(it.id).isEmpty() })
    }

    @Test fun movingOutOfAnExcludedFolderCannotReenableAi() = runBlocking {
        val folder = repository.createCollection("Private", "folder", "#ffffff")
        val item = repository.createNote("Task", "Private content", listOf(folder.id))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("personal_library_settings", 0)
        prefs.edit().putStringSet("excluded_collections", setOf(folder.id)).commit()
        repository.setItemCollections(item.id, emptyList())
        assertTrue(prefs.getStringSet("excluded_items", emptySet())!!.contains(item.id))
    }

    @Test fun persistedUndoRestoresOnlyItemsTrashedByThatDeletion() = runBlocking {
        val folder = repository.createCollection("Undo", "folder", "#ffffff")
        val active = repository.createNote("Active", "body", listOf(folder.id))
        val oldTrash = repository.createNote("Old trash", "body", listOf(folder.id))
        repository.moveToTrash(oldTrash.id)
        val now = System.currentTimeMillis()
        val operation = repository.deleteCollection(folder.id, now = now)
        val reopened = LibraryRepository(ApplicationProvider.getApplicationContext(), db)
        assertEquals(operation, reopened.resumeCollectionDeletions().single())
        assertTrue(reopened.undoCollectionDeletion(operation.operationId, now + 4_999))
        assertFalse(repository.dao.getItemById(active.id)!!.isTrash)
        assertTrue(repository.dao.getItemById(oldTrash.id)!!.isTrash)
        assertEquals(listOf(folder.id), repository.dao.getCollectionIdsForItem(active.id))
        assertTrue(repository.dao.getPendingCollectionDeletions().isEmpty())
    }

    @Test fun expiryRejectsUndoAndManualRestoreGoesToInbox() = runBlocking {
        val folder = repository.createCollection("Expired", "folder", "#ffffff")
        val item = repository.createNote("Active", "body", listOf(folder.id))
        val now = System.currentTimeMillis()
        val operation = repository.deleteCollection(folder.id, now = now)
        assertFalse(repository.undoCollectionDeletion(operation.operationId, now + 5_000))
        assertFalse(repository.finalizeCollectionDeletion(operation.operationId, now + 4_999))
        assertTrue(repository.finalizeCollectionDeletion(operation.operationId, now + 5_000))
        repository.restoreFromTrash(item.id)
        assertTrue(repository.dao.getCollectionIdsForItem(item.id).isEmpty())
        assertFalse(repository.dao.getItemById(item.id)!!.isTrash)
        assertNull(repository.dao.getStoredCollection(folder.id))
    }

    @Test fun manualRestoreBeforeUndoDetachesFromTheHiddenFolderAndRejectsNewSaves() = runBlocking {
        val folder = repository.createCollection("Hidden", "folder", "#ffffff")
        val item = repository.createNote("Active", "body", listOf(folder.id))
        val now = System.currentTimeMillis()
        val operation = repository.deleteCollection(folder.id, now = now)
        try { repository.createNote("Late", "body", listOf(folder.id)); fail("Hidden folders reject saves") }
        catch (_: IllegalArgumentException) { }
        repository.restoreFromTrash(item.id)
        assertTrue(repository.dao.getCollectionIdsForItem(item.id).isEmpty())
        assertTrue(repository.undoCollectionDeletion(operation.operationId, now + 1))
        assertTrue(repository.dao.getCollectionIdsForItem(item.id).isEmpty())
    }

    @Test fun pendingDeletionBackupContainsTrashWithoutTheHiddenFolder() = runBlocking {
        val folder = repository.createCollection("Pending", "folder", "#ffffff")
        val item = repository.createNote("Active", "body", listOf(folder.id))
        repository.deleteCollection(folder.id)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File.createTempFile("pending-delete-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(file), "eight888".toCharArray()))
            val preview = repository.inspectBackup(android.net.Uri.fromFile(file), "eight888".toCharArray())!!
            try {
                assertTrue(preview.manifest.collections.isEmpty())
                assertTrue(preview.manifest.memberships.isEmpty())
                assertTrue(preview.manifest.items.single { it.id == item.id }.isTrash)
            } finally { repository.discardRestorePreview(preview) }
        } finally { file.delete() }
    }

    @Test fun restoringBackupDuringUndoCannotReuseHiddenFolderIdentity() = runBlocking {
        val folder = repository.createCollection("Pending restore", "folder", "#ffffff")
        val item = repository.createNote("Active", "body", listOf(folder.id))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File.createTempFile("hidden-restore-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(file), "eight888".toCharArray()))
            val preview = repository.inspectBackup(android.net.Uri.fromFile(file), "eight888".toCharArray())!!
            val now = System.currentTimeMillis()
            val operation = repository.deleteCollection(folder.id, now = now)
            assertEquals(1, repository.commitRestore(preview, keepBoth = true))
            val restored = repository.dao.getAllActiveItems().single()
            assertNotEquals(item.id, restored.id)
            val restoredFolder = repository.dao.getCollectionIdsForItem(restored.id).single()
            assertNotEquals(folder.id, restoredFolder)
            assertTrue(repository.finalizeCollectionDeletion(operation.operationId, now + 5_000))
            assertNotNull(repository.dao.getStoredCollection(restoredFolder))
            assertEquals(listOf(restoredFolder), repository.dao.getCollectionIdsForItem(restored.id))
        } finally { file.delete() }
    }

    @Test fun legacyBackupKeepsOldestSourceMembershipBeforeCollisionRemapping() = runBlocking {
        val owner = repository.createCollection("Owner", "folder", "#ffffff")
        val item = repository.createNote("Source", "body")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File.createTempFile("legacy-memberships-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(file), "eight888".toCharArray()))
            val preview = repository.inspectBackup(android.net.Uri.fromFile(file), "eight888".toCharArray())!!
            val incoming = preview.copy(manifest = preview.manifest.copy(
                collections = listOf(
                    com.personallibrary.core.backup.BackupCollectionRecord("z-newer", "Newer", "folder", "#ffffff", 2),
                    com.personallibrary.core.backup.BackupCollectionRecord(owner.id, "Older colliding ID", "folder", "#ffffff", 1)),
                memberships = listOf(
                    com.personallibrary.core.backup.BackupMembershipRecord(item.id, "z-newer"),
                    com.personallibrary.core.backup.BackupMembershipRecord(item.id, owner.id))))
            assertEquals(1, repository.commitRestore(incoming, keepBoth = true))
            val restored = repository.dao.getAllActiveItems().single { it.id != item.id }
            val folder = repository.dao.getStoredCollection(repository.dao.getCollectionIdsForItem(restored.id).single())!!
            assertEquals(1L, folder.createdAt)
            assertNotEquals(owner.id, folder.id)
            assertEquals(owner, repository.dao.getStoredCollection(owner.id))
            assertTrue(repository.dao.getCollectionIdsForItem(item.id).isEmpty())
        } finally { file.delete() }
    }

    @Test fun collectionUndoReschedulesReminderAndRetainsSharedOriginalReferences() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val folder = repository.createCollection("Originals", "folder", "#ffffff")
        val bytes = "Shared original synthetic bytes".toByteArray()
        val reminder = CaptureReminder(System.currentTimeMillis() + 120_000, false, false, null)
        val first = repository.captureStream(bytes.inputStream(), "first.txt", "text/plain", collectionIds = listOf(folder.id), reminder = reminder)
        repository.captureStream(bytes.inputStream(), "second.txt", "text/plain")
        val alarms = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.AlarmManager::class.java))
        assertEquals(1, alarms.scheduledAlarms.size)
        val now = System.currentTimeMillis()
        val operation = repository.deleteCollection(folder.id, now = now)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertTrue(repository.undoCollectionDeletion(operation.operationId, now + 1))
        assertEquals(1, alarms.scheduledAlarms.size)
        assertEquals(2, repository.dao.getAssetByHash(first.contentHash!!)!!.refCount)
        assertArrayEquals(bytes, repository.getBlobFile(first.contentHash)!!.readBytes())
    }
}
