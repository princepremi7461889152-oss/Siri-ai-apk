package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import com.example.data.SiriRepository
import com.example.device.BootReceiver
import com.example.device.PermissionManager
import com.example.model.ReminderItem
import com.example.service.SiriVoiceService
import com.example.ui.screens.PrivacyScreen
import com.example.ui.theme.SiriAITheme
import com.example.viewmodel.SiriViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackgroundAndPermissionsRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("siri_ai_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        SiriVoiceService.isServiceRunning = false
        SiriVoiceService.isWakeStandbyActive = false
    }

    @After
    fun tearDown() {
        SiriVoiceService.isServiceRunning = false
        SiriVoiceService.isWakeStandbyActive = false
    }

    @Test
    fun testNoRepeatedPermissionPromptsAcrossAppLaunches() = runTest(UnconfinedTestDispatcher()) {
        val viewModel = SiriViewModel(app)
        val emittedPermissions = mutableListOf<String>()
        val collectJob = launch {
            viewModel.permissionRequestEvents.collect { emittedPermissions.add(it) }
        }

        // First launch: RECORD_AUDIO is not granted, so only RECORD_AUDIO is requested once
        viewModel.runFirstLaunchPermissionCheck()
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), emittedPermissions)
        assertTrue(viewModel.repository.isInitialPermissionSetupDone())

        // Simulated denial recorded
        viewModel.onPermissionResult(Manifest.permission.RECORD_AUDIO, granted = false)

        // Second & third launches: MUST NOT re-prompt automatically
        val secondVm = SiriViewModel(app)
        val secondJob = launch {
            secondVm.permissionRequestEvents.collect { emittedPermissions.add(it) }
        }
        secondVm.runFirstLaunchPermissionCheck()
        secondVm.runFirstLaunchPermissionCheck()
        assertEquals(1, emittedPermissions.size)

        // Requesting an ALREADY-GRANTED permission must NOT emit a prompt
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        secondVm.requestPermissionIfNeeded(Manifest.permission.RECORD_AUDIO)
        assertEquals(1, emittedPermissions.size)
        assertTrue(secondVm.permissionStatus.value.hasMicrophone)

        collectJob.cancel()
        secondJob.cancel()
    }

    @Test
    fun testLivePermissionRecheckReflectsGrantAndRevocation() {
        val viewModel = SiriViewModel(app)
        assertFalse(viewModel.permissionStatus.value.hasMicrophone)
        assertFalse(viewModel.permissionStatus.value.hasContacts)
        assertFalse(viewModel.permissionStatus.value.hasNotifications)

        // Grant permissions dynamically as if toggled in System Settings
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.POST_NOTIFICATIONS
        )
        viewModel.refreshPermissionStatus()

        assertTrue(viewModel.permissionStatus.value.hasMicrophone)
        assertTrue(viewModel.permissionStatus.value.hasContacts)
        assertTrue(viewModel.permissionStatus.value.hasNotifications)

        // Revoke RECORD_AUDIO dynamically — must never stay stuck on stale cached true
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        viewModel.refreshPermissionStatus()

        assertFalse(viewModel.permissionStatus.value.hasMicrophone)
        assertTrue(viewModel.permissionStatus.value.hasContacts)
    }

    @Test
    fun testServiceLifecyclePauseResumeScreenLockAndStickyRecovery() {
        // Without RECORD_AUDIO, start() returns false and does not throw
        assertFalse(SiriVoiceService.start(app, enableWakeStandby = false))

        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val controller = Robolectric.buildService(
            SiriVoiceService::class.java,
            Intent(app, SiriVoiceService::class.java).apply {
                action = SiriVoiceService.ACTION_START_WAKE_STANDBY
            }
        ).create().startCommand(0, 1)

        val service = controller.get()
        assertTrue(SiriVoiceService.isServiceRunning)
        assertTrue(SiriVoiceService.isWakeStandbyActive)
        assertTrue(SiriRepository(app).backgroundServiceEnabled.value)
        assertTrue(SiriRepository(app).backgroundWakeStandbyEnabled.value)

        // Verify ongoing notification has Listen, Hey Siri/Pause Wake, Command, and Stop actions
        val notifManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val activeNotif = shadowOf(notifManager).getNotification(SiriVoiceService.NOTIFICATION_ID)
        assertNotNull(activeNotif)
        val actionTitles = activeNotif.actions?.mapNotNull { it.title?.toString() }.orEmpty()
        assertTrue(actionTitles.contains("Listen"))
        assertTrue(actionTitles.contains("Stop"))
        assertTrue(actionTitles.contains("Command"))

        // Duplicate start in identical mode returns true smoothly
        assertTrue(SiriVoiceService.start(app, enableWakeStandby = true))

        // Pause for foreground UI -> keeps service alive, yields mic -> resume restores standby
        controller.withIntent(
            Intent(app, SiriVoiceService::class.java).apply { action = SiriVoiceService.ACTION_PAUSE_FOR_UI }
        ).startCommand(0, 2)
        assertTrue(SiriVoiceService.isServiceRunning)

        controller.withIntent(
            Intent(app, SiriVoiceService::class.java).apply { action = SiriVoiceService.ACTION_RESUME_AFTER_UI }
        ).startCommand(0, 3)
        assertTrue(SiriVoiceService.isServiceRunning)
        assertTrue(SiriVoiceService.isWakeStandbyActive)

        // Screen off & unlock broadcasts handled without crashing
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        Robolectric.flushForegroundThreadScheduler()
        assertTrue(SiriVoiceService.isServiceRunning)

        // START_STICKY process recreation (intent == null) restores when preference enabled
        controller.withIntent(null).startCommand(0, 4)
        assertTrue(SiriVoiceService.isServiceRunning)
        assertTrue(SiriVoiceService.isWakeStandbyActive)

        // Explicit stop clears preference and stops service
        controller.withIntent(
            Intent(app, SiriVoiceService::class.java).apply { action = SiriVoiceService.ACTION_STOP }
        ).startCommand(0, 5)
        assertFalse(SiriVoiceService.isServiceRunning)
        assertFalse(SiriVoiceService.isWakeStandbyActive)
        assertFalse(SiriRepository(app).backgroundServiceEnabled.value)

        controller.destroy()
    }

    @Test
    fun testBootReceiverReschedulesRemindersWithoutIllegalBackgroundMicFgsStart() {
        shadowOf(app).grantPermissions(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
        val repo = SiriRepository(app)
        repo.setBackgroundServiceEnabled(true)
        repo.addReminder(
            ReminderItem(
                title = "Morning Standup",
                triggerTimeMillis = System.currentTimeMillis() + 3_600_000L,
                isEnabled = true
            )
        )

        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))

        // Reminders rescheduled
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isNotEmpty())

        // Android 14+ prohibits microphone FGS from BOOT_COMPLETED — service must NOT start directly
        assertFalse(SiriVoiceService.isServiceRunning)

        // User-facing resume notification posted instead
        val notifManager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = shadowOf(notifManager).allNotifications
        assertTrue(posted.any { shadowOf(it).contentTitle?.toString()?.contains("Siri AI Assistant") == true })
    }

    @Test
    fun testPrivacyScreenPermissionDashboardDisplaysAccurateStatuses() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val viewModel = SiriViewModel(app)

        composeTestRule.setContent {
            SiriAITheme {
                PrivacyScreen(viewModel = viewModel)
            }
        }

        composeTestRule.onNodeWithTag("perm_status_microphone").assertTextEquals("Granted")
        composeTestRule.onNodeWithTag("perm_status_contacts").assertTextEquals("Not Granted")
        composeTestRule.onNodeWithTag("perm_status_notifications").assertTextEquals("Not Granted")
        composeTestRule.onNodeWithTag("perm_status_background_service").assertTextEquals("Inactive")
        composeTestRule.onNodeWithTag("perm_action_contacts").assertIsDisplayed()

        // Simulate granting contacts in Settings + returning to app
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)
        viewModel.refreshPermissionStatus()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("perm_status_contacts").assertTextEquals("Granted")
    }
}
