package com.everettpenne.ix

import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Tone targets that follow the scene. [IxAutoTone] bends every picture's brightness ranks onto one row of targets (the medians of the Pixel app's
 * pictures), but the Pixel's own pictures do not sit on one row: the middle alone runs from luma 73 to 147 (of 255) between scenes, and the 99th
 * percentile from 190 to 244. A dim, low-contrast overcast scene is rendered much lighter and airier than a contrasty one, and one row left the
 * first kind too dark (observed against 213 same-moment pairs, 2026-11-02). This predicts the row from what the raw burst says about the scene.
 *
 * Features (all from the block-averaged linear luma, black level, white balance and colour matrix applied, 16x16 sensor pixels per block): the log2
 * mean, the spread of log2, the stops from the 5th to the 99th percentile, from the 25th to the 50th and from the 50th to the 95th, and the bright
 * mass in the top third (sky). The ridge weights come from `tools/look/fit_tone.py`
 * (`fit --drop ev`, leave-one-scene-out error 11.9 luma against 18.6 for the fixed row); regenerate them there, do not hand-edit them.
 */
public object IxSceneTone {
    private const val BLOCK = 8
    private const val BLEND = 0.7f   // blocks of 8x8 RGGB quads = 16x16 sensor pixels

    private val MU = floatArrayOf(-4.08625f, 1.47697f, 5.63022f, 2.28115f, 1.04859f, 0.09063f)
    private val SD = floatArrayOf(0.94532f, 0.44190f, 1.68679f, 1.04670f, 0.48211f, 0.07239f)
    private val BASE = floatArrayOf(12.29933f, 27.41893f, 67.94353f, 107.19517f, 146.61636f, 188.35692f, 213.87720f)
    /** One row per feature (lmean, lspread, dr, hi, lo, sky), one column per rank (1, 5, 25, 50, 75, 95, 99 percent): luma of 255 per standard deviation. */
    private val W = arrayOf(
        floatArrayOf(5.18174f, 10.91068f, 20.94454f, 25.35793f, 27.92885f, 27.01029f, 21.31036f),
        floatArrayOf(-9.59162f, -13.48286f, -0.11878f, -3.36162f, 5.30838f, -7.04220f, -20.78339f),
        floatArrayOf(-0.13827f, -1.50508f, 9.86657f, 15.18078f, 6.98806f, 11.62785f, 30.48091f),
        floatArrayOf(4.86570f, 7.71208f, -12.07600f, -16.63144f, -7.89013f, 11.03483f, 8.10607f),
        floatArrayOf(1.05730f, -1.05136f, -16.37997f, -1.18689f, 3.56721f, 4.59187f, 4.09049f),
        floatArrayOf(-0.83900f, -1.14665f, -1.18188f, -1.09146f, 1.16794f, 3.86300f, 3.34771f),
    )

    /** The six features of a mosaic, or null if it is too small or empty to say anything. */
    public fun features(raw: ShortArray, width: Int, height: Int, cfa: IxCfa, black: IntArray, white: Int, wb: FloatArray, ccm: FloatArray): FloatArray? {
        val bw = width / (2 * BLOCK); val bh = height / (2 * BLOCK)
        if (bw < 4 || bh < 4) return null
        // black levels are indexed by raster position in the 2x2 cell, as in the DNG
        val blackMean = (black[0] + black[1] + black[2] + black[3]) / 4f
        val scale = max(white - blackMean, 1f)
        val rx = cfa.redX; val ry = cfa.redY; val bx = 1 - rx; val by = 1 - ry
        fun v(x: Int, y: Int) = max((raw[y * width + x].toInt() and 0xFFFF) - black[(y and 1) * 2 + (x and 1)], 0).toFloat()
        val luma = FloatArray(bw * bh)
        for (j in 0 until bh) for (i in 0 until bw) {
            var r = 0f; var g = 0f; var b = 0f
            for (qy in 0 until BLOCK) for (qx in 0 until BLOCK) {
                val x0 = (i * BLOCK + qx) * 2; val y0 = (j * BLOCK + qy) * 2
                r += v(x0 + rx, y0 + ry); b += v(x0 + bx, y0 + by)
                g += v(x0 + bx, y0 + ry) + v(x0 + rx, y0 + by)
            }
            val n = (BLOCK * BLOCK).toFloat()
            val rr = r / n * wb[0] / scale; val gg = g / (2f * n) * wb[1] / scale; val bb = b / n * wb[2] / scale
            val y = (0.2126f * (ccm[0] * rr + ccm[1] * gg + ccm[2] * bb) + 0.7152f * (ccm[3] * rr + ccm[4] * gg + ccm[5] * bb) + 0.0722f * (ccm[6] * rr + ccm[7] * gg + ccm[8] * bb))
            luma[j * bw + i] = max(y, 0f)
        }
        fun log2(x: Float) = (ln(max(x, 1e-5f).toDouble()) / ln(2.0)).toFloat()
        val sorted = luma.sortedArray()
        fun pct(p: Float) = sorted[min(sorted.size - 1, (p / 100f * (sorted.size - 1)).toInt())]
        var sum = 0.0; for (y in luma) sum += y
        val mean = max((sum / luma.size).toFloat(), 1e-5f)
        var m = 0.0; var m2 = 0.0; for (y in luma) { val l = log2(y).toDouble(); m += l; m2 += l * l }
        m /= luma.size; m2 /= luma.size
        val p5 = pct(5f); val p25 = pct(25f); val p50 = pct(50f); val p75 = pct(75f); val p95 = pct(95f); val p99 = pct(99f)
        val topRows = max(bh / 3, 1); val p90 = pct(90f)
        var sky = 0; for (j in 0 until topRows) for (i in 0 until bw) if (luma[j * bw + i] > p90) sky++
        return floatArrayOf(
            log2(mean), sqrt(max(m2 - m * m, 0.0)).toFloat(), log2(p99) - log2(p5), log2(p95) - log2(p50), log2(p50) - log2(p25), sky.toFloat() / (topRows * bw),
        )
    }

    /**
     * The [IxAutoTone] target row (display brightness 0..1 at [IxAutoTone.RANKS]) predicted for a picture with these [features]. Held inside the range the
     * Pixel app's pictures actually occupy and always rising, so a strange scene cannot ask for a nonsensical row; the ends stay 0 and 1.
     */
    public fun targets(features: FloatArray): FloatArray {
        val out = FloatArray(IxAutoTone.RANKS.size)
        var prev = 0f
        for (k in 0 until 7) {
            var y = BASE[k]
            for (f in 0 until 6) y += W[f][k] * ((features[f] - MU[f]) / SD[f]).coerceIn(-2.5f, 2.5f)
            // Shrunk toward the fixed row: the fit is linear and extrapolates badly at the extremes. A contrasty dim bench scene (dynamic range 2.2 sd above
            // the pairs) was predicted a median of 83 and a 25th percentile of 43 where the Pixel app rendered 104 and 67, so the shadows came out dark and
            // blocky. At 0.7 the held-out error is 12.1 against 11.9 for the pure fit and 18.6 for the fixed row.
            y = BLEND * y + (1f - BLEND) * IxAutoTone.TARGETS[k + 1] * 255f
            val t = (y / 255f).coerceIn(0.01f, 0.97f)
            out[k + 1] = max(t, prev + 0.01f).also { prev = it }
        }
        out[0] = 0f; out[7] = 1f
        return out
    }
}
