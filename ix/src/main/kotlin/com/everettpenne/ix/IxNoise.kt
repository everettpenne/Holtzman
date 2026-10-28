package com.everettpenne.ix

import kotlin.math.abs
import kotlin.math.sqrt

/** A fitted noise model for RAW counts: variance = [shot] * signal + [readVariance], in counts squared, with the signal black-subtracted. */
public class IxNoiseFit(public val shot: Float, public val readVariance: Float, public val tiles: Int, public val binSignal: FloatArray, public val binVariance: FloatArray) {
    override fun toString(): String = "variance = %.4f * signal + %.2f (read std %.2f) from %d tiles".format(java.util.Locale.ROOT, shot, readVariance, sqrt(maxOf(readVariance, 0f)), tiles)
}

/**
 * Measures a sensor's noise from two frames of the same still scene (the photon-transfer method): within a small tile the scene is nearly flat, and
 * the difference of the two frames is pure noise of twice the variance. Plotting that variance against the tile's brightness gives a straight line
 * whose slope is the shot-noise gain and whose intercept is the read-noise variance. Needs the camera and scene to hold still between the frames.
 */
public object IxNoise {
    /** Fits the model from [a] and [b], two [width] x [height] mosaics of the same scene taken at the same settings. Uses the first green site of each Bayer cell. */
    public fun fit(a: ShortArray, b: ShortArray, width: Int, height: Int, black: IntArray, white: Int, cfa: IxCfa = IxCfa.RGGB, tileCells: Int = 16): IxNoiseFit {
        var gx = 0; var gy = 0
        search@ for (y in 0..1) for (x in 0..1) if (cfa.channelAt(x, y) == 1) { gx = x; gy = y; break@search }
        val bl = black[gx + 2 * gy]
        val cw = width / 2; val ch = height / 2
        val tx = cw / tileCells; val ty = ch / tileCells
        val n = tileCells * tileCells
        val signals = ArrayList<Float>(); val variances = ArrayList<Float>()
        val limit = bl + (white - bl) * 0.9f
        for (j in 0 until ty) for (i in 0 until tx) {
            var sa = 0.0; var sb = 0.0; var clipped = false
            for (v in 0 until tileCells) for (u in 0 until tileCells) {
                val idx = ((j * tileCells + v) * 2 + gy) * width + (i * tileCells + u) * 2 + gx
                val pa = a[idx].toInt() and 0xFFFF; val pb = b[idx].toInt() and 0xFFFF
                if (pa >= limit || pb >= limit) clipped = true
                sa += pa; sb += pb
            }
            if (clipped) continue
            val ma = sa / n; val mb = sb / n
            var ss = 0.0
            for (v in 0 until tileCells) for (u in 0 until tileCells) {
                val idx = ((j * tileCells + v) * 2 + gy) * width + (i * tileCells + u) * 2 + gx
                val d = ((a[idx].toInt() and 0xFFFF) - ma) - ((b[idx].toInt() and 0xFFFF) - mb)
                ss += d * d
            }
            signals += (((ma + mb) / 2) - bl).toFloat().coerceAtLeast(0f)
            variances += (ss / (2.0 * (n - 1))).toFloat()
        }
        require(signals.size >= 24) { "too few usable tiles (${signals.size}) to fit a noise model" }
        // bin by brightness (equal tile counts) and take each bin's median variance: a tile with texture or motion has too much variance, and the median ignores it
        val order = signals.indices.sortedBy { signals[it] }
        val bins = 12.coerceAtMost(order.size / 8)
        val bs = FloatArray(bins); val bv = FloatArray(bins)
        for (k in 0 until bins) {
            val part = order.subList(k * order.size / bins, (k + 1) * order.size / bins)
            bs[k] = part.map { signals[it] }.sorted()[part.size / 2]; bv[k] = part.map { variances[it] }.sorted()[part.size / 2]
        }
        val mx = bs.average(); val my = bv.average()
        var sxx = 0.0; var sxy = 0.0
        for (k in 0 until bins) { sxx += (bs[k] - mx) * (bs[k] - mx); sxy += (bs[k] - mx) * (bv[k] - my) }
        val slope = if (sxx > 0) sxy / sxx else 0.0
        return IxNoiseFit(slope.toFloat(), (my - slope * mx).toFloat(), signals.size, bs, bv)
    }
}
