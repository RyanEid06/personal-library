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

    @Test fun replacingReminderWithPastTimeCancelsPreviouslyFutureAlarm() {
        val manager = org.robolectric.Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        assertTrue(scheduler.schedule("zone_shift", "Date only", System.currentTimeMillis() + 60_000L, false))
        assertEquals(1, manager.scheduledAlarms.size)
        assertFalse(scheduler.schedule("zone_shift", "Date only", System.currentTimeMillis() - 60_000L, false))
        assertTrue("Old-zone alarm must be removed even when new local due time is past", manager.scheduledAlarms.isEmpty())
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

    @Test fun collidingItemHashesKeepIndependentAlarmsAndCancellation() {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        val manager = org.robolectric.Shadows.shadowOf(context.getSystemService(AlarmManager::class.java))
        assertTrue(scheduler.schedule("Aa", "First", System.currentTimeMillis() + 60_000, false))
        assertTrue(scheduler.schedule("BB", "Second", System.currentTimeMillis() + 120_000, false))
        assertEquals(2, manager.scheduledAlarms.size)
        scheduler.cancel("Aa")
        assertEquals(1, manager.scheduledAlarms.size)
        val remaining = org.robolectric.Shadows.shadowOf(manager.scheduledAlarms.single().operation).savedIntent
        assertEquals("BB", remaining.getStringExtra(ReminderReceiver.EXTRA_ITEM_ID))
    }

    @Test fun replacingLegacyAlarmMigratesToOneItemSpecificAlarm() {
        val manager = context.getSystemService(AlarmManager::class.java)
        val legacy = android.app.PendingIntent.getBroadcast(context, "legacy".hashCode(),
            android.content.Intent(context, ReminderReceiver::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        manager.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 60_000, legacy)
        assertTrue(scheduler.schedule("legacy", "Migrated", System.currentTimeMillis() + 120_000, false))
        val alarms = org.robolectric.Shadows.shadowOf(manager).scheduledAlarms
        assertEquals(1, alarms.size)
        assertNotNull(org.robolectric.Shadows.shadowOf(alarms.single().operation).savedIntent.data)
    }

    @Test fun cancellationRemovesDeliveredItemAndLegacyNotifications() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(android.app.NotificationChannel("synthetic", "Synthetic", android.app.NotificationManager.IMPORTANCE_DEFAULT))
        val notification = android.app.Notification.Builder(context, "synthetic").setSmallIcon(android.R.drawable.ic_dialog_info).build()
        manager.notify("removed", 0, notification)
        manager.notify("removed".hashCode(), notification)
        manager.notify("kept", 0, notification)
        scheduler.cancel("removed")
        assertEquals(1, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)
        assertNotNull(org.robolectric.Shadows.shadowOf(manager).getNotification("kept", 0))
    }
}
