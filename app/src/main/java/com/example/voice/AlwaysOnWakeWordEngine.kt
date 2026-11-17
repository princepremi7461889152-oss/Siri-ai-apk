package com.example.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.model.WakeWordOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

enum class WakeEngineBackend {
    HARDWARE_SOUND_TRIGGER_DSP,
    CONTINUOUS_OFFLINE_AUDIO_RECORD
}

data class WakeHardwareStatus(
    val hasMicrophoneFeature: Boolean,
    val supportsUnprocessedAudio: Boolean,
    val hardwareSoundTriggerServicePresent: Boolean,
    val captureHotwordPermissionGranted: Boolean,
    val activeBackend: WakeEngineBackend,
    val detailMessage: String
)

data class OfflineAcousticEvaluation(
    val detected: Boolean,
    val matchedPhrase: String,
    val confidence: Float,
    val durationMs: Int,
    val rejectionReason: String? = null
)

/**
 * Probes SoundTrigger / DSP hardware availability on the current device.
 * Note: Android restricts `AlwaysOnHotwordDetector` / `CAPTURE_AUDIO_HOTWORD` DSP access
 * exclusively to pre-installed privileged system `VoiceInteractionService` apps.
 * Non-system apps transparently fall back to a single continuous low-CPU AudioRecord offline stream.
 */
object SoundTriggerCapabilityProbe {

    private const val PERMISSION_CAPTURE_AUDIO_HOTWORD = "android.permission.CAPTURE_AUDIO_HOTWORD"

    fun probe(context: Context): WakeHardwareStatus {
        val pm = context.packageManager
        val hasMic = pm.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val supportsUnprocessed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && am != null) {
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        } else {
            false
        }
        val soundTriggerServicePresent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                @Suppress("WrongConstant")
                context.getSystemService("soundtrigger") != null
            } catch (_: Exception) {
                false
            }
        } else {
            false
        }
        val captureHotwordGranted = ContextCompat.checkSelfPermission(
            context,
            PERMISSION_CAPTURE_AUDIO_HOTWORD
        ) == PackageManager.PERMISSION_GRANTED

        val backend = if (soundTriggerServicePresent && captureHotwordGranted) {
            WakeEngineBackend.HARDWARE_SOUND_TRIGGER_DSP
        } else {
            WakeEngineBackend.CONTINUOUS_OFFLINE_AUDIO_RECORD
        }

        val detail = when {
            !hasMic -> "Device reports no microphone hardware."
            backend == WakeEngineBackend.HARDWARE_SOUND_TRIGGER_DSP ->
                "DSP SoundTrigger hardware hotword active."
            soundTriggerServicePresent ->
                "DSP SoundTrigger requires privileged system permission; using continuous low-power offline AudioRecord engine."
            else ->
                "Hardware SoundTrigger unavailable; using continuous low-power offline AudioRecord engine."
        }

        return WakeHardwareStatus(
            hasMicrophoneFeature = hasMic,
            supportsUnprocessedAudio = supportsUnprocessed,
            hardwareSoundTriggerServicePresent = soundTriggerServicePresent,
            captureHotwordPermissionGranted = captureHotwordGranted,
            activeBackend = backend,
            detailMessage = detail
        )
    }
}

/**
 * Pure-Kotlin 100% offline acoustic phoneme & spectral-contour wake word analyzer for
 * "Hey Siri", "Siri", and "Suno Siri". Zero audio leaves the device.
 */
object OfflineHotwordAcousticClassifier {

    const val SAMPLE_RATE = 16_000
    const val FRAME_SAMPLES = 320 // 20 ms at 16 kHz

    private const val F_LOW = 650.0   // Vowel fundamental / F1 band
    private const val F_MID = 1650.0  // Vowel F2 formant band
    private const val F_SIB = 4600.0  // Sibilant /s/ fricative band

