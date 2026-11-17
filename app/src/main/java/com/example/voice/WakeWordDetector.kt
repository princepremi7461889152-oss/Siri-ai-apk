package com.example.voice

import com.example.model.WakeWordOption
import java.util.Locale

/**
 * Result of evaluating an audio recognition turn against strict wake-word,
 * confidence threshold, noise-floor (SNR), and anti-echo criteria.
 */
data class WakeWordEvaluation(
    val detected: Boolean,
    val commandText: String,
    val confidence: Float,
    val snrMargin: Float,
    val rejectionReason: String? = null
)

/**
 * High-precision "Hey Siri" wake-word gate with acoustic noise-floor tracking,
 * confidence thresholding, phonetic false-positive rejection, and self-echo cooldown.
 */
class WakeWordDetector(
    private val minConfidenceThreshold: Float = 0.65f,
    private val minSpeechRmsThreshold: Float = 0.18f,
    private val minSnrMarginAboveNoise: Float = 0.08f,
    private val echoCooldownMs: Long = 700L
) {
    // Exponential moving average of ambient background RMS (normalized 0.05..1.0)
    private var ambientNoiseFloor: Float = 0.10f
    private var peakUtteranceRms: Float = 0.0f
    private var speechSampleCount: Int = 0
    private var lastTtsFinishedAtMs: Long = 0L

    /**
     * Updates the adaptive background noise floor while the mic is idle/waiting,
     * and records peak speech energy during an active utterance.
     */
    fun onRmsSample(normalizedRms: Float, isSpeechActive: Boolean) {
        val clamped = normalizedRms.coerceIn(0.02f, 1.0f)
        if (isSpeechActive) {
            if (clamped > peakUtteranceRms) {
                peakUtteranceRms = clamped
            }
            speechSampleCount++
        } else {
            // Smoothly adapt ambient background noise floor (TV, fan, room hum)
            ambientNoiseFloor = ((ambientNoiseFloor * 0.65f) + (clamped * 0.35f)).coerceIn(0.05f, 0.70f)
        }
    }

    /**
     * Resets per-utterance acoustic peaks before starting a new recognition window.
     */
    fun resetUtteranceMetrics() {
        peakUtteranceRms = 0.0f
        speechSampleCount = 0
    }

    /**
     * Marks when TTS stopped speaking to reject self-echo triggers immediately after playback.
     */
    fun markAssistantFinishedSpeaking(timestampMs: Long = System.currentTimeMillis()) {
        lastTtsFinishedAtMs = timestampMs
    }

    fun getAmbientNoiseFloor(): Float = ambientNoiseFloor

    /**
     * Evaluates N-best speech hypotheses, optional Android confidence scores, and acoustic energy
     * to decide whether the user genuinely spoke the wake phrase (by default "Hey Siri").
     */
    fun evaluate(
        nBestMatches: List<String>,
        confidenceScores: FloatArray?,
        wakeWordOption: WakeWordOption = WakeWordOption.HEY_SIRI,
        isAssistantCurrentlySpeaking: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        enforceAcousticCheck: Boolean = true
    ): WakeWordEvaluation {
        if (nBestMatches.isEmpty()) {
            return WakeWordEvaluation(false, "", 0f, 0f, "No speech hypotheses")
        }

        // 1. Anti-echo guard: ignore recognition triggered by Siri's own TTS audio
        if (isAssistantCurrentlySpeaking) {
            return WakeWordEvaluation(false, "", 0f, 0f, "Assistant is speaking")
        }
        if (lastTtsFinishedAtMs > 0L && (nowMs - lastTtsFinishedAtMs) < echoCooldownMs) {
            return WakeWordEvaluation(false, "", 0f, 0f, "Anti-echo cooldown active")
        }

        // 2. Acoustic SNR & background noise filter (rejects distant TV chatter / faint murmur)
        val snrMargin = peakUtteranceRms - ambientNoiseFloor
        if (enforceAcousticCheck && speechSampleCount > 0) {
            if (peakUtteranceRms < minSpeechRmsThreshold) {
                return WakeWordEvaluation(
                    detected = false,
                    commandText = "",
                    confidence = confidenceScores?.firstOrNull() ?: 0f,
                    snrMargin = snrMargin,
                    rejectionReason = "Below speech energy threshold (peak=${"%.2f".format(peakUtteranceRms)})"
                )
            }
            if (ambientNoiseFloor > 0.14f && snrMargin < minSnrMarginAboveNoise) {
                return WakeWordEvaluation(
                    detected = false,
                    commandText = "",
                    confidence = confidenceScores?.firstOrNull() ?: 0f,
                    snrMargin = snrMargin,
                    rejectionReason = "Insufficient SNR over background noise (margin=${"%.2f".format(snrMargin)})"
                )
            }
        }

        // 3. Scan N-best hypotheses with confidence verification
        val limit = minOf(nBestMatches.size, 5)
        for (i in 0 until limit) {
            val candidate = nBestMatches[i].trim()
            if (candidate.isEmpty()) continue

            val score = if (confidenceScores != null && i < confidenceScores.size && confidenceScores[i] >= 0f) {
                confidenceScores[i]
            } else {
                // Offline engines sometimes omit CONFIDENCE_SCORES (-1 or null):
                // require exact top-1 strict phrase match when scores are absent
                if (i == 0) 0.80f else 0.50f
            }

            if (score < minConfidenceThreshold) {
                continue
            }

            val stripped = matchStrictWakePrefix(candidate, wakeWordOption)
            if (stripped != null) {
                return WakeWordEvaluation(
                    detected = true,
                    commandText = stripped,
                    confidence = score,
                    snrMargin = snrMargin,
                    rejectionReason = null
                )
            }
        }

        val topConf = confidenceScores?.firstOrNull() ?: 0f
        val reason = if (topConf in 0.01f..minConfidenceThreshold) {
            "Low recognition confidence (${"%.2f".format(topConf)})"
        } else {
            "Wake phrase '${wakeWordOption.label}' not detected at utterance start"
        }
        return WakeWordEvaluation(
            detected = false,
            commandText = "",
            confidence = topConf,
            snrMargin = snrMargin,
            rejectionReason = reason
        )
    }

    /**
     * Checks if [utterance] genuinely begins with the active wake phrase (or at most a tiny
     * 1-word conversational lead-in like "ok" / "uh") and returns the remaining command text.
     * Rejects mid-sentence mentions (e.g. TV dialogue) and false lookalikes ("seriously", "sorry").
     */
    fun matchStrictWakePrefix(utterance: String, option: WakeWordOption): String? {
        val cleaned = utterance
            .trim()
            .replace(Regex("""[“”"']+"""), "")
            .replace(Regex("""\s+"""), " ")
        if (cleaned.isBlank()) return null

        val lower = cleaned.lowercase(Locale.ROOT)

        // Reject common phonetic false positives immediately
        val falsePositiveStarts = listOf(
            "seriously", "hey serious", "hey sorry", "hey sarah", "hey saira",
            "hey sherry", "hey jerry", "hey terry", "hey city", "hey theory",
            "series", "syria"
        )
        if (falsePositiveStarts.any { lower.startsWith(it) }) {
            return null
        }

        val wakePatterns: List<Regex> = when (option) {
            WakeWordOption.HEY_SIRI -> listOf(
                // Strictly require "hey/he/ok" + "siri/seri/seree/ciri/सीरी/सिरी" at start (optional 1-word filler)
                Regex("""^(?:(?:uh+|um+|oh|acha|accha|please)\s+)?(?:hey|he|hay|ok|okay)\s+(?:siri|seree|seri|ciri|seeri|सिरी|सीरी)(?:$|[\s,.:!?।\-]+(.*)$)""", RegexOption.IGNORE_CASE),
                Regex("""^(?:हे|हेय|ओके)\s+(?:सिरी|सीरी)(?:$|[\s,.:!?।\-]+(.*)$)""", RegexOption.IGNORE_CASE)
            )
            WakeWordOption.SIRI -> listOf(
                Regex("""^(?:(?:hey|ok|okay|arre)\s+)?(?:siri|सिरी|सीरी)(?:$|[\s,.:!?।\-]+(.*)$)""", RegexOption.IGNORE_CASE)
            )
            WakeWordOption.SUNO_SIRI -> listOf(
                Regex("""^(?:(?:hey|arre)\s+)?(?:suno|sun)\s+(?:siri|सिरी|सीरी)(?:$|[\s,.:!?।\-]+(.*)$)""", RegexOption.IGNORE_CASE),
                Regex("""^(?:सुनो|सुन)\s+(?:सिरी|सीरी)(?:$|[\s,.:!?।\-]+(.*)$)""", RegexOption.IGNORE_CASE),
                Regex("""^(?:hey|ok)\s+(?:siri|सिरी|सीरी)(?:$|[\s,.:!?।\-]+(.*)$)""", RegexOption.IGNORE_CASE)
            )
        }

        for (pattern in wakePatterns) {
            val match = pattern.find(cleaned)
            if (match != null) {
                val command = (match.groups[1]?.value ?: "")
                    .trim()
                    .trimStart(',', '.', '!', '?', '।', ':')
                    .trim()
                return command
            }
        }
        return null
    }
}
