package com.everettpenne.holtzman.save

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * How far a photo is zoomed in, measured against a wide picture of the same scene. The Pixel Camera app writes no zoom into the photo it hands back (only the
 * lens's focal length, which tells the ultrawide from the main lens, not 2x from 1x), so the zoom is found from the pictures: the middle 1/s of the wide picture
 * is compared with the whole zoomed photo for a range of scales s, and the s that matches best is the zoom. The two shots are taken a few seconds apart by hand,
 * so the match allows a small shift, and it reports how good the best match was so a bad one can be ignored.
 */
object ZoomEstimate {
    class Luma(val width: Int, val height: Int, val v: FloatArray) {
        /** Bilinear sample at a position in pixels (clamped to the picture). */
        fun at(x: Float, y: Float): Float {
            val xx = x.coerceIn(0f, width - 1.001f); val yy = y.coerceIn(0f, height - 1.001f)
            val x0 = xx.toInt(); val y0 = yy.toInt(); val fx = xx - x0; val fy = yy - y0
            val i = y0 * width + x0
            return (v[i] * (1 - fx) + v[i + 1] * fx) * (1 - fy) + (v[i + width] * (1 - fx) + v[i + width + 1] * fx) * fy
        }
    }

    class Result(val zoom: Float, val score: Float)

    /** [l] turned a quarter, clockwise or not. */
    fun turned(l: Luma, clockwise: Boolean): Luma {
        val w = l.height; val h = l.width; val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) out[y * w + x] = if (clockwise) l.v[(l.height - 1 - x) * l.width + y] else l.v[x * l.width + (l.width - 1 - y)]
        return Luma(w, h, out)
    }

    /** [estimate], trying [zoomed] as it is and, when its shape differs from [wide]'s (the phone was turned between the shots), turned a quarter each way. */
    fun estimateAnyTurn(wide: Luma, zoomed: Luma, maxZoom: Float = 10f): Result {
        val tries = if ((wide.width >= wide.height) == (zoomed.width >= zoomed.height)) listOf(zoomed) else listOf(turned(zoomed, true), turned(zoomed, false))
        return tries.map { estimate(wide, it, maxZoom) }.maxBy { it.score }
    }

    /** Normalised correlation of [wide]'s middle 1/[s] (shifted by [dx], [dy] in output cells) with [zoomed], on an [n] x [m] grid, over its central part. */
    private fun ncc(wide: Luma, zoomed: Luma, s: Float, dx: Int, dy: Int, n: Int, m: Int): Float {
        val cw = wide.width / s; val ch = wide.height / s
        val x0 = (wide.width - cw) / 2f; val y0 = (wide.height - ch) / 2f
        var sa = 0.0; var sb = 0.0; var saa = 0.0; var sbb = 0.0; var sab = 0.0; var c = 0
        val mx = n / 8; val my = m / 8
        for (j in my until m - my) for (i in mx until n - mx) {
            val a = wide.at(x0 + (i + 0.5f + dx) / n * cw, y0 + (j + 0.5f + dy) / m * ch)
            val b = zoomed.at((i + 0.5f) / n * zoomed.width, (j + 0.5f) / m * zoomed.height)
            sa += a; sb += b; saa += a * a; sbb += b * b; sab += a * b; c++
        }
        val cov = sab - sa * sb / c; val va = saa - sa * sa / c; val vb = sbb - sb * sb / c
        return if (va < 1e-6 || vb < 1e-6) 0f else (cov / sqrt(va * vb)).toFloat()
    }

    /** The zoom (1 or more) at which [zoomed] best matches [wide], searched from 1x to [maxZoom] in steps of about 2%, with its best correlation. */
    fun estimate(wide: Luma, zoomed: Luma, maxZoom: Float = 10f, minZoom: Float = 0.8f): Result {
        val n = 56; val m = 56 * zoomed.height / max(zoomed.width, 1)
        var bestS = 1f; var best = -2f
        var s = minZoom
        while (s <= maxZoom) {
            var local = -2f
            for (dy in -3..3) for (dx in -3..3) local = max(local, ncc(wide, zoomed, s, dx, dy, n, m))
            if (local > best) { best = local; bestS = s }
            s *= 1.02f
        }
        // refine around the best, finer
        var lo = bestS / 1.03f; val hi = min(bestS * 1.03f, maxZoom)
        var rs = lo
        while (rs <= hi) {
            var local = -2f
            for (dy in -3..3) for (dx in -3..3) local = max(local, ncc(wide, zoomed, rs, dx, dy, n, m))
            if (local > best) { best = local; bestS = rs }
            rs *= 1.005f
        }
        return Result(bestS, best)
    }

    /**
     * The zoom to apply: the measured one, never below 1 (the Pixel app's 1x photo is about 13% wider than this viewfinder's 1x, and a zoom under 1 is the other,
     * ultrawide lens), snapped to a whole number of x when within 8% of it; null if the match was poor.
     */
    fun settle(r: Result, minScore: Float = 0.55f): Float? {
        if (r.score < minScore) return null
        val z = max(r.zoom, 1f)
        val whole = Math.round(z).toFloat()
        return if (whole >= 1f && abs(z - whole) / whole < 0.08f) whole else Math.round(z * 10f) / 10f.toFloat()
    }
}
