package com.example.reminder

import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReminderLifecycleTest {
    private lateinit var context: Context
    private lateinit var database: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private val future get() = System.currentTimeMillis() + 120_000
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        database = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        repository = LibraryRepository(context, database)
    }
    @After fun close() = database.close()
    private suspend fun source(id: String, trash: Boolean = false, done: Boolean = false, due: Long = future): ItemEntity {
        val item = ItemEntity(id, "Synthetic $id", "", "TEXT", isTrash = trash, reminderTime = due, isReminderDone = done)
        repository.dao.insertItem(item)
        repository.dao.insertReminder(ReminderEntity("rem_$id", id, item.title, due, isDone = done, isExact = false))
        return item
    }
    private fun alarmCount() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms.size
    private fun intent(id: String, due: Long, action: String? = null) = Intent(context, ReminderReceiver::class.java).apply {
        this.action = action
        putExtra(ReminderReceiver.EXTRA_ITEM_ID, id)
        putExtra(ReminderReceiver.EXTRA_REMINDER_TIME, due)
    }
    @Test fun restoreSchedulesFuturePendingItemAndReplacesWithoutDuplicates() = runBlocking {
        val item = source("restored")
        val reminder = repository.dao.getReminderByItemId(item.id)
        val scheduler = ReminderScheduler(context)
        assertTrue(scheduler.scheduleRestored(item, reminder))
        assertTrue(scheduler.scheduleRestored(item, reminder))
        assertEquals(1, alarmCount())
    }
    @Test fun restoreRefusesTrashCompletedExpiredAndMissingReminders() = runBlocking {
        val scheduler = ReminderScheduler(context)
        for (item in listOf(source("trash", trash = true), source("done", done = true), source("past", due = System.currentTimeMillis() - 1))) {
            assertFalse(scheduler.scheduleRestored(item, repository.dao.getReminderByItemId(item.id)))
        }
        assertFalse(scheduler.scheduleRestored(source("missing"), null))
        assertEquals(0, alarmCount())
    }
    @Test fun bootSchedulesOnlyExistingActivePendingSources() = runBlocking {
        source("active")
        source("trash", trash = true)
        source("done", done = true)
        repository.dao.insertReminder(ReminderEntity("orphan", "missing", "Missing", future, isExact = false))
        BootReceiver().reschedulePending(context, repository)
        assertEquals(1, alarmCount())
    }
    @Test fun staleAlarmDoesNotNotifyTrashDeletedCompletedOrNewerReminder() = runBlocking {
        val receiver = ReminderReceiver()
        val trash = source("trash", trash = true)
        val done = source("done", done = true)
        val newer = source("newer")
        receiver.handleIntent(context, intent(trash.id, trash.reminderTime!!), repository)
        receiver.handleIntent(context, intent(done.id, done.reminderTime!!), repository)
        receiver.handleIntent(context, intent("missing", future), repository)
        receiver.handleIntent(context, intent(newer.id, newer.reminderTime!! - 60_000), repository)
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.size)
    }
    @Test fun staleSnoozeDoesNotReviveTrashOrReplaceNewerReminder() = runBlocking {
        val receiver = ReminderReceiver()
        val trash = source("trash", trash = true)
        val newer = source("newer")
        receiver.handleIntent(context, intent(trash.id, trash.reminderTime!!, ReminderReceiver.ACTION_SNOOZE), repository)
        receiver.handleIntent(context, intent(newer.id, newer.reminderTime!! - 60_000, ReminderReceiver.ACTION_SNOOZE), repository)
        assertEquals(trash.reminderTime, repository.dao.getReminderByItemId(trash.id)!!.dueMillis)
        assertEquals(newer.reminderTime, repository.dao.getReminderByItemId(newer.id)!!.dueMillis)
        assertEquals(0, alarmCount())
    }
    @Test fun collidingItemsHaveIndependentNotificationsAndActions() = runBlocking {
        val first = source("Aa", due = System.currentTimeMillis() - 100)
        val second = source("BB", due = System.currentTimeMillis() - 100)
        val receiver = ReminderReceiver()
        receiver.handleIntent(context, intent(first.id, first.reminderTime!!), repository)
        receiver.handleIntent(context, intent(second.id, second.reminderTime!!), repository)
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
        assertEquals(2, notifications.size)
        val ids = notifications.map { shadowOf(it.actions[0].actionIntent).savedIntent.getStringExtra(ReminderReceiver.EXTRA_ITEM_ID) }.toSet()
        assertEquals(setOf("Aa", "BB"), ids)
    }

    @Test fun validSnoozeSchedulesFutureReminderAndCompleteCancelsIt() = runBlocking {
        val item = source("action", due = System.currentTimeMillis() - 100)
        val receiver = ReminderReceiver()
        receiver.handleIntent(context, intent(item.id, item.reminderTime!!, ReminderReceiver.ACTION_SNOOZE), repository)
        val snoozed = repository.dao.getReminderByItemId(item.id)!!
        assertTrue(snoozed.dueMillis > System.currentTimeMillis())
        assertEquals(1, alarmCount())
        // Completion occurs from the notification when this replacement becomes due.
        val due = System.currentTimeMillis() - 100
        repository.dao.insertReminder(snoozed.copy(dueMillis = due))
        repository.dao.setReminder(item.id, due, false)
        receiver.handleIntent(context, intent(item.id, due, ReminderReceiver.ACTION_COMPLETE), repository)
        assertTrue(repository.dao.getReminderByItemId(item.id)!!.isDone)
        assertTrue(repository.dao.getItemById(item.id)!!.isReminderDone)
        assertEquals(0, alarmCount())
    }

    @Test fun legacyActionCannotSnoozeFutureReplacementAndMissingSourceCannotCreateState() = runBlocking {
        val item = source("legacy_action")
        val receiver = ReminderReceiver()
        receiver.handleIntent(context, Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_SNOOZE
            putExtra(ReminderReceiver.EXTRA_ITEM_ID, item.id)
        }, repository)
        receiver.handleIntent(context, intent("missing", future, ReminderReceiver.ACTION_SNOOZE), repository)
        assertEquals(item.reminderTime, repository.dao.getReminderByItemId(item.id)!!.dueMillis)
        assertNull(repository.dao.getReminderByItemId("missing"))
        assertEquals(0, alarmCount())
    }
}
