package com.example.model

enum class VoiceState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ERROR
}

enum class AssistantEmotion(
    val displayName: String,
    val badgeLabel: String,
    val pitchFactor: Float,
    val rateFactor: Float,
    val pauseMs: Long,
    val accentColorHex: Long
) {
    NEUTRAL("Calm", "Calm", 1.00f, 1.00f, 65L, 0xFFFFD700),
    HAPPINESS("Happy", "Happy", 1.08f, 1.06f, 55L, 0xFF00E676),
    SADNESS("Empathetic", "Empathetic", 0.89f, 0.86f, 110L, 0xFF64B5F6),
    EXCITEMENT("Excited", "Excited", 1.16f, 1.13f, 45L, 0xFFFF9100),
    SURPRISE("Surprised", "Surprised", 1.18f, 1.07f, 55L, 0xFF00F0FF),
    CURIOSITY("Curious", "Curious", 1.07f, 0.98f, 80L, 0xFFB388FF),
    SERIOUSNESS("Serious", "Focused", 0.91f, 0.93f, 95L, 0xFF90A4AE)
}

enum class VoicePersonality(val displayName: String, val speechRate: Float, val pitch: Float, val toneDesc: String) {
    FRIENDLY("Friendly (Siri Classic)", 1.0f, 1.06f, "Warm, natural, and conversational"),
    CALM("Calm & Measured", 0.94f, 0.98f, "Soothing, gentle, and paced"),
    PROFESSIONAL("Professional", 1.0f, 1.0f, "Crisp, concise, and business-ready"),
    CHEERFUL("Cheerful", 1.08f, 1.15f, "Energetic, bright, and upbeat"),
    BOLD("Bold", 1.0f, 0.88f, "Direct, punchy, and confident")
}

enum class VoiceGender(val displayName: String, val pitchFactor: Float, val description: String) {
    FEMALE("Female (Siri)", 1.18f, "Melodious, warm Siri female voice"),
    MALE("Male", 0.90f, "Deep, resonant male voice"),
    SYSTEM_DEFAULT("System Default", 1.0f, "Android device default voice")
}

enum class LanguageOption(val displayName: String, val tag: String, val speechLocaleTag: String) {
    AUTO("Auto Detect", "auto", "en-US"),
    ENGLISH("English", "en", "en-US"),
    HINDI("Hindi (हिन्दी)", "hi", "hi-IN"),
    HINGLISH("Hinglish", "hi-en", "en-IN")
}

enum class ResponseLengthPreference(val displayName: String, val instruction: String) {
    CONCISE("Concise", "Keep the answer extremely concise, punchy, and direct in 1-2 short sentences."),
    BALANCED("Balanced", "Keep the answer conversational, warm, and natural in 2-3 sentences."),
    DETAILED("Detailed", "Provide a comprehensive, detailed explanation covering nuances."),
    STEP_BY_STEP("Step-by-step", "Provide clear step-by-step numbered instructions.")
}

enum class WakeWordOption(val phrase: String, val label: String) {
    HEY_SIRI("hey siri", "Hey Siri"),
    SIRI("siri", "Siri"),
    SUNO_SIRI("suno siri", "Suno Siri / Sun Siri")
}

enum class AssistantMode(val displayName: String, val shortLabel: String, val description: String) {
    AUTO("Auto (Smart Switch)", "Auto", "Uses Online AI when connected and seamlessly switches Offline when disconnected"),
    ONLINE("Online AI", "Online", "Full Gemini/OpenAI streaming, live web search, and map grounding"),
    OFFLINE("Offline Mode", "Offline", "Zero-internet device controls, apps, alarms, math, Q&A & local office tools"),
    OFFICE("Office & Study", "Office", "Structured emails, notes summary, work planning, revision flashcards & study help")
}