    private data class FrameFeatures(
        val rms: Float,
        val zcr: Float,
        val lowEnergy: Float,
        val midEnergy: Float,
        val sibEnergy: Float
    ) {
        val totalBand: Float get() = lowEnergy + midEnergy + sibEnergy + 1e-6f
        val sibRatio: Float get() = sibEnergy / totalBand
        val voicedRatio: Float get() = (lowEnergy + midEnergy) / totalBand
        val isSibilant: Boolean get() = rms > 0.015f && sibRatio >= 0.34f && (zcr >= 0.20f || sibEnergy > lowEnergy * 1.15f)
        val isVoiced: Boolean get() = rms > 0.018f && voicedRatio >= 0.56f && zcr in 0.015f..0.28f
    }

    private fun goertzelMagnitudeSq(samples: ShortArray, offset: Int, len: Int, targetFreqHz: Double): Float {
        val omega = (2.0 * PI * targetFreqHz) / SAMPLE_RATE
        val coeff = 2.0 * cos(omega)
        var sPrev = 0.0
        var sPrev2 = 0.0
        for (i in 0 until len) {
            val x = samples[offset + i].toDouble() / Short.MAX_VALUE.toDouble()
            val s = x + coeff * sPrev - sPrev2
            sPrev2 = sPrev
            sPrev = s
        }
        val power = sPrev2 * sPrev2 + sPrev * sPrev - coeff * sPrev * sPrev2
        return max(0.0, power).toFloat()
    }

    private fun extractFrameFeatures(samples: ShortArray, offset: Int, len: Int): FrameFeatures {
        var sumSq = 0.0
        var zeroCrossings = 0
        var prev = samples[offset].toInt()
        for (i in 0 until len) {
            val cur = samples[offset + i].toInt()
            val norm = cur / 32768.0
            sumSq += norm * norm
            if (i > 0 && ((cur >= 0 && prev < 0) || (cur < 0 && prev >= 0))) {
                zeroCrossings++
            }
            prev = cur
        }
        val rms = sqrt(sumSq / len).toFloat()
        val zcr = zeroCrossings.toFloat() / len.toFloat()

        val eLow = goertzelMagnitudeSq(samples, offset, len, F_LOW)
        val eMid = goertzelMagnitudeSq(samples, offset, len, F_MID)
        val eSib = goertzelMagnitudeSq(samples, offset, len, F_SIB)
        return FrameFeatures(rms = rms, zcr = zcr, lowEnergy = eLow, midEnergy = eMid, sibEnergy = eSib)
    }

