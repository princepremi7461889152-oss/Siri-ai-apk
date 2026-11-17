package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.example.model.AssistantEmotion
import com.example.model.VoiceState
import com.example.ui.theme.SiriChampagneGold
import com.example.ui.theme.SiriCyan
import com.example.ui.theme.SiriDeepGold
import com.example.ui.theme.SiriGold
import com.example.ui.theme.SiriGoldWhite
import com.example.ui.theme.SiriLightGold
import com.example.ui.theme.SiriWarmGold
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private class OrbPathCache {
    val waveOuterPath = Path()
    val waveInnerPath = Path()
    val leftBrowPath = Path()
    val rightBrowPath = Path()
    val eyeArcPath = Path()
    val mouthPath = Path()
    val grinPath = Path()
}

/**
 * High-FPS, GPU-accelerated volumetric Siri AI Orb with continuous zero-reset phase integration,
 * real microphone & TTS audio synchronization, floating particles, 3D orbital rings,
 * soft harmonic energy waves, and expressive emotional animations.
 */
@Composable
fun SiriOrb(
    voiceState: VoiceState,
    audioLevel: Float,
    emotion: AssistantEmotion = AssistantEmotion.NEUTRAL,
    reducedMotion: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val pathCache = remember { OrbPathCache() }

    // Audio envelope smoothed quickly to stay responsive to voice RMS and TTS word cadence
    val smoothAudio by animateFloatAsState(
        targetValue = audioLevel.coerceIn(0.02f, 1.0f),
        animationSpec = tween(durationMillis = 75, easing = FastOutSlowInEasing),
        label = "orb_smooth_audio"
    )

    // Smooth velocity targets (degrees/sec or radians/sec) so switching states NEVER jumps or resets phase
    val targetCoreSpeedDeg = when {
        reducedMotion -> 0f
        voiceState == VoiceState.ERROR -> 18f
        voiceState == VoiceState.THINKING -> 175f
        voiceState == VoiceState.SPEAKING -> 92f
        voiceState == VoiceState.LISTENING -> 68f + (audioLevel.coerceIn(0f, 1f) * 58f)
        emotion == AssistantEmotion.EXCITEMENT -> 115f
        emotion == AssistantEmotion.HAPPINESS -> 64f
        emotion == AssistantEmotion.SURPRISE -> 82f
        emotion == AssistantEmotion.CURIOSITY -> 56f
        emotion == AssistantEmotion.SADNESS -> 18f
        emotion == AssistantEmotion.SERIOUSNESS -> 26f
        else -> 36f
    }

    val targetWaveSpeedRad = when {
        reducedMotion -> 0f
        voiceState == VoiceState.ERROR -> 1.3f
        voiceState == VoiceState.THINKING -> 4.6f
        voiceState == VoiceState.SPEAKING -> 5.4f
        voiceState == VoiceState.LISTENING -> 3.8f
        emotion == AssistantEmotion.EXCITEMENT -> 4.2f
        emotion == AssistantEmotion.HAPPINESS -> 2.8f
        emotion == AssistantEmotion.SURPRISE -> 3.6f
        emotion == AssistantEmotion.SADNESS -> 1.35f
        else -> 2.1f
    }

    val targetParticleSpeedRad = when {
        reducedMotion -> 0f
        voiceState == VoiceState.ERROR -> 0.28f
        voiceState == VoiceState.THINKING -> 1.65f
        voiceState == VoiceState.SPEAKING -> 1.10f
        voiceState == VoiceState.LISTENING -> 0.95f
        emotion == AssistantEmotion.EXCITEMENT -> 1.55f
        emotion == AssistantEmotion.HAPPINESS -> 0.85f
        emotion == AssistantEmotion.SADNESS -> 0.34f
        else -> 0.62f
    }

    val targetBreathSpeedRad = when {
        reducedMotion -> 0f
        voiceState == VoiceState.THINKING -> 3.4f
        emotion == AssistantEmotion.EXCITEMENT -> 3.8f
        emotion == AssistantEmotion.SADNESS -> 1.45f
        else -> 2.2f
    }

    val coreSpeedDeg by animateFloatAsState(
        targetValue = targetCoreSpeedDeg,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "core_speed"
    )
    val waveSpeedRad by animateFloatAsState(
        targetValue = targetWaveSpeedRad,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "wave_speed"
    )
    val particleSpeedRad by animateFloatAsState(
        targetValue = targetParticleSpeedRad,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "particle_speed"
    )
    val breathSpeedRad by animateFloatAsState(
        targetValue = targetBreathSpeedRad,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "breath_speed"
    )

    // Continuous phase states — read ONLY in graphicsLayer & Canvas DrawScope (0 recompositions per frame!)
    val coreAngleState = remember { mutableFloatStateOf(0f) }
    val ringAngleState = remember { mutableFloatStateOf(0f) }
    val wavePhaseState = remember { mutableFloatStateOf(0f) }
    val particlePhaseState = remember { mutableFloatStateOf(0f) }
    val breathPhaseState = remember { mutableFloatStateOf(0f) }
    val floatPhaseState = remember { mutableFloatStateOf(0f) }
    val blinkClockState = remember { mutableFloatStateOf(0f) }

    val twoPi = (2.0 * PI).toFloat()

    // Vsync-locked 60/120Hz frame loop; automatically pauses when backgrounded or reducedMotion=true
    LaunchedEffect(reducedMotion, lifecycleOwner) {
        if (reducedMotion) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var lastNanos = 0L
            while (isActive) {
                withFrameNanos { frameNanos ->
                    if (lastNanos != 0L) {
                        val dt = ((frameNanos - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
                        coreAngleState.floatValue = (coreAngleState.floatValue + dt * coreSpeedDeg) % 360f
                        ringAngleState.floatValue = (ringAngleState.floatValue - dt * (coreSpeedDeg * 0.68f) + 360f) % 360f
                        wavePhaseState.floatValue = (wavePhaseState.floatValue + dt * waveSpeedRad) % twoPi
                        particlePhaseState.floatValue = (particlePhaseState.floatValue + dt * particleSpeedRad) % (twoPi * 4f)
                        breathPhaseState.floatValue = (breathPhaseState.floatValue + dt * breathSpeedRad) % twoPi
                        floatPhaseState.floatValue = (floatPhaseState.floatValue + dt * 1.65f) % twoPi
                        blinkClockState.floatValue = (blinkClockState.floatValue + dt / 3.8f) % 1.0f
                    }
                    lastNanos = frameNanos
                }
            }
        }
    }

    // Surprise quick expansion burst + glowing pulse shockwave
    val surpriseBurst = remember { Animatable(0f) }
    val surpriseShockwave = remember { Animatable(0f) }
    LaunchedEffect(emotion, reducedMotion) {
        if (emotion == AssistantEmotion.SURPRISE && !reducedMotion) {
            surpriseBurst.snapTo(0f)
            surpriseShockwave.snapTo(0f)
            launch {
                surpriseBurst.animateTo(1f, animationSpec = tween(140, easing = FastOutSlowInEasing))
                surpriseBurst.animateTo(
                    0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow)
                )
            }
            launch {
                surpriseShockwave.animateTo(1f, animationSpec = tween(760, easing = FastOutSlowInEasing))
            }
        } else {
            surpriseBurst.animateTo(0f, animationSpec = tween(200))
            surpriseShockwave.snapTo(0f)
        }
    }

    // Smooth energy scale transitions between states
    val stateEnergy by animateFloatAsState(
        targetValue = when (voiceState) {
            VoiceState.LISTENING -> 1.00f
            VoiceState.THINKING -> 1.06f
            VoiceState.SPEAKING -> 1.04f
            VoiceState.ERROR -> 0.86f
            VoiceState.IDLE -> when (emotion) {
                AssistantEmotion.EXCITEMENT -> 1.04f
                AssistantEmotion.SADNESS -> 0.90f
                else -> 0.95f
            }
        },
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "state_energy"
    )

    val targetFloatAmplitude = when {
        reducedMotion -> 0f
        voiceState == VoiceState.ERROR -> 0f
        voiceState == VoiceState.IDLE && emotion == AssistantEmotion.SADNESS -> 3.0f
        voiceState == VoiceState.IDLE -> 5.5f
        else -> 2.0f
    }
    val floatAmplitude by animateFloatAsState(
        targetValue = targetFloatAmplitude,
        animationSpec = spring(stiffness = Spring.StiffnessLow),
        label = "float_amplitude"
    )

    // Smooth facial expression interpolations
    val targetHeadTilt = when {
        reducedMotion -> 0f
        voiceState == VoiceState.ERROR -> 0f
        emotion == AssistantEmotion.CURIOSITY -> -7f
        emotion == AssistantEmotion.SADNESS -> 4.5f
        else -> 0f
    }
    val baseHeadTilt by animateFloatAsState(
        targetValue = targetHeadTilt,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "head_tilt"
    )

    val smileFactor by animateFloatAsState(
        targetValue = when {
            voiceState == VoiceState.ERROR -> -0.35f
            emotion == AssistantEmotion.HAPPINESS -> 0.90f
            emotion == AssistantEmotion.EXCITEMENT -> 1.0f
            emotion == AssistantEmotion.SADNESS -> -0.78f
            emotion == AssistantEmotion.SURPRISE -> 0.12f
            emotion == AssistantEmotion.CURIOSITY -> 0.25f
            emotion == AssistantEmotion.SERIOUSNESS -> -0.10f
            else -> 0.36f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "smile_factor"
    )

    val browLift by animateFloatAsState(
        targetValue = when {
            voiceState == VoiceState.ERROR -> -0.40f
            emotion == AssistantEmotion.SURPRISE -> 1.0f
            emotion == AssistantEmotion.EXCITEMENT -> 0.65f
            emotion == AssistantEmotion.HAPPINESS -> 0.30f
            emotion == AssistantEmotion.CURIOSITY -> 0.58f
            emotion == AssistantEmotion.SADNESS -> -0.48f
            emotion == AssistantEmotion.SERIOUSNESS -> -0.58f
            else -> 0f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "brow_lift"
    )

    // Color identity per state & emotion (preserving golden orb core identity)
    val targetPrimaryColor = when {
        voiceState == VoiceState.ERROR -> Color(0xFFE53935)                // Subtle red glow
        voiceState == VoiceState.THINKING -> Color(0xFF8B5CF6)             // Purple-blue processing waves
        emotion == AssistantEmotion.SADNESS -> Color(0xFF64B5F6)           // Soft blue glow
        emotion == AssistantEmotion.EXCITEMENT -> Color(0xFFFFD54F)        // Bright golden-orange glow
        emotion == AssistantEmotion.HAPPINESS -> SiriGold                  // Warm golden glow
        emotion == AssistantEmotion.CURIOSITY -> Color(0xFF8B5CF6)         // Purple-blue thinking/curious waves
        emotion == AssistantEmotion.SURPRISE -> SiriGold                   // Radiant gold + crisp cyan pulse
        emotion == AssistantEmotion.SERIOUSNESS -> Color(0xFFE6B800)       // Deep steady gold
        else -> SiriGold                                                   // Classic 24K Siri Gold
    }

    val targetSecondaryColor = when {
        voiceState == VoiceState.ERROR -> Color(0xFFC62828)                // Subtle crimson
        voiceState == VoiceState.THINKING -> Color(0xFF3B82F6)             // Electric royal blue
        emotion == AssistantEmotion.SADNESS -> Color(0xFF2979FF)           // Deep soft blue
        emotion == AssistantEmotion.EXCITEMENT -> Color(0xFFFF6D00)        // Energetic bright orange
        emotion == AssistantEmotion.HAPPINESS -> SiriWarmGold              // Warm sunlit amber-gold
        emotion == AssistantEmotion.CURIOSITY -> Color(0xFF2979FF)         // Electric blue
        emotion == AssistantEmotion.SURPRISE -> SiriCyan                   // Luminous surprise flash
        emotion == AssistantEmotion.SERIOUSNESS -> Color(0xFF8D6E63)       // Focused warm bronze
        else -> SiriWarmGold
    }

    val targetTertiaryColor = when {
        voiceState == VoiceState.ERROR -> Color(0xFF7F1D1D)
        voiceState == VoiceState.THINKING -> Color(0xFF00E5FF)
        emotion == AssistantEmotion.SADNESS -> Color(0xFF1565C0)
        emotion == AssistantEmotion.EXCITEMENT -> Color(0xFFFF3D00)
        emotion == AssistantEmotion.HAPPINESS -> SiriLightGold
        emotion == AssistantEmotion.CURIOSITY -> SiriCyan
        emotion == AssistantEmotion.SURPRISE -> SiriChampagneGold
        emotion == AssistantEmotion.SERIOUSNESS -> SiriDeepGold
        else -> SiriDeepGold
    }

    val targetAccentColor = when {
        voiceState == VoiceState.ERROR -> Color(0xFFFF8A80)
        voiceState == VoiceState.THINKING -> Color(0xFFE0E7FF)
        emotion == AssistantEmotion.SADNESS -> Color(0xFFBBDEFB)
        emotion == AssistantEmotion.SURPRISE -> Color.White
        emotion == AssistantEmotion.EXCITEMENT -> SiriGoldWhite
        else -> SiriChampagneGold
    }

    val cPrimary by animateColorAsState(targetValue = targetPrimaryColor, animationSpec = tween(420), label = "cPrimary")
    val cSecondary by animateColorAsState(targetValue = targetSecondaryColor, animationSpec = tween(420), label = "cSecondary")
    val cTertiary by animateColorAsState(targetValue = targetTertiaryColor, animationSpec = tween(420), label = "cTertiary")
    val cAccent by animateColorAsState(targetValue = targetAccentColor, animationSpec = tween(420), label = "cAccent")

    val bloomAlphaMultiplier by animateFloatAsState(
        targetValue = when {
            voiceState == VoiceState.ERROR -> 0.58f // Keep error red subtle, never harsh
            emotion == AssistantEmotion.EXCITEMENT -> 1.18f
            voiceState == VoiceState.THINKING -> 1.12f
            emotion == AssistantEmotion.SADNESS -> 0.85f
            else -> 1.0f
        },
        animationSpec = tween(350),
        label = "bloom_alpha"
    )

    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            .size(260.dp)
            .testTag("siri_voice_orb")
            .semantics {
                contentDescription = "Siri orb, state ${voiceState.name}, emotion ${emotion.displayName}"
            }
            .graphicsLayer {
                translationY = if (reducedMotion) 0f else (sin(floatPhaseState.floatValue) * floatAmplitude.dp.toPx())
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val baseRadius = (size.minDimension / 2f) * 0.68f

            val coreAngle = coreAngleState.floatValue
            val ringAngle = ringAngleState.floatValue
            val wavePhase = wavePhaseState.floatValue
            val particlePhase = particlePhaseState.floatValue
            val breathPhase = breathPhaseState.floatValue
            val blinkClock = blinkClockState.floatValue

            val blinkOpenness = if (!reducedMotion && blinkClock > 0.93f) {
                val t = (blinkClock - 0.93f) / 0.07f
                abs(cos(t * PI)).toFloat().coerceIn(0.08f, 1f)
            } else {
                1f
            }

            // Smooth breathing + real voice-driven expansion + surprise pop
            val breathAmplitude = when {
                reducedMotion -> 0f
                emotion == AssistantEmotion.EXCITEMENT -> 0.045f
                emotion == AssistantEmotion.HAPPINESS -> 0.036f
                emotion == AssistantEmotion.SADNESS -> 0.020f
                else -> 0.032f
            }
            val breathScale = 1.0f + (breathAmplitude * sin(breathPhase))

            val audioScale = when (voiceState) {
                VoiceState.LISTENING -> smoothAudio * 0.26f
                VoiceState.SPEAKING -> (smoothAudio * 0.18f) + (0.03f * sin(wavePhase * 2f))
                else -> 0f
            }

            val surpriseScale = surpriseBurst.value * 0.16f
            val livelyBounce = when {
                reducedMotion -> 0f
                emotion == AssistantEmotion.EXCITEMENT -> 0.028f * abs(sin(wavePhase))
                emotion == AssistantEmotion.HAPPINESS -> 0.016f * sin(wavePhase)
                else -> 0f
            }

            val currentRadius = baseRadius * (breathScale + audioScale + surpriseScale + livelyBounce) * stateEnergy

            // LAYER 1: Volumetric Atmospheric Bloom & Smooth Glowing Halo
            val audioBloomBoost = if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING) {
                smoothAudio * 0.28f
            } else 0f
            val bloomRadius = currentRadius * (1.62f + audioBloomBoost + (surpriseBurst.value * 0.25f))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        cPrimary.copy(alpha = (0.34f * bloomAlphaMultiplier).coerceIn(0f, 0.55f)),
                        cSecondary.copy(alpha = (0.18f * bloomAlphaMultiplier).coerceIn(0f, 0.35f)),
                        cTertiary.copy(alpha = (0.07f * bloomAlphaMultiplier).coerceIn(0f, 0.20f)),
                        Color.Transparent
                    ),
                    center = center,
                    radius = bloomRadius
                ),
                radius = bloomRadius,
                center = center
            )

            val innerHaloRadius = currentRadius * 1.28f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        cSecondary.copy(alpha = (0.40f * bloomAlphaMultiplier).coerceIn(0f, 0.58f)),
                        cTertiary.copy(alpha = (0.18f * bloomAlphaMultiplier).coerceIn(0f, 0.32f)),
                        Color.Transparent
                    ),
                    center = center,
                    radius = innerHaloRadius
                ),
                radius = innerHaloRadius,
                center = center
            )

            // LAYER 2: Soft Energy Waves + Surprise Shockwave + Audio Sound Ripples
            drawSoftEnergyWaves(
                center = center,
                radius = currentRadius,
                wavePhase = wavePhase,
                coreAngle = coreAngle,
                smoothAudio = smoothAudio,
                voiceState = voiceState,
                emotion = emotion,
                surpriseShockwave = surpriseShockwave.value,
                cPrimary = cPrimary,
                cSecondary = cSecondary,
                cTertiary = cTertiary,
                cAccent = cAccent,
                waveOuterPath = pathCache.waveOuterPath,
                waveInnerPath = pathCache.waveInnerPath
            )

            // LAYER 3: Continuously Moving 3D Glowing Particles & Starburst Flares
            if (!reducedMotion) {
                drawFloatingParticles(
                    center = center,
                    baseRadius = currentRadius,
                    phase = particlePhase,
                    wavePhase = wavePhase,
                    smoothAudio = smoothAudio,
                    voiceState = voiceState,
                    emotion = emotion,
                    colorA = cPrimary,
                    colorB = cSecondary,
                    colorAccent = cAccent
                )
            }

            // LAYER 4: Precessing 3D Gyroscopic Orbital Rings with Traveling Glow Nodes
            drawGyroscopicRings(
                center = center,
                radius = currentRadius,
                coreAngle = coreAngle,
                ringAngle = ringAngle,
                wavePhase = wavePhase,
                smoothAudio = smoothAudio,
                voiceState = voiceState,
                emotion = emotion,
                cPrimary = cPrimary,
                cSecondary = cSecondary,
                cTertiary = cTertiary,
                cAccent = cAccent
            )

            // LAYER 5: Volumetric Dark-Gold/Emotion Core & Rotating Internal Plasma Lobes
            drawVolumetricCore(
                center = center,
                radius = currentRadius,
                rotation = coreAngle,
                smoothAudio = smoothAudio,
                voiceState = voiceState,
                emotion = emotion,
                cPrimary = cPrimary,
                cSecondary = cSecondary,
                cTertiary = cTertiary,
                cAccent = cAccent
            )

            // LAYER 6: Expressive Animated Face inside the Volumetric Sphere
            val dynamicTilt = when {
                reducedMotion -> 0f
                emotion == AssistantEmotion.EXCITEMENT -> baseHeadTilt - (2.6f * sin(wavePhase))
                voiceState == VoiceState.THINKING -> baseHeadTilt + (4.2f * sin(wavePhase * 0.5f))
                else -> baseHeadTilt
            }
            drawEmotionalFace(
                center = center,
                radius = currentRadius,
                emotion = emotion,
                voiceState = voiceState,
                blinkOpenness = blinkOpenness,
                smileFactor = smileFactor,
                browLift = browLift,
                headTilt = dynamicTilt,
                wavePhase = wavePhase,
                smoothAudio = smoothAudio,
                cAccent = cAccent,
                pathCache = pathCache
            )

            // LAYER 7: Optical Fresnel Rim Lighting
            drawCircle(
                brush = Brush.sweepGradient(
                    colors = listOf(
                        cPrimary.copy(alpha = 0.92f),
                        cSecondary.copy(alpha = 0.78f),
                        cTertiary.copy(alpha = 0.85f),
                        cAccent.copy(alpha = 0.68f),
                        cPrimary.copy(alpha = 0.92f)
                    ),
                    center = center
                ),
                radius = currentRadius,
                center = center,
                style = Stroke(width = (2.2f + smoothAudio * 0.8f).dp.toPx())
            )

            // LAYER 8: Curved 3D Glass Specular Reflections
            drawGlassReflections(
                center = center,
                radius = currentRadius,
                cAccent = cAccent
            )
        }
    }
}

