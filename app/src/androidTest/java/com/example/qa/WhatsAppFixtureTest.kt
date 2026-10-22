package com.example.qa

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.CollectionEntity
import com.example.data.DATABASE_CALLBACK
import com.example.data.LibraryRepository
import com.example.data.LibraryRoomDatabase
import com.example.model.SortOrder
import com.example.ui.LocalSearchContext
import com.example.ui.projectLocalSearch
import com.personallibrary.core.migration.DateOrder
import com.personallibrary.core.migration.WhatsAppExportParser
import com.personallibrary.core.model.SearchQuery
import com.personallibrary.core.model.ItemKind
import com.personallibrary.core.storage.ContentClassifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Synthetic T01–T09 repository/Room checks; no picker, preview, external viewer or official APK proof. */
@RunWith(AndroidJUnit4::class)
class WhatsAppFixtureTest {
    private lateinit var db: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var isolatedContext: Context
    private lateinit var ownedRoot: File
    private lateinit var settings: android.content.SharedPreferences
    private var hadPaused = false
    private var wasPaused = false

    @Before fun setup() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        settings = app.getSharedPreferences("personal_library_settings", Context.MODE_PRIVATE)
        hadPaused = settings.contains("ai_paused")
        wasPaused = settings.getBoolean("ai_paused", false)
        check(settings.edit().putBoolean("ai_paused", true).commit())
        ownedRoot = File.createTempFile("whatsapp-fixture-", "", app.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        // Each test owns its managed blobs, even if another suite saved the same synthetic bytes.
        isolatedContext = object : ContextWrapper(app) {
            override fun getFilesDir() = File(ownedRoot, "files").apply { mkdirs() }
            override fun getCacheDir() = File(ownedRoot, "cache").apply { mkdirs() }
        }
        db = Room.inMemoryDatabaseBuilder(app, LibraryRoomDatabase::class.java)
            .addCallback(DATABASE_CALLBACK).build()
        repository = LibraryRepository(isolatedContext, db)
    }

    @After fun cleanup() {
        try {
            if (::db.isInitialized) db.close()
            if (::ownedRoot.isInitialized) check(ownedRoot.deleteRecursively())
        } finally {
            if (::settings.isInitialized) {
                val edit = settings.edit()
                if (hadPaused) edit.putBoolean("ai_paused", wasPaused) else edit.remove("ai_paused")
                check(edit.commit())
            }
        }
    }