enum class InformationFreshness(val label: String, val badgeColorHex: Long) {
    VERIFIED_SYSTEM("Verified System Action", 0xFF00F0FF),
    WEB_SEARCH("Live Web Grounding", 0xFF00FFB2),
    AI_INTERPRETATION("AI Generated Interpretation", 0xFFB388FF)
}

data class WebSourceLink(
    val title: String,
    val url: String,
    val snippet: String = ""
)

data class AppChoiceOption(
    val appName: String,
    val packageName: String,
    val activityName: String
)

data class ContactMatch(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val typeLabel: String = "Mobile"
)

enum class ConfirmationActionType {
    DIAL_PHONE,
    SEND_SMS,
    DELETE_NOTE,
    DELETE_TASK,
    CANCEL_REMINDER,
    DELETE_MEMORY,
    CLEAR_HISTORY,
    CUSTOM
}

data class PendingConfirmation(
    val actionType: ConfirmationActionType,
    val targetLabel: String,
    val summary: String,
    val promptSpeech: String,
    val payload: Map<String, String> = emptyMap(),
    val isHindiOrHinglish: Boolean = false
)

enum class ReminderRepeat(val label: String, val intervalMillis: Long) {
    NONE("One-time", 0L),
    HOURLY("Hourly", 60L * 60L * 1000L),
    DAILY("Daily", 24L * 60L * 60L * 1000L),
    WEEKLY("Weekly", 7L * 24L * 60L * 60L * 1000L)
}

data class ReminderItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val triggerTimeMillis: Long,
    val repeat: ReminderRepeat = ReminderRepeat.NONE,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class MemoryItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val category: String = "Personal",
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sessionId: String = "default",
    val text: String,
    val isFromUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val actionType: String? = null,
    val actionSummary: String? = null,
    val imageUri: String? = null,
    val isError: Boolean = false,
    val sources: List<WebSourceLink> = emptyList(),
    val freshness: InformationFreshness = InformationFreshness.AI_INTERPRETATION,
    val latencyMs: Long? = null,
    val emotion: AssistantEmotion = AssistantEmotion.NEUTRAL,
    val appChoices: List<AppChoiceOption> = emptyList(),
    val contactChoices: List<ContactMatch> = emptyList(),
    val requiresConfirmation: Boolean = false,
    val assistantMode: AssistantMode? = null
)

data class ConversationSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "General" // "General", "Work", "Personal", "Research", "Study"
)

data class IntentExecutionResult(
    val intentName: String,
    val parameters: Map<String, String> = emptyMap(),
    val feedbackSpeech: String,
    val success: Boolean,
    val actionSummary: String? = null,
    val requiresConfirmation: Boolean = false,
    val pendingConfirmation: PendingConfirmation? = null,
    val webSources: List<WebSourceLink> = emptyList(),
    val isReversible: Boolean = false,
    val undoAction: String? = null,
    val emotion: AssistantEmotion = AssistantEmotion.NEUTRAL,
    val appChoices: List<AppChoiceOption> = emptyList(),
    val contactChoices: List<ContactMatch> = emptyList()
)

data class AutomationStep(
    val id: String = java.util.UUID.randomUUID().toString(),
    val actionType: String, // "TIMER", "APP", "TORCH", "NOTE", "VOLUME", "SPEAK", "ALARM", "SETTING"
    val parameter: String,
    val description: String
)

data class AutomationRoutine(
    val id: String,
    val name: String,
    val triggerPhrase: String,
    val steps: List<AutomationStep>,
    val isEnabled: Boolean = true,
    val category: String = "Custom", // "Productivity", "Lifestyle", "Relax", "Travel"
    val scheduledTime: String? = null // e.g. "07:30"
)

data class VoiceShortcut(
    val id: String = java.util.UUID.randomUUID().toString(),
    val triggerPhrase: String,
    val commandText: String,
    val customSpokenReply: String? = null,
    val isEnabled: Boolean = true
)