private fun DrawScope.drawSoftEnergyWaves(
    center: Offset,
    radius: Float,
    wavePhase: Float,
    coreAngle: Float,
    smoothAudio: Float,
    voiceState: VoiceState,
    emotion: AssistantEmotion,
    surpriseShockwave: Float,
    cPrimary: Color,
    cSecondary: Color,
    cTertiary: Color,
    cAccent: Color,
    waveOuterPath: Path,
    waveInnerPath: Path
) {
    val isThinkingWaves = voiceState == VoiceState.THINKING || emotion == AssistantEmotion.CURIOSITY
    val isAudioReactive = voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING

    val ampFactor = when {
        isAudioReactive -> 0.035f + (smoothAudio * 0.085f)
        isThinkingWaves -> 0.068f
        emotion == AssistantEmotion.EXCITEMENT -> 0.055f
        emotion == AssistantEmotion.SADNESS -> 0.022f
        else -> 0.030f
    }

    val steps = 48
    val twoPi = (2.0 * PI).toFloat()

    // Outer undulating plasma energy wave
    waveOuterPath.rewind()
    waveInnerPath.rewind()
    for (i in 0 until steps) {
        val theta = (i.toFloat() / steps.toFloat()) * twoPi
        val harmonic1 = sin(theta * 3f + wavePhase)
        val harmonic2 = 0.55f * cos(theta * 5f - wavePhase * 1.4f)
        val r1 = radius * (1.13f + ampFactor * (harmonic1 + harmonic2))
        val r2 = radius * (1.07f + (ampFactor * 0.72f) * sin(theta * 4f - wavePhase * 1.25f))

        val x1 = center.x + r1 * cos(theta)
        val y1 = center.y + r1 * sin(theta)
        val x2 = center.x + r2 * cos(theta)
        val y2 = center.y + r2 * sin(theta)

        if (i == 0) {
            waveOuterPath.moveTo(x1, y1)
            waveInnerPath.moveTo(x2, y2)
        } else {
            waveOuterPath.lineTo(x1, y1)
            waveInnerPath.lineTo(x2, y2)
        }
    }
    waveOuterPath.close()
    waveInnerPath.close()

    val outerWaveAlpha = if (voiceState == VoiceState.ERROR) 0.28f else if (isThinkingWaves) 0.65f else 0.45f
    drawPath(
        path = waveOuterPath,
        color = cSecondary.copy(alpha = outerWaveAlpha * 0.42f),
        style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus
    )
    drawPath(
        path = waveOuterPath,
        color = cPrimary.copy(alpha = outerWaveAlpha),
        style = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus
    )
    drawPath(
        path = waveInnerPath,
        color = cTertiary.copy(alpha = outerWaveAlpha * 0.85f),
        style = Stroke(width = 1.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        blendMode = BlendMode.Plus
    )

    // Voice-synchronized expanding audio ripple rings in Listening & Speaking modes
    if (isAudioReactive) {
        val ripplePhase1 = (wavePhase / twoPi) % 1.0f
        val ripplePhase2 = ((wavePhase / twoPi) + 0.5f) % 1.0f
        val rippleReach = radius * (0.28f + smoothAudio * 0.34f)

        val r1 = radius * 1.04f + rippleReach * ripplePhase1
        val a1 = ((1f - ripplePhase1) * (0.20f + smoothAudio * 0.50f)).coerceIn(0f, 0.70f)
        drawCircle(
            color = cPrimary.copy(alpha = a1),
            radius = r1,
            center = center,
            style = Stroke(width = (1.6f + smoothAudio * 2.0f).dp.toPx()),
            blendMode = BlendMode.Plus
        )

        val r2 = radius * 1.04f + rippleReach * ripplePhase2
        val a2 = ((1f - ripplePhase2) * (0.18f + smoothAudio * 0.45f)).coerceIn(0f, 0.65f)
        drawCircle(
            color = cSecondary.copy(alpha = a2),
            radius = r2,
            center = center,
            style = Stroke(width = (1.4f + smoothAudio * 1.6f).dp.toPx()),
            blendMode = BlendMode.Plus
        )
    }

    // Extra rotating purple & blue arc ribbons in Thinking / Processing mode
    if (isThinkingWaves) {
        rotate(degrees = coreAngle * 1.1f, pivot = center) {
            val arcRadius = radius * 1.24f
            drawArc(
                color = cPrimary.copy(alpha = 0.72f),
                startAngle = 0f,
                sweepAngle = 115f,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
                blendMode = BlendMode.Plus
            )
            drawArc(
                color = cSecondary.copy(alpha = 0.75f),
                startAngle = 180f,
                sweepAngle = 115f,
                useCenter = false,
                topLeft = Offset(center.x - arcRadius, center.y - arcRadius),
                size = Size(arcRadius * 2f, arcRadius * 2f),
                style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round),
                blendMode = BlendMode.Plus
            )
        }
    }

    // Surprise quick expansion glowing pulse wave
    if (surpriseShockwave in 0.01f..0.99f) {
        val shockRadius = radius * (1.02f + surpriseShockwave * 0.58f)
        val shockAlpha = ((1f - surpriseShockwave) * 0.85f).coerceIn(0f, 0.85f)
        drawCircle(
            color = cAccent.copy(alpha = shockAlpha),
            radius = shockRadius,
            center = center,
            style = Stroke(width = (3.5f * (1f - surpriseShockwave * 0.6f)).dp.toPx()),
            blendMode = BlendMode.Plus
        )
    }
}

