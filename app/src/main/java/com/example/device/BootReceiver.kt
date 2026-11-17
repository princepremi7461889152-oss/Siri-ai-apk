package com.example.device

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.data.SiriRepository
import com.example.service.SiriVoiceService

/**
 * Restores scheduled reminders on device reboot / app update and offers a compliant
 * one-tap prompt to resume the background voice service without violating Android 14+
 * BOOT_COMPLETED microphone foreground service restrictions.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val EXTRA_RESUME_BACKGROUND_SERVICE = "EXTRA_RESUME_BACKGROUND_SERVICE"
        private const val RESUME_NOTIFICATION_ID = 1009
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        val repo = SiriRepository(context.applicationContext)
        val scheduler = ReminderScheduler(context.applicationContext)

        // Restore all active future reminders
        val now = System.currentTimeMillis()
        repo.reminders.value
            .filter { it.isEnabled && it.triggerTimeMillis > now }
            .forEach { reminder ->
                try {
                    scheduler.scheduleReminder(reminder)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to reschedule reminder ${reminder.id} after boot", e)
                }
            }

        // Never start a microphone foreground service directly from BOOT_COMPLETED on Android 14+.
        // Instead, if the user previously opted into Background Voice Service, post a resume notification
        // (when notification permission is granted) so one tap launches the foreground assistant cleanly.
        if (repo.backgroundServiceEnabled.value &&
            PermissionManager.isGranted(context, Manifest.permission.RECORD_AUDIO) &&
            PermissionManager.isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        ) {
            postResumeAssistantNotification(context.applicationContext)
        }
    }

    private fun postResumeAssistantNotification(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SiriVoiceService.CHANNEL_ID,
                "Siri AI Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of Siri AI voice session and background command controls"
            }
            manager.createNotificationChannel(channel)
        }

        val resumeIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_RESUME_BACKGROUND_SERVICE, true)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            RESUME_NOTIFICATION_ID,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, SiriVoiceService.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Siri AI Assistant Ready")
            .setContentText("Tap to resume background voice assistant after reboot")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager.notify(RESUME_NOTIFICATION_ID, notification)
    }
}
