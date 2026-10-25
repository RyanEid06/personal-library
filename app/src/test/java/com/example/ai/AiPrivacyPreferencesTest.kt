package com.example.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiPrivacyPreferencesTest {
    @Test fun migrationAndOwnerExclusionChangesCannotLoseEitherAddition() =
        verifySerializedMutation("excluded_items")

    @Test fun itemAndCollectionMutationsUseTheSamePrivacyLock() =
        verifySerializedMutation("excluded_collections")

    private fun verifySerializedMutation(secondKey: String) {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("privacy-concurrency-test", 0)
        prefs.edit().clear().putStringSet("excluded_items", setOf("existing")).commit()
        val firstRead = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondEntered = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        fun checkedThread(action: () -> Unit) = Thread {
            try { action() } catch (error: Throwable) { failure.compareAndSet(null, error) }
        }
        val migration = checkedThread {
            assertTrue(AiPrivacyPreferences.mutate(prefs, "excluded_items") { values ->
                firstRead.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                values.add("transferred")
            })
        }
        val owner = checkedThread {
            assertTrue(AiPrivacyPreferences.mutate(prefs, secondKey) { values ->
                secondEntered.countDown()
                values.add("owner-change")
            })
        }
        try {
            migration.start()
            assertTrue(firstRead.await(5, TimeUnit.SECONDS))
            owner.start()
            // Wait for an observable outcome: the monitor blocked the second writer, or
            // its transform ran. A negative timeout alone would not prove serialization.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (owner.state != Thread.State.BLOCKED && secondEntered.count != 0L &&
                owner.isAlive && System.nanoTime() < deadline) Thread.yield()
            assertEquals("Competing privacy mutations must share one monitor", Thread.State.BLOCKED, owner.state)
            assertEquals("Second transform must wait until the first set is committed", 1L, secondEntered.count)
        } finally {
            releaseFirst.countDown()
            migration.join(5000)
            if (owner.state != Thread.State.NEW) owner.join(5000)
        }
        failure.get()?.let { throw AssertionError("Privacy writer failed", it) }
        assertFalse(migration.isAlive)
        assertFalse(owner.isAlive)
        assertEquals(setOf("existing", "transferred") + if (secondKey == "excluded_items") setOf("owner-change") else emptySet(),
            prefs.getStringSet("excluded_items", emptySet()))
        if (secondKey == "excluded_collections") assertEquals(setOf("owner-change"), prefs.getStringSet(secondKey, emptySet()))
        prefs.edit().clear().commit()
    }
}