    /**
     * Evaluates a captured speech window [pcm] against acoustic cues for [option].
     */
    fun evaluatePcmWindow(
        pcm: ShortArray,
        sampleCount: Int = pcm.size,
        option: WakeWordOption,
        ambientNoiseRms: Float = 0.015f
    ): OfflineAcousticEvaluation {
        if (sampleCount < FRAME_SAMPLES * 13) { // < 260ms too short to be "Siri" / "Hey Siri"
            return OfflineAcousticEvaluation(false, "", 0f, (sampleCount * 1000) / SAMPLE_RATE, "Utterance too short")
        }
        val durationMs = (sampleCount * 1000) / SAMPLE_RATE
        if (durationMs > 1750) {
            return OfflineAcousticEvaluation(false, "", 0f, durationMs, "Utterance too long for wake phrase")
        }

        val frames = mutableListOf<FrameFeatures>()
        var idx = 0
        while (idx + FRAME_SAMPLES <= sampleCount) {
            frames.add(extractFrameFeatures(pcm, idx, FRAME_SAMPLES))
            idx += FRAME_SAMPLES
        }
        if (frames.size < 13) {
            return OfflineAcousticEvaluation(false, "", 0f, durationMs, "Insufficient acoustic frames")
        }

        val peakRms = frames.maxOf { it.rms }
        val avgRms = frames.map { it.rms }.average().toFloat()
        val crestRatio = peakRms / max(avgRms, 1e-4f)
        if (peakRms < max(0.032f, ambientNoiseRms * 2.1f)) {
            return OfflineAcousticEvaluation(false, "", 0f, durationMs, "Insufficient SNR above noise floor")
        }
        if (crestRatio < 1.18f) {
            // Flat broadband fan/static noise lacks syllabic modulation
            return OfflineAcousticEvaluation(false, "", 0f, durationMs, "Flat broadband non-speech energy")
        }

        // Locate strongest sibilant /s/ cluster followed by voiced "iri" nucleus
        var bestSibStart = -1
        var bestSibEnd = -1
        var bestSibRunScore = 0f

        var i = 0
        while (i < frames.size) {
            if (frames[i].isSibilant) {
                var j = i
                var scoreSum = 0f
                while (j < frames.size && frames[j].isSibilant) {
                    scoreSum += frames[j].sibRatio
                    j++
                }
                val sibLen = j - i
                // Sibilant /s/ in "Siri" lasts ~40ms..260ms (2..13 frames)
                if (sibLen in 2..14 && scoreSum > bestSibRunScore) {
                    bestSibRunScore = scoreSum
                    bestSibStart = i
                    bestSibEnd = j
                }
                i = j
            } else {
                i++
            }
        }

        if (bestSibStart < 0) {
            return OfflineAcousticEvaluation(false, "", 0.15f, durationMs, "Missing /s/ onset sibilant")
        }

        val preFrames = frames.subList(0, bestSibStart)
        val postFrames = frames.subList(bestSibEnd, frames.size)

        val preVoicedCount = preFrames.count { it.isVoiced }
        val postVoicedCount = postFrames.count { it.isVoiced }
        val preSibilantCount = preFrames.count { it.isSibilant }

        // Post-sibilant "iri" requires at least 160ms (8 voiced frames)
        if (postVoicedCount < 8 || postFrames.size > 48) {
            return OfflineAcousticEvaluation(false, "", 0.25f, durationMs, "Missing two-syllable 'iri' vowel contour after /s/")
        }

        // Check two-syllable modulation OR clear mid-vowel F2 prominence in "iri"
        val postMidProminentCount = postFrames.count { it.isVoiced && (it.midEnergy + it.lowEnergy) > it.sibEnergy * 1.4f }
        if (postMidProminentCount < 6) {
            return OfflineAcousticEvaluation(false, "", 0.30f, durationMs, "Post-sibilant vowel formants too weak")
        }

        return when (option) {
            WakeWordOption.HEY_SIRI -> {
                // Must have voiced "Hey" before /s/ (~80ms..500ms => 4..26 voiced frames)
                if (preVoicedCount < 4 || preVoicedCount > 28) {
                    OfflineAcousticEvaluation(false, "", 0.35f, durationMs, "Missing 'Hey' voiced prefix before 'Siri'")
                } else {
                    val conf = (0.72f + minOf(0.14f, bestSibRunScore * 0.03f) + minOf(0.10f, postVoicedCount * 0.008f)).coerceAtMost(0.97f)
                    OfflineAcousticEvaluation(true, WakeWordOption.HEY_SIRI.label, conf, durationMs)
                }
            }
            WakeWordOption.SIRI -> {
                // Matches either crisp "Siri" (short/no prefix) or "Hey Siri"
                if (preVoicedCount > 28) {
                    OfflineAcousticEvaluation(false, "", 0.32f, durationMs, "Excessive non-wake prefix before 'Siri'")
                } else {
                    val conf = (0.74f + minOf(0.14f, bestSibRunScore * 0.03f) + minOf(0.09f, postVoicedCount * 0.008f)).coerceAtMost(0.97f)
                    OfflineAcousticEvaluation(true, WakeWordOption.SIRI.label, conf, durationMs)
                }
            }
            WakeWordOption.SUNO_SIRI -> {
                // Matches "Suno Siri" (pre-sibilant /s/ + voiced "uno" + /s/ + "iri") OR "Hey Siri"
                val hasSunoContour = preSibilantCount >= 2 && preVoicedCount >= 4
                val hasHeyContour = preVoicedCount in 4..28
                if (!hasSunoContour && !hasHeyContour) {
                    OfflineAcousticEvaluation(false, "", 0.33f, durationMs, "Missing 'Suno'/'Hey' prefix before 'Siri'")
                } else {
                    val conf = (0.72f + minOf(0.15f, bestSibRunScore * 0.03f)).coerceAtMost(0.96f)
                    OfflineAcousticEvaluation(true, WakeWordOption.SUNO_SIRI.label, conf, durationMs)
                }
            }
        }
    }
}

