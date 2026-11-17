package com.example.ai

import com.example.model.AssistantEmotion
import java.util.concurrent.ConcurrentHashMap

/**
 * Emotional Intelligence engine for detecting conversational sentiment across
 * English, Hindi, and Hinglish, parsing optional LLM emotion tags, and avoiding
 * repetitive robotic responses.
 */
object EmotionEngine {

    private val lastPickedIndices = ConcurrentHashMap<String, Int>()
    private val emotionTagRegex = Regex("""^\s*\[EMOTION\s*:\s*([A-Za-z_]+)\s*\]\s*""", RegexOption.IGNORE_CASE)

    /**
     * Parses a leading `[EMOTION:X]` tag emitted by the AI model and falls back
     * to lexical sentiment analysis when the tag is absent or neutral.
     */
    fun parseTaggedResponse(rawText: String, userPrompt: String): Pair<AssistantEmotion, String> {
        val match = emotionTagRegex.find(rawText)
        val parsedEmotion = if (match != null) {
            mapEmotionName(match.groupValues[1])
        } else {
            null
        }
        val cleaned = if (match != null) {
            rawText.removeRange(match.range).trimStart()
        } else {
            rawText.trim()
        }
        val finalEmotion = if (parsedEmotion != null && parsedEmotion != AssistantEmotion.NEUTRAL) {
            parsedEmotion
        } else {
            detectEmotion(userPrompt, cleaned)
        }
        return finalEmotion to cleaned
    }

    /**
     * Strips any stray `[EMOTION:...]` tag from a streaming text chunk without waiting
     * for the rest of the message.
     */
    fun stripEmotionTag(text: String): String {
        return text.replace(emotionTagRegex, "").trimStart()
    }

    fun extractLeadingEmotionTag(text: String): AssistantEmotion? {
        val match = emotionTagRegex.find(text) ?: return null
        return mapEmotionName(match.groupValues[1])
    }

    private fun mapEmotionName(raw: String): AssistantEmotion? {
        return when (raw.trim().uppercase()) {
            "HAPPINESS", "HAPPY", "JOY", "CHEERFUL", "WARM" -> AssistantEmotion.HAPPINESS
            "SADNESS", "SAD", "EMPATHY", "EMPATHETIC", "SORROW" -> AssistantEmotion.SADNESS
            "EXCITEMENT", "EXCITED", "THRILLED", "ENTHUSIASTIC" -> AssistantEmotion.EXCITEMENT
            "SURPRISE", "SURPRISED", "AMAZED", "SHOCKED" -> AssistantEmotion.SURPRISE
            "CURIOSITY", "CURIOUS", "INQUISITIVE", "THOUGHTFUL" -> AssistantEmotion.CURIOSITY
            "SERIOUSNESS", "SERIOUS", "FOCUSED", "URGENT", "CALM_SERIOUS" -> AssistantEmotion.SERIOUSNESS
            "NEUTRAL", "CALM" -> AssistantEmotion.NEUTRAL
            else -> null
        }
    }

