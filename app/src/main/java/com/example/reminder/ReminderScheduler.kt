package com.example.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import com.example.data.ItemEntity
import com.example.data.ReminderEntity

class ReminderScheduler(private val context: Context) {

    companion object {
        internal fun itemUri(itemId: String): Uri = Uri.Builder().scheme("memora").authority("reminder").appendPath(itemId).build()

        internal fun isActiveSource(item: ItemEntity?, reminder: ReminderEntity?): Boolean =
            item != null && reminder != null && reminder.itemId == item.id && !item.isTrash &&
                !item.isReminderDone && !reminder.isDone && item.reminderTime != null
    }

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun notificationsEnabled(): Boolean =
        (Build.VERSION.SDK_INT < 33 || androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) &&
            androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun canUseExactAlarms(): Boolean = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()

    /** Shared by Trash restoration, portable restore and boot/time-zone reconciliation. */
    fun scheduleRestored(item: ItemEntity, reminder: ReminderEntity?): Boolean {
        if (!isActiveSource(item, reminder)) {
            cancel(item.id)
            return false
        }
        val current = requireNotNull(reminder)
        // Callers persist date-only timezone resolution before scheduling, so delivery can verify
        // this exact stored generation instead of disagreeing with an in-memory recalculation.
        return schedule(item.id, item.title, current.dueMillis, current.isExact && !current.isDateOnly)
    }

    fun schedule(itemId: String, title: String, triggerAtMillis: Long, isExact: Boolean = true): Boolean {
        // Replacing a reminder also removes its old alarm when the new time is past or blocked.
        cancel(itemId)
        if (triggerAtMillis <= System.currentTimeMillis()) return false
        if (!notificationsEnabled()) return false

        val intent = Intent(context, ReminderReceiver::class.java).apply {
            data = itemUri(itemId)
            putExtra(ReminderReceiver.EXTRA_ITEM_ID, itemId)
            putExtra(ReminderReceiver.EXTRA_ITEM_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_REMINDER_TIME, triggerAtMillis)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            itemId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (isExact) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    } else {
                        alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun cancel(itemId: String) {
        val notifications = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notifications.cancel(itemId, 0)
        notifications.cancel(itemId.hashCode())
        // Also retire pre-1.0.1 alarms, whose identity had no item-specific data URI.
        for (uri in listOf(itemUri(itemId), null)) {
            val intent = Intent(context, ReminderReceiver::class.java).apply { data = uri }
            val pendingIntent = PendingIntent.getBroadcast(context, itemId.hashCode(), intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
            if (pendingIntent != null) {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
            }
        }
    }
}
