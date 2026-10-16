package com.personallibrary.core

import com.personallibrary.core.storage.ManagedAssetStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CaptureRecoveryTest {
    @Rule @JvmField val temp = TemporaryFolder()

    @Test fun startupReconciliationRetainsCommittedOriginalsAndRemovesInterruptedPublications() {
        val store = ManagedAssetStore(temp.root.toPath())
        val saved = store.import("committed".byteInputStream(), "saved.txt", "text/plain")
        val orphan = store.import("uncommitted".byteInputStream(), "orphan.txt", "text/plain")
        val stage = temp.root.resolve("staging/stage_interrupted").apply { writeText("partial bytes") }
        val unrelated = temp.root.resolve("blobs/owner-note.txt").apply { writeText("preserve unknown data") }
        store.reconcile(setOf(saved.hash))
        assertEquals("committed", store.getBlobFile(saved.hash)!!.readText())
        assertNull(store.getBlobFile(orphan.hash))
        assertFalse(stage.exists())
        assertTrue(unrelated.exists())
    }

    @Test fun providerTimeoutDiscardsAndClosesAStreamThatArrivesLate() = runBlocking {
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        try {
            try {
                ProviderIo.open(timeoutMillis = 50) {
                    while (true) {
                        try { release.await(); break } catch (_: InterruptedException) { }
                    }
                    object : ByteArrayInputStream(byteArrayOf(1)) { override fun close() { closed.countDown(); super.close() } }
                }
                fail("Expected timeout")
            } catch (_: IOException) { }
        } finally { release.countDown() }
        assertTrue("Late stream must be closed", closed.await(2, TimeUnit.SECONDS))
    }
}
