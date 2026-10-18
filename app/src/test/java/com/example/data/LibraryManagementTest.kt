package com.example.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.personallibrary.core.backup.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryManagementTest {
    private lateinit var db: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("personal_library_settings", 0).edit().clear().putBoolean("ai_paused", true).commit()
        db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        repository = LibraryRepository(context, db)
    }
    @After fun close() = db.close()
    private suspend fun invalid(block: suspend () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }

    @Test fun collectionValidationAndEditingPreserveIdentity() = runBlocking {
        val first = repository.createCollection("  Reading  ", "folder", "#ffffff")
        assertEquals("Reading", first.name)
        invalid { repository.createCollection("reading", "folder", "#ffffff") }
        invalid { repository.createCollection("  Favorites ", "folder", "#ffffff") }
        invalid { repository.createCollection(" due REMINDERS ", "folder", "#ffffff") }
        invalid { repository.createCollection(" ", "folder", "#ffffff") }
        val second = repository.createCollection("Work", "folder", "#ffffff")
        invalid { repository.editCollection(second.id, "READING", "book", "#000000") }
        repository.editCollection(first.id, " READing ", "book", "#000000")
        assertEquals(first.copy(name = "READing", iconName = "book", colorHex = "#000000"), repository.dao.getAllCollections().first { it.id == first.id })
    }

    @Test fun collectionDeleteIsAtomicAndNeverDeletesItemsOrOtherMemberships() = runBlocking {
        val first = repository.createCollection("First", "folder", "#ffffff")
        val second = repository.createCollection("Second", "folder", "#ffffff")
        val item = repository.createNote("Keep", "Owner content", listOf(first.id, second.id))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete BEFORE DELETE ON collections BEGIN SELECT RAISE(ABORT, 'injected'); END")
        try { repository.deleteCollection(first.id); fail("Expected trigger failure") } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(setOf(first.id, second.id), repository.dao.getCollectionIdsForItem(item.id).toSet())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete")
        repository.deleteCollection(first.id)
        assertEquals(item, repository.dao.getItemById(item.id))
        assertEquals(listOf(second.id), repository.dao.getCollectionIdsForItem(item.id))
    }

    @Test fun bulkMembershipChangesAreUnionSubtractAndValidateBeforeWriting() = runBlocking {
        val first = repository.createCollection("First", "folder", "#ffffff")
        val second = repository.createCollection("Second", "folder", "#ffffff")
        val item = repository.createNote("Keep", "Content", listOf(first.id))
        invalid { repository.changeItemCollections(listOf(item.id, "missing"), listOf(second.id), true) }
        invalid { repository.changeItemCollections(listOf(item.id), listOf("missing"), true) }
        assertEquals(listOf(first.id), repository.dao.getCollectionIdsForItem(item.id))
        repository.changeItemCollections(listOf(item.id, item.id), listOf(second.id), true)
        assertEquals(setOf(first.id, second.id), repository.dao.getCollectionIdsForItem(item.id).toSet())
        repository.changeItemCollections(listOf(item.id), listOf(second.id), false)
        assertEquals(listOf(first.id), repository.dao.getCollectionIdsForItem(item.id))
        repository.moveToTrash(item.id)
        invalid { repository.changeItemCollections(listOf(item.id), listOf(second.id), true) }
    }

    @Test fun bulkMembershipFailureRollsBackAllSelectedItems() = runBlocking {
        val existing = repository.createCollection("Existing", "folder", "#ffffff")
        val extra = repository.createCollection("Extra", "folder", "#ffffff")
        val first = repository.createNote("First", "Body", listOf(existing.id))
        val second = repository.createNote("Second", "Body", listOf(existing.id))
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_second BEFORE INSERT ON item_collection_cross_ref WHEN new.itemId = '${second.id}' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        try { repository.changeItemCollections(listOf(first.id, second.id), listOf(extra.id), true); fail("Expected trigger failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(listOf(existing.id), repository.dao.getCollectionIdsForItem(first.id))
        assertEquals(listOf(existing.id), repository.dao.getCollectionIdsForItem(second.id))
    }

    @Test fun renamedRandomLinksRoutesImportAndRoundTripsPortableRole() = runBlocking {
        val random = repository.getOrCreateRandomLinksCollection()
        repository.editCollection(random.id, "Inbox", "chat", "#123456")
        repository.importWhatsAppMigration("13/10/2026, 09:00 - Ryan: hello")
        assertEquals(random.id, repository.getOrCreateRandomLinksCollection().id)
        assertTrue(repository.dao.getAllActiveItems().all { repository.dao.getCollectionIdsForItem(it.id) == listOf(random.id) })
        val destination = File.createTempFile("role-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(destination), "role-passphrase".toCharArray()))
            val preview = repository.inspectBackup(android.net.Uri.fromFile(destination), "role-passphrase".toCharArray())!!
            assertEquals(CollectionEntity.ROLE_RANDOM_LINKS, preview.manifest.collections.single().appRole)
            repository.discardRestorePreview(preview)
        } finally { destination.delete() }
    }

    @Test fun restoreMergesRenamedRoleAndRemapsMemberships() = runBlocking {
        val live = repository.getOrCreateRandomLinksCollection()
        repository.editCollection(live.id, "My inbox", "chat", "#abcdef")
        val source = repository.createNote("Imported", "Body")
        val destination = File.createTempFile("restore-role-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(destination), "role-passphrase".toCharArray()))
            val preview = repository.inspectBackup(android.net.Uri.fromFile(destination), "role-passphrase".toCharArray())!!
            val incoming = preview.copy(manifest = preview.manifest.copy(
                collections = listOf(BackupCollectionRecord("other-install", "Renamed there", "folder", "#ffffff", 1, CollectionEntity.ROLE_RANDOM_LINKS)),
                memberships = listOf(BackupMembershipRecord(source.id, "other-install"))))
            assertEquals(1, repository.commitRestore(incoming, keepBoth = true))
            assertEquals(1, repository.dao.getAllCollections().size)
            val imported = repository.dao.getAllActiveItems().single { it.id != source.id }
            assertEquals(listOf(live.id), repository.dao.getCollectionIdsForItem(imported.id))
            assertEquals("My inbox", repository.getOrCreateRandomLinksCollection().name)
        } finally { destination.delete() }
    }

    @Test fun freshRestorePreservesRenamedPortableRole() = freshRoleRestore(false)
    @Test fun legacyBackupWithoutRoleAdoptsDefaultAndPreservesMembership() = freshRoleRestore(true)

    @Test fun restoredOrdinaryCollectionNameCollisionKeepsBothIdentitiesAndMemberships() = restoredNameCollision(false)
    @Test fun restoredRoleNameCollisionKeepsOrdinaryCollectionAndPortableRole() = restoredNameCollision(true)

    @Test fun repeatedRestoreReusesSameCollectionIdAfterCollisionRenameAndOwnerRename() = runBlocking {
        val live = repository.createCollection("Reading", "book", "#123456")
        val source = repository.createNote("Saved", "Owner body", listOf(live.id))
        val destination = File.createTempFile("repeat-restore-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(destination), "restore-passphrase".toCharArray()))
            repeat(3) { index ->
                val preview = repository.inspectBackup(android.net.Uri.fromFile(destination), "restore-passphrase".toCharArray())!!
                val incoming = preview.copy(manifest = preview.manifest.copy(
                    collections = listOf(BackupCollectionRecord("remote-id", "Reading", "folder", "#ffffff", 1)),
                    memberships = listOf(BackupMembershipRecord(source.id, "remote-id"))))
                assertEquals(1, repository.commitRestore(incoming, keepBoth = true))
                val collections = repository.dao.getAllCollections()
                assertEquals("Repeated restore must retain one collection per portable ID", 2, collections.size)
                val restored = collections.single { it.id == "remote-id" }
                assertEquals(if (index < 2) "Reading (restored 2)" else "Owner renamed inbox", restored.name)
                assertEquals(if (index < 2) "folder" else "chat", restored.iconName)
                assertEquals(if (index < 2) "#ffffff" else "#abcdef", restored.colorHex)
                val imported = repository.dao.getAllActiveItems().filter { it.id != source.id }
                assertEquals(index + 1, imported.size)
                assertTrue(imported.all { repository.dao.getCollectionIdsForItem(it.id) == listOf("remote-id") })
                if (index == 1) repository.editCollection("remote-id", "Owner renamed inbox", "chat", "#abcdef")
            }
            assertEquals(live, repository.dao.getAllCollections().single { it.id == live.id })
        } finally { destination.delete() }
    }

    @Test fun repeatedTrueIdCollisionRestoreReusesRemappedIdentityAfterOwnerRename() = runBlocking {
        val owner = repository.createCollection("Owner collection", "book", "#123456")
        val source = repository.createNote("Saved", "Owner body", listOf(owner.id))
        val destination = File.createTempFile("repeat-id-collision-", ".plb", context.cacheDir)
        var remappedId: String? = null
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(destination), "restore-passphrase".toCharArray()))
            repeat(4) { index ->
                val preview = repository.inspectBackup(android.net.Uri.fromFile(destination), "restore-passphrase".toCharArray())!!
                val incoming = preview.copy(manifest = preview.manifest.copy(
                    collections = listOf(BackupCollectionRecord(owner.id, "Restored collection", "folder", "#ffffff", 1)),
                    memberships = listOf(BackupMembershipRecord(source.id, owner.id))))
                assertEquals(1, repository.commitRestore(incoming, keepBoth = true))
                val collections = repository.dao.getAllCollections()
                assertEquals("Repeated true collision restore must reuse its remapped identity", if (index < 3) 2 else 1, collections.size)
                if (index < 3) assertEquals(owner, collections.single { it.id == owner.id })
                else assertTrue(collections.none { it.id == owner.id })
                val restored = collections.single { it.id != owner.id }
                if (index == 0) remappedId = restored.id else assertEquals(remappedId, restored.id)
                assertEquals(1L, restored.createdAt)
                assertEquals(if (index == 0) "Restored collection" else "Owner renamed restored collection", restored.name)
                assertEquals(if (index == 0) "folder" else "chat", restored.iconName)
                assertEquals(if (index == 0) "#ffffff" else "#abcdef", restored.colorHex)
                assertTrue(repository.dao.getAllActiveItems().filter { it.id != source.id }.all {
                    repository.dao.getCollectionIdsForItem(it.id) == listOf(restored.id)
                })
                if (index == 0) repository.editCollection(restored.id, "Owner renamed restored collection", "chat", "#abcdef")
                if (index == 2) repository.deleteCollection(owner.id)
            }
        } finally { destination.delete() }
    }

    private fun restoredNameCollision(role: Boolean) = runBlocking {
        val live = repository.createCollection("Reading", "book", "#123456")
        val source = repository.createNote("Saved", "Owner body", listOf(live.id))
        val destination = File.createTempFile("restore-name-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(destination), "restore-passphrase".toCharArray()))
            val preview = repository.inspectBackup(android.net.Uri.fromFile(destination), "restore-passphrase".toCharArray())!!
            val incoming = preview.copy(manifest = preview.manifest.copy(
                collections = listOf(BackupCollectionRecord("different-id", "  rEaDiNg  ", "folder", "#ffffff", 1,
                    if (role) CollectionEntity.ROLE_RANDOM_LINKS else null)),
                memberships = listOf(BackupMembershipRecord(source.id, "different-id"))))
            assertEquals(1, repository.commitRestore(incoming, keepBoth = true))
            val collections = repository.dao.getAllCollections()
            assertEquals(2, collections.size)
            assertEquals(live, collections.single { it.id == live.id })
            val restored = collections.single { it.id == "different-id" }
            assertEquals("rEaDiNg (restored 2)", restored.name)
            assertEquals(2, collections.map { it.name.lowercase() }.distinct().size)
            assertEquals(listOf(live.id), repository.dao.getCollectionIdsForItem(source.id))
            val imported = repository.dao.getAllActiveItems().single { it.id != source.id }
            assertEquals(listOf(restored.id), repository.dao.getCollectionIdsForItem(imported.id))
            if (role) assertEquals(restored.id, repository.getOrCreateRandomLinksCollection().id)
            else assertNull(restored.appRole)
        } finally { destination.delete() }
    }

    private fun freshRoleRestore(legacy: Boolean) = runBlocking {
        val source = repository.createNote("Imported", "Body")
        val destination = File.createTempFile("fresh-role-", ".plb", context.cacheDir)
        try {
            assertTrue(repository.exportEncryptedBackup(android.net.Uri.fromFile(destination), "role-passphrase".toCharArray()))
            val preview = repository.inspectBackup(android.net.Uri.fromFile(destination), "role-passphrase".toCharArray())!!
            repository.deletePermanently(source.id)
            val incoming = preview.copy(manifest = preview.manifest.copy(
                collections = listOf(BackupCollectionRecord("other-install", if (legacy) "Random Links" else "Renamed inbox", "folder", "#ffffff", 1,
                    if (legacy) null else CollectionEntity.ROLE_RANDOM_LINKS)),
                memberships = listOf(BackupMembershipRecord(source.id, "other-install"))))
            assertEquals(1, repository.commitRestore(incoming))
            val role = repository.getOrCreateRandomLinksCollection()
            assertEquals("other-install", role.id)
            assertEquals(if (legacy) "Random Links" else "Renamed inbox", role.name)
            assertEquals(listOf(role.id), repository.dao.getCollectionIdsForItem(source.id))
            assertEquals(1, repository.dao.getAllCollections().size)
        } finally { destination.delete() }
    }

    @Test fun bodyEditInvalidatesOldAiAndFtsWithoutLosingProvenance() = runBlocking {
        val item = repository.createNote("Before", "obsoleteword")
        val original = item.copy(sourceDate = 123, createdAt = 456, aiDescription = "staleai", aiConcepts = "staleconcept")
        repository.dao.updateItem(original)
        repository.dao.insertSearchDocument(SearchDocumentEntity("stale", item.id, textContent = "staleai", embedding = "1,2"))
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(item.id))
        repository.editItem(item.id, " After ", "freshword")
        val edited = repository.dao.getItemById(item.id)!!
        assertEquals("After", edited.title)
        assertEquals(123L, edited.sourceDate)
        assertEquals(456L, edited.createdAt)
        assertNull(edited.aiDescription)
        assertNull(edited.aiConcepts)
        assertTrue(edited.localIndexVersion > item.localIndexVersion)
        assertTrue(repository.dao.getSearchDocumentsForItem(item.id).isEmpty())
        assertTrue(repository.dao.searchFts("obsoleteword*").isEmpty())
        assertEquals(item.id, repository.dao.searchFts("freshword*").single().id)
        assertFalse(repository.publishAiResult(item.id, item.localIndexVersion, "stale", "stale", "READY", emptyList(), 1))
    }

    @Test fun mediaTitleOnlyEditPreservesOriginalAndDerivedData() = runBlocking {
        val item = repository.captureStream("original media bytes".byteInputStream(), "original.mp4", "video/mp4")
        val indexed = item.copy(extractedText = "cached text", aiDescription = "media description", localIndexCoverage = "COMPLETE")
        repository.dao.updateItem(indexed)
        val document = SearchDocumentEntity("page", item.id, pageNumber = 2, textContent = "cached text", embedding = "1,2")
        repository.dao.insertSearchDocument(document)
        repository.editItem(item.id, "New title", indexed.content)
        val edited = repository.dao.getItemById(item.id)!!
        assertEquals(indexed.copy(title = "New title", localIndexVersion = indexed.localIndexVersion + 1), edited)
        assertTrue(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
        assertTrue(repository.dao.getSearchDocumentsForItem(item.id).any { it.textContent.startsWith("New title") })
        assertTrue(repository.dao.getSearchDocumentsForItem(item.id).all { it.embedding == null })
        assertTrue(repository.dao.getSearchDocumentsForItem(item.id).any { it.pageNumber == 2 && it.textContent == "cached text" })
        assertFalse(repository.publishAiResult(item.id, indexed.localIndexVersion, "stale", "stale", "READY", listOf(document), 1))
        assertEquals("original media bytes", repository.getBlobFile(item.contentHash!!)!!.readText())
        invalid { repository.editItem(item.id, "New title", "Replace the file provenance") }
    }

    @Test fun linkEditValidatesUrlAndPreservesSupportedSchemes() = runBlocking {
        val item = repository.saveLink("https://example.com", "Link", "")
        invalid { repository.editItem(item.id, "", "https://example.com") }
        invalid { repository.editItem(item.id, "Link", "https://") }
        invalid { repository.editItem(item.id, "Link", "plain words") }
        invalid { repository.editItem(item.id, "Link", "https://exa mple.com") }
        invalid { repository.editItem(item.id, "Link", "javascript:alert(1)") }
        invalid { repository.editItem(item.id, "Link", "file:///private/file") }
        repository.editItem(item.id, "Email", "mailto:hello@example.com\nUser note")
        assertEquals("mailto:hello@example.com\nUser note", repository.dao.getItemById(item.id)!!.content)
    }

    @Test fun titleEditCompletesPendingInitialExtractionAndRejectsOldPublication() = runBlocking {
        val item = repository.captureStream("initial local text".byteInputStream(), "pending.txt", "text/plain")
        repository.editItem(item.id, "Current title", item.content)
        val edited = repository.dao.getItemById(item.id)!!
        assertEquals("initial local text", edited.extractedText)
        assertEquals("COMPLETE", edited.localIndexCoverage.uppercase())
        assertFalse(repository.publishLocalIndex(item.id, item.localIndexVersion, "obsolete", "COMPLETE", emptyList()))
        assertFalse(repository.dao.getAnalysisJob(item.id)!!.metadataOnly)
        assertTrue(repository.checkpointLocalIndex(item.id, edited.localIndexVersion, edited.extractedText, edited.localIndexCoverage))
    }

    @Test fun titleEditRetainsFirstAiAnalysisWhenLocalExtractionFinishedOffline() = runBlocking {
        val item = repository.captureStream("initial local text".byteInputStream(), "first-analysis.txt", "text/plain")
        assertTrue(repository.publishLocalIndex(item.id, item.localIndexVersion, "initial local text", "COMPLETE", emptyList()))
        val indexed = repository.dao.getItemById(item.id)!!
        repository.dao.insertOrUpdateAnalysisJob(AnalysisJobEntity(item.id, indexed.localIndexVersion, indexed.localIndexVersion, status = "QUEUED"))
        repository.editItem(item.id, "Corrected before first analysis", item.content)
        val edited = repository.dao.getItemById(item.id)!!
        val job = repository.dao.getAnalysisJob(item.id)!!
        assertFalse("A rename must not suppress the first ever AI analysis", job.metadataOnly)
        assertEquals(edited.localIndexVersion, job.expectedContentVersion)
        assertEquals("initial local text", edited.extractedText)
        assertNull(edited.aiDescription)
        assertNull(edited.aiConcepts)
    }

    @Test fun whatsAppBodyEditPreservesExactSenderAndImportProvenance() = runBlocking {
        repository.importWhatsAppMigration("13/10/2026, 09:00 - Ryan: Original message")
        val item = repository.dao.getAllActiveItems().single { it.id.startsWith("wa_") || it.content.contains("\n\nWhatsApp provenance\n") }
        val suffix = item.content.substring(item.content.indexOf("\n\nWhatsApp provenance\n"))
        invalid { repository.editItem(item.id, "Corrected", "No provenance") }
        repository.editItem(item.id, "Corrected", "Corrected message" + suffix)
        val edited = repository.dao.getItemById(item.id)!!
        assertEquals("Corrected message" + suffix, edited.content)
        assertEquals(item.createdAt, edited.createdAt)
        assertEquals(item.sourceDate, edited.sourceDate)
    }
}
