package com.everettpenne.ix

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The lens-shading correction a RAW frame needs: the lens lets less light reach the corners, and the camera reports how much to multiply each place
 * by, as a coarse grid with one gain per Bayer channel. [gains] is row-major, four floats per grid point in the order red, green (on red rows),
 * green (on blue rows), blue, as Camera2 reports it.
 */
public class IxShadingMap(public val cols: Int, public val rows: Int, public val gains: FloatArray) {
    init { require(cols >= 2 && rows >= 2 && gains.size == cols * rows * 4) { "a shading map needs at least 2x2 points and 4 gains each" } }

    /** The gain for [channel] (0 red, 1 green on red rows, 2 green on blue rows, 3 blue) at the picture position ([u], [v]) in 0..1, bilinear between grid points. */
    public fun gain(channel: Int, u: Float, v: Float): Float {
        val fx = u.coerceIn(0f, 1f) * (cols - 1); val fy = v.coerceIn(0f, 1f) * (rows - 1)
        val x0 = min(fx.toInt(), cols - 2); val y0 = min(fy.toInt(), rows - 2)
        val tx = fx - x0; val ty = fy - y0
        fun g(x: Int, y: Int) = gains[(y * cols + x) * 4 + channel]
        return (g(x0, y0) * (1 - tx) + g(x0 + 1, y0) * tx) * (1 - ty) + (g(x0, y0 + 1) * (1 - tx) + g(x0 + 1, y0 + 1) * tx) * ty
    }
}

/**
 * Everything the renderer needs to turn RAW counts into a picture, all of it from the frame's own metadata: levels, the white-balance gains and the
 * 3x3 colour matrix the camera itself chose (so the colours follow the stock camera's), and the shading map.
 */
/**
 * The finishing look, applied to the display-referred picture after the sRGB curve: a black point (so shadows reach black instead of staying
 * milky), an S-shaped contrast, saturation, and unsharp-mask [sharpen] on luma (the GPU renderer only: it needs the neighbours, and the CPU
 * renderer is the fallback). [NEUTRAL] changes nothing.
 */
public class IxLook(
    /** Display value (0..1) that maps to black. */
    public val blackPoint: Float = 0f,
    /** 0 = none, 1 = a full smoothstep S-curve; blended between. */
    public val contrast: Float = 0f,
    /** 1 = unchanged. */
    public val saturation: Float = 1f,
    /** Unsharp-mask strength on luma, 0 = off. */
    public val sharpen: Float = 0f,
    /** Detail smaller than this (display levels of 255) is left alone, so noise is not sharpened with the picture. */
    public val sharpenCore: Float = 2f,
    /** A tone curve applied last: the output display value (0..1) at each of [CURVE_X], linear between. Null leaves the tones alone. */
    public val curve: FloatArray? = null,
    /**
     * The noise-aware finish (GPU only): [denoise] is how many noise standard deviations apart two neighbours may be and still be averaged (0 = this
     * finish off, use [sharpen] alone); [detail] is the gain on fine detail left after denoising, [detailCore] the noise multiple below which detail is
     * left alone, [clarity] the gain on medium-scale local contrast, [chromaSmoothing] how far colour is pulled toward its edge-aware average.
     */
    public val denoise: Float = 0f,
    public val detail: Float = 0f,
    public val detailCore: Float = 0.5f,
    public val clarity: Float = 0f,
    public val chromaSmoothing: Float = 0.85f,
) {
    init { require(curve == null || curve.size == CURVE_X.size) { "the curve needs ${CURVE_X.size} points" } }

    public companion object {
        public val NEUTRAL: IxLook = IxLook()
        /** Where the tone curve is sampled, in display values of 255. */
        public val CURVE_X: FloatArray = floatArrayOf(0f, 8f, 16f, 32f, 48f, 64f, 96f, 128f, 160f, 192f, 224f, 255f).map { it / 255f }.toFloatArray()
    }

    private fun curved(x: Float): Float {
        val c = curve ?: return x
        if (x <= 0f) return c[0]
        for (i in 1 until CURVE_X.size) if (x <= CURVE_X[i]) { val t = (x - CURVE_X[i - 1]) / (CURVE_X[i] - CURVE_X[i - 1]); return c[i - 1] + (c[i] - c[i - 1]) * t }
        return c.last()
    }

    /** The per-pixel part of the look on a display-referred colour (components 0..1); sharpening is separate. */
    public fun apply(rgb: FloatArray) {
        for (i in 0..2) {
            var c = ((rgb[i] - blackPoint) / (1f - blackPoint)).coerceIn(0f, 1f)
            c += contrast * (c * c * (3f - 2f * c) - c)
            rgb[i] = c
        }
        val l = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
        for (i in 0..2) rgb[i] = curved((l + (rgb[i] - l) * saturation).coerceIn(0f, 1f))
    }
}