/**
 * Continuous, zero-restart offline wake word monitor backed by a single AudioRecord stream.
 * Does NOT repeatedly start/stop the microphone and transmits zero audio to the network.
 */
class AlwaysOnWakeWordEngine(
    private val context: Context,
    private val onWakeDetected: (matchedPhrase: String, confidence: Float) -> Unit,
    private val onHardwareError: ((String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "AlwaysOnWakeWord"
        private const val MAX_WINDOW_SAMPLES = OfflineHotwordAcousticClassifier.SAMPLE_RATE * 18 / 10 // 1.8s
        private const val SILENCE_END_FRAMES = 11 // 220ms trailing silence triggers utterance check
        private const val ECHO_COOLDOWN_MS = 950L
        private const val TRIGGER_COOLDOWN_MS = 2_200L
    }

    private val isRunning = AtomicBoolean(false)
    private val micOpenCount = AtomicInteger(0)

    @Volatile
    var wakeWordOption: WakeWordOption = WakeWordOption.HEY_SIRI

    @Volatile
    private var lastTtsFinishedAtMs: Long = 0L

    @Volatile
    private var lastTriggerAtMs: Long = 0L

    private var audioRecord: AudioRecord? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var readerThread: Thread? = null

    fun isListening(): Boolean = isRunning.get()

    /** Telemetry counter verifying the mic opens once per standby session instead of polling ON/OFF. */
    fun getMicOpenSessionCount(): Int = micOpenCount.get()

    fun markAssistantFinishedSpeaking(nowMs: Long = System.currentTimeMillis()) {
        lastTtsFinishedAtMs = nowMs
    }

    @Synchronized
    fun start(): Boolean {
        if (isRunning.get()) return true
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onHardwareError?.invoke("Microphone permission missing")
            return false
        }

        val minBuf = AudioRecord.getMinBufferSize(
            OfflineHotwordAcousticClassifier.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onHardwareError?.invoke("AudioRecord unsupported on device")
            return false
        }

        val bufferBytes = max(minBuf * 2, OfflineHotwordAcousticClassifier.FRAME_SAMPLES * 2 * 8)
        val record = createBestAudioRecord(bufferBytes)
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            try {
                record?.release()
            } catch (_: Exception) {
            }
            onHardwareError?.invoke("Unable to initialize continuous audio input")
            return false
        }

        attachHardwareAudioEffects(record.audioSessionId)
        audioRecord = record

        try {
            record.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord.startRecording failed", e)
            releaseAudioResources()
            onHardwareError?.invoke("Microphone unavailable: ${e.message}")
            return false
        }

        isRunning.set(true)
        micOpenCount.incrementAndGet()

        val worker = Thread({ runContinuousLoop(record) }, "SiriAlwaysOnWakeThread").apply {
            priority = Thread.NORM_PRIORITY
        }
        readerThread = worker
        worker.start()
        return true
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
        val t = readerThread
        readerThread = null
        if (t != null && t !== Thread.currentThread()) {
            try {
                t.join(250L)
            } catch (_: InterruptedException) {
            }
        }
        releaseAudioResources()
    }

    private fun createBestAudioRecord(bufferBytes: Int): AudioRecord? {
        val sources = intArrayOf(MediaRecorder.AudioSource.VOICE_RECOGNITION, MediaRecorder.AudioSource.MIC)
        for (source in sources) {
            try {
                val candidate = AudioRecord(
                    source,
                    OfflineHotwordAcousticClassifier.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    return candidate
                }
                candidate.release()
            } catch (e: SecurityException) {
                throw e
            } catch (_: Exception) {
            }
        }
        return null
    }

    private fun attachHardwareAudioEffects(sessionId: Int) {
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        } catch (_: Exception) {
        }
    }

    private fun releaseAudioResources() {
        try {
            echoCanceler?.release()
        } catch (_: Exception) {
        }
        echoCanceler = null
        try {
            noiseSuppressor?.release()
        } catch (_: Exception) {
        }
        noiseSuppressor = null
        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }
        audioRecord = null
    }

    private fun runContinuousLoop(record: AudioRecord) {
        val frameBuf = ShortArray(OfflineHotwordAcousticClassifier.FRAME_SAMPLES)
        val utteranceBuf = ShortArray(MAX_WINDOW_SAMPLES)
        var utteranceSamples = 0
        var inUtterance = false
        var silenceFrames = 0
        var ambientNoiseRms = 0.015f
        var zeroReadErrors = 0

        while (isRunning.get()) {
            val read = try {
                record.read(frameBuf, 0, frameBuf.size)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.w(TAG, "AudioRecord read exception", e)
                    onHardwareError?.invoke("Continuous mic stream interrupted")
                }
                break
            }

            if (read <= 0) {
                zeroReadErrors++
                if (zeroReadErrors > 15 && isRunning.get()) {
                    onHardwareError?.invoke("Microphone stream silenced or busy")
                    break
                }
                continue
            }
            zeroReadErrors = 0

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastTtsFinishedAtMs < ECHO_COOLDOWN_MS) {
                inUtterance = false
                utteranceSamples = 0
                silenceFrames = 0
                continue
            }

            // Lightweight energy + ZCR VAD check (minimal CPU while silent)
            var sumSq = 0.0
            var zc = 0
            var prev = frameBuf[0].toInt()
            for (i in 0 until read) {
                val cur = frameBuf[i].toInt()
                val n = cur / 32768.0
                sumSq += n * n
                if (i > 0 && ((cur >= 0 && prev < 0) || (cur < 0 && prev >= 0))) zc++
                prev = cur
            }
            val rms = sqrt(sumSq / read).toFloat()
            val zcr = zc.toFloat() / read.toFloat()

            val speechThreshold = max(0.022f, ambientNoiseRms * 2.3f)
            val isSpeechFrame = rms > speechThreshold && zcr in 0.015f..0.68f

            if (isSpeechFrame) {
                inUtterance = true
                silenceFrames = 0
                val copyLen = minOf(read, MAX_WINDOW_SAMPLES - utteranceSamples)
                if (copyLen > 0) {
                    System.arraycopy(frameBuf, 0, utteranceBuf, utteranceSamples, copyLen)
                    utteranceSamples += copyLen
                }
                if (utteranceSamples >= MAX_WINDOW_SAMPLES) {
                    evaluateCapturedBuffer(utteranceBuf, utteranceSamples, ambientNoiseRms)
                    inUtterance = false
                    utteranceSamples = 0
                    silenceFrames = 0
                }
            } else {
                // Adapt ambient noise floor in silence
                ambientNoiseRms = if (rms < ambientNoiseRms) {
                    ambientNoiseRms * 0.85f + rms * 0.15f
                } else {
                    ambientNoiseRms * 0.96f + rms * 0.04f
                }.coerceIn(0.005f, 0.12f)

                if (inUtterance) {
                    val copyLen = minOf(read, MAX_WINDOW_SAMPLES - utteranceSamples)
                    if (copyLen > 0) {
                        System.arraycopy(frameBuf, 0, utteranceBuf, utteranceSamples, copyLen)
                        utteranceSamples += copyLen
                    }
                    silenceFrames++
                    if (silenceFrames >= SILENCE_END_FRAMES) {
                        evaluateCapturedBuffer(utteranceBuf, utteranceSamples, ambientNoiseRms)
                        inUtterance = false
                        utteranceSamples = 0
                        silenceFrames = 0
                    }
                }
            }
        }
    }

    private fun evaluateCapturedBuffer(pcm: ShortArray, sampleCount: Int, ambientNoiseRms: Float) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerAtMs < TRIGGER_COOLDOWN_MS) return

        val eval = OfflineHotwordAcousticClassifier.evaluatePcmWindow(
            pcm = pcm,
            sampleCount = sampleCount,
            option = wakeWordOption,
            ambientNoiseRms = ambientNoiseRms
        )
        if (eval.detected) {
            lastTriggerAtMs = now
            onWakeDetected(eval.matchedPhrase, eval.confidence)
        }
    }
}
