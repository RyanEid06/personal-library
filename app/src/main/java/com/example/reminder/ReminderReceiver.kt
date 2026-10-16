package com.example.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.LibraryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "personal_library_reminders"
        const val CHANNEL_NAME = "Memora Reminders"
        const val EXTRA_ITEM_ID = "extra_item_id"
        const val EXTRA_ITEM_TITLE = "extra_item_title"
        const val ACTION_COMPLETE = "com.example.reminder.ACTION_COMPLETE"
        const val ACTION_SNOOZE = "com.example.reminder.ACTION_SNOOZE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val itemId = intent.getStringExtra(EXTRA_ITEM_ID) ?: return
        val action = intent.action
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (action == ACTION_COMPLETE) {
            val pending = goAsync()
            val repository = LibraryRepository(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    repository.completeReminder(itemId)
                    notificationManager.cancel(itemId.hashCode())
                } finally {
                    pending.finish()
                }
            }
            return
        }

        if (action == ACTION_SNOOZE) {
            val pending = goAsync()
            val repository = LibraryRepository(context)
            val scheduler = ReminderScheduler(context)
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val snoozeUntil = System.currentTimeMillis() + (15 * 60 * 1000L) // 15 mins
                    repository.snoozeReminder(itemId, snoozeUntil)
                    scheduler.schedule(itemId, intent.getStringExtra(EXTRA_ITEM_TITLE) ?: "Reminder", snoozeUntil)
                    notificationManager.cancel(itemId.hashCode())
                } finally {
                    pending.finish()
                }
            }
            return
        }

        // Standard alarm trigger: build and show reminder notification
        if (!ReminderScheduler(context).notificationsEnabled()) return
        val itemTitle = intent.getStringExtra(EXTRA_ITEM_TITLE) ?: "Library Reminder"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for saved Memora items"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            itemId.hashCode(),
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Notification Privacy: check if App Lock is enabled
        val appLockPrefs = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
        val isLockEnabled = appLockPrefs.getBoolean("lock_enabled", false)

        val title = if (isLockEnabled) "Memora Reminder" else "Reminder: $itemTitle"
        val message = if (isLockEnabled) "You have a protected item reminder" else "Tap to view this saved item in Memora"

        // Snooze Intent (15 minutes)
        val snoozeIntent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = ACTION_SNOOZE
            putExtra(EXTRA_ITEM_ID, itemId)
            putExtra(EXTRA_ITEM_TITLE, itemTitle)
        }
        val snoozePendingIntent = PendingIntent.getBroadcast(
            context,
            itemId.hashCode() + 1,
            snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Complete Intent
        val completeIntent = Intent(context, ReminderReceiver::class.java).apply {
            this.action = ACTION_COMPLETE
            putExtra(EXTRA_ITEM_ID, itemId)
        }
        val completePendingIntent = PendingIntent.getBroadcast(
            context,
            itemId.hashCode() + 2,
            completeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_recent_history, "Snooze 15m", snoozePendingIntent)
            .addAction(android.R.drawable.checkbox_on_background, "Complete", completePendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        notificationManager.notify(itemId.hashCode(), notification)
    }
}
