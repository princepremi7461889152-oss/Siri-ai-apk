package com.example.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.data.SiriRepository
import com.example.model.ReminderRepeat

class ReminderReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "siri_reminders_channel"
        const val EXTRA_REMINDER_ID = "extra_reminder_id"
        const val EXTRA_REMINDER_TITLE = "extra_reminder_title"
        const val EXTRA_REMINDER_REPEAT = "extra_reminder_repeat"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val reminderId = intent.getStringExtra(EXTRA_REMINDER_ID) ?: return
        val title = intent.getStringExtra(EXTRA_REMINDER_TITLE) ?: "Siri Reminder"
        val repeatName = intent.getStringExtra(EXTRA_REMINDER_REPEAT) ?: ReminderRepeat.NONE.name
        val repeat = try {
            ReminderRepeat.valueOf(repeatName)
        } catch (_: Exception) {
            ReminderRepeat.NONE
        }

        postReminderNotification(context, reminderId, title, repeat)

        // Reschedule recurring reminders cleanly
        if (repeat != ReminderRepeat.NONE && repeat.intervalMillis > 0L) {
            try {
                val repo = SiriRepository(context.applicationContext)
                val nextTrigger = System.currentTimeMillis() + repeat.intervalMillis
                val updated = repo.updateReminder(
                    id = reminderId,
                    newTriggerTimeMillis = nextTrigger
                )
                if (updated != null && updated.isEnabled) {
                    ReminderScheduler(context.applicationContext).scheduleReminder(updated)
                }
            } catch (e: Exception) {
                Log.e("ReminderReceiver", "Failed to reschedule recurring reminder", e)
            }
        }
    }

    private fun postReminderNotification(
        context: Context,
        reminderId: String,
        title: String,
        repeat: ReminderRepeat
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                Log.w("ReminderReceiver", "POST_NOTIFICATIONS permission not granted; skipping reminder notification")
                return
            }
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Siri Alarms & Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Scheduled and recurring reminders created with Siri"
            }
            manager.createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            reminderId.hashCode(),
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val subtitle = if (repeat != ReminderRepeat.NONE) {
            "Recurring (${repeat.label}) reminder"
        } else {
            "Siri Voice Reminder"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        manager.notify(reminderId.hashCode(), notification)
    }
}
