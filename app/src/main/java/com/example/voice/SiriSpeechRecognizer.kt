package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.example.model.LanguageOption

class SiriSpeechRecognizer(
    private val context: Context,
    private val onResult: (String) -> Unit,
    private val onPartialResult: (String) -> Unit,
    private val onRmsDbChanged: (Float) -> Unit,
    private val onErrorOccurred: (String) -> Unit,
    private val onListeningStateChanged: (Boolean) -> Unit,
    private val onSpeechDetected: (() -> Unit)? = null,
    private val onDetailedResult: ((matches: List<String>, confidences: FloatArray?) -> Unit)? = null,
    private val onRawRmsSample: ((normalizedRms: Float, isSpeechActive: Boolean) -> Unit)? = null,
    private val onSilentRestartNeeded: (() -> Unit)? = null,
    private val onNoSpeechDetected: ((String) -> Unit)? = null
) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening: Boolean = false
    private var isSpeechActive: Boolean = false
    private var isStandbyMode: Boolean = false
    private var currentLanguage: LanguageOption = LanguageOption.AUTO
    private var preferOffline: Boolean = false

    // Duplicate command prevention & debouncing
    private var lastRecognizedText: String = ""
    private var lastRecognizedTimestamp: Long = 0L

    init {
        createRecognizer()
    }

    private fun createRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            Log.w("SiriSpeech", "Speech recognition not available on this device")
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    isListening = true
                    isSpeechActive = false
                    if (!isStandbyMode) {
                        onListeningStateChanged(true)
                    }
                }

                override fun onBeginningOfSpeech() {
                    isListening = true
                    isSpeechActive = true
                    if (!isStandbyMode) {
                        onListeningStateChanged(true)
                    }
                    onSpeechDetected?.invoke()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Normalize -2dB to 10dB into 0.05 - 1.0 range
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.05f, 1.0f)
                    onRawRmsSample?.invoke(normalized, isSpeechActive)
                    if (!isStandbyMode) {
                        onRmsDbChanged(normalized)
                    }
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    isSpeechActive = false
                    if (!isStandbyMode) {
                        onListeningStateChanged(false)
                    }
                }

                override fun onError(error: Int) {
                    isListening = false
                    isSpeechActive = false
                    if (!isStandbyMode) {
                        onListeningStateChanged(false)
                    }
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
                        SpeechRecognizer.ERROR_CLIENT -> "Client connection error"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required"
                        SpeechRecognizer.ERROR_NETWORK -> "Network connection error"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out"
                        SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized. Try speaking again."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer is busy"
                        SpeechRecognizer.ERROR_SERVER -> "Recognition server error"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Listening timed out"
                        else -> "Speech recognition error ($error)"
                    }
                    Log.d("SiriSpeech", "Recognition error: $errorMsg (standby=$isStandbyMode)")
                    if (isStandbyMode) {
                        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                            isStandbyMode = false
                            onErrorOccurred(errorMsg)
                        } else {
                            onSilentRestartNeeded?.invoke()
                        }
                    } else if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        onNoSpeechDetected?.invoke(errorMsg)
                    } else {
                        onErrorOccurred(errorMsg)
                    }
                }

                override fun onResults(results: Bundle?) {
                    isListening = false
                    isSpeechActive = false
                    if (!isStandbyMode) {
                        onListeningStateChanged(false)
                    }
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                    val spokenText = matches.firstOrNull()

                    if (!spokenText.isNullOrEmpty()) {
                        val now = System.currentTimeMillis()
                        if (!isStandbyMode && spokenText.equals(lastRecognizedText, ignoreCase = true) && (now - lastRecognizedTimestamp) < 1200) {
                            Log.d("SiriSpeech", "Debounced duplicate speech command: $spokenText")
                            return
                        }
                        lastRecognizedText = spokenText
                        lastRecognizedTimestamp = now
                        if (onDetailedResult != null) {
                            onDetailedResult.invoke(matches, confidences)
                        } else {
                            onResult(spokenText)
                        }
                    } else if (isStandbyMode) {
                        onSilentRestartNeeded?.invoke()
                    } else {
                        onNoSpeechDetected?.invoke("No speech recognized. Try speaking again.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partial = matches?.firstOrNull()?.trim()
                    if (!partial.isNullOrEmpty() && !isStandbyMode) {
                        onPartialResult(partial)
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
    }

    fun setLanguage(language: LanguageOption) {
        currentLanguage = language
    }

    fun setPreferOffline(offline: Boolean) {
        preferOffline = offline
    }

    fun startListening(standbyMode: Boolean = false) {
        isStandbyMode = standbyMode
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            if (!standbyMode) {
                onErrorOccurred("Speech recognizer is not available on this device.")
            }
            return
        }

        if (speechRecognizer == null) {
            createRecognizer()
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, !standbyMode)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            // Fast endpointing to reduce post-speech lag
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (standbyMode) 550L else 750L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, if (standbyMode) 400L else 550L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 350L)
            putExtra("android.speech.extra.DICTATION_MODE", false)

            val langLocale = when (currentLanguage) {
                LanguageOption.HINDI -> "hi-IN"
                LanguageOption.HINGLISH -> "en-IN"
                LanguageOption.ENGLISH -> "en-US"
                LanguageOption.AUTO -> "en-US"
            }
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, langLocale)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, langLocale)
            if (currentLanguage == LanguageOption.AUTO || currentLanguage == LanguageOption.HINGLISH) {
                putExtra("android.speech.extra.EXTRA_ADDITIONAL_LANGUAGES", arrayOf("hi-IN", "en-US"))
            }
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }

        try {
            speechRecognizer?.startListening(intent)
            isListening = true
            isSpeechActive = false
            if (!standbyMode) {
                onListeningStateChanged(true)
            }
        } catch (e: Exception) {
            Log.e("SiriSpeech", "Failed to start listening", e)
            if (!standbyMode) {
                onErrorOccurred("Could not start microphone: ${e.message}")
            }
        }
    }

    fun stopListening() {
        isStandbyMode = false
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("SiriSpeech", "Error stopping listening", e)
        }
        isListening = false
        isSpeechActive = false
        onListeningStateChanged(false)
    }

    fun cancel() {
        isStandbyMode = false
        try {
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            Log.e("SiriSpeech", "Error cancelling listening", e)
        }
        isListening = false
        isSpeechActive = false
        onListeningStateChanged(false)
    }

    fun destroy() {
        cancel()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("SiriSpeech", "Error destroying speech recognizer", e)
        }
        speechRecognizer = null
    }

    fun isCurrentlyListening(): Boolean = isListening
}
