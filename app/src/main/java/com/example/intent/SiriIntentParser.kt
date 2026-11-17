package com.example.intent

import android.provider.Settings
import com.example.ai.EmotionEngine
import com.example.device.AndroidDeviceController
import com.example.device.AppLaunchOutcome
import com.example.device.ContactSearchOutcome
import com.example.model.AppChoiceOption
import com.example.model.AssistantEmotion
import com.example.model.AssistantMode
import com.example.model.ConfirmationActionType
import com.example.model.ContactMatch
import com.example.model.IntentExecutionResult
import com.example.model.MemoryItem
import com.example.model.NoteItem
import com.example.model.PendingConfirmation
import com.example.model.ReminderItem
import com.example.model.ReminderRepeat
import com.example.model.TaskItem
import com.example.model.VoiceGender
import com.example.model.VoiceShortcut
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SiriIntentParser(
    private val deviceController: AndroidDeviceController,
    private val onCustomRoutineTrigger: ((routineTrigger: String) -> IntentExecutionResult?)? = null,
    private val onTaskAddTrigger: ((taskTitle: String) -> IntentExecutionResult)? = null,
    private val onNoteAddTrigger: ((noteTitle: String, noteContent: String) -> IntentExecutionResult)? = null,
    private val getVoiceShortcuts: (() -> List<VoiceShortcut>)? = null,
    private val onDailyBriefingTrigger: (() -> IntentExecutionResult)? = null,
    private val onUndoTrigger: (() -> IntentExecutionResult)? = null,
    private val onVoiceGenderTrigger: ((gender: VoiceGender) -> IntentExecutionResult)? = null,
    private val onReminderCreateTrigger: ((title: String, triggerTimeMillis: Long, repeat: ReminderRepeat) -> IntentExecutionResult)? = null,
    private val onReminderEditTrigger: ((query: String, newTitle: String?, newTriggerTimeMillis: Long?, newRepeat: ReminderRepeat?) -> IntentExecutionResult)? = null,
    private val onReminderDeleteByIdTrigger: ((reminderId: String) -> IntentExecutionResult)? = null,
    private val onReminderListTrigger: (() -> IntentExecutionResult)? = null,
    private val findReminderByQuery: ((query: String) -> List<ReminderItem>)? = null,
    private val findNoteByQuery: ((query: String) -> List<NoteItem>)? = null,
    private val onNoteDeleteByIdTrigger: ((noteId: String) -> IntentExecutionResult)? = null,
    private val findTaskByQuery: ((query: String) -> List<TaskItem>)? = null,
    private val onTaskDeleteByIdTrigger: ((taskId: String) -> IntentExecutionResult)? = null,
    private val onClearHistoryTrigger: (() -> IntentExecutionResult)? = null,
    private val isMemoryEnabled: (() -> Boolean)? = null,
    private val onMemoryToggleTrigger: ((enabled: Boolean) -> IntentExecutionResult)? = null,
    private val onMemorySaveTrigger: ((title: String, content: String) -> IntentExecutionResult)? = null,
    private val onMemoryRetrieveTrigger: ((query: String) -> IntentExecutionResult)? = null,
    private val onMemoryUpdateTrigger: ((query: String, newContent: String) -> IntentExecutionResult)? = null,
    private val findMemoryByQuery: ((query: String) -> List<MemoryItem>)? = null,
    private val onMemoryDeleteByIdTrigger: ((memoryId: String) -> IntentExecutionResult)? = null,
    private val onModeChangeTrigger: ((mode: AssistantMode) -> IntentExecutionResult)? = null,
    private val onOfficeCommandTrigger: ((command: String) -> IntentExecutionResult?)? = null
) {

    // Contextual conversation memory for follow-up commands
    private var lastExecutedIntent: String? = null
    private var lastParameters: Map<String, String> = emptyMap()
    private var lastAssistantSpeechText: String? = null

    // Pending disambiguation when multiple installed apps match a spoken name
    private var pendingAppChoices: List<AppChoiceOption> = emptyList()
    private var pendingDisambiguationHindi: Boolean = false

    // Pending disambiguation when multiple contacts match a spoken name
    private var pendingContactChoices: List<ContactMatch> = emptyList()
    private var pendingContactPurpose: String = "CALL" // "CALL" or "SMS"
    private var pendingContactSmsBody: String = ""
    private var pendingContactHindi: Boolean = false

    // Pending confirmation gate for sensitive actions (calls, SMS, deletions)
    private var pendingConfirmation: PendingConfirmation? = null

    fun getLastExecutedIntent(): String? = lastExecutedIntent
    fun getPendingConfirmation(): PendingConfirmation? = pendingConfirmation

    fun updateLastAssistantSpeech(speech: String) {
        if (speech.isNotBlank()) {
            lastAssistantSpeechText = speech
        }
    }

    fun clearPendingAppDisambiguation() {
        pendingAppChoices = emptyList()
    }

    fun clearPendingContactDisambiguation() {
        pendingContactChoices = emptyList()
        pendingContactSmsBody = ""
    }

    fun clearPendingConfirmation() {
        pendingConfirmation = null
    }

    fun cleanWakePhrase(rawText: String): String {
        var text = rawText.trim()
        val prefixes = listOf(
            "hey siri,", "hey siri", "ok siri,", "ok siri",
            "siri,", "siri", "arre siri", "suno siri,", "suno siri", "sun siri",
            "हे सिरी,", "हे सिरी", "हे सीरी,", "हे सीरी", "सिरी,", "सिरी", "सुनो सिरी"
        )
        for (prefix in prefixes) {
            if (text.lowercase().startsWith(prefix)) {
                text = text.substring(prefix.length).trim()
                if (text.startsWith(",") || text.startsWith(".") || text.startsWith("।")) {
                    text = text.substring(1).trim()
                }
                break
            }
        }
        return text
    }

    fun isStopCommand(cleanText: String): Boolean {
        val lower = cleanText.lowercase().replace(Regex("[.!?,।]+$"), "").trim()
        val stopPhrases = listOf(
            "stop", "quiet", "stop speaking", "shut up", "silence", "be quiet",
            "chup", "shant ho jao", "bas karo", "ruk jao", "chup raho"
        )
        if (lower == "pause" || lower == "pause speaking") {
            return true
        }
        if (lower == "cancel" && pendingConfirmation == null && pendingAppChoices.isEmpty() && pendingContactChoices.isEmpty()) {
            return true
        }
        return stopPhrases.any { lower == it || lower.startsWith("$it ") }
    }

    /**
     * Confirms and executes the active PendingConfirmation (callable from voice or UI Confirm button).
     */
    fun confirmPendingAction(): IntentExecutionResult? {
        val pending = pendingConfirmation ?: return null
        pendingConfirmation = null
        val isHi = pending.isHindiOrHinglish

        val res = when (pending.actionType) {
            ConfirmationActionType.DIAL_PHONE -> {
                val number = pending.payload["number"].orEmpty()
                val label = pending.targetLabel
                val (ok, msg) = deviceController.dialPhoneNumber(number)
                val speech = if (isHi && ok) "$label ko call laga rahi hoon." else msg
                IntentExecutionResult(
                    intentName = "DIAL_PHONE_CONFIRMED",
                    parameters = pending.payload,
                    feedbackSpeech = speech,
                    success = ok,
                    actionSummary = "Confirmed call to $label",
                    emotion = if (ok) AssistantEmotion.HAPPINESS else AssistantEmotion.SERIOUSNESS
                )
            }
            ConfirmationActionType.SEND_SMS -> {
                val number = pending.payload["number"].orEmpty()
                val body = pending.payload["body"].orEmpty()
                val label = pending.targetLabel
                val (ok, msg) = deviceController.composeSms(number, body)
                val speech = if (isHi && ok) "$label ke liye message taiyar hai." else msg
                IntentExecutionResult(
                    intentName = "SEND_SMS_CONFIRMED",
                    parameters = pending.payload,
                    feedbackSpeech = speech,
                    success = ok,
                    actionSummary = "Confirmed SMS to $label",
                    emotion = if (ok) AssistantEmotion.HAPPINESS else AssistantEmotion.SERIOUSNESS
                )
            }
            ConfirmationActionType.DELETE_NOTE -> {
                val id = pending.payload["id"].orEmpty()
                onNoteDeleteByIdTrigger?.invoke(id)
                    ?: IntentExecutionResult("DELETE_NOTE", emptyMap(), "Deleted note '${pending.targetLabel}'.", true, "Deleted Note")
            }
            ConfirmationActionType.DELETE_TASK -> {
                val id = pending.payload["id"].orEmpty()
                onTaskDeleteByIdTrigger?.invoke(id)
                    ?: IntentExecutionResult("DELETE_TASK", emptyMap(), "Deleted task '${pending.targetLabel}'.", true, "Deleted Task")
            }
            ConfirmationActionType.CANCEL_REMINDER -> {
                val id = pending.payload["id"].orEmpty()
                onReminderDeleteByIdTrigger?.invoke(id) ?: run {
                    deviceController.reminderScheduler.cancelReminder(id)
                    IntentExecutionResult("CANCEL_REMINDER", emptyMap(), "Cancelled reminder '${pending.targetLabel}'.", true, "Cancelled Reminder")
                }
            }
            ConfirmationActionType.DELETE_MEMORY -> {
                val id = pending.payload["id"].orEmpty()
                onMemoryDeleteByIdTrigger?.invoke(id)
                    ?: IntentExecutionResult("DELETE_MEMORY", emptyMap(), "Removed memory '${pending.targetLabel}'.", true, "Deleted Memory")
            }
            ConfirmationActionType.CLEAR_HISTORY -> {
                onClearHistoryTrigger?.invoke()
                    ?: IntentExecutionResult("CLEAR_HISTORY", emptyMap(), "Conversation history cleared.", true, "Cleared History")
            }
            ConfirmationActionType.CUSTOM -> {
                IntentExecutionResult("CONFIRMED", pending.payload, "Confirmed ${pending.targetLabel}.", true, pending.summary)
            }
        }
        recordLastIntent(res)
        return res
    }

    /**
     * Cancels the active PendingConfirmation (callable from voice or UI Cancel button).
     */
    fun cancelPendingAction(): IntentExecutionResult? {
        val pending = pendingConfirmation ?: return null
        pendingConfirmation = null
        val speech = if (pending.isHindiOrHinglish) {
            "Theek hai, ${pending.targetLabel} cancel kar diya."
        } else {
            "Okay, cancelled ${pending.summary}."
        }
        val res = IntentExecutionResult(
            intentName = "ACTION_CANCELLED",
            parameters = mapOf("cancelled" to pending.actionType.name),
            feedbackSpeech = speech,
            success = true,
            actionSummary = "Cancelled: ${pending.targetLabel}",
            emotion = AssistantEmotion.NEUTRAL
        )
        recordLastIntent(res)
        return res
    }

    /**
     * Entry point supporting single commands, confirmations, disambiguations, and multi-step commands.
     */
    fun parseAndExecute(rawCommand: String): IntentExecutionResult? {
        val cleanedRaw = cleanWakePhrase(rawCommand).trim()
        val text = cleanedRaw.lowercase().replace(Regex("[.!?,।]+$"), "").trim()
        if (text.isBlank()) return null

        // 0A. Pending Sensitive Action Confirmation gate
        if (pendingConfirmation != null) {
            if (SiriFeatureParsers.isConfirmationReply(text)) {
                return confirmPendingAction()
            }
            if (SiriFeatureParsers.isCancellationReply(text)) {
                return cancelPendingAction()
            }
        }

        // 0B. Pending Contact Disambiguation follow-up
        if (pendingContactChoices.isNotEmpty()) {
            resolvePendingContactDisambiguation(text)?.let {
                recordLastIntent(it)
                return it
            }
        }

        // 0C. Pending App Disambiguation follow-up
        if (pendingAppChoices.isNotEmpty()) {
            resolvePendingAppDisambiguation(text)?.let {
                recordLastIntent(it)
                return it
            }
        }

        // 0D. Multi-Step Command Check (execute each step in order)
        val steps = splitMultiStepCommands(cleanedRaw)
        if (steps.size >= 2) {
            val stepResults = mutableListOf<IntentExecutionResult>()
            for (step in steps) {
                val res = parseSingleCommand(step)
                if (res != null) {
                    stepResults.add(res)
                }
            }
            if (stepResults.size >= 2) {
                val combinedSpeech = stepResults.joinToString(" ") { it.feedbackSpeech }
                val combinedSummary = stepResults.mapNotNull { it.actionSummary }.joinToString(" → ")
                val lastConfirm = stepResults.lastOrNull { it.pendingConfirmation != null }
                val lastAppChoices = stepResults.lastOrNull { it.appChoices.isNotEmpty() }?.appChoices ?: emptyList()
                val lastContactChoices = stepResults.lastOrNull { it.contactChoices.isNotEmpty() }?.contactChoices ?: emptyList()
                val combinedSources = stepResults.flatMap { it.webSources }
                val overallSuccess = stepResults.any { it.success }
                val multiResult = IntentExecutionResult(
                    intentName = "MULTI_STEP",
                    parameters = mapOf(
                        "steps" to stepResults.size.toString(),
                        "intents" to stepResults.joinToString(",") { it.intentName }
                    ),
                    feedbackSpeech = combinedSpeech,
                    success = overallSuccess,
                    actionSummary = combinedSummary.ifBlank { "Executed ${stepResults.size} steps" },
                    requiresConfirmation = lastConfirm != null,
                    pendingConfirmation = lastConfirm?.pendingConfirmation,
                    webSources = combinedSources,
                    emotion = stepResults.last().emotion,
                    appChoices = lastAppChoices,
                    contactChoices = lastContactChoices
                )
                recordLastIntent(multiResult)
                return multiResult
            }
        }

        return parseSingleCommand(cleanedRaw)
    }

    /**
     * Splits compound sentences into ordered sub-commands without breaking single-body intents.
     */
    fun splitMultiStepCommands(rawCleanText: String): List<String> {
        val trimmed = rawCleanText.trim().replace(Regex("[.!?।]+$"), "").trim()
        val lower = trimmed.lowercase()

        // Guard single-body phrases that legitimately contain "and" / commas
        val singleBodyPrefixes = listOf(
            "create note ", "take a note ", "note: ", "note banao ",
            "remember that ", "save to memory ", "yaad rakhna ",
            "send sms ", "send message ", "send a message ", "text ", "message ",
            "what is zero", "tell me a joke", "what is "
        )
        if (singleBodyPrefixes.any { lower.startsWith(it) } && !lower.contains(",")) {
            return listOf(trimmed)
        }

        // First split on explicit multi-clause delimiters: commas, semicolons, "and then", "aur", "phir", "uske baad"
        val primaryRegex = Regex(
            """\s*(?:,|;|।|\band\s+then\b|\bthen\b|\baur\b|और|\bphir\b|\bfir\b|\buske\s+baad\b|उसके\s+बाद)\s*""",
            RegexOption.IGNORE_CASE
        )
        val primaryParts = trimmed.split(primaryRegex).map { it.trim() }.filter { it.isNotBlank() }
        if (primaryParts.size >= 2) {
            return primaryParts
        }

        // Also handle " <cmd1> and <cmd2> " when neither is inside math like "2 and 2"
        if (lower.contains(" and ") && !Regex("""\d+\s+and\s+\d+""").containsMatchIn(lower)) {
            val andParts = trimmed.split(Regex("""\s+\band\b\s+""", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (andParts.size >= 2 && andParts.all { it.split(" ").size >= 2 }) {
                return andParts
            }
        }
        return listOf(trimmed)
    }

    private fun parseSingleCommand(rawCommand: String): IntentExecutionResult? {
        val cleanedRaw = cleanWakePhrase(rawCommand).trim()
        val text = cleanedRaw.lowercase().replace(Regex("[.!?,।]+$"), "").trim()
        if (text.isBlank()) return null
        val isHi = SiriFeatureParsers.isHindiOrHinglish(cleanedRaw)

        // Check user-defined voice shortcuts first
        getVoiceShortcuts?.invoke()?.firstOrNull { it.isEnabled && text == it.triggerPhrase.lowercase().trim() }?.let { shortcut ->
            if (shortcut.customSpokenReply != null && shortcut.commandText.isBlank()) {
                val res = IntentExecutionResult(
                    intentName = "VOICE_SHORTCUT",
                    feedbackSpeech = shortcut.customSpokenReply,
                    success = true,
                    actionSummary = "Executed shortcut: ${shortcut.triggerPhrase}"
                )
                recordLastIntent(res)
                return res
            } else if (shortcut.commandText.isNotBlank()) {
                return parseSingleCommand(shortcut.commandText)
            }
        }

        // 1. Stop / Quiet command
        if (isStopCommand(text)) {
            val res = IntentExecutionResult("STOP", emptyMap(), "Stopped.", true, "Cancelled voice output")
            recordLastIntent(res)
            return res
        }

        // 2. Undo Command
        if (text == "undo" || text == "undo that" || text == "piche jao" || text == "cancel that action") {
            onUndoTrigger?.invoke()?.let {
                recordLastIntent(it)
                return it
            }
        }

        // 3. Daily Briefing
        if (SiriFeatureParsers.matchesAny(text, listOf("daily briefing", "give me my daily briefing", "morning briefing", "aaj ka briefing", "what's my day look like", "brief me", "good morning briefing"))) {
            onDailyBriefingTrigger?.invoke()?.let {
                recordLastIntent(it)
                return it
            }
        }

        // 4A. Assistant Mode Switching (Offline / Online / Office / Auto)
        val requestedMode = when {
            SiriFeatureParsers.matchesAny(text, listOf(
                "offline mode", "enable offline mode", "switch to offline mode", "turn on offline mode",
                "offline mode on karo", "offline mode chalu karo", "offline mode lagao", "go offline"
            )) -> AssistantMode.OFFLINE
            SiriFeatureParsers.matchesAny(text, listOf(
                "office mode", "enable office mode", "switch to office mode", "turn on office mode",
                "start office mode", "office mode on karo", "office mode chalu karo", "office mode lagao", "study mode"
            )) -> AssistantMode.OFFICE
            SiriFeatureParsers.matchesAny(text, listOf(
                "online mode", "enable online mode", "switch to online mode", "turn on online mode",
                "online mode on karo", "online mode chalu karo", "online mode lagao", "go online"
            )) -> AssistantMode.ONLINE
            SiriFeatureParsers.matchesAny(text, listOf(
                "auto mode", "automatic mode", "switch to auto mode", "auto mode on karo", "auto mode lagao"
            )) -> AssistantMode.AUTO
            else -> null
        }
        if (requestedMode != null) {
            val res = onModeChangeTrigger?.invoke(requestedMode) ?: IntentExecutionResult(
                intentName = "SET_ASSISTANT_MODE",
                parameters = mapOf("mode" to requestedMode.name),
                feedbackSpeech = "Switched to ${requestedMode.displayName}.",
                success = true,
                actionSummary = "Mode: ${requestedMode.displayName}",
                emotion = AssistantEmotion.HAPPINESS
            )
            recordLastIntent(res)
            return res
        }

        // 4. Voice Gender Selection
        if (text.contains("female voice") || text.contains("female awaz") || text.contains("female aawaz") ||
            text.contains("ladki ki aawaz") || text.contains("ladki ki awaz") || text.contains("aurat ki aawaz") ||
            text == "female voice karo" || text == "female voice lagao" || text == "female voice add karo" ||
            text == "switch to female voice" || text == "change voice to female"
        ) {
            val res = onVoiceGenderTrigger?.invoke(VoiceGender.FEMALE) ?: IntentExecutionResult(
                intentName = "SET_VOICE_GENDER",
                parameters = mapOf("gender" to "FEMALE"),
                feedbackSpeech = "Maine voice ko Female Siri voice par set kar diya hai. Kaise madad kar sakti hoon?",
                success = true,
                actionSummary = "Changed voice to Female (Siri)"
            )
            recordLastIntent(res)
            return res
        }
        if (text.contains("male voice") || text.contains("male awaz") || text.contains("male aawaz") ||
            text.contains("ladke ki aawaz") || text.contains("purush ki aawaz") ||
            text == "male voice karo" || text == "male voice lagao" || text == "switch to male voice"
        ) {
            val res = onVoiceGenderTrigger?.invoke(VoiceGender.MALE) ?: IntentExecutionResult(
                intentName = "SET_VOICE_GENDER",
                parameters = mapOf("gender" to "MALE"),
                feedbackSpeech = "Voice set to Male voice.",
                success = true,
                actionSummary = "Changed voice to Male"
            )
            recordLastIntent(res)
            return res
        }

        // 5. Smart Conversation Context Follow-ups
        handleContextualFollowUp(text, isHi)?.let {
            recordLastIntent(it)
            return it
        }

        // 6. Repeat last spoken response
        if (SiriFeatureParsers.matchesAny(text, listOf("repeat that", "say that again", "what did you say", "fir se bolo", "phir se bolo", "phir se batao", "fir se batao", "dobara bolo"))) {
            val lastSpeech = lastAssistantSpeechText
                ?: lastParameters["feedback"]
                ?: lastExecutedIntent?.let { "Last action was $it." }
                ?: "I haven't said anything yet in this session."
            val res = IntentExecutionResult("REPEAT", emptyMap(), lastSpeech, true, "Repeated previous response")
            recordLastIntent(res)
            return res
        }

        // 7. Conversational & Personality responses (Jokes, Easter Eggs, Greetings, Identity, Capabilities)
        SiriFeatureParsers.handleConversationalQueries(text)?.let {
            recordLastIntent(it)
            return it
        }

        // 8. Flashlight / Torch
        if (SiriFeatureParsers.matchesAny(text, listOf("turn on flashlight", "turn on the flashlight", "turn on torch", "torch on", "flashlight on", "batti jalao", "torch jalao", "light on karo", "flashlight chalu karo", "torch chalu karo", "torch light on karo"))) {
            val (ok, msg) = deviceController.setFlashlight(true)
            val res = IntentExecutionResult("FLASHLIGHT_ON", emptyMap(), msg, ok, "Turned on Flashlight", isReversible = true, undoAction = "FLASHLIGHT_OFF")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("turn off flashlight", "turn off the flashlight", "turn off torch", "torch off", "flashlight off", "batti band karo", "torch band karo", "light off karo", "flashlight band kar do", "torch bujhao", "batti bujhao"))) {
            val (ok, msg) = deviceController.setFlashlight(false)
            val res = IntentExecutionResult("FLASHLIGHT_OFF", emptyMap(), msg, ok, "Turned off Flashlight", isReversible = true, undoAction = "FLASHLIGHT_ON")
            recordLastIntent(res)
            return res
        }

        // 9. Battery, Storage, Device Info, Network, Time & Date
        if (SiriFeatureParsers.matchesAny(text, listOf(
                "battery", "battery status", "battery percentage", "how much battery", "check battery",
                "battery kitni hai", "battery kitni bachi hai", "battery check karo", "charge kitna hai",
                "battery charge", "kitna charge bacha hai", "battery level"
            ))
        ) {
            val info = deviceController.getBatteryInfo()
            val res = IntentExecutionResult("BATTERY_STATUS", emptyMap(), info, true, "Checked Battery Status")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf(
                "device info", "device information", "phone info", "phone information", "phone details",
                "system info", "check device info", "mere phone ki jankari", "phone ki details", "android version"
            ))
        ) {
            val info = deviceController.getDeviceInfo()
            val res = IntentExecutionResult("DEVICE_INFO", emptyMap(), info, true, "Checked Device Info")
            recordLastIntent(res)
            return res
        }
        if (text.contains("storage") || text.contains("free space") || text.contains("disk space") || text.contains("memory space") || text.contains("kitna space")) {
            if (text.contains("setting")) {
                val (ok, msg) = deviceController.openStorageSettings()
                val res = IntentExecutionResult("OPEN_SETTINGS", mapOf("target" to "storage"), msg, ok, "Opened Storage Settings")
                recordLastIntent(res)
                return res
            }
            val info = deviceController.getStorageInfo()
            val res = IntentExecutionResult("STORAGE_STATUS", emptyMap(), info, true, "Checked Device Storage")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("network", "network status", "connectivity", "internet status", "am i online", "internet chal raha hai", "wifi status", "wifi kaisa hai", "network check karo"))) {
            val info = deviceController.getNetworkInfo()
            val res = IntentExecutionResult("NETWORK_STATUS", emptyMap(), info, true, "Checked Network Connectivity")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("what time is it", "what's the time", "current time", "tell me the time", "samay kya hai", "time kya hua hai", "time kya hua", "time batao", "kitne baje hain"))) {
            val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
            val res = IntentExecutionResult("TIME", emptyMap(), "It is $time.", true, "Checked Time")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("what is today's date", "what's the date", "today's date", "tell me the date", "aaj konsi tarikh hai", "aaj ki tarikh kya hai", "aaj ki date kya hai", "aaj ki date batao", "current date", "aaj konsa din hai"))) {
            val date = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault()).format(Date())
            val res = IntentExecutionResult("DATE", emptyMap(), "Today is $date.", true, "Checked Date")
            recordLastIntent(res)
            return res
        }

        // 10. Volume Controls
        if (SiriFeatureParsers.matchesAny(text, listOf("volume up", "increase volume", "raise volume", "louder", "awaz badhao", "awaz tez karo", "volume badhao"))) {
            val (ok, msg) = deviceController.adjustVolume("up")
            val res = IntentExecutionResult("VOLUME_UP", emptyMap(), msg, ok, "Raised Volume")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("volume down", "decrease volume", "lower volume", "awaz kam karo", "awaz dheere karo", "volume kam karo"))) {
            val (ok, msg) = deviceController.adjustVolume("down")
            val res = IntentExecutionResult("VOLUME_DOWN", emptyMap(), msg, ok, "Lowered Volume")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("mute", "mute audio", "mute music", "awaz band karo", "phone silent karo"))) {
            val (ok, msg) = deviceController.adjustVolume("mute")
            val res = IntentExecutionResult("VOLUME_MUTE", emptyMap(), msg, ok, "Muted Volume")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("unmute", "unmute audio", "awaz chalu karo"))) {
            val (ok, msg) = deviceController.adjustVolume("unmute")
            val res = IntentExecutionResult("VOLUME_UNMUTE", emptyMap(), msg, ok, "Unmuted Volume")
            recordLastIntent(res)
            return res
        }

        // 11. Music & MediaSession Playback Controls
        if (SiriFeatureParsers.matchesAny(text, listOf("pause music", "pause song", "stop music playback", "stop music", "music roko", "gaana roko", "song band karo"))) {
            val (ok, msg) = deviceController.controlMediaPlayback("PAUSE")
            val res = IntentExecutionResult("MEDIA_PAUSE", emptyMap(), msg, ok, "Paused music")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("resume music", "resume song", "play music", "chalu karo music", "gaana chalao", "gaana bajao", "play song", "music chalu karo"))) {
            val (ok, msg) = deviceController.controlMediaPlayback("PLAY")
            val res = IntentExecutionResult("MEDIA_PLAY", emptyMap(), msg, ok, "Resumed music")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("next song", "next track", "skip song", "agla gaana", "next gaana", "skip track"))) {
            val (ok, msg) = deviceController.controlMediaPlayback("NEXT")
            val res = IntentExecutionResult("MEDIA_NEXT", emptyMap(), msg, ok, "Skipped track")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("previous song", "previous track", "pichla gaana", "pichhla gaana"))) {
            val (ok, msg) = deviceController.controlMediaPlayback("PREVIOUS")
            val res = IntentExecutionResult("MEDIA_PREV", emptyMap(), msg, ok, "Previous track")
            recordLastIntent(res)
            return res
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("what song is playing", "what is playing", "now playing", "kaun sa gaana baj raha hai"))) {
            val (ok, msg) = deviceController.controlMediaPlayback("NOW_PLAYING")
            val res = IntentExecutionResult("MEDIA_INFO", emptyMap(), msg, ok, "Checked active media")
            recordLastIntent(res)
            return res
        }

        // 12. Unsupported system restrictions with settings fallback
        if (SiriFeatureParsers.matchesAny(text, listOf("turn on airplane mode", "turn off airplane mode", "airplane mode on", "flight mode on"))) {
            val (ok, _) = deviceController.openSettings(Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane Mode Settings")
            return IntentExecutionResult(
                intentName = "UNSUPPORTED_RESTRICTION",
                feedbackSpeech = "Android security prohibits third-party apps from toggling Airplane mode directly. I have opened Airplane mode settings for you.",
                success = ok,
                actionSummary = "Opened Airplane Mode Settings",
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("restart phone", "reboot phone", "shut down phone", "power off phone"))) {
            return IntentExecutionResult(
                intentName = "UNSUPPORTED_RESTRICTION",
                feedbackSpeech = "Restarting or shutting down the device requires system-level OS privileges. Please press and hold your phone's Power button.",
                success = false,
                actionSummary = "System security restriction",
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }

        // 13. Phone Settings Deep Links
        handleSettingsIntent(text)?.let {
            recordLastIntent(it)
            return it
        }

        // 14. Confirmation-Gated Deletions (Notes, Tasks, Reminders, Memories, Chat History)
        handleDeleteCommands(cleanedRaw, text, isHi)?.let {
            recordLastIntent(it)
            return it
        }

        // 15. Smart Memory (Save, Retrieve, Edit, Toggle)
        handleSmartMemoryCommands(cleanedRaw, text, isHi)?.let {
            recordLastIntent(it)
            return it
        }

        // 16. Timers, Alarms & Scheduled/Recurring Reminders (Create, Edit, List)
        handleAlarmsTimersAndReminders(cleanedRaw, text, isHi)?.let {
            recordLastIntent(it)
            return it
        }

        // 17. Contact Assistant & Phone Calling (Always confirmation-gated)
        SiriFeatureParsers.parseCallTarget(cleanedRaw)?.let { target ->
            return handleCallCommand(target, isHi)
        }

        // 18. Contact Assistant & SMS Composer (Always confirmation-gated)
        SiriFeatureParsers.parseSmsRequest(cleanedRaw)?.let { smsReq ->
            return handleSmsCommand(smsReq.target, smsReq.body, isHi)
        }

        // 19. Navigation, Google Maps & Place Search (Offline-aware)
        handleNavigationAndPlaces(cleanedRaw, text, isHi)?.let {
            recordLastIntent(it)
            return it
        }

        // 20. Calendar Event Creation
        val calMatch = Regex("""(?:add\s+event|create\s+calendar\s+event|schedule\s+meeting)\s+(.+)""").find(text)
        if (calMatch != null) {
            val eventTitle = calMatch.groupValues[1].trim()
            val (ok, msg) = deviceController.createCalendarEvent(eventTitle)
            val res = IntentExecutionResult("CALENDAR_EVENT", mapOf("title" to eventTitle), msg, ok, "Created Calendar Event: $eventTitle")
            recordLastIntent(res)
            return res
        }

        // 21. Offline Calculations & Unit Conversions
        SiriFeatureParsers.evaluateMath(text)?.let { calcResult ->
            val res = IntentExecutionResult("CALCULATION", mapOf("result" to calcResult), calcResult, true, "Calculated Math")
            recordLastIntent(res)
            return res
        }
        SiriFeatureParsers.evaluateUnitConversion(text)?.let { convResult ->
            val res = IntentExecutionResult("UNIT_CONVERSION", mapOf("result" to convResult), convResult, true, "Converted Units")
            recordLastIntent(res)
            return res
        }

        // 22. Universal App Launcher (English, Hinglish & Hindi Devanagari)
        val appToOpen = extractAppToOpen(text)
        if (appToOpen != null) {
            return handleOpenApp(appToOpen, isHi)
        }

        // 23. Web Search & Official Links (Offline-aware)
        handleWebSearch(cleanedRaw, text, isHi)?.let {
            recordLastIntent(it)
            return it
        }

        // 24. Add Note
        if (text.startsWith("create note ") || text.startsWith("take a note ") || text.startsWith("note: ") || text.startsWith("note banao ")) {
            val noteBody = cleanedRaw
                .replace(Regex("""^(?:create\s+note|take\s+a\s+note|note:|note\s+banao)\s+""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (noteBody.isNotEmpty() && onNoteAddTrigger != null) {
                val res = onNoteAddTrigger.invoke("Voice Note", noteBody)
                recordLastIntent(res)
                return res
            }
        }

        // 25. Add Task
        if (text.startsWith("add task ") || text.startsWith("note down ") || text.startsWith("task add karo ") || text.startsWith("soochi mein jodo ") || text.startsWith("remind me to ")) {
            val taskTitle = cleanedRaw
                .replace(Regex("""^(?:add\s+task|note\s+down|task\s+add\s+karo|soochi\s+mein\s+jodo|remind\s+me\s+to)\s+""", RegexOption.IGNORE_CASE), "")
                .trim()
            if (taskTitle.isNotEmpty() && onTaskAddTrigger != null) {
                val res = onTaskAddTrigger.invoke(taskTitle)
                recordLastIntent(res)
                return res
            }
        }

        // 26. Office Mode & Productivity Local Commands
        onOfficeCommandTrigger?.invoke(cleanedRaw)?.let { officeRes ->
            recordLastIntent(officeRes)
            return officeRes
        }

        // 27. Custom Automation Routine
        if (onCustomRoutineTrigger != null) {
            val routineResult = onCustomRoutineTrigger.invoke(text)
            if (routineResult != null) {
                recordLastIntent(routineResult)
                return routineResult
            }
        }

        return null
    }

    private fun handleContextualFollowUp(text: String, isHi: Boolean): IntentExecutionResult? {
        val last = lastExecutedIntent ?: return null

        // Flashlight toggle follow-up
        if (SiriFeatureParsers.matchesAny(text, listOf("turn it off", "switch it off", "turn that off", "band kar do", "ise band karo", "off karo"))) {
            if (last == "FLASHLIGHT_ON") {
                val (ok, msg) = deviceController.setFlashlight(false)
                return IntentExecutionResult("FLASHLIGHT_OFF", emptyMap(), msg, ok, "Turned off Flashlight (Context Follow-up)")
            }
            if (last == "MEDIA_PLAY") {
                val (ok, msg) = deviceController.controlMediaPlayback("PAUSE")
                return IntentExecutionResult("MEDIA_PAUSE", emptyMap(), msg, ok, "Paused media (Context Follow-up)")
            }
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("turn it on", "switch it on", "turn that on", "chalu kar do", "ise chalu karo", "on karo"))) {
            if (last == "FLASHLIGHT_OFF") {
                val (ok, msg) = deviceController.setFlashlight(true)
                return IntentExecutionResult("FLASHLIGHT_ON", emptyMap(), msg, ok, "Turned on Flashlight (Context Follow-up)")
            }
            if (last == "MEDIA_PAUSE") {
                val (ok, msg) = deviceController.controlMediaPlayback("PLAY")
                return IntentExecutionResult("MEDIA_PLAY", emptyMap(), msg, ok, "Resumed media (Context Follow-up)")
            }
        }

        // Volume follow-up
        if (last.startsWith("VOLUME_") || last.startsWith("MEDIA_")) {
            if (text in setOf("aur badhao", "more", "little louder", "thoda aur tez", "raise it more")) {
                val (ok, msg) = deviceController.adjustVolume("up")
                return IntentExecutionResult("VOLUME_UP", emptyMap(), msg, ok, "Raised Volume (Follow-up)")
            }
            if (text in setOf("aur kam karo", "quieter", "lower it more", "thoda aur kam", "softer")) {
                val (ok, msg) = deviceController.adjustVolume("down")
                return IntentExecutionResult("VOLUME_DOWN", emptyMap(), msg, ok, "Lowered Volume (Follow-up)")
            }
        }

        // Media track follow-up
        if (last.startsWith("MEDIA_")) {
            if (text in setOf("next", "agla", "skip")) {
                val (ok, msg) = deviceController.controlMediaPlayback("NEXT")
                return IntentExecutionResult("MEDIA_NEXT", emptyMap(), msg, ok, "Skipped track (Follow-up)")
            }
            if (text in setOf("previous", "pichla", "back")) {
                val (ok, msg) = deviceController.controlMediaPlayback("PREVIOUS")
                return IntentExecutionResult("MEDIA_PREV", emptyMap(), msg, ok, "Previous track (Follow-up)")
            }
        }

        // Contact follow-up ("call them", "message them ...")
        val savedNumber = lastParameters["number"] ?: lastParameters["contact_number"]
        val savedName = lastParameters["contact_name"] ?: savedNumber
        if (!savedNumber.isNullOrBlank() && !savedName.isNullOrBlank()) {
            if (text in setOf("call them", "call him", "call her", "unhe call karo", "inko call karo")) {
                return queueCallConfirmation(savedName, savedNumber, isHi)
            }
            val msgFollowUp = Regex("""^(?:message them|text them|unhe message bhejo)\s*(?:saying\s+)?(.+)$""").find(text)
            if (msgFollowUp != null) {
                return queueSmsConfirmation(savedName, savedNumber, msgFollowUp.groupValues[1].trim(), isHi)
            }
        }

        // Web search follow-up ("open first link" / "open official link")
        val savedUrl = lastParameters["top_url"]
        if (last == "WEB_SEARCH" && !savedUrl.isNullOrBlank() &&
            (text in setOf("open it", "open that", "open official link", "open first link", "link kholo"))
        ) {
            val (ok, msg) = deviceController.openUrl(savedUrl)
            return IntentExecutionResult("OPEN_URL", mapOf("url" to savedUrl), msg, ok, "Opened $savedUrl")
        }

        return null
    }

    private fun handleSettingsIntent(text: String): IntentExecutionResult? {
        val settingTarget = when {
            text.contains("wifi") && (text.contains("setting") || text.contains("kholo") || text.contains("open")) ->
                "wifi" to deviceController.openWifiSettings()
            text.contains("bluetooth") && (text.contains("setting") || text.contains("kholo") || text.contains("open")) ->
                "bluetooth" to deviceController.openBluetoothSettings()
            (text.contains("display") || text.contains("brightness") || text.contains("screen")) && text.contains("setting") ->
                "display" to deviceController.openDisplaySettings()
            (text.contains("sound") || text.contains("audio")) && text.contains("setting") ->
                "sound" to deviceController.openSoundSettings()
            (text.contains("location") || text.contains("gps")) && text.contains("setting") ->
                "location" to deviceController.openLocationSettings()
            text.contains("battery") && text.contains("setting") ->
                "battery" to deviceController.openBatterySettings()
            (text.contains("hotspot") || text.contains("network setting")) ->
                "hotspot" to deviceController.openHotspotSettings()
            text.contains("date") && text.contains("setting") ->
                "date" to deviceController.openDateSettings()
            text.contains("privacy") && text.contains("setting") ->
                "privacy" to deviceController.openPrivacySettings()
            text.contains("accessibility") && text.contains("setting") ->
                "accessibility" to deviceController.openAccessibilitySettings()
            text.contains("notification") && text.contains("setting") ->
                "notification" to deviceController.openNotificationSettings()
            text.contains("exact alarm") && text.contains("setting") ->
                "exact_alarm" to deviceController.reminderScheduler.openExactAlarmPermissionSettings()
            (text.contains("app") && text.contains("setting")) ->
                "apps" to deviceController.openAppsSettings()
            text in setOf("phone settings kholo", "mobile settings kholo", "device settings open karo", "device settings kholo") ->
                "settings" to deviceController.openSettings(Settings.ACTION_SETTINGS, "Settings")
            else -> null
        } ?: return null

        val (targetName, resultPair) = settingTarget
        return IntentExecutionResult(
            intentName = "OPEN_SETTINGS",
            parameters = mapOf("target" to targetName),
            feedbackSpeech = resultPair.second,
            success = resultPair.first,
            actionSummary = "Opened $targetName settings"
        )
    }

    private fun handleDeleteCommands(cleanedRaw: String, text: String, isHi: Boolean): IntentExecutionResult? {
        // Clear chat history
        if (SiriFeatureParsers.matchesAny(text, listOf("clear chat history", "delete chat history", "wipe chat history", "chat history delete karo", "history saaf karo"))) {
            val prompt = if (isHi) {
                "Kya aap apni poori chat history delete karna chahte hain? Confirm karne ke liye haan ya cancel karne ke liye nahi boliye."
            } else {
                "Are you sure you want to delete all conversation history? Say yes to confirm or no to cancel."
            }
            pendingConfirmation = PendingConfirmation(
                actionType = ConfirmationActionType.CLEAR_HISTORY,
                targetLabel = "Chat History",
                summary = "clearing all chat history",
                promptSpeech = prompt,
                isHindiOrHinglish = isHi
            )
            return IntentExecutionResult(
                intentName = "CLEAR_HISTORY_CONFIRM",
                feedbackSpeech = prompt,
                success = true,
                actionSummary = "Awaiting confirmation to clear history",
                requiresConfirmation = true,
                pendingConfirmation = pendingConfirmation,
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }

        // Cancel / Delete reminder
        val cancelRemMatch = Regex("""^(?:cancel|delete|remove)\s+(?:my\s+)?(?:reminder\s+(?:for|to|called)?\s*(.+)|(.+?)\s+reminder)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
            ?: Regex("""^reminder\s+(?:cancel|delete)\s+karo\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (cancelRemMatch != null) {
            val query = (cancelRemMatch.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() }
                ?: cancelRemMatch.groupValues.getOrNull(2).orEmpty()).trim()
            val matches = findReminderByQuery?.invoke(query).orEmpty()
            val target = matches.firstOrNull()
            if (target == null) {
                val msg = if (isHi) "Mujhe '$query' naam ka koi reminder nahi mila." else "I couldn't find a reminder matching '$query'."
                return IntentExecutionResult("CANCEL_REMINDER", emptyMap(), msg, false, "Reminder not found")
            }
            val prompt = if (isHi) {
                "Kya main '${target.title}' reminder cancel kar doon? Haan ya nahi boliye."
            } else {
                "Should I cancel the reminder '${target.title}'? Say yes to confirm or no to cancel."
            }
            pendingConfirmation = PendingConfirmation(
                actionType = ConfirmationActionType.CANCEL_REMINDER,
                targetLabel = target.title,
                summary = "cancelling reminder '${target.title}'",
                promptSpeech = prompt,
                payload = mapOf("id" to target.id),
                isHindiOrHinglish = isHi
            )
            return IntentExecutionResult(
                intentName = "CANCEL_REMINDER_CONFIRM",
                parameters = mapOf("id" to target.id, "title" to target.title),
                feedbackSpeech = prompt,
                success = true,
                actionSummary = "Confirm cancelling '${target.title}'",
                requiresConfirmation = true,
                pendingConfirmation = pendingConfirmation,
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }

        // Delete note
        val delNoteMatch = Regex("""^(?:delete|remove)\s+(?:the\s+|my\s+)?note\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (delNoteMatch != null) {
            val query = delNoteMatch.groupValues[1].trim()
            val target = findNoteByQuery?.invoke(query)?.firstOrNull()
            if (target == null) {
                return IntentExecutionResult("DELETE_NOTE", emptyMap(), "I couldn't find any note matching '$query'.", false, "Note not found")
            }
            val prompt = "Delete note '${target.title}: ${target.content.take(40)}'? Say yes to confirm or no to cancel."
            pendingConfirmation = PendingConfirmation(
                actionType = ConfirmationActionType.DELETE_NOTE,
                targetLabel = target.title,
                summary = "deleting note '${target.title}'",
                promptSpeech = prompt,
                payload = mapOf("id" to target.id),
                isHindiOrHinglish = isHi
            )
            return IntentExecutionResult(
                intentName = "DELETE_NOTE_CONFIRM",
                parameters = mapOf("id" to target.id),
                feedbackSpeech = prompt,
                success = true,
                actionSummary = "Confirm deleting note",
                requiresConfirmation = true,
                pendingConfirmation = pendingConfirmation,
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }

        // Delete task
        val delTaskMatch = Regex("""^(?:delete|remove)\s+(?:the\s+|my\s+)?task\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (delTaskMatch != null) {
            val query = delTaskMatch.groupValues[1].trim()
            val target = findTaskByQuery?.invoke(query)?.firstOrNull()
            if (target == null) {
                return IntentExecutionResult("DELETE_TASK", emptyMap(), "I couldn't find any task matching '$query'.", false, "Task not found")
            }
            val prompt = "Delete task '${target.title}'? Say yes to confirm or no to cancel."
            pendingConfirmation = PendingConfirmation(
                actionType = ConfirmationActionType.DELETE_TASK,
                targetLabel = target.title,
                summary = "deleting task '${target.title}'",
                promptSpeech = prompt,
                payload = mapOf("id" to target.id),
                isHindiOrHinglish = isHi
            )
            return IntentExecutionResult(
                intentName = "DELETE_TASK_CONFIRM",
                parameters = mapOf("id" to target.id),
                feedbackSpeech = prompt,
                success = true,
                actionSummary = "Confirm deleting task",
                requiresConfirmation = true,
                pendingConfirmation = pendingConfirmation,
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }

        // Forget / Delete memory
        val delMemMatch = Regex("""^(?:forget\s+(?:that\s+)?|delete\s+memory\s+|remove\s+memory\s+)(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (delMemMatch != null) {
            val query = delMemMatch.groupValues[1].trim()
            val target = findMemoryByQuery?.invoke(query)?.firstOrNull()
            if (target == null) {
                return IntentExecutionResult("DELETE_MEMORY", emptyMap(), "I couldn't find a saved memory matching '$query'.", false, "Memory not found")
            }
            val prompt = "Forget your saved memory '${target.content}'? Say yes to confirm or no to cancel."
            pendingConfirmation = PendingConfirmation(
                actionType = ConfirmationActionType.DELETE_MEMORY,
                targetLabel = target.title,
                summary = "deleting memory '${target.title}'",
                promptSpeech = prompt,
                payload = mapOf("id" to target.id),
                isHindiOrHinglish = isHi
            )
            return IntentExecutionResult(
                intentName = "DELETE_MEMORY_CONFIRM",
                parameters = mapOf("id" to target.id),
                feedbackSpeech = prompt,
                success = true,
                actionSummary = "Confirm deleting memory",
                requiresConfirmation = true,
                pendingConfirmation = pendingConfirmation,
                emotion = AssistantEmotion.SERIOUSNESS
            )
        }

        return null
    }

    private fun handleSmartMemoryCommands(cleanedRaw: String, text: String, isHi: Boolean): IntentExecutionResult? {
        if (SiriFeatureParsers.matchesAny(text, listOf("disable memory", "turn off memory", "memory off karo", "memory band karo"))) {
            return onMemoryToggleTrigger?.invoke(false)
                ?: IntentExecutionResult("MEMORY_TOGGLE", mapOf("enabled" to "false"), "Smart memory has been disabled.", true, "Memory Disabled")
        }
        if (SiriFeatureParsers.matchesAny(text, listOf("enable memory", "turn on memory", "memory on karo", "memory chalu karo"))) {
            return onMemoryToggleTrigger?.invoke(true)
                ?: IntentExecutionResult("MEMORY_TOGGLE", mapOf("enabled" to "true"), "Smart memory has been enabled.", true, "Memory Enabled")
        }

        // Edit memory: "update memory <topic> to <new content>"
        val editMemMatch = Regex("""^(?:update|edit|change)\s+memory\s+(.+?)\s+(?:to|as)\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (editMemMatch != null) {
            val query = editMemMatch.groupValues[1].trim()
            val newContent = editMemMatch.groupValues[2].trim()
            return onMemoryUpdateTrigger?.invoke(query, newContent)
                ?: IntentExecutionResult("UPDATE_MEMORY", emptyMap(), "Updated memory for $query.", true, "Updated Memory")
        }

        // Save memory: "remember that ...", "remember my/i ...", "save to memory ...", "yaad rakhna ..."
        val saveMemMatch = Regex("""^(?:remember\s+that|save\s+to\s+memory|yaad\s+rakhna(?:\s+ki)?|yaad\s+rakho(?:\s+ki)?|remember\s+(?=(?:my|i|we|our)\s+))(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (saveMemMatch != null) {
            val fact = saveMemMatch.groupValues[1].trim()
            if (isMemoryEnabled?.invoke() == false) {
                return IntentExecutionResult(
                    intentName = "MEMORY_DISABLED",
                    feedbackSpeech = "Smart memory is currently disabled. Enable it in Privacy settings or say 'enable memory' first.",
                    success = false,
                    actionSummary = "Memory disabled",
                    emotion = AssistantEmotion.SERIOUSNESS
                )
            }
            val title = fact.split(" ").take(4).joinToString(" ").replaceFirstChar { it.uppercase() }
            return onMemorySaveTrigger?.invoke(title, fact)
                ?: IntentExecutionResult("SAVE_MEMORY", mapOf("content" to fact), "I'll remember that: $fact", true, "Saved Memory")
        }

        // Retrieve memory
        if (text.startsWith("what do you remember") || text.startsWith("what did i tell you") ||
            text in setOf("show my memories", "show memories", "my memories", "mere baare mein kya yaad hai", "meri memories dikhao") ||
            Regex("""^(?:what is|what's)\s+my\s+(.+)$""").matches(text)
        ) {
            if (isMemoryEnabled?.invoke() == false) {
                return IntentExecutionResult(
                    intentName = "MEMORY_DISABLED",
                    feedbackSpeech = "Smart memory is currently disabled in Privacy settings.",
                    success = false,
                    actionSummary = "Memory disabled"
                )
            }
            val query = text
                .replace(Regex("""^(?:what do you remember(?: about)?|what did i tell you(?: to remember)?(?: about)?|what is my|what's my|show my memories|show memories|my memories|mere baare mein kya yaad hai|meri memories dikhao)\s*"""), "")
                .trim()
            // Only intercept "what is my X" locally if there IS a matching memory or it's explicitly a memory listing request
            val res = onMemoryRetrieveTrigger?.invoke(query)
            if (res != null && (res.success || !text.startsWith("what is my") && !text.startsWith("what's my"))) {
                return res
            }
        }
        return null
    }

    private fun handleAlarmsTimersAndReminders(cleanedRaw: String, text: String, isHi: Boolean): IntentExecutionResult? {
        // List reminders
        if (SiriFeatureParsers.matchesAny(text, listOf("show my reminders", "list reminders", "what are my reminders", "mere reminders dikhao", "mere reminders batao"))) {
            return onReminderListTrigger?.invoke()
                ?: IntentExecutionResult("LIST_REMINDERS", emptyMap(), "You have no scheduled reminders yet.", true, "Listed Reminders")
        }

        // Edit reminder: "edit reminder <old> to <new>", "change my <old> reminder to <new>"
        val editRemMatch = Regex("""^(?:edit|change|update|reschedule)\s+(?:my\s+)?(?:reminder\s+(?:for|to)?\s*(.+?)|(.+?)\s+reminder)\s+(?:to|for)\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (editRemMatch != null) {
            val query = (editRemMatch.groupValues.getOrNull(1).takeIf { !it.isNullOrBlank() }
                ?: editRemMatch.groupValues.getOrNull(2).orEmpty()).trim()
            val newClause = editRemMatch.groupValues[3].trim()
            val parsedNew = SiriFeatureParsers.parseReminderSchedule(newClause)
                ?: SiriFeatureParsers.parseReminderSchedule("set reminder at $newClause")
                ?: SiriFeatureParsers.parseReminderSchedule("set reminder $newClause")
            val newTime = parsedNew?.triggerTimeMillis
            val newRepeat = parsedNew?.repeat?.takeIf { it != ReminderRepeat.NONE }
            val newTitle = if (parsedNew == null || newClause.none { it.isDigit() }) newClause else parsedNew.title.takeIf { it != "Reminder" }
            val effectiveQuery = query.ifBlank { newTitle ?: newClause }
            return onReminderEditTrigger?.invoke(effectiveQuery, newTitle, newTime, newRepeat)
                ?: IntentExecutionResult("EDIT_REMINDER", mapOf("query" to effectiveQuery), "Updated reminder.", true, "Edited Reminder")
        }

        // Timers
        val timerMatch = Regex("""(?:set|start|run)?\s*(?:a\s*)?timer\s*(?:for\s*)?(\d+)\s*(minute|minutes|min|second|seconds|sec)?""").find(text)
            ?: Regex("""(\d+)\s*(?:minute|min)\s*ka\s*timer""").find(text)
        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toIntOrNull() ?: 5
            val unit = timerMatch.groupValues.getOrNull(2) ?: "minute"
            val totalSeconds = if (unit.startsWith("sec")) amount else amount * 60
            val (ok, msg) = deviceController.setTimer(totalSeconds, "Siri Timer")
            return IntentExecutionResult("SET_TIMER", mapOf("seconds" to totalSeconds.toString()), msg, ok, "Set $amount $unit timer")
        }

        // Alarms (supports AM/PM and Hindi qualifiers subah/shaam/raat/dopahar with optional :mm)
        val engAlarmMatch = Regex("""(?:set|start)?\s*(?:an\s*)?alarm\s*(?:for|at)?\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?""").find(text)
        val hiAlarmMatch = if (engAlarmMatch == null) {
            Regex("""(subah|shaam|sham|raat|rat|dopahar)?\s*(\d{1,2})(?::(\d{2}))?\s*baje\s*ka\s*alarm""").find(text)
        } else null
        if (engAlarmMatch != null) {
            var hour = engAlarmMatch.groupValues[1].toIntOrNull() ?: 7
            val min = engAlarmMatch.groupValues.getOrNull(2)?.toIntOrNull() ?: 0
            val period = engAlarmMatch.groupValues.getOrNull(3)?.lowercase()
            if (period == "pm" && hour < 12) hour += 12
            if (period == "am" && hour == 12) hour = 0
            val (ok, msg) = deviceController.setAlarm(hour, min, "Siri Alarm")
            return IntentExecutionResult("SET_ALARM", mapOf("hour" to hour.toString(), "min" to min.toString()), msg, ok, "Set alarm for $hour:$min")
        }
        if (hiAlarmMatch != null) {
            val hiPeriod = hiAlarmMatch.groupValues[1].lowercase()
            var hour = hiAlarmMatch.groupValues[2].toIntOrNull() ?: 7
            val min = hiAlarmMatch.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
            if ((hiPeriod in listOf("shaam", "sham", "raat", "rat") || (hiPeriod == "dopahar" && hour < 11)) && hour < 12) {
                hour += 12
            } else if (hiPeriod == "subah" && hour == 12) {
                hour = 0
            }
            val (ok, msg) = deviceController.setAlarm(hour, min, "Siri Alarm")
            return IntentExecutionResult("SET_ALARM", mapOf("hour" to hour.toString(), "min" to min.toString()), msg, ok, "Set alarm for $hour:$min")
        }

        // Scheduled & Recurring Voice Reminders (e.g. "30 minute baad reminder lagao", "remind me daily at 8 am to drink water")
        if (text.contains("reminder") || text.startsWith("remind me")) {
            val parsed = SiriFeatureParsers.parseReminderSchedule(cleanedRaw)
            if (parsed != null) {
                if (onReminderCreateTrigger != null) {
                    return onReminderCreateTrigger.invoke(parsed.title, parsed.triggerTimeMillis, parsed.repeat)
                }
                val item = ReminderItem(title = parsed.title, triggerTimeMillis = parsed.triggerTimeMillis, repeat = parsed.repeat)
                val sched = deviceController.reminderScheduler.scheduleReminder(item)
                val timeStr = SiriFeatureParsers.formatTimestamp(parsed.triggerTimeMillis)
                val repeatNote = if (parsed.repeat != ReminderRepeat.NONE) " (${parsed.repeat.label})" else ""
                val permNote = buildString {
                    if (sched.needsExactAlarmPermission) append(" Grant Exact Alarm access in Privacy settings for down-to-the-minute delivery.")
                    if (sched.needsNotificationPermission) append(" Enable Notification permission so reminder alerts appear.")
                }
                return IntentExecutionResult(
                    intentName = "SET_REMINDER",
                    parameters = mapOf("title" to parsed.title, "repeat" to parsed.repeat.name),
                    feedbackSpeech = "Reminder set for $timeStr$repeatNote: ${parsed.title}.$permNote",
                    success = sched.scheduled,
                    actionSummary = "Set${repeatNote} reminder: ${parsed.title}",
                    emotion = AssistantEmotion.HAPPINESS
                )
            }
        }
        return null
    }

    private fun handleCallCommand(target: String, isHi: Boolean): IntentExecutionResult {
        val digitsOnly = target.replace(Regex("[\\s-]"), "")
        val isPhoneNumber = Regex("""^\+?\d{3,15}$""").matches(digitsOnly)
        if (isPhoneNumber) {
            return queueCallConfirmation(target, digitsOnly, isHi)
        }

        return when (val outcome = deviceController.contactAssistant.searchContacts(target)) {
            is ContactSearchOutcome.PermissionMissing -> {
                val msg = if (isHi) {
                    "Contacts padhne ke liye Contacts permission zaroori hai. Kripya Contacts access allow karein."
                } else {
                    "I need Contacts permission to look up $target. Please grant Contacts permission and try again."
                }
                IntentExecutionResult("CONTACT_PERMISSION_REQUIRED", mapOf("query" to target), msg, false, "Contacts permission needed", emotion = AssistantEmotion.SERIOUSNESS)
            }
            is ContactSearchOutcome.NotFound -> {
                val msg = if (isHi) "Mujhe '$target' naam ka koi contact nahi mila." else "I couldn't find any contact named '$target'."
                IntentExecutionResult("CONTACT_NOT_FOUND", mapOf("query" to target), msg, false, "Contact not found: $target", emotion = AssistantEmotion.SERIOUSNESS)
            }
            is ContactSearchOutcome.SingleMatch -> {
                queueCallConfirmation(outcome.contact.name, outcome.contact.phoneNumber, isHi)
            }
            is ContactSearchOutcome.DuplicateMatches -> {
                pendingContactChoices = outcome.matches
                pendingContactPurpose = "CALL"
                pendingContactSmsBody = ""
                pendingContactHindi = isHi
                val optsText = outcome.matches.mapIndexed { idx, c -> "${idx + 1}. ${c.name} (${c.phoneNumber})" }.joinToString(", ")
                val msg = if (isHi) {
                    "Mujhe '$target' se milte-julte kai contacts mile: $optsText. Aap kise call karna chahte hain?"
                } else {
                    "I found multiple contacts for '$target': $optsText. Which one should I call?"
                }
                IntentExecutionResult(
                    intentName = "CONTACT_DISAMBIGUATION",
                    parameters = mapOf("query" to target, "count" to outcome.matches.size.toString()),
                    feedbackSpeech = msg,
                    success = true,
                    actionSummary = "Multiple contacts for '$target'",
                    emotion = AssistantEmotion.CURIOSITY,
                    contactChoices = outcome.matches
                )
            }
        }
    }

    private fun handleSmsCommand(target: String, body: String, isHi: Boolean): IntentExecutionResult {
        val digitsOnly = target.replace(Regex("[\\s-]"), "")
        val isPhoneNumber = Regex("""^\+?\d{3,15}$""").matches(digitsOnly)
        if (isPhoneNumber) {
            return queueSmsConfirmation(target, digitsOnly, body, isHi)
        }

        return when (val outcome = deviceController.contactAssistant.searchContacts(target)) {
            is ContactSearchOutcome.PermissionMissing -> {
                IntentExecutionResult(
                    intentName = "CONTACT_PERMISSION_REQUIRED",
                    parameters = mapOf("query" to target),
                    feedbackSpeech = "I need Contacts permission to look up $target before preparing a message.",
                    success = false,
                    actionSummary = "Contacts permission needed",
                    emotion = AssistantEmotion.SERIOUSNESS
                )
            }
            is ContactSearchOutcome.NotFound -> {
                IntentExecutionResult(
                    intentName = "CONTACT_NOT_FOUND",
                    parameters = mapOf("query" to target),
                    feedbackSpeech = if (isHi) "Mujhe '$target' contact nahi mila." else "I couldn't find any contact named '$target'.",
                    success = false,
                    actionSummary = "Contact not found: $target",
                    emotion = AssistantEmotion.SERIOUSNESS
                )
            }
            is ContactSearchOutcome.SingleMatch -> {
                queueSmsConfirmation(outcome.contact.name, outcome.contact.phoneNumber, body, isHi)
            }
            is ContactSearchOutcome.DuplicateMatches -> {
                pendingContactChoices = outcome.matches
                pendingContactPurpose = "SMS"
                pendingContactSmsBody = body
                pendingContactHindi = isHi
                val optsText = outcome.matches.mapIndexed { idx, c -> "${idx + 1}. ${c.name} (${c.phoneNumber})" }.joinToString(", ")
                val msg = if (isHi) {
                    "Mujhe '$target' ke kai contacts mile: $optsText. Message kise bhejna hai?"
                } else {
                    "I found multiple contacts for '$target': $optsText. Which one should I message?"
                }
                IntentExecutionResult(
                    intentName = "CONTACT_DISAMBIGUATION",
                    parameters = mapOf("query" to target),
                    feedbackSpeech = msg,
                    success = true,
                    actionSummary = "Select contact for message",
                    emotion = AssistantEmotion.CURIOSITY,
                    contactChoices = outcome.matches
                )
            }
        }
    }

    private fun queueCallConfirmation(contactName: String, phoneNumber: String, isHi: Boolean): IntentExecutionResult {
        val display = if (contactName == phoneNumber) phoneNumber else "$contactName ($phoneNumber)"
        val prompt = if (isHi) {
            "Kya main $display ko call karoon? Confirm karne ke liye haan ya cancel karne ke liye nahi boliye."
        } else {
            "Would you like me to call $display? Say yes to confirm or no to cancel."
        }
        val pending = PendingConfirmation(
            actionType = ConfirmationActionType.DIAL_PHONE,
            targetLabel = contactName,
            summary = "calling $display",
            promptSpeech = prompt,
            payload = mapOf("number" to phoneNumber, "contact_name" to contactName),
            isHindiOrHinglish = isHi
        )
        pendingConfirmation = pending
        val res = IntentExecutionResult(
            intentName = "DIAL_PHONE",
            parameters = mapOf("number" to phoneNumber, "contact_name" to contactName, "contact_number" to phoneNumber),
            feedbackSpeech = prompt,
            success = true,
            actionSummary = "Awaiting call confirmation for $display",
            requiresConfirmation = true,
            pendingConfirmation = pending,
            emotion = AssistantEmotion.CURIOSITY
        )
        recordLastIntent(res)
        return res
    }

    private fun queueSmsConfirmation(contactName: String, phoneNumber: String, messageBody: String, isHi: Boolean): IntentExecutionResult {
        val display = if (contactName == phoneNumber) phoneNumber else "$contactName ($phoneNumber)"
        val bodySnippet = if (messageBody.isBlank()) "(empty)" else "\"$messageBody\""
        val prompt = if (isHi) {
            "Kya main $display ko yeh message bhejoon: $bodySnippet? Haan ya nahi boliye."
        } else {
            "Ready to send message to $display saying $bodySnippet. Say yes to confirm or no to cancel."
        }
        val pending = PendingConfirmation(
            actionType = ConfirmationActionType.SEND_SMS,
            targetLabel = contactName,
            summary = "sending message to $display",
            promptSpeech = prompt,
            payload = mapOf("number" to phoneNumber, "body" to messageBody, "contact_name" to contactName),
            isHindiOrHinglish = isHi
        )
        pendingConfirmation = pending
        val res = IntentExecutionResult(
            intentName = "SEND_SMS",
            parameters = mapOf("number" to phoneNumber, "body" to messageBody, "contact_name" to contactName, "contact_number" to phoneNumber),
            feedbackSpeech = prompt,
            success = true,
            actionSummary = "Awaiting confirmation to send SMS to $display",
            requiresConfirmation = true,
            pendingConfirmation = pending,
            emotion = AssistantEmotion.CURIOSITY
        )
        recordLastIntent(res)
        return res
    }

    private fun resolvePendingContactDisambiguation(lowerText: String): IntentExecutionResult? {
        val choices = pendingContactChoices
        if (choices.isEmpty()) return null
        if (SiriFeatureParsers.isCancellationReply(lowerText)) {
            clearPendingContactDisambiguation()
            return IntentExecutionResult("CONTACT_CANCELLED", emptyMap(), "Cancelled contact selection.", true, "Cancelled contact selection")
        }

        val indexPick = when {
            lowerText in setOf("1", "one", "first", "first one", "1st", "pehla", "पहला") -> 0
            lowerText in setOf("2", "two", "second", "second one", "2nd", "doosra", "dusra", "दूसरा") -> 1
            lowerText in setOf("3", "three", "third", "third one", "3rd", "teesra", "तीसरा") -> 2
            lowerText in setOf("4", "four", "fourth", "4th", "chautha") -> 3
            else -> null
        }

        val selected = if (indexPick != null && indexPick in choices.indices) {
            choices[indexPick]
        } else {
            choices.firstOrNull { it.name.equals(lowerText, ignoreCase = true) }
                ?: choices.firstOrNull { it.name.lowercase().contains(lowerText) && lowerText.length >= 2 }
                ?: choices.firstOrNull { it.phoneNumber.endsWith(lowerText.filter { ch -> ch.isDigit() }.takeLast(4)) && lowerText.any { ch -> ch.isDigit() } }
        } ?: return null

        val purpose = pendingContactPurpose
        val smsBody = pendingContactSmsBody
        val isHi = pendingContactHindi
        clearPendingContactDisambiguation()

        return if (purpose == "SMS") {
            queueSmsConfirmation(selected.name, selected.phoneNumber, smsBody, isHi)
        } else {
            queueCallConfirmation(selected.name, selected.phoneNumber, isHi)
        }
    }

    fun selectPendingContact(contact: ContactMatch): IntentExecutionResult? {
        if (pendingContactChoices.isEmpty()) return null
        val purpose = pendingContactPurpose
        val smsBody = pendingContactSmsBody
        val isHi = pendingContactHindi
        clearPendingContactDisambiguation()
        val res = if (purpose == "SMS") {
            queueSmsConfirmation(contact.name, contact.phoneNumber, smsBody, isHi)
        } else {
            queueCallConfirmation(contact.name, contact.phoneNumber, isHi)
        }
        recordLastIntent(res)
        return res
    }

    private fun handleNavigationAndPlaces(cleanedRaw: String, text: String, isHi: Boolean): IntentExecutionResult? {
        if (SiriFeatureParsers.matchesAny(text, listOf("open google maps", "open maps", "maps kholo", "google maps kholo", "maps open karo"))) {
            val (ok, msg) = deviceController.openGoogleMaps()
            return IntentExecutionResult("OPEN_MAPS", emptyMap(), msg, ok, "Opened Google Maps")
        }

        val placeMatch = Regex("""^(?:search\s+places?|find\s+places?|search\s+on\s+maps|find\s+nearby|nearby)\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
            ?: Regex("""^find\s+(.+?)\s+(?:near\s+me|nearby|on\s+maps)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
            ?: Regex("""^(?:aas\s+paas|mere\s+paas)\s+(.+?)\s+(?:dhundo|khojo|dikhao)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (placeMatch != null) {
            val place = placeMatch.groupValues[1].trim()
            val (ok, msg) = deviceController.searchPlacesOnMaps(place)
            return IntentExecutionResult("PLACE_SEARCH", mapOf("place" to place), msg, ok, if (ok) "Searched places: $place" else "Offline: place search skipped")
        }

        val navMatch = Regex("""^(?:navigate\s+to|directions\s+to|take\s+me\s+to|route\s+to|start\s+navigation\s+to)\s+(.+)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
            ?: Regex("""^(.+?)\s+(?:ka\s+rasta\s+dikhao|ke\s+liye\s+navigation\s+shuru\s+karo)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        if (navMatch != null) {
            val destination = navMatch.groupValues[1].trim()
            val (ok, msg) = deviceController.openNavigation(destination)
            return IntentExecutionResult("NAVIGATION", mapOf("destination" to destination), msg, ok, if (ok) "Navigating to $destination" else "Offline: navigation unavailable")
        }
        return null
    }

    private fun handleOpenApp(appToOpen: String, isHi: Boolean): IntentExecutionResult {
        val outcome = deviceController.openAppWithResult(appToOpen)
        val res = when (outcome) {
            is AppLaunchOutcome.Launched -> {
                clearPendingAppDisambiguation()
                val speech = if (isHi) "${outcome.appName} khol rahi hoon." else outcome.message
                IntentExecutionResult(
                    intentName = "OPEN_APP",
                    parameters = mapOf("app" to outcome.appName, "package" to outcome.packageName, "verified" to "true"),
                    feedbackSpeech = speech,
                    success = true,
                    actionSummary = "Opened ${outcome.appName}",
                    emotion = AssistantEmotion.HAPPINESS
                )
            }
            is AppLaunchOutcome.DisambiguationNeeded -> {
                val options = outcome.candidates.map { AppChoiceOption(it.appName, it.packageName, it.activityName) }
                pendingAppChoices = options
                pendingDisambiguationHindi = isHi
                val namesJoined = formatAppOptionsForSpeech(options.map { it.appName }, isHi)
                val speech = if (isHi) {
                    "Mujhe kai milte-julte apps mile hain: $namesJoined. Aap kaunsa kholna chahte hain?"
                } else {
                    "I found multiple matching apps: $namesJoined. Which one would you like to open?"
                }
                IntentExecutionResult(
                    intentName = "OPEN_APP_DISAMBIGUATION",
                    parameters = mapOf("query" to appToOpen, "count" to options.size.toString()),
                    feedbackSpeech = speech,
                    success = true,
                    actionSummary = "Multiple matches for '$appToOpen'",
                    emotion = AssistantEmotion.CURIOSITY,
                    appChoices = options
                )
            }
            is AppLaunchOutcome.NotFound -> {
                clearPendingAppDisambiguation()
                val speech = if (isHi) "$appToOpen aapke phone mein install nahi mila." else outcome.message
                IntentExecutionResult(
                    intentName = "OPEN_APP",
                    parameters = mapOf("app" to appToOpen, "verified" to "false"),
                    feedbackSpeech = speech,
                    success = false,
                    actionSummary = "App not found: $appToOpen",
                    emotion = AssistantEmotion.SERIOUSNESS
                )
            }
            is AppLaunchOutcome.LaunchFailed -> {
                clearPendingAppDisambiguation()
                IntentExecutionResult(
                    intentName = "OPEN_APP",
                    parameters = mapOf("app" to outcome.appName, "package" to outcome.packageName, "verified" to "false"),
                    feedbackSpeech = outcome.message,
                    success = false,
                    actionSummary = "Could not open ${outcome.appName}",
                    emotion = AssistantEmotion.SERIOUSNESS
                )
            }
        }
        recordLastIntent(res)
        return res
    }

    private fun handleWebSearch(cleanedRaw: String, text: String, isHi: Boolean): IntentExecutionResult? {
        val officialSiteMatch = Regex("""^(?:official\s+(?:website|site|link)\s+(?:of|for)\s+|(.+?)\s+official\s+(?:website|site))(.*)$""", RegexOption.IGNORE_CASE).find(cleanedRaw)
        val searchPrefixMatch = if (text.startsWith("search for ") || text.startsWith("google ") || text.startsWith("search today's news") || text.startsWith("search ") || text.startsWith("khojo ")) {
            cleanedRaw.replace(Regex("""^(?:search\s+for|google|search|khojo)\s+""", RegexOption.IGNORE_CASE), "").trim()
        } else null

        val query = when {
            officialSiteMatch != null -> (officialSiteMatch.groupValues[1].ifBlank { officialSiteMatch.groupValues[2] }).trim()
            searchPrefixMatch != null -> searchPrefixMatch
            else -> return null
        }
        if (query.isBlank()) return null

        val links = deviceController.resolveOfficialWebsiteLinks(query)
        val (ok, msg) = deviceController.searchWeb(query)
        val topUrl = links.firstOrNull()?.url.orEmpty()
        return IntentExecutionResult(
            intentName = "WEB_SEARCH",
            parameters = mapOf("query" to query, "top_url" to topUrl),
            feedbackSpeech = msg,
            success = ok,
            actionSummary = if (ok) "Web search: $query" else "Offline: web search unavailable",
            webSources = links,
            emotion = if (ok) AssistantEmotion.CURIOSITY else AssistantEmotion.SERIOUSNESS
        )
    }

    fun extractAppToOpen(normalizedLowerText: String): String? {
        val cleaned = normalizedLowerText
            .replace(Regex("""^(?:please|can you|could you|would you|zara|ज़रा|कृपया|abhi|अभी|ek baar|mujhe)\s+"""), "")
            .replace(Regex("""^(?:mere\s+phone\s+mein|mere\s+mobile\s+mein|mere\s+device\s+mein|is\s+phone\s+mein|phone\s+mein|mobile\s+mein|device\s+mein|phone\s+me|mobile\s+me|on\s+my\s+phone|in\s+my\s+phone|on\s+this\s+phone|mere\s+phone\s+ka|mere\s+phone\s+ki|मेरे\s+फोन\s+में|मेरे\s+मोबाइल\s+में|फोन\s+में|मोबाइल\s+में)\s+"""), "")
            .replace(Regex("""^(?:mera|meri|mere|मेरा|मेरी|मेरे)\s+"""), "")
            .replace(Regex("""[.!?।]+$"""), "")
            .trim()
        if (cleaned.isBlank()) return null

        val prefixRegex = Regex("""^(?:open(?:\s+up)?|launch|start|run|kholo|khol\s+do|kholna|kholiye|khol\s+dijiye|chalu\s+karo|chalu\s+kijiye|chalao|chalaiye|open\s+karo|open\s+kijiye|खोलो|खोल\s+दो|खोलें|खोलिए|खोल\s+दीजिए|चालू\s+करो|चालू\s+कीजिए|चलाओ|चलाइए|ओपन\s+करो|ओपन\s+कीजिए)\s+(.+)$""")
        val postfixRegex = Regex("""^(.+?)\s+(?:kholo|khol\s+do|khol\s+dena|kholna|kholna\s+hai|kholiye|khol\s+dijiye|open\s+karo|open\s+kar\s+do|open\s+kijiye|open\s+kar\s+dijiye|open|chalu\s+karo|chalu\s+kar\s+do|chalu\s+kijiye|start\s+karo|start\s+kijiye|launch\s+karo|chalao|chalaiye|lagao|on\s+karo|खोलो|खोल\s+दो|खोलें|खोलिए|खोल\s+दीजिए|खोलना\s+है|चालू\s+करो|चालू\s+कर\s+दो|चालू\s+कीजिए|चलाओ|चलाइए|ओपन\s+करो|ओपन\s+कर\s+दो|ओपन\s+कीजिए)$""")

        var rawCandidate = prefixRegex.find(cleaned)?.groupValues?.getOrNull(1)
            ?: postfixRegex.find(cleaned)?.groupValues?.getOrNull(1)
            ?: return null

        rawCandidate = rawCandidate
            .replace(Regex("""^(?:mere\s+phone\s+mein|mere\s+mobile\s+mein|phone\s+mein|mobile\s+mein|on\s+my\s+phone|in\s+my\s+phone|मेरे\s+फोन\s+में|फोन\s+में)\s+"""), "")
            .replace(Regex("""^(?:the|a|an|my|mera|meri|mere|मेरा|मेरी|मेरे|app|application|ऐप|एप्लीकेशन)\s+"""), "")
            .replace(Regex("""\s+(?:on\s+my\s+phone|in\s+my\s+phone|on\s+phone|in\s+phone|phone\s+mein)$"""), "")
            .replace(Regex("""\s+(?:app|application|ko|please|karo|kar\s+do|kijiye|ऐप|एप्लीकेशन|को|प्लीज)$"""), "")
            .trim()

        if (rawCandidate.isBlank()) return null
        val nonAppWords = setOf("it", "that", "this", "up", "a", "an", "the")
        if (rawCandidate in nonAppWords) return null
        return rawCandidate
    }

    private fun resolvePendingAppDisambiguation(lowerText: String): IntentExecutionResult? {
        val choices = pendingAppChoices
        if (choices.isEmpty()) return null

        if (lowerText in setOf("cancel", "none", "never mind", "nevermind", "chhodo", "rehne do", "mat kholo")) {
            clearPendingAppDisambiguation()
            val msg = if (pendingDisambiguationHindi) "Theek hai, cancel kar diya." else "Okay, cancelled app launch."
            return IntentExecutionResult("OPEN_APP_CANCELLED", emptyMap(), msg, true, "Cancelled app selection")
        }

        val indexPick = when {
            lowerText in setOf("1", "one", "first", "first one", "1st", "pehla", "pahla", "pehle wala", "पहला") -> 0
            lowerText in setOf("2", "two", "second", "second one", "2nd", "doosra", "dusra", "doosre wala", "दूसरा") -> 1
            lowerText in setOf("3", "three", "third", "third one", "3rd", "teesra", "tisra", "तीसरा") -> 2
            lowerText in setOf("4", "four", "fourth", "fourth one", "4th", "chautha", "चौथा") -> 3
            else -> null
        }

        val selected = if (indexPick != null && indexPick in choices.indices) {
            choices[indexPick]
        } else {
            val cleanedChoice = extractAppToOpen(lowerText) ?: lowerText.removePrefix("open ").removePrefix("the ").trim()
            choices.firstOrNull { it.appName.equals(cleanedChoice, ignoreCase = true) }
                ?: choices.firstOrNull { it.appName.lowercase().contains(cleanedChoice) && cleanedChoice.length >= 2 }
                ?: choices.firstOrNull { cleanedChoice.contains(it.appName.lowercase()) }
        } ?: return null

        clearPendingAppDisambiguation()
        val outcome = deviceController.launchSpecificApp(selected.packageName, selected.activityName, selected.appName)
        return when (outcome) {
            is AppLaunchOutcome.Launched -> {
                val speech = if (pendingDisambiguationHindi) "${outcome.appName} khol rahi hoon." else outcome.message
                IntentExecutionResult("OPEN_APP", mapOf("app" to outcome.appName, "package" to outcome.packageName, "verified" to "true"), speech, true, "Opened ${outcome.appName}", emotion = AssistantEmotion.HAPPINESS)
            }
            else -> {
                val msg = (outcome as? AppLaunchOutcome.LaunchFailed)?.message ?: "Could not open ${selected.appName}."
                IntentExecutionResult("OPEN_APP", mapOf("app" to selected.appName, "verified" to "false"), msg, false, "Could not open ${selected.appName}", emotion = AssistantEmotion.SERIOUSNESS)
            }
        }
    }

    private fun formatAppOptionsForSpeech(names: List<String>, isHindi: Boolean): String {
        val conj = if (isHindi) " aur " else " or "
        return when (names.size) {
            0 -> ""
            1 -> names[0]
            2 -> "${names[0]}$conj${names[1]}"
            else -> names.dropLast(1).joinToString(", ") + conj + names.last()
        }
    }

    private fun recordLastIntent(result: IntentExecutionResult) {
        lastExecutedIntent = result.intentName
        lastParameters = result.parameters + ("feedback" to result.feedbackSpeech)
        lastAssistantSpeechText = result.feedbackSpeech
    }
}