    /**
     * Detects the most appropriate assistant emotion based on user input and reply context
     * across English, Hindi (Devanagari), and Hinglish.
     */
    fun detectEmotion(userPrompt: String, assistantReply: String = ""): AssistantEmotion {
        val u = userPrompt.lowercase().trim()
        val a = assistantReply.lowercase().trim()
        val combined = "$u $a"

        // Priority 1: Empathy / Sadness signals in user's situation
        val sadnessScore = countMatches(
            u,
            listOf(
                "sad", "upset", "depressed", "lonely", "heartbroken", "cry", "crying", "hurt",
                "failed", "failure", "lost my", "bad day", "terrible day", "awful", "stressed",
                "anxious", "worried", "exhausted", "tired", "burnout", "sorry", "unfortunately",
                "dukhi", "udaas", "udas", "bura laga", "pareshan", "thak gaya", "thak gayi",
                "haar gaya", "rona", "tension", "dukh", "takleef",
                "दुखी", "उदास", "परेशान", "थक गया", "बुरा लगा", "टेंशन", "रो"
            )
        ) * 2 + countMatches(
            a,
            listOf("sorry to hear", "take care", "here for you", "must be tough", "maaf", "samajh sakti")
        )

        // Priority 2: Excitement (wins, celebrations, enthusiasm)
        val excitementScore = countMatches(
            combined,
            listOf(
                "awesome", "amazing", "fantastic", "woohoo", "yay", "congratulations", "congrats",
                "i won", "we won", "got the job", "selected", "promoted", "party", "celebrate",
                "excited", "thrilled", "brilliant", "incredible", "let's go", "lets go",
                "wah", "waah", "zabardast", "kamaal", "maza aa gaya", "jeet gaya", "jeet gayi",
                "badhai", "shabaash", "balle", "jhakaas",
                "वाह", "कमाल", "मज़ा आ गया", "बधाई", "जबरदस्त", "जीत"
            )
        ) + (if (u.count { it == '!' } >= 2) 2 else 0)

        // Priority 3: Surprise
        val surpriseScore = countMatches(
            combined,
            listOf(
                "wow", "whoa", "woah", "no way", "really?", "are you serious", "unbelievable",
                "shocking", "mind blown", "mind-blown", "unexpected", "what?!", "omg", "oh my god",
                "sach mein", "kya baat hai", "arre wah", "hairan", "kamaal hai",
                "अरे", "सच में", "क्या बात", "हैरान", "यकीन नहीं"
            )
        )

        // Priority 4: Seriousness (emergencies, security, warnings, errors, critical work)
        val seriousnessScore = countMatches(
            combined,
            listOf(
                "urgent", "emergency", "critical", "warning", "security", "privacy", "breach",
                "prohibited", "restrict", "cannot", "couldn't", "failed to", "error", "danger",
                "important", "deadline", "medical", "doctor", "hospital", "legal",
                "zaroori", "gambhir", "khatra", "saavdhan", "suraksha",
                "ज़रूरी", "गंभीर", "खतरा", "सावधान", "आपात"
            )
        )

        // Priority 5: Happiness
        val happinessScore = countMatches(
            combined,
            listOf(
                "happy", "glad", "joy", "smile", "laugh", "funny", "joke", "love", "wonderful",
                "great", "good morning", "thank", "thanks", "welcome", "pleasure", "cheerful",
                "haha", "hehe", "lol", "nice", "delighted",
                "khush", "badhiya", "mast", "shukriya", "dhanyawad", "achha", "accha", "swagat",
                "chutkula", "hasna", "muskurahat",
                "खुश", "बढ़िया", "मस्त", "धन्यवाद", "शुक्रिया", "अच्छा", "नमस्ते"
            )
        )

        // Priority 6: Curiosity
        val curiosityScore = countMatches(
            u,
            listOf(
                "why", "how", "what if", "wonder", "curious", "explain", "meaning of",
                "tell me about", "could you tell", "did you know", "research", "science",
                "space", "universe", "history", "calculate", "convert",
                "kyun", "kyo", "kaise", "kya hota", "batao", "samjhao", "jaanna",
                "क्यों", "कैसे", "क्या है", "बताओ", "समझाओ"
            )
        ) + (if (u.endsWith("?")) 1 else 0)

        val scores = listOf(
            AssistantEmotion.SADNESS to sadnessScore,
            AssistantEmotion.EXCITEMENT to excitementScore,
            AssistantEmotion.SURPRISE to surpriseScore,
            AssistantEmotion.SERIOUSNESS to seriousnessScore,
            AssistantEmotion.HAPPINESS to happinessScore,
            AssistantEmotion.CURIOSITY to curiosityScore
        )

        val best = scores.maxByOrNull { it.second }
        return if (best != null && best.second > 0) best.first else AssistantEmotion.NEUTRAL
    }

    /**
     * Picks a response from [options] without repeating the exact same phrase consecutively
     * for a given [key], eliminating robotic repetition.
     */
    fun pickNonRepeating(key: String, options: List<String>): String {
        if (options.isEmpty()) return ""
        if (options.size == 1) return options[0]
        val lastIdx = lastPickedIndices[key]
        val candidateIndices = options.indices.filter { it != lastIdx }
        val chosenIdx = candidateIndices.random()
        lastPickedIndices[key] = chosenIdx
        return options[chosenIdx]
    }

    private fun countMatches(text: String, keywords: List<String>): Int {
        if (text.isBlank()) return 0
        var hits = 0
        for (kw in keywords) {
            if (kw.length <= 4 && kw.all { it.isLetter() }) {
                if (Regex("""\b${Regex.escape(kw)}\b""").containsMatchIn(text)) {
                    hits++
                }
            } else if (text.contains(kw)) {
                hits++
            }
        }
        return hits
    }
}
