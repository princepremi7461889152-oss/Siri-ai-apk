package com.example

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.example.data.SiriRepository
import com.example.device.AndroidDeviceController
import com.example.device.ReminderScheduler
import com.example.intent.SiriIntentParser
import com.example.model.ReminderItem
import com.example.model.ReminderRepeat
import com.example.service.SiriVoiceService
import com.example.viewmodel.SiriViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureUpgradeRobolectricTest {

    private lateinit var app: Application
    private lateinit var repository: SiriRepository
    private lateinit var deviceController: AndroidDeviceController
    private lateinit var viewModel: SiriViewModel
    private lateinit var parser: SiriIntentParser

    @Before
    fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences("siri_ai_prefs", Context.MODE_PRIVATE).edit().clear().commit()

        registerMockLaunchableApp("com.google.android.youtube", "com.google.android.youtube.HomeActivity", "YouTube")
        registerMockLaunchableApp("com.google.android.apps.maps", "com.google.android.maps.MapsActivity", "Maps")
        registerMockLaunchableApp("com.android.settings", "com.android.settings.Settings", "Settings")

        setNetworkOnline(true)

        repository = SiriRepository(app)
        deviceController = AndroidDeviceController(app)
        viewModel = SiriViewModel(app)
        parser = buildWiredParser(deviceController, repository)
    }

    private fun buildWiredParser(controller: AndroidDeviceController, repo: SiriRepository): SiriIntentParser {
        return SiriIntentParser(
            deviceController = controller,
            onTaskAddTrigger = { title ->
                repo.addTask(title)
                com.example.model.IntentExecutionResult("ADD_TASK", mapOf("title" to title), "Added task $title.", true, "Added Task")
            },
            onNoteAddTrigger = { title, content ->
                repo.addNote(title, content)
                com.example.model.IntentExecutionResult("ADD_NOTE", mapOf("title" to title), "Saved note.", true, "Saved Note")
            },
            onReminderCreateTrigger = { title, triggerAt, repeat ->
                val item = ReminderItem(title = title, triggerTimeMillis = triggerAt, repeat = repeat)
                repo.addReminder(item)
                val sched = controller.reminderScheduler.scheduleReminder(item)
                com.example.model.IntentExecutionResult("SET_REMINDER", mapOf("id" to item.id, "title" to title, "repeat" to repeat.name), "Reminder set: $title.", sched.scheduled, "Set reminder")
            },
            onReminderEditTrigger = { query, newTitle, newTriggerAt, newRepeat ->
                val target = repo.findMatchingReminders(query).firstOrNull() ?: repo.reminders.value.firstOrNull()
                if (target == null) {
                    com.example.model.IntentExecutionResult("EDIT_REMINDER", emptyMap(), "Not found", false, "Not found")
                } else {
                    val updated = repo.updateReminder(target.id, newTitle, newTriggerAt, newRepeat)!!
                    controller.reminderScheduler.scheduleReminder(updated)
                    com.example.model.IntentExecutionResult("EDIT_REMINDER", mapOf("id" to updated.id, "title" to updated.title), "Updated ${updated.title}", true, "Edited reminder")
                }
            },
            onReminderDeleteByIdTrigger = { id ->
                controller.reminderScheduler.cancelReminder(id)
                val deleted = repo.deleteReminder(id)
                com.example.model.IntentExecutionResult("CANCEL_REMINDER", emptyMap(), "Cancelled ${deleted?.title}", deleted != null, "Cancelled reminder")
            },
            findReminderByQuery = { q -> repo.findMatchingReminders(q) },
            findNoteByQuery = { q -> repo.notes.value.filter { it.title.contains(q, true) || it.content.contains(q, true) } },
            onNoteDeleteByIdTrigger = { id ->
                repo.deleteNote(id)
                com.example.model.IntentExecutionResult("DELETE_NOTE", emptyMap(), "Deleted note.", true, "Deleted note")
            },
            findTaskByQuery = { q -> repo.tasks.value.filter { it.title.contains(q, true) } },
            onTaskDeleteByIdTrigger = { id ->
                repo.removeTask(id)
                com.example.model.IntentExecutionResult("DELETE_TASK", emptyMap(), "Deleted task.", true, "Deleted task")
            },
            onClearHistoryTrigger = {
                repo.clearHistory()
                com.example.model.IntentExecutionResult("CLEAR_HISTORY", emptyMap(), "Cleared history.", true, "Cleared history")
            },
            isMemoryEnabled = { repo.userPreferences.value.memoryEnabled },
            onMemoryToggleTrigger = { enabled ->
                repo.setMemoryEnabled(enabled)
                com.example.model.IntentExecutionResult("MEMORY_TOGGLE", mapOf("enabled" to enabled.toString()), "Memory $enabled", true, "Memory toggle")
            },
            onMemorySaveTrigger = { title, content ->
                val mem = repo.addMemory(title, content)
                com.example.model.IntentExecutionResult("SAVE_MEMORY", mapOf("id" to mem.id), "Saved: $content", true, "Saved Memory")
            },
            onMemoryRetrieveTrigger = { query ->
                val matches = if (query.isBlank()) repo.memories.value else repo.searchMemories(query)
                if (matches.isEmpty()) {
                    com.example.model.IntentExecutionResult("RETRIEVE_MEMORY", emptyMap(), "No memory found.", false, "None")
                } else {
                    com.example.model.IntentExecutionResult("RETRIEVE_MEMORY", mapOf("count" to matches.size.toString()), matches.joinToString("; ") { it.content }, true, "Retrieved")
                }
            },
            onMemoryUpdateTrigger = { query, newContent ->
                val target = repo.searchMemories(query).firstOrNull()
                if (target == null) {
                    com.example.model.IntentExecutionResult("UPDATE_MEMORY", emptyMap(), "Not found", false, "Not found")
                } else {
                    repo.updateMemory(target.id, content = newContent)
                    com.example.model.IntentExecutionResult("UPDATE_MEMORY", mapOf("id" to target.id), "Updated to $newContent", true, "Updated Memory")
                }
            },
            findMemoryByQuery = { q -> repo.searchMemories(q) },
            onMemoryDeleteByIdTrigger = { id ->
                val del = repo.deleteMemory(id)
                com.example.model.IntentExecutionResult("DELETE_MEMORY", emptyMap(), "Forgot ${del?.title}", del != null, "Deleted Memory")
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
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        pm.addIntentFilterForActivity(component, filter)
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

    private fun installFakeContactsProvider(contacts: List<Triple<String, String, String>>) {
        val provider = object : ContentProvider() {
            override fun onCreate(): Boolean = true
            override fun query(
                uri: Uri,
                projection: Array<out String>?,
                selection: String?,
                selectionArgs: Array<out String>?,
                sortOrder: String?
            ): Cursor {
                val cols = arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                )
                val cursor = MatrixCursor(cols)
                contacts.forEach { (id, name, number) ->
                    cursor.addRow(arrayOf(id, name, number, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE))
                }
                return cursor
            }
            override fun getType(uri: Uri): String? = null
            override fun insert(uri: Uri, values: ContentValues?): Uri? = null
            override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
            override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
        }
        ShadowContentResolver.registerProviderInternal(ContactsContract.AUTHORITY, provider)
    }

    // 1. Smart Conversation Context Follow-ups
    @Test
    fun testSmartConversationFollowUpsAndRepeat() {
        val volUp = parser.parseAndExecute("volume up")
        assertEquals("VOLUME_UP", volUp?.intentName)
        val volFollow = parser.parseAndExecute("aur badhao")
        assertEquals("VOLUME_UP", volFollow?.intentName)

        val pause = parser.parseAndExecute("pause music")
        assertEquals("MEDIA_PAUSE", pause?.intentName)
        val resumeFollow = parser.parseAndExecute("turn it on")
        assertEquals("MEDIA_PLAY", resumeFollow?.intentName)

        parser.updateLastAssistantSpeech("This is the previous AI response.")
        val repeat = parser.parseAndExecute("repeat that")
        assertEquals("REPEAT", repeat?.intentName)
        assertTrue(repeat?.feedbackSpeech?.contains("previous AI response") == true)
    }

    // 2. Phone Settings Control & Storage Inspection
    @Test
    fun testPhoneSettingsAndStorageControls() {
        val storageRes = parser.parseAndExecute("check storage")
        assertEquals("STORAGE_STATUS", storageRes?.intentName)
        assertTrue(storageRes?.feedbackSpeech?.contains("GB", ignoreCase = true) == true)

        val wifiRes = parser.parseAndExecute("open wifi settings")
        assertEquals("OPEN_SETTINGS", wifiRes?.intentName)
        assertEquals("wifi", wifiRes?.parameters?.get("target"))
        assertEquals(Settings.ACTION_WIFI_SETTINGS, shadowOf(app).nextStartedActivity?.action)

        val locRes = parser.parseAndExecute("open location settings")
        assertEquals("OPEN_SETTINGS", locRes?.intentName)
        assertEquals(Settings.ACTION_LOCATION_SOURCE_SETTINGS, shadowOf(app).nextStartedActivity?.action)
    }

    // 3. Alarm & Scheduled/Recurring Reminders + Scheduler Permission Fallback
    @Test
    fun testRemindersCrudRecurringAndAlarmFallback() {
        val rel = parser.parseAndExecute("30 minute baad reminder lagao")
        assertEquals("SET_REMINDER", rel?.intentName)
        assertEquals(1, repository.reminders.value.size)

        val daily = parser.parseAndExecute("remind me daily at 8 am to drink water")
        assertEquals("SET_REMINDER", daily?.intentName)
        assertEquals(ReminderRepeat.DAILY.name, daily?.parameters?.get("repeat"))
        assertEquals(2, repository.reminders.value.size)

        val edited = parser.parseAndExecute("edit reminder drink water to 9 am")
        assertEquals("EDIT_REMINDER", edited?.intentName)
        assertTrue(edited?.success == true)

        // Cancel reminder is confirmation-gated!
        val cancelAsk = parser.parseAndExecute("cancel reminder drink water")
        assertEquals("CANCEL_REMINDER_CONFIRM", cancelAsk?.intentName)
        assertTrue(cancelAsk?.requiresConfirmation == true)
        assertEquals(2, repository.reminders.value.size)

        val cancelDone = parser.parseAndExecute("yes")
        assertEquals("CANCEL_REMINDER", cancelDone?.intentName)
        assertEquals(1, repository.reminders.value.size)

        // Exact alarm inexact fallback never crashes
        val scheduler = ReminderScheduler(app)
        val schedRes = scheduler.scheduleReminder(
            ReminderItem(title = "Test Alarm", triggerTimeMillis = System.currentTimeMillis() + 60_000L, repeat = ReminderRepeat.DAILY)
        )
        assertTrue(schedRes.scheduled)
        val alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isNotEmpty())
    }

    // 4. Music Control
    @Test
    fun testMusicControls() {
        assertEquals("MEDIA_PLAY", parser.parseAndExecute("play music")?.intentName)
        assertEquals("MEDIA_PAUSE", parser.parseAndExecute("pause music")?.intentName)
        assertEquals("MEDIA_NEXT", parser.parseAndExecute("next song")?.intentName)
        assertEquals("MEDIA_PREV", parser.parseAndExecute("previous track")?.intentName)
        assertNotNull(parser.parseAndExecute("what song is playing"))
    }

    // 5. Multi-Step Compound Commands
    @Test
    fun testMultiStepCommandExecutionInOrder() {
        val multi = parser.parseAndExecute("Hey Siri, YouTube kholo, volume kam karo aur 30 minute baad reminder lagao.")
        assertNotNull(multi)
        assertEquals("MULTI_STEP", multi?.intentName)
        assertEquals("3", multi?.parameters?.get("steps"))
        assertEquals("OPEN_APP,VOLUME_DOWN,SET_REMINDER", multi?.parameters?.get("intents"))
        assertTrue(multi?.success == true)

        val launched = shadowOf(app).nextStartedActivity
        assertEquals("com.google.android.youtube", launched?.`package` ?: launched?.component?.packageName)
        assertEquals(1, repository.reminders.value.size)
    }

    // 6. Confirmation System (Call, SMS, Deletions via Voice & ViewModel)
    @Test
    fun testConfirmationGateAcceptAndCancel() {
        val dialPrompt = parser.parseAndExecute("call 9876543210")
        assertEquals("DIAL_PHONE", dialPrompt?.intentName)
        assertTrue(dialPrompt?.requiresConfirmation == true)
        assertNull("Dialer must NOT open before confirmation", shadowOf(app).nextStartedActivity)

        val cancelled = parser.parseAndExecute("no")
        assertEquals("ACTION_CANCELLED", cancelled?.intentName)
        assertNull(shadowOf(app).nextStartedActivity)

        parser.parseAndExecute("call 9876543210")
        val confirmed = parser.parseAndExecute("haan")
        assertEquals("DIAL_PHONE_CONFIRMED", confirmed?.intentName)
        val dialIntent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_DIAL, dialIntent?.action)
        assertEquals("tel:9876543210", dialIntent?.dataString)

        // ViewModel confirmation gate for clearing history
        viewModel.handleUserUtterance("clear chat history")
        assertNotNull(viewModel.pendingConfirmation.value)
        viewModel.cancelPendingAction()
        assertNull(viewModel.pendingConfirmation.value)
        assertTrue(viewModel.repository.messages.value.isNotEmpty())
    }

    // 7. Contact Assistant (Permission Check, Single Match, Duplicate Disambiguation, Hinglish SMS)
    @Test
    fun testContactAssistantPermissionsSingleAndDuplicates() {
        installFakeContactsProvider(
            listOf(
                Triple("1", "Ananya Sharma", "+919900112233"),
                Triple("2", "Rahul Kumar", "+919811111111"),
                Triple("3", "Rahul Verma", "+919822222222")
            )
        )

        // Without READ_CONTACTS permission
        val denied = parser.parseAndExecute("call Ananya")
        assertEquals("CONTACT_PERMISSION_REQUIRED", denied?.intentName)
        assertFalse(denied?.success == true)

        // Grant READ_CONTACTS
        shadowOf(app).grantPermissions(Manifest.permission.READ_CONTACTS)

        // Duplicate contact disambiguation -> pick 2 -> confirm
        val dup = parser.parseAndExecute("call Rahul")
        assertEquals("CONTACT_DISAMBIGUATION", dup?.intentName)
        assertEquals(2, dup?.contactChoices?.size)

        val confirmRahulVerma = parser.parseAndExecute("2")
        assertEquals("DIAL_PHONE", confirmRahulVerma?.intentName)
        assertTrue(confirmRahulVerma?.requiresConfirmation == true)
        assertTrue(confirmRahulVerma?.feedbackSpeech?.contains("Rahul Verma") == true)

        val dialed = parser.parseAndExecute("yes")
        assertEquals("DIAL_PHONE_CONFIRMED", dialed?.intentName)
        val dialIntent = shadowOf(app).nextStartedActivity
        assertEquals("tel:+919822222222", dialIntent?.dataString)

        // Hinglish SMS via Contact Assistant
        val smsAsk = parser.parseAndExecute("Ananya ko message bhejo main pahunch raha hoon")
        assertEquals("SEND_SMS", smsAsk?.intentName)
        assertTrue(smsAsk?.requiresConfirmation == true)
        assertNull(shadowOf(app).nextStartedActivity)

        val smsSend = parser.parseAndExecute("bhej do")
        assertEquals("SEND_SMS_CONFIRMED", smsSend?.intentName)
        val smsIntent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, smsIntent?.action)
        assertEquals("smsto:+919900112233", smsIntent?.dataString)
        assertEquals("main pahunch raha hoon", smsIntent?.getStringExtra("sms_body"))
    }

    // 8. Smart Memory (Save, Retrieve, Update, Confirmation Delete, Disable/Enable)
    @Test
    fun testSmartMemoryLifecycleAndToggle() {
        val saved = parser.parseAndExecute("remember that my favorite color is blue")
        assertEquals("SAVE_MEMORY", saved?.intentName)
        assertEquals(1, repository.memories.value.size)

        val retrieved = parser.parseAndExecute("what do you remember about color")
        assertEquals("RETRIEVE_MEMORY", retrieved?.intentName)
        assertTrue(retrieved?.feedbackSpeech?.contains("blue", ignoreCase = true) == true)

        val updated = parser.parseAndExecute("update memory color to emerald green")
        assertEquals("UPDATE_MEMORY", updated?.intentName)
        assertEquals("emerald green", repository.memories.value.first().content)

        val delAsk = parser.parseAndExecute("forget color")
        assertEquals("DELETE_MEMORY_CONFIRM", delAsk?.intentName)
        assertTrue(delAsk?.requiresConfirmation == true)
        assertEquals(1, repository.memories.value.size)

        val delDone = parser.parseAndExecute("yes")
        assertEquals("DELETE_MEMORY", delDone?.intentName)
        assertTrue(repository.memories.value.isEmpty())

        val disabled = parser.parseAndExecute("disable memory")
        assertFalse(repository.userPreferences.value.memoryEnabled)
        assertEquals("MEMORY_TOGGLE", disabled?.intentName)

        val blocked = parser.parseAndExecute("remember that I drink tea")
        assertEquals("MEMORY_DISABLED", blocked?.intentName)
        assertFalse(blocked?.success == true)

        parser.parseAndExecute("enable memory")
        assertTrue(repository.userPreferences.value.memoryEnabled)
    }

    // 9. Navigation & Web Search (Online Official Links & Offline Handling)
    @Test
    fun testNavigationWebSearchAndOfflineGracefulHandling() {
        setNetworkOnline(true)

        val mapsOpen = parser.parseAndExecute("open google maps")
        assertEquals("OPEN_MAPS", mapsOpen?.intentName)
        assertTrue(mapsOpen?.success == true)
        shadowOf(app).nextStartedActivity

        val places = parser.parseAndExecute("search places coffee near me")
        assertEquals("PLACE_SEARCH", places?.intentName)
        assertTrue(places?.success == true)
        assertNotNull(shadowOf(app).nextStartedActivity)

        val official = parser.parseAndExecute("official website of nasa")
        assertEquals("WEB_SEARCH", official?.intentName)
        assertTrue(official?.webSources?.any { it.url == "https://www.nasa.gov" } == true)
        shadowOf(app).nextStartedActivity

        // Switch offline
        setNetworkOnline(false)
        val offlineNav = parser.parseAndExecute("navigate to India Gate")
        assertEquals("NAVIGATION", offlineNav?.intentName)
        assertFalse(offlineNav?.success == true)
        assertTrue(offlineNav?.feedbackSpeech?.contains("offline", ignoreCase = true) == true)
        assertNull("Should not launch Maps when offline", shadowOf(app).nextStartedActivity)

        val offlineSearch = parser.parseAndExecute("search for kotlin coroutines")
        assertEquals("WEB_SEARCH", offlineSearch?.intentName)
        assertFalse(offlineSearch?.success == true)
        assertTrue(offlineSearch?.feedbackSpeech?.contains("offline", ignoreCase = true) == true)
        assertNull("Should not launch browser when offline", shadowOf(app).nextStartedActivity)
    }

    // 10. Background Voice Service ("Hey Siri" Standby & Permission Compliance)
    @Test
    fun testBackgroundVoiceServiceStandbyAndCommandExecution() {
        // Without RECORD_AUDIO on Android 14+, starting wake standby must NOT crash with SecurityException
        val deniedController = Robolectric.buildService(
            SiriVoiceService::class.java,
            Intent(app, SiriVoiceService::class.java).apply { action = SiriVoiceService.ACTION_START_WAKE_STANDBY }
        ).create().startCommand(0, 1)
        assertFalse(SiriVoiceService.isWakeStandbyActive)
        deniedController.destroy()

        // With RECORD_AUDIO granted
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val serviceController = Robolectric.buildService(
            SiriVoiceService::class.java,
            Intent(app, SiriVoiceService::class.java).apply { action = SiriVoiceService.ACTION_START_WAKE_STANDBY }
        ).create().startCommand(0, 1)

        assertTrue(SiriVoiceService.isServiceRunning)
        assertTrue(SiriVoiceService.isWakeStandbyActive)

        // Execute multi-step / reminder command directly via background handler
        serviceController.get().handleBackgroundCommand("Hey Siri, 15 minute baad reminder lagao")
        val updatedRepo = SiriRepository(app)
        assertTrue(updatedRepo.reminders.value.isNotEmpty())

        serviceController.withIntent(
            Intent(app, SiriVoiceService::class.java).apply { action = SiriVoiceService.ACTION_STOP_WAKE_STANDBY }
        ).startCommand(0, 2)
        assertFalse(SiriVoiceService.isWakeStandbyActive)

        serviceController.withIntent(
            Intent(app, SiriVoiceService::class.java).apply { action = SiriVoiceService.ACTION_STOP }
        ).startCommand(0, 3)
        assertFalse(SiriVoiceService.isServiceRunning)
        serviceController.destroy()
    }
}