public class IxRenderSettings(
    public val cfa: IxCfa,
    public val black: IntArray,
    public val white: Int,
    /** White-balance gains: red, green, blue. */
    public val wb: FloatArray,
    /** Camera RGB (after the gains) to linear sRGB, row-major 3x3. */
    public val ccm: FloatArray,
    public val shading: IxShadingMap? = null,
    /** Extra gain on the linear picture; null asks the renderer to choose one so an average scene lands at middle grey. */
    public val exposure: Float? = null,
    public val look: IxLook = IxLook.NEUTRAL,
) {
    init { require(black.size == 4 && wb.size == 3 && ccm.size == 9) { "need 4 black levels, 3 white-balance gains and a 3x3 matrix" } }
}

public object IxRender {
    // Malvar-He-Cutler 5x5 demosaic kernels (x1/8), as (dx, dy, weight) taps: only the non-zero ones.
    private val G_AT_RB = taps(listOf(0 to -2 to -1.0, 0 to -1 to 2.0, -2 to 0 to -1.0, -1 to 0 to 2.0, 0 to 0 to 4.0, 1 to 0 to 2.0, 2 to 0 to -1.0, 0 to 1 to 2.0, 0 to 2 to -1.0))
    /** R (or B) at a green site whose row holds that colour. */
    private val RB_AT_G_SAMEROW = taps(listOf(0 to -2 to 0.5, -1 to -1 to -1.0, 1 to -1 to -1.0, -2 to 0 to -1.0, -1 to 0 to 4.0, 0 to 0 to 5.0, 1 to 0 to 4.0, 2 to 0 to -1.0, -1 to 1 to -1.0, 1 to 1 to -1.0, 0 to 2 to 0.5))
    /** R (or B) at a green site whose column holds that colour. */
    private val RB_AT_G_SAMECOL = taps(listOf(-2 to 0 to 0.5, -1 to -1 to -1.0, -1 to 1 to -1.0, 0 to -2 to -1.0, 0 to -1 to 4.0, 0 to 0 to 5.0, 0 to 1 to 4.0, 0 to 2 to -1.0, 1 to -1 to -1.0, 1 to 1 to -1.0, 2 to 0 to 0.5))
    /** R at a B site, or B at an R site. */
    private val RB_AT_BR = taps(listOf(0 to -2 to -1.5, -1 to -1 to 2.0, 1 to -1 to 2.0, -2 to 0 to -1.5, 0 to 0 to 6.0, 2 to 0 to -1.5, -1 to 1 to 2.0, 1 to 1 to 2.0, 0 to 2 to -1.5))

    /** One tap of a demosaic kernel: the neighbour at ([dx], [dy]) contributes [weight] times its value. */
    public class Tap(public val dx: Int, public val dy: Int, public val weight: Float)

    /** The demosaic kernels by name, for a renderer that runs elsewhere (the GPU) to build the same filters from the same numbers. */
    public val kernels: Map<String, List<Tap>> = mapOf(
        "greenAtRedBlue" to G_AT_RB, "rbAtGreenSameRow" to RB_AT_G_SAMEROW, "rbAtGreenSameCol" to RB_AT_G_SAMECOL, "rbAtBlueRed" to RB_AT_BR,
    ).mapValues { (_, t) -> t.w.indices.map { Tap(t.dx[it], t.dy[it], t.w[it]) } }

    private class Taps(val dx: IntArray, val dy: IntArray, val w: FloatArray)
    private fun taps(list: List<Pair<Pair<Int, Int>, Double>>) = Taps(IntArray(list.size) { list[it].first.first }, IntArray(list.size) { list[it].first.second }, FloatArray(list.size) { (list[it].second / 8.0).toFloat() })

    /** Linear light to the sRGB transfer curve (0..1). */
    public fun srgbEncode(x: Float): Float { val c = x.coerceIn(0f, 1f); return if (c <= 0.0031308f) 12.92f * c else (1.055 * c.toDouble().pow(1.0 / 2.4) - 0.055).toFloat() }

    /** A soft shoulder: linear to 0.75, then a smooth roll-off to 1, so highlights compress instead of clipping flat. Monotone, continuous in slope. */
    public fun shoulder(x: Float): Float = if (x <= 0.75f) max(x, 0f) else 0.75f + 0.25f * (1f - exp(-(x - 0.75f) / 0.25f))

