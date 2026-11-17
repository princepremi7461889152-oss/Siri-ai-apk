package com.example.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.BuildConfig
import com.example.model.AiConfig
import com.example.model.AssistantEmotion
import com.example.model.AssistantMode
import com.example.model.LanguageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

data class AiGenerationResult(
    val text: String,
    val latencyMs: Long = 0L,
    val isSuccess: Boolean = true,
    val emotion: AssistantEmotion = AssistantEmotion.NEUTRAL
)

class AiBrainRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(4, 2, TimeUnit.MINUTES))
        .build()

    fun normalizeCustomEndpoint(rawEndpoint: String): String? {
        val trimmed = rawEndpoint.trim()
        val effective = if (trimmed.isBlank()) "https://api.openai.com/v1" else trimmed
        if (!effective.startsWith("http://", ignoreCase = true) && !effective.startsWith("https://", ignoreCase = true)) {
            return null
        }
        return if (effective.contains("/chat/completions")) effective else "${effective.trimEnd('/')}/chat/completions"
    }

    fun mapHttpError(code: Int, rawErrMsg: String, provider: String): String {
        val cleanErr = rawErrMsg.trim()
        return when (code) {
            400 -> "$provider rejected the request parameters (HTTP 400${if (cleanErr.isNotBlank()) ": $cleanErr" else ""})."
            401, 403 -> "$provider authentication failed (HTTP $code). Please check your API key in Brain Studio."
            404 -> "$provider model or endpoint was not found (HTTP 404). Verify the model name and URL."
            429 -> "$provider rate limit or quota reached (HTTP 429). Please wait a moment and try again."
            in 500..599 -> "$provider server is temporarily unavailable (HTTP $code). Please try again shortly."
            else -> "$provider Error (HTTP $code${if (cleanErr.isNotBlank()) ": $cleanErr" else ""})"
        }
    }

    fun mapNetworkException(e: Exception): String {
        return when (e) {
            is UnknownHostException -> "No internet connection or unrecognized server host."
            is SocketTimeoutException -> "The request timed out while waiting for the server."
            is SSLException -> "Secure SSL/TLS connection failed. Check your network or endpoint URL."
            else -> "Network error: ${e.localizedMessage ?: "Unknown connection failure"}"
        }
    }

    fun getEffectiveApiKey(config: AiConfig): String {
        val buildKey = BuildConfig.GEMINI_API_KEY.trim()
        return when {
            config.customApiKey.isNotBlank() -> config.customApiKey.trim()
            buildKey.isNotBlank() && buildKey != "YOUR_GEMINI_API_KEY" && buildKey != "MY_GEMINI_API_KEY" -> buildKey
            else -> ""
        }
    }

    /**
     * Tests API key connection and returns roundtrip latency in ms
     */
    suspend fun testConnection(config: AiConfig): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey(config)
        val isCustomApi = config.provider == "Custom / OpenAI API" || config.customEndpoint.isNotBlank()

        if (apiKey.isBlank() && !isCustomApi) {
            return@withContext Pair(false, "No API key configured. Enter your personal Gemini API key or select Custom API.")
        }

        val startTime = System.currentTimeMillis()

        try {
            if (isCustomApi) {
                val url = normalizeCustomEndpoint(config.customEndpoint)
                    ?: return@withContext Pair(false, "Invalid Custom API URL. Must start with http:// or https://.")
                val modelName = config.model.ifBlank { "gpt-4o-mini" }
                val payload = JSONObject().apply {
                    put("model", modelName)
                    put("messages", JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("content", "Respond with 'Connection successful'")
                        })
                    })
                    put("max_tokens", 10)
                }

                val reqBuilder = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                if (apiKey.isNotBlank()) {
                    reqBuilder.addHeader("Authorization", "Bearer $apiKey")
                }

                client.newCall(reqBuilder.build()).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    val respString = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        Pair(true, "Connected to Custom API ($modelName) in ${latency}ms!")
                    } else {
                        val rawErr = try {
                            JSONObject(respString).optJSONObject("error")?.optString("message", "").orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        Pair(false, "${mapHttpError(response.code, rawErr, "Custom API")} ($latency ms)")
                    }
                }
            } else {
                val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:generateContent?key=$apiKey"
                val payload = JSONObject().apply {
                    val contents = JSONArray().apply {
                        put(JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().apply { put("text", "Respond with 'Connection successful'") })
                            })
                        })
                    }
                    put("contents", contents)
                    if (config.model.contains("2.5")) {
                        put("generationConfig", JSONObject().apply {
                            put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 0) })
                            put("maxOutputTokens", 16)
                        })
                    }
                }

                val body = payload.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url(url).post(body).build()
                client.newCall(request).execute().use { response ->
                    val latency = System.currentTimeMillis() - startTime
                    val respString = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        Pair(true, "Connected to Gemini (${config.model}) in ${latency}ms!")
                    } else {
                        val rawErr = try {
                            JSONObject(respString).optJSONObject("error")?.optString("message", "").orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        Pair(false, "${mapHttpError(response.code, rawErr, "Gemini")} ($latency ms)")
                    }
                }
            }
        } catch (e: Exception) {
            Pair(false, mapNetworkException(e))
        }
    }

    /**
     * Streams AI response via SSE and invokes [onSentenceReady] as soon as each spoken sentence
     * completes, drastically reducing time-to-first-voice-response.
     */
    suspend fun generateResponseStream(
        prompt: String,
        config: AiConfig,
        language: LanguageOption,
        recentHistory: List<Pair<String, Boolean>> = emptyList(),
        imageUri: Uri? = null,
        memoryContext: String = "",
        onEmotionDetected: (AssistantEmotion) -> Unit = {},
        onPartialText: (String) -> Unit = {},
        onSentenceReady: (sentence: String, isFirst: Boolean, emotion: AssistantEmotion) -> Unit = { _, _, _ -> },
        assistantMode: AssistantMode = AssistantMode.ONLINE
    ): AiGenerationResult = withContext(Dispatchers.IO) {
        val apiKey = getEffectiveApiKey(config)
        val startTime = System.currentTimeMillis()
        val isCustomApi = config.provider == "Custom / OpenAI API" || config.customEndpoint.isNotBlank()

        if (apiKey.isBlank() && !isCustomApi) {
            val offlineEmotion = EmotionEngine.detectEmotion(prompt)
            val offlineMsg = "No cloud API key is configured. Offline tools (apps, settings, alarms, reminders, math, notes & Office templates) work without internet, or add an API key in Brain Studio."
            onEmotionDetected(offlineEmotion)
            onPartialText(offlineMsg)
            onSentenceReady(offlineMsg, true, offlineEmotion)
            return@withContext AiGenerationResult(
                text = offlineMsg,
                latencyMs = 5L,
                isSuccess = false,
                emotion = offlineEmotion
            )
        }

        val fullSystemPrompt = buildSystemPrompt(config, language, memoryContext, assistantMode)
        val effectiveMaxTokens = if (assistantMode == AssistantMode.OFFICE) {
            maxOf(config.maxOutputTokens, 900)
        } else {
            config.maxOutputTokens
        }
        val detectedUserEmotion = EmotionEngine.detectEmotion(prompt)
        onEmotionDetected(detectedUserEmotion)

        try {
            if (isCustomApi) {
                streamOpenAiCompatible(
                    prompt = prompt,
                    config = config,
                    apiKey = apiKey,
                    fullSystemPrompt = fullSystemPrompt,
                    recentHistory = recentHistory,
                    maxTokens = effectiveMaxTokens,
                    initialEmotion = detectedUserEmotion,
                    startTime = startTime,
                    onEmotionDetected = onEmotionDetected,
                    onPartialText = onPartialText,
                    onSentenceReady = onSentenceReady
                )
            } else {
                streamGemini(
                    prompt = prompt,
                    config = config,
                    apiKey = apiKey,
                    fullSystemPrompt = fullSystemPrompt,
                    recentHistory = recentHistory,
                    imageUri = imageUri,
                    maxTokens = effectiveMaxTokens,
                    initialEmotion = detectedUserEmotion,
                    startTime = startTime,
                    onEmotionDetected = onEmotionDetected,
                    onPartialText = onPartialText,
                    onSentenceReady = onSentenceReady
                )
            }
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            Log.e("SiriAI", "Streaming exception", e)
            val fallbackMsg = "${mapNetworkException(e)} Offline device & productivity tools remain available."
            onEmotionDetected(AssistantEmotion.SERIOUSNESS)
            onSentenceReady(fallbackMsg, true, AssistantEmotion.SERIOUSNESS)
            AiGenerationResult(fallbackMsg, latency, false, AssistantEmotion.SERIOUSNESS)
        }
    }

    /**
     * Non-streaming fallback / convenience method.
     */
    suspend fun generateResponse(
        prompt: String,
        config: AiConfig,
        language: LanguageOption,
        recentHistory: List<Pair<String, Boolean>> = emptyList(),
        imageUri: Uri? = null,
        memoryContext: String = "",
        assistantMode: AssistantMode = AssistantMode.ONLINE
    ): AiGenerationResult {
        return generateResponseStream(
            prompt = prompt,
            config = config,
            language = language,
            recentHistory = recentHistory,
            imageUri = imageUri,
            memoryContext = memoryContext,
            assistantMode = assistantMode
        )
    }

    /**
     * Fetches a concise instant-answer web summary suitable for reading aloud via TTS.
     */
    suspend fun fetchWebSummaryForSpeech(query: String): String? = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext null

        // 1. Try DuckDuckGo Instant Answer API
        try {
            val ddgUrl = "https://api.duckduckgo.com/?q=${Uri.encode(clean)}&format=json&no_html=1&skip_disambig=1"
            val req = Request.Builder().url(ddgUrl).get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val abstractText = json.optString("AbstractText", "").trim()
                        if (abstractText.isNotBlank()) {
                            return@withContext cleanVoiceText(abstractText).take(320)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        // 2. Try Wikipedia REST summary API
        try {
            val wikiSlug = Uri.encode(clean.replace(" ", "_"))
            val wikiUrl = "https://en.wikipedia.org/api/rest_v1/page/summary/$wikiSlug"
            val req = Request.Builder()
                .url(wikiUrl)
                .addHeader("User-Agent", "SiriAiAssistantAndroid/1.1")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val extract = json.optString("extract", "").trim()
                        if (extract.isNotBlank()) {
                            return@withContext cleanVoiceText(extract).take(320)
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }
        null
    }

    private fun buildSystemPrompt(
        config: AiConfig,
        language: LanguageOption,
        memoryContext: String = "",
        assistantMode: AssistantMode = AssistantMode.ONLINE
    ): String {
        val isOffice = assistantMode == AssistantMode.OFFICE
        val langInstruction = when (language) {
            LanguageOption.HINDI -> if (isOffice) {
                "Reply in clear, professional Hindi (Devanagari). Use short clean lines instead of markdown symbols."
            } else {
                "Reply fluently in natural Hindi (using Devanagari script). Keep the response conversational, warm, and concise in 1-2 spoken sentences (max 3). Never use markdown, asterisks, or bullet points."
            }
            LanguageOption.HINGLISH -> if (isOffice) {
                "Reply in clear, polite Hinglish (Latin alphabet) suited for work and study. Avoid markdown symbols."
            } else {
                "Reply in friendly, everyday Hinglish (natural Hindi-English mix written in Latin alphabet). Keep it warm, natural, and concise in 1-2 spoken sentences (max 3). Never use markdown."
            }
            LanguageOption.ENGLISH -> if (isOffice) {
                "Reply in crisp, professional English structured with short clear lines for work, study, or drafting. Never use markdown asterisks or hash headers."
            } else {
                "Reply in fluent, natural English. Keep it conversational, calm, and concise in 1-2 spoken sentences suitable for voice speech. Avoid robotic chatbot clichés and never use markdown, asterisks, or bullet points."
            }
            LanguageOption.AUTO -> if (isOffice) {
                "Match the user's language (English, Hindi, or Hinglish) with crisp, professional Office & Study clarity. Never use markdown symbols."
            } else {
                "Detect whether the user is speaking English, Hindi, or Hinglish, and reply fluently in that exact matching language style. Keep it conversational and voice-ready in 1-2 sentences without any markdown."
            }
        }

        val currentTimeDate = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy h:mm a", java.util.Locale.getDefault()).format(java.util.Date())
        val lengthInstruction = if (isOffice) {
            "Provide a complete, structured, high-utility Office/Study response (use numbered lines or clean line breaks, without markdown formatting)."
        } else {
            config.responseLength.instruction
        }
        val modeRole = if (isOffice) {
            "Active Mode: OFFICE & STUDY COPILOT. Help draft emails/applications/reports, summarize documents, simplify study topics, organize work plans, and translate accurately."
        } else {
            "Active Mode: VOICE ASSISTANT."
        }
        val memorySection = if (memoryContext.isNotBlank()) {
            "\nUser-Approved Personal Memories & Context:\n$memoryContext"
        } else {
            ""
        }
        return "${config.systemPrompt}\n" +
            "$modeRole\n" +
            "Current date and time: $currentTimeDate\n" +
            "Length instruction: $lengthInstruction\n" +
            "Language instruction: $langInstruction\n" +
            "Emotion Tagging: Start every response with exactly one tag from [EMOTION:HAPPINESS], [EMOTION:SADNESS], [EMOTION:EXCITEMENT], [EMOTION:SURPRISE], [EMOTION:CURIOSITY], [EMOTION:SERIOUSNESS], or [EMOTION:NEUTRAL], followed by a space and your reply.\n" +
            "Anti-Repetition: Vary your sentence openings and wording naturally every turn.\n" +
            "Assistant name: Siri.$memorySection"
    }

    private fun streamGemini(
        prompt: String,
        config: AiConfig,
        apiKey: String,
        fullSystemPrompt: String,
        recentHistory: List<Pair<String, Boolean>>,
        imageUri: Uri?,
        maxTokens: Int,
        initialEmotion: AssistantEmotion,
        startTime: Long,
        onEmotionDetected: (AssistantEmotion) -> Unit,
        onPartialText: (String) -> Unit,
        onSentenceReady: (String, Boolean, AssistantEmotion) -> Unit
    ): AiGenerationResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${config.model}:streamGenerateContent?alt=sse&key=$apiKey"
        val root = JSONObject()

        root.put("systemInstruction", JSONObject().apply {
            put("parts", JSONArray().apply {
                put(JSONObject().apply { put("text", fullSystemPrompt) })
            })
        })

        val contentsArray = JSONArray()
        for ((text, isUser) in recentHistory.takeLast(8)) {
            contentsArray.put(JSONObject().apply {
                put("role", if (isUser) "user" else "model")
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", text) })
                })
            })
        }

        val userTurnParts = JSONArray()
        if (imageUri != null) {
            val base64Image = readImageAsBase64(imageUri)
            if (base64Image != null) {
                userTurnParts.put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", "image/jpeg")
                        put("data", base64Image)
                    })
                })
            }
        }
        userTurnParts.put(JSONObject().apply { put("text", prompt) })
        contentsArray.put(JSONObject().apply {
            put("role", "user")
            put("parts", userTurnParts)
        })
        root.put("contents", contentsArray)

        val genConfig = JSONObject().apply {
            put("temperature", config.temperature.toDouble())
            put("maxOutputTokens", maxTokens)
            // Disable hidden thinking tokens on Gemini 2.5 Flash for ultra-low voice latency
            if (config.model.contains("2.5") && !config.model.contains("pro")) {
                put("thinkingConfig", JSONObject().apply { put("thinkingBudget", 0) })
            }
        }
        root.put("generationConfig", genConfig)

        val request = Request.Builder()
            .url(url)
            .post(root.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val latency = System.currentTimeMillis() - startTime
                val errBody = response.body?.string().orEmpty()
                val rawErr = try {
                    JSONObject(errBody).optJSONObject("error")?.optString("message", "").orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val msg = "${mapHttpError(response.code, rawErr, "Gemini")} Local device actions still work."
                onEmotionDetected(AssistantEmotion.SERIOUSNESS)
                onSentenceReady(msg, true, AssistantEmotion.SERIOUSNESS)
                return AiGenerationResult(msg, latency, false, AssistantEmotion.SERIOUSNESS)
            }

            val bodyStream = response.body?.byteStream()
                ?: return AiGenerationResult("Empty stream from AI brain.", System.currentTimeMillis() - startTime, false, AssistantEmotion.SERIOUSNESS)

            return consumeSseStream(
                reader = BufferedReader(InputStreamReader(bodyStream)),
                isOpenAiFormat = false,
                prompt = prompt,
                initialEmotion = initialEmotion,
                startTime = startTime,
                onEmotionDetected = onEmotionDetected,
                onPartialText = onPartialText,
                onSentenceReady = onSentenceReady
            )
        }
    }

    private fun streamOpenAiCompatible(
        prompt: String,
        config: AiConfig,
        apiKey: String,
        fullSystemPrompt: String,
        recentHistory: List<Pair<String, Boolean>>,
        maxTokens: Int,
        initialEmotion: AssistantEmotion,
        startTime: Long,
        onEmotionDetected: (AssistantEmotion) -> Unit,
        onPartialText: (String) -> Unit,
        onSentenceReady: (String, Boolean, AssistantEmotion) -> Unit
    ): AiGenerationResult {
        val url = normalizeCustomEndpoint(config.customEndpoint)
            ?: return AiGenerationResult("Invalid Custom API URL. Must start with http:// or https://.", System.currentTimeMillis() - startTime, false, AssistantEmotion.SERIOUSNESS)
        val modelName = config.model.ifBlank { "gpt-4o-mini" }

        val messagesArray = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", fullSystemPrompt)
            })
            for ((text, isUser) in recentHistory.takeLast(8)) {
                put(JSONObject().apply {
                    put("role", if (isUser) "user" else "assistant")
                    put("content", text)
                })
            }
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        }

        val payload = JSONObject().apply {
            put("model", modelName)
            put("messages", messagesArray)
            put("temperature", config.temperature.toDouble())
            put("max_tokens", maxTokens)
            put("stream", true)
        }

        val reqBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotBlank()) {
            reqBuilder.addHeader("Authorization", "Bearer $apiKey")
        }

        client.newCall(reqBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                val latency = System.currentTimeMillis() - startTime
                val errBody = response.body?.string().orEmpty()
                val rawErr = try {
                    JSONObject(errBody).optJSONObject("error")?.optString("message", "").orEmpty()
                } catch (_: Exception) {
                    ""
                }
                val msg = "${mapHttpError(response.code, rawErr, "Custom API")} Local device controls remain active."
                onEmotionDetected(AssistantEmotion.SERIOUSNESS)
                onSentenceReady(msg, true, AssistantEmotion.SERIOUSNESS)
                return AiGenerationResult(msg, latency, false, AssistantEmotion.SERIOUSNESS)
            }

            val bodyStream = response.body?.byteStream()
                ?: return AiGenerationResult("Empty stream from Custom API.", System.currentTimeMillis() - startTime, false, AssistantEmotion.SERIOUSNESS)

            return consumeSseStream(
                reader = BufferedReader(InputStreamReader(bodyStream)),
                isOpenAiFormat = true,
                prompt = prompt,
                initialEmotion = initialEmotion,
                startTime = startTime,
                onEmotionDetected = onEmotionDetected,
                onPartialText = onPartialText,
                onSentenceReady = onSentenceReady
            )
        }
    }

    private fun consumeSseStream(
        reader: BufferedReader,
        isOpenAiFormat: Boolean,
        prompt: String,
        initialEmotion: AssistantEmotion,
        startTime: Long,
        onEmotionDetected: (AssistantEmotion) -> Unit,
        onPartialText: (String) -> Unit,
        onSentenceReady: (String, Boolean, AssistantEmotion) -> Unit
    ): AiGenerationResult {
        val rawAccumulator = StringBuilder()
        val sentenceBuffer = StringBuilder()
        var currentEmotion = initialEmotion
        var emotionResolvedFromTag = false
        var tagBufferChecked = false
        var emittedFirstSentence = false

        reader.useLines { lines ->
            for (line in lines) {
                if (!line.startsWith("data:")) continue
                val dataStr = line.removePrefix("data:").trim()
                if (dataStr.isEmpty() || dataStr == "[DONE]") continue

                val deltaText = if (isOpenAiFormat) extractOpenAiDelta(dataStr) else extractGeminiDelta(dataStr)
                if (deltaText.isEmpty()) continue

                rawAccumulator.append(deltaText)
                val fullRaw = rawAccumulator.toString()

                // Wait briefly until any leading `[EMOTION:...]` tag closes before emitting spoken text
                if (!tagBufferChecked) {
                    if (fullRaw.trimStart().startsWith("[")) {
                        if (fullRaw.contains("]")) {
                            val parsedTag = EmotionEngine.extractLeadingEmotionTag(fullRaw)
                            if (parsedTag != null) {
                                currentEmotion = parsedTag
                                emotionResolvedFromTag = true
                                onEmotionDetected(currentEmotion)
                            }
                            tagBufferChecked = true
                            val afterTag = EmotionEngine.stripEmotionTag(fullRaw)
                            if (afterTag.isNotEmpty()) {
                                sentenceBuffer.append(afterTag)
                                onPartialText(cleanVoiceText(afterTag))
                            }
                        }
                        continue
                    } else if (fullRaw.length >= 2) {
                        tagBufferChecked = true
                        sentenceBuffer.append(fullRaw)
                    } else {
                        continue
                    }
                } else {
                    sentenceBuffer.append(deltaText)
                    val cleanSoFar = cleanVoiceText(EmotionEngine.stripEmotionTag(fullRaw))
                    if (cleanSoFar.isNotEmpty()) {
                        onPartialText(cleanSoFar)
                    }
                }

                // Flush completed sentences immediately for fast TTS playback
                val bufStr = sentenceBuffer.toString()
                val splitIdx = findSentenceEndIndex(bufStr)
                if (splitIdx >= 0) {
                    val completeSentence = cleanVoiceText(bufStr.substring(0, splitIdx + 1))
                    val remainder = bufStr.substring(splitIdx + 1).trimStart()
                    sentenceBuffer.clear()
                    sentenceBuffer.append(remainder)

                    if (completeSentence.isNotBlank()) {
                        if (!emotionResolvedFromTag) {
                            currentEmotion = EmotionEngine.detectEmotion(prompt, completeSentence)
                            onEmotionDetected(currentEmotion)
                        }
                        onSentenceReady(completeSentence, !emittedFirstSentence, currentEmotion)
                        emittedFirstSentence = true
                    }
                }
            }
        }

        val (finalEmotion, strippedFull) = EmotionEngine.parseTaggedResponse(rawAccumulator.toString(), prompt)
        val cleanFinal = cleanVoiceText(strippedFull)
        val remaining = cleanVoiceText(sentenceBuffer.toString())

        if (remaining.isNotBlank()) {
            onSentenceReady(remaining, !emittedFirstSentence, finalEmotion)
        } else if (!emittedFirstSentence && cleanFinal.isNotBlank()) {
            onSentenceReady(cleanFinal, true, finalEmotion)
        }

        onEmotionDetected(finalEmotion)
        val latency = System.currentTimeMillis() - startTime
        return if (cleanFinal.isNotBlank()) {
            onPartialText(cleanFinal)
            AiGenerationResult(cleanFinal, latency, true, finalEmotion)
        } else {
            AiGenerationResult("I heard you, but could not generate a response. Please try again.", latency, false, AssistantEmotion.SERIOUSNESS)
        }
    }

    private fun extractGeminiDelta(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val candidates = obj.optJSONArray("candidates") ?: return ""
            if (candidates.length() == 0) return ""
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return ""
            val parts = content.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                sb.append(parts.getJSONObject(i).optString("text", ""))
            }
            sb.toString()
        } catch (_: Exception) {
            ""
        }
    }

    private fun extractOpenAiDelta(jsonStr: String): String {
        return try {
            val obj = JSONObject(jsonStr)
            val choices = obj.optJSONArray("choices") ?: return ""
            if (choices.length() == 0) return ""
            val choice = choices.getJSONObject(0)
            val delta = choice.optJSONObject("delta")
            if (delta != null) {
                delta.optString("content", "")
            } else {
                choice.optJSONObject("message")?.optString("content", "") ?: ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun findSentenceEndIndex(text: String): Int {
        if (text.length < 14) return -1
        for (i in 10 until text.length) {
            val c = text[i]
            if (c == '.' || c == '!' || c == '?' || c == '।' || c == '\n') {
                // Avoid splitting inside decimal numbers like 3.14
                if (c == '.' && i > 0 && text[i - 1].isDigit() && i + 1 < text.length && text[i + 1].isDigit()) {
                    continue
                }
                if (i == text.lastIndex || text[i + 1].isWhitespace()) {
                    return i
                }
            }
        }
        return -1
    }

    private fun readImageAsBase64(uri: Uri): String? {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return null
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            val maxDimension = 1024
            val width = bitmap.width
            val height = bitmap.height
            val scaledBitmap = if (width > maxDimension || height > maxDimension) {
                val ratio = width.toFloat() / height.toFloat()
                val targetW: Int
                val targetH: Int
                if (width > height) {
                    targetW = maxDimension
                    targetH = (maxDimension / ratio).toInt()
                } else {
                    targetH = maxDimension
                    targetW = (maxDimension * ratio).toInt()
                }
                Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            } else {
                bitmap
            }

            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
            val bytes = outputStream.toByteArray()
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e("SiriAI", "Failed to encode image to base64", e)
            null
        }
    }

    /**
     * Strips markdown symbols, asterisks, bullet points, headers, and excessive whitespace
     * to ensure the assistant's response is natural, clean, and voice-ready for TTS.
     */
    fun cleanVoiceText(raw: String): String {
        return raw
            .replace(Regex("""^\s*\[EMOTION\s*:\s*[A-Za-z_]+\s*\]\s*""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\*\*([^*]+)\*\*"""), "$1")
            .replace(Regex("""\*([^*]+)\*"""), "$1")
            .replace(Regex("""__([^_]+)__"""), "$1")
            .replace(Regex("""_([^_]+)_"""), "$1")
            .replace(Regex("""#{1,6}\s*"""), "")
            .replace(Regex("""```[\s\S]*?```"""), "")
            .replace(Regex("""`([^`]+)`"""), "$1")
            .replace(Regex("""\[([^\]]+)\]\([^)]+\)"""), "$1")
            .replace(Regex("""^\s*[-*•]\s+""", RegexOption.MULTILINE), "")
            .replace(Regex("""\s*[-—–]{2,}\s*"""), ", ")
            .replace(Regex("""\r\n"""), "\n")
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