    @Test fun full200PreservesEveryMessageProvenanceSourceOrderSearchAndDuplicateIdentity() = runBlocking {
        val bytes = fixtureBytes("Memora_QA_WhatsApp_200.txt")
        assertEquals("8f00e73fff8ef45bd43b45fdb5d94a8d7841f7b5faa7024565e8d113850b94b7", hash(bytes))
        val chat = bytes.toString(Charsets.UTF_8)
        val source = sourceRecords(chat)
        assertEquals(200, source.size)
        assertEquals((1..200).map { "QA-MSG-%03d".format(Locale.US, it) }, source.map { it.body.substringBefore(" |") })
        val parsed = WhatsAppExportParser.parse(chat, DateOrder.DAY_FIRST, TimeZone.getDefault())
        assertEquals(200, parsed.totalMessages)
        assertEquals(0, parsed.systemMessageCount)
        assertEquals(0, parsed.mediaReferencesCount)
        assertEquals(0, parsed.unparseableDateCount)
        assertEquals(0, parsed.ambiguousDateCount)
        assertEquals(200 to 0, repository.importWhatsAppMigration(chat, DateOrder.DAY_FIRST))
        val expectedIds = source.map { it.key }
        val saved = messages().associateBy { it.id }
        assertEquals(expectedIds.toSet(), saved.keys)
        source.forEach { record ->
            val item = checkNotNull(saved[record.key])
            assertEquals(record.provenance, item.content)
            assertEquals(timestamp(record.date), item.sourceDate)
            assertEquals(record.body, parsed.messages.single { it.importKey == item.id }.rawText)
            val marker = record.body.substringBefore(" | ")
            assertEquals(setOf(item.id), repository.dao.searchFts(checkNotNull(SearchQuery.fts(marker))).map { it.id }.toSet())
            assertEquals(setOf(item.id), repository.searchWithCollections(marker).map { it.item.id }.toSet())
        }
        assertTrue("The supplied long-message fixture must be preserved in full", source.any { it.body.length > 700 })
        assertTrue(source.any { '\n' in it.body })
        assertTrue(source.any { "مرحبا" in it.body })
        assertTrue(source.any { "bibliothèque" in it.body })
        assertTrue(source.any { "🚀" in it.body })
        val rows = expectedIds.map { checkNotNull(repository.dao.getItemWithCollections(it)) }
        assertEquals(expectedIds, projectLocalSearch(rows.reversed(), LocalSearchContext(sort = SortOrder.SOURCE_OLDEST)).items.map { it.item.id })
        assertEquals(expectedIds.reversed(), projectLocalSearch(rows, LocalSearchContext(sort = SortOrder.SOURCE_NEWEST)).items.map { it.item.id })
        val randomLinks = repository.getOrCreateRandomLinksCollection()
        assertEquals(CollectionEntity.ROLE_RANDOM_LINKS, randomLinks.appRole)
        assertEquals(201, repository.dao.getAllActiveItems().size) // 200 eligible messages plus original export.
        assertEquals(expectedIds.toSet() + exportItem().id, repository.dao.getItemIdsForCollection(randomLinks.id).toSet())
        assertArrayEquals(bytes, checkNotNull(repository.getBlobFile(checkNotNull(exportItem().contentHash))).readBytes())
        val before = repository.dao.getAllItemsIncludingTrash().associateBy { it.id }
        assertEquals(0 to 0, repository.importWhatsAppMigration(chat, DateOrder.DAY_FIRST))
        assertEquals(before, repository.dao.getAllItemsIncludingTrash().associateBy { it.id })
        assertEquals(1, repository.dao.getAllAssets().single().refCount)
    }

    @Test fun incremental160To200AddsExactly40AndKeepsRenamedRandomLinksAndPriorRecords() = runBlocking {
        val small = fixtureBytes("Memora_QA_WhatsApp_160.txt")
        assertEquals("403a9800444ac4a3523c82b2a84803db055e4ffb6bb144a4c6fa9912c5bdae49", hash(small))
        val full = fixtureText("Memora_QA_WhatsApp_200.txt")
        assertEquals(sourceRecords(small.toString(Charsets.UTF_8)), sourceRecords(full).take(160))
        assertEquals(160 to 0, repository.importWhatsAppMigration(small.toString(Charsets.UTF_8), DateOrder.DAY_FIRST))
        val before = messages().associateBy { it.id }
        assertEquals(160, before.size)
        val collection = repository.getOrCreateRandomLinksCollection()
        repository.editCollection(collection.id, "Synthetic migration archive", "book", "#123456")
        assertEquals(40 to 0, repository.importWhatsAppMigration(full, DateOrder.DAY_FIRST))
        val after = messages().associateBy { it.id }
        assertEquals(200, after.size)
        assertEquals(before, after.filterKeys { it in before })
        assertEquals(sourceRecords(full).drop(160).map { it.key }.toSet(), after.keys - before.keys)
        assertEquals(collection.id, repository.getOrCreateRandomLinksCollection().id)
        assertEquals("Synthetic migration archive", repository.getOrCreateRandomLinksCollection().name)
        assertEquals(1, repository.dao.getAllCollections().count { it.appRole == CollectionEntity.ROLE_RANDOM_LINKS })
        assertTrue(after.keys.all { repository.dao.getCollectionIdsForItem(it) == listOf(collection.id) })
        assertEquals(202, repository.dao.getAllActiveItems().size) // Two distinct original export files.
    }

