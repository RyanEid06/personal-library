package com.example.data

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.reminder.ReminderScheduler
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
class RepositoryTrashSafetyTest {
    private lateinit var context: Context
    private lateinit var database: LibraryRoomDatabase
    private lateinit var repository: LibraryRepository
    private lateinit var scheduler: ReminderScheduler

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as android.app.Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        database = Room.inMemoryDatabaseBuilder(context, LibraryRoomDatabase::class.java).addCallback(DATABASE_CALLBACK).build()
        repository = LibraryRepository(context, database)
        scheduler = ReminderScheduler(context)
    }
    @After fun close() = database.close()
    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms
    private suspend fun item(id: String, due: Long = System.currentTimeMillis() + 120_000, done: Boolean = false): ItemEntity {
        val item = ItemEntity(id, "Synthetic $id", "Synthetic body", "TEXT", reminderTime = due, isReminderDone = done)
        repository.dao.insertItem(item)
        repository.dao.insertReminder(ReminderEntity("rem_$id", id, item.title, due, isDone = done, isExact = false))
        return item
    }

    @Test fun singleTrashCancelsAlarmAndRestoreReschedulesItOnce() = runBlocking {
        val saved = item("single")
        scheduler.scheduleRestored(saved, repository.dao.getReminderByItemId(saved.id))
        assertEquals(1, alarms().size)
        repository.moveToTrash(saved.id)
        assertTrue(repository.dao.getItemById(saved.id)!!.isTrash)
        assertEquals(0, alarms().size)
        repository.restoreFromTrash(saved.id)
        assertFalse(repository.dao.getItemById(saved.id)!!.isTrash)
        assertEquals(1, alarms().size)
        repository.restoreFromTrash(saved.id)
        assertEquals(1, alarms().size)
    }

    @Test fun batchUndoRestoresOnlyChangedItemsAndPreservesAllMemberships() = runBlocking {
        val first = item("first")
        val second = item("second")
        val alreadyTrashed = item("already_trash")
        repository.moveToTrash(alreadyTrashed.id)
        repository.dao.insertCollection(CollectionEntity("work", "Work"))
        repository.dao.insertCollection(CollectionEntity("personal", "Personal"))
        repository.setItemCollections(first.id, listOf("work", "personal"))
        repository.setItemCollections(second.id, listOf("personal"))
        scheduler.scheduleRestored(first, repository.dao.getReminderByItemId(first.id))
        scheduler.scheduleRestored(second, repository.dao.getReminderByItemId(second.id))

        val changed = repository.moveToTrash(listOf(first.id, second.id, first.id, alreadyTrashed.id, "missing"))
        assertEquals(setOf(first.id, second.id), changed.toSet())
        assertEquals(2, changed.size)
        assertEquals(0, alarms().size)
        val restored = repository.restoreFromTrash(changed)
        assertEquals(changed.toSet(), restored.toSet())
        assertEquals(2, alarms().size)
        assertTrue(repository.dao.getItemById(alreadyTrashed.id)!!.isTrash)
        assertEquals(setOf("work", "personal"), repository.dao.getCollectionIdsForItem(first.id).toSet())
        assertEquals(listOf("personal"), repository.dao.getCollectionIdsForItem(second.id))
        assertTrue(repository.restoreFromTrash(changed).isEmpty())
        assertEquals(2, alarms().size)
    }

    @Test fun batchRestoreKeepsCompletedAndPastRemindersWithoutSchedulingThem() = runBlocking {
        val pending = item("pending")
        val completed = item("completed", done = true)
        val expired = item("expired", due = System.currentTimeMillis() - 60_000)
        val ids = listOf(pending.id, completed.id, expired.id)
        repository.moveToTrash(ids)
        assertEquals(ids.toSet(), repository.restoreFromTrash(ids).toSet())
        assertEquals(1, alarms().size)
        assertTrue(repository.dao.getItemById(completed.id)!!.isReminderDone)
        assertTrue(repository.dao.getReminderByItemId(completed.id)!!.isDone)
        assertEquals(expired.reminderTime, repository.dao.getItemById(expired.id)!!.reminderTime)
    }

    @Test fun permanentDeletionCancelsAlarmAndDeletesReminderState() = runBlocking {
        val saved = item("delete")
        scheduler.scheduleRestored(saved, repository.dao.getReminderByItemId(saved.id))
        assertEquals(1, alarms().size)
        repository.deletePermanently(saved.id)
        assertEquals(0, alarms().size)
        assertNull(repository.dao.getReminderByItemId(saved.id))
        assertNull(repository.dao.getItemById(saved.id))
    }

    @Test fun stalePermanentDeletionConfirmationCannotDeleteRestoredItem() = runBlocking {
        val saved = item("restored_before_confirmation")
        repository.moveToTrash(saved.id)
        repository.restoreFromTrash(saved.id)
        repository.deletePermanently(saved.id, requireTrash = true)
        assertNotNull(repository.dao.getItemById(saved.id))
        assertEquals(1, alarms().size)
    }

    @Test fun restoredDateOnlyReminderUsesCurrentTimezoneAndStoredDeliveryTime() = runBlocking {
        val previousZone = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
            val date = java.time.LocalDate.now().plusDays(2).toString()
            val originalDue = com.personallibrary.core.ReminderDate.atNine(date)
            val saved = item("date_only", due = originalDue)
            repository.dao.updateItem(saved.copy(isReminderDateOnly = true, reminderLocalDate = date))
            repository.dao.insertReminder(repository.dao.getReminderByItemId(saved.id)!!.copy(isDateOnly = true, localDate = date))
            repository.moveToTrash(saved.id)
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+04:00"))
            val expectedDue = com.personallibrary.core.ReminderDate.atNine(date)
            repository.restoreFromTrash(saved.id)
            assertEquals(expectedDue, repository.dao.getReminderByItemId(saved.id)!!.dueMillis)
            assertEquals(expectedDue, repository.dao.getItemById(saved.id)!!.reminderTime)
            assertEquals(1, alarms().size)
            assertEquals(expectedDue, alarms().single().triggerAtTime)
        } finally {
            java.util.TimeZone.setDefault(previousZone)
        }
    }
}
