package com.aurex.sphere

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Kotlin port of the thinking-orbs geometry engine: pure math over
 * (size, t, opts), no rendering surface. Dots come out in GENERATION order
 * (the sphere keeps each dot's identity so the pool can morph onto it) and
 * are never filtered by alpha; only radii are clamped and faint lines dropped.
 */

internal enum class Mode(val speed: Double, val extra: Map<String, Double>) {
    ORBITS(1.885, emptyMap()),
    GLOBE(2.015, mapOf("scanMul" to 4.08, "dimBase" to 0.45)),
    RUBIK(1.82, emptyMap()),
    WAVE(4.388, emptyMap()),
    WEB(3.315, emptyMap()),
    BRAID(1.625, emptyMap()),
    RIBBON(2.34, mapOf("spin" to 0.0, "bandMul" to 3.9, "wobMul" to 1.0)),
    RING(3.24, mapOf("spin" to 0.0, "bandMul" to 3.627, "wobMul" to 0.368)),
    MORPH(2.405, mapOf("spread" to 1.45));

    val base: Map<String, Double>
        get() = BASE_PROFILES.getValue(this)
}

internal class Opts(private val m: Map<String, Double>) {
    fun d(k: String, def: Double): Double = m[k] ?: def
    fun flag(k: String): Boolean = (m[k] ?: 0.0) != 0.0
}

private fun jsRound(v: Double): Double = floor(v + 0.5)

private val COUNT_PAIRS = listOf(
    "latRings" to "lonDensity",
    "rings" to "lonDensity",
    "lanes" to "segs"
)
private val COUNT_KEYS = listOf("orbitN", "ghostN", "nodeN", "strandN", "signals")
private val RADIUS_KEYS = listOf(
    "rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth", "nodeR", "nodeRDepth"
)

internal fun scaleCounts(o: Map<String, Double>, scale: Double): Map<String, Double> {
    val out = o.toMutableMap()
    val done = HashSet<String>()
    val rt = sqrt(scale)
    for ((a, b) in COUNT_PAIRS) {
        val va = out[a]
        val vb = out[b]
        if (va != null && vb != null && a !in done && b !in done) {
            out[a] = max(2.0, jsRound(va * rt))
            out[b] = max(2.0, jsRound(vb * rt))
            done += a
            done += b
        }
    }
    for (k in COUNT_KEYS) {
        val v = out[k]
        // 0 means the mode opted out of that layer entirely (ring has no ghost sphere)
        if (v != null && v != 0.0 && k !in done) out[k] = max(1.0, jsRound(v * scale))
    }
    out["iconD"]?.let { out["iconD"] = max(0.02, it * scale) }
    return out
}

internal fun scaleRadii(o: Map<String, Double>, scale: Double): Map<String, Double> {
    val out = o.toMutableMap()
    for (k in RADIUS_KEYS) out[k]?.let { out[k] = it * scale }
    out["rSizeMul"] = (out["rSizeMul"] ?: 1.0) * scale
    return out
}

private val BASE_PROFILES: Map<Mode, Map<String, Double>> = mapOf(
    Mode.GLOBE to mapOf(
        "latRings" to 17.0, "lonDensity" to 44.0, "rBase" to 0.6, "rDepth" to 1.7, "rBoost" to 1.0,
        "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.ORBITS to mapOf(
        "orbitN" to 12.0, "ghostN" to 40.0, "ghostR" to 0.9, "ghostA" to 0.5, "particles" to 3.0,
        "partR" to 1.2, "partRDepth" to 1.6, "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.RUBIK to mapOf(
        "latRings" to 15.0, "lonDensity" to 40.0, "moveCount" to 14.0, "rBase" to 0.6, "rDepth" to 1.7,
        "rActive" to 0.3, "inkFar" to 0.62, "inkSpan" to 0.54, "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.WAVE to mapOf(
        "rings" to 15.0, "lonDensity" to 40.0, "rBase" to 0.6, "rDepth" to 1.7, "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.WEB to mapOf(
        "nodeN" to 30.0, "thr" to 0.72, "signals" to 5.0, "nodeR" to 1.4, "nodeRDepth" to 1.8,
        "lineW" to 2.0, "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.BRAID to mapOf(
        "strandN" to 52.0, "turns" to 3.0, "ghostN" to 150.0, "rBase" to 1.2, "rDepth" to 1.8,
        "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.RIBBON to mapOf(
        "lanes" to 5.0, "segs" to 88.0, "ghostN" to 150.0, "rBase" to 1.1, "rDepth" to 1.7,
        "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.RING to mapOf(
        "lanes" to 5.0, "segs" to 88.0, "ghostN" to 0.0, "faceOn" to 1.0, "rBase" to 1.1, "rDepth" to 1.7,
        "rsPow" to 0.6, "rMin" to 0.3
    ),
    Mode.MORPH to mapOf("rDot" to 0.021, "iconD" to 1.0, "rMin" to 0.25)
)

/** One instant of geometry: dots in generation order, then edge lines. */
internal class Frame {
    var n = 0
    var x = DoubleArray(4096)
    var y = DoubleArray(4096)
    var z = DoubleArray(4096)
    var r = DoubleArray(4096)
    var white = DoubleArray(4096)
    var a = DoubleArray(4096)

    var ln = 0
    var lx1 = DoubleArray(512)
    var ly1 = DoubleArray(512)
    var lx2 = DoubleArray(512)
    var ly2 = DoubleArray(512)
    var lwhite = DoubleArray(512)
    var la = DoubleArray(512)
    var lw = DoubleArray(512)

    fun reset() {
        n = 0
        ln = 0
    }

    fun dot(px: Double, py: Double, pz: Double, pr: Double, pw: Double, pa: Double = 1.0) {
        if (n == x.size) {
            val s = n * 2
            x = x.copyOf(s); y = y.copyOf(s); z = z.copyOf(s)
            r = r.copyOf(s); white = white.copyOf(s); a = a.copyOf(s)
        }
        x[n] = px; y[n] = py; z[n] = pz; r[n] = pr; white[n] = pw; a[n] = pa
        n++
    }

    fun line(x1: Double, y1: Double, x2: Double, y2: Double, w: Double, wt: Double, al: Double) {
        if (ln == lx1.size) {
            val s = ln * 2
            lx1 = lx1.copyOf(s); ly1 = ly1.copyOf(s); lx2 = lx2.copyOf(s); ly2 = ly2.copyOf(s)
            lwhite = lwhite.copyOf(s); la = la.copyOf(s); lw = lw.copyOf(s)
        }
        lx1[ln] = x1; ly1[ln] = y1; lx2[ln] = x2; ly2[ln] = y2; lw[ln] = w; lwhite[ln] = wt; la[ln] = al
        ln++
    }

    /** Clamp radii to the mode's floor; drop invisible lines. Dots keep their order. */
    fun finish(rMin: Double) {
        for (i in 0 until n) r[i] = max(rMin, r[i])
        var k = 0
        for (i in 0 until ln) {
            if (la[i] >= 0.02) {
                if (k != i) {
                    lx1[k] = lx1[i]; ly1[k] = ly1[i]; lx2[k] = lx2[i]; ly2[k] = ly2[i]
                    lwhite[k] = lwhite[i]; la[k] = la[i]; lw[k] = lw[i]
                }
                k++
            }
        }
        ln = k
    }
}

/** Shared spin + tilt + orthographic projection (results in px / py / pz). */
internal class Proj {
    var px = 0.0
    var py = 0.0
    var pz = 0.0
    private var st = 0.0
    private var ct = 1.0
    private var sy = 0.0
    private var cyw = 1.0
    private var cx = 0.0
    private var cy = 0.0
    private var sc = 1.0

    fun set(yaw: Double, tilt: Double, cx: Double, cy: Double, scale: Double): Proj {
        st = sin(tilt); ct = cos(tilt); sy = sin(yaw); cyw = cos(yaw)
        this.cx = cx; this.cy = cy; sc = scale
        return this
    }

    fun p(x: Double, y: Double, z: Double) {
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct
        px = cx + x1 * sc
        py = cy - y1 * sc
        pz = z2
    }
}

private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f
private fun frac(x: Double) = x - floor(x)
private fun hashD(a: Double, b: Double): Double {
    val h = sin(a * 12.9898 + b * 78.233) * 43758.5453
    return h - floor(h)
}
private fun vnoise(x: Double, y: Double): Double {
    val xi = floor(x)
    val yi = floor(y)
    var fx = x - xi
    var fy = y - yi
    fx = fx * fx * (3 - 2 * fx)
    fy = fy * fy * (3 - 2 * fy)
    val a = hashD(xi, yi)
    val b = hashD(xi + 1, yi)
    val c = hashD(xi, yi + 1)
    val d = hashD(xi + 1, yi + 1)
    return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy
}
private val GOLDEN = PI * (3 - sqrt(5.0))
private fun fibY(i: Int, n: Int) = 1 - (2 * (i + 0.5)) / n
private fun fibX(i: Int, n: Int): Double {
    val y = fibY(i, n)
    return sqrt(1 - y * y) * cos(i * GOLDEN)
}
private fun fibZ(i: Int, n: Int): Double {
    val y = fibY(i, n)
    return sqrt(1 - y * y) * sin(i * GOLDEN)
}
private fun angleDelta(a: Double, b: Double) = atan2(sin(a - b), cos(a - b))
private fun radiusScale(size: Double, pow: Double) = (size / 300).pow(pow)

private val proj = Proj()

internal fun frameFor(mode: Mode, f: Frame, size: Double, t: Double, o: Opts) {
    f.reset()
    when (mode) {
        Mode.ORBITS -> frameOrbits(f, size, t, o)
        Mode.GLOBE -> frameGlobe(f, size, t, o)
        Mode.RUBIK -> frameRubik(f, size, t, o)
        Mode.WAVE -> frameWave(f, size, t, o)
        Mode.WEB -> frameWeb(f, size, t, o)
        Mode.BRAID -> frameBraid(f, size, t, o)
        Mode.RIBBON, Mode.RING -> frameRibbon(f, size, t, o)
        Mode.MORPH -> frameMorph(f, size, t, o)
    }
    f.finish(o.d("rMin", 0.3))
}

// --- orbits (working) ---------------------------------------------------

private fun frameOrbits(f: Frame, size: Double, t: Double, o: Opts) {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.82
    val pt = proj.set(t * 0.12, 0.3, cx, cy, 1.0)
    val rs = radiusScale(size, o.d("rsPow", 0.6))
    val orbitN = o.d("orbitN", 12.0).toInt()
    val ghostN = o.d("ghostN", 40.0).toInt()
    val particles = o.d("particles", 3.0).toInt()

    for (orb in 0 until orbitN) {
        val h1 = hashD(orb.toDouble(), 1.7)
        val h2 = hashD(orb.toDouble(), 5.2)
        val h3 = hashD(orb.toDouble(), 8.9)
        val ro = R * (0.45 + 0.52 * h1)
        val th = h1 * 2 * PI
        val phi = acos(2 * h2 - 1)
        val nx = sin(phi) * cos(th)
        val ny = cos(phi)
        val nz = sin(phi) * sin(th)
        var ux = -ny
        var uy = nx
        val uz = 0.0
        val ul = max(1e-6, sqrt(ux * ux + uy * uy))
        ux /= ul
        uy /= ul
        val vx = ny * uz - nz * uy
        val vy = nz * ux - nx * uz
        val vz = nx * uy - ny * ux
        val speed = (0.25 + 0.55 * h3) * (if (h3 > 0.5) 1 else -1)

        for (k in 0 until ghostN) {
            val a = (k.toDouble() / ghostN) * 2 * PI
            pt.p(
                (ux * cos(a) + vx * sin(a)) * ro,
                (uy * cos(a) + vy * sin(a)) * ro,
                (uz * cos(a) + vz * sin(a)) * ro
            )
            val depth = (pt.pz / ro + 1) / 2
            f.dot(pt.px, pt.py, pt.pz, o.d("ghostR", 0.9) * rs, 0.72, o.d("ghostA", 0.5) * (0.4 + 0.6 * depth))
        }
        for (m in 0 until particles) {
            val a = t * speed + (m.toDouble() / particles) * 2 * PI + h2 * 6
            pt.p(
                (ux * cos(a) + vx * sin(a)) * ro,
                (uy * cos(a) + vy * sin(a)) * ro,
                (uz * cos(a) + vz * sin(a)) * ro
            )
            val depth = (pt.pz / ro + 1) / 2
            f.dot(
                pt.px, pt.py, pt.pz,
                (o.d("partR", 1.2) + o.d("partRDepth", 1.6) * depth) * rs,
                0.3 - 0.22 * depth
            )
        }
    }
}

// --- lattice modes: globe (searching), rubik (solving), wave (listening) --

private fun frameGlobe(f: Frame, size: Double, t: Double, o: Opts) {
    val spin = 0.5
    val cx = size / 2
    val cy = size / 2
    val radius = (size / 2) * 0.82
    val tilt = 0.4 + 0.06 * sin(t * 0.35)
    val pt = proj.set(t * spin, tilt, cx, cy, radius)
    val scan = t * (spin + (1.7 - spin) * o.d("scanMul", 1.0))
    val rs = radiusScale(size, o.d("rsPow", 0.6))
    val dimBase = o.d("dimBase", 1.0)
    val latRings = o.d("latRings", 17.0).toInt()
    val lonDensity = o.d("lonDensity", 44.0)
    for (li in 0..latRings) {
        val lat = -PI / 2 + (li.toDouble() / latRings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1, jsRound(abs(cosLat) * lonDensity).toInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * PI
            pt.p(cosLat * cos(lon), sinLat, cosLat * sin(lon))
            val z = pt.pz
            val depth = (z + 1) / 2
            val d = angleDelta(lon + t * spin, scan)
            val boost = exp(-(d * d) / 0.18) * max(0.0, z)
            f.dot(
                pt.px, pt.py, z,
                (o.d("rBase", 0.6) + o.d("rDepth", 1.7) * depth + o.d("rBoost", 1.0) * boost) * rs,
                o.d("inkFar", 0.62) - o.d("inkSpan", 0.54) * depth,
                dimBase + (1 - dimBase) * min(1.0, boost)
            )
        }
    }
}

private class Moves(val axis: IntArray, val lo: DoubleArray, val hi: DoubleArray, val ang: DoubleArray)
private val movesCache = HashMap<Int, Moves>()
private fun makeMoves(count: Int): Moves = movesCache.getOrPut(count) {
    val axis = IntArray(count)
    val lo = DoubleArray(count)
    val hi = DoubleArray(count)
    val ang = DoubleArray(count)
    for (i in 0 until count) {
        axis[i] = min(2, floor(hashD(i.toDouble(), 2.3) * 3).toInt())
        lo[i] = -1.0 + 0.5 * min(3, floor(hashD(i.toDouble(), 5.9) * 4).toInt())
        hi[i] = lo[i] + 0.5
        ang[i] = (if (hashD(i.toDouble(), 7.7) < 0.5) 1 else -1) * PI / 2
    }
    Moves(axis, lo, hi, ang)
}

private fun frameRubik(f: Frame, size: Double, t: Double, o: Opts) {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.82
    val pt = proj.set(t * 0.55, 0.35 + 0.1 * sin(t * 0.9), cx, cy, R)
    val rs = radiusScale(size, o.d("rsPow", 0.6))
    val moveCount = o.d("moveCount", 14.0).toInt()
    val mv = makeMoves(moveCount)

    // solve cycle: scramble in eased quarter turns, replay in reverse, rest
    val slotDur = 0.42
    val rest = 1.2
    val cyc = 2 * moveCount * slotDur + rest
    val tc = t % cyc
    val amount = DoubleArray(moveCount)
    var active = -1
    if (tc < 2 * moveCount * slotDur) {
        val slot = floor(tc / slotDur).toInt()
        val p = (tc - slot * slotDur) / slotDur
        val cl = min(1.0, p / 0.7)
        val ep = 1 - (1 - cl).pow(3)
        if (slot < moveCount) {
            for (i in 0 until slot) amount[i] = 1.0
            amount[slot] = ep
            active = slot
        } else {
            val u = 2 * moveCount - 1 - slot
            for (i in 0 until u) amount[i] = 1.0
            amount[u] = 1 - ep
            active = u
        }
    }

    val latRings = o.d("latRings", 15.0).toInt()
    val lonDensity = o.d("lonDensity", 40.0)
    for (li in 0..latRings) {
        val lat = -PI / 2 + (li.toDouble() / latRings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1, jsRound(abs(cosLat) * lonDensity).toInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * PI
            var x = cosLat * cos(lon)
            var y = sinLat
            var z = cosLat * sin(lon)
            var inActive = false
            for (i in 0 until moveCount) {
                if (amount[i] <= 0) continue
                val coord = when (mv.axis[i]) { 0 -> x; 1 -> y; else -> z }
                if (coord < mv.lo[i] || coord >= mv.hi[i]) continue
                if (i == active) inActive = true
                val a = mv.ang[i] * amount[i]
                val ca = cos(a)
                val sa = sin(a)
                when (mv.axis[i]) {
                    0 -> { val y2 = y * ca - z * sa; z = y * sa + z * ca; y = y2 }
                    1 -> { val x2 = x * ca + z * sa; z = -x * sa + z * ca; x = x2 }
                    else -> { val x2 = x * ca - y * sa; y = x * sa + y * ca; x = x2 }
                }
            }
            pt.p(x, y, z)
            val depth = (pt.pz + 1) / 2
            f.dot(
                pt.px, pt.py, pt.pz,
                (o.d("rBase", 0.6) + o.d("rDepth", 1.7) * depth + (if (inActive) o.d("rActive", 0.3) else 0.0)) * rs,
                o.d("inkFar", 0.62) - o.d("inkSpan", 0.54) * depth - (if (inActive) 0.14 else 0.0)
            )
        }
    }
}

private fun frameWave(f: Frame, size: Double, t: Double, o: Opts) {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.874
    val pt = proj.set(t * 0.18, 0.38, cx, cy, 1.0)
    val rs = radiusScale(size, o.d("rsPow", 0.6))
    val rings = o.d("rings", 15.0).toInt()
    val lonDensity = o.d("lonDensity", 40.0)
    for (ri in 0..rings) {
        val lat = -PI / 2 + (ri.toDouble() / rings) * PI
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val w = 0.62 * sin(t * 2.1 - ri * 0.52) + 0.38 * sin(t * 1.27 + ri * 0.83)
        val rr = R * (0.88 + 0.105 * w)
        val lonCount = max(1, jsRound(abs(cosLat) * lonDensity).toInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toDouble() / lonCount) * 2 * PI
            pt.p(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
            val depth = (pt.pz / R + 1) / 2
            val crest = max(0.0, w)
            f.dot(
                pt.px, pt.py, pt.pz,
                (o.d("rBase", 0.6) + o.d("rDepth", 1.7) * depth) * (1 + 0.4 * crest) * rs,
                0.66 - 0.56 * depth - 0.1 * crest
            )
        }
    }
}

// --- braid (weaving) -------------------------------------------------------

private fun frameBraid(f: Frame, size: Double, t: Double, o: Opts) {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.76
    val pt = proj.set(t * 0.4, 0.3, cx, cy, 1.0)
    val rs = radiusScale(size, o.d("rsPow", 0.6))
    val ghostN = o.d("ghostN", 150.0).toInt()
    for (i in 0 until ghostN) {
        pt.p(fibX(i, ghostN) * R, fibY(i, ghostN) * R, fibZ(i, ghostN) * R)
        val depth = (pt.pz / R + 1) / 2
        f.dot(pt.px, pt.py, pt.pz, 0.8 * rs, 0.78, 0.1 + 0.22 * depth)
    }
    val strandN = o.d("strandN", 52.0).toInt()
    val turns = o.d("turns", 3.0)
    for (s in 0 until 3) {
        val phase = (s / 3.0) * 2 * PI
        for (i in 0 until strandN) {
            val u = (frac(i.toDouble() / strandN + t * 0.045) * 2 - 1) * 0.96
            val surf = sqrt(max(0.0, 1 - u * u))
            val endFade = min(1.0, (1 - abs(u)) / 0.1)
            val a = u * PI * turns + phase
            val weave = 1 + 0.075 * sin(u * PI * turns * 2 + phase * 2 + t * 0.8)
            val rr = surf * R * weave
            pt.p(cos(a) * rr, u * R * weave, sin(a) * rr)
            val depth = (pt.pz / R + 1) / 2
            f.dot(
                pt.px, pt.py, pt.pz,
                (o.d("rBase", 1.2) + o.d("rDepth", 1.8) * depth) * rs,
                0.55 - 0.45 * depth,
                endFade * (0.45 + 0.55 * depth)
            )
        }
    }
}

// --- ribbon (composing) / ring (breathing, faceOn) ---------------------------

private fun frameRibbon(f: Frame, size: Double, t: Double, o: Opts) {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.78
    val spin = o.d("spin", 1.0)
    val camTilt = 0.3
    val pt = proj.set(t * 0.1 * spin, camTilt, cx, cy, 1.0)
    val rs = radiusScale(size, o.d("rsPow", 0.6))
    val faceOn = o.flag("faceOn")

    val ghostN = o.d("ghostN", 150.0).toInt()
    for (i in 0 until ghostN) {
        pt.p(fibX(i, ghostN) * R, fibY(i, ghostN) * R, fibZ(i, ghostN) * R)
        val depth = (pt.pz / R + 1) / 2
        f.dot(pt.px, pt.py, pt.pz, 0.8 * rs, 0.78, 0.1 + 0.22 * depth)
    }

    val ya = t * 0.24 * spin
    val ta = if (faceOn) -camTilt else 0.55 + 0.3 * sin(t * 0.18) * spin
    val ux = cos(ya)
    val uy = 0.0
    val uz = sin(ya)
    val vx = -uz * sin(ta)
    val vy = cos(ta)
    val vz = ux * sin(ta)
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    val wobMul = o.d("wobMul", 1.0)
    val wobAmp = 0.23 * wobMul
    val baseR = if (faceOn) R / (1 + 0.85 * wobAmp) else R

    val baseLanes = o.d("lanes", 5.0)
    val segs = o.d("segs", 88.0).toInt()
    val lanes = max(1, jsRound(baseLanes * o.d("bandMul", 1.0)).toInt())
    for (w in 0 until lanes) {
        val laneOff = (w - (lanes - 1) / 2.0) * 0.075
        val edge = abs(w - (lanes - 1) / 2.0) / max(1.0, (lanes - 1) / 2.0)
        for (k in 0 until segs) {
            val a = (k.toDouble() / segs) * 2 * PI
            val wob = (0.16 * sin(a * 3 - t * 1.7 + w * 0.22) + 0.07 * sin(a * 5 + t * 1.1)) * wobMul
            val radial = if (faceOn) 1 + wob else 1.0
            val off = if (faceOn) laneOff else laneOff + wob
            val x = ux * cos(a) + vx * sin(a) + nx * off
            val y = uy * cos(a) + vy * sin(a) + ny * off
            val z = uz * cos(a) + vz * sin(a) + nz * off
            val l = sqrt(x * x + y * y + z * z)
            val rr = baseR * radial
            pt.p((x / l) * rr, (y / l) * rr, (z / l) * rr)
            val depth = (pt.pz / R + 1) / 2
            f.dot(
                pt.px, pt.py, pt.pz,
                (o.d("rBase", 1.1) + o.d("rDepth", 1.7) * depth) * (1 - 0.25 * edge) * rs,
                0.52 - 0.44 * depth + 0.18 * edge,
                0.4 + 0.6 * depth
            )
        }
    }
}

// --- web (connecting) ------------------------------------------------------

private fun frameWeb(f: Frame, size: Double, t: Double, o: Opts) {
    val cx = size / 2
    val cy = size / 2
    val R = (size / 2) * 0.8 * o.d("spread", 1.0)
    val pt = proj.set(t * 0.12, 0.32, cx, cy, R)
    val rs = radiusScale(size, o.d("rsPow", 0.6))
    val nodeN = o.d("nodeN", 30.0).toInt()
    val thr = o.d("thr", 0.72)
    val nodeR = o.d("nodeR", 1.4)
    val nodeRDepth = o.d("nodeRDepth", 1.8)

    val nxs = DoubleArray(nodeN)
    val nys = DoubleArray(nodeN)
    val nzs = DoubleArray(nodeN)
    for (i in 0 until nodeN) {
        val x = fibX(i, nodeN) + 0.3 * (vnoise(i * 0.31 + 9, t * 0.24) - 0.5) * 2
        val y = fibY(i, nodeN) + 0.3 * (vnoise(i * 0.53 + 27, t * 0.21) - 0.5) * 2
        val z = fibZ(i, nodeN) + 0.3 * (vnoise(i * 0.77 + 55, t * 0.27) - 0.5) * 2
        val l = sqrt(x * x + y * y + z * z)
        nxs[i] = x / l; nys[i] = y / l; nzs[i] = z / l
    }

    for (i in 0 until nodeN) {
        for (j in i + 1 until nodeN) {
            val dx = nxs[i] - nxs[j]
            val dy = nys[i] - nys[j]
            val dz = nzs[i] - nzs[j]
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (dist >= thr) continue
            pt.p(nxs[i], nys[i], nzs[i])
            val x1 = pt.px; val y1 = pt.py; val z1 = pt.pz
            pt.p(nxs[j], nys[j], nzs[j])
            val depth = ((z1 + pt.pz) / 2 + 1) / 2
            f.line(
                x1, y1, pt.px, pt.py,
                max(0.6, o.d("lineW", 0.8) * rs), 0.42,
                (1 - dist / thr) * (0.3 + 0.55 * depth)
            )
        }
    }

    for (i in 0 until nodeN) {
        pt.p(nxs[i], nys[i], nzs[i])
        val depth = (pt.pz + 1) / 2
        val pulse = 1 + 0.25 * sin(t * 1.4 + i * 2.7)
        f.dot(pt.px, pt.py, pt.pz, (nodeR + nodeRDepth * depth) * pulse * rs, 0.55 - 0.45 * depth)
    }

    val signals = o.d("signals", 5.0).toInt()
    for (s in 0 until signals) {
        val seg = floor(t * 0.55 + s * 7.31)
        val a = floor(hashD(seg, s * 3.1 + 1.7) * nodeN).toInt()
        val b = floor(hashD(seg, s * 5.7 + 4.2) * nodeN).toInt()
        if (a == b) continue
        val ff = frac(t * 0.55 + s * 7.31)
        val x = lerp(nxs[a], nxs[b], ff)
        val y = lerp(nys[a], nys[b], ff)
        val z = lerp(nzs[a], nzs[b], ff)
        val l = max(1e-6, sqrt(x * x + y * y + z * z))
        pt.p(x / l, y / l, z / l)
        val depth = (pt.pz + 1) / 2
        f.dot(pt.px, pt.py, pt.pz, (nodeR * 1.5 + nodeRDepth * depth) * rs, 0.05, 0.5 + 0.5 * depth)
    }
}

// --- morph (shaping): circle → triangle → square ------------------------------

private fun smoothE(x: Double) = x * x * (3 - 2 * x)

private class PolyPath(private val verts: Array<DoubleArray>) {
    private val len = DoubleArray(verts.size)
    private var total = 0.0

    init {
        for (i in verts.indices) {
            val a = verts[i]
            val b = verts[(i + 1) % verts.size]
            len[i] = hypot(b[0] - a[0], b[1] - a[1])
            total += len[i]
        }
    }

    fun at(f: Double, out: DoubleArray) {
        var target = f * total
        var i = 0
        while (target > len[i] && i < verts.size - 1) {
            target -= len[i]
            i++
        }
        val a = verts[i]
        val b = verts[(i + 1) % verts.size]
        val ff = if (len[i] != 0.0) min(1.0, target / len[i]) else 0.0
        out[0] = a[0] + (b[0] - a[0]) * ff
        out[1] = a[1] + (b[1] - a[1]) * ff
    }
}

private val TRIANGLE = PolyPath(arrayOf(doubleArrayOf(0.0, -0.26), doubleArrayOf(0.24, 0.16), doubleArrayOf(-0.24, 0.16)))
private val SQUARE = PolyPath(
    arrayOf(
        doubleArrayOf(0.0, -0.2), doubleArrayOf(0.2, -0.2), doubleArrayOf(0.2, 0.2),
        doubleArrayOf(-0.2, 0.2), doubleArrayOf(-0.2, -0.2)
    )
)

private fun shapeAt(k: Int, f: Double, out: DoubleArray) {
    when (k) {
        0 -> {
            val a = -PI / 2 + f * 2 * PI
            out[0] = cos(a) * 0.24
            out[1] = sin(a) * 0.24
        }
        1 -> TRIANGLE.at(f, out)
        else -> SQUARE.at(f, out)
    }
}

private const val MORPH_HOLD = 1.4
private const val MORPH_MORPH = 0.9
private const val MORPH_SEG = MORPH_HOLD + MORPH_MORPH
private const val MORPH_M = 160
private val mpx = DoubleArray(MORPH_M)
private val mpy = DoubleArray(MORPH_M)
private val mlen = DoubleArray(MORPH_M)
private val ta = DoubleArray(2)
private val tb = DoubleArray(2)

private fun frameMorph(f: Frame, size: Double, t: Double, o: Opts) {
    val kk = 3
    val tc = t % (MORPH_SEG * kk)
    val k = floor(tc / MORPH_SEG).toInt()
    val local = tc - k * MORPH_SEG
    val m = if (local > MORPH_HOLD) smoothE((local - MORPH_HOLD) / MORPH_MORPH) else 0.0
    val sprd = o.d("spread", 1.0)

    for (i in 0 until MORPH_M) {
        val fr = i.toDouble() / MORPH_M
        shapeAt(k, fr, ta)
        shapeAt((k + 1) % kk, fr, tb)
        mpx[i] = (ta[0] + (tb[0] - ta[0]) * m) * sprd
        mpy[i] = (ta[1] + (tb[1] - ta[1]) * m) * sprd
    }
    var total = 0.0
    for (i in 0 until MORPH_M) {
        val j = (i + 1) % MORPH_M
        mlen[i] = hypot(mpx[j] - mpx[i], mpy[j] - mpy[i])
        total += mlen[i]
    }

    val n = max(6, jsRound(34 * o.d("iconD", 1.0)).toInt())
    val re = o.d("rDot", 0.021) * 1.35 * sprd
    val pulse = 1 + 0.02 * sin(local * 3.1)
    val c2 = size / 2
    var seg = 0
    var acc = 0.0
    for (k2 in 0 until n) {
        val target = (k2.toDouble() / n) * total
        while (acc + mlen[seg] < target && seg < MORPH_M - 1) {
            acc += mlen[seg]
            seg++
        }
        val j = (seg + 1) % MORPH_M
        val ff = if (mlen[seg] != 0.0) min(1.0, (target - acc) / mlen[seg]) else 0.0
        val x = (mpx[seg] + (mpx[j] - mpx[seg]) * ff) * pulse
        val y = (mpy[seg] + (mpy[j] - mpy[seg]) * ff) * pulse
        f.dot(c2 + x * size, c2 + y * size, 0.0, max(0.35, re * size), 0.1)
    }
}
