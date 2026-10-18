package com.example.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_TIME_CHANGED) {
            val pending = goAsync()
            val repository = LibraryRepository(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    reschedulePending(context, repository)
                } finally {
                    pending.finish()
                }
            }
        }
    }

    internal suspend fun reschedulePending(context: Context, repository: LibraryRepository) {
        val scheduler = ReminderScheduler(context)
        for (rem in repository.dao.getPendingReminders()) {
            val item = repository.dao.getItemById(rem.itemId)
            if (!ReminderScheduler.isActiveSource(item, rem)) {
                scheduler.cancel(rem.itemId)
                continue
            }
            val due = if (rem.isDateOnly) {
                try { com.personallibrary.core.ReminderDate.atNine(requireNotNull(rem.localDate)) }
                catch (_: IllegalArgumentException) { scheduler.cancel(rem.itemId); continue }
                catch (_: java.time.DateTimeException) { scheduler.cancel(rem.itemId); continue }
            } else rem.dueMillis
            if (due != rem.dueMillis) {
                repository.dao.insertReminder(rem.copy(dueMillis = due))
                repository.dao.setReminder(rem.itemId, due, rem.isExact, rem.isDateOnly, rem.localDate)
            }
            // Newly past-due local dates stay pending, but their old-zone alarm is cancelled.
            scheduler.scheduleRestored(requireNotNull(item), rem.copy(dueMillis = due))
        }
    }
}
