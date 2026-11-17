package com.example.device

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.ReminderItem
import com.example.model.ReminderRepeat

data class ReminderScheduleResult(
    val scheduled: Boolean,
    val isExact: Boolean,
    val needsExactAlarmPermission: Boolean,
    val needsNotificationPermission: Boolean
)

class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    fun canScheduleExactAlarms(): Boolean {
        val am = alarmManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                am.canScheduleExactAlarms()
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
    }

    fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun scheduleReminder(reminder: ReminderItem): ReminderScheduleResult {
        val am = alarmManager ?: return ReminderScheduleResult(
            scheduled = false,
            isExact = false,
            needsExactAlarmPermission = !canScheduleExactAlarms(),
            needsNotificationPermission = !hasNotificationPermission()
        )

        if (!reminder.isEnabled) {
            cancelReminder(reminder.id)
            return ReminderScheduleResult(
                scheduled = false,
                isExact = false,
                needsExactAlarmPermission = !canScheduleExactAlarms(),
                needsNotificationPermission = !hasNotificationPermission()
            )
        }

        val pendingIntent = buildPendingIntent(reminder)
        val triggerAt = reminder.triggerTimeMillis.coerceAtLeast(System.currentTimeMillis() + 5_000L)
        val canExact = canScheduleExactAlarms()
        val hasNotif = hasNotificationPermission()

        return try {
            if (canExact) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                ReminderScheduleResult(
                    scheduled = true,
                    isExact = true,
                    needsExactAlarmPermission = false,
                    needsNotificationPermission = !hasNotif
                )
            } else {
                // Graceful fallback when SCHEDULE_EXACT_ALARM is not granted on Android 12+
                if (reminder.repeat != ReminderRepeat.NONE && reminder.repeat.intervalMillis > 0L) {
                    am.setInexactRepeating(
                        AlarmManager.RTC_WAKEUP,
                        triggerAt,
                        reminder.repeat.intervalMillis,
                        pendingIntent
                    )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                } else {
                    am.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                }
                ReminderScheduleResult(
                    scheduled = true,
                    isExact = false,
                    needsExactAlarmPermission = true,
                    needsNotificationPermission = !hasNotif
                )
            }
        } catch (se: SecurityException) {
            Log.w("ReminderScheduler", "Exact alarm security exception; falling back to inexact alarm", se)
            try {
                am.set(AlarmManager.RTC_WAKEUP, triggerAt, pendingIntent)
                ReminderScheduleResult(
                    scheduled = true,
                    isExact = false,
                    needsExactAlarmPermission = true,
                    needsNotificationPermission = !hasNotif
                )
            } catch (_: Exception) {
                ReminderScheduleResult(
                    scheduled = false,
                    isExact = false,
                    needsExactAlarmPermission = true,
                    needsNotificationPermission = !hasNotif
                )
            }
        }
    }

    fun cancelReminder(reminderId: String) {
        val am = alarmManager ?: return
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.example.REMINDER_$reminderId"
        }
        val pi = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        pi.cancel()
    }

    fun openExactAlarmPermissionSettings(): Pair<Boolean, String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return Pair(true, "Exact alarms are automatically permitted on this Android version.")
        }
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            Pair(true, "Opening Exact Alarm permission settings.")
        } catch (e: Exception) {
            Pair(false, "Could not open Exact Alarm settings.")
        }
    }

    private fun buildPendingIntent(reminder: ReminderItem): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.example.REMINDER_${reminder.id}"
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminder.id)
            putExtra(ReminderReceiver.EXTRA_REMINDER_TITLE, reminder.title)
            putExtra(ReminderReceiver.EXTRA_REMINDER_REPEAT, reminder.repeat.name)
        }
        return PendingIntent.getBroadcast(
            context,
            reminder.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
