package com.everettpenne.ix

import kotlin.math.max
import kotlin.math.min

/**
 * A tone curve chosen per picture. Photographs from the Pixel camera app land near the same brightness anchors on the whole (the medians over 68
 * pairs: luma 102 of 255 at the middle, 200 at the 95th percentile, 219 at the 99th, the darkest percent near 8), though not exactly: the middle
 * moves between 87 and 131 (quartiles) from scene to scene, lower in contrasty scenes. An earlier version of these targets came from two
 * pictures (87, 188, 206) and left every picture about a quarter too dark in the middle. This does the same as the Pixel app, to the medians: it reads where the picture's own brightness ranks fall, and bends a curve so those
 * ranks land on the targets, blended with the identity by [strength] and limited in slope so a flat or very contrasty scene is not stretched into
 * banding or noise.
 */
public object IxAutoTone {
    /** Fractions of the picture's pixels at or below a brightness. */
    public val RANKS: FloatArray = floatArrayOf(0f, 0.01f, 0.05f, 0.25f, 0.5f, 0.95f, 0.99f, 1f)
    /** The display brightness (0..1) each rank is taken to: the medians of the Pixel camera app's luma at those ranks over 68 same-moment pairs (2026-10-28). */
    public val TARGETS: FloatArray = floatArrayOf(0f, 7.6f, 23.9f, 66.5f, 101.7f, 199.8f, 219.1f, 255f).map { it / 255f }.toFloatArray()
    public const val BINS: Int = 256

    /**
     * [TARGETS] for an exposure compensation of [ev] stops: each target is taken to linear light (gamma 2.2), multiplied by 2^ev and brought back, with a
     * soft shoulder above 0.8 so the brightest ranks approach white without reaching it; black and white themselves stay put.
     */
    public fun targetsFor(ev: Float, base: FloatArray = TARGETS): FloatArray {
        if (ev == 0f) return base
        val gain = Math.pow(2.0, ev.toDouble()).toFloat()
        return FloatArray(base.size) { i ->
            val t = base[i]
            if (i == 0 || i == base.lastIndex) t else {
                val lin = Math.pow(t.toDouble(), 2.2).toFloat() * gain
                val v = if (lin <= 0.8f) lin else 0.8f + 0.2f * (1f - Math.exp(-((lin - 0.8f) / 0.2f).toDouble()).toFloat())
                Math.pow(v.toDouble(), 1.0 / 2.2).toFloat().coerceIn(0f, 0.985f)
            }
        }.also { for (i in 1 until it.size) if (it[i] < it[i - 1]) it[i] = it[i - 1] }
    }

    /**
     * Lifts ([amount] above 0) or deepens (below 0) the shadows of a tone [curve]: a smooth bump centred on display brightness 0.2 and about 0.12 wide,
     * at most 0.1 of the range at full amount, fading out toward black so the blacks stay black, and leaving the highlights where the curve had them. The
     * slope limits are applied again afterwards.
     */
    public fun shadows(curve: FloatArray, amount: Float, minSlope: Float = 0.45f, maxSlope: Float = 2.0f): FloatArray {
        if (amount == 0f) return curve
        val a = amount.coerceIn(-1f, 1f)
        val xs = IxLook.CURVE_X
        val out = FloatArray(xs.size) { i -> val d = (xs[i] - 0.2f) / 0.12f; val fade = minOf(1f, xs[i] / 0.08f); (curve[i] + a * 0.1f * fade * Math.exp((-d * d / 2f).toDouble()).toFloat()).coerceIn(0f, 1f) }
        out[0] = 0f
        for (i in 1 until xs.size) {
            val dx = xs[i] - xs[i - 1]
            val ceilingY = 1f - minSlope * (1f - xs[i])
            out[i] = out[i].coerceIn(out[i - 1] + minSlope * dx, minOf(out[i - 1] + maxSlope * dx, ceilingY))
        }
        return out
    }

    /**
     * [histogram] counts the picture's display luma in [BINS] bins (bin i covers brightness i / 255). Returns the curve's output at each of
     * [IxLook.CURVE_X], ready for [IxLook.curve].
     */
    public fun curve(histogram: IntArray, strength: Float = 1f, targets: FloatArray = TARGETS, minSlope: Float = 0.45f, maxSlope: Float = 2.0f): FloatArray {
        require(histogram.size == BINS) { "need $BINS bins" }
        var total = 0L; for (c in histogram) total += c
        if (total == 0L) return IxLook.CURVE_X.copyOf()
        val cdf = FloatArray(BINS); var acc = 0L
        for (i in 0 until BINS) { acc += histogram[i]; cdf[i] = acc.toFloat() / total }
        fun rankAt(x: Float): Float { val f = x * 255f; val i = min(f.toInt(), BINS - 1); val frac = f - i; return (if (i == 0) 0f else cdf[i - 1]) * (1 - frac) + cdf[i] * frac }
        fun target(rank: Float): Float {
            for (k in 1 until RANKS.size) if (rank <= RANKS[k]) { val t = (rank - RANKS[k - 1]) / max(RANKS[k] - RANKS[k - 1], 1e-6f); return targets[k - 1] + (targets[k] - targets[k - 1]) * t }
            return targets.last()
        }
        val xs = IxLook.CURVE_X
        val ys = FloatArray(xs.size) { i -> val x = xs[i]; (x + strength * (target(rankAt(x)) - x)).coerceIn(0f, 1f) }
        ys[0] = 0f
        // monotone, and no knot may be steeper or shallower than the limits: walk up from black, clamping each step
        for (i in 1 until xs.size) {
            val dx = xs[i] - xs[i - 1]
            // and it must leave room to keep rising at the minimum slope to the top, or the end of the curve would go flat
            val ceiling = 1f - minSlope * (1f - xs[i])
            ys[i] = ys[i].coerceIn(ys[i - 1] + minSlope * dx, minOf(ys[i - 1] + maxSlope * dx, ceiling))
        }
        return ys
    }

    /**
     * Keeps a face out of the shadows and out of the highlights without evening out skin tones. [faceLuma] is the face's mean brightness (0..1) before
     * the curve. If the curve would put it below [floor] it is lifted to the floor, above [ceiling] it is eased down to the ceiling, and the change
     * fades out around it so the rest of the picture is barely touched; a face already between the two is left exactly as the curve has it. (Pushing
     * every face to one brightness would wash out dark skin: only the extremes are corrected.)
     */
    public fun protectFace(curve: FloatArray, faceLuma: Float, floor: Float = 0.27f, ceiling: Float = 0.72f, width: Float = 0.2f, minSlope: Float = 0.45f, maxSlope: Float = 2.0f): FloatArray {
        val xs = IxLook.CURVE_X
        fun at(x: Float): Float {
            if (x <= 0f) return curve[0]
            for (i in 1 until xs.size) if (x <= xs[i]) { val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]); return curve[i - 1] + (curve[i] - curve[i - 1]) * t }
            return curve.last()
        }
        val y = at(faceLuma)
        val delta = if (y < floor) floor - y else if (y > ceiling) ceiling - y else return curve
        val out = FloatArray(xs.size) { i -> val d = (xs[i] - faceLuma) / width; (curve[i] + delta * kotlin.math.exp(-d * d / 2f)).coerceIn(0f, 1f) }
        out[0] = 0f
        for (i in 1 until xs.size) {
            val dx = xs[i] - xs[i - 1]
            val ceilingY = 1f - minSlope * (1f - xs[i])
            out[i] = out[i].coerceIn(out[i - 1] + minSlope * dx, minOf(out[i - 1] + maxSlope * dx, ceilingY))
        }
        return out
    }
}
