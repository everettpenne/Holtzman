package com.everettpenne.ix

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * An Ultra HDR gain map: a small single-channel picture of how much brighter each place is in the HDR rendition than in the SDR one. A pixel [data] = v
 * (0..255) means a gain of 2^(log2([maxBoost]) * v / 255); 0 is no gain.
 */
public class IxGainMapResult(
    public val width: Int,
    public val height: Int,
    public val data: ByteArray,
    public val maxBoost: Float,
) {
    /** The gain, as a multiplier (1 = none), at cell ([x], [y]). */
    public fun gainAt(x: Int, y: Int): Float = maxBoost.toDouble().pow((data[y * width + x].toInt() and 0xFF) / 255.0).toFloat()
}

public object IxGainMap {
    /**
     * Builds the gain map from two views of the same scene on the same grid: [sdr] (linear luminance of the camera's own JPEG) and [hdr] (linear
     * luminance of the RAW frame: [IxBlocks.luminance]).
     *
     * What the camera's tone curve compresses (bright skies, lamps) is still there in the RAW; the ratio hdr / sdr is large exactly there. The two are
     * in different units (the RAW is scaled to the sensor's range, the JPEG to the display's), so the ratio is first divided by its own median over the
     * mid-tones, which puts "unchanged" at 1. Then, per cell: gain = clamp(ratio / median, 1, [maxBoost]), faded in over the bright part of the picture ([knee]). A cell whose RAW clipped has lost its true
     * brightness, so it keeps no gain. The map is smoothed with a 3x3 mean so block edges do not show.
     *
     * Returns null when there is nothing worth encoding (no cell gains more than [minUseful], or no mid-tones to calibrate against).
     */
    /** How much of a cell's full gain applies, by how bright the SDR is there: 0 up to 0.3 (linear), 1 from 0.7, smooth between. */
    internal fun knee(sdrLinear: Float): Double {
        val t = ((sdrLinear - 0.3f) / 0.4f).coerceIn(0f, 1f).toDouble()
        return t * t * (3 - 2 * t)
    }

    public fun compute(sdr: FloatArray, hdr: IxBlocks, maxBoost: Float = 4f, minUseful: Float = 1.15f): IxGainMapResult? {
        val n = hdr.width * hdr.height
        require(sdr.size == n && hdr.luminance.size == n && hdr.clipped.size == n) { "sdr, hdr and clipped must share one grid" }
        require(maxBoost > 1f) { "maxBoost must exceed 1" }

        // calibrate: the median ratio over cells of mid-tone SDR brightness that did not clip
        val mid = ArrayList<Float>()
        for (i in 0 until n) {
            val s = sdr[i]
            if (s in 0.05f..0.5f && !hdr.clipped[i] && hdr.luminance[i] > 1e-5f) mid.add(hdr.luminance[i] / s)
        }
        if (mid.size < 16) return null
        mid.sort()
        val median = mid[mid.size / 2]
        if (median <= 0f) return null

        val gain = FloatArray(n)
        var useful = false
        for (i in 0 until n) {
            val s = sdr[i].coerceAtLeast(1e-3f)
            val full = ((hdr.luminance[i] / s) / median).coerceIn(1f, maxBoost)
            // a highlight boost, not a brightening of the whole picture: shadows and mid-tones keep the SDR look, and the gain fades in over the bright part
            gain[i] = if (hdr.clipped[i]) 1f else exp(ln(full.toDouble()) * knee(sdr[i])).toFloat()
            if (gain[i] >= minUseful) useful = true
        }
        if (!useful) return null

        val smooth = FloatArray(n)
        for (y in 0 until hdr.height) for (x in 0 until hdr.width) {
            var sum = 0f; var c = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until hdr.width && yy in 0 until hdr.height) { sum += gain[yy * hdr.width + xx]; c++ }
            }
            smooth[y * hdr.width + x] = sum / c
        }
        val logMax = ln(maxBoost.toDouble())
        val data = ByteArray(n) { i -> (ln(smooth[i].toDouble()) / logMax * 255.0 + 0.5).toInt().coerceIn(0, 255).toByte() }
        return IxGainMapResult(hdr.width, hdr.height, data, maxBoost)
    }
}
