package com.example.capture

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import com.example.model.SharePayload
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.example.PersonalLibraryApp::class)
class CaptureSafetyTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun androidProviderSizeIsOptionalAndActuallyReachesPreflight() = runBlocking {
        for (reportedSize in listOf(Long.MAX_VALUE, null, -1L)) {
            val authority = "synthetic-size-${java.util.UUID.randomUUID()}"
            val source = java.io.File.createTempFile("synthetic-provider-", ".txt", context.cacheDir).apply { writeText("Synthetic source") }
            var queriedSize = false
            val provider = object : android.content.ContentProvider() {
                override fun onCreate() = true
                override fun getType(uri: Uri) = "text/plain"
                override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): android.database.Cursor {
                    val columns = projection ?: arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)
                    return android.database.MatrixCursor(columns).apply {
                        addRow(columns.map<String, Any?> { column ->
                            when (column) {
                                android.provider.OpenableColumns.DISPLAY_NAME -> "synthetic-source.txt"
                                android.provider.OpenableColumns.SIZE -> { queriedSize = true; reportedSize }
                                else -> null
                            }
                        }.toTypedArray())
                    }
                }
                override fun openAssetFile(uri: Uri, mode: String) = android.content.res.AssetFileDescriptor(
                    android.os.ParcelFileDescriptor.open(source, android.os.ParcelFileDescriptor.MODE_READ_ONLY), 0,
                    android.content.res.AssetFileDescriptor.UNKNOWN_LENGTH)
                override fun insert(uri: Uri, values: android.content.ContentValues?) = throw UnsupportedOperationException()
                override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
                override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, selectionArgs: Array<out String>?) = throw UnsupportedOperationException()
            }
            provider.attachInfo(context, android.content.pm.ProviderInfo().apply { this.authority = authority })
            org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
            val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
            val repository = LibraryRepository(context, db)
            try {
                val result = CaptureBatchProcessor.android(context, repository).save(SharePayload(uris = listOf("content://$authority/source")), emptyList())
                assertTrue("Provider size must be queried", queriedSize)
                if (reportedSize == Long.MAX_VALUE) {
                    assertEquals(CaptureBatchResult(0, 1), result)
                    assertTrue(repository.dao.getAllAssets().isEmpty())
                    assertTrue(repository.dao.getAllItemsIncludingTrash().isEmpty())
                } else {
                    assertEquals(CaptureBatchResult(1, 0), result)
                    assertEquals("Synthetic source", repository.assetStore.getBlobFile(repository.dao.getAllActiveItems().single().contentHash!!)!!.readText())
                }
            } finally { db.close(); source.delete() }
        }
    }

    @Test fun knownOversizeLeavesNoItemAssetOrStagingAndDoesNotReadSource() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        val repository = LibraryRepository(context, db)
        var read = false
        try {
            val processor = CaptureBatchProcessor(repository) { _, _ ->
                IncomingAsset("synthetic-huge.bin", "application/octet-stream", knownSizeBytes = Long.MAX_VALUE) {
                    object : InputStream() { override fun read(): Int { read = true; return -1 } }
                }
            }
            assertEquals(CaptureBatchResult(0, 1), processor.save(SharePayload(uris = listOf("content://synthetic/oversize")), emptyList()))
            assertFalse(read)
            assertTrue(repository.dao.getAllItemsIncludingTrash().isEmpty())
            assertTrue(repository.dao.getAllAssets().isEmpty())
            assertTrue(repository.assetStore.root.resolve("staging").toFile().listFiles().orEmpty().isEmpty())
        } finally { db.close() }
    }

    @Test @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    fun captureKeepsAdmissionClosedUntilTeardownAndAllowsNextBatch() = runBlocking {
        val app = context as com.example.PersonalLibraryApp
        app.captureState.finish("Reset test state")
        val controller = org.robolectric.Robolectric.buildService(CaptureService::class.java).create()
        val service = controller.get()
        val repository = LibraryRepository(context)
        fun intent(batch: String, text: String) = Intent(Intent.ACTION_SEND_MULTIPLE)
            .putExtra(Intent.EXTRA_TEXT, text).putExtra("capture_batch_id", batch)
        suspend fun waitForNote(text: String) {
            withTimeout(10_000) {
                while (repository.dao.getAllActiveItems().none { it.content == text }) delay(10)
            }
        }
        suspend fun finishMainCleanup() {
            withTimeout(10_000) {
                while (app.captureState.state.value.saving) {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
        }
        try {
            val first = "First confirmed batch ${java.util.UUID.randomUUID()}"
            assertTrue(app.captureState.begin("first", 1))
            service.onStartCommand(intent("first", first), 0, 1)
            waitForNote(first)
            // The main looper has not performed foreground teardown yet.
            delay(100)
            assertFalse("A second batch cannot take admission before old service teardown", app.captureState.begin("too_early", 1))
            finishMainCleanup()
            val second = "Second confirmed batch ${java.util.UUID.randomUUID()}"
            assertTrue(app.captureState.begin("second", 1))
            service.onStartCommand(intent("second", second), 0, 2)
            waitForNote(second)
            finishMainCleanup()
            assertEquals(1, app.captureState.state.value.saved)
        } finally { controller.destroy() }
    }

    @Test fun mergesClipAndStreamUrisAndRejectsPrivateFileAndNetworkUris() {
        val first = Uri.parse("content://transient/first")
        val second = Uri.parse("content://transient/second")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM,
            arrayListOf(first, second, Uri.parse("file:///data/user/0/private"), Uri.parse("https://example.com/file")))
        intent.clipData = ClipData.newRawUri("first", first)
        val payload = SharePayloadDecoder.decode(intent)!!
        assertEquals(listOf(first.toString(), second.toString()), payload.uris)
        assertEquals(2, payload.rejectedCount)
        assertNull(payload.mimeType)
        assertNull(SharePayloadDecoder.decode(Intent(Intent.ACTION_MAIN)))
        val text = Intent(Intent.ACTION_SEND).apply { clipData = ClipData.newPlainText("note", "Grades 🎓") }
        assertEquals("Grades 🎓", SharePayloadDecoder.decode(text)!!.text)
    }

    @Test fun processRestartKeepsCommittedCountsButNeverReplaysTransientSources() {
        context.getSharedPreferences("capture_state", 0).edit().clear().commit()
        val state = CaptureStateStore(context)
        assertTrue(state.begin("synthetic", 3))
        assertFalse(state.begin("duplicate", 3))
        state.progress(1, 0)
        val restored = CaptureStateStore(context).state.value
        assertFalse(restored.saving)
        assertEquals(1, restored.saved)
        assertTrue(restored.message!!.contains("interrupted"))
        assertFalse(context.getSharedPreferences("capture_state", 0).all.values.any { it.toString().contains("content://") })
    }

    @Test fun staleBatchCallbacksCannotChangeNextConfirmedBatch() {
        val store = CaptureStateStore(context)
        assertTrue(store.begin("old", 1))
        store.finish("First complete", "old")
        assertTrue(store.begin("new", 2))
        store.progress(99, 99, "old")
        store.finish("Stale completion", "old")
        assertTrue(store.state.value.saving)
        assertEquals("new", store.state.value.batchId)
        assertEquals(0, store.state.value.saved)
    }

    @Test fun batchReportsPartialFailureOnlyAfterOriginalAndMetadataCommit() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        val repository = LibraryRepository(context, db)
        try {
            val processor = CaptureBatchProcessor(repository) { uri, _ ->
                IncomingAsset("transient.txt", "text/plain") {
                    if (uri.endsWith("lost")) throw java.io.FileNotFoundException("Grant expired")
                    "transient original".byteInputStream()
                }
            }
            var callbacks = 0
            val result = processor.save(SharePayload(uris = listOf("content://test/good", "content://test/lost")), emptyList()) { saved, _ ->
                callbacks++
                if (saved > 0) runBlocking {
                    val item = repository.dao.getAllActiveItems().single()
                    assertEquals("transient original", repository.assetStore.getBlobFile(item.contentHash!!)!!.readText())
                    assertEquals(1, repository.dao.getAssetByHash(item.contentHash)!!.refCount)
                }
            }
            assertEquals(CaptureBatchResult(1, 1), result)
            assertEquals(2, callbacks)
        } finally { db.close() }
    }

    @Test fun cancellationDuringTransientCopyLeavesNoCorruptItemOrPublishedBlob() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        val repository = LibraryRepository(context, db)
        val captureJob = Job()
        var closed = false
        try {
            val processor = CaptureBatchProcessor(repository) { _, _ -> IncomingAsset("cancel.txt", "text/plain") {
                object : InputStream() {
                    override fun read(): Int { captureJob.cancel(); return 65 }
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { buffer[offset] = 65; captureJob.cancel(); return 1 }
                    override fun close() { closed = true }
                }
            } }
            try { withContext(captureJob) { processor.save(SharePayload(uris = listOf("content://test/cancel")), emptyList()) }; fail("Expected cancellation") }
            catch (_: CancellationException) { }
            assertTrue(closed)
            assertTrue(repository.dao.getAllItemsIncludingTrash().isEmpty())
            assertTrue(repository.dao.getAllAssets().isEmpty())
        } finally { db.close() }
    }
}
