package com.example.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

/**
 * Plays a brief, non-blocking dual-harmonic activation chime when "Hey Siri" or "Siri" is detected.
 * Respects device ringer silence / muted notification streams safely.
 */
object ActivationSoundPlayer {

    private const val TAG = "ActivationSoundPlayer"
    private const val SAMPLE_RATE = 16_000
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun playActivationChime(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am != null && am.ringerMode == AudioManager.RINGER_MODE_SILENT) {
            return
        }
        scope.launch {
            playTwoToneChimeInternal()
        }
    }

    internal fun generateChimeSamples(): ShortArray {
        // Two crisp rising harmonics: 784 Hz (G5, 55 ms) -> 1046.5 Hz (C6, 85 ms)
        val part1Len = (SAMPLE_RATE * 0.055f).toInt()
        val part2Len = (SAMPLE_RATE * 0.085f).toInt()
        val total = part1Len + part2Len
        val pcm = ShortArray(total)

        for (i in 0 until part1Len) {
            val env = sin(PI * i / part1Len).toFloat()
            val sample = sin(2.0 * PI * 784.0 * i / SAMPLE_RATE).toFloat() * env * 0.35f
            pcm[i] = (sample * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        for (j in 0 until part2Len) {
            val env = sin(PI * j / part2Len).toFloat()
            val tone = (0.75f * sin(2.0 * PI * 1046.5 * j / SAMPLE_RATE).toFloat() +
                0.25f * sin(2.0 * PI * 1568.0 * j / SAMPLE_RATE).toFloat()) * env * 0.35f
            pcm[part1Len + j] = (tone * Short.MAX_VALUE).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return pcm
    }

    private fun playTwoToneChimeInternal() {
        var track: AudioTrack? = null
        try {
            val pcm = generateChimeSamples()
            val minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(pcm.size * 2)

            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            val format = AudioFormat.Builder()
                .setSampleRate(SAMPLE_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()

            track = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBuf)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            track.write(pcm, 0, pcm.size)
            track.play()
            Thread.sleep(160L)
        } catch (e: Exception) {
            Log.d(TAG, "Activation chime skipped in this environment: ${e.message}")
        } finally {
            try {
                track?.stop()
                track?.release()
            } catch (_: Exception) {
            }
        }
    }
}
