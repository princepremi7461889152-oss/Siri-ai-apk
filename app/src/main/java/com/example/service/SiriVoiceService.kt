package com.example.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.ai.AiBrainRepository
import com.example.ai.OfflineAssistantEngine
import com.example.data.SiriRepository
import com.example.device.AndroidDeviceController
import com.example.device.PermissionManager
import com.example.intent.SiriIntentParser
import com.example.model.AssistantEmotion
import com.example.model.AssistantMode
import com.example.model.ChatMessage
import com.example.model.InformationFreshness
import com.example.model.IntentExecutionResult
import com.example.model.ReminderItem
import com.example.model.WakeWordOption
import com.example.voice.ActivationSoundPlayer
import com.example.voice.AlwaysOnWakeWordEngine
import com.example.voice.SiriSpeechRecognizer
import com.example.voice.SiriTextToSpeech
import com.example.voice.SoundTriggerCapabilityProbe
import com.example.voice.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service enabling background Siri voice, optional "Hey Siri" wake-word standby,
 * screen-lock/audio-interruption recovery, background AI answers with TTS, and quick notification
 * commands while strictly respecting Android 11-15+ microphone, foreground service, and battery rules.
 */
class SiriVoiceService : Service() {

    companion object {
        private const val TAG = "SiriVoiceService"
        const val CHANNEL_ID = "siri_voice_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "ACTION_START_SIRI_SERVICE"
        const val ACTION_STOP = "ACTION_STOP_SIRI_SERVICE"
        const val ACTION_TRIGGER_LISTEN = "ACTION_TRIGGER_LISTEN"
        const val ACTION_BACKGROUND_LISTEN = "ACTION_BACKGROUND_LISTEN"
        const val ACTION_START_WAKE_STANDBY = "ACTION_START_WAKE_STANDBY"
        const val ACTION_STOP_WAKE_STANDBY = "ACTION_STOP_WAKE_STANDBY"
        const val ACTION_REMOTE_COMMAND = "ACTION_REMOTE_COMMAND"
        const val ACTION_PAUSE_FOR_UI = "ACTION_PAUSE_FOR_UI"
        const val ACTION_RESUME_AFTER_UI = "ACTION_RESUME_AFTER_UI"
        const val EXTRA_TRIGGER_LISTEN = "EXTRA_TRIGGER_LISTEN"
        const val EXTRA_WAKE_STANDBY = "EXTRA_WAKE_STANDBY"
        const val EXTRA_PAUSE_FOR_UI = "EXTRA_PAUSE_FOR_UI"
        const val KEY_REMOTE_COMMAND_TEXT = "KEY_REMOTE_COMMAND_TEXT"
        private const val RESUME_FALLBACK_NOTIFICATION_ID = 1008

        private const val BACKGROUND_LISTEN_TIMEOUT_MS = 12_000L
        private const val STANDBY_RESTART_DELAY_MS = 350L
        private const val MAX_CONSECUTIVE_STANDBY_ERRORS = 10
        private const val WAKELOCK_TIMEOUT_MS = 15_000L

        private val _isServiceRunningFlow = MutableStateFlow(false)
        val isServiceRunningFlow: StateFlow<Boolean> = _isServiceRunningFlow.asStateFlow()

        private val _isWakeStandbyActiveFlow = MutableStateFlow(false)
        val isWakeStandbyActiveFlow: StateFlow<Boolean> = _isWakeStandbyActiveFlow.asStateFlow()

        @Volatile
        private var activeInstance: SiriVoiceService? = null

        @Volatile
        var isServiceRunning = false
            set(value) {
                field = value
                _isServiceRunningFlow.value = value
            }

        @Volatile
        var isWakeStandbyActive = false
            set(value) {
                field = value
                _isWakeStandbyActiveFlow.value = value
            }

        /**
         * Starts [SiriVoiceService] cleanly.
         * - Avoids duplicate FGS restarts when already running in the requested mode.
         * - Refuses to launch a microphone FGS without RECORD_AUDIO permission.
         * - Catches Android 12-15+ [ForegroundServiceStartNotAllowedException] and [SecurityException] safely.
         */
        fun start(
            context: Context,
            enableWakeStandby: Boolean = false,
            pauseForUi: Boolean = false
        ): Boolean {
            if (!PermissionManager.isGranted(context, Manifest.permission.RECORD_AUDIO)) {
                Log.w(TAG, "Cannot start SiriVoiceService: RECORD_AUDIO not granted")
                return false
            }

            val current = activeInstance
            if (isServiceRunning && current != null &&
                isWakeStandbyActive == enableWakeStandby &&
                current.pausedForForegroundUi == pauseForUi
            ) {
                return true
            }

            val intent = Intent(context, SiriVoiceService::class.java).apply {
                action = if (enableWakeStandby) ACTION_START_WAKE_STANDBY else ACTION_START
                putExtra(EXTRA_WAKE_STANDBY, enableWakeStandby)
                putExtra(EXTRA_PAUSE_FOR_UI, pauseForUi)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is ForegroundServiceStartNotAllowedException
                ) {
                    Log.w(TAG, "Android 12+ blocked background foreground service start", e)
                } else {
                    Log.w(TAG, "Unable to start SiriVoiceService", e)
                }
                false
            }
        }

        fun pauseForForegroundUi(context: Context) {
            if (!isServiceRunning) return
            val instance = activeInstance
            if (instance != null) {
                instance.mainHandler.post { instance.pauseForUiInternal() }
                return
            }
            try {
                val intent = Intent(context, SiriVoiceService::class.java).apply {
                    action = ACTION_PAUSE_FOR_UI
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "pauseForForegroundUi failed", e)
            }
        }

        fun resumeAfterForegroundUi(context: Context, enableWakeStandby: Boolean? = null) {
            if (!isServiceRunning) return
            val instance = activeInstance
            if (instance != null) {
                instance.mainHandler.post { instance.resumeAfterUiInternal(enableWakeStandby) }
                return
            }
            try {
                val intent = Intent(context, SiriVoiceService::class.java).apply {
                    action = ACTION_RESUME_AFTER_UI
                    if (enableWakeStandby != null) {
                        putExtra(EXTRA_WAKE_STANDBY, enableWakeStandby)
                    }
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "resumeAfterForegroundUi failed", e)
            }
        }

