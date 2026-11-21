package com.aurex.sphere

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Aurex sphere, Speaking mode only. Single file, no other dependencies
 * (compose ui + foundation only; does NOT need OrbEngine.kt).
 *
 * 2000 emerald dust points on a shell, turning slowly on two axes. The level
 * swells the shell (each dot by its own amount) and washes it from emerald
 * toward white. The level RISES AND FALLS WITH THE SPEECH: a beat per syllable,
 * a dip between words, longer rests at commas, full stops and new lines.
 *
 * Two ways to drive it:
 *
 *   1) From the response text (no audio access needed). The pulses follow the
 *      words, so a longer reply keeps moving for longer:
 *
 *        AurexSpeakingSphere(
 *            modifier = Modifier.size(300.dp),
 *            speaking = isSpeaking,
 *            text = replyText,
 *            wordsPerMinute = 165          // match your TTS speed
 *        )
 *
 *   2) From a real audio level (0f..~1f, per-frame, e.g. avg spectrum / 128):
 *
 *        AurexSpeakingSphere(speaking = isSpeaking, volume = { ttsLevel })
 *
 * speaking = false is the plain resting sphere (also what Idle / Listening show).
 * With speaking = true and neither text nor volume, it talks generically.
 */
@Composable
fun AurexSpeakingSphere(
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF33DB12),
    reducedMotion: Boolean = rememberSpeakingReducedMotion(),
    contentDescription: String? = null,
    /** True while the assistant is talking; false = resting sphere. */
    speaking: Boolean = true,
    /** The reply being spoken. Pulses are timed from its words and punctuation. */
    text: String? = null,
    /** Speech rate used to time [text]; set it near your TTS rate. */
    wordsPerMinute: Int = 165,
    /** Optional real audio level. When given, it drives the sphere instead of [text]. */
    volume: (() -> Float)? = null,
    /**
     * Optional speech clock in seconds through the reply (e.g. the TTS
     * playback position mapped onto the sentence timeline). When given, the
     * envelope follows IT — the beats land exactly where the voice is.
     */
    clock: (() -> Double)? = null
) {
    val engine = remember { SpeakingEngine().also { it.setSpeech(text, wordsPerMinute) } }
    engine.density = LocalDensity.current.density
    var tick by remember { mutableIntStateOf(0) }

    val curColor by rememberUpdatedState(color)
    val curReduced by rememberUpdatedState(reducedMotion)
    val curSpeaking by rememberUpdatedState(speaking)
    val curVolume by rememberUpdatedState(volume)
    val curClock by rememberUpdatedState(clock)

    LaunchedEffect(engine, text, wordsPerMinute) { engine.setSpeech(text, wordsPerMinute) }

    LaunchedEffect(engine) {
        val tint = IntArray(3)
        while (true) {
            // frames stop on their own while the app is in the background
            withFrameNanos { now ->
                val c = curColor
                tint[0] = (c.red * 255f).roundToInt()
                tint[1] = (c.green * 255f).roundToInt()
                tint[2] = (c.blue * 255f).roundToInt()
                if (engine.step(now, tint, curSpeaking, curVolume?.invoke(), curReduced, curClock?.invoke())) tick++
            }
        }
    }

    Canvas(
        modifier = modifier
            .onSizeChanged { engine.setSize(it.width, it.height) }
            // additive dots composite on a transparent layer, then over your background
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .then(
                if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription }
                else Modifier
            )
    ) {
        @Suppress("UNUSED_EXPRESSION")
        tick // read to invalidate every frame
        engine.draw(this)
    }
}

@Composable
private fun rememberSpeakingReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

// ---------------------------------------------------------------------------
// Speech envelope: turns words into a level that rises and falls like talking.

private class Tok(val syll: Int, val pause: Double, val key: Int)