private fun DrawScope.drawFloatingParticles(
    center: Offset,
    baseRadius: Float,
    phase: Float,
    wavePhase: Float,
    smoothAudio: Float,
    voiceState: VoiceState,
    emotion: AssistantEmotion,
    colorA: Color,
    colorB: Color,
    colorAccent: Color
) {
    val particleCount = when {
        voiceState == VoiceState.ERROR -> 8
        emotion == AssistantEmotion.EXCITEMENT -> 24
        voiceState == VoiceState.THINKING -> 20
        voiceState == VoiceState.SPEAKING || voiceState == VoiceState.LISTENING -> 18
        else -> 16
    }

    val isExcited = emotion == AssistantEmotion.EXCITEMENT
    val twoPi = (2.0 * PI).toFloat()

    // Energetic golden-orange radiant rays in Excited emotion
    if (isExcited) {
        val rayCount = 8
        for (r in 0 until rayCount) {
            val rayAngle = (r.toFloat() / rayCount) * twoPi + (phase * 0.25f)
            val pulse = 0.5f + 0.5f * sin(wavePhase * 1.5f + r)
            val startDist = baseRadius * 1.12f
            val endDist = baseRadius * (1.36f + 0.16f * pulse)
            drawLine(
                color = colorA.copy(alpha = 0.35f + 0.35f * pulse),
                start = Offset(center.x + startDist * cos(rayAngle), center.y + startDist * sin(rayAngle)),
                end = Offset(center.x + endDist * cos(rayAngle), center.y + endDist * sin(rayAngle)),
                strokeWidth = 2.0.dp.toPx(),
                cap = StrokeCap.Round,
                blendMode = BlendMode.Plus
            )
        }
    }

    for (i in 0 until particleCount) {
        val angleOffset = (i * (twoPi / particleCount))
        val direction = if (i % 2 == 0) 1f else -0.82f
        val pAngle = phase * direction + angleOffset

        val audioPush = if (voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING) {
            smoothAudio * 0.16f
        } else 0f
        val distanceScale = 1.12f + audioPush + (0.28f * sin((pAngle * 2f) + i))
        val orbitDist = baseRadius * distanceScale

        val tiltY = if (i % 3 == 0) 0.92f else 0.72f
        val px = center.x + (orbitDist * cos(pAngle))
        val py = center.y + (orbitDist * sin(pAngle) * tiltY)

        val pRadius = (1.5f + (1.1f * sin(pAngle + i)) + (if (isExcited) 0.6f else 0f)).dp.toPx()
        val baseAlpha = if (voiceState == VoiceState.ERROR) 0.45f else 0.88f
        val pAlpha = (0.35f + (0.50f * sin(pAngle + wavePhase))).coerceIn(0.15f, baseAlpha)
        val pColor = when (i % 3) {
            0 -> colorA
            1 -> colorB
            else -> colorAccent
        }

        // Soft additive bloom halo
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    colorAccent.copy(alpha = pAlpha),
                    pColor.copy(alpha = pAlpha * 0.55f),
                    Color.Transparent
                ),
                center = Offset(px, py),
                radius = pRadius * 2.5f
            ),
            radius = pRadius * 2.3f,
            center = Offset(px, py),
            blendMode = BlendMode.Plus
        )
        // Crisp bright particle core
        drawCircle(
            color = Color.White.copy(alpha = (pAlpha * 0.9f).coerceIn(0f, 0.95f)),
            radius = (pRadius * 0.55f).coerceAtLeast(1.2f),
            center = Offset(px, py)
        )
    }
}

