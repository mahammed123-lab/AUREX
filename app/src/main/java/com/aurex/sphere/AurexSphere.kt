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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Aurex hero sphere.
 *
 * ONE pool of 2000 emerald dust points on a radius-2 shell, turning slowly on
 * two axes.
 *
 *  - [SphereState.Idle] and [SphereState.Listening] are the SAME plain sphere.
 *  - [SphereState.Speaking] is that sphere, and only here does [volume] drive it:
 *    the shell swells (each dot by its own amount) and washes from emerald toward
 *    white as the voice gets louder, with a fast attack and a slow release.
 *  - Every other state (Working, Searching, ...) sends the SAME points to the dots
 *    of that thinking-orb shape, and back onto the shell when the work ends.
 *    There is no second layer.
 *
 * Every shape uses exactly the sphere's dots: same count, same perspective dot
 * size, same loose scatter, same additive ink, and is fitted to the shell's own
 * radius. Working / Searching get 1.7x dots, Weaving / Breathing 1.4x; Connecting
 * gathers its dots into tight clumps at the joints. Switching shapes goes through
 * the sphere: dots return to the shell, then leave it again as the new shape.
 *
 * Dependencies: compose ui + foundation only.
 *
 *   AurexSphere(
 *       state = SphereState.Speaking,   // Idle, Listening, Speaking, Working, Searching, ...
 *       modifier = Modifier.size(300.dp),
 *       volume = { ttsLevel }            // 0f..~1f, avg spectrum / 128 like the web analyser
 *   )
 */
enum class SphereState {
    /** The plain sphere at rest. */
    Idle,
    /** Identical to [Idle]: the same sphere. */
    Listening,
    /** The same sphere, swelling and washing toward white with [AurexSphere]'s `volume`. */
    Speaking,
    Working, Searching, Solving, Connecting, Weaving, Composing, Breathing, Shaping,
    /** The engine's wave-lattice orb (what the thinking-orbs library calls "listening"). */
    Wave
}

private fun SphereState.toMode(): Mode? = when (this) {
    SphereState.Idle, SphereState.Listening, SphereState.Speaking -> null
    SphereState.Wave -> Mode.WAVE
    SphereState.Working -> Mode.ORBITS
    SphereState.Searching -> Mode.GLOBE
    SphereState.Solving -> Mode.RUBIK
    SphereState.Connecting -> Mode.WEB
    SphereState.Weaving -> Mode.BRAID
    SphereState.Composing -> Mode.RIBBON
    SphereState.Breathing -> Mode.RING
    SphereState.Shaping -> Mode.MORPH
}

/** Idle, Listening and Speaking are one shape; everything else is its own. */
private fun SphereState.shapeKey(): SphereState = if (toMode() == null) SphereState.Idle else this

