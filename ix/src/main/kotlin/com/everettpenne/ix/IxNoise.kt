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

/**
 * Estimates how noisy a finished 8-bit picture is, per brightness, so the finishing pass can smooth by exactly as much as the noise needs: the
 * median absolute response of Immerkaer's noise filter (a 3x3 Laplacian-of-Laplacian that cancels smooth structure), taken separately for dark,
 * middle and bright areas. The median ignores the few pixels sitting on real edges and texture, so the estimate follows the noise, not the scene.
 */
public object IxDisplayNoise {
    /** Brightness (0..1) at which the three returned noise levels apply. */
    public val CENTRES: FloatArray = floatArrayOf(0.06f, 0.26f, 0.7f)

    /**
     * [argb] holds the picture's pixels as ints with red in the low byte (the order the renderer writes them), [width] x [height]. Returns the noise
     * standard deviation of luma, in 0..1 units, for dark (below 0.12), middle (0.12 to 0.4) and bright (above 0.4) areas; a bin with too few pixels
     * takes the mean of the others.
     */
    public fun estimate(argb: java.nio.IntBuffer, width: Int, height: Int, stride: Int = 3): FloatArray {
        val hist = IntArray(3 * BINS)
        val l = FloatArray(9)
        var y = stride
        while (y < height - stride) {
            var x = stride
            while (x < width - stride) {
                var k = 0; var mean = 0f
                for (j in -1..1) for (i in -1..1) {
                    val v = argb.get((y + j * stride) * width + x + i * stride)
                    val lum = (0.2126f * (v and 0xFF) + 0.7152f * ((v shr 8) and 0xFF) + 0.0722f * ((v shr 16) and 0xFF)) / 255f
                    l[k++] = lum; mean += lum
                }
                mean /= 9f
                val r = abs(l[0] - 2 * l[1] + l[2] - 2 * l[3] + 4 * l[4] - 2 * l[5] + l[6] - 2 * l[7] + l[8])
                val bin = if (mean < 0.12f) 0 else if (mean < 0.4f) 1 else 2
                hist[bin * BINS + minOf(((r / TOP) * BINS).toInt(), BINS - 1)]++
                x += stride
            }
            y += stride
        }
        return fromHistogram(hist)
    }

    /** Histogram resolution of the filter response, and the response at its top end (0..1 luma units). The GPU fills the same layout. */
    public const val BINS: Int = 2048
    public const val TOP: Float = 0.5f

    /**
     * The noise levels from a histogram of filter responses, [BINS] entries for each of the dark, middle and bright areas in turn: the median
     * response of each, scaled to a standard deviation. A bin with too few pixels takes the mean of the others.
     */
    public fun fromHistogram(hist: IntArray): FloatArray {
        require(hist.size == 3 * BINS) { "need ${3 * BINS} entries" }
        val out = FloatArray(3) { -1f }
        for (b in 0..2) {
            var count = 0; for (i in 0 until BINS) count += hist[b * BINS + i]
            if (count < 200) continue
            var acc = 0; var idx = 0
            while (idx < BINS - 1 && acc + hist[b * BINS + idx] < count / 2) { acc += hist[b * BINS + idx]; idx++ }
            out[b] = (idx + 0.5f) * TOP / BINS / 4.047f   // the filter's output is 6 sigma wide; the median of its magnitude is 0.6745 of that
        }
        val known = out.filter { it >= 0f }
        val fill = if (known.isEmpty()) 0.01f else known.average().toFloat()
        for (b in 0..2) if (out[b] < 0f) out[b] = fill
        return out
    }
}