private fun DrawScope.drawGyroscopicRings(
    center: Offset,
    radius: Float,
    coreAngle: Float,
    ringAngle: Float,
    wavePhase: Float,
    smoothAudio: Float,
    voiceState: VoiceState,
    emotion: AssistantEmotion,
    cPrimary: Color,
    cSecondary: Color,
    cTertiary: Color,
    cAccent: Color
) {
    val isActiveAudio = voiceState == VoiceState.LISTENING || voiceState == VoiceState.SPEAKING
    val ringExpand = if (isActiveAudio) 1f + smoothAudio * 0.12f else 1f
    val ringAlphaBoost = if (voiceState == VoiceState.ERROR) 0.55f else 1f

    // Ring 1: Primary tilted elliptical orbit with orbiting energy node
    val r1Deg = -28f + (coreAngle * 0.18f) + (4f * sin(wavePhase))
    val r1W = radius * 2.32f * ringExpand
    val r1H = radius * 0.88f * ringExpand
    rotate(degrees = r1Deg, pivot = center) {
        drawOval(
            brush = Brush.sweepGradient(
                colors = listOf(
                    cPrimary.copy(alpha = 0.88f * ringAlphaBoost),
                    cSecondary.copy(alpha = 0.22f * ringAlphaBoost),
                    cTertiary.copy(alpha = 0.78f * ringAlphaBoost),
                    cPrimary.copy(alpha = 0.15f * ringAlphaBoost),
                    cPrimary.copy(alpha = 0.88f * ringAlphaBoost)
                ),
                center = center
            ),
            topLeft = Offset(center.x - r1W / 2f, center.y - r1H / 2f),
            size = Size(r1W, r1H),
            style = Stroke(
                width = (1.8f + (smoothAudio * 1.8f)).dp.toPx(),
                cap = StrokeCap.Round
            ),
            blendMode = BlendMode.Plus
        )
        // Traveling glowing orbital bead on Ring 1
        val t1 = Math.toRadians(coreAngle.toDouble())
        val node1 = Offset(
            center.x + (r1W / 2f) * cos(t1).toFloat(),
            center.y + (r1H / 2f) * sin(t1).toFloat()
        )
        drawCircle(
            color = cAccent.copy(alpha = 0.90f * ringAlphaBoost),
            radius = 3.2.dp.toPx(),
            center = node1,
            blendMode = BlendMode.Plus
        )
    }

    // Ring 2: Counter-precessing gyroscopic orbit with traveling node
    val r2Deg = 42f + (ringAngle * 0.22f)
    val r2W = radius * 2.12f * ringExpand
    val r2H = radius * 0.74f * ringExpand
    rotate(degrees = r2Deg, pivot = center) {
        drawOval(
            brush = Brush.sweepGradient(
                colors = listOf(
                    cTertiary.copy(alpha = 0.82f * ringAlphaBoost),
                    cPrimary.copy(alpha = 0.25f * ringAlphaBoost),
                    cSecondary.copy(alpha = 0.86f * ringAlphaBoost),
                    cTertiary.copy(alpha = 0.15f * ringAlphaBoost),
                    cTertiary.copy(alpha = 0.82f * ringAlphaBoost)
                ),
                center = center
            ),
            topLeft = Offset(center.x - r2W / 2f, center.y - r2H / 2f),
            size = Size(r2W, r2H),
            style = Stroke(
                width = 1.6.dp.toPx(),
                cap = StrokeCap.Round
            ),
            blendMode = BlendMode.Plus
        )
        val t2 = Math.toRadians((ringAngle * 1.3f).toDouble())
        val node2 = Offset(
            center.x + (r2W / 2f) * cos(t2).toFloat(),
            center.y + (r2H / 2f) * sin(t2).toFloat()
        )
        drawCircle(
            color = cSecondary.copy(alpha = 0.88f * ringAlphaBoost),
            radius = 2.8.dp.toPx(),
            center = node2,
            blendMode = BlendMode.Plus
        )
    }

    // Ring 3: Subtle high-tilt energy halo ring (stronger during Thinking / Excitement)
    val r3Alpha = when {
        voiceState == VoiceState.THINKING -> 0.72f
        emotion == AssistantEmotion.EXCITEMENT -> 0.65f
        else -> 0.34f
    } * ringAlphaBoost
    val r3Deg = 8f - (coreAngle * 0.12f)
    val r3W = radius * 2.42f * ringExpand
    val r3H = radius * 0.56f * ringExpand
    rotate(degrees = r3Deg, pivot = center) {
        drawOval(
            brush = Brush.sweepGradient(
                colors = listOf(
                    cSecondary.copy(alpha = r3Alpha),
                    Color.Transparent,
                    cPrimary.copy(alpha = r3Alpha * 0.85f),
                    Color.Transparent,
                    cSecondary.copy(alpha = r3Alpha)
                ),
                center = center
            ),
            topLeft = Offset(center.x - r3W / 2f, center.y - r3H / 2f),
            size = Size(r3W, r3H),
            style = Stroke(width = 1.25.dp.toPx(), cap = StrokeCap.Round),
            blendMode = BlendMode.Plus
        )
    }
}

