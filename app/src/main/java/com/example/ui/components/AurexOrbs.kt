package com.example.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

enum class OrbState { LISTENING, THINKING, SEARCHING, SOLVING, GENERATING, SPEAKING, CONNECTING, IDLE }

private val OrbIdle = Color(0xFFCCCCCC)
private val OrbConnecting = Color(0xFFCCCCCC)
private val OrbListening = Color(0xFF999999)
private val OrbThinking = Color(0xFFD8D8D8)
private val OrbSpeaking = Color(0xFFE8E8E8)
private val OrbError = Color(0xFFF87171)
private val TAU = (2f * Math.PI).toFloat()

private fun followRate(durationMs: Float, elapsedMs: Float): Float {
    if (durationMs <= 0f) return 1f
    return (1.0 - Math.pow(0.1, (elapsedMs / durationMs).toDouble())).toFloat()
}

@Composable
fun ThinkingOrb(
    state: OrbState,
    level: Float = 0f,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var glow by remember { mutableFloatStateOf(0f) }
    var colorState by remember { mutableStateOf(OrbIdle) }
    var currentBase by remember { mutableFloatStateOf(0.82f) }
    var currentRange by remember { mutableFloatStateOf(0.18f) }
    var startedAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var lastNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                val dtMs = if (lastNanos == 0L) 16f else ((now - lastNanos) / 1_000_000f).coerceIn(0f, 100f)
                lastNanos = now
                if (startedAt == 0L) startedAt = now
                val elapsed = (now - startedAt) / 1_000_000f

                val active = state == OrbState.LISTENING || state == OrbState.SPEAKING
                val vol = level.coerceIn(0f, 1f)

                if (active) {
                    val (base, maxScale) = when (state) {
                        OrbState.SPEAKING -> 0.95f to 1.03f
                        else -> 0.82f to 1.0f
                    }
                    val range = maxScale - base
                    val tRate = followRate(620f, dtMs)
                    currentBase += (base - currentBase) * tRate
                    currentRange += (maxScale - base - currentRange) * tRate
                    val tScale = currentBase + level.coerceIn(0f, 1f) * currentRange
                    val tGlow = if (state == OrbState.SPEAKING) level.coerceIn(0f, 1f) * 24f else 0f
                    val activityRate = followRate(if (tScale > scale) 50f else 110f, dtMs)
                    scale += (tScale - scale) * activityRate
                    glow += (tGlow - glow) * activityRate
                } else {
                    val tRate = followRate(620f, dtMs)
                    currentBase += (1f - currentBase) * tRate
                    currentRange += (0f - currentRange) * tRate
                    scale += (1f - scale) * tRate
                    glow += (0f - glow) * tRate
                }

                val targetColor = when (state) {
                    OrbState.LISTENING -> Color(0xFF999999)
                    OrbState.SPEAKING -> Color(0xFFE8E8E8)
                    OrbState.IDLE, OrbState.CONNECTING -> Color(0xFFCCCCCC)
                    else -> Color(0xFFD8D8D8)
                }
                val colorRate = followRate(620f, dtMs)
                colorState = lerp(colorState, targetColor, colorRate.coerceIn(0f, 1f))
                if (abs(colorState.alpha - targetColor.alpha) < 0.01f) colorState = Color(
                    colorState.red, colorState.green, colorState.blue, targetColor.alpha
                )
            }
        }
    }

    val active = state == OrbState.LISTENING || state == OrbState.SPEAKING

    Canvas(modifier = modifier) {
        val d = size.minDimension * 0.55f
        val r = d / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        var pulseScale = 0f
        var pulseAlphaDrop = 0f
        if (!active) {
            val settled = (1f - (abs(scale - 1f) / 0.08f).coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val elapsed = (System.currentTimeMillis() % 3000L) / 1000f
            if (state == OrbState.IDLE) {
                val ph = (elapsed % 3f) / 3f
                pulseScale = 0.06f * (0.5f - 0.5f * cos(TAU * ph)) * (1f - abs(scale - 1f) / 0.08f).coerceIn(0f, 1f)
            } else if (state != OrbState.IDLE) {
                val ph = (elapsed % 1.5f) / 1.5f
                val w = 0.5f - 0.5f * cos(TAU * ph)
                pulseScale = -0.05f * w * settled
                pulseAlphaDrop = 0.4f * w * settled
            }
        }

        val drawScale = (scale + pulseScale).coerceAtLeast(0.05f)

        if (glow > 0.5f) {
            val gNorm = (glow / 24f).coerceIn(0f, 1f)
            val glowRadius = r * drawScale * (1.35f + 0.55f * gNorm)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(
                        colorState.copy(alpha = 0.36f * gNorm),
                        colorState.copy(alpha = 0.10f * gNorm),
                        Color.Transparent
                    ),
                    center,
                    glowRadius
                ),
                center = center,
                radius = glowRadius
            )
        }

        drawCircle(
            color = colorState.copy(alpha = (colorState.alpha * (1f - pulseAlphaDrop)).coerceIn(0f, 1f)),
            radius = r * drawScale,
            center = center
        )
    }
}
// ─────────────────────────────────────────────────────────────────────────────
