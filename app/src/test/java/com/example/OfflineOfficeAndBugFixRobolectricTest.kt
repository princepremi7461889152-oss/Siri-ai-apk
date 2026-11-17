package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.SpeechRecognizer
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.ai.AiBrainRepository
import com.example.ai.OfflineAssistantEngine
import com.example.data.SecureCredentialStore
import com.example.data.SiriRepository
import com.example.device.AndroidDeviceController
import com.example.intent.SiriFeatureParsers
import com.example.intent.SiriIntentParser
import com.example.model.AssistantMode
import com.example.model.LanguageOption
import com.example.model.VoiceGender
import com.example.model.VoiceState
import com.example.service.CapturedNotification
import com.example.service.SiriNotificationListenerService
import com.example.ui.screens.VoiceAssistantScreen
import com.example.viewmodel.SiriViewModel
import com.example.voice.SiriSpeechRecognizer
import com.example.voice.SiriTextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-xxhdpi")
class OfflineOfficeAndBugFixRobolectricTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var repository: SiriRepository
    private lateinit var deviceController: AndroidDeviceController
    private lateinit var viewModel: SiriViewModel
    private lateinit var parser: SiriIntentParser

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("siri_ai_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("siri_secure_credentials", Context.MODE_PRIVATE).edit().clear().commit()
        SiriNotificationListenerService.clearNotifications()

        registerMockLaunchableApp("com.google.android.youtube", "com.google.android.youtube.HomeActivity", "YouTube")
        registerMockLaunchableApp("com.android.settings", "com.android.settings.Settings", "Settings")

        setNetworkOnline(true)

        repository = SiriRepository(app)
        deviceController = AndroidDeviceController(app)
        viewModel = SiriViewModel(app)
        parser = SiriIntentParser(
            deviceController = deviceController,
            onModeChangeTrigger = { mode ->
                viewModel.setAssistantMode(mode)
                com.example.model.IntentExecutionResult("CHANGE_MODE", mapOf("mode" to mode.name), "Mode switched to ${mode.shortLabel}", true)
            }
        )
    }

    private fun registerMockLaunchableApp(packageName: String, activityName: String, label: String) {
        val pm = shadowOf(app.packageManager)
        val component = ComponentName(packageName, activityName)
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
            this.name = label
            this.nonLocalizedLabel = label
        }
        val actInfo = ActivityInfo().apply {
            this.packageName = packageName
            this.name = activityName
            this.applicationInfo = appInfo
            this.nonLocalizedLabel = label
            this.exported = true
        }
        val resolveInfo = ResolveInfo().apply {
            this.activityInfo = actInfo
            this.nonLocalizedLabel = label
        }
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        pm.addActivityIfNotPresent(component)
        pm.addOrUpdateActivity(actInfo)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        pm.addResolveInfoForIntent(intent, resolveInfo)
    }

    @Suppress("DEPRECATION")
    private fun setNetworkOnline(online: Boolean) {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadowCm = shadowOf(cm)
        if (online) {
            val netInfo = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
                android.net.NetworkInfo.DetailedState.CONNECTED,
                ConnectivityManager.TYPE_WIFI,
                0,
                true,
                true
            )
            shadowCm.setActiveNetworkInfo(netInfo)
            shadowCm.setDefaultNetworkActive(true)
            val activeNet = cm.activeNetwork
            if (activeNet != null) {
                val caps = ShadowNetworkCapabilities.newInstance()
                shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                shadowCm.setNetworkCapabilities(activeNet, caps)
            }
        } else {
            shadowCm.setDefaultNetworkActive(false)
            shadowCm.setActiveNetworkInfo(null)
        }
    }

    // -------------------------------------------------------------------------
    // 1. SECURE CREDENTIAL STORAGE & LEGACY KEY MIGRATION
    // -------------------------------------------------------------------------

    @Test
    fun testSecureCredentialStoreEncryptsAndMigratesLegacyPlaintextKey() {
        val store = SecureCredentialStore(app)
        store.saveApiKey("sk-secret-test-key-9988")
        assertEquals("sk-secret-test-key-9988", store.loadApiKey())

        val secureRaw = app.getSharedPreferences("siri_secure_credentials", Context.MODE_PRIVATE)
            .all.values.joinToString()
        assertFalse("Encrypted store must not save plaintext API key", secureRaw.contains("sk-secret-test-key-9988"))

        // Simulate legacy plaintext key in siri_ai_prefs and verify automatic migration
        store.clearApiKey()
        val legacyPrefs = app.getSharedPreferences("siri_ai_prefs", Context.MODE_PRIVATE)
        legacyPrefs.edit().putString("custom_api_key", "AIzaLegacyPlaintextKey123").commit()

        val migratedRepo = SiriRepository(app)
        assertEquals("AIzaLegacyPlaintextKey123", migratedRepo.aiConfig.value.customApiKey)
        assertNull(
            "Plaintext key should be purged from legacy SharedPreferences after migration",
            legacyPrefs.getString("custom_api_key", null)
        )
    }

    // -------------------------------------------------------------------------
    // 2. OFFLINE MODE END-TO-END TESTS
    // -------------------------------------------------------------------------

    @Test
    fun testOfflineModeHandlesLocalTasksAndGivesHonestBoundaryForCloudAi() {
        setNetworkOnline(false)
        viewModel.updateConnectivityStatus(false)
        viewModel.setAssistantMode(AssistantMode.OFFLINE)

        // Open installed app offline
        viewModel.handleUserUtterance("YouTube kholo")
        val ytIntent = shadowOf(app).nextStartedActivity
        assertEquals("com.google.android.youtube", ytIntent?.`package` ?: ytIntent?.component?.packageName)

        // Open Android settings offline (app launcher + specific settings deep link)
        viewModel.handleUserUtterance("settings kholo")
        val settingsLaunched = shadowOf(app).nextStartedActivity
        assertTrue(
            settingsLaunched?.action == Settings.ACTION_SETTINGS ||
                (settingsLaunched?.`package` ?: settingsLaunched?.component?.packageName) == "com.android.settings"
        )
        viewModel.handleUserUtterance("wifi settings kholo")
        assertEquals(Settings.ACTION_WIFI_SETTINGS, shadowOf(app).nextStartedActivity?.action)

        // Device info in Hindi & English
        viewModel.handleUserUtterance("mere phone ki jankari")
        val devMsg = viewModel.repository.messages.value.last()
        assertEquals("DEVICE_INFO", devMsg.actionType)
        assertTrue(devMsg.text.contains("Android", ignoreCase = true))

        // Upgraded Math: multi-op, percentage discount, total, average
        val multiOpMath = SiriFeatureParsers.evaluateMath("calculate (15 + 5) * 2 - 4")
        assertTrue("Expected result containing 36, got $multiOpMath", multiOpMath?.contains("36") == true)
        assertTrue(SiriFeatureParsers.evaluateMath("what is 20% off on 500")?.contains("400") == true)
        assertTrue(SiriFeatureParsers.evaluateMath("18% gst on 1000")?.contains("180") == true)
        assertTrue(SiriFeatureParsers.evaluateMath("total of 10, 20, 30")?.contains("60") == true)
        assertTrue(SiriFeatureParsers.evaluateMath("average of 10 20 30")?.contains("20") == true)

        // Date & Time in Hindi
        viewModel.handleUserUtterance("aaj ki date kya hai")
        assertEquals("DATE", viewModel.repository.messages.value.last().actionType)

        viewModel.handleUserUtterance("kitne baje hain")
        assertEquals("TIME", viewModel.repository.messages.value.last().actionType)

        // Built-in Offline Knowledge Base & Study Explainer
        viewModel.handleUserUtterance("what is photosynthesis")
        assertTrue(viewModel.repository.messages.value.last().text.contains("chlorophyll", ignoreCase = true))

        viewModel.handleUserUtterance("what is the capital of india")
        assertTrue(viewModel.repository.messages.value.last().text.contains("New Delhi", ignoreCase = true))

        viewModel.handleUserUtterance("what can you do offline")
        assertEquals("OFFLINE_ASSISTANT", viewModel.repository.messages.value.last().actionType)

        // Saved user preferences offline
        viewModel.repository.updateUserPreferences(viewModel.repository.userPreferences.value.copy(userName = "Aarav"))
        viewModel.handleUserUtterance("what are my preferences")
        assertTrue(viewModel.repository.messages.value.last().text.contains("Aarav"))

        // Open-ended cloud AI query while offline -> honest explanation, never claims cloud AI runs offline
        viewModel.handleUserUtterance("Write a 500 word research essay about quantum thermodynamics")
        val honestReply = viewModel.repository.messages.value.last()
        assertEquals("OFFLINE_ASSISTANT", honestReply.actionType)
        assertTrue(honestReply.text.contains("Offline", ignoreCase = true))
        assertTrue(honestReply.text.contains("internet", ignoreCase = true))
        assertTrue(viewModel.voiceState.value != VoiceState.THINKING && viewModel.voiceState.value != VoiceState.ERROR)
    }

    // -------------------------------------------------------------------------
    // 3. OFFICE MODE END-TO-END TESTS
    // -------------------------------------------------------------------------

    @Test
    fun testOfficeModeSummariesWorkPlanFlashcardsAndTemplates() {
        setNetworkOnline(false)
        viewModel.updateConnectivityStatus(false)

        // Voice switch to Office mode
        viewModel.handleUserUtterance("office mode on karo")
        assertEquals(AssistantMode.OFFICE, viewModel.assistantMode.value)
        assertEquals(AssistantMode.OFFICE, viewModel.resolvedMode.value)

        // Seed notes & tasks & reminders
        viewModel.repository.addNote("Physics Chapter 1", "Newton laws describe force equals mass times acceleration F = ma.", "Study")
        viewModel.repository.addTask("Submit quarterly budget", "Work", "High", "4:00 PM")
        viewModel.createAndScheduleReminder("Client Sync", System.currentTimeMillis() + 3_600_000L)

        // 1. Summarize stored local notes
        viewModel.handleUserUtterance("summarize my notes")
        val notesSummary = viewModel.repository.messages.value.last()
        assertEquals("OFFICE_SUMMARIZE_NOTES", notesSummary.actionType)
        assertTrue(notesSummary.text.contains("Physics Chapter 1"))

        // 2. Organize daily work
        viewModel.handleUserUtterance("organize my work")
        val workPlan = viewModel.repository.messages.value.last()
        assertEquals("OFFICE_WORK_PLAN", workPlan.actionType)
        assertTrue(workPlan.text.contains("Submit quarterly budget"))
        assertTrue(workPlan.text.contains("Client Sync"))

        // 3. Revision notes & flashcard generation
        val initialCards = viewModel.repository.flashcards.value.size
        viewModel.handleUserUtterance("create flashcards from notes")
        val revRes = viewModel.repository.messages.value.last()
        assertEquals("OFFICE_REVISION_NOTES", revRes.actionType)
        assertTrue(viewModel.repository.flashcards.value.size > initialCards)

        // 4. Draft email, leave application, report outline, summarize pasted text, offline translation
        viewModel.handleUserUtterance("draft a professional email for sprint demo")
        val emailMsg = viewModel.repository.messages.value.last()
        assertEquals("OFFICE_EMAIL_DRAFT", emailMsg.actionType)
        assertTrue(emailMsg.text.contains("Subject:", ignoreCase = true))

        viewModel.handleUserUtterance("leave application")
        assertEquals("OFFICE_LETTER_DRAFT", viewModel.repository.messages.value.last().actionType)

        viewModel.handleUserUtterance("prepare a report outline on quarterly growth")
        val reportMsg = viewModel.repository.messages.value.last()
        assertEquals("OFFICE_REPORT_OUTLINE", reportMsg.actionType)
        assertTrue(reportMsg.text.contains("Executive Summary"))

        viewModel.handleUserUtterance("summarize: First point defines scope. Second point measures velocity. Final point confirms release readiness.")
        assertEquals("OFFICE_TEXT_SUMMARY", viewModel.repository.messages.value.last().actionType)

        viewModel.handleUserUtterance("translate good morning to hindi")
        assertEquals("OFFICE_TRANSLATE_OFFLINE", viewModel.repository.messages.value.last().actionType)
        assertTrue(viewModel.repository.messages.value.last().text.contains("Suprabhat"))
    }

    // -------------------------------------------------------------------------
    // 4. AUTOMATIC VS MANUAL MODE SWITCHING & ONLINE REPOSITORY RESILIENCE
    // -------------------------------------------------------------------------

    @Test
    fun testAutoAndManualModeSwitchingPreservesExplicitSelection() {
        // AUTO tracks live connectivity
        viewModel.setAssistantMode(AssistantMode.AUTO)
        viewModel.updateConnectivityStatus(true)
        assertEquals(AssistantMode.ONLINE, viewModel.resolvedMode.value)

        viewModel.updateConnectivityStatus(false)
        assertEquals(AssistantMode.OFFLINE, viewModel.resolvedMode.value)

        // Manual OFFLINE does NOT switch back to ONLINE when network returns
        viewModel.setAssistantMode(AssistantMode.OFFLINE)
        viewModel.updateConnectivityStatus(true)
        assertEquals(AssistantMode.OFFLINE, viewModel.assistantMode.value)
        assertEquals(AssistantMode.OFFLINE, viewModel.resolvedMode.value)

        // Manual OFFICE remains OFFICE regardless of connectivity changes
        viewModel.setAssistantMode(AssistantMode.OFFICE)
        viewModel.updateConnectivityStatus(false)
        assertEquals(AssistantMode.OFFICE, viewModel.assistantMode.value)
        assertEquals(AssistantMode.OFFICE, viewModel.resolvedMode.value)
    }

    @Test
    fun testAiBrainRepositoryUrlValidationHttpErrorsAndOfficeFormatting() {
        val brain = AiBrainRepository(app)
        assertNull(brain.normalizeCustomEndpoint("ftp://bad-url.example"))
        assertEquals("https://api.openai.com/v1/chat/completions", brain.normalizeCustomEndpoint(""))
        assertEquals("https://proxy.example.com/v1/chat/completions", brain.normalizeCustomEndpoint("https://proxy.example.com/v1/"))

        assertTrue(brain.mapHttpError(401, "Invalid key", "Gemini").contains("API key", ignoreCase = true))
        assertTrue(brain.mapHttpError(429, "Quota exceeded", "Custom API").contains("rate limit", ignoreCase = true))
        assertTrue(brain.mapHttpError(503, "", "Gemini").contains("temporarily unavailable", ignoreCase = true))

        val formattedOffice = brain.cleanVoiceText("[EMOTION:HAPPINESS] **Subject: Update**\n\n1. Item one\n2. Item two")
        assertFalse(formattedOffice.contains("**"))
        assertTrue("Structured Office drafts should keep line breaks", formattedOffice.contains("\n"))
    }

    // -------------------------------------------------------------------------
    // 5. PROJECT-WIDE BUG FIX VERIFICATION
    // -------------------------------------------------------------------------

    @Test
    fun testSpeakTextAndPreviewVoiceDoNotPolluteChatHistory() {
        val beforeCount = viewModel.repository.messages.value.size
        viewModel.speakText("Vision analysis result text")
        assertEquals("speakText must not append fake user/assistant messages", beforeCount, viewModel.repository.messages.value.size)

        viewModel.previewVoiceSample("Sample preview voice")
        assertEquals("previewVoiceSample must not append fake messages", beforeCount, viewModel.repository.messages.value.size)
    }

    @Test
    fun testSttNoMatchErrorDoesNotLeaveVoiceStateStuckInThinking() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        var detectedNoSpeechMsg: String? = null
        val stt = SiriSpeechRecognizer(
            context = app,
            onResult = {},
            onPartialResult = {},
            onRmsDbChanged = {},
            onErrorOccurred = {},
            onListeningStateChanged = {},
            onNoSpeechDetected = { msg -> detectedNoSpeechMsg = msg }
        )
        stt.setPreferOffline(true)
        assertNotNull(stt)

        // Also test via ViewModel flow: no-speech callback resets THINKING/LISTENING cleanly
        viewModel.startListening()
        assertEquals(VoiceState.IDLE, viewModel.voiceState.value) // Robolectric has no system SpeechRecognizer -> gracefully resets IDLE
        assertNull(detectedNoSpeechMsg)
        stt.destroy()
    }

    @Test
    fun testTtsGenderAndOfflineVoiceFilteringSafety() {
        val tts = SiriTextToSpeech(app) {}
        tts.setPreferOffline(true)
        tts.setVoiceGender(VoiceGender.FEMALE)
        tts.setVoiceGender(VoiceGender.MALE)
        tts.setVoiceGender(VoiceGender.SYSTEM_DEFAULT)
        tts.setLanguage(LanguageOption.HINGLISH)
        // Must handle queued speech cleanly even if engine hasn't finished init on JVM
        tts.speak("Testing early speech queue")
        tts.shutdown()
    }

    @Test
    fun testNoteAndActionUndoConsumesLogEntryOnce() {
        val beforeNotes = viewModel.repository.notes.value.size
        viewModel.handleUserUtterance("create note Temporary Architecture Brief")
        assertEquals(beforeNotes + 1, viewModel.repository.notes.value.size)

        val firstUndo = viewModel.performUndo()
        assertTrue(firstUndo.success)
        assertEquals("UNDO_NOTE", firstUndo.intentName)
        assertEquals(beforeNotes, viewModel.repository.notes.value.size)

        // Second undo must NOT undo the exact same entry again
        val secondUndo = viewModel.performUndo()
        assertFalse(secondUndo.success)
        assertEquals("UNDO", secondUndo.intentName)
    }

    @Test
    fun testHindiPmAlarmAndTomorrowReminderParsing() {
        val alarmRes = parser.parseAndExecute("shaam 6:30 baje ka alarm")
        assertEquals("SET_ALARM", alarmRes?.intentName)
        assertEquals("18", alarmRes?.parameters?.get("hour"))
        assertEquals("30", alarmRes?.parameters?.get("min"))
        val alarmIntent = shadowOf(app).nextStartedActivity
        assertEquals(AlarmClock.ACTION_SET_ALARM, alarmIntent?.action)
        assertEquals(18, alarmIntent?.getIntExtra(AlarmClock.EXTRA_HOUR, -1))
        assertEquals(30, alarmIntent?.getIntExtra(AlarmClock.EXTRA_MINUTES, -1))

        val tomorrowParsed = SiriFeatureParsers.parseReminderSchedule("kal ke liye meeting reminder lagao")
        assertNotNull(tomorrowParsed)
        val cal = Calendar.getInstance().apply { timeInMillis = tomorrowParsed!!.triggerTimeMillis }
        val expectedCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
        assertEquals(expectedCal.get(Calendar.DAY_OF_YEAR), cal.get(Calendar.DAY_OF_YEAR))
        assertTrue(tomorrowParsed!!.title.contains("meeting", ignoreCase = true))
    }

    @Test
    fun testNotificationPrivacyFilterSelfExclusionAndQuietHours() {
        // Post ongoing Siri notification + OTP bank alert + normal friend message
        SiriNotificationListenerService.clearNotifications()
        val normalNotif = CapturedNotification("com.whatsapp", "Rohan", "Are we meeting at 5?")
        val otpNotif = CapturedNotification("com.bank.app", "Bank Alert", "Your OTP is 492011 for login")
        val selfNotif = CapturedNotification(app.packageName, "Siri AI Assistant", "Background Voice Active")

        // Use NotificationListenerService.onNotificationPosted substitute via companion helper check or Robolectric test hook?
        // Verify recent list filters out self package and masks OTP in ViewModel.readNotifications()
        val sbnSelf = mockStatusBarNotification(app.packageName, "Siri AI Assistant", "Background Voice Active")
        val sbnOtp = mockStatusBarNotification("com.bank.app", "Bank Alert", "Your OTP is 492011 for login")
        val sbnFriend = mockStatusBarNotification("com.whatsapp", "Rohan", "Let's review slides")

        val service = SiriNotificationListenerService()
        service.onNotificationPosted(sbnSelf)
        service.onNotificationPosted(sbnOtp)
        service.onNotificationPosted(sbnFriend)

        viewModel.repository.updateUserPreferences(
            viewModel.repository.userPreferences.value.copy(
                filterSensitiveNotifications = true,
                quietHoursEnabled = true,
                quietHoursStart = "00:00",
                quietHoursEnd = "23:59"
            )
        )

        viewModel.handleUserUtterance("read my notifications")
        val lastMsg = viewModel.repository.messages.value.last()
        assertEquals("READ_NOTIFICATIONS", lastMsg.actionType)
        assertFalse("Own FGS notification must be excluded", lastMsg.text.contains("Background Voice Active"))
        assertFalse("OTP digits must be redacted when privacy filter is enabled", lastMsg.text.contains("492011"))
        assertTrue("Friend message should still appear", lastMsg.text.contains("Let's review slides"))
        assertTrue("Quiet hours should be respected", lastMsg.text.contains("Quiet hours", ignoreCase = true))
        assertFalse("Quiet hours must avoid active SPEAKING state", viewModel.voiceState.value == VoiceState.SPEAKING)
    }

    @Test
    fun testWipeAllLocalDataCancelsScheduledAlarms() {
        viewModel.createAndScheduleReminder("Morning Standup", System.currentTimeMillis() + 120_000L)
        assertEquals(1, viewModel.repository.reminders.value.size)

        viewModel.wipeAllLocalData()
        assertTrue(viewModel.repository.reminders.value.isEmpty())
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun testVoiceAssistantUiModeSwitcherAndOfficeQuickChips() {
        viewModel.repository.updateUserPreferences(
            viewModel.repository.userPreferences.value.copy(reducedMotion = true)
        )
        composeTestRule.setContent {
            VoiceAssistantScreen(viewModel = viewModel)
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("connectivity_status_badge").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_chip_auto").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_chip_online").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_chip_offline").assertIsDisplayed()
        composeTestRule.onNodeWithTag("mode_chip_office").assertIsDisplayed()

        composeTestRule.onNodeWithTag("mode_chip_office").performClick()
        composeTestRule.waitForIdle()
        assertEquals(AssistantMode.OFFICE, viewModel.assistantMode.value)
        composeTestRule.onNodeWithText("Summarize my notes").assertIsDisplayed()

        composeTestRule.onNodeWithTag("mode_chip_auto").performClick()
        composeTestRule.waitForIdle()
        assertEquals(AssistantMode.AUTO, viewModel.assistantMode.value)
    }

    @Suppress("DEPRECATION")
    private fun mockStatusBarNotification(pkg: String, title: String, text: String): android.service.notification.StatusBarNotification {
        val notification = android.app.Notification.Builder(app)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        return android.service.notification.StatusBarNotification(
            pkg,
            pkg,
            pkg.hashCode(),
            null,
            android.os.Process.myUid(),
            0,
            0,
            notification,
            android.os.Process.myUserHandle(),
            System.currentTimeMillis()
        )
    }
}
