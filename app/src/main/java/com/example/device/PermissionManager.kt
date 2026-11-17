package com.example.device

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.service.SiriNotificationListenerService
import com.example.service.SiriVoiceService

/**
 * Live permission & service readiness snapshot.
 * Every field is derived from live Android system checks — never from cached booleans.
 */
data class PermissionStatusSnapshot(
    val hasMicrophone: Boolean = false,
    val hasNotifications: Boolean = true,
    val hasContacts: Boolean = false,
    val hasCamera: Boolean = false,
    val hasExactAlarms: Boolean = true,
    val hasNotificationListener: Boolean = false,
    val isBackgroundServiceActive: Boolean = false,
    val isIgnoringBatteryOptimizations: Boolean = false
)

object PermissionManager {

    fun isGranted(context: Context, permission: String): Boolean {
        if (permission == Manifest.permission.POST_NOTIFICATIONS &&
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
        ) {
            return NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        return ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun canScheduleExactAlarms(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return false
        return try {
            alarmManager.canScheduleExactAlarms()
        } catch (_: Exception) {
            false
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            pm.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: Exception) {
            false
        }
    }

    fun getSnapshot(context: Context): PermissionStatusSnapshot {
        return PermissionStatusSnapshot(
            hasMicrophone = isGranted(context, Manifest.permission.RECORD_AUDIO),
            hasNotifications = isGranted(context, Manifest.permission.POST_NOTIFICATIONS),
            hasContacts = isGranted(context, Manifest.permission.READ_CONTACTS),
            hasCamera = isGranted(context, Manifest.permission.CAMERA),
            hasExactAlarms = canScheduleExactAlarms(context),
            hasNotificationListener = SiriNotificationListenerService.isConnected,
            isBackgroundServiceActive = SiriVoiceService.isServiceRunning,
            isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations(context)
        )
    }

    fun shouldShowRationale(activity: Activity?, permission: String): Boolean {
        if (activity == null) return false
        return ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
    }

    fun getPermissionTitleAndReason(permission: String): Pair<String, String> {
        return when (permission) {
            Manifest.permission.RECORD_AUDIO ->
                "Microphone Access" to "Needed so Siri can hear your voice commands and keep background voice listening available when you enable it."
            Manifest.permission.POST_NOTIFICATIONS ->
                "Notifications Access" to "Needed on Android 13+ to show the ongoing background assistant notification, Stop control, and scheduled reminders."
            Manifest.permission.READ_CONTACTS ->
                "Contacts Access" to "Needed only when you ask Siri to call or message a saved contact by name."
            Manifest.permission.CAMERA ->
                "Camera Access" to "Needed only if you choose to capture photos inside the app. Standard photo picking and flashlight work without it."
            else ->
                "Permission Required" to "This feature needs system permission to operate properly."
        }
    }

    fun openAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    fun openNotificationSettings(context: Context) {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            openAppSettings(context)
        }
    }

    fun openExactAlarmSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        openAppSettings(context)
    }

    fun openBatteryOptimizationSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(intent)
                return
            } catch (_: Exception) {
            }
        }
        openAppSettings(context)
    }
}
