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
            val scheduler = ReminderScheduler(context)

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val pendingReminders = repository.dao.getPendingReminders()
                    for (rem in pendingReminders) {
                        val due = if (rem.isDateOnly && rem.localDate != null) {
                            try { com.personallibrary.core.ReminderDate.atNine(rem.localDate) } catch (_: java.time.DateTimeException) { continue }
                        } else rem.dueMillis
                        if (due != rem.dueMillis) {
                            repository.dao.insertReminder(rem.copy(dueMillis = due))
                            repository.dao.setReminder(rem.itemId, due, rem.isExact, rem.isDateOnly, rem.localDate)
                        }
                        // Newly past-due local dates remain visible as pending; do not deliver an old-zone alarm.
                        scheduler.schedule(rem.itemId, rem.title, due, rem.isExact)
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
