package com.example.qa

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.data.LibraryRepository
import com.example.data.LibraryRoomDatabase
import com.example.data.SearchDocumentEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real file-backed Room observers across the independent repositories used by UI/services/workers. */
@RunWith(AndroidJUnit4::class)
class RepositoryObservationTest {
    @Test fun existingLibraryObserverReceivesCaptureEditsIndexMetadataTrashAndRestore() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val ownedRoot = File.createTempFile("repository-observation-", "", app.cacheDir).apply {
            check(delete()); check(mkdir())
        }
        val isolatedContext = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(ownedRoot, "files").apply { mkdirs() }
            override fun getCacheDir() = File(ownedRoot, "cache").apply { mkdirs() }
            override fun getDatabasePath(name: String) = File(ownedRoot, "databases/$name").apply {
                parentFile!!.mkdirs()
                check(parentFile!!.isDirectory)
            }
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?) =
                super.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory)
            override fun openOrCreateDatabase(name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?) =
                super.openOrCreateDatabase(getDatabasePath(name).absolutePath, mode, factory, errorHandler)
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("${ownedRoot.name}-$name", mode)
        }
        // No injected/shared test DB: this must exercise the production builder in both constructors.
        val observing = LibraryRepository(isolatedContext)
        val writing = LibraryRepository(isolatedContext)
        val active = Channel<List<com.example.data.ItemWithCollections>>(Channel.UNLIMITED)
        val trash = Channel<List<com.example.data.ItemEntity>>(Channel.UNLIMITED)
        val activeObserver = launch(Dispatchers.IO) {
            observing.observeActiveItemsWithCollections().collect { active.send(it) }
        }
        val trashObserver = launch(Dispatchers.IO) {
            observing.observeTrashItems().collect { trash.send(it) }
        }
        try {
            // Open the writer before the baseline; later emissions must come from writes, not reopening.
            assertTrue(writing.dao.getAllActiveItems().isEmpty())
            assertTrue(active.awaitEmission("initial active library") { it.isEmpty() }.isEmpty())
            assertTrue(trash.awaitEmission("initial trash") { it.isEmpty() }.isEmpty())

            val captured = writing.captureStream(
                "Synthetic captured document".byteInputStream(), "captured.txt", "text/plain")
            val inserted = active.awaitEmission("capture insert") { rows -> rows.any { it.item.id == captured.id } }
            assertEquals("captured.txt", inserted.single().item.title)

            writing.updateItem(captured.copy(title = "Corrected title"))
            val edited = active.awaitEmission("item edit") { rows -> rows.singleOrNull()?.item?.title == "Corrected title" }
            assertEquals(captured.id, edited.single().item.id)

            val localDocument = SearchDocumentEntity("local-document", captured.id, textContent = "Indexed document text")
            assertTrue(writing.publishLocalIndex(captured.id, 1, "Indexed document text", "COMPLETE", listOf(localDocument)))
            val indexed = active.awaitEmission("worker local-index publication") { rows ->
                rows.singleOrNull()?.item?.let { it.extractedText == "Indexed document text" && it.localIndexVersion == 2 } == true
            }.single().item
            assertEquals("COMPLETE", indexed.localIndexCoverage)

            assertTrue(writing.publishAiResult(captured.id, 2, "Synthetic description", "synthetic concepts",
                "READY", listOf(localDocument), attempts = 1))
            val analyzed = active.awaitEmission("worker AI metadata publication") { rows ->
                rows.singleOrNull()?.item?.aiDescription == "Synthetic description"
            }.single().item
            assertEquals("synthetic concepts", analyzed.aiConcepts)
            assertEquals("READY", analyzed.aiStatus)

            writing.moveToTrash(captured.id)
            assertTrue(active.awaitEmission("removal from active library") { it.isEmpty() }.isEmpty())
            val trashed = trash.awaitEmission("addition to trash") { rows -> rows.any { it.id == captured.id } }.single()
            assertTrue(trashed.isTrash)
            assertEquals("Corrected title", trashed.title)

            writing.restoreFromTrash(captured.id)
            assertEquals(captured.id, active.awaitEmission("restored active item") { rows ->
                rows.any { it.item.id == captured.id }
            }.single().item.id)
            assertTrue(trash.awaitEmission("removal from trash") { it.isEmpty() }.isEmpty())
        } finally {
            activeObserver.cancelAndJoin()
            trashObserver.cancelAndJoin()
            // Test-only cleanup of the default-built DBs; never close another suite's injected DB.
            listOf(observing, writing).map { repository ->
                LibraryRepository::class.java.declaredFields.single { it.type == LibraryRoomDatabase::class.java }
                    .apply { isAccessible = true }.get(repository) as LibraryRoomDatabase
            }.distinct().forEach { it.close() }
            check(ownedRoot.deleteRecursively())
        }
    }

    private suspend fun <T> Channel<T>.awaitEmission(stage: String, predicate: (T) -> Boolean): T {
        val observed = withTimeoutOrNull(10_000) {
            var value = receive()
            while (!predicate(value)) value = receive()
            value
        }
        assertNotNull("Existing Room observer did not receive $stage within 10 seconds", observed)
        return checkNotNull(observed)
    }
}