private fun DrawScope.drawVolumetricCore(
    center: Offset,
    radius: Float,
    rotation: Float,
    smoothAudio: Float,
    voiceState: VoiceState,
    emotion: AssistantEmotion,
    cPrimary: Color,
    cSecondary: Color,
    cTertiary: Color,
    cAccent: Color
) {
    // Deep spherical obsidian base tinted subtly toward current emotion
    val coreEdgeColor = when {
        voiceState == VoiceState.ERROR -> Color(0xFF1A0707)
        voiceState == VoiceState.THINKING -> Color(0xFF0C0922)
        emotion == AssistantEmotion.SADNESS -> Color(0xFF071324)
        else -> Color(0xFF181104)
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color(0xFF281F0C),
                coreEdgeColor,
                Color(0xFF070502)
            ),
            center = center,
            radius = radius
        ),
        radius = radius,
        center = center
    )

    val radAngle = Math.toRadians(rotation.toDouble())
    val lobeOrbit = radius * (0.40f + smoothAudio * 0.06f)

    // Three smoothly orbiting volumetric light lobes inside the sphere
    val nodeAOffset = Offset(
        x = center.x + (lobeOrbit * cos(radAngle)).toFloat(),
        y = center.y + (lobeOrbit * sin(radAngle)).toFloat()
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                cPrimary.copy(alpha = 0.58f),
                cPrimary.copy(alpha = 0.26f),
                Color.Transparent
            ),
            center = nodeAOffset,
            radius = radius * 0.88f
        ),
        radius = radius * 0.88f,
        center = nodeAOffset
    )

    val radAngleB = radAngle + (2.0 * PI / 3.0)
    val nodeBOffset = Offset(
        x = center.x + (lobeOrbit * 0.92f * cos(radAngleB)).toFloat(),
        y = center.y + (lobeOrbit * 0.92f * sin(radAngleB)).toFloat()
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                cSecondary.copy(alpha = 0.54f),
                cSecondary.copy(alpha = 0.24f),
                Color.Transparent
            ),
            center = nodeBOffset,
            radius = radius * 0.82f
        ),
        radius = radius * 0.82f,
        center = nodeBOffset
    )

    val radAngleC = radAngle + (4.0 * PI / 3.0)
    val nodeCOffset = Offset(
        x = center.x + (lobeOrbit * 0.84f * cos(radAngleC)).toFloat(),
        y = center.y + (lobeOrbit * 0.84f * sin(radAngleC)).toFloat()
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                cTertiary.copy(alpha = 0.55f),
                cTertiary.copy(alpha = 0.24f),
                Color.Transparent
            ),
            center = nodeCOffset,
            radius = radius * 0.78f
        ),
        radius = radius * 0.78f,
        center = nodeCOffset
    )

    val coreGlowRadius = if (voiceState == VoiceState.THINKING) {
        radius * 0.56f
    } else {
        radius * (0.40f + smoothAudio * 0.14f)
    }
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                cAccent.copy(alpha = 0.44f),
                cPrimary.copy(alpha = 0.22f),
                Color.Transparent
            ),
            center = center,
            radius = coreGlowRadius
        ),
        radius = coreGlowRadius,
        center = center
    )
}

