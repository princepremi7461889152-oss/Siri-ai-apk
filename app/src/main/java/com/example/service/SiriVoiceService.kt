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
import com.example.voice.SiriSpeechRecognizer
import com.example.voice.SiriTextToSpeech
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
        const val KEY_REMOTE_COMMAND_TEXT = "KEY_REMOTE_COMMAND_TEXT"

        private const val BACKGROUND_LISTEN_TIMEOUT_MS = 12_000L
        private const val STANDBY_RESTART_DELAY_MS = 280L
        private const val MAX_CONSECUTIVE_STANDBY_ERRORS = 10
        private const val WAKELOCK_TIMEOUT_MS = 15_000L

        private val _isServiceRunningFlow = MutableStateFlow(false)
        val isServiceRunningFlow: StateFlow<Boolean> = _isServiceRunningFlow.asStateFlow()

        private val _isWakeStandbyActiveFlow = MutableStateFlow(false)
        val isWakeStandbyActiveFlow: StateFlow<Boolean> = _isWakeStandbyActiveFlow.asStateFlow()

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
         * - Catches Android 12-15+ [ForegroundServiceStartNotAllowedException] safely.
         */
        fun start(context: Context, enableWakeStandby: Boolean = false): Boolean {
            if (!PermissionManager.isGranted(context, Manifest.permission.RECORD_AUDIO)) {
                Log.w(TAG, "Cannot start SiriVoiceService: RECORD_AUDIO not granted")
                return false
            }

            if (isServiceRunning && isWakeStandbyActive == enableWakeStandby) {
                return true
            }

            val intent = Intent(context, SiriVoiceService::class.java).apply {
                action = if (enableWakeStandby) ACTION_START_WAKE_STANDBY else ACTION_START
                putExtra(EXTRA_WAKE_STANDBY, enableWakeStandby)
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
            try {
                val intent = Intent(context, SiriVoiceService::class.java).apply {
                    action = ACTION_PAUSE_FOR_UI
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "pauseForForegroundUi failed", e)
            }
        }

        fun resumeAfterForegroundUi(context: Context) {
            if (!isServiceRunning) return
            try {
                val intent = Intent(context, SiriVoiceService::class.java).apply {
                    action = ACTION_RESUME_AFTER_UI
                }
                context.startService(intent)
            } catch (e: Exception) {
                Log.w(TAG, "resumeAfterForegroundUi failed", e)
            }
        }

        fun stop(context: Context) {
            SiriRepository(context.applicationContext).setBackgroundServiceEnabled(false)
            SiriRepository(context.applicationContext).setBackgroundWakeStandbyEnabled(false)
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
    private var isScreenOff = false

    @Volatile
    private var consecutiveErrorCount = 0

    override fun onCreate() {
        super.onCreate()
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

        textToSpeech = SiriTextToSpeech(applicationContext) { speaking ->
            isAssistantSpeaking = speaking
            if (!speaking) {
                wakeWordDetector.markAssistantFinishedSpeaking()
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
                consecutiveErrorCount = 0
                cancelBackgroundTimeout()
                handleBackgroundCommand(recognizedText)
            },
            onPartialResult = {},
            onRmsDbChanged = {},
            onRawRmsSample = { level, isSpeechActive ->
                wakeWordDetector.onRmsSample(level, isSpeechActive)
            },
            onDetailedResult = { matches, confidences ->
                consecutiveErrorCount = 0
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
                if (shouldResumeStandbyNow()) {
                    val delay = computeStandbyBackoffDelay(isError = false)
                    scheduleWakeStandbyRestart(delay)
                }
            },
            onNoSpeechDetected = {
                cancelBackgroundTimeout()
                if (resumeStandbyAfterCommand) {
                    restoreStandbyIfNeeded()
                } else {
                    updateNotification(currentIdleNotificationText())
                }
            },
            onErrorOccurred = { err ->
                cancelBackgroundTimeout()
                handleSpeechErrorOccurred(err)
            },
            onErrorCode = { code, _ ->
                if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    consecutiveErrorCount = MAX_CONSECUTIVE_STANDBY_ERRORS
                } else if (code != SpeechRecognizer.ERROR_NO_MATCH && code != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                    consecutiveErrorCount = (consecutiveErrorCount + 1).coerceAtMost(MAX_CONSECUTIVE_STANDBY_ERRORS + 2)
                }
            },
            onListeningStateChanged = { listening ->
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

    private fun handleSpeechErrorOccurred(err: String) {
        if (!hasRecordAudioPermission()) {
            isWakeStandbyActive = false
            pausedDueToScreenLock = false
            updateNotification("Microphone permission required for voice listening")
            return
        }

        if (isScreenOff && isWakeStandbyActive) {
            // Android / OEM blocked mic while screen is locked — pause gracefully & recover automatically on unlock
            pausedDueToScreenLock = true
            cancelWakeStandbyRestart()
            updateNotification("Screen locked • Mic paused; unlock or tap 'Listen'")
            return
        }

        if (isVoiceCallOrExternalMicBusy()) {
            pausedDueToAudioInterruption = true
            cancelWakeStandbyRestart()
            updateNotification("Paused during call / external audio • Will resume")
            return
        }

        if (isWakeStandbyActive && isServiceRunning && !isAssistantSpeaking && !pausedForForegroundUi) {
            if (consecutiveErrorCount >= MAX_CONSECUTIVE_STANDBY_ERRORS) {
                isWakeStandbyActive = false
                updateNotification("Standby paused after mic errors • Tap 'Hey Siri' to retry")
            } else {
                scheduleWakeStandbyRestart(computeStandbyBackoffDelay(isError = true))
            }
        } else if (resumeStandbyAfterCommand) {
            restoreStandbyIfNeeded()
        } else {
            updateNotification("Background Voice Ready • $err")
        }
    }

    private fun computeStandbyBackoffDelay(isError: Boolean): Long {
        if (!isError || consecutiveErrorCount <= 1) return STANDBY_RESTART_DELAY_MS
        val exp = 1L shl (consecutiveErrorCount - 1).coerceAtMost(5)
        return (450L * exp).coerceAtMost(15_000L)
    }

    private fun shouldResumeStandbyNow(): Boolean {
        return isServiceRunning &&
            (isWakeStandbyActive || resumeStandbyAfterCommand) &&
            !isAssistantSpeaking &&
            !pausedForForegroundUi &&
            !pausedDueToAudioInterruption &&
            !pausedDueToScreenLock &&
            hasRecordAudioPermission()
    }

    private fun restoreStandbyIfNeeded() {
        if (!isServiceRunning) return
        if (resumeStandbyAfterCommand) {
            resumeStandbyAfterCommand = false
            isWakeStandbyActive = true
        }
        if (isWakeStandbyActive && !pausedForForegroundUi && !pausedDueToAudioInterruption && !pausedDueToScreenLock) {
            updateNotification("Hey Siri Standby Active • Say \"Hey Siri\"")
            scheduleWakeStandbyRestart(STANDBY_RESTART_DELAY_MS)
        } else {
            updateNotification(currentIdleNotificationText())
        }
    }

    private fun currentIdleNotificationText(): String {
        return when {
            pausedDueToAudioInterruption -> "Paused during call / external mic use"
            pausedDueToScreenLock -> "Screen locked • Unlock or tap 'Command'"
            isWakeStandbyActive -> "Hey Siri Standby Active • Say \"Hey Siri\""
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
            if (!ensureForegroundStarted()) {
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
                repository?.setBackgroundServiceEnabled(false)
                repository?.setBackgroundWakeStandbyEnabled(false)
                isServiceRunning = false
                isWakeStandbyActive = false
                awaitingWakeFollowUp = false
                resumeStandbyAfterCommand = false
                pausedForForegroundUi = false
                pausedDueToAudioInterruption = false
                pausedDueToScreenLock = false
                cancelBackgroundTimeout()
                cancelWakeStandbyRestart()
                backgroundAiJob?.cancel()
                speechRecognizer?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
                    cancelWakeStandbyRestart()
                    startBackgroundSpeechCapture()
                }
                return START_STICKY
            }
            ACTION_START_WAKE_STANDBY -> {
                if (ensureForegroundStarted()) {
                    repository?.setBackgroundServiceEnabled(true)
                    repository?.setBackgroundWakeStandbyEnabled(true)
                    pausedForForegroundUi = false
                    pausedDueToScreenLock = false
                    startWakeWordStandbyMode()
                }
                return START_STICKY
            }
            ACTION_STOP_WAKE_STANDBY -> {
                repository?.setBackgroundWakeStandbyEnabled(false)
                isWakeStandbyActive = false
                awaitingWakeFollowUp = false
                resumeStandbyAfterCommand = false
                pausedDueToScreenLock = false
                cancelWakeStandbyRestart()
                speechRecognizer?.cancel()
                updateNotification("Background Voice Active • Tap 'Listen' or 'Hey Siri'")
                return START_STICKY
            }
            ACTION_PAUSE_FOR_UI -> {
                pausedForForegroundUi = true
                cancelWakeStandbyRestart()
                cancelBackgroundTimeout()
                speechRecognizer?.cancel()
                updateNotification("Siri Foreground Active • Mic handed to app")
                return START_STICKY
            }
            ACTION_RESUME_AFTER_UI -> {
                pausedForForegroundUi = false
                if (!isServiceRunning || !hasRecordAudioPermission()) {
                    return START_STICKY
                }
                val wantWake = isWakeStandbyActive || (repository?.backgroundWakeStandbyEnabled?.value == true)
                if (wantWake) {
                    startWakeWordStandbyMode()
                } else {
                    updateNotification("Background Voice Active • Tap 'Listen' or 'Command'")
                }
                return START_STICKY
            }
        }

        val startWithWake = intent.getBooleanExtra(EXTRA_WAKE_STANDBY, false)
        if (ensureForegroundStarted()) {
            repository?.setBackgroundServiceEnabled(true)
            if (startWithWake) {
                repository?.setBackgroundWakeStandbyEnabled(true)
                startWakeWordStandbyMode()
            } else if (repository?.backgroundWakeStandbyEnabled?.value == true && !pausedForForegroundUi) {
                startWakeWordStandbyMode()
            } else {
                updateNotification(currentIdleNotificationText())
            }
            return START_STICKY
        }
        return START_NOT_STICKY
    }

    private fun stopSelfSafely() {
        isServiceRunning = false
        isWakeStandbyActive = false
        stopSelf()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureForegroundStarted(): Boolean {
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
            isServiceRunning = false
            isWakeStandbyActive = false
            stopSelf()
            false
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground service", e)
            isServiceRunning = false
            isWakeStandbyActive = false
            stopSelf()
            false
        }
    }

    private fun startWakeWordStandbyMode() {
        if (!hasRecordAudioPermission()) {
            isWakeStandbyActive = false
            updateNotification("Microphone permission required for Hey Siri standby")
            return
        }
        if (isVoiceCallOrExternalMicBusy()) {
            isWakeStandbyActive = true
            pausedDueToAudioInterruption = true
            updateNotification("Paused during call / external mic use")
            return
        }
        consecutiveErrorCount = 0
        isWakeStandbyActive = true
        pausedDueToScreenLock = false
        pausedDueToAudioInterruption = false
        updateNotification("Hey Siri Standby Active • Say \"Hey Siri\"")
        if (!pausedForForegroundUi) {
            scheduleWakeStandbyRestart(120L)
        }
    }

    private fun scheduleWakeStandbyRestart(delayMs: Long) {
        cancelWakeStandbyRestart()
        val runnable = Runnable {
            if (!isServiceRunning || !isWakeStandbyActive || isAssistantSpeaking ||
                pausedForForegroundUi || pausedDueToAudioInterruption || pausedDueToScreenLock ||
                !hasRecordAudioPermission()
            ) {
                return@Runnable
            }
            if (isVoiceCallOrExternalMicBusy()) {
                pausedDueToAudioInterruption = true
                updateNotification("Paused during call / external mic use")
                return@Runnable
            }
            try {
                wakeWordDetector.resetUtteranceMetrics()
                speechRecognizer?.startListening(standbyMode = true)
            } catch (e: Exception) {
                Log.w(TAG, "Wake standby start failed", e)
                consecutiveErrorCount++
                if (consecutiveErrorCount >= MAX_CONSECUTIVE_STANDBY_ERRORS) {
                    isWakeStandbyActive = false
                    updateNotification("Background Voice Ready • Tap 'Listen'")
                } else {
                    scheduleWakeStandbyRestart(computeStandbyBackoffDelay(isError = true))
                }
            }
        }
        standbyRestartRunnable = runnable
        mainHandler.postDelayed(runnable, delayMs)
    }

    private fun cancelWakeStandbyRestart() {
        standbyRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        standbyRestartRunnable = null
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

    private fun registerScreenStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        isScreenOff = true
                        if (isServiceRunning && isWakeStandbyActive) {
                            updateNotification("Screen locked • Standby active where permitted")
                        }
                    }
                    Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> {
                        isScreenOff = false
                        val wasPausedByLock = pausedDueToScreenLock
                        pausedDueToScreenLock = false
                        if (isServiceRunning && !pausedForForegroundUi && !pausedDueToAudioInterruption) {
                            if (wasPausedByLock || isWakeStandbyActive) {
                                consecutiveErrorCount = 0
                                speechRecognizer?.recreateRecognizer()
                                if (isWakeStandbyActive || repository?.backgroundWakeStandbyEnabled?.value == true) {
                                    isWakeStandbyActive = true
                                    updateNotification("Hey Siri Standby Active • Say \"Hey Siri\"")
                                    scheduleWakeStandbyRestart(350L)
                                }
                            }
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
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
                        val callOrMicBusy = isVoiceCallOrExternalMicBusy()
                        if (callOrMicBusy && !pausedDueToAudioInterruption) {
                            pausedDueToAudioInterruption = true
                            cancelWakeStandbyRestart()
                            speechRecognizer?.cancel()
                            updateNotification("Paused during call / external mic use")
                        } else if (!callOrMicBusy && pausedDueToAudioInterruption) {
                            pausedDueToAudioInterruption = false
                            if (!pausedForForegroundUi && !pausedDueToScreenLock && isWakeStandbyActive) {
                                speechRecognizer?.recreateRecognizer()
                                updateNotification("Hey Siri Standby Active • Say \"Hey Siri\"")
                                scheduleWakeStandbyRestart(400L)
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

    private fun isVoiceCallOrExternalMicBusy(): Boolean {
        val am = audioManager ?: return false
        return try {
            val mode = am.mode
            mode == AudioManager.MODE_IN_CALL ||
                mode == AudioManager.MODE_IN_COMMUNICATION ||
                mode == AudioManager.MODE_RINGTONE
        } catch (_: Exception) {
            false
        }
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
        isServiceRunning = false
        isWakeStandbyActive = false
        awaitingWakeFollowUp = false
        resumeStandbyAfterCommand = false
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