    /** The gain that puts the picture's average at [target] (18% is middle grey), within a sensible range: the mean of every 16th pixel's white-balanced green. */
    public fun autoExposure(raw: ShortArray, width: Int, height: Int, s: IxRenderSettings, target: Float = 0.18f): Float {
        var sum = 0.0; var n = 0
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                // a green site: this one, or its neighbour to the right (every second site on a row is green)
                val gx = if (s.cfa.channelAt(x, y) == 1) x else x + 1
                if (gx < width && s.cfa.channelAt(gx, y) == 1) { sum += max((raw[y * width + gx].toInt() and 0xFFFF) - s.black[(gx and 1) + 2 * (y and 1)], 0).toDouble() / (s.white - s.black[0]); n++ }
                x += 16
            }
            y += 16
        }
        val mean = if (n > 0) sum / n * s.wb[1] else target.toDouble()
        return (target / max(mean, 1e-4)).toFloat().coerceIn(1f, 8f)
    }

    /**
     * Renders rows [y0] until [y1] of [raw] (a [width] x [height] mosaic, tight rows) to packed ARGB in [out] (a row after row, [width] ints each).
     * Black level, shading gain and range scaling; a 5x5 Malvar-He-Cutler demosaic; white balance; the camera's own colour matrix; exposure; a highlight
     * shoulder; the sRGB curve. Rendering in bands keeps the working memory small: a 12-megapixel float picture would not fit in the app's heap.
     */
    public fun renderRows(raw: ShortArray, width: Int, height: Int, y0: Int, y1: Int, s: IxRenderSettings, exposure: Float, out: IntArray) {
        require(y0 in 0 until y1 && y1 <= height) { "bad rows $y0 until $y1 of $height" }
        require(raw.size >= width * height && out.size >= (y1 - y0) * width) { "buffers too small" }
        val by0 = max(y0 - 2, 0); val by1 = min(y1 + 2, height)
        val bandRows = by1 - by0
        val lin = FloatArray(bandRows * width)
        val range = FloatArray(4) { 1f / (s.white - s.black[it]) }
        for (y in by0 until by1) {
            val row = (y - by0) * width
            for (x in 0 until width) {
                val cell = (x and 1) + 2 * (y and 1)
                var v = max((raw[y * width + x].toInt() and 0xFFFF) - s.black[cell], 0) * range[cell]
                s.shading?.let { sh ->
                    // the map's channels are red, green (red rows), green (blue rows), blue: which one this site is
                    val ch = when (s.cfa.channelAt(x, y)) { 0 -> 0; 2 -> 3; else -> if (s.cfa.channelAt(x + 1, y).let { it == 0 } || s.cfa.channelAt(x - 1, y) == 0) 1 else 2 }
                    v *= sh.gain(ch, x.toFloat() / (width - 1), y.toFloat() / (height - 1))
                }
                lin[row + x] = v
            }
        }
        fun at(x: Int, y: Int): Float = lin[(y.coerceIn(by0, by1 - 1) - by0) * width + x.coerceIn(0, width - 1)]
        fun apply(t: Taps, x: Int, y: Int): Float { var a = 0f; for (i in t.w.indices) a += t.w[i] * at(x + t.dx[i], y + t.dy[i]); return a }
        val m = s.ccm
        for (y in y0 until y1) for (x in 0 until width) {
            val v = at(x, y)
            val r: Float; val g: Float; val b: Float
            when (s.cfa.channelAt(x, y)) {
                0 -> { r = v; g = apply(G_AT_RB, x, y); b = apply(RB_AT_BR, x, y) }
                2 -> { b = v; g = apply(G_AT_RB, x, y); r = apply(RB_AT_BR, x, y) }
                else -> {
                    g = v
                    val redInRow = s.cfa.channelAt(x + 1, y) == 0 || s.cfa.channelAt(x - 1, y) == 0
                    r = apply(if (redInRow) RB_AT_G_SAMEROW else RB_AT_G_SAMECOL, x, y)
                    b = apply(if (redInRow) RB_AT_G_SAMECOL else RB_AT_G_SAMEROW, x, y)
                }
            }
            val wr = max(r, 0f) * s.wb[0]; val wg = max(g, 0f) * s.wb[1]; val wb = max(b, 0f) * s.wb[2]
            val cr = (m[0] * wr + m[1] * wg + m[2] * wb) * exposure
            val cg = (m[3] * wr + m[4] * wg + m[5] * wb) * exposure
            val cb = (m[6] * wr + m[7] * wg + m[8] * wb) * exposure
            val disp = floatArrayOf(srgbEncode(shoulder(cr)), srgbEncode(shoulder(cg)), srgbEncode(shoulder(cb)))
            s.look.apply(disp)
            val pr = (disp[0] * 255f + 0.5f).toInt(); val pg = (disp[1] * 255f + 0.5f).toInt(); val pb = (disp[2] * 255f + 0.5f).toInt()
            out[(y - y0) * width + x] = (0xFF shl 24) or (pr shl 16) or (pg shl 8) or pb
        }
    }
}
