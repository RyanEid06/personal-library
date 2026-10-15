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
                    val now = System.currentTimeMillis()
                    for (rem in pendingReminders) {
                        if (rem.dueMillis > now) {
                            scheduler.schedule(rem.itemId, rem.title, rem.dueMillis, rem.isExact)
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
        }
    }
}
