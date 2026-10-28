package com.everettpenne.ix

import kotlin.math.max
import kotlin.math.min

/**
 * A tone curve chosen per picture. Photographs from the Pixel camera app, taken of different scenes, land on nearly the same brightness anchors
 * whatever the scene (median luma about 87 of 255, 95th percentile about 188, 99th about 206, the darkest percent near black): the app normalises
 * each picture to a target distribution. This does the same: it reads where the picture's own brightness ranks fall, and bends a curve so those
 * ranks land on the targets, blended with the identity by [strength] and limited in slope so a flat or very contrasty scene is not stretched into
 * banding or noise.
 */
public object IxAutoTone {
    /** Fractions of the picture's pixels at or below a brightness. */
    public val RANKS: FloatArray = floatArrayOf(0f, 0.01f, 0.05f, 0.25f, 0.5f, 0.95f, 0.99f, 1f)
    /** The display brightness (0..1) each rank is taken to, from two Pixel camera app pictures of different scenes. */
    public val TARGETS: FloatArray = floatArrayOf(0f, 4f, 14f, 48f, 87f, 188f, 206f, 255f).map { it / 255f }.toFloatArray()
    public const val BINS: Int = 256

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
}
