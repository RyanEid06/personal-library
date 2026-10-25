package com.example.capture

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.provider.OpenableColumns
import com.example.data.LibraryRepository
import com.example.model.SharePayload
import com.personallibrary.core.ProviderIo
import kotlinx.coroutines.*
import java.io.FilterInputStream
import java.io.InputStream

data class IncomingAsset(val name: String?, val mime: String?, val knownSizeBytes: Long? = null, val open: suspend () -> InputStream)
data class CaptureBatchResult(val saved: Int, val failed: Int)

class CaptureBatchProcessor(private val repository: LibraryRepository,
    private val resolve: suspend (String, String?) -> IncomingAsset) {
    suspend fun save(payload: SharePayload, collections: List<String>, explicitSticker: Boolean = false,
        favorite: Boolean = false, reminder: com.example.data.CaptureReminder? = null,
        onCommitted: (Int, Int) -> Unit = { _, _ -> }): CaptureBatchResult = coroutineScope {
        var saved = 0
        var failed = payload.rejectedCount
        for (uri in payload.uris.distinct()) {
            ensureActive()
            try {
                val asset = resolve(uri, payload.mimeType)
                val stream = asset.open()
                val job = currentCoroutineContext()[Job]!!
                val closer = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                    try { awaitCancellation() } finally { withContext(NonCancellable + Dispatchers.IO) { try { stream.close() } catch (_: Exception) { } } }
                }
                try {
                    val checked = object : FilterInputStream(stream) {
                        override fun read(): Int { job.ensureActive(); return super.read().also { job.ensureActive() } }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            job.ensureActive(); return stream.read(buffer, offset, length).also { job.ensureActive() }
                        }
                    }
                    checked.use { repository.captureStream(it, asset.name, asset.mime, explicitSticker, collections,
                        knownSizeBytes = asset.knownSizeBytes, favorite = favorite, reminder = reminder) }
                    saved++
                } finally { withContext(NonCancellable) { closer.cancelAndJoin() } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++ }
            onCommitted(saved, failed)
        }
        payload.text?.takeIf { it.isNotBlank() }?.let { text ->
            ensureActive()
            try {
                if (text.startsWith("https://") || text.startsWith("http://")) repository.saveLink(text, "", "", collections, favorite, reminder)
                else repository.createNote("", text, collections, favorite, reminder)
                saved++
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++ }
            onCommitted(saved, failed)
        }
        CaptureBatchResult(saved, failed)
    }

    companion object {
        fun android(context: Context, repository: LibraryRepository) = CaptureBatchProcessor(repository) { raw, senderMime ->
            val uri = Uri.parse(raw)
            require(uri.scheme.equals("content", true)) { "Only granted content URIs can be imported" }
            val resolver = context.contentResolver
            val querySignal = CancellationSignal()
            val name = try {
                ProviderIo.textMetadata(cancel = querySignal::cancel) {
                    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null, querySignal)?.use {
                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index >= 0 && it.moveToFirst()) it.getString(index) else null
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val sizeSignal = CancellationSignal()
            val size = try {
                ProviderIo.textMetadata(cancel = sizeSignal::cancel) {
                    resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null, sizeSignal)?.use {
                        val index = it.getColumnIndex(OpenableColumns.SIZE)
                        if (index >= 0 && it.moveToFirst() && !it.isNull(index)) it.getLong(index).takeIf { bytes -> bytes >= 0 }?.toString() else null
                    }
                }?.toLongOrNull()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            val mime = try { ProviderIo.textMetadata { resolver.getType(uri) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            val openSignal = CancellationSignal()
            IncomingAsset(name, mime ?: senderMime?.takeUnless { it == "*/*" }, knownSizeBytes = size) {
                ProviderIo.open(cancel = openSignal::cancel) {
                    val descriptor = resolver.openAssetFileDescriptor(uri, "r", openSignal) ?: error("Source no longer available")
                    try { descriptor.createInputStream() } catch (error: Exception) { descriptor.close(); throw error }
                }
            }
        }
    }
}