data class AutomationRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val conditionType: String, // "BATTERY_LOW", "TIME_EVENING", "TIME_MORNING"
    val thresholdValue: String, // e.g. "20", "22:00"
    val actionType: String, // "MUTE_AUDIO", "BEDTIME_ROUTINE", "FLASH_OFF"
    val isEnabled: Boolean = true
)

data class ActionExecutionLog(
    val id: String = java.util.UUID.randomUUID().toString(),
    val actionName: String,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isSuccess: Boolean = true,
    val isReversible: Boolean = false,
    val undoPayload: String? = null
)

data class UserPreferences(
    val userName: String = "Friend",
    val homeLocation: String = "Home",
    val workLocation: String = "Office",
    val preferredTempUnit: String = "Celsius (°C)", // "Celsius (°C)", "Fahrenheit (°F)"
    val morningBriefingTime: String = "08:00 AM",
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: String = "22:00",
    val quietHoursEnd: String = "07:00",
    val filterSensitiveNotifications: Boolean = true,
    val reducedMotion: Boolean = false,
    val voiceSessionTimeoutSeconds: Int = 30, // 15, 30, 60, 0 (no timeout)
    val memoryEnabled: Boolean = true
)

data class TaskItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val timeLabel: String = "Today",
    val category: String = "General",
    val priority: String = "Normal", // "High", "Normal", "Low"
    val isCompleted: Boolean = false
)

data class NoteItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val category: String = "General", // "Personal", "Work", "Ideas", "Study"
    val timestamp: Long = System.currentTimeMillis()
)

data class FlashcardItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val question: String,
    val answer: String,
    val topic: String = "General"
)

data class SmartDevice(
    val id: String,
    val name: String,
    val type: String, // "LIGHT", "PLUG", "THERMOSTAT"
    val isOn: Boolean = false,
    val stateValue: String = "Off"
)

const val DEFAULT_SIRI_SYSTEM_PROMPT: String =
    "You are Siri, an emotionally intelligent personal voice assistant.\n" +
    "1. Voice-First & Concise: Answer conversationally in 1-2 crisp, direct sentences (maximum 3). Never write essays or walls of text.\n" +
    "2. Expressive & Human-Like: Vary your phrasing naturally across turns. Never repeat the same canned openings or robotic filler like 'Certainly!', 'As an AI language model...', or 'Sure, here are some points:'.\n" +
    "3. Emotional Intelligence: Mirror and respond to the user's feelings with genuine warmth, empathy, excitement, curiosity, or focus.\n" +
    "4. Clean Spoken Words: Never use asterisks, markdown bolding, hashes, bullets, or raw URLs. Keep every response voice-ready for text-to-speech.\n" +
    "5. Language Matching: Respond in the matching language of the user's prompt (English, Hindi, or Hinglish) using natural colloquial phrasing.\n" +
    "6. Subtle Wit & Charm: For playful or personal questions, answer with Siri's signature dry wit and warmth."

data class AiConfig(
    val provider: String = "Google Gemini", // "Google Gemini", "Custom / OpenAI API"
    val model: String = "gemini-2.5-flash",
    val customApiKey: String = "",
    val customEndpoint: String = "", // e.g. "https://api.openai.com/v1" or custom customer proxy endpoint
    val temperature: Float = 0.7f,
    val maxOutputTokens: Int = 384,
    val responseLength: ResponseLengthPreference = ResponseLengthPreference.BALANCED,
    val customGreeting: String = "Hello! I am Siri. How can I help you today?",
    val systemPrompt: String = DEFAULT_SIRI_SYSTEM_PROMPT
)

data class UpdateInfo(
    val latestVersionCode: Int = 3,
    val latestVersionName: String = "1.2",
    val releaseNotes: String = "Added Offline Mode, Online Mode, Office & Study Mode, Automatic Mode Detection, Android Keystore encrypted API key storage, and full voice/UI stability fixes.",
    val downloadUrl: String = "https://github.com/princepremi7461889152-oss/Siri_Ai_Apk/releases",
    val isMandatory: Boolean = false
)