private fun hash01(a: Int, b: Int): Double {
    val h = sin((a % 9973) * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}

/** Words -> syllable counts + the rest that follows each (comma / full stop / new line). */
private fun tokensOf(text: String): List<Tok> {
    val out = ArrayList<Tok>()
    val vowels = "aeiouyAEIOUY"
    for (raw in text.replace("\r", "").replace("\n", " ¶ ").split(' ', '\t')) {
        if (raw.isBlank()) continue
        if (raw == "¶") { out.add(Tok(0, 0.4, 0)); continue }
        val pause = when (raw.last()) {
            '.', '!', '?', '…', '।', '。' -> 0.36
            ',', ';', ':', '—' -> 0.16
            else -> 0.0
        }
        val letters = raw.count { it.isLetterOrDigit() }
        if (letters == 0) { if (pause > 0.0) out.add(Tok(0, pause, 0)); continue }
        var groups = 0
        var prevVowel = false
        for (ch in raw) {
            val v = ch in vowels
            if (v && !prevVowel) groups++
            prevVowel = v
        }
        val syll = if (groups == 0) max(1, letters / 3) else groups
        out.add(Tok(min(syll, 8), pause, raw.hashCode()))
    }
    return out
}

/** Stand-in speech for when there is no text: a steady talker with a breath every few words. */
private fun proceduralTokens(): List<Tok> {
    val r = Random(11)
    val out = ArrayList<Tok>()
    var untilPause = 6
    repeat(160) {
        val s = when (r.nextInt(10)) { in 0..3 -> 1; in 4..7 -> 2; 8 -> 3; else -> 4 }
        untilPause--
        val pause = if (untilPause <= 0) { untilPause = 5 + r.nextInt(8); 0.2 + r.nextDouble() * 0.3 } else 0.0
        out.add(Tok(s, pause, r.nextInt()))
    }
    return out
}

/** 100 samples per second of level 0..1: a bump per syllable, a dip between words. */
private fun buildEnvelope(tokens: List<Tok>, wpm: Int): FloatArray {
    val syl = 40.0 / wpm.coerceIn(60, 300)   // ~1.5 syllables per word
    val gap = 0.035
    val segs = ArrayList<DoubleArray>()
    var t = 0.0
    for ((wi, tk) in tokens.withIndex()) {
        if (tk.syll > 0) {
            val wordAmp = 0.55 + 0.45 * hash01(tk.key, wi)
            for (s in 0 until tk.syll) {
                val d = syl * (0.85 + 0.3 * hash01(tk.key, wi * 7 + s + 1))
                segs.add(doubleArrayOf(t, t + d, wordAmp * (if (s == 0) 1.0 else 0.8)))
                t += d
            }
            t += gap
        }
        t += tk.pause
    }
    val env = FloatArray(((t + 0.2) * 100).toInt() + 2)
    for (sg in segs) {
        val i0 = (sg[0] * 100).toInt()
        val i1 = min(env.size - 1, (sg[1] * 100).toInt())
        for (i in i0..i1) {
            val x = ((i * 0.01 - sg[0]) / (sg[1] - sg[0])).coerceIn(0.0, 1.0)
            val v = (sg[2] * (0.25 + 0.75 * sin(PI * x))).toFloat()
            if (v > env[i]) env[i] = v
        }
    }
    return env
}

/** Estimated spoken length of a text at the given rate — used to place sentence chunks on the reply timeline. */
fun estimateSpeechSeconds(text: String, wpm: Int = 165): Double {
    val e = buildEnvelope(tokensOf(text), wpm)
    return e.size / 100.0
}

// ---------------------------------------------------------------------------

private const val DUST_N = 2000
private const val R_SHELL = 0.3233
private const val CAM_Z = 4.5

private class SpeakingEngine {
    // fixed per-point data: direction on the shell and each dot's own swell amount
    private val dir = FloatArray(DUST_N * 3)
    private val spread = FloatArray(DUST_N)

    init {
        val r = Random.Default
        for (i in 0 until DUST_N) {
            var x: Double; var y: Double; var z: Double; var l: Double
            do {
                x = r.nextDouble() * 2 - 1; y = r.nextDouble() * 2 - 1; z = r.nextDouble() * 2 - 1
                l = sqrt(x * x + y * y + z * z)
            } while (l < 1e-4)
            dir[i * 3] = (x / l).toFloat(); dir[i * 3 + 1] = (y / l).toFloat(); dir[i * 3 + 2] = (z / l).toFloat()
            spread[i] = r.nextFloat()
        }
    }

    var density = 1f
    private var widthPx = 0
    private var heightPx = 0
    private var side = 0.0

    private var spin = 0.0
    private var vol = 0.0
    private var last = 0L
    private var frozenKey = ""

    // speech timeline
    private var env = FloatArray(0)
    private var loop = false
    private var speechText: String? = null
    private var speechWpm = 0
    private var speechStart = 0L      // 0 = restart the clock on the next frame
    private var wasSpeaking = false

    // per-frame draw buffers (dp space; scaled by density at draw time)
    private val px = FloatArray(DUST_N)
    private val py = FloatArray(DUST_N)
    private val pz = FloatArray(DUST_N)
    private val pr = FloatArray(DUST_N)
    private val pa = FloatArray(DUST_N)
    private val pcr = IntArray(DUST_N)
    private val pcg = IntArray(DUST_N)
    private val pcb = IntArray(DUST_N)
    private val keys = LongArray(DUST_N)
    private var nDraw = 0

    fun setSize(w: Int, h: Int) {
        widthPx = w
        heightPx = h
    }

    /** New reply = new timeline. Same reply growing (streaming) keeps its place. */
    fun setSpeech(text: String?, wpm: Int) {
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        if (env.isNotEmpty() && t == speechText && wpm == speechWpm) return
        val old = speechText
        val continuing = env.isNotEmpty() && t != null && old != null && t.startsWith(old) && wpm == speechWpm
        speechText = t
        speechWpm = wpm
        loop = t == null
        env = buildEnvelope(if (t == null) proceduralTokens() else tokensOf(t), wpm)
        if (!continuing) speechStart = 0L
    }

    private fun envAt(sec: Double): Double {
        if (env.isEmpty() || sec < 0.0) return 0.0
        val i = (sec * 100).toInt()
        return when {
            i < env.size -> env[i].toDouble()
            loop -> env[i % env.size].toDouble()
            else -> 0.0
        }
    }

    /** Advance the animation. Returns true when there is a new frame to draw. */
    fun step(nowNanos: Long, tint: IntArray, speaking: Boolean, live: Float?, frozen: Boolean, clockSec: Double? = null): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false
        side = min(widthPx, heightPx) / density.toDouble()

        if (speaking && !wasSpeaking) speechStart = 0L
        wasSpeaking = speaking
        if (speaking && speechStart == 0L) speechStart = nowNanos

        val level = when {
            !speaking -> 0.0
            live != null -> live.toDouble().coerceAtLeast(0.0)
            frozen -> 0.5                                   // static "speaking" look
            // the TTS playback position through the reply: beats land with the voice
            clockSec != null -> envAt(clockSec)
            else -> envAt((nowNanos - speechStart) / 1e9)   // follows the words
        }

        if (frozen) {
            // reduced motion: one static instant, repainted only when something changes
            val key = "$side|${tint[0]},${tint[1]},${tint[2]}|${(level * 20).roundToInt()}"
            if (key == frozenKey) return false
            frozenKey = key
            spin = 0.0
            vol = level
            compose(tint)
            return true
        }
        frozenKey = ""

        val dt = if (last != 0L) min(0.1, (nowNanos - last) / 1e9) else 0.0
        last = nowNanos
        spin += dt

        // quick attack on each syllable, quick release so it dips between words
        val tau = if (level > vol) 0.035 else 0.09
        vol += (level - vol) * (1 - exp(-dt / tau))

        compose(tint)
        return true
    }

    private fun compose(tint: IntArray) {
        val c = side / 2
        val R = R_SHELL * side
        val half = side / 2
        val ang = spin * 0.05
        val sy = sin(ang); val cyw = cos(ang); val sz = sin(ang); val cz = cos(ang)

        // emerald -> white, driven by voice
        val dr = (tint[0] + (255 - tint[0]) * vol).roundToInt().coerceIn(0, 255)
        val dg = (tint[1] + (255 - tint[1]) * vol).roundToInt().coerceIn(0, 255)
        val db = (tint[2] + (255 - tint[2]) * vol).roundToInt().coerceIn(0, 255)

        var n = 0
        for (i in 0 until DUST_N) {
            val ax = dir[i * 3]; val ay = dir[i * 3 + 1]; val az = dir[i * 3 + 2]
            // Rz(φ) · Ry(θ) · p — the original's oblique double spin
            val x1 = ax * cyw + az * sy
            val z1 = -ax * sy + az * cyw
            val y1 = ay * cz - z1 * sz
            val z2 = ay * sz + z1 * cz
            val xp = 1 + vol * spread[i] * 0.4          // each dot swells by its own amount
            // perspective: the original drew through a camera at 4.5
            val dd = max(0.1, CAM_Z - z2 * xp)
            val x = c + x1 * R * xp
            val y = c - y1 * R * xp
            val r = (0.012 * half) / dd
            val al = 0.9
            if (al < 0.02 || r < 0.3) continue
            px[i] = x.toFloat(); py[i] = y.toFloat(); pz[i] = z2.toFloat(); pr[i] = r.toFloat(); pa[i] = al.toFloat()
            pcr[i] = dr; pcg[i] = dg; pcb[i] = db
            // far -> near order: float bits mapped to a sortable int, index in the low word
            val bits = pz[i].toRawBits()
            val sortable = bits xor ((bits shr 31) and 0x7FFFFFFF)
            keys[n++] = (sortable.toLong() shl 32) or i.toLong()
        }
        java.util.Arrays.sort(keys, 0, n)
        nDraw = n
    }

    fun draw(scope: DrawScope) = with(scope) {
        if (side <= 0.0) return@with
        val d = density
        val offX = (size.width - side.toFloat() * d) / 2f
        val offY = (size.height - side.toFloat() * d) / 2f
        // additive ink, like canvas 'lighter'
        for (k in 0 until nDraw) {
            val i = (keys[k] and 0xFFFFFFFFL).toInt()
            val color = Color(pcr[i] / 255f, pcg[i] / 255f, pcb[i] / 255f, pa[i].coerceIn(0f, 1f))
            val r = pr[i]
            val cx = offX + px[i] * d
            val cy = offY + py[i] * d
            // cheap square marks for the finest grain, real circles where visible
            if (r < 1.1f) {
                drawRect(color, Offset(cx - r * d, cy - r * d), Size(r * 2 * d, r * 2 * d), blendMode = BlendMode.Plus)
            } else {
                drawCircle(color, r * d, Offset(cx, cy), blendMode = BlendMode.Plus)
            }
        }
    }
}