@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember {
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

@Composable
fun AurexSphere(
    state: SphereState = SphereState.Idle,
    modifier: Modifier = Modifier,
    color: Color = Color(0xFF33DB12),
    /** How loosely the dots sit around their shape, 0..1. */
    scatter: Float = 0.5f,
    /** Engine ink convention: near dots bright on a dark substrate. */
    dark: Boolean = true,
    reducedMotion: Boolean = rememberReducedMotion(),
    contentDescription: String? = null,
    /** Normalised output level, ~0..1 (average spectrum byte / 128). Only read while Speaking. */
    volume: () -> Float = { 0f }
) {
    val engine = remember { SphereEngine() }
    engine.density = LocalDensity.current.density
    var tick by remember { mutableIntStateOf(0) }

    val curState by rememberUpdatedState(state)
    val curColor by rememberUpdatedState(color)
    val curScatter by rememberUpdatedState(scatter)
    val curDark by rememberUpdatedState(dark)
    val curReduced by rememberUpdatedState(reducedMotion)
    val curVolume by rememberUpdatedState(volume)

    LaunchedEffect(engine) {
        val tint = IntArray(3)
        while (true) {
            // frames stop on their own while the app is in the background
            withFrameNanos { now ->
                val c = curColor
                tint[0] = (c.red * 255f).roundToInt()
                tint[1] = (c.green * 255f).roundToInt()
                tint[2] = (c.blue * 255f).roundToInt()
                if (engine.step(now, curState, tint, curDark, curScatter.toDouble(), curVolume(), curReduced)) tick++
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

// ---------------------------------------------------------------------------

private const val DESIGN = 300.0
private const val COUNT_POW = 1.6
private const val HERO_SIZE = 1.25
private const val DUST_N = 2000
private const val R_SHELL = 0.3233
private const val CAM_Z = 4.5

private fun thickOf(m: Mode): Double = when (m) {
    Mode.ORBITS, Mode.GLOBE -> 1.7
    Mode.BRAID, Mode.RING -> 1.4
    else -> 1.0
}

private class Cfg(
    val mode: Mode,
    val opts: Opts,
    /** Scale that makes the shape's edge equal the sphere's radius. */
    val fit: Double,
    /** Gather each dot's copies tightly (connecting's joints). */
    val cluster: Boolean,
    val thick: Double,
    /** Orb-dot count of the reference frame. */
    val m: Int,
    /** Orb dot each pool point travels to. */
    val tgt: IntArray,
    /** How many pool points share that orb dot. */
    val cnt: FloatArray
)

private class Mulberry(private var s: Int) {
    fun next(): Double {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

internal class SphereEngine {
    // fixed per-point data: direction on the shell, spread, scatter offset
    private val dir = FloatArray(DUST_N * 3)
    private val spread = FloatArray(DUST_N)
    private val jit = FloatArray(DUST_N * 2)

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
            val a = r.nextDouble() * 2 * PI
            val rr = sqrt(r.nextDouble())
            jit[i * 2] = (cos(a) * rr).toFloat(); jit[i * 2 + 1] = (sin(a) * rr).toFloat()
        }
    }

    var density = 1f
    private var widthPx = 0
    private var heightPx = 0
    private var side = 0.0

    private var spin = 0.0
    private var orbTime = 0.0
    private var vol = 0.0
    /** 0 = plain sphere, 1 = full orb shape. */
    private var orbAlpha = 0.0
    private var shown = SphereState.Idle
    private var cfg: Cfg? = null
    private var last = 0L
    private var frozenKey = ""

    private val ref = Frame()
    private val scratch = Frame()
    private val live = Frame()

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

    private var lx1 = FloatArray(512); private var ly1 = FloatArray(512)
    private var lx2 = FloatArray(512); private var ly2 = FloatArray(512)
    private var lw = FloatArray(512); private var la = FloatArray(512)
    private var lcr = IntArray(512); private var lcg = IntArray(512); private var lcb = IntArray(512)
    private var nLines = 0

    private val rgb = DoubleArray(3)

    fun setSize(w: Int, h: Int) {
        widthPx = w
        heightPx = h
    }

    private fun setShown(s: SphereState) {
        shown = s
        cfg = s.toMode()?.let { buildCfg(it) }
    }

    /** Resolve a shape for this size and pair every pool point with an orb dot. */
    private fun buildCfg(mode: Mode): Cfg {
        var m = scaleCounts(mode.base, (side / DESIGN).pow(COUNT_POW))
        m = scaleRadii(m, HERO_SIZE)
        m = m + mode.extra
        val opts = Opts(m)
        val c = side / 2
        val R = R_SHELL * side

        frameFor(mode, ref, side, orbTime, opts)
        val M = ref.n

        // fit the shape to the sphere's radius (robust edge over a dozen moments)
        var buf = DoubleArray(1 shl 14)
        var cnt = 0
        for (k in 0 until 12) {
            frameFor(mode, scratch, side, orbTime + k * 0.9, opts)
            for (i in 0 until scratch.n) {
                if (cnt == buf.size) buf = buf.copyOf(cnt * 2)
                buf[cnt++] = hypot(scratch.x[i] - c, scratch.y[i] - c)
            }
        }
        java.util.Arrays.sort(buf, 0, cnt)
        var edge = if (cnt > 0) buf[floor(cnt * 0.99).toInt()] else 1.0
        if (edge == 0.0) edge = 1.0
        val fit = (R / edge).coerceIn(0.4, 1.5)

        // every shape gets exactly the sphere's dots: a random 2000 of the orb's
        // dots, or repeats of them when it has fewer
        val rnd = Mulberry(7)
        val order = IntArray(M) { it }
        for (j in M - 1 downTo 1) {
            val k = floor(rnd.next() * (j + 1)).toInt()
            val tmp = order[j]; order[j] = order[k]; order[k] = tmp
        }
        val copies = FloatArray(M)
        val sel = IntArray(DUST_N)
        for (k in 0 until DUST_N) {
            val j = order[k % M]
            sel[k] = j
            copies[j]++
        }

        // pair by angle around the centre so points glide the short way
        val ang = spin * 0.05
        val sy = sin(ang); val cyw = cos(ang); val sz = sin(ang); val cz = cos(ang)
        val pk = DoubleArray(DUST_N)
        val ok = DoubleArray(M)
        for (i in 0 until DUST_N) {
            val ax = dir[i * 3].toDouble(); val ay = dir[i * 3 + 1].toDouble(); val az = dir[i * 3 + 2].toDouble()
            val x1 = ax * cyw + az * sy
            val z1 = -ax * sy + az * cyw
            val y1 = ay * cz - z1 * sz
            pk[i] = atan2(y1, x1) + hypot(x1, y1) * 1e-3
        }
        for (j in 0 until M) {
            val dx = ref.x[j] - c
            val dy = ref.y[j] - c
            ok[j] = atan2(-dy, dx) + hypot(dx, dy) / side * 1e-3
        }
        val pi = (0 until DUST_N).sortedBy { pk[it] }
        val selSorted = sel.toList().sortedBy { ok[it] }
        val tgt = IntArray(DUST_N)
        val ccnt = FloatArray(DUST_N)
        for (k in 0 until DUST_N) {
            tgt[pi[k]] = selSorted[k]
            ccnt[pi[k]] = copies[selSorted[k]]
        }
        return Cfg(mode, opts, fit, mode == Mode.WEB, thickOf(mode), M, tgt, ccnt)
    }

    /** Advance the animation. Returns true when there is a new frame to draw. */
    fun step(
        nowNanos: Long,
        want: SphereState,
        tint: IntArray,
        dark: Boolean,
        scatter: Double,
        volume: Float,
        frozen: Boolean
    ): Boolean {
        if (widthPx <= 0 || heightPx <= 0) return false
        val newSide = min(widthPx, heightPx) / density.toDouble()
        if (newSide != side) {
            side = newSide
            // size changed: rebuild the current shape for the new size
            cfg = shown.toMode()?.let { buildCfg(it) }
        }

        // Idle / Listening / Speaking are one shape; only Speaking hears the voice
        val wantShape = want.shapeKey()
        val level = if (want == SphereState.Speaking) volume.toDouble().coerceAtLeast(0.0) else 0.0

        if (frozen) {
            // reduced motion: one static instant, repainted only when something changes
            val key = "$wantShape|$side|${tint[0]},${tint[1]},${tint[2]}|$dark|$scatter|${(level * 20).roundToInt()}"
            if (key == frozenKey) return false
            frozenKey = key
            spin = 0.0
            orbTime = 0.6
            if (shown != wantShape) setShown(wantShape)
            orbAlpha = if (cfg != null) 1.0 else 0.0
            vol = level
            compose(tint, dark, scatter)
            return true
        }
        frozenKey = ""

        val dt = if (last != 0L) min(0.1, (nowNanos - last) / 1e9) else 0.0
        last = nowNanos
        spin += dt
        cfg?.let { orbTime += dt * it.mode.speed }

        // volume: fast attack, slow release — a swell
        vol += (level - vol) * (if (level > vol) 0.35 else 0.06)

        // changing shape = the dots return to the sphere first, then leave it
        // again as the new shape; the sphere itself is never torn down
        var goal = orbAlpha
        if (wantShape != shown) {
            if (orbAlpha > 0.02) goal = 0.0 else setShown(wantShape)
        }
        if (wantShape == shown) goal = if (cfg != null) 1.0 else 0.0
        orbAlpha += (goal - orbAlpha) * min(1.0, dt * 3.6)
        if (orbAlpha < 0.002) orbAlpha = 0.0 else if (orbAlpha > 0.998) orbAlpha = 1.0

        compose(tint, dark, scatter)
        return true
    }

    private fun ink(w: Double, dark: Boolean, tint: IntArray) {
        val g = w.coerceIn(0.0, 1.0)
        val b = 0.5 + 0.5 * (if (dark) 1 - g else g)
        val hi = b.pow(6) * 0.35
        rgb[0] = tint[0] * b + (255 - tint[0]) * hi
        rgb[1] = tint[1] * b + (255 - tint[1]) * hi
        rgb[2] = tint[2] * b + (255 - tint[2]) * hi
    }

    /** Blend every pool point between its shell position and its orb dot. */
    private fun compose(tint: IntArray, dark: Boolean, scatter: Double) {
        val c = side / 2
        val R = R_SHELL * side
        val half = side / 2
        val e = orbAlpha * orbAlpha * (3 - 2 * orbAlpha)
        val ang = spin * 0.05
        val sy = sin(ang); val cyw = cos(ang); val sz = sin(ang); val cz = cos(ang)

        val cf = cfg
        if (cf != null) frameFor(cf.mode, live, side, orbTime, cf.opts)
        val ex = 1 + vol * 0.05
        val bl = 1 + vol * 0.4
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
            val xp = 1 + vol * spread[i] * 0.4
            // perspective: the original drew through a camera at 4.5
            val dd = max(0.1, CAM_Z - z2 * xp)
            var x = c + x1 * R * xp
            var y = c - y1 * R * xp
            var z = z2
            var r = (0.012 * half) / dd
            var al = 0.9
            var cr = dr.toDouble(); var cg = dg.toDouble(); var cb = db.toDouble()

            if (cf != null && live.n > 0 && e > 0) {
                val j = min(min(cf.m - 1, live.n - 1), cf.tgt[i])
                ink(live.white[j], dark, tint)
                val cp = cf.cnt[i]
                val jr = if (cf.cluster && cp > 1) (0.004 + 0.004 * scatter) * side
                else scatter * 0.014 * side * (1 + (cf.thick - 1) * 0.6) * (if (cp > 1) min(2.5, sqrt(cp.toDouble())) else 1.0)
                val ox = c + (live.x[j] - c) * ex * cf.fit + jit[i * 2] * jr
                val oy = c + (live.y[j] - c) * ex * cf.fit + jit[i * 2 + 1] * jr
                // same dot size as the sphere: perspective radius from depth
                val orr = (0.012 * half) / (CAM_Z - live.z[j].coerceIn(-1.0, 1.0)) * bl * cf.thick
                val oa = 0.9 * live.a[j]
                x += (ox - x) * e
                y += (oy - y) * e
                z += (live.z[j] - z) * e
                r += (orr - r) * e
                al += (oa - al) * e
                cr += (rgb[0] - cr) * e
                cg += (rgb[1] - cg) * e
                cb += (rgb[2] - cb) * e
            }
            if (al < 0.02 || r < 0.3) continue
            px[i] = x.toFloat(); py[i] = y.toFloat(); pz[i] = z.toFloat(); pr[i] = r.toFloat(); pa[i] = al.toFloat()
            pcr[i] = cr.toInt().coerceIn(0, 255); pcg[i] = cg.toInt().coerceIn(0, 255); pcb[i] = cb.toInt().coerceIn(0, 255)
            // far -> near order: float bits mapped to a sortable int, index in the low word
            val bits = pz[i].toRawBits()
            val sortable = bits xor ((bits shr 31) and 0x7FFFFFFF)
            keys[n++] = (sortable.toLong() shl 32) or i.toLong()
        }
        java.util.Arrays.sort(keys, 0, n)
        nDraw = n

        // connecting's web: edges, fitted with the dots, fading in late
        nLines = 0
        if (cf != null && live.ln > 0 && e > 0.5) {
            val k = (e - 0.5) * 2
            val f = ex * cf.fit
            if (live.ln > lx1.size) {
                val s = live.ln * 2
                lx1 = FloatArray(s); ly1 = FloatArray(s); lx2 = FloatArray(s); ly2 = FloatArray(s)
                lw = FloatArray(s); la = FloatArray(s); lcr = IntArray(s); lcg = IntArray(s); lcb = IntArray(s)
            }
            for (i in 0 until live.ln) {
                ink(live.lwhite[i], dark, tint)
                lx1[i] = (c + (live.lx1[i] - c) * f).toFloat(); ly1[i] = (c + (live.ly1[i] - c) * f).toFloat()
                lx2[i] = (c + (live.lx2[i] - c) * f).toFloat(); ly2[i] = (c + (live.ly2[i] - c) * f).toFloat()
                lw[i] = live.lw[i].toFloat()
                la[i] = (live.la[i] * k * k * 0.6).toFloat()
                lcr[i] = rgb[0].toInt().coerceIn(0, 255); lcg[i] = rgb[1].toInt().coerceIn(0, 255); lcb[i] = rgb[2].toInt().coerceIn(0, 255)
            }
            nLines = live.ln
        }
    }

    fun draw(scope: DrawScope) = with(scope) {
        if (side <= 0.0) return@with
        val d = density
        val offX = (size.width - side.toFloat() * d) / 2f
        val offY = (size.height - side.toFloat() * d) / 2f
        // additive ink, like canvas 'lighter'
        for (i in 0 until nLines) {
            drawLine(
                color = Color(lcr[i] / 255f, lcg[i] / 255f, lcb[i] / 255f, la[i].coerceIn(0f, 1f)),
                start = Offset(offX + lx1[i] * d, offY + ly1[i] * d),
                end = Offset(offX + lx2[i] * d, offY + ly2[i] * d),
                strokeWidth = lw[i] * d,
                blendMode = BlendMode.Plus
            )
        }
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
