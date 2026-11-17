package com.example.viewmodel

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiBrainRepository
import com.example.ai.EmotionEngine
import com.example.ai.OfflineAssistantEngine
import com.example.data.SiriRepository
import com.example.device.AndroidDeviceController
import com.example.device.AppLaunchOutcome
import com.example.device.PermissionManager
import com.example.device.PermissionStatusSnapshot
import com.example.intent.SiriIntentParser
import com.example.model.AppChoiceOption
import com.example.model.AssistantEmotion
import com.example.model.AssistantMode
import com.example.model.AutomationRoutine
import com.example.model.ChatMessage
import com.example.model.ConfirmationActionType
import com.example.model.ContactMatch
import com.example.model.InformationFreshness
import com.example.model.IntentExecutionResult
import com.example.model.LanguageOption
import com.example.model.PendingConfirmation
import com.example.model.ReminderItem
import com.example.model.ReminderRepeat
import com.example.model.VoiceGender
import com.example.model.VoicePersonality
import com.example.model.VoiceState
import com.example.service.SiriNotificationListenerService
import com.example.service.SiriVoiceService
import com.example.voice.SiriSpeechRecognizer
import com.example.voice.SiriTextToSpeech
import com.example.voice.WakeWordDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class SiriViewModel(application: Application) : AndroidViewModel(application) {

    val repository = SiriRepository(application)
    val deviceController = AndroidDeviceController(application)
    val aiBrain = AiBrainRepository(application)
    val wakeWordDetector = WakeWordDetector()

    private val _voiceState = MutableStateFlow(VoiceState.IDLE)
    val voiceState: StateFlow<VoiceState> = _voiceState.asStateFlow()

    private val _currentEmotion = MutableStateFlow(AssistantEmotion.NEUTRAL)
    val currentEmotion: StateFlow<AssistantEmotion> = _currentEmotion.asStateFlow()

    private val _audioLevel = MutableStateFlow(0.1f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()

    private val _transcription = MutableStateFlow("")
    val transcription: StateFlow<String> = _transcription.asStateFlow()

    private val _lastAssistantSpeech = MutableStateFlow("")
    val lastAssistantSpeech: StateFlow<String> = _lastAssistantSpeech.asStateFlow()

    private val _isTorchActive = MutableStateFlow(false)
    val isTorchActive: StateFlow<Boolean> = _isTorchActive.asStateFlow()

    private val _isWakeWordStandby = MutableStateFlow(false)
    val isWakeWordStandby: StateFlow<Boolean> = _isWakeWordStandby.asStateFlow()

    // True only when user just said "Hey Siri" alone and Siri is waiting for their follow-up command
    private var awaitingWakeFollowUpCommand: Boolean = false
    private var standbyRestartJob: Job? = null

    // Focus timer state
    private val _focusTimerRemaining = MutableStateFlow(25 * 60)
    val focusTimerRemaining: StateFlow<Int> = _focusTimerRemaining.asStateFlow()
    private val _isFocusTimerRunning = MutableStateFlow(false)
    val isFocusTimerRunning: StateFlow<Boolean> = _isFocusTimerRunning.asStateFlow()
    private var focusTimerJob: Job? = null

    // Sleep timer state for media playback
    private val _sleepTimerRemaining = MutableStateFlow(0)
    val sleepTimerRemaining: StateFlow<Int> = _sleepTimerRemaining.asStateFlow()
    private var sleepTimerJob: Job? = null

    // Voice session timeout job
    private var sessionTimeoutJob: Job? = null

    // AI generation job for cancellation
    private var aiJob: Job? = null

    // Camera / Image analysis state
    private val _selectedImageUri = MutableStateFlow<Uri?>(null)
    val selectedImageUri: StateFlow<Uri?> = _selectedImageUri.asStateFlow()

    private val _apiConnectionTestResult = MutableStateFlow<Pair<Boolean, String>?>(null)
    val apiConnectionTestResult: StateFlow<Pair<Boolean, String>?> = _apiConnectionTestResult.asStateFlow()
    private val _isTestingApi = MutableStateFlow(false)
    val isTestingApi: StateFlow<Boolean> = _isTestingApi.asStateFlow()

    // Chat history search query
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    // Transient UI toast / notification events
    private val _uiEvents = MutableSharedFlow<String>()
    val uiEvents: SharedFlow<String> = _uiEvents.asSharedFlow()

    // In-app update check state
    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    // App disambiguation options when multiple installed apps match a spoken name
    private val _pendingAppChoices = MutableStateFlow<List<AppChoiceOption>>(emptyList())
    val pendingAppChoices: StateFlow<List<AppChoiceOption>> = _pendingAppChoices.asStateFlow()

    // Contact disambiguation options when duplicate/multiple contacts match a spoken name
    private val _pendingContactChoices = MutableStateFlow<List<ContactMatch>>(emptyList())
    val pendingContactChoices: StateFlow<List<ContactMatch>> = _pendingContactChoices.asStateFlow()

    // Pending confirmation state before sensitive actions (calling, texting, deleting)
    private val _pendingConfirmation = MutableStateFlow<PendingConfirmation?>(null)
    val pendingConfirmation: StateFlow<PendingConfirmation?> = _pendingConfirmation.asStateFlow()

    // Live network & mode state
    private val _isOnline = MutableStateFlow(deviceController.isNetworkAvailable())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    val assistantMode: StateFlow<AssistantMode> = repository.assistantMode

    private val _resolvedMode = MutableStateFlow(computeResolvedMode(repository.assistantMode.value, _isOnline.value))
    val resolvedMode: StateFlow<AssistantMode> = _resolvedMode.asStateFlow()

    val onDeviceAiSupported: Boolean = deviceController.isOnDeviceAiHardwareSupported()

    private val _permissionRequestEvents = Channel<String>(Channel.BUFFERED)
    val permissionRequestEvents: Flow<String> = _permissionRequestEvents.receiveAsFlow()

    private val _permissionStatus = MutableStateFlow(PermissionManager.getSnapshot(application))
    val permissionStatus: StateFlow<PermissionStatusSnapshot> = _permissionStatus.asStateFlow()

    val isBackgroundServiceActive: StateFlow<Boolean> = SiriVoiceService.isServiceRunningFlow

    private var unregisterConnectivityCallback: (() -> Unit)? = null

    private var speechRecognizer: SiriSpeechRecognizer? = null
    private var textToSpeech: SiriTextToSpeech? = null
    private lateinit var intentParser: SiriIntentParser

    init {
        initTts()
        initSpeechRecognizer()
        initIntentParser()
        syncVoiceOfflinePreference()
        refreshPermissionStatus()
        viewModelScope.launch {
            SiriVoiceService.isServiceRunningFlow.collect {
                refreshPermissionStatus()
            }
        }
        unregisterConnectivityCallback = deviceController.observeConnectivity { online: Boolean ->
            _isOnline.value = online
            syncVoiceOfflinePreference()
        }
    }

    fun refreshPermissionStatus() {
        val snapshot = PermissionManager.getSnapshot(getApplication())
        _permissionStatus.value = snapshot

        // Clear denial flag whenever permission is currently granted so later revocation
        // prompts normally instead of falsely assuming permanent denial.
        if (snapshot.hasMicrophone && repository.wasPermissionDeniedBefore(Manifest.permission.RECORD_AUDIO)) {
            repository.setPermissionDeniedBefore(Manifest.permission.RECORD_AUDIO, false)
        }
        if (snapshot.hasContacts && repository.wasPermissionDeniedBefore(Manifest.permission.READ_CONTACTS)) {
            repository.setPermissionDeniedBefore(Manifest.permission.READ_CONTACTS, false)
        }
        if (snapshot.hasCamera && repository.wasPermissionDeniedBefore(Manifest.permission.CAMERA)) {
            repository.setPermissionDeniedBefore(Manifest.permission.CAMERA, false)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            snapshot.hasNotifications &&
            repository.wasPermissionDeniedBefore(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            repository.setPermissionDeniedBefore(Manifest.permission.POST_NOTIFICATIONS, false)
        }

        // Stop active microphone foreground service cleanly if user revoked Microphone in Settings.
        if (!snapshot.hasMicrophone && SiriVoiceService.isServiceRunning) {
            SiriVoiceService.stop(getApplication())
        }
    }

    fun requestPermissionIfNeeded(permission: String) {
        if (PermissionManager.isGranted(getApplication(), permission)) {
            repository.setPermissionDeniedBefore(permission, false)
            refreshPermissionStatus()
            return
        }
        _permissionRequestEvents.trySend(permission)
    }

    /**
     * Prompts for Microphone permission at most once on first launch when not already granted.
     * Subsequent app launches NEVER re-prompt automatically.
     */
    fun runFirstLaunchPermissionCheck() {
        refreshPermissionStatus()
        if (repository.isInitialPermissionSetupDone()) return
        repository.markInitialPermissionSetupDone()
        if (!PermissionManager.isGranted(getApplication(), Manifest.permission.RECORD_AUDIO) &&
            !repository.hasPermissionBeenPrompted(Manifest.permission.RECORD_AUDIO)
        ) {
            _permissionRequestEvents.trySend(Manifest.permission.RECORD_AUDIO)
        }
    }

    fun onPermissionResult(permission: String, granted: Boolean) {
        repository.markPermissionPrompted(permission)
        repository.setPermissionDeniedBefore(permission, !granted)
        refreshPermissionStatus()
        if (granted && permission == Manifest.permission.RECORD_AUDIO &&
            repository.backgroundServiceEnabled.value &&
            !SiriVoiceService.isServiceRunning
        ) {
            SiriVoiceService.start(
                getApplication(),
                enableWakeStandby = repository.backgroundWakeStandbyEnabled.value,
                pauseForUi = true
            )
            refreshPermissionStatus()
        }
    }

    fun onAppForegrounded() {
        refreshPermissionStatus()
        if (SiriVoiceService.isServiceRunning) {
            SiriVoiceService.pauseForForegroundUi(getApplication())
        } else if (repository.backgroundServiceEnabled.value && hasRecordAudioPermission()) {
            SiriVoiceService.start(
                getApplication(),
                enableWakeStandby = repository.backgroundWakeStandbyEnabled.value,
                pauseForUi = true
            )
        }
    }

    /**
     * Pre-promotes the foreground service while the Activity is still transitioning out of foreground (ON_PAUSE),
     * avoiding Android 12-15+ ForegroundServiceStartNotAllowedException inside ON_STOP.
     */
    fun onAppPausing() {
        val wantsStandby = repository.backgroundServiceEnabled.value || _isWakeWordStandby.value
        if (wantsStandby && hasRecordAudioPermission() && !SiriVoiceService.isServiceRunning) {
            SiriVoiceService.start(
                getApplication(),
                enableWakeStandby = repository.backgroundWakeStandbyEnabled.value || _isWakeWordStandby.value,
                pauseForUi = true
            )
        }
    }

    fun onAppBackgrounded() {
        val hadUiWakeStandby = _isWakeWordStandby.value
        if (hadUiWakeStandby) {
            _isWakeWordStandby.value = false
            standbyRestartJob?.cancel()
            repository.setBackgroundWakeStandbyEnabled(true)
        }
        if (_voiceState.value == VoiceState.LISTENING) {
            sessionTimeoutJob?.cancel()
            _voiceState.value = VoiceState.IDLE
            _audioLevel.value = 0.08f
        }
        speechRecognizer?.cancel()

        // Only hand mic standby back to an already-started foreground service; never attempt
        // startForegroundService() from ON_STOP where Android 12/14+ considers the app backgrounded.
        if (SiriVoiceService.isServiceRunning && hasRecordAudioPermission()) {
            SiriVoiceService.resumeAfterForegroundUi(
                getApplication(),
                enableWakeStandby = repository.backgroundWakeStandbyEnabled.value || hadUiWakeStandby
            )
        }
        refreshPermissionStatus()
    }

    fun setBackgroundVoiceServiceEnabled(enable: Boolean) {
        if (enable) {
            if (!hasRecordAudioPermission()) {
                requestPermissionIfNeeded(Manifest.permission.RECORD_AUDIO)
                viewModelScope.launch {
                    _uiEvents.emit("Microphone permission is needed for Background Voice Service.")
                }
                return
            }
            repository.setBackgroundServiceEnabled(true)
            val started = SiriVoiceService.start(
                getApplication(),
                enableWakeStandby = repository.backgroundWakeStandbyEnabled.value
            )
            if (!started) {
                viewModelScope.launch {
                    _uiEvents.emit("Unable to start background service right now. Check Android battery & permissions.")
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                !PermissionManager.isGranted(getApplication(), Manifest.permission.POST_NOTIFICATIONS) &&
                !repository.hasPermissionBeenPrompted(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                requestPermissionIfNeeded(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            SiriVoiceService.stop(getApplication())
        }
        refreshPermissionStatus()
    }

    private fun computeResolvedMode(mode: AssistantMode, online: Boolean): AssistantMode {
        return when (mode) {
            AssistantMode.AUTO -> if (online) AssistantMode.ONLINE else AssistantMode.OFFLINE
            AssistantMode.ONLINE -> AssistantMode.ONLINE
            AssistantMode.OFFLINE -> AssistantMode.OFFLINE
            AssistantMode.OFFICE -> AssistantMode.OFFICE
        }
    }

    private fun syncVoiceOfflinePreference() {
        _resolvedMode.value = computeResolvedMode(repository.assistantMode.value, _isOnline.value)
        val preferOffline = repository.assistantMode.value == AssistantMode.OFFLINE || !_isOnline.value
        textToSpeech?.setPreferOffline(preferOffline)
        speechRecognizer?.setPreferOffline(preferOffline)
    }

    fun setAssistantMode(mode: AssistantMode) {
        repository.updateAssistantMode(mode)
        syncVoiceOfflinePreference()
    }

    fun updateConnectivityStatus(online: Boolean) {
        _isOnline.value = online
        syncVoiceOfflinePreference()
    }

    private fun hasRecordAudioPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            getApplication(),
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun initTts() {
        textToSpeech = SiriTextToSpeech(
            context = getApplication(),
            onSpeechAudioLevel = { level ->
                if (_voiceState.value == VoiceState.SPEAKING) {
                    _audioLevel.value = level
                }
            }
        ) { isSpeaking ->
            if (isSpeaking) {
                _voiceState.value = VoiceState.SPEAKING
            } else {
                wakeWordDetector.markAssistantFinishedSpeaking()
                if (_voiceState.value == VoiceState.SPEAKING) {
                    _audioLevel.value = 0.08f
                    _voiceState.value = VoiceState.IDLE
                    if (awaitingWakeFollowUpCommand) {
                        awaitingWakeFollowUpCommand = false
                        viewModelScope.launch {
                            delay(140)
                            startListening()
                        }
                    } else if (_isWakeWordStandby.value) {
                        scheduleWakeWordStandbyRestart(200L)
                    } else if (repository.continuousListening.value) {
                        viewModelScope.launch {
                            delay(180)
                            if (repository.wakeWordEnabled.value) {
                                startWakeWordStandby()
                            } else {
                                startListening()
                            }
                        }
                    }
                }
            }
        }.apply {
            setPersonality(repository.voicePersonality.value)
            setVoiceGender(repository.voiceGender.value)
            setLanguage(repository.selectedLanguage.value)
            setSpeedMultiplier(repository.customSpeedMultiplier.value)
            setPitchMultiplier(repository.customPitchMultiplier.value)
        }
    }

    private fun initSpeechRecognizer() {
        speechRecognizer = SiriSpeechRecognizer(
            context = getApplication(),
            onResult = { recognizedText ->
                _transcription.value = recognizedText
                handleUserUtterance(recognizedText)
            },
            onPartialResult = { partial ->
                _transcription.value = partial
                if (intentParser.isStopCommand(partial)) {
                    stopAllSpeech()
                }
            },
            onRmsDbChanged = { level ->
                if (_voiceState.value == VoiceState.LISTENING) {
                    _audioLevel.value = level
                }
            },
            onRawRmsSample = { level, isSpeechActive ->
                wakeWordDetector.onRmsSample(level, isSpeechActive)
            },
            onDetailedResult = { matches, confidences ->
                if (_isWakeWordStandby.value) {
                    handleStandbyRecognitionResult(matches, confidences)
                } else {
                    val top = matches.firstOrNull().orEmpty()
                    if (top.isNotBlank()) {
                        _transcription.value = top
                        handleUserUtterance(top)
                    }
                }
            },
            onSilentRestartNeeded = {
                if (_isWakeWordStandby.value && _voiceState.value == VoiceState.IDLE) {
                    scheduleWakeWordStandbyRestart(180L)
                }
            },
            onNoSpeechDetected = { msg ->
                Log.d("SiriVM", "No speech detected: $msg")
                sessionTimeoutJob?.cancel()
                _audioLevel.value = 0.08f
                if (_isWakeWordStandby.value) {
                    scheduleWakeWordStandbyRestart(180L)
                } else {
                    _voiceState.value = VoiceState.IDLE
                    _transcription.value = "Didn't catch that. Tap mic to try again."
                }
            },
            onErrorOccurred = { errorMsg ->
                Log.d("SiriVM", "Speech error: $errorMsg")
                sessionTimeoutJob?.cancel()
                _audioLevel.value = 0.08f
                if (_isWakeWordStandby.value) {
                    _isWakeWordStandby.value = false
                }
                _voiceState.value = VoiceState.IDLE
            },
            onListeningStateChanged = { listening ->
                if (_isWakeWordStandby.value) return@SiriSpeechRecognizer
                if (listening) {
                    _voiceState.value = VoiceState.LISTENING
                    startVoiceSessionTimeout()
                } else if (_voiceState.value == VoiceState.LISTENING) {
                    _voiceState.value = VoiceState.THINKING
                    sessionTimeoutJob?.cancel()
                }
            },
            onSpeechDetected = {
                if (!_isWakeWordStandby.value && _voiceState.value == VoiceState.SPEAKING) {
                    textToSpeech?.stop()
                    _voiceState.value = VoiceState.LISTENING
                }
            }
        ).apply {
            setLanguage(repository.selectedLanguage.value)
        }
    }

    private fun handleStandbyRecognitionResult(matches: List<String>, confidences: FloatArray?) {
        val evaluation = wakeWordDetector.evaluate(
            nBestMatches = matches,
            confidenceScores = confidences,
            wakeWordOption = repository.wakeWordOption.value,
            isAssistantCurrentlySpeaking = (_voiceState.value == VoiceState.SPEAKING)
        )

        if (!evaluation.detected) {
            Log.d("SiriWake", "Rejected background/non-wake speech: ${evaluation.rejectionReason}")
            scheduleWakeWordStandbyRestart(120L)
            return
        }

        // Valid "Hey Siri" wake word detected!
        _isWakeWordStandby.value = false
        standbyRestartJob?.cancel()

        if (evaluation.commandText.isNotBlank()) {
            _transcription.value = evaluation.commandText
            handleUserUtterance(evaluation.commandText)
        } else {
            // Only "Hey Siri" was spoken — acknowledge warmly & listen for the command
            val ack = when (repository.selectedLanguage.value) {
                LanguageOption.HINDI, LanguageOption.HINGLISH ->
                    EmotionEngine.pickNonRepeating("wake_ack_hi", listOf("Haan, main sun rahi hoon.", "Boliye, main ready hoon!"))
                else ->
                    EmotionEngine.pickNonRepeating("wake_ack_en", listOf("Yes? I'm listening.", "Mm-hmm, go ahead!", "Right here—what's up?"))
            }
            _currentEmotion.value = AssistantEmotion.CURIOSITY
            _lastAssistantSpeech.value = ack
            awaitingWakeFollowUpCommand = true
            speakResponse(ack, AssistantEmotion.CURIOSITY)
        }
    }

    private fun scheduleWakeWordStandbyRestart(delayMs: Long) {
        standbyRestartJob?.cancel()
        standbyRestartJob = viewModelScope.launch {
            delay(delayMs)
            if (_isWakeWordStandby.value && _voiceState.value == VoiceState.IDLE) {
                wakeWordDetector.resetUtteranceMetrics()
                speechRecognizer?.startListening(standbyMode = true)
            }
        }
    }

    fun startWakeWordStandby() {
        if (!hasRecordAudioPermission()) {
            _isWakeWordStandby.value = false
            requestPermissionIfNeeded(Manifest.permission.RECORD_AUDIO)
            viewModelScope.launch { _uiEvents.emit("Microphone permission is needed for Hey Siri standby.") }
            return
        }
        if (SiriVoiceService.isServiceRunning) {
            SiriVoiceService.pauseForForegroundUi(getApplication())
        } else if (repository.backgroundServiceEnabled.value) {
            SiriVoiceService.start(
                getApplication(),
                enableWakeStandby = true,
                pauseForUi = true
            )
        }
        stopAllSpeech()
        _isWakeWordStandby.value = true
        wakeWordDetector.resetUtteranceMetrics()
        speechRecognizer?.startListening(standbyMode = true)
    }

    fun stopWakeWordStandby() {
        _isWakeWordStandby.value = false
        repository.setBackgroundWakeStandbyEnabled(false)
        standbyRestartJob?.cancel()
        speechRecognizer?.cancel()
        if (_voiceState.value == VoiceState.LISTENING) {
            _voiceState.value = VoiceState.IDLE
        }
    }

    fun toggleWakeWordStandby() {
        if (_isWakeWordStandby.value) {
            stopWakeWordStandby()
        } else {
            startWakeWordStandby()
        }
    }

    private fun initIntentParser() {
        intentParser = SiriIntentParser(
            deviceController = deviceController,
            onCustomRoutineTrigger = { trigger ->
                val routine = repository.routines.value.firstOrNull {
                    it.isEnabled && trigger.contains(it.triggerPhrase.lowercase())
                }
                if (routine != null) {
                    executeRoutine(routine)
                } else null
            },
            onTaskAddTrigger = { taskTitle ->
                repository.addTask(taskTitle)
                IntentExecutionResult(
                    intentName = "ADD_TASK",
                    parameters = mapOf("title" to taskTitle),
                    feedbackSpeech = EmotionEngine.pickNonRepeating(
                        "task_add",
                        listOf(
                            "Got it—added '$taskTitle' to your tasks.",
                            "Done! '$taskTitle' is now on your task list."
                        )
                    ),
                    success = true,
                    actionSummary = "Added Task: $taskTitle",
                    isReversible = true,
                    undoAction = taskTitle,
                    emotion = AssistantEmotion.HAPPINESS
                )
            },
            onNoteAddTrigger = { noteTitle, noteContent ->
                repository.addNote(noteTitle, noteContent)
                IntentExecutionResult(
                    intentName = "ADD_NOTE",
                    parameters = mapOf("title" to noteTitle, "content" to noteContent),
                    feedbackSpeech = "Saved that note for you: $noteContent",
                    success = true,
                    actionSummary = "Saved Note",
                    isReversible = true,
                    undoAction = noteTitle,
                    emotion = AssistantEmotion.HAPPINESS
                )
            },
            getVoiceShortcuts = { repository.shortcuts.value },
            onDailyBriefingTrigger = { generateDailyBriefing() },
            onUndoTrigger = { performUndo() },
            onVoiceGenderTrigger = { gender ->
                setVoiceGender(gender)
                val speech = when (gender) {
                    VoiceGender.FEMALE -> "Maine voice ko Female Siri voice par set kar diya hai. Kaise madad kar sakti hoon?"
                    VoiceGender.MALE -> "Voice has been set to Male voice."
                    VoiceGender.SYSTEM_DEFAULT -> "Voice reset to system default."
                }
                IntentExecutionResult(
                    intentName = "SET_VOICE_GENDER",
                    parameters = mapOf("gender" to gender.name),
                    feedbackSpeech = speech,
                    success = true,
                    actionSummary = "Changed voice to ${gender.displayName}",
                    emotion = AssistantEmotion.HAPPINESS
                )
            },
            onReminderCreateTrigger = { title, triggerMillis, repeat ->
                createAndScheduleReminder(title, triggerMillis, repeat)
            },
            onReminderEditTrigger = { query, newTitle, newTriggerMillis, newRepeat ->
                editAndRescheduleReminder(query, newTitle, newTriggerMillis, newRepeat)
            },
            onReminderDeleteByIdTrigger = { reminderId ->
                val removed = deleteAndCancelReminder(reminderId)
                val speech = if (removed != null) {
                    "Cancelled reminder '${removed.title}'."
                } else {
                    "Reminder was already removed."
                }
                IntentExecutionResult("CANCEL_REMINDER", emptyMap(), speech, removed != null, "Cancelled reminder", emotion = AssistantEmotion.NEUTRAL)
            },
            onReminderListTrigger = {
                val active = repository.reminders.value.filter { it.isEnabled }
                if (active.isEmpty()) {
                    IntentExecutionResult("LIST_REMINDERS", emptyMap(), "You don't have any active scheduled reminders.", true, "Listed Reminders")
                } else {
                    val fmt = SimpleDateFormat("MMM d h:mm a", Locale.getDefault())
                    val summary = active.take(5).joinToString("; ") { rem ->
                        val rep = if (rem.repeat != ReminderRepeat.NONE) " (${rem.repeat.label})" else ""
                        "${rem.title} at ${fmt.format(Date(rem.triggerTimeMillis))}$rep"
                    }
                    IntentExecutionResult("LIST_REMINDERS", mapOf("count" to active.size.toString()), "Your upcoming reminders: $summary", true, "Listed ${active.size} reminders")
                }
            },
            findReminderByQuery = { q -> repository.findMatchingReminders(q) },
            findNoteByQuery = { q ->
                val lower = q.lowercase().trim()
                repository.notes.value.filter {
                    it.title.lowercase().contains(lower) || it.content.lowercase().contains(lower)
                }
            },
            onNoteDeleteByIdTrigger = { noteId ->
                val note = repository.notes.value.firstOrNull { it.id == noteId }
                repository.deleteNote(noteId)
                IntentExecutionResult("DELETE_NOTE", emptyMap(), "Deleted note '${note?.title ?: "Note"}'.", true, "Deleted Note")
            },
            findTaskByQuery = { q ->
                val lower = q.lowercase().trim()
                repository.tasks.value.filter { it.title.lowercase().contains(lower) }
            },
            onTaskDeleteByIdTrigger = { taskId ->
                val task = repository.tasks.value.firstOrNull { it.id == taskId }
                repository.removeTask(taskId)
                IntentExecutionResult("DELETE_TASK", emptyMap(), "Deleted task '${task?.title ?: "Task"}'.", true, "Deleted Task")
            },
            onClearHistoryTrigger = {
                repository.clearHistory()
                IntentExecutionResult("CLEAR_HISTORY", emptyMap(), "All conversation history has been cleared.", true, "Cleared Chat History")
            },
            isMemoryEnabled = { repository.userPreferences.value.memoryEnabled },
            onMemoryToggleTrigger = { enabled ->
                repository.setMemoryEnabled(enabled)
                val speech = if (enabled) {
                    "Smart memory is now enabled. I can remember preferences you ask me to save."
                } else {
                    "Smart memory has been disabled. I won't save or use personal memories until you re-enable it."
                }
                IntentExecutionResult("MEMORY_TOGGLE", mapOf("enabled" to enabled.toString()), speech, true, if (enabled) "Memory Enabled" else "Memory Disabled")
            },
            onMemorySaveTrigger = { title, content ->
                val item = repository.addMemory(title, content)
                repository.logActionExecution("SAVE_MEMORY", "Saved memory: ${item.title}", true, false)
                IntentExecutionResult("SAVE_MEMORY", mapOf("id" to item.id, "content" to content), "Got it—I've saved that to your memory: $content", true, "Saved Memory", emotion = AssistantEmotion.HAPPINESS)
            },
            onMemoryRetrieveTrigger = { query ->
                val matches = if (query.isBlank()) repository.memories.value else repository.searchMemories(query)
                if (matches.isEmpty()) {
                    val msg = if (query.isBlank()) "You don't have any saved memories yet." else "I don't have a saved memory about '$query'."
                    IntentExecutionResult("RETRIEVE_MEMORY", emptyMap(), msg, false, "No matching memory")
                } else {
                    val joined = matches.take(4).joinToString("; ") { it.content }
                    IntentExecutionResult("RETRIEVE_MEMORY", mapOf("count" to matches.size.toString()), "Here is what I remember: $joined", true, "Retrieved ${matches.size} memories", emotion = AssistantEmotion.CURIOSITY)
                }
            },
            onMemoryUpdateTrigger = { query, newContent ->
                val target = repository.searchMemories(query).firstOrNull()
                if (target == null) {
                    IntentExecutionResult("UPDATE_MEMORY", emptyMap(), "I couldn't find a saved memory matching '$query' to update.", false, "Memory not found")
                } else {
                    repository.updateMemory(target.id, content = newContent)
                    IntentExecutionResult("UPDATE_MEMORY", mapOf("id" to target.id), "Updated your memory '${target.title}' to: $newContent", true, "Updated Memory", emotion = AssistantEmotion.HAPPINESS)
                }
            },
            findMemoryByQuery = { q -> repository.searchMemories(q) },
            onMemoryDeleteByIdTrigger = { memId ->
                val removed = repository.deleteMemory(memId)
                IntentExecutionResult("DELETE_MEMORY", emptyMap(), "Forgot memory '${removed?.title ?: "item"}'.", removed != null, "Deleted Memory")
            },
            onModeChangeTrigger = { newMode ->
                setAssistantMode(newMode)
                val msg = when (newMode) {
                    AssistantMode.OFFLINE -> "Switched to Offline Mode. Device controls, apps, settings, reminders, math, and Office templates are ready without internet."
                    AssistantMode.OFFICE -> "Switched to Office Mode. Ready for emails, note summaries, daily work plans, flashcards, and study help."
                    AssistantMode.ONLINE -> "Switched to Online Mode for live cloud AI, web summaries, and navigation."
                    AssistantMode.AUTO -> "Switched to Auto Mode (${if (_isOnline.value) "currently Online" else "currently Offline"})."
                }
                IntentExecutionResult("CHANGE_MODE", mapOf("mode" to newMode.name), msg, true, "Mode: ${newMode.shortLabel}", emotion = AssistantEmotion.HAPPINESS)
            },
            onOfficeCommandTrigger = { command ->
                val currentOnline = deviceController.isNetworkAvailable()
                val res = OfflineAssistantEngine.tryHandleOfficeCommand(
                    rawText = command,
                    isOnline = currentOnline,
                    isOfficeMode = repository.assistantMode.value == AssistantMode.OFFICE,
                    notes = repository.notes.value,
                    tasks = repository.tasks.value,
                    reminders = repository.reminders.value,
                    userName = repository.userPreferences.value.userName,
                    onAddFlashcard = { q, a, topic -> repository.addFlashcard(q, a, topic) }
                )
                res?.let {
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
    }

    private fun startVoiceSessionTimeout() {
        sessionTimeoutJob?.cancel()
        val timeoutSeconds = repository.userPreferences.value.voiceSessionTimeoutSeconds
        if (timeoutSeconds > 0) {
            sessionTimeoutJob = viewModelScope.launch {
                delay(timeoutSeconds * 1000L)
                if (_voiceState.value == VoiceState.LISTENING) {
                    stopListening()
                    _uiEvents.emit("Listening session timed out to save battery.")
                }
            }
        }
    }

    fun startListening() {
        if (!hasRecordAudioPermission()) {
            _voiceState.value = VoiceState.IDLE
            requestPermissionIfNeeded(Manifest.permission.RECORD_AUDIO)
            viewModelScope.launch { _uiEvents.emit("Microphone permission is required to listen.") }
            return
        }
        if (SiriVoiceService.isServiceRunning) {
            SiriVoiceService.pauseForForegroundUi(getApplication())
        }
        stopWakeWordStandby()
        stopAllSpeech()
        _transcription.value = "Listening..."
        _voiceState.value = VoiceState.LISTENING
        wakeWordDetector.resetUtteranceMetrics()
        speechRecognizer?.startListening(standbyMode = false)
    }

    fun stopListening() {
        sessionTimeoutJob?.cancel()
        _isWakeWordStandby.value = false
        speechRecognizer?.stopListening()
        _audioLevel.value = 0.08f
        _voiceState.value = VoiceState.IDLE
    }

    fun stopAllSpeech() {
        sessionTimeoutJob?.cancel()
        standbyRestartJob?.cancel()
        awaitingWakeFollowUpCommand = false
        aiJob?.cancel()
        textToSpeech?.stop()
        speechRecognizer?.cancel()
        _audioLevel.value = 0.08f
        _voiceState.value = VoiceState.IDLE
    }

    fun setPersonality(personality: VoicePersonality) {
        repository.updatePersonality(personality)
        textToSpeech?.setPersonality(personality)
    }

    fun setVoiceGender(gender: VoiceGender) {
        repository.updateVoiceGender(gender)
        textToSpeech?.setVoiceGender(gender)
    }

    fun setLanguage(language: LanguageOption) {
        repository.updateLanguage(language)
        textToSpeech?.setLanguage(language)
        speechRecognizer?.setLanguage(language)
    }

    fun setSpeedMultiplier(multiplier: Float) {
        repository.setSpeedMultiplier(multiplier)
        textToSpeech?.setSpeedMultiplier(multiplier)
    }

    fun setPitchMultiplier(multiplier: Float) {
        repository.setPitchMultiplier(multiplier)
        textToSpeech?.setPitchMultiplier(multiplier)
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun toggleFlashlight() {
        val next = !_isTorchActive.value
        val (ok, _) = deviceController.setFlashlight(next)
        if (ok) {
            _isTorchActive.value = next
            repository.logActionExecution(
                actionName = if (next) "FLASHLIGHT_ON" else "FLASHLIGHT_OFF",
                summary = if (next) "Turned on Flashlight" else "Turned off Flashlight",
                isSuccess = true,
                isReversible = true
            )
        }
    }

    fun setSelectedImage(uri: Uri?) {
        _selectedImageUri.value = uri
    }

    fun speakText(text: String) {
        if (text.isBlank()) return
        stopAllSpeech()
        _lastAssistantSpeech.value = text
        speakResponse(text, _currentEmotion.value)
    }

    fun previewVoiceSample(text: String) {
        if (text.isBlank()) return
        stopAllSpeech()
        speakResponse(text, AssistantEmotion.HAPPINESS)
    }

    fun saveNoteDraft(title: String, content: String) {
        if (content.isBlank()) return
        repository.addNote(title.ifBlank { "Office Draft" }, content, "Work")
        viewModelScope.launch {
            _uiEvents.emit("Saved draft to Planner Notes")
        }
    }

    fun wipeAllLocalData() {
        repository.reminders.value.forEach { rem ->
            deviceController.reminderScheduler.cancelReminder(rem.id)
        }
        stopAllSpeech()
        repository.clearAllPersonalData()
        syncVoiceOfflinePreference()
    }

    fun copyResponseToClipboard(text: String) {
        val ok = deviceController.copyToClipboard("Siri AI Response", text)
        viewModelScope.launch {
            _uiEvents.emit(if (ok) "Copied response to clipboard" else "Could not copy to clipboard")
        }
    }

    /**
     * Main entry point for user voice or typed input
     */
    fun handleUserUtterance(rawText: String) {
        val cleanText = intentParser.cleanWakePhrase(rawText).trim()
        if (cleanText.isEmpty()) {
            _voiceState.value = VoiceState.IDLE
            return
        }

        // Cancel any pending outdated AI request
        aiJob?.cancel()

        // Detect initial emotion immediately so facial animation responds right away
        val initialEmotion = EmotionEngine.detectEmotion(cleanText)
        _currentEmotion.value = initialEmotion

        val currentImage = _selectedImageUri.value
        val selectedMode = repository.assistantMode.value
        val userMsg = ChatMessage(
            sessionId = repository.currentSessionId.value,
            text = rawText,
            isFromUser = true,
            imageUri = currentImage?.toString(),
            emotion = initialEmotion,
            assistantMode = selectedMode
        )
        repository.addMessage(userMsg)
        _selectedImageUri.value = null

        // Check for immediate STOP command
        if (intentParser.isStopCommand(cleanText)) {
            stopAllSpeech()
            return
        }

        // Check if user asks to read notifications
        if (cleanText.contains("read my notification", ignoreCase = true) ||
            cleanText.contains("check notification", ignoreCase = true) ||
            cleanText.contains("summarize notification", ignoreCase = true)
        ) {
            readNotifications()
            return
        }

        // 1. Try fast local system intent execution (includes App Launcher, Settings, Reminders, Office tools, Mode switching)
        val localIntentResult = intentParser.parseAndExecute(cleanText)
        if (localIntentResult != null) {
            handleIntentResult(localIntentResult)
            return
        }

        // 2. Refresh live connectivity state & check whether Offline Assistant should handle it
        val liveOnline = deviceController.isNetworkAvailable()
        if (_isOnline.value != liveOnline) {
            _isOnline.value = liveOnline
        }
        syncVoiceOfflinePreference()

        val shouldRunOfflineOnly = (selectedMode == AssistantMode.OFFLINE) || !liveOnline
        if (shouldRunOfflineOnly) {
            val prefs = repository.userPreferences.value
            val prefsSummary = "Name: ${prefs.userName}, Language: ${repository.selectedLanguage.value.displayName}, Home: ${prefs.homeLocation}, Work: ${prefs.workLocation}, Memory: ${if (prefs.memoryEnabled) "On" else "Off"}"
            val (offlineText, offlineEmotion) = OfflineAssistantEngine.answerOfflineQuery(
                prompt = cleanText,
                language = repository.selectedLanguage.value,
                memories = if (prefs.memoryEnabled) repository.memories.value else emptyList(),
                userName = prefs.userName,
                onDeviceAiSupported = onDeviceAiSupported,
                userPrefsSummary = prefsSummary
            )
            handleIntentResult(
                IntentExecutionResult(
                    intentName = "OFFLINE_ASSISTANT",
                    feedbackSpeech = offlineText,
                    success = true,
                    actionSummary = if (selectedMode == AssistantMode.OFFLINE) "Offline Mode" else "Offline (No Internet)",
                    emotion = offlineEmotion
                )
            )
            return
        }

        // 3. Stream Gemini/OpenAI AI response with zero-wait sentence-by-sentence TTS
        _voiceState.value = VoiceState.THINKING
        val streamSessionId = "siri_stream_${System.currentTimeMillis()}"
        var anySentenceSpoken = false

        aiJob = viewModelScope.launch {
            try {
                val currentSessionMsgs = repository.messages.value.filter { it.sessionId == repository.currentSessionId.value }
                val recentPairs = currentSessionMsgs.takeLast(8).map { it.text to it.isFromUser }
                val memoryContext = buildMemoryContextPrompt()

                val aiResult = aiBrain.generateResponseStream(
                    prompt = cleanText,
                    config = repository.aiConfig.value,
                    language = repository.selectedLanguage.value,
                    recentHistory = recentPairs,
                    imageUri = currentImage,
                    memoryContext = memoryContext,
                    onEmotionDetected = { emotion ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            _currentEmotion.value = emotion
                        }
                    },
                    onPartialText = { partialText ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            _lastAssistantSpeech.value = partialText
                        }
                    },
                    onSentenceReady = { sentence, isFirst, emotion ->
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            anySentenceSpoken = true
                            _currentEmotion.value = emotion
                            _voiceState.value = VoiceState.SPEAKING
                            textToSpeech?.speakStreamingChunk(
                                sentence = sentence,
                                isFirstChunk = isFirst,
                                isFinalChunk = false,
                                emotion = emotion,
                                streamSessionId = streamSessionId
                            )
                        }
                    },
                    assistantMode = if (selectedMode == AssistantMode.OFFICE) AssistantMode.OFFICE else AssistantMode.ONLINE
                )

                withContext(Dispatchers.Main.immediate) {
                    _currentEmotion.value = aiResult.emotion
                    _lastAssistantSpeech.value = aiResult.text
                    intentParser.updateLastAssistantSpeech(aiResult.text)
                    val assistantMsg = ChatMessage(
                        sessionId = repository.currentSessionId.value,
                        text = aiResult.text,
                        isFromUser = false,
                        freshness = InformationFreshness.AI_INTERPRETATION,
                        latencyMs = aiResult.latencyMs,
                        emotion = aiResult.emotion,
                        assistantMode = repository.assistantMode.value
                    )
                    repository.addMessage(assistantMsg)

                    if (anySentenceSpoken) {
                        textToSpeech?.finishStreamingSession(streamSessionId)
                    } else {
                        speakResponse(aiResult.text, aiResult.emotion)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.e("SiriVM", "AI generation error", e)
                _currentEmotion.value = AssistantEmotion.SADNESS
                _voiceState.value = VoiceState.ERROR
                val errMsg = "Sorry, I had trouble processing that request. Please try again."
                repository.addMessage(
                    ChatMessage(
                        sessionId = repository.currentSessionId.value,
                        text = errMsg,
                        isFromUser = false,
                        isError = true,
                        emotion = AssistantEmotion.SADNESS
                    )
                )
                speakResponse(errMsg, AssistantEmotion.SADNESS)
            }
        }
    }

    private fun handleIntentResult(result: IntentExecutionResult) {
        val effectiveEmotion = if (result.emotion != AssistantEmotion.NEUTRAL) {
            result.emotion
        } else {
            EmotionEngine.detectEmotion("", result.feedbackSpeech)
        }
        _currentEmotion.value = effectiveEmotion
        _lastAssistantSpeech.value = result.feedbackSpeech
        if (result.intentName.startsWith("FLASHLIGHT")) {
            _isTorchActive.value = deviceController.isFlashlightActive()
        }

        val freshness = when {
            result.intentName == "WEB_SEARCH" -> InformationFreshness.WEB_SEARCH
            else -> InformationFreshness.VERIFIED_SYSTEM
        }

        _pendingAppChoices.value = result.appChoices
        _pendingContactChoices.value = result.contactChoices
        _pendingConfirmation.value = if (result.requiresConfirmation) {
            result.pendingConfirmation ?: intentParser.getPendingConfirmation()
        } else {
            null
        }

        val assistantMsg = ChatMessage(
            sessionId = repository.currentSessionId.value,
            text = result.feedbackSpeech,
            isFromUser = false,
            actionType = result.intentName,
            actionSummary = result.actionSummary,
            sources = result.webSources,
            freshness = freshness,
            emotion = effectiveEmotion,
            appChoices = result.appChoices,
            contactChoices = result.contactChoices,
            requiresConfirmation = result.requiresConfirmation,
            assistantMode = repository.assistantMode.value
        )
        repository.addMessage(assistantMsg)
        repository.logActionExecution(result.intentName, result.actionSummary ?: result.feedbackSpeech, result.success, result.isReversible, result.undoAction)
        speakResponse(result.feedbackSpeech, effectiveEmotion)

        if (result.intentName == "CONTACT_PERMISSION_REQUIRED") {
            requestPermissionIfNeeded(Manifest.permission.READ_CONTACTS)
        }

        // When online web search succeeds, fetch a concise spoken summary and read it aloud
        val searchQuery = result.parameters["query"]
        if (result.intentName == "WEB_SEARCH" && result.success && !searchQuery.isNullOrBlank()) {
            fetchAndReadWebSummary(searchQuery)
        }
    }

    private fun fetchAndReadWebSummary(query: String) {
        viewModelScope.launch {
            val summary = aiBrain.fetchWebSummaryForSpeech(query)
            if (!summary.isNullOrBlank()) {
                val spoken = "Here is a quick summary for $query: $summary"
                _lastAssistantSpeech.value = spoken
                intentParser.updateLastAssistantSpeech(spoken)
                repository.addMessage(
                    ChatMessage(
                        sessionId = repository.currentSessionId.value,
                        text = spoken,
                        isFromUser = false,
                        actionType = "WEB_SUMMARY",
                        actionSummary = "Read web summary aloud",
                        freshness = InformationFreshness.WEB_SEARCH,
                        emotion = AssistantEmotion.CURIOSITY
                    )
                )
                speakResponse(spoken, AssistantEmotion.CURIOSITY)
            }
        }
    }

    private fun buildMemoryContextPrompt(): String {
        val prefs = repository.userPreferences.value
        if (!prefs.memoryEnabled) return ""
        val parts = mutableListOf<String>()
        if (prefs.userName.isNotBlank() && prefs.userName != "Friend") {
            parts.add("- User's preferred name: ${prefs.userName}")
        }
        if (prefs.homeLocation.isNotBlank() && prefs.homeLocation != "Home") {
            parts.add("- Home area: ${prefs.homeLocation}")
        }
        if (prefs.workLocation.isNotBlank() && prefs.workLocation != "Office") {
            parts.add("- Work area: ${prefs.workLocation}")
        }
        for (mem in repository.memories.value.take(12)) {
            parts.add("- [${mem.category}] ${mem.title}: ${mem.content}")
        }
        return parts.joinToString("\n")
    }

    fun confirmPendingAction() {
        val res = intentParser.confirmPendingAction() ?: return
        _pendingConfirmation.value = null
        handleIntentResult(res)
    }

    fun cancelPendingAction() {
        val res = intentParser.cancelPendingAction() ?: return
        _pendingConfirmation.value = null
        handleIntentResult(res)
    }

    fun selectContactChoice(contact: ContactMatch) {
        _pendingContactChoices.value = emptyList()
        val res = intentParser.selectPendingContact(contact) ?: return
        handleIntentResult(res)
    }

    fun createAndScheduleReminder(
        title: String,
        triggerTimeMillis: Long,
        repeat: ReminderRepeat = ReminderRepeat.NONE
    ): IntentExecutionResult {
        val item = ReminderItem(
            title = title,
            triggerTimeMillis = triggerTimeMillis,
            repeat = repeat,
            isEnabled = true
        )
        repository.addReminder(item)
        val sched = deviceController.reminderScheduler.scheduleReminder(item)
        val fmt = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(triggerTimeMillis))
        val repeatNote = if (repeat != ReminderRepeat.NONE) " (${repeat.label})" else ""
        val permNote = buildString {
            if (sched.needsExactAlarmPermission) append(" Grant Exact Alarm permission in Privacy settings for exact timing.")
            if (sched.needsNotificationPermission) append(" Grant Notification permission so alerts show.")
        }
        val speech = "Reminder set for $fmt$repeatNote: $title.$permNote"
        repository.logActionExecution("SET_REMINDER", "Set$repeatNote reminder: $title", sched.scheduled, false)
        return IntentExecutionResult(
            intentName = "SET_REMINDER",
            parameters = mapOf("id" to item.id, "title" to title, "repeat" to repeat.name),
            feedbackSpeech = speech,
            success = sched.scheduled,
            actionSummary = "Set$repeatNote reminder: $title",
            emotion = AssistantEmotion.HAPPINESS
        )
    }

    fun editAndRescheduleReminder(
        query: String,
        newTitle: String? = null,
        newTriggerTimeMillis: Long? = null,
        newRepeat: ReminderRepeat? = null
    ): IntentExecutionResult {
        val target = repository.findMatchingReminders(query).firstOrNull()
            ?: repository.reminders.value.firstOrNull()
            ?: return IntentExecutionResult("EDIT_REMINDER", emptyMap(), "I couldn't find any reminder matching '$query' to edit.", false, "Reminder not found")

        val updated = repository.updateReminder(
            id = target.id,
            newTitle = newTitle,
            newTriggerTimeMillis = newTriggerTimeMillis,
            newRepeat = newRepeat
        ) ?: return IntentExecutionResult("EDIT_REMINDER", emptyMap(), "Could not update reminder.", false, "Update failed")

        if (updated.isEnabled) {
            deviceController.reminderScheduler.scheduleReminder(updated)
        }
        val fmt = SimpleDateFormat("EEE, MMM d 'at' h:mm a", Locale.getDefault()).format(Date(updated.triggerTimeMillis))
        return IntentExecutionResult(
            intentName = "EDIT_REMINDER",
            parameters = mapOf("id" to updated.id, "title" to updated.title),
            feedbackSpeech = "Updated reminder '${updated.title}' to $fmt.",
            success = true,
            actionSummary = "Edited reminder: ${updated.title}",
            emotion = AssistantEmotion.HAPPINESS
        )
    }

    fun deleteAndCancelReminder(id: String): ReminderItem? {
        deviceController.reminderScheduler.cancelReminder(id)
        return repository.deleteReminder(id)
    }

    fun toggleAndScheduleReminder(id: String) {
        val updated = repository.toggleReminder(id) ?: return
        if (updated.isEnabled) {
            deviceController.reminderScheduler.scheduleReminder(updated)
        } else {
            deviceController.reminderScheduler.cancelReminder(id)
        }
    }

    fun selectAppChoice(choice: AppChoiceOption) {
        _pendingAppChoices.value = emptyList()
        intentParser.clearPendingAppDisambiguation()
        val outcome = deviceController.launchSpecificApp(choice.packageName, choice.activityName, choice.appName)
        val (ok, speech, emotion) = when (outcome) {
            is AppLaunchOutcome.Launched -> Triple(true, outcome.message, AssistantEmotion.HAPPINESS)
            is AppLaunchOutcome.LaunchFailed -> Triple(false, outcome.message, AssistantEmotion.SERIOUSNESS)
            else -> Triple(false, "Could not open ${choice.appName}.", AssistantEmotion.SERIOUSNESS)
        }
        _currentEmotion.value = emotion
        _lastAssistantSpeech.value = speech
        repository.addMessage(
            ChatMessage(
                sessionId = repository.currentSessionId.value,
                text = speech,
                isFromUser = false,
                actionType = "OPEN_APP",
                actionSummary = if (ok) "Opened ${choice.appName}" else "Could not open ${choice.appName}",
                freshness = InformationFreshness.VERIFIED_SYSTEM,
                emotion = emotion
            )
        )
        repository.logActionExecution("OPEN_APP", "Opened ${choice.appName}", ok)
        speakResponse(speech, emotion)
    }

    private fun speakResponse(text: String, emotion: AssistantEmotion = _currentEmotion.value) {
        _voiceState.value = VoiceState.SPEAKING
        textToSpeech?.speak(text, emotion)
    }

    /**
     * Generates a composite Daily Briefing
     */
    fun triggerDailyBriefing() {
        val res = generateDailyBriefing()
        handleIntentResult(res)
    }

    private fun generateDailyBriefing(): IntentExecutionResult {
        val prefs = repository.userPreferences.value
        val name = prefs.userName
        val date = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault()).format(Date())
        val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
        val batteryInfo = deviceController.getBatteryInfo()
        val networkInfo = deviceController.getNetworkInfo()
        val pendingTasks = repository.tasks.value.filter { !it.isCompleted }
        val highPriority = pendingTasks.filter { it.priority == "High" }

        val taskSummary = if (pendingTasks.isEmpty()) {
            "You have no pending tasks today."
        } else {
            "You have ${pendingTasks.size} pending tasks${if (highPriority.isNotEmpty()) ", including ${highPriority.size} high priority" else ""}."
        }

        val speech = "Good day, $name! Today is $date, and it's $time. $batteryInfo $networkInfo $taskSummary Have a wonderful day!"
        return IntentExecutionResult(
            intentName = "DAILY_BRIEFING",
            feedbackSpeech = speech,
            success = true,
            actionSummary = "Delivered Daily Briefing for $name",
            emotion = AssistantEmotion.HAPPINESS
        )
    }

    /**
     * Performs Undo of the last reversible action
     */
    fun performUndo(): IntentExecutionResult {
        val lastLog = repository.actionLogs.value.firstOrNull { it.isReversible }
        if (lastLog == null) {
            val speech = "There are no recent actions that can be undone."
            return IntentExecutionResult("UNDO", emptyMap(), speech, false, "Nothing to undo", emotion = AssistantEmotion.SERIOUSNESS)
        }

        val result = when (lastLog.actionName) {
            "FLASHLIGHT_ON" -> {
                val (ok, _) = deviceController.setFlashlight(false)
                _isTorchActive.value = false
                IntentExecutionResult("UNDO_FLASHLIGHT", emptyMap(), "Turned off flashlight.", ok, "Undid Flashlight ON", emotion = AssistantEmotion.NEUTRAL)
            }
            "FLASHLIGHT_OFF" -> {
                val (ok, _) = deviceController.setFlashlight(true)
                _isTorchActive.value = true
                IntentExecutionResult("UNDO_FLASHLIGHT", emptyMap(), "Turned flashlight back on.", ok, "Undid Flashlight OFF", emotion = AssistantEmotion.NEUTRAL)
            }
            "ADD_TASK" -> {
                val title = lastLog.undoPayload
                if (title != null) {
                    val task = repository.tasks.value.firstOrNull { it.title == title }
                    if (task != null) {
                        repository.removeTask(task.id)
                    }
                }
                IntentExecutionResult("UNDO_TASK", emptyMap(), "Removed the task you just added.", true, "Undid Task addition", emotion = AssistantEmotion.NEUTRAL)
            }
            "ADD_NOTE" -> {
                val title = lastLog.undoPayload
                if (title != null) {
                    val note = repository.notes.value.firstOrNull { it.title == title }
                    if (note != null) {
                        repository.deleteNote(note.id)
                    }
                }
                IntentExecutionResult("UNDO_NOTE", emptyMap(), "Removed the note you just saved.", true, "Undid Note creation", emotion = AssistantEmotion.NEUTRAL)
            }
            else -> {
                IntentExecutionResult("UNDO", emptyMap(), "Action cannot be undone automatically.", false, "Undo unsupported", emotion = AssistantEmotion.SERIOUSNESS)
            }
        }
        if (result.success) {
            repository.markActionUndone(lastLog.id)
        }
        return result
    }

    /**
     * Executes custom multi-step routine
     */
    fun executeRoutine(routine: AutomationRoutine): IntentExecutionResult {
        viewModelScope.launch {
            for (step in routine.steps) {
                when (step.actionType) {
                    "TIMER" -> {
                        val secs = step.parameter.toIntOrNull() ?: 300
                        deviceController.setTimer(secs, routine.name)
                    }
                    "TORCH" -> {
                        val enable = step.parameter.toBoolean()
                        deviceController.setFlashlight(enable)
                        _isTorchActive.value = enable
                    }
                    "VOLUME" -> {
                        deviceController.adjustVolume(step.parameter)
                    }
                    "ALARM" -> {
                        deviceController.setAlarm(7, 0, routine.name)
                    }
                    "INFO_BATTERY" -> {
                        deviceController.getBatteryInfo()
                    }
                    "INFO_NETWORK" -> {
                        deviceController.getNetworkInfo()
                    }
                    "APP" -> {
                        deviceController.openApp(step.parameter)
                    }
                    "SPEAK" -> {
                        speakResponse(step.parameter, AssistantEmotion.HAPPINESS)
                    }
                }
                delay(350)
            }
        }
        val feedback = "Running '${routine.name}' routine with ${routine.steps.size} steps."
        return IntentExecutionResult(
            intentName = "AUTOMATION_ROUTINE",
            parameters = mapOf("routine" to routine.name),
            feedbackSpeech = feedback,
            success = true,
            actionSummary = "Executed ${routine.steps.size} automation steps",
            emotion = AssistantEmotion.EXCITEMENT
        )
    }

    private fun isWithinQuietHours(startHHmm: String, endHHmm: String): Boolean {
        return try {
            val sParts = startHHmm.split(":")
            val eParts = endHHmm.split(":")
            val startMin = sParts[0].toInt() * 60 + sParts[1].toInt()
            val endMin = eParts[0].toInt() * 60 + eParts[1].toInt()
            val cal = Calendar.getInstance()
            val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            if (startMin <= endMin) {
                nowMin in startMin until endMin
            } else {
                nowMin >= startMin || nowMin < endMin
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun readNotifications() {
        val prefs = repository.userPreferences.value
        val myPkg = getApplication<Application>().packageName
        val sensitiveKeywords = listOf("otp", "one time password", "verification code", "pin", "cvv", "password", "passcode", "2fa")
        val notifications = SiriNotificationListenerService.getRecentNotifications()
            .filter { it.packageName != myPkg }
        val quietNow = prefs.quietHoursEnabled && isWithinQuietHours(prefs.quietHoursStart, prefs.quietHoursEnd)

        val speech = if (notifications.isEmpty()) {
            "You have no new notifications captured, or notification listener permission is not enabled yet."
        } else {
            val summary = notifications.take(3).joinToString("; ") { notif ->
                val combined = "${notif.title} ${notif.text}".lowercase()
                val isSensitive = prefs.filterSensitiveNotifications && sensitiveKeywords.any { combined.contains(it) }
                if (isSensitive) {
                    "${notif.title.ifBlank { "Security Alert" }}: Sensitive OTP/code hidden by Privacy filter"
                } else {
                    "${notif.title}: ${notif.text}"
                }
            }
            if (quietNow) {
                "Quiet hours (${prefs.quietHoursStart} to ${prefs.quietHoursEnd}) are active, so voice readout is muted. Notifications: $summary"
            } else {
                "Here are your latest notifications: $summary"
            }
        }
        val emotion = if (notifications.isEmpty()) AssistantEmotion.SERIOUSNESS else AssistantEmotion.CURIOSITY
        _currentEmotion.value = emotion
        _lastAssistantSpeech.value = speech
        val msg = ChatMessage(
            sessionId = repository.currentSessionId.value,
            text = speech,
            isFromUser = false,
            actionType = "READ_NOTIFICATIONS",
            actionSummary = "Checked ${notifications.size} notifications",
            freshness = InformationFreshness.VERIFIED_SYSTEM,
            emotion = emotion
        )
        repository.addMessage(msg)
        if (!quietNow) {
            speakResponse(speech, emotion)
        }
    }

    // --- Focus Timer ---
    fun toggleFocusTimer() {
        if (_isFocusTimerRunning.value) {
            pauseFocusTimer()
        } else {
            startFocusTimer()
        }
    }

    fun startFocusTimer() {
        _isFocusTimerRunning.value = true
        focusTimerJob?.cancel()
        focusTimerJob = viewModelScope.launch {
            while (_focusTimerRemaining.value > 0 && _isFocusTimerRunning.value) {
                delay(1000)
                _focusTimerRemaining.value -= 1
            }
            if (_focusTimerRemaining.value == 0) {
                _isFocusTimerRunning.value = false
                _currentEmotion.value = AssistantEmotion.EXCITEMENT
                speakResponse("Focus study session complete! Great job—take a 5-minute break.", AssistantEmotion.EXCITEMENT)
            }
        }
    }

    fun pauseFocusTimer() {
        _isFocusTimerRunning.value = false
        focusTimerJob?.cancel()
    }

    fun resetFocusTimer(minutes: Int = 25) {
        pauseFocusTimer()
        _focusTimerRemaining.value = minutes * 60
    }

    // --- Sleep Timer for Media ---
    fun startSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _sleepTimerRemaining.value = minutes * 60
        sleepTimerJob = viewModelScope.launch {
            while (_sleepTimerRemaining.value > 0) {
                delay(1000)
                _sleepTimerRemaining.value -= 1
            }
            deviceController.controlMediaPlayback("PAUSE")
            deviceController.adjustVolume("mute")
            _currentEmotion.value = AssistantEmotion.NEUTRAL
            speakResponse("Sleep timer finished. Media playback paused.", AssistantEmotion.NEUTRAL)
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _sleepTimerRemaining.value = 0
    }

    // --- In-App Update Verification ---
    fun checkForUpdates() {
        _showUpdateDialog.value = true
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    // --- API Configuration Test ---
    fun testApiConnection() {
        _isTestingApi.value = true
        _apiConnectionTestResult.value = null
        viewModelScope.launch {
            val result = aiBrain.testConnection(repository.aiConfig.value)
            _apiConnectionTestResult.value = result
            _isTestingApi.value = false
        }
    }

    override fun onCleared() {
        super.onCleared()
        unregisterConnectivityCallback?.invoke()
        deviceController.release()
        textToSpeech?.shutdown()
        speechRecognizer?.destroy()
        focusTimerJob?.cancel()
        sleepTimerJob?.cancel()
        sessionTimeoutJob?.cancel()
        standbyRestartJob?.cancel()
        aiJob?.cancel()
    }
}
