package com.personallibrary.core

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resumeWithException

/** Optional provider IPC cannot pin the capture mutex forever or create unbounded blocked threads. */
object ProviderIo {
    private val executor = ThreadPoolExecutor(0, 2, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { work -> Thread(work, "library-provider").apply { isDaemon = true } }, ThreadPoolExecutor.AbortPolicy())

    suspend fun open(cancel: () -> Unit = {}, timeoutMillis: Long = 30_000, open: () -> InputStream): InputStream =
        await(cancel, timeoutMillis, { runCatching { it.close() } }, open)

    suspend fun textMetadata(cancel: () -> Unit = {}, timeoutMillis: Long = 30_000, query: () -> String?): String? =
        await(cancel, timeoutMillis, {}, query)

    private suspend fun <T> await(cancel: () -> Unit, timeoutMillis: Long, discard: (T) -> Unit, operation: () -> T): T {
        try {
            return withTimeout(timeoutMillis) {
                suspendCancellableCoroutine { continuation ->
                    val task = AtomicReference<Future<*>?>()
                    continuation.invokeOnCancellation {
                        runCatching(cancel)
                        task.get()?.cancel(true)
                    }
                    try {
                        val future = executor.submit {
                            val result = try { operation() } catch (error: Exception) {
                                continuation.resumeWithException(error)
                                return@submit
                            }
                            continuation.resume(result, onCancellation = { _, value, _ -> discard(value) })
                        }
                        task.set(future)
                        if (!continuation.isActive) future.cancel(true)
                    } catch (_: RejectedExecutionException) {
                        continuation.resumeWithException(IOException("File provider is busy"))
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw timeout
            throw IOException("File provider did not respond in time", timeout)
        }
    }
}