    @Test fun ambiguousDateStaysUnresolvedUntilChoiceAndChoiceDoesNotChangeIdentity() = runBlocking {
        val records = sourceRecords(fixtureText("Memora_QA_WhatsApp_200.txt"))
        val ambiguous = records.first { it.date.startsWith("03/04/2026") }
        val certain = records.first { it.date.startsWith("13/10/2026") }
        val chat = listOf(ambiguous, certain).joinToString("\n") { "${it.date.replace(" ", ", ")} - ${it.sender}: ${it.body}" }
        assertEquals(2 to 1, repository.importWhatsAppMigration(chat))
        assertNull(repository.dao.getItemById(ambiguous.key)!!.sourceDate)
        val october = timestamp(certain.date)
        assertEquals(october, repository.dao.getItemById(certain.key)!!.sourceDate)
        assertEquals(0 to 0, repository.importWhatsAppMigration(chat, DateOrder.DAY_FIRST))
        assertEquals(timestamp("03/04/2026 08:00"), repository.dao.getItemById(ambiguous.key)!!.sourceDate)
        assertEquals(0 to 0, repository.importWhatsAppMigration(chat, DateOrder.MONTH_FIRST))
        assertEquals(timestamp("04/03/2026 08:00"), repository.dao.getItemById(ambiguous.key)!!.sourceDate)
        assertEquals(october, repository.dao.getItemById(certain.key)!!.sourceDate)
        assertEquals(setOf(ambiguous.key, certain.key), messages().map { it.id }.toSet())
    }

    @Test fun providedPhotosReopenByteExactAndMissingAttachmentNeverBorrowsAnotherOriginal() = runBlocking {
        val chat = fixtureText("Media_Example/_chat.txt")
        val source = sourceRecords(chat)
        val names = listOf("IMG-20261017-WA0001.jpg", "IMG-20261017-WA0002.png", "GIF-20261017-WA0003.gif")
        val bytes = names.associateWith { fixtureBytes("Media_Example/$it") }
        assertEquals(8 to 0, repository.importWhatsAppMigration(chat, DateOrder.DAY_FIRST, bytes.mapValues { it.value.inputStream() }))
        assertEquals(8, messages().size)
        names.take(2).forEach { name ->
            val item = messages().single { it.originalFileName == name }
            assertEquals(hash(bytes.getValue(name)), item.contentHash)
            assertEquals(bytes.getValue(name).size.toLong(), item.fileSizeBytes)
            repeat(2) {
                // Fresh repository/file handle proves managed-original reopening, not external viewer rendering.
                val reloaded = LibraryRepository(isolatedContext, db)
                assertArrayEquals(bytes.getValue(name), checkNotNull(reloaded.getBlobFile(checkNotNull(item.contentHash))).inputStream().use { it.readBytes() })
            }
            assertEquals(1, repository.dao.getAssetByHash(checkNotNull(item.contentHash))!!.refCount)
        }
        val missing = messages().single { it.originalFileName == "IMG-20261017-WA9999.jpg" }
        assertNull(missing.contentHash)
        assertEquals(0L, missing.fileSizeBytes)
        assertEquals(source.last().provenance, missing.content)
        assertTrue("Missing attachment remains a searchable provenance record", repository.searchWithCollections("WA9999").any { it.item.id == missing.id })
        assertEquals(0 to 0, repository.importWhatsAppMigration(chat, DateOrder.DAY_FIRST, bytes.mapValues { it.value.inputStream() }))
        assertNull(repository.dao.getItemById(missing.id)!!.contentHash)
    }

    @Test fun parserRecognizesAllFourMediaReferencesIncludingProvidedGif() {
        val parsed = WhatsAppExportParser.parse(fixtureText("Media_Example/_chat.txt"), DateOrder.DAY_FIRST)
        assertEquals(8, parsed.totalMessages)
        assertEquals("GIF-20261017-WA0003.gif", parsed.messages.single { "GIF-20261017-WA0003.gif" in it.rawText }.attachmentFileName)
        assertEquals(4, parsed.mediaReferencesCount)
    }

