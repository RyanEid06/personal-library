package com.example.reminder

import android.app.AlarmManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderSchedulerTest {

    private lateinit var context: Context
    private lateinit var scheduler: ReminderScheduler

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        scheduler = ReminderScheduler(context)
        org.robolectric.Shadows.shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun testPastDueReminderRejected() {
        val past = System.currentTimeMillis() - 10_000L
        val scheduled = scheduler.schedule("item_past", "Past Due", past)
        assertFalse("Past-due reminders should not be scheduled", scheduled)
    }

    @Test
    fun testFutureReminderScheduledAndCancelled() {
        val future = System.currentTimeMillis() + 60_000L
        val scheduled = scheduler.schedule("item_future", "Future Study", future)
        assertTrue("Future reminder should be scheduled successfully", scheduled)

        // Cancel reminder
        scheduler.cancel("item_future")
        // No exception thrown on cancel
    }

    @Test fun deniedNotificationPermissionCannotReportDeliverableReminder() {
        org.robolectric.Shadows.shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(scheduler.schedule("denied", "Not deliverable", System.currentTimeMillis() + 60_000))
    }

    @Test @Config(sdk = [32]) fun android12DoesNotRequireAndroid13NotificationPermission() {
        org.robolectric.Shadows.shadowOf(context as android.app.Application).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(scheduler.schedule("legacy", "Deliverable", System.currentTimeMillis() + 60_000))
    }
}