private fun DrawScope.drawEmotionalFace(
    center: Offset,
    radius: Float,
    emotion: AssistantEmotion,
    voiceState: VoiceState,
    blinkOpenness: Float,
    smileFactor: Float,
    browLift: Float,
    headTilt: Float,
    wavePhase: Float,
    smoothAudio: Float,
    cAccent: Color,
    pathCache: OrbPathCache
) {
    rotate(degrees = headTilt, pivot = center) {
        val eyeOffsetX = radius * 0.30f
        val eyeCenterY = center.y - (radius * 0.10f)
        val eyeRadiusX = radius * 0.11f
        val baseEyeHeight = when (emotion) {
            AssistantEmotion.SURPRISE -> radius * 0.16f
            AssistantEmotion.EXCITEMENT -> radius * 0.145f
            AssistantEmotion.SERIOUSNESS -> radius * 0.085f
            AssistantEmotion.SADNESS -> radius * 0.10f
            else -> radius * 0.125f
        }
        val eyeRadiusY = baseEyeHeight * blinkOpenness

        val faceColor = Color.White.copy(alpha = 0.96f)
        val glowColor = cAccent.copy(alpha = 0.45f)

        val glanceX = when {
            voiceState == VoiceState.THINKING -> radius * 0.045f * cos(wavePhase)
            emotion == AssistantEmotion.CURIOSITY -> radius * 0.035f
            else -> 0f
        }
        val glanceY = when {
            voiceState == VoiceState.THINKING -> -radius * 0.03f
            emotion == AssistantEmotion.SADNESS -> radius * 0.02f
            else -> 0f
        }

        // 1. Expressive Eyebrows
        val browY = eyeCenterY - baseEyeHeight - (radius * 0.09f) - (browLift * radius * 0.05f)
        val leftBrowTilt = when (emotion) {
            AssistantEmotion.SADNESS -> -radius * 0.05f
            AssistantEmotion.SERIOUSNESS -> radius * 0.035f
            AssistantEmotion.CURIOSITY -> -radius * 0.04f
            else -> 0f
        }
        val rightBrowTilt = when (emotion) {
            AssistantEmotion.SADNESS -> radius * 0.05f
            AssistantEmotion.SERIOUSNESS -> -radius * 0.035f
            else -> 0f
        }
        val leftExtraRaise = if (emotion == AssistantEmotion.CURIOSITY) -radius * 0.05f else 0f

        val browStroke = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round)
        pathCache.leftBrowPath.rewind()
        val lx = center.x - eyeOffsetX
        pathCache.leftBrowPath.moveTo(lx - eyeRadiusX * 1.15f, browY + leftExtraRaise - leftBrowTilt)
        pathCache.leftBrowPath.quadraticTo(
            lx,
            browY + leftExtraRaise - (browLift * radius * 0.04f),
            lx + eyeRadiusX * 1.15f,
            browY + leftExtraRaise + leftBrowTilt
        )
        drawPath(pathCache.leftBrowPath, color = faceColor.copy(alpha = 0.85f), style = browStroke)

        pathCache.rightBrowPath.rewind()
        val rx = center.x + eyeOffsetX
        pathCache.rightBrowPath.moveTo(rx - eyeRadiusX * 1.15f, browY - rightBrowTilt)
        pathCache.rightBrowPath.quadraticTo(
            rx,
            browY - (browLift * radius * 0.04f),
            rx + eyeRadiusX * 1.15f,
            browY + rightBrowTilt
        )
        drawPath(pathCache.rightBrowPath, color = faceColor.copy(alpha = 0.85f), style = browStroke)

        // 2. Expressive Eyes
        val leftEyeCenter = Offset(center.x - eyeOffsetX + glanceX, eyeCenterY + glanceY)
        val rightEyeCenter = Offset(center.x + eyeOffsetX + glanceX, eyeCenterY + glanceY)

        if (emotion == AssistantEmotion.HAPPINESS && blinkOpenness > 0.4f) {
            val happyStroke = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round)
            val happyGlowStroke = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
            for (idx in 0..1) {
                val ec = if (idx == 0) leftEyeCenter else rightEyeCenter
                pathCache.eyeArcPath.rewind()
                pathCache.eyeArcPath.moveTo(ec.x - eyeRadiusX * 1.15f, ec.y + eyeRadiusY * 0.25f)
                pathCache.eyeArcPath.quadraticTo(
                    ec.x,
                    ec.y - eyeRadiusY * 1.15f,
                    ec.x + eyeRadiusX * 1.15f,
                    ec.y + eyeRadiusY * 0.25f
                )
                drawPath(pathCache.eyeArcPath, color = glowColor, style = happyGlowStroke)
                drawPath(pathCache.eyeArcPath, color = faceColor, style = happyStroke)
            }
        } else {
            for (idx in 0..1) {
                val ec = if (idx == 0) leftEyeCenter else rightEyeCenter
                drawOval(
                    color = glowColor,
                    topLeft = Offset(ec.x - eyeRadiusX * 1.35f, ec.y - eyeRadiusY * 1.35f),
                    size = Size(eyeRadiusX * 2.7f, (eyeRadiusY * 2.7f).coerceAtLeast(3f))
                )
                drawOval(
                    color = faceColor,
                    topLeft = Offset(ec.x - eyeRadiusX, ec.y - eyeRadiusY),
                    size = Size(eyeRadiusX * 2f, (eyeRadiusY * 2f).coerceAtLeast(2.5f))
                )
                if (blinkOpenness > 0.5f) {
                    val glintR = if (emotion == AssistantEmotion.EXCITEMENT || emotion == AssistantEmotion.SURPRISE) {
                        eyeRadiusX * 0.42f
                    } else {
                        eyeRadiusX * 0.30f
                    }
                    drawCircle(
                        color = SiriGoldWhite,
                        radius = glintR,
                        center = Offset(ec.x - eyeRadiusX * 0.3f, ec.y - eyeRadiusY * 0.32f)
                    )
                }
            }
        }

        // 3. Expressive Mouth synchronized to TTS speech energy
        val mouthCenterY = center.y + (radius * 0.24f)
        val mouthHalfWidth = when (emotion) {
            AssistantEmotion.EXCITEMENT -> radius * 0.26f
            AssistantEmotion.HAPPINESS -> radius * 0.23f
            AssistantEmotion.SURPRISE -> radius * 0.11f
            AssistantEmotion.CURIOSITY -> radius * 0.14f
            AssistantEmotion.SADNESS -> radius * 0.17f
            AssistantEmotion.SERIOUSNESS -> radius * 0.18f
            AssistantEmotion.NEUTRAL -> radius * 0.17f
        }

        val speakingOpenness = if (voiceState == VoiceState.SPEAKING) {
            (0.14f + (smoothAudio * 0.86f) + 0.14f * abs(sin(wavePhase * 2f))).coerceIn(0.12f, 1.0f)
        } else {
            0f
        }

        val mouthStroke = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

        when {
            emotion == AssistantEmotion.SURPRISE && voiceState != VoiceState.SPEAKING -> {
                drawOval(
                    color = glowColor,
                    topLeft = Offset(center.x - mouthHalfWidth * 1.15f, mouthCenterY - mouthHalfWidth * 1.25f),
                    size = Size(mouthHalfWidth * 2.3f, mouthHalfWidth * 2.5f)
                )
                drawOval(
                    color = faceColor,
                    topLeft = Offset(center.x - mouthHalfWidth, mouthCenterY - mouthHalfWidth * 1.1f),
                    size = Size(mouthHalfWidth * 2f, mouthHalfWidth * 2.2f),
                    style = mouthStroke
                )
            }
            voiceState == VoiceState.SPEAKING -> {
                val openHeight = radius * 0.15f * speakingOpenness
                val curveOffset = smileFactor * radius * 0.08f
                pathCache.mouthPath.rewind()
                pathCache.mouthPath.moveTo(center.x - mouthHalfWidth, mouthCenterY)
                pathCache.mouthPath.quadraticTo(
                    center.x,
                    mouthCenterY + curveOffset + openHeight,
                    center.x + mouthHalfWidth,
                    mouthCenterY
                )
                pathCache.mouthPath.quadraticTo(
                    center.x,
                    mouthCenterY + curveOffset - openHeight * 0.55f,
                    center.x - mouthHalfWidth,
                    mouthCenterY
                )
                pathCache.mouthPath.close()
                drawPath(pathCache.mouthPath, color = faceColor.copy(alpha = 0.85f))
                drawPath(pathCache.mouthPath, color = faceColor, style = mouthStroke)
            }
            emotion == AssistantEmotion.EXCITEMENT -> {
                val grinDepth = radius * 0.18f
                pathCache.grinPath.rewind()
                pathCache.grinPath.moveTo(center.x - mouthHalfWidth, mouthCenterY - radius * 0.03f)
                pathCache.grinPath.quadraticTo(
                    center.x,
                    mouthCenterY + grinDepth,
                    center.x + mouthHalfWidth,
                    mouthCenterY - radius * 0.03f
                )
                pathCache.grinPath.quadraticTo(
                    center.x,
                    mouthCenterY + radius * 0.02f,
                    center.x - mouthHalfWidth,
                    mouthCenterY - radius * 0.03f
                )
                pathCache.grinPath.close()
                drawPath(pathCache.grinPath, color = faceColor.copy(alpha = 0.90f))
            }
            else -> {
                val curveY = mouthCenterY + (smileFactor * radius * 0.13f)
                val endYOffset = -smileFactor * radius * 0.03f
                pathCache.mouthPath.rewind()
                pathCache.mouthPath.moveTo(center.x - mouthHalfWidth, mouthCenterY + endYOffset)
                pathCache.mouthPath.quadraticTo(
                    center.x + (if (emotion == AssistantEmotion.CURIOSITY) radius * 0.04f else 0f),
                    curveY,
                    center.x + mouthHalfWidth,
                    mouthCenterY + endYOffset
                )
                drawPath(pathCache.mouthPath, color = glowColor, style = Stroke(width = 5.5.dp.toPx(), cap = StrokeCap.Round))
                drawPath(pathCache.mouthPath, color = faceColor, style = mouthStroke)
            }
        }
    }
}

private fun DrawScope.drawGlassReflections(
    center: Offset,
    radius: Float,
    cAccent: Color
) {
    val highlightCenter = Offset(
        center.x - (radius * 0.35f),
        center.y - (radius * 0.38f)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.56f),
                Color.White.copy(alpha = 0.18f),
                Color.Transparent
            ),
            center = highlightCenter,
            radius = radius * 0.40f
        ),
        radius = radius * 0.40f,
        center = highlightCenter
    )

    val bounceCenter = Offset(
        center.x + (radius * 0.38f),
        center.y + (radius * 0.40f)
    )
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                cAccent.copy(alpha = 0.34f),
                Color.Transparent
            ),
            center = bounceCenter,
            radius = radius * 0.36f
        ),
        radius = radius * 0.36f,
        center = bounceCenter
    )
}