    @Test fun providedGifIsAttachedAndReopensByteExact() = runBlocking {
        val name = "GIF-20261017-WA0003.gif"
        val bytes = fixtureBytes("Media_Example/$name")
        val chat = fixtureText("Media_Example/_chat.txt")
        repository.importWhatsAppMigration(chat, DateOrder.DAY_FIRST, mapOf(name to bytes.inputStream()))
        val record = sourceRecords(chat).single { name in it.body }
        val item = checkNotNull(repository.dao.getItemById(record.key))
        assertEquals(name, item.originalFileName)
        assertEquals(hash(bytes), item.contentHash)
        assertArrayEquals(bytes, checkNotNull(repository.getBlobFile(checkNotNull(item.contentHash))).readBytes())
    }

    @Test fun androidRuntimeResolvesAbsentAndGenericMediaMimeFamiliesAndManagedKinds() {
        val formats = listOf(
            Triple("jpg", "image/", ItemKind.PHOTO), Triple("png", "image/", ItemKind.PHOTO),
            Triple("gif", "image/", ItemKind.PHOTO), Triple("webp", "image/", ItemKind.PHOTO),
            Triple("mp4", "video/", ItemKind.VIDEO), Triple("mov", "video/", ItemKind.VIDEO),
            Triple("opus", "audio/", ItemKind.AUDIO), Triple("m4a", "audio/", ItemKind.AUDIO)
        )
        formats.forEach { (extension, family, kind) ->
            val name = "synthetic-runtime.$extension"
            listOf(null, "application/octet-stream").forEach { declared ->
                val bytes = "Synthetic runtime MIME mapping: $extension/$declared".toByteArray()
                assertEquals("Android classifier $name / $declared", kind, ContentClassifier.classify(declared, name))
                val stored = repository.assetStore.import(bytes.inputStream(), name, declared)
                assertTrue("Android managed MIME $name / $declared must be $family, was ${stored.mimeType}", stored.mimeType.startsWith(family))
                assertEquals("Android managed kind $name / $declared", kind, stored.kind)
                assertArrayEquals(bytes, checkNotNull(repository.assetStore.getBlobFile(stored.hash)).readBytes())
            }
        }
    }

    private suspend fun messages() = repository.dao.getAllActiveItems().filter { it.originalFileName != "WhatsApp export.txt" }
    private suspend fun exportItem() = repository.dao.getAllActiveItems().single { it.originalFileName == "WhatsApp export.txt" }
    private fun fixtureBytes(path: String) = InstrumentationRegistry.getInstrumentation().context.assets.open("qa/whatsapp/$path").use { it.readBytes() }
    private fun fixtureText(path: String) = fixtureBytes(path).toString(Charsets.UTF_8)
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun timestamp(date: String) = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).apply {
        timeZone = TimeZone.getDefault(); isLenient = false
    }.parse(date)!!.time

    private data class SourceRecord(val sender: String, val date: String, val body: String) {
        val key: String get() = MessageDigest.getInstance("SHA-256").digest("${sender}_${date}_$body".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val provenance: String get() = "$body\n\nWhatsApp provenance\nSender: $sender\nSource date text: $date\nImport timezone: ${TimeZone.getDefault().id}"
    }

    // Independent oracle for this validated, fixed DD/MM/YYYY fixture, not a replacement app parser.
    private fun sourceRecords(chat: String): List<SourceRecord> {
        val headers = Regex("(?m)^(\\d{2}/\\d{2}/\\d{4}), (\\d{2}:\\d{2}) - ([^:\\n]+): ").findAll(chat).toList()
        return headers.mapIndexed { index, match ->
            SourceRecord(match.groupValues[3], "${match.groupValues[1]} ${match.groupValues[2]}",
                chat.substring(match.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: chat.length).trimEnd())
        }
    }
}