        fun stop(context: Context) {
            SiriRepository(context.applicationContext).setBackgroundServiceEnabled(false)
            SiriRepository(context.applicationContext).setBackgroundWakeStandbyEnabled(false)
            val instance = activeInstance
            if (instance != null) {
                instance.mainHandler.post { instance.stopServiceFromCommand() }
                return
            }
            if (!isServiceRunning) {
                isWakeStandbyActive = false
                return
            }
            val intent = Intent(context, SiriVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "Fallback stopService after startService error", e)
                try {
                    context.stopService(Intent(context, SiriVoiceService::class.java))
                } catch (_: Exception) {
                }
                isServiceRunning = false
                isWakeStandbyActive = false
            }
        }
    }

    private var repository: SiriRepository? = null
    private var deviceController: AndroidDeviceController? = null
    private var aiBrain: AiBrainRepository? = null
    private var intentParser: SiriIntentParser? = null
    private var speechRecognizer: SiriSpeechRecognizer? = null
    private var textToSpeech: SiriTextToSpeech? = null
    private var alwaysOnWakeEngine: AlwaysOnWakeWordEngine? = null
    private val wakeWordDetector = WakeWordDetector()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backgroundAiJob: Job? = null
    private var timeoutRunnable: Runnable? = null
    private var standbyRestartRunnable: Runnable? = null

    private var audioManager: AudioManager? = null
    private var audioRecordingCallback: AudioManager.AudioRecordingCallback? = null
    private var screenStateReceiver: BroadcastReceiver? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var isAssistantSpeaking = false

    @Volatile
    private var awaitingWakeFollowUp = false

    @Volatile
    private var resumeStandbyAfterCommand = false

    @Volatile
    private var pausedForForegroundUi = false

    @Volatile
    private var pausedDueToAudioInterruption = false

    @Volatile
    private var pausedDueToScreenLock = false

    @Volatile
    private var pausedDueToDeviceIdle = false

    @Volatile
    private var isScreenOff = false

    @Volatile
    private var isOurRecognizerRecording = false

    @Volatile
    private var consecutiveErrorCount = 0

    @Volatile
    private var consecutiveIdleCycles = 0

    override fun onCreate() {
        super.onCreate()
        activeInstance = this
        createNotificationChannel()

        val repo = SiriRepository(applicationContext)
        val controller = AndroidDeviceController(applicationContext)
        val brain = AiBrainRepository(applicationContext)
        repository = repo
        deviceController = controller
        aiBrain = brain

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        isScreenOff = pm?.let { !it.isInteractive } ?: false
        wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG::VoiceCommandWakeLock")?.apply {
            setReferenceCounted(false)
        }

        intentParser = SiriIntentParser(
            deviceController = controller,
            onReminderCreateTrigger = { title, triggerAt, repeat ->
                val item = ReminderItem(title = title, triggerTimeMillis = triggerAt, repeat = repeat)
                repo.addReminder(item)
                val sched = controller.reminderScheduler.scheduleReminder(item)
                IntentExecutionResult(
                    intentName = "SET_REMINDER",
                    parameters = mapOf("id" to item.id, "title" to title),
                    feedbackSpeech = "Reminder set: $title.",
                    success = sched.scheduled,
                    actionSummary = "Set reminder: $title"
                )
            },
            onReminderEditTrigger = { query, newTitle, newTriggerAt, newRepeat ->
                val target = repo.findMatchingReminders(query).firstOrNull() ?: repo.reminders.value.firstOrNull()
                if (target == null) {
                    IntentExecutionResult("EDIT_REMINDER", emptyMap(), "Could not find reminder '$query'.", false)
                } else {
                    val updated = repo.updateReminder(target.id, newTitle, newTriggerAt, newRepeat)!!
                    if (updated.isEnabled) controller.reminderScheduler.scheduleReminder(updated)
                    IntentExecutionResult("EDIT_REMINDER", mapOf("id" to updated.id), "Updated reminder '${updated.title}'.", true, "Edited reminder")
                }
            },
            onReminderDeleteByIdTrigger = { id ->
                controller.reminderScheduler.cancelReminder(id)
                val removed = repo.deleteReminder(id)
                IntentExecutionResult(
                    intentName = "CANCEL_REMINDER",
                    feedbackSpeech = if (removed != null) "Cancelled reminder '${removed.title}'." else "Reminder not found.",
                    success = removed != null
                )
            },
            onReminderListTrigger = {
                val active = repo.reminders.value.filter { it.isEnabled }
                if (active.isEmpty()) {
                    IntentExecutionResult("LIST_REMINDERS", emptyMap(), "No active reminders scheduled.", true)
                } else {
                    val fmt = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())
                    val text = active.take(4).joinToString("; ") { "${it.title} at ${fmt.format(Date(it.triggerTimeMillis))}" }
                    IntentExecutionResult("LIST_REMINDERS", emptyMap(), "Upcoming reminders: $text", true)
                }
            },
            findReminderByQuery = { q -> repo.findMatchingReminders(q) },
            onTaskAddTrigger = { title ->
                repo.addTask(title)
                IntentExecutionResult("ADD_TASK", mapOf("title" to title), "Added task $title.", true, "Added Task: $title")
            },
            findTaskByQuery = { q -> repo.tasks.value.filter { it.title.contains(q, ignoreCase = true) } },
            onTaskDeleteByIdTrigger = { id ->
                repo.removeTask(id)
                IntentExecutionResult("DELETE_TASK", emptyMap(), "Deleted task.", true, "Deleted Task")
            },
            onNoteAddTrigger = { title, body ->
                repo.addNote(title, body)
                IntentExecutionResult("ADD_NOTE", mapOf("title" to title), "Saved note: $body", true, "Saved Note")
            },
            findNoteByQuery = { q -> repo.notes.value.filter { it.title.contains(q, true) || it.content.contains(q, true) } },
            onNoteDeleteByIdTrigger = { id ->
                repo.deleteNote(id)
                IntentExecutionResult("DELETE_NOTE", emptyMap(), "Deleted note.", true, "Deleted Note")
            },
            getVoiceShortcuts = { repo.shortcuts.value },
            onClearHistoryTrigger = {
                repo.clearHistory()
                IntentExecutionResult("CLEAR_HISTORY", emptyMap(), "Cleared chat history.", true)
            },
            onMemorySaveTrigger = { title, fact ->
                repo.addMemory(title, fact)
                IntentExecutionResult("SAVE_MEMORY", mapOf("content" to fact), "Saved to memory: $fact", true, "Saved Memory")
            },
            onMemoryRetrieveTrigger = { query ->
                val matches = if (query.isBlank()) repo.memories.value else repo.searchMemories(query)
                if (matches.isEmpty()) {
                    IntentExecutionResult("RETRIEVE_MEMORY", emptyMap(), "No matching memory saved.", false)
                } else {
                    IntentExecutionResult("RETRIEVE_MEMORY", emptyMap(), "I remember: ${matches.take(3).joinToString("; ") { it.content }}", true)
                }
            },
            onMemoryUpdateTrigger = { query, newContent ->
                val target = repo.searchMemories(query).firstOrNull()
                if (target == null) {
                    IntentExecutionResult("UPDATE_MEMORY", emptyMap(), "Memory not found.", false)
                } else {
                    repo.updateMemory(target.id, content = newContent)
                    IntentExecutionResult("UPDATE_MEMORY", emptyMap(), "Updated memory to: $newContent", true)
                }
            },
            findMemoryByQuery = { q -> repo.searchMemories(q) },
            onMemoryDeleteByIdTrigger = { id ->
                val del = repo.deleteMemory(id)
                IntentExecutionResult("DELETE_MEMORY", emptyMap(), "Forgot '${del?.title ?: "memory"}'.", del != null)
            },
            onMemoryToggleTrigger = { enabled ->
                repo.setMemoryEnabled(enabled)
                IntentExecutionResult("MEMORY_TOGGLE", emptyMap(), if (enabled) "Memory enabled." else "Memory disabled.", true)
            },
            isMemoryEnabled = { repo.userPreferences.value.memoryEnabled },
            onModeChangeTrigger = { mode ->
                repo.updateAssistantMode(mode)
                IntentExecutionResult("CHANGE_MODE", mapOf("mode" to mode.name), "Switched to ${mode.shortLabel} mode.", true, "Mode: ${mode.shortLabel}")
            },
            onOfficeCommandTrigger = { command ->
                val officeRes = OfflineAssistantEngine.tryHandleOfficeCommand(
                    rawText = command,
                    isOnline = controller.isNetworkAvailable(),
                    isOfficeMode = repo.assistantMode.value == AssistantMode.OFFICE,
                    notes = repo.notes.value,
                    tasks = repo.tasks.value,
                    reminders = repo.reminders.value,
                    userName = repo.userPreferences.value.userName,
                    onAddFlashcard = { q, a, topic -> repo.addFlashcard(q, a, topic) }
                )
                officeRes?.let {
                    IntentExecutionResult(
                        intentName = it.intentName,
                        parameters = if (it.isNoteSaveable) mapOf("saveableTitle" to it.suggestedNoteTitle) else emptyMap(),
                        feedbackSpeech = it.text,
                        success = true,
                        actionSummary = it.summary,
                        emotion = it.emotion
                    )
                }
            }
        )

        alwaysOnWakeEngine = AlwaysOnWakeWordEngine(
            context = applicationContext,
            onWakeDetected = { matchedPhrase, confidence ->
                mainHandler.post {
                    onOfflineWakeWordTriggered(matchedPhrase, confidence)
                }
            },
            onHardwareError = { reason ->
                mainHandler.post {
                    handleAlwaysOnHardwareError(reason)
                }
            }
        ).apply {
            wakeWordOption = repo.wakeWordOption.value
        }

        serviceScope.launch {
            repo.wakeWordOption.collect { option ->
                alwaysOnWakeEngine?.wakeWordOption = option
                if (isServiceRunning && isWakeStandbyActive && !pausedForForegroundUi) {
                    mainHandler.post {
                        updateNotification("Always-On ${option.label} • Say \"${option.label}\"")
                    }
                }
            }
        }

        val hardwareStatus = SoundTriggerCapabilityProbe.probe(applicationContext)
        Log.d(TAG, "Wake Engine Capability: ${hardwareStatus.detailMessage}")

        textToSpeech = SiriTextToSpeech(applicationContext) { speaking ->
            isAssistantSpeaking = speaking
            if (!speaking) {
                wakeWordDetector.markAssistantFinishedSpeaking()
                alwaysOnWakeEngine?.markAssistantFinishedSpeaking()
                if (awaitingWakeFollowUp && isServiceRunning && !pausedForForegroundUi) {
                    awaitingWakeFollowUp = false
                    startBackgroundSpeechCapture()
                } else if (shouldResumeStandbyNow()) {
                    if (resumeStandbyAfterCommand) {
                        isWakeStandbyActive = true
                        resumeStandbyAfterCommand = false
                    }
                    scheduleWakeStandbyRestart(STANDBY_RESTART_DELAY_MS)
                }
            }
        }.apply {
            setPersonality(repo.voicePersonality.value)
            setVoiceGender(repo.voiceGender.value)
            setLanguage(repo.selectedLanguage.value)
            setSpeedMultiplier(repo.customSpeedMultiplier.value)
            setPitchMultiplier(repo.customPitchMultiplier.value)
        }

        speechRecognizer = SiriSpeechRecognizer(
            context = applicationContext,
            onResult = { recognizedText ->
                isOurRecognizerRecording = false
                consecutiveErrorCount = 0
                consecutiveIdleCycles = 0
                cancelBackgroundTimeout()
                handleBackgroundCommand(recognizedText)
            },
            onPartialResult = {},
            onRmsDbChanged = {},
            onRawRmsSample = { level, isSpeechActive ->
                wakeWordDetector.onRmsSample(level, isSpeechActive)
            },
            onDetailedResult = { matches, confidences ->
                isOurRecognizerRecording = false
                consecutiveErrorCount = 0
                consecutiveIdleCycles = 0
                if (isWakeStandbyActive && !awaitingWakeFollowUp) {
                    handleBackgroundStandbyMatches(matches, confidences)
                } else {
                    val top = matches.firstOrNull().orEmpty()
                    if (top.isNotBlank()) {
                        cancelBackgroundTimeout()
                        handleBackgroundCommand(top)
                    } else if (shouldResumeStandbyNow()) {
                        restoreStandbyIfNeeded()
                    }
                }
            },
            onSilentRestartNeeded = {
                isOurRecognizerRecording = false
                if (shouldResumeStandbyNow()) {
                    scheduleWakeStandbyRestart(STANDBY_RESTART_DELAY_MS)
                }
            },
            onNoSpeechDetected = {
                isOurRecognizerRecording = false
                cancelBackgroundTimeout()
                if (resumeStandbyAfterCommand) {
                    restoreStandbyIfNeeded()
                } else {
                    updateNotification(currentIdleNotificationText())
                }
            },
            onErrorOccurred = { err ->
                isOurRecognizerRecording = false
                cancelBackgroundTimeout()
                handleSpeechErrorOccurred(err)
            },
            onErrorCode = { code, _ ->
                isOurRecognizerRecording = false
                if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    consecutiveErrorCount = MAX_CONSECUTIVE_STANDBY_ERRORS
                } else if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    consecutiveErrorCount = (consecutiveErrorCount + 1).coerceAtMost(MAX_CONSECUTIVE_STANDBY_ERRORS + 2)
                }
            },
            onListeningStateChanged = { listening ->
                isOurRecognizerRecording = listening
                if (listening) {
                    consecutiveErrorCount = 0
                    if (!isWakeStandbyActive) {
                        updateNotification("Listening in background… speak your command")
                    }
                }
            }
        ).apply {
            setLanguage(repo.selectedLanguage.value)
        }

        registerScreenStateReceiver()
        registerAudioRecordingMonitor()
    }

    private fun handleAlwaysOnHardwareError(reason: String) {
        isOurRecognizerRecording = false
        if (!hasRecordAudioPermission()) {
            isWakeStandbyActive = false
            pausedDueToScreenLock = false
            alwaysOnWakeEngine?.stop()
            updateNotification("Microphone permission required for wake listening")
            return
        }
        if (isVoiceCallOrExternalMicBusy()) {
            pausedDueToAudioInterruption = true
            alwaysOnWakeEngine?.stop()
            updateNotification("Paused during call / external mic use • Will resume")
            return
        }
        if (isDeviceInDoze()) {
            pausedDueToDeviceIdle = true
            alwaysOnWakeEngine?.stop()
            updateNotification("Doze battery saver • Resumes when active")
            return
        }
        if (isScreenOff) {
            // OEM blocked continuous mic stream after screen lock — pause gracefully until unlock
            pausedDueToScreenLock = true
            alwaysOnWakeEngine?.stop()
            updateNotification("Screen locked ($reason) • Unlock to resume")
            return
        }
        Log.d(TAG, "Always-On wake engine notice: $reason")
    }

    private fun handleSpeechErrorOccurred(err: String) {
        if (!hasRecordAudioPermission()) {
            isWakeStandbyActive = false
            pausedDueToScreenLock = false
            alwaysOnWakeEngine?.stop()
            updateNotification("Microphone permission required for voice listening")
            return
        }

        if (isVoiceCallOrExternalMicBusy()) {
            pausedDueToAudioInterruption = true
            cancelWakeStandbyRestart()
            alwaysOnWakeEngine?.stop()
            updateNotification("Paused during call / external audio • Will resume")
            return
        }

        if (resumeStandbyAfterCommand) {
            restoreStandbyIfNeeded()
        } else if (isWakeStandbyActive && isServiceRunning && !pausedForForegroundUi) {
            restoreStandbyIfNeeded()
        } else {
            updateNotification("Background Voice Ready • $err")
        }
    }

    private fun shouldResumeStandbyNow(): Boolean {
        return isServiceRunning &&
            (isWakeStandbyActive || resumeStandbyAfterCommand) &&
            !isAssistantSpeaking &&
            !pausedForForegroundUi &&
            !pausedDueToAudioInterruption &&
            !pausedDueToScreenLock &&
            !pausedDueToDeviceIdle &&
            hasRecordAudioPermission()
    }

    private fun restoreStandbyIfNeeded() {
        if (!isServiceRunning) return
        if (resumeStandbyAfterCommand) {
            resumeStandbyAfterCommand = false
            isWakeStandbyActive = true
        }
        if (isWakeStandbyActive && !pausedForForegroundUi && !pausedDueToAudioInterruption && !pausedDueToScreenLock && !pausedDueToDeviceIdle) {
            startWakeWordStandbyMode()
        } else {
            updateNotification(currentIdleNotificationText())
        }
    }

    private fun currentIdleNotificationText(): String {
        val wakeLabel = repository?.wakeWordOption?.value?.label ?: "Hey Siri"
        return when {
            pausedDueToAudioInterruption -> "Paused during call / external mic use"
            pausedDueToScreenLock -> "Screen locked • Unlock or tap 'Command'"
            pausedDueToDeviceIdle -> "Doze battery saver • Resumes when active"
            isWakeStandbyActive -> "Always-On $wakeLabel • Say \"$wakeLabel\""
            else -> "Background Voice Active • Tap 'Listen' or 'Command'"
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle START_STICKY process recreation (intent == null)
        if (intent == null) {
            val repo = repository
            val shouldStayActive = repo?.backgroundServiceEnabled?.value == true
            if (!shouldStayActive || !hasRecordAudioPermission()) {
                stopSelfSafely()
                return START_NOT_STICKY
            }
            if (!ensureForegroundStarted(isBackgroundRestart = true)) {
                return START_NOT_STICKY
            }
            val restoreWake = repo.backgroundWakeStandbyEnabled.value
            if (restoreWake && !pausedForForegroundUi) {
                startWakeWordStandbyMode()
            } else {
                updateNotification(currentIdleNotificationText())
            }
            return START_STICKY
        }

        when (intent.action) {
            ACTION_STOP -> {
                stopServiceFromCommand()
                return START_NOT_STICKY
            }
            ACTION_REMOTE_COMMAND -> {
                if (!ensureForegroundStarted()) {
                    return START_NOT_STICKY
                }
                repository?.setBackgroundServiceEnabled(true)
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val typedCommand = remoteInput?.getCharSequence(KEY_REMOTE_COMMAND_TEXT)?.toString()?.trim()
                if (!typedCommand.isNullOrBlank()) {
                    handleBackgroundCommand(typedCommand)
                }
                return START_STICKY
            }
            ACTION_BACKGROUND_LISTEN -> {
                if (ensureForegroundStarted()) {
                    repository?.setBackgroundServiceEnabled(true)
                    resumeStandbyAfterCommand = isWakeStandbyActive ||
                        (repository?.backgroundWakeStandbyEnabled?.value == true)
                    isWakeStandbyActive = false
                    pausedForForegroundUi = false
                    pausedDueToScreenLock = false
                    pausedDueToDeviceIdle = false
                    cancelWakeStandbyRestart()
                    alwaysOnWakeEngine?.stop()
                    isOurRecognizerRecording = false
                    startBackgroundSpeechCapture()
                }
                return START_STICKY
            }
            ACTION_START_WAKE_STANDBY -> {
                val pauseInitially = intent.getBooleanExtra(EXTRA_PAUSE_FOR_UI, false)
                if (ensureForegroundStarted()) {
                    repository?.setBackgroundServiceEnabled(true)
                    repository?.setBackgroundWakeStandbyEnabled(true)
                    pausedForForegroundUi = pauseInitially
                    pausedDueToScreenLock = false
                    pausedDueToDeviceIdle = false
                    if (pauseInitially) {
                        isWakeStandbyActive = true
                        alwaysOnWakeEngine?.stop()
                        isOurRecognizerRecording = false
                        updateNotification("Siri Foreground Active • Mic handed to app")
                    } else {
                        startWakeWordStandbyMode()
                    }
                }
                return START_STICKY
            }
            ACTION_STOP_WAKE_STANDBY -> {
                if (!isServiceRunning && !ensureForegroundStarted()) {
                    return START_NOT_STICKY
                }
                repository?.setBackgroundWakeStandbyEnabled(false)
                isWakeStandbyActive = false
                awaitingWakeFollowUp = false
                resumeStandbyAfterCommand = false
                pausedDueToScreenLock = false
                cancelWakeStandbyRestart()
                alwaysOnWakeEngine?.stop()
                isOurRecognizerRecording = false
                speechRecognizer?.cancel()
                updateNotification("Background Voice Active • Tap 'Listen' or 'Hey Siri'")
                return START_STICKY
            }
            ACTION_PAUSE_FOR_UI -> {
                if (!isServiceRunning && !ensureForegroundStarted()) {
                    return START_NOT_STICKY
                }
                pauseForUiInternal()
                return START_STICKY
            }
            ACTION_RESUME_AFTER_UI -> {
                if (!isServiceRunning && !ensureForegroundStarted()) {
                    return START_NOT_STICKY
                }
                val wakeExtra = if (intent.hasExtra(EXTRA_WAKE_STANDBY)) {
                    intent.getBooleanExtra(EXTRA_WAKE_STANDBY, false)
                } else null
                resumeAfterUiInternal(wakeExtra)
                return START_STICKY
            }
        }

        val startWithWake = intent.getBooleanExtra(EXTRA_WAKE_STANDBY, false)
        val pauseInitially = intent.getBooleanExtra(EXTRA_PAUSE_FOR_UI, false)
        if (ensureForegroundStarted()) {
            repository?.setBackgroundServiceEnabled(true)
            pausedForForegroundUi = pauseInitially
            if (pauseInitially) {
                isWakeStandbyActive = startWithWake || (repository?.backgroundWakeStandbyEnabled?.value == true)
                if (startWithWake) {
                    repository?.setBackgroundWakeStandbyEnabled(true)
                }
                updateNotification("Siri Foreground Active • Mic handed to app")
            } else if (startWithWake) {
                repository?.setBackgroundWakeStandbyEnabled(true)
                startWakeWordStandbyMode()
            } else if (repository?.backgroundWakeStandbyEnabled?.value == true) {
                startWakeWordStandbyMode()
            } else {
                updateNotification(currentIdleNotificationText())
            }
            return START_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val shouldStayActive = repository?.backgroundServiceEnabled?.value == true
        if (shouldStayActive && hasRecordAudioPermission() && isServiceRunning) {
            pausedForForegroundUi = false
            updateNotification(currentIdleNotificationText())
            if ((isWakeStandbyActive || repository?.backgroundWakeStandbyEnabled?.value == true) &&
                !pausedDueToScreenLock && !pausedDueToAudioInterruption && !pausedDueToDeviceIdle
            ) {
                startWakeWordStandbyMode()
            }
        }
    }

    private fun pauseForUiInternal() {
        pausedForForegroundUi = true
        cancelWakeStandbyRestart()
        cancelBackgroundTimeout()
        alwaysOnWakeEngine?.stop()
        isOurRecognizerRecording = false
        speechRecognizer?.cancel()
        updateNotification("Siri Foreground Active • Mic handed to app")
    }

    private fun resumeAfterUiInternal(enableWakeStandby: Boolean? = null) {
        pausedForForegroundUi = false
        if (!isServiceRunning || !hasRecordAudioPermission()) {
            return
        }
        if (enableWakeStandby == true) {
            repository?.setBackgroundWakeStandbyEnabled(true)
        }
        val wantWake = (enableWakeStandby == true) ||
            isWakeStandbyActive ||
            (repository?.backgroundWakeStandbyEnabled?.value == true)
        if (wantWake) {
            startWakeWordStandbyMode()
        } else {
            alwaysOnWakeEngine?.stop()
            updateNotification("Background Voice Active • Tap 'Listen' or 'Command'")
        }
    }

    private fun stopServiceFromCommand() {
        repository?.setBackgroundServiceEnabled(false)
        repository?.setBackgroundWakeStandbyEnabled(false)
        isServiceRunning = false
        isWakeStandbyActive = false
        awaitingWakeFollowUp = false
        resumeStandbyAfterCommand = false
        pausedForForegroundUi = false
        pausedDueToAudioInterruption = false
        pausedDueToScreenLock = false
        pausedDueToDeviceIdle = false
        isOurRecognizerRecording = false
        cancelBackgroundTimeout()
        cancelWakeStandbyRestart()
        backgroundAiJob?.cancel()
        alwaysOnWakeEngine?.stop()
        speechRecognizer?.cancel()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
        stopSelf()
    }

    private fun stopSelfSafely() {
        isServiceRunning = false
        isWakeStandbyActive = false
        isOurRecognizerRecording = false
        stopSelf()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureForegroundStarted(isBackgroundRestart: Boolean = false): Boolean {
        val hasMicPermission = hasRecordAudioPermission()
        val notification = buildNotification(currentIdleNotificationText())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !hasMicPermission) {
            // Android 14+ throws SecurityException if starting a microphone FGS without RECORD_AUDIO
            Log.w(TAG, "RECORD_AUDIO permission not granted; cannot run microphone foreground service on Android 14+")
            isServiceRunning = false
            isWakeStandbyActive = false
            stopSelf()
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isServiceRunning = true
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Microphone foreground service restricted by OS", e)
            if (isBackgroundRestart) {
                postOneTapResumeNotification()
            }
            isServiceRunning = false
            isWakeStandbyActive = false
            stopSelf()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground service", e)
            if (isBackgroundRestart) {
                postOneTapResumeNotification()
            }
            isServiceRunning = false
            isWakeStandbyActive = false
            stopSelf()
            false
        }
    }

    private fun postOneTapResumeNotification() {
        if (!PermissionManager.isGranted(this, Manifest.permission.POST_NOTIFICATIONS)) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val resumeIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(com.example.device.BootReceiver.EXTRA_RESUME_BACKGROUND_SERVICE, true)
        }
        val pi = PendingIntent.getActivity(
            this,
            RESUME_FALLBACK_NOTIFICATION_ID,
            resumeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Siri AI Assistant Paused")
            .setContentText("Android stopped background mic access after process restart. Tap to resume.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        manager.notify(RESUME_FALLBACK_NOTIFICATION_ID, notif)
    }

    private fun startWakeWordStandbyMode() {
        if (!hasRecordAudioPermission()) {
            isWakeStandbyActive = false
            alwaysOnWakeEngine?.stop()
            isOurRecognizerRecording = false
            updateNotification("Microphone permission required for wake listening")
            return
        }
        if (isVoiceCallOrExternalMicBusy()) {
            isWakeStandbyActive = true
            pausedDueToAudioInterruption = true
            alwaysOnWakeEngine?.stop()
            isOurRecognizerRecording = false
            updateNotification("Paused during call / external mic use")
            return
        }
        if (isDeviceInDoze()) {
            isWakeStandbyActive = true
            pausedDueToDeviceIdle = true
            alwaysOnWakeEngine?.stop()
            isOurRecognizerRecording = false
            updateNotification("Doze battery saver • Resumes when active")
            return
        }
        consecutiveErrorCount = 0
        consecutiveIdleCycles = 0
        isWakeStandbyActive = true
        pausedDueToScreenLock = false
        pausedDueToDeviceIdle = false
        pausedDueToAudioInterruption = false
        val wakeLabel = repository?.wakeWordOption?.value?.label ?: "Hey Siri"
        alwaysOnWakeEngine?.wakeWordOption = repository?.wakeWordOption?.value ?: WakeWordOption.HEY_SIRI

        if (!pausedForForegroundUi) {
            // Single continuous offline AudioRecord stream — NEVER restarts mic in a loop
            val started = alwaysOnWakeEngine?.start() == true
            isOurRecognizerRecording = started
            if (started) {
                updateNotification("Always-On $wakeLabel • Say \"$wakeLabel\"")
            } else {
                updateNotification("Background Standby Ready • Tap 'Listen' or 'Command'")
            }
        } else {
            updateNotification("Siri Foreground Active • Mic handed to app")
        }
    }

    private fun scheduleWakeStandbyRestart(delayMs: Long) {
        cancelWakeStandbyRestart()
        val runnable = Runnable {
            if (!isServiceRunning || !isWakeStandbyActive || isAssistantSpeaking ||
                pausedForForegroundUi || pausedDueToAudioInterruption || pausedDueToScreenLock ||
                pausedDueToDeviceIdle || !hasRecordAudioPermission()
            ) {
                return@Runnable
            }
            if (isVoiceCallOrExternalMicBusy()) {
                pausedDueToAudioInterruption = true
                alwaysOnWakeEngine?.stop()
                isOurRecognizerRecording = false
                updateNotification("Paused during call / external mic use")
                return@Runnable
            }
            val started = alwaysOnWakeEngine?.start() == true
            isOurRecognizerRecording = started
            if (started) {
                val wakeLabel = repository?.wakeWordOption?.value?.label ?: "Hey Siri"
                updateNotification("Always-On $wakeLabel • Say \"$wakeLabel\"")
            }
        }
        standbyRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelWakeStandbyRestart() {
        standbyRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        standbyRestartRunnable = null
    }

    internal fun onOfflineWakeWordTriggered(matchedPhrase: String, confidence: Float) {
        if (!isServiceRunning || isAssistantSpeaking || pausedForForegroundUi) return
        Log.d(TAG, "Offline wake word '$matchedPhrase' triggered (conf=$confidence)")

        // Release continuous AudioRecord stream immediately so SpeechRecognizer can capture command
        alwaysOnWakeEngine?.stop()
        isOurRecognizerRecording = false
        resumeStandbyAfterCommand = true
        isWakeStandbyActive = false

        acquireShortWakeLock()
        ActivationSoundPlayer.playActivationChime(applicationContext)

        // If background activity launches are permitted (e.g. API < 29 or overlay granted), bring existing Siri UI forward
        val canLaunchUi = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            android.provider.Settings.canDrawOverlays(this)
        if (canLaunchUi && !isScreenOff) {
            try {
                val uiIntent = Intent(this, MainActivity::class.java).apply {
                    action = ACTION_TRIGGER_LISTEN
                    putExtra(EXTRA_TRIGGER_LISTEN, true)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                }
                startActivity(uiIntent)
                updateNotification("$matchedPhrase detected • Siri UI active")
                return
            } catch (e: Exception) {
                Log.d(TAG, "Foreground UI launch restricted by OS; running voice conversation in service", e)
            }
        }

        updateNotification("$matchedPhrase detected • Listening for your command…")
        startBackgroundSpeechCapture()
    }

    private fun handleBackgroundStandbyMatches(matches: List<String>, confidences: FloatArray?) {
        val wakeOption = repository?.wakeWordOption?.value ?: WakeWordOption.HEY_SIRI
        val eval = wakeWordDetector.evaluate(
            nBestMatches = matches,
            confidenceScores = confidences,
            wakeWordOption = wakeOption,
            isAssistantCurrentlySpeaking = isAssistantSpeaking
        )
        if (!eval.detected) {
            scheduleWakeStandbyRestart(180L)
            return
        }

        if (eval.commandText.isNotBlank()) {
            handleBackgroundCommand(eval.commandText)
        } else {
            awaitingWakeFollowUp = true
            updateNotification("Yes? Listening for your command…")
            textToSpeech?.speak("Yes? I'm listening.", AssistantEmotion.CURIOSITY)
        }
    }

    private fun startBackgroundSpeechCapture() {
        if (!hasRecordAudioPermission()) {
            updateNotification("Microphone permission needed for background voice")
            return
        }
        if (isVoiceCallOrExternalMicBusy()) {
            pausedDueToAudioInterruption = true
            updateNotification("Microphone busy during call / external app")
            return
        }

        mainHandler.post {
            try {
                speechRecognizer?.startListening(standbyMode = false)
                scheduleBackgroundTimeout()
            } catch (e: Exception) {
                Log.w(TAG, "Background speech capture failed", e)
                speechRecognizer?.recreateRecognizer()
                updateNotification("Tap 'Listen' or 'Command' to speak")
                if (resumeStandbyAfterCommand) {
                    restoreStandbyIfNeeded()
                }
            }
        }
    }

    private fun scheduleBackgroundTimeout() {
        cancelBackgroundTimeout()
        val runnable = Runnable {
            speechRecognizer?.stopListening()
            if (resumeStandbyAfterCommand) {
                restoreStandbyIfNeeded()
            } else {
                updateNotification(currentIdleNotificationText())
            }
        }
        timeoutRunnable = runnable
        mainHandler.postDelayed(runnable, BACKGROUND_LISTEN_TIMEOUT_MS)
    }

    private fun cancelBackgroundTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    internal fun handleBackgroundCommand(rawCommand: String) {
        val parser = intentParser ?: return
        val repo = repository
        val controller = deviceController
        val clean = parser.cleanWakePhrase(rawCommand).trim()
        if (clean.isBlank()) {
            if (resumeStandbyAfterCommand) restoreStandbyIfNeeded() else updateNotification(currentIdleNotificationText())
            return
        }

        val preferOfflineVoice = repo?.assistantMode?.value == AssistantMode.OFFLINE || controller?.isNetworkAvailable() == false
        textToSpeech?.setPreferOffline(preferOfflineVoice)
        speechRecognizer?.setPreferOffline(preferOfflineVoice)

        val result = parser.parseAndExecute(clean) ?: if (repo != null && preferOfflineVoice) {
            val prefs = repo.userPreferences.value
            val (reply, emotion) = OfflineAssistantEngine.answerOfflineQuery(
                prompt = clean,
                language = repo.selectedLanguage.value,
                memories = repo.memories.value,
                userName = prefs.userName,
                onDeviceAiSupported = controller?.isOnDeviceAiHardwareSupported() == true,
                userPrefsSummary = "Name: ${prefs.userName}, Language: ${repo.selectedLanguage.value.displayName}, Home: ${prefs.homeLocation}, Work: ${prefs.workLocation}"
            )
            IntentExecutionResult(
                intentName = "OFFLINE_ASSISTANT",
                feedbackSpeech = reply,
                success = true,
                actionSummary = "Offline reply",
                emotion = emotion
            )
        } else null

        if (result != null) {
            repo?.addMessage(
                ChatMessage(
                    sessionId = repo.currentSessionId.value,
                    text = clean,
                    isFromUser = true,
                    assistantMode = repo.assistantMode.value
                )
            )
            repo?.addMessage(
                ChatMessage(
                    sessionId = repo.currentSessionId.value,
                    text = result.feedbackSpeech,
                    isFromUser = false,
                    actionType = result.intentName,
                    actionSummary = result.actionSummary,
                    freshness = InformationFreshness.VERIFIED_SYSTEM,
                    emotion = result.emotion,
                    assistantMode = repo.assistantMode.value
                )
            )
            updateNotification(result.actionSummary ?: result.feedbackSpeech)
            textToSpeech?.speak(result.feedbackSpeech, result.emotion)
            if (resumeStandbyAfterCommand && !isAssistantSpeaking) {
                // In case TTS engine isn't initialized yet or doesn't emit onDone synchronously
                scheduleStandbyRecoveryFallback()
            }
        } else {
            // Answer conversational AI queries directly in background without blocked background startActivity()
            executeBackgroundAiQuery(clean)
        }
    }

    private fun scheduleStandbyRecoveryFallback() {
        mainHandler.postDelayed({
            if (resumeStandbyAfterCommand && !isAssistantSpeaking && isServiceRunning) {
                restoreStandbyIfNeeded()
            }
        }, 1200L)
    }

    private fun executeBackgroundAiQuery(cleanPrompt: String) {
        val repo = repository ?: return
        val brain = aiBrain ?: return
        backgroundAiJob?.cancel()
        updateNotification("Thinking: \"$cleanPrompt\"")

        repo.addMessage(
            ChatMessage(
                sessionId = repo.currentSessionId.value,
                text = cleanPrompt,
                isFromUser = true,
                assistantMode = repo.assistantMode.value
            )
        )

        backgroundAiJob = serviceScope.launch {
            acquireShortWakeLock()
            try {
                val recentPairs = repo.messages.value
                    .filter { it.sessionId == repo.currentSessionId.value }
                    .takeLast(6)
                    .map { it.text to it.isFromUser }

                val aiResult = brain.generateResponse(
                    prompt = cleanPrompt,
                    config = repo.aiConfig.value,
                    language = repo.selectedLanguage.value,
                    recentHistory = recentPairs,
                    assistantMode = if (repo.assistantMode.value == AssistantMode.OFFICE) AssistantMode.OFFICE else AssistantMode.ONLINE
                )

                withContext(Dispatchers.Main) {
                    repo.addMessage(
                        ChatMessage(
                            sessionId = repo.currentSessionId.value,
                            text = aiResult.text,
                            isFromUser = false,
                            freshness = InformationFreshness.AI_INTERPRETATION,
                            latencyMs = aiResult.latencyMs,
                            emotion = aiResult.emotion,
                            assistantMode = repo.assistantMode.value
                        )
                    )
                    updateNotification(aiResult.text.take(90))
                    textToSpeech?.speak(aiResult.text, aiResult.emotion)
                    if (resumeStandbyAfterCommand) {
                        scheduleStandbyRecoveryFallback()
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Background AI query failed", e)
                withContext(Dispatchers.Main) {
                    val fallback = "Unable to reach cloud AI right now. Offline commands remain ready."
                    updateNotification(fallback)
                    textToSpeech?.speak(fallback, AssistantEmotion.SERIOUSNESS)
                    if (resumeStandbyAfterCommand) {
                        restoreStandbyIfNeeded()
                    }
                }
            } finally {
                releaseShortWakeLock()
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireShortWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (!wl.isHeld) {
                    wl.acquire(WAKELOCK_TIMEOUT_MS)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wakeLock", e)
        }
    }

    private fun releaseShortWakeLock() {
        try {
            wakeLock?.let { wl ->
                if (wl.isHeld) {
                    wl.release()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release wakeLock", e)
        }
    }

    private fun isDeviceInDoze(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return try {
            pm.isDeviceIdleMode
        } catch (_: Exception) {
            false
        }
    }

    private fun registerScreenStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOff = true
                        // Keep single continuous AudioRecord stream alive across screen-off whenever
                        // Android/OEM permits (only stops if Doze activates or OS mutes hardware audio).
                        if (isServiceRunning && isWakeStandbyActive) {
                            val wakeLabel = repository?.wakeWordOption?.value?.label ?: "Hey Siri"
                            if (alwaysOnWakeEngine?.isListening() == true) {
                                updateNotification("Screen Off Always-On • Say \"$wakeLabel\"")
                            }
                        }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        isScreenOff = false
                        val wasPausedByLock = pausedDueToScreenLock
                        pausedDueToScreenLock = false
                        if (isServiceRunning && !pausedForForegroundUi && !pausedDueToAudioInterruption && !isDeviceInDoze()) {
                            pausedDueToDeviceIdle = false
                            if (wasPausedByLock || ((isWakeStandbyActive || repository?.backgroundWakeStandbyEnabled?.value == true) && alwaysOnWakeEngine?.isListening() != true)) {
                                consecutiveErrorCount = 0
                                consecutiveIdleCycles = 0
                                startWakeWordStandbyMode()
                            } else if (isWakeStandbyActive) {
                                val wakeLabel = repository?.wakeWordOption?.value?.label ?: "Hey Siri"
                                updateNotification("Always-On $wakeLabel • Say \"$wakeLabel\"")
                            }
                        }
                    }
                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        val inDoze = isDeviceInDoze()
                        pausedDueToDeviceIdle = inDoze
                        if (inDoze && isServiceRunning && isWakeStandbyActive) {
                            cancelWakeStandbyRestart()
                            alwaysOnWakeEngine?.stop()
                            isOurRecognizerRecording = false
                            speechRecognizer?.cancel()
                            updateNotification("Doze battery saver • Resumes when active")
                        } else if (!inDoze && isServiceRunning && !pausedForForegroundUi && !pausedDueToAudioInterruption && (isWakeStandbyActive || repository?.backgroundWakeStandbyEnabled?.value == true)) {
                            consecutiveErrorCount = 0
                            consecutiveIdleCycles = 0
                            startWakeWordStandbyMode()
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
        }
        try {
            ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            screenStateReceiver = receiver
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register screen state receiver", e)
        }
    }

    private fun registerAudioRecordingMonitor() {
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) {
                    mainHandler.post {
                        if (!isServiceRunning) return@post
                        val callOrMicBusy = isVoiceCallOrExternalMicBusy(configs)
                        if (callOrMicBusy && !pausedDueToAudioInterruption) {
                            pausedDueToAudioInterruption = true
                            cancelWakeStandbyRestart()
                            alwaysOnWakeEngine?.stop()
                            isOurRecognizerRecording = false
                            speechRecognizer?.cancel()
                            updateNotification("Paused during call / external mic use")
                        } else if (!callOrMicBusy && pausedDueToAudioInterruption) {
                            pausedDueToAudioInterruption = false
                            if (!pausedForForegroundUi && !pausedDueToScreenLock && !pausedDueToDeviceIdle && isWakeStandbyActive) {
                                consecutiveErrorCount = 0
                                consecutiveIdleCycles = 0
                                startWakeWordStandbyMode()
                            }
                        }
                    }
                }
            }
            try {
                audioManager?.registerAudioRecordingCallback(callback, mainHandler)
                audioRecordingCallback = callback
            } catch (e: Exception) {
                Log.w(TAG, "AudioRecordingCallback unavailable", e)
            }
        }
    }

    private fun isVoiceCallOrExternalMicBusy(configs: List<AudioRecordingConfiguration>? = null): Boolean {
        val am = audioManager ?: return false
        val modeBusy = try {
            val mode = am.mode
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
        } catch (_: Exception) {
            false
        }
        if (modeBusy) return true

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val activeConfigs = try {
                configs ?: am.activeRecordingConfigurations
            } catch (_: Exception) {
                null
            }
            if (!activeConfigs.isNullOrEmpty()) {
                val externalActive = if (isOurRecognizerRecording) {
                    activeConfigs.size > 1
                } else {
                    activeConfigs.isNotEmpty()
                }
                if (externalActive) return true
            }
        }
        return false
    }

    private fun updateNotification(statusText: String) {
        if (!isServiceRunning) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
    }

    private fun buildServicePendingIntent(requestCode: Int, intent: Intent, mutable: Boolean = false): PendingIntent {
        val baseFlags = if (mutable) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && intent.action != ACTION_STOP) {
            PendingIntent.getForegroundService(this, requestCode, intent, baseFlags)
        } else {
            PendingIntent.getService(this, requestCode, intent, baseFlags)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            this.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val bgListenIntent = Intent(this, SiriVoiceService::class.java).apply {
            action = ACTION_BACKGROUND_LISTEN
        }
        val bgListenPendingIntent = buildServicePendingIntent(3, bgListenIntent)

        val wakeToggleIntent = Intent(this, SiriVoiceService::class.java).apply {
            action = if (isWakeStandbyActive) ACTION_STOP_WAKE_STANDBY else ACTION_START_WAKE_STANDBY
        }
        val wakeTogglePendingIntent = buildServicePendingIntent(5, wakeToggleIntent)

        val remoteInput = RemoteInput.Builder(KEY_REMOTE_COMMAND_TEXT)
            .setLabel("Say or type e.g. 'YouTube kholo'")
            .build()
        val remoteCommandIntent = Intent(this, SiriVoiceService::class.java).apply {
            action = ACTION_REMOTE_COMMAND
        }
        val remotePendingIntent = buildServicePendingIntent(4, remoteCommandIntent, mutable = true)
        val remoteAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Command",
            remotePendingIntent
        ).addRemoteInput(remoteInput).build()

        val stopIntent = Intent(this, SiriVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = buildServicePendingIntent(1, stopIntent)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Siri AI Assistant")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(android.R.drawable.ic_btn_speak_now, "Listen", bgListenPendingIntent)
            .addAction(
                android.R.drawable.ic_btn_speak_now,
                if (isWakeStandbyActive) "Pause Wake" else "Hey Siri",
                wakeTogglePendingIntent
            )
            .addAction(remoteAction)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeInstance === this) {
            activeInstance = null
        }
        isServiceRunning = false
        isWakeStandbyActive = false
        awaitingWakeFollowUp = false
        resumeStandbyAfterCommand = false
        isOurRecognizerRecording = false
        cancelBackgroundTimeout()
        cancelWakeStandbyRestart()
        backgroundAiJob?.cancel()
        serviceScope.cancel()
        releaseShortWakeLock()

        screenStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) {
            }
        }
        screenStateReceiver = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            audioRecordingCallback?.let {
                try {
                    audioManager?.unregisterAudioRecordingCallback(it)
                } catch (_: Exception) {
                }
            }
            audioRecordingCallback = null
        }

        deviceController?.release()
        alwaysOnWakeEngine?.stop()
        alwaysOnWakeEngine = null
        speechRecognizer?.destroy()
        textToSpeech?.shutdown()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Siri AI Voice Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows status of Siri AI voice session and background command controls"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
