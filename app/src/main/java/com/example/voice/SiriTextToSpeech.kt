package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import com.example.model.AssistantEmotion
import com.example.model.LanguageOption
import com.example.model.VoiceGender
import com.example.model.VoicePersonality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class SiriTextToSpeech(
    private val context: Context,
    private val onSpeechAudioLevel: ((Float) -> Unit)? = null,
    private val onStateChange: (isSpeaking: Boolean) -> Unit
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var isInitialized = false

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null

    private var currentPersonality: VoicePersonality = VoicePersonality.FRIENDLY
    private var currentVoiceGender: VoiceGender = VoiceGender.FEMALE
    private var currentLanguage: LanguageOption = LanguageOption.AUTO
    private var customSpeedMultiplier: Float = 1.0f
    private var customPitchMultiplier: Float = 1.0f

    private var activeFinalUtteranceId: String? = null
    private val utteranceTexts = ConcurrentHashMap<String, String>()
    private val utteranceEmotions = ConcurrentHashMap<String, AssistantEmotion>()

    // Real-time TTS speech envelope tracking for synchronized orb & mouth animation
    private val envelopeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var envelopeJob: Job? = null

    @Volatile private var isCurrentlySpeaking = false
    @Volatile private var hasRangeStartCallbacks = false
    @Volatile private var targetVocalLevel = 0.08f
    @Volatile private var smoothedVocalLevel = 0.08f
    @Volatile private var lastRangeEventMs = 0L
    @Volatile private var activeChunkId: String? = null
    @Volatile private var activeChunkStartedAtMs = 0L

    // Caching to avoid repeated IPC lookups to engine.voices on every speak() call
    private val voiceCache = ConcurrentHashMap<String, Voice>()
    private var lastAppliedLocaleTag: String? = null
    private var lastAppliedGender: VoiceGender? = null
    private var lastAppliedPitch: Float = -1f
    private var lastAppliedRate: Float = -1f
    private var preferOfflineVoices: Boolean = false
    private var defaultSystemVoice: Voice? = null
    private var pendingUtterance: Triple<String, AssistantEmotion, String>? = null

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            defaultSystemVoice = try { tts?.defaultVoice } catch (_: Exception) { null }
            applyVoiceSettings(AssistantEmotion.NEUTRAL)
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    requestAudioFocus()
                    isCurrentlySpeaking = true
                    hasRangeStartCallbacks = false
                    activeChunkId = utteranceId
                    activeChunkStartedAtMs = System.currentTimeMillis()
                    lastRangeEventMs = activeChunkStartedAtMs
                    val text = utteranceId?.let { utteranceTexts[it] }.orEmpty()
                    val emotion = utteranceId?.let { utteranceEmotions[it] } ?: AssistantEmotion.NEUTRAL
                    targetVocalLevel = if (text.isNotBlank()) computeWordVocalEnergy(text.take(12), emotion) else 0.45f
                    startSpeechEnvelopeLoop()
                    onStateChange(true)
                }

                override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                    val fullText = utteranceId?.let { utteranceTexts[it] } ?: return
                    if (start < 0 || end <= start || start >= fullText.length) return
                    val safeEnd = end.coerceAtMost(fullText.length)
                    val spokenToken = fullText.substring(start, safeEnd)
                    val emotion = utteranceEmotions[utteranceId] ?: AssistantEmotion.NEUTRAL

                    hasRangeStartCallbacks = true
                    lastRangeEventMs = System.currentTimeMillis()
                    targetVocalLevel = computeWordVocalEnergy(spokenToken, emotion)
                }

                override fun onDone(utteranceId: String?) {
                    utteranceId?.let {
                        utteranceTexts.remove(it)
                        utteranceEmotions.remove(it)
                    }
                    if (utteranceId == activeFinalUtteranceId || activeFinalUtteranceId == null) {
                        stopSpeechEnvelopeLoop()
                        abandonAudioFocus()
                        onStateChange(false)
                    } else {
                        // Brief natural dip between sequential sentence chunks
                        targetVocalLevel = 0.10f
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    stopSpeechEnvelopeLoop()
                    abandonAudioFocus()
                    onStateChange(false)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    stopSpeechEnvelopeLoop()
                    abandonAudioFocus()
                    onStateChange(false)
                }
            })
            Log.d("SiriTTS", "TextToSpeech successfully initialized")
            pendingUtterance?.let { (text, emo, id) ->
                pendingUtterance = null
                speak(text, emo, id)
            }
        } else {
            isInitialized = false
            pendingUtterance = null
            onStateChange(false)
            Log.e("SiriTTS", "TextToSpeech initialization failed with status $status")
        }
    }

    /**
     * Computes a realistic vocal energy value (0.14..0.98) for a spoken word/token
     * so orb pulsing and mouth aperture match real TTS word cadence.
     */
    fun computeWordVocalEnergy(token: String, emotion: AssistantEmotion): Float {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) return 0.14f

        val vowels = trimmed.count {
            it.lowercaseChar() in charArrayOf('a', 'e', 'i', 'o', 'u', 'y', 'अ', 'आ', 'इ', 'ई', 'उ', 'ऊ', 'ए', 'ऐ', 'ओ', 'औ', 'ा', 'ि', 'ी', 'ु', 'ू', 'े', 'ै', 'ो', 'ौ')
        }
        val lengthFactor = (trimmed.length.coerceIn(1, 10) / 10f) * 0.25f
        val vowelFactor = (vowels.coerceIn(1, 5) / 5f) * 0.28f
        val emphasisBoost = if (trimmed.contains('!') || trimmed.contains('?')) 0.16f else 0f
        val emotionGain = when (emotion) {
            AssistantEmotion.EXCITEMENT -> 0.18f
            AssistantEmotion.SURPRISE -> 0.16f
            AssistantEmotion.HAPPINESS -> 0.10f
            AssistantEmotion.SADNESS -> -0.14f
            AssistantEmotion.SERIOUSNESS -> -0.05f
            else -> 0.0f
        }
        return (0.34f + lengthFactor + vowelFactor + emphasisBoost + emotionGain).coerceIn(0.18f, 0.98f)
    }

    private fun startSpeechEnvelopeLoop() {
        if (envelopeJob?.isActive == true) return
        envelopeJob = envelopeScope.launch {
            while (isActive && isCurrentlySpeaking) {
                val now = System.currentTimeMillis()
                if (!hasRangeStartCallbacks) {
                    // Fallback word-timed cadence for engines without onRangeStart (API 24-25)
                    val chunkId = activeChunkId
                    val chunkText = chunkId?.let { utteranceTexts[it] }.orEmpty()
                    val emotion = chunkId?.let { utteranceEmotions[it] } ?: AssistantEmotion.NEUTRAL
                    if (chunkText.isNotBlank()) {
                        val words = chunkText.split(Regex("""\s+""")).filter { it.isNotBlank() }
                        if (words.isNotEmpty()) {
                            val rate = lastAppliedRate.takeIf { it > 0f } ?: 1.0f
                            val msPerWord = (240f / rate).toLong().coerceIn(120L, 420L)
                            val elapsed = (now - activeChunkStartedAtMs).coerceAtLeast(0L)
                            val wordIdx = ((elapsed / msPerWord) % words.size).toInt()
                            val withinWordPhase = ((elapsed % msPerWord).toFloat() / msPerWord.toFloat())
                            val baseEnergy = computeWordVocalEnergy(words[wordIdx], emotion)
                            // Natural syllable bell curve inside each word
                            val syllableBell = kotlin.math.sin(withinWordPhase * Math.PI).toFloat().coerceIn(0.25f, 1f)
                            targetVocalLevel = (baseEnergy * syllableBell).coerceIn(0.14f, 0.96f)
                        }
                    }
                } else if (now - lastRangeEventMs > 210L) {
                    // Natural inter-word / comma pause decay between real onRangeStart events
                    targetVocalLevel = 0.14f
                }

                smoothedVocalLevel = smoothedVocalLevel * 0.45f + targetVocalLevel * 0.55f
                onSpeechAudioLevel?.invoke(smoothedVocalLevel)
                delay(32L)
            }
        }
    }

    private fun stopSpeechEnvelopeLoop() {
        isCurrentlySpeaking = false
        hasRangeStartCallbacks = false
        activeChunkId = null
        envelopeJob?.cancel()
        envelopeJob = null
        targetVocalLevel = 0.08f
        smoothedVocalLevel = 0.08f
        onSpeechAudioLevel?.invoke(0.08f)
    }

    fun setPersonality(personality: VoicePersonality) {
        currentPersonality = personality
        applyVoiceSettings(AssistantEmotion.NEUTRAL)
    }

    fun setVoiceGender(gender: VoiceGender) {
        currentVoiceGender = gender
        lastAppliedGender = null
        applyVoiceSettings(AssistantEmotion.NEUTRAL)
    }

    fun setLanguage(language: LanguageOption) {
        currentLanguage = language
        lastAppliedLocaleTag = null
        applyVoiceSettings(AssistantEmotion.NEUTRAL)
    }

    fun setPreferOffline(preferOffline: Boolean) {
        if (preferOfflineVoices != preferOffline) {
            preferOfflineVoices = preferOffline
            voiceCache.clear()
            lastAppliedGender = null
            applyVoiceSettings(AssistantEmotion.NEUTRAL)
        }
    }

    fun setSpeedMultiplier(multiplier: Float) {
        customSpeedMultiplier = multiplier.coerceIn(0.5f, 2.0f)
        applyVoiceSettings(AssistantEmotion.NEUTRAL)
    }

    fun setPitchMultiplier(multiplier: Float) {
        customPitchMultiplier = multiplier.coerceIn(0.5f, 2.0f)
        applyVoiceSettings(AssistantEmotion.NEUTRAL)
    }

    /**
     * Computes effective pitch and speech rate combining user settings, personality,
     * gender, and current emotional state.
     */
    fun computeEmotionProsody(emotion: AssistantEmotion): Pair<Float, Float> {
        val effectivePitch = (
            currentPersonality.pitch *
                customPitchMultiplier *
                currentVoiceGender.pitchFactor *
                emotion.pitchFactor
            ).coerceIn(0.55f, 1.85f)
        val effectiveRate = (
            currentPersonality.speechRate *
                customSpeedMultiplier *
                emotion.rateFactor
            ).coerceIn(0.60f, 1.85f)
        return effectivePitch to effectiveRate
    }

    private fun applyVoiceSettings(emotion: AssistantEmotion, overrideLocale: Locale? = null) {
        val engine = tts ?: return
        if (!isInitialized) return

        val locale = overrideLocale ?: when (currentLanguage) {
            LanguageOption.HINDI -> Locale.forLanguageTag("hi-IN")
            LanguageOption.HINGLISH -> Locale.forLanguageTag("en-IN")
            LanguageOption.ENGLISH -> Locale.US
            LanguageOption.AUTO -> Locale.US
        }

        val localeTag = locale.toLanguageTag()
        if (localeTag != lastAppliedLocaleTag || currentVoiceGender != lastAppliedGender) {
            val langResult = engine.setLanguage(locale)
            if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                engine.setLanguage(Locale.US)
            }
            applyGenderVoiceCached(locale)
            lastAppliedLocaleTag = localeTag
            lastAppliedGender = currentVoiceGender
        }

        val (effectivePitch, effectiveRate) = computeEmotionProsody(emotion)
        if (kotlin.math.abs(effectivePitch - lastAppliedPitch) > 0.01f) {
            engine.setPitch(effectivePitch)
            lastAppliedPitch = effectivePitch
        }
        if (kotlin.math.abs(effectiveRate - lastAppliedRate) > 0.01f) {
            engine.setSpeechRate(effectiveRate)
            lastAppliedRate = effectiveRate
        }
    }

    private fun applyGenderVoiceCached(targetLocale: Locale) {
        val engine = tts ?: return
        if (currentVoiceGender == VoiceGender.SYSTEM_DEFAULT) {
            try {
                val restoreVoice = defaultSystemVoice ?: engine.defaultVoice
                if (restoreVoice != null) {
                    engine.voice = restoreVoice
                }
            } catch (_: Exception) {
            }
            return
        }
        val cacheKey = "${targetLocale.language}_${currentVoiceGender.name}_off_$preferOfflineVoices"
        val cached = voiceCache[cacheKey]
        if (cached != null) {
            try {
                engine.voice = cached
            } catch (_: Exception) {
            }
            return
        }

        try {
            val availableVoices = engine.voices
            if (!availableVoices.isNullOrEmpty()) {
                val localeCandidates = availableVoices.filter { voice ->
                    voice.locale.language == targetLocale.language
                }
                val offlinePreferred = if (preferOfflineVoices) {
                    localeCandidates.filter { !it.isNetworkConnectionRequired }
                        .ifEmpty { localeCandidates }
                } else {
                    localeCandidates.sortedBy { if (it.isNetworkConnectionRequired) 1 else 0 }
                }

                val femaleTokens = listOf("female", "#female", "-fem", "_fem", "sfg", "f00", "tpf", "iog", "hia", "hic", "iol", "woman")
                val maleTokens = listOf("male", "#male", "-male", "_male", "m00", "tpc", "tpd", "iob", "iom", "hid", "hie", "man")

                val selectedVoice = when (currentVoiceGender) {
                    VoiceGender.FEMALE -> {
                        offlinePreferred.firstOrNull { voice ->
                            val n = voice.name.lowercase()
                            femaleTokens.any { n.contains(it) }
                        } ?: offlinePreferred.firstOrNull()
                    }
                    VoiceGender.MALE -> {
                        offlinePreferred.firstOrNull { voice ->
                            val n = voice.name.lowercase()
                            !n.contains("female") && maleTokens.any { n.contains(it) }
                        } ?: offlinePreferred.getOrNull(1) ?: offlinePreferred.firstOrNull()
                    }
                    VoiceGender.SYSTEM_DEFAULT -> null
                }
                if (selectedVoice != null) {
                    voiceCache[cacheKey] = selectedVoice
                    engine.voice = selectedVoice
                }
            }
        } catch (e: Exception) {
            Log.d("SiriTTS", "Voice selection fallback: ${e.message}")
        }
    }

    fun sanitizeForSpeech(text: String): String {
        return text
            .replace(Regex("""^\s*\[EMOTION\s*:\s*[A-Za-z_]+\s*\]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Regex("""\*([^*]+)\*"""), "$1")
            .replace(Regex("""__([^_]+)__"""), "$1")
            .replace(Regex("""_([^_]+)_"""), "$1")
            .replace(Regex("""#{1,6}\s*"""), "")
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
            .replace(Regex("""https?://\S+"""), "")
            .replace(Regex("""^\s*[-*•]\s+""", RegexOption.MULTILINE), "")
            .replace(Regex("""^\s*\d+\.\s+""", RegexOption.MULTILINE), "")
            .replace(Regex("""\s*[-—–]{2}\s*"""), ", ")
            .replace(Regex("""[\uD83C-\uDBFF\uDC00-\uDFFF]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun detectLanguageFromText(text: String): Locale {
        val hasDevanagari = text.any { it in '\u0900'..'\u097F' }
        if (hasDevanagari) {
            return Locale.forLanguageTag("hi-IN")
        }
        val lower = text.lowercase()
        val hinglishMarkers = listOf(
            "hai", "hain", "karo", "kya", "batao", "samachar", "chalu", "shant",
            "kaise", "samay", "namaste", "dhanyawad", "shukriya", "bilkul",
            "badhiya", "theek", "hoon", "aaj", "kal", "diya", "madad", "chutkula",
            "khol", "rahi", "raha", "nahi", "mila"
        )
        val matchCount = hinglishMarkers.count { lower.contains(Regex("""\b$it\b""")) }
        return if (matchCount >= 1) {
            Locale.forLanguageTag("en-IN")
        } else {
            Locale.US
        }
    }

    private fun requestAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener { }
                    .build()
                audioFocusRequest = focusRequest
                am.requestAudioFocus(focusRequest)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    null,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        } catch (e: Exception) {
            Log.e("SiriTTS", "Error requesting audio focus", e)
        }
    }

    private fun abandonAudioFocus() {
        try {
            val am = audioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.e("SiriTTS", "Error abandoning audio focus", e)
        }
    }

    /**
     * Speaks a complete utterance immediately with emotion-matched pitch, rate, and cadence.
     */
    fun speak(
        text: String,
        emotion: AssistantEmotion = AssistantEmotion.NEUTRAL,
        utteranceId: String = "siri_speech_${System.currentTimeMillis()}"
    ) {
        if (!isInitialized || tts == null) {
            Log.d("SiriTTS", "TTS initializing; queuing pending utterance")
            pendingUtterance = Triple(text, emotion, utteranceId)
            return
        }

        val clean = sanitizeForSpeech(text)
        if (clean.isBlank()) return

        val detectedLocale = if (currentLanguage == LanguageOption.AUTO) detectLanguageFromText(clean) else null
        applyVoiceSettings(emotion, detectedLocale)

        val sentences = clean.split(Regex("""(?<=[.!?।])\s+""")).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return

        val finalId = "${utteranceId}_final"
        activeFinalUtteranceId = finalId
        utteranceTexts.clear()
        utteranceEmotions.clear()

        for ((index, sentence) in sentences.withIndex()) {
            val isLast = (index == sentences.lastIndex)
            val chunkId = if (isLast) finalId else "${utteranceId}_part_$index"
            utteranceTexts[chunkId] = sentence
            utteranceEmotions[chunkId] = emotion
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, chunkId)
            }
            val queueMode = if (index == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(sentence, queueMode, params, chunkId)
            if (!isLast && emotion.pauseMs > 0L) {
                tts?.playSilentUtterance(emotion.pauseMs, TextToSpeech.QUEUE_ADD, null)
            }
        }
    }

    /**
     * Streams a single sentence chunk into TTS immediately as it arrives from the AI stream,
     * avoiding waiting for the full LLM response before speaking begins.
     */
    fun speakStreamingChunk(
        sentence: String,
        isFirstChunk: Boolean,
        isFinalChunk: Boolean,
        emotion: AssistantEmotion,
        streamSessionId: String
    ) {
        if (!isInitialized || tts == null) return
        val clean = sanitizeForSpeech(sentence)
        if (clean.isBlank()) {
            if (isFinalChunk && activeFinalUtteranceId == null) {
                onStateChange(false)
            }
            return
        }

        if (isFirstChunk) {
            utteranceTexts.clear()
            utteranceEmotions.clear()
            val detectedLocale = if (currentLanguage == LanguageOption.AUTO) detectLanguageFromText(clean) else null
            applyVoiceSettings(emotion, detectedLocale)
        } else {
            // Update pitch/rate if emotion refined mid-stream
            val (pitch, rate) = computeEmotionProsody(emotion)
            if (kotlin.math.abs(pitch - lastAppliedPitch) > 0.02f) {
                tts?.setPitch(pitch)
                lastAppliedPitch = pitch
            }
            if (kotlin.math.abs(rate - lastAppliedRate) > 0.02f) {
                tts?.setSpeechRate(rate)
                lastAppliedRate = rate
            }
        }

        val chunkId = if (isFinalChunk) {
            "${streamSessionId}_final".also { activeFinalUtteranceId = it }
        } else {
            val id = "${streamSessionId}_chunk_${System.nanoTime()}"
            // Keep activeFinalUtteranceId non-null so earlier chunks don't prematurely mark idle
            if (activeFinalUtteranceId == null) {
                activeFinalUtteranceId = "${streamSessionId}_pending_end"
            }
            id
        }

        utteranceTexts[chunkId] = clean
        utteranceEmotions[chunkId] = emotion

        val params = Bundle().apply {
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, chunkId)
        }
        val queueMode = if (isFirstChunk) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(clean, queueMode, params, chunkId)
        if (!isFinalChunk && emotion.pauseMs > 0L) {
            tts?.playSilentUtterance(emotion.pauseMs, TextToSpeech.QUEUE_ADD, null)
        }
    }

    /**
     * Marks the end of a streaming TTS session when no trailing sentence text remained.
     */
    fun finishStreamingSession(streamSessionId: String) {
        if (!isInitialized || tts == null) return
        val endId = "${streamSessionId}_end_marker"
        activeFinalUtteranceId = endId
        tts?.playSilentUtterance(10L, TextToSpeech.QUEUE_ADD, endId)
    }

    fun stop() {
        activeFinalUtteranceId = null
        utteranceTexts.clear()
        utteranceEmotions.clear()
        stopSpeechEnvelopeLoop()
        try {
            tts?.stop()
        } catch (e: Exception) {
            Log.e("SiriTTS", "Error stopping TTS", e)
        }
        abandonAudioFocus()
        onStateChange(false)
    }

    fun shutdown() {
        stopSpeechEnvelopeLoop()
        utteranceTexts.clear()
        utteranceEmotions.clear()
        envelopeScope.cancel()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e("SiriTTS", "Error shutting down TTS", e)
        }
        abandonAudioFocus()
        tts = null
    }
}
