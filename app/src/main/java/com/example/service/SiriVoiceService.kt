package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.ai.OfflineAssistantEngine
import com.example.data.SiriRepository
import com.example.device.AndroidDeviceController
import com.example.intent.SiriIntentParser
import com.example.model.AssistantEmotion
import com.example.model.AssistantMode
import com.example.model.IntentExecutionResult
import com.example.model.ReminderItem
import com.example.model.ReminderRepeat
import com.example.model.WakeWordOption
import com.example.voice.SiriSpeechRecognizer
import com.example.voice.SiriTextToSpeech
import com.example.voice.WakeWordDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreground service enabling background Siri voice, optional "Hey Siri" wake-word standby,
 * and quick notification commands while strictly respecting Android 11-15+ microphone,
 * foreground service, and battery restrictions.
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
        const val EXTRA_TRIGGER_LISTEN = "EXTRA_TRIGGER_LISTEN"
        const val EXTRA_WAKE_STANDBY = "EXTRA_WAKE_STANDBY"
        const val KEY_REMOTE_COMMAND_TEXT = "KEY_REMOTE_COMMAND_TEXT"
        private const val BACKGROUND_LISTEN_TIMEOUT_MS = 12_000L
        private const val STANDBY_RESTART_DELAY_MS = 280L

        private val _isServiceRunningFlow = MutableStateFlow(false)
        val isServiceRunningFlow: StateFlow<Boolean> = _isServiceRunningFlow.asStateFlow()

        @Volatile
        var isServiceRunning = false
            set(value) {
                field = value
                _isServiceRunningFlow.value = value
            }

        @Volatile
        var isWakeStandbyActive = false

        fun start(context: Context, enableWakeStandby: Boolean = false) {
            val intent = Intent(context, SiriVoiceService::class.java).apply {
                action = if (enableWakeStandby) ACTION_START_WAKE_STANDBY else ACTION_START
                putExtra(EXTRA_WAKE_STANDBY, enableWakeStandby)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SiriVoiceService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private var repository: SiriRepository? = null
    private var deviceController: AndroidDeviceController? = null
    private var intentParser: SiriIntentParser? = null
    private var speechRecognizer: SiriSpeechRecognizer? = null
    private var textToSpeech: SiriTextToSpeech? = null
    private val wakeWordDetector = WakeWordDetector()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var timeoutRunnable: Runnable? = null
    private var standbyRestartRunnable: Runnable? = null

    @Volatile
    private var isAssistantSpeaking = false

    @Volatile
    private var awaitingWakeFollowUp = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val repo = SiriRepository(applicationContext)
        val controller = AndroidDeviceController(applicationContext)
        repository = repo
        deviceController = controller
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
                if (awaitingWakeFollowUp) {
                    awaitingWakeFollowUp = false
                    startBackgroundSpeechCapture()
                } else if (isWakeStandbyActive && isServiceRunning) {
                    scheduleWakeStandbyRestart(STANDBY_RESTART_DELAY_MS)
                }
            }
        }

        speechRecognizer = SiriSpeechRecognizer(
            context = applicationContext,
            onResult = { recognizedText ->
                cancelBackgroundTimeout()
                handleBackgroundCommand(recognizedText)
            },
            onPartialResult = {},
            onRmsDbChanged = {},
            onRawRmsSample = { level, isSpeechActive ->
                wakeWordDetector.onRmsSample(level, isSpeechActive)
            },
            onDetailedResult = { matches, confidences ->
                if (isWakeStandbyActive && !awaitingWakeFollowUp) {
                    handleBackgroundStandbyMatches(matches, confidences)
                } else {
                    val top = matches.firstOrNull().orEmpty()
                    if (top.isNotBlank()) {
                        cancelBackgroundTimeout()
                        handleBackgroundCommand(top)
                    }
                }
            },
            onSilentRestartNeeded = {
                if (isWakeStandbyActive && isServiceRunning && !isAssistantSpeaking) {
                    scheduleWakeStandbyRestart(STANDBY_RESTART_DELAY_MS)
                }
            },
            onErrorOccurred = { err ->
                cancelBackgroundTimeout()
                if (isWakeStandbyActive && isServiceRunning && !isAssistantSpeaking) {
                    scheduleWakeStandbyRestart(900L)
                } else {
                    updateNotification("Background Voice Ready • $err")
                }
            },
            onListeningStateChanged = { listening ->
                if (listening && !isWakeStandbyActive) {
                    updateNotification("Listening in background… speak your command")
                }
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                isServiceRunning = false
                isWakeStandbyActive = false
                awaitingWakeFollowUp = false
                cancelBackgroundTimeout()
                cancelWakeStandbyRestart()
                speechRecognizer?.cancel()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REMOTE_COMMAND -> {
                val remoteInput = RemoteInput.getResultsFromIntent(intent)
                val typedCommand = remoteInput?.getCharSequence(KEY_REMOTE_COMMAND_TEXT)?.toString()?.trim()
                if (!typedCommand.isNullOrBlank()) {
                    ensureForegroundStarted()
                    handleBackgroundCommand(typedCommand)
                    return START_STICKY
                }
            }
            ACTION_BACKGROUND_LISTEN -> {
                if (ensureForegroundStarted()) {
                    isWakeStandbyActive = false
                    cancelWakeStandbyRestart()
                    startBackgroundSpeechCapture()
                }
                return START_STICKY
            }
            ACTION_START_WAKE_STANDBY -> {
                if (ensureForegroundStarted()) {
                    startWakeWordStandbyMode()
                }
                return START_STICKY
            }
            ACTION_STOP_WAKE_STANDBY -> {
                isWakeStandbyActive = false
                awaitingWakeFollowUp = false
                cancelWakeStandbyRestart()
                speechRecognizer?.cancel()
                updateNotification("Background Voice Active • Tap 'Listen' or 'Hey Siri'")
                return START_STICKY
            }
        }

        val startWithWake = intent?.getBooleanExtra(EXTRA_WAKE_STANDBY, false) == true ||
            (repository?.wakeWordEnabled?.value == true && hasRecordAudioPermission())
        if (ensureForegroundStarted() && startWithWake && intent?.action == ACTION_START && intent.getBooleanExtra(EXTRA_WAKE_STANDBY, false)) {
            startWakeWordStandbyMode()
        }
        return START_STICKY
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensureForegroundStarted(): Boolean {
        val hasMicPermission = hasRecordAudioPermission()
        val notification = buildNotification(
            if (isWakeStandbyActive) "Hey Siri Standby Active" else "Background Voice Active • Tap 'Listen' or 'Command'"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !hasMicPermission) {
            // Android 14+ throws SecurityException if starting a microphone FGS without RECORD_AUDIO
            Log.w(TAG, "RECORD_AUDIO permission not granted; cannot run microphone foreground service on Android 14+")
            isServiceRunning = false
            isWakeStandbyActive = false
            stopSelf()
            return false
        }

        isServiceRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && hasMicPermission) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } catch (e: SecurityException) {
                Log.w(TAG, "Microphone foreground service restricted by OS", e)
                isServiceRunning = false
                isWakeStandbyActive = false
                stopSelf()
                return false
            } catch (e: Exception) {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return true
    }

    private fun startWakeWordStandbyMode() {
        if (!hasRecordAudioPermission()) {
            isWakeStandbyActive = false
            updateNotification("Microphone permission required for Hey Siri standby")
            return
        }
        isWakeStandbyActive = true
        updateNotification("Hey Siri Standby Active • Say \"Hey Siri\"")
        scheduleWakeStandbyRestart(120L)
    }

    private fun scheduleWakeStandbyRestart(delayMs: Long) {
        cancelWakeStandbyRestart()
        val runnable = Runnable {
            if (!isServiceRunning || !isWakeStandbyActive || isAssistantSpeaking || !hasRecordAudioPermission()) {
                return@Runnable
            }
            try {
                wakeWordDetector.resetUtteranceMetrics()
                speechRecognizer?.startListening(standbyMode = true)
            } catch (e: Exception) {
                Log.w(TAG, "Wake standby start failed", e)
                isWakeStandbyActive = false
                updateNotification("Background Voice Ready • Tap 'Listen'")
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

        mainHandler.post {
            try {
                speechRecognizer?.startListening(standbyMode = false)
                scheduleBackgroundTimeout()
            } catch (e: Exception) {
                Log.w(TAG, "Background speech capture failed; opening Siri", e)
                updateNotification("Tap 'Ask Siri' to speak")
            }
        }
    }

    private fun scheduleBackgroundTimeout() {
        cancelBackgroundTimeout()
        val runnable = Runnable {
            speechRecognizer?.stopListening()
            updateNotification(
                if (isWakeStandbyActive) "Hey Siri Standby Active" else "Background Voice Active • Battery-safe standby"
            )
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
            updateNotification("Background Voice Active • Ready")
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
            updateNotification(result.actionSummary ?: result.feedbackSpeech)
            textToSpeech?.speak(result.feedbackSpeech, result.emotion)
        } else {
            // Forward non-local conversational queries to MainActivity gracefully
            updateNotification("Opening Siri for \"$clean\"")
            textToSpeech?.speak("Opening Siri to answer that.", AssistantEmotion.CURIOSITY)
            val openIntent = Intent(this, MainActivity::class.java).apply {
                this.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            try {
                startActivity(openIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Background activity launch blocked by Android OS", e)
            }
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(statusText))
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
        val bgListenPendingIntent = PendingIntent.getService(
            this,
            3,
            bgListenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val wakeToggleIntent = Intent(this, SiriVoiceService::class.java).apply {
            action = if (isWakeStandbyActive) ACTION_STOP_WAKE_STANDBY else ACTION_START_WAKE_STANDBY
        }
        val wakeTogglePendingIntent = PendingIntent.getService(
            this,
            5,
            wakeToggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val remoteInput = RemoteInput.Builder(KEY_REMOTE_COMMAND_TEXT)
            .setLabel("Say or type e.g. 'YouTube kholo'")
            .build()
        val remoteCommandIntent = Intent(this, SiriVoiceService::class.java).apply {
            action = ACTION_REMOTE_COMMAND
        }
        val remoteFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val remotePendingIntent = PendingIntent.getService(
            this,
            4,
            remoteCommandIntent,
            remoteFlags
        )
        val remoteAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send,
            "Command",
            remotePendingIntent
        ).addRemoteInput(remoteInput).build()

        val stopIntent = Intent(this, SiriVoiceService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Siri AI Assistant")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
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
        cancelBackgroundTimeout()
        cancelWakeStandbyRestart()
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
