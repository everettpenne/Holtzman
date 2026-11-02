package com.everettpenne.ix

import kotlin.math.abs
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
    /** Local tone mapping (GPU only), 0 = off, 1 = full: shadows are lifted and highlights eased by the brightness of the neighbourhood, not the pixel alone. */
    public val localTone: Float = 0f,
    /** Film grain added at the very end, in display levels of 255 at its strongest (0 = none); it hides the plastic look heavy denoising leaves. */
    public val grain: Float = 0f,
    /** Adaptive tone (GPU only), 0 = off: the tone curve is chosen per picture from its own brightness distribution, see [IxAutoTone]. Replaces [curve]. */
    public val autoTone: Float = 0f,
    /** Exposure compensation in stops (adaptive tone only): the brightness the picture is brought to moves with it, see [IxAutoTone.targetsFor]. */
    public val exposureEv: Float = 0f,
    /** -1..1, 0 = none (adaptive tone only): darkens or lifts the shadows without moving the highlights, see [IxAutoTone.shadows]. */
    public val shadows: Float = 0f,
    /** 0 = off, 1 = full: colour is scaled by hue (see [HUE_CHROMA]), toward the Pixel camera app's calmer oranges, yellows and greens. */
    public val hueChroma: Float = 0f,
) {
    init { require(curve == null || curve.size == CURVE_X.size) { "the curve needs ${CURVE_X.size} points" } }

    public companion object {
        public val NEUTRAL: IxLook = IxLook()
        /**
         * Chroma multiplier at hue angles 0, 30, ... 330 degrees (the angle of Cr over Cb of the BT.709 display colour: blue 355, magenta 50, red 103, orange and
         * skin about 125, yellow 175, green 230, cyan 283), linear between. From the 52 registered Pixel pairs (docs/colour-fit.md): in flat areas the Pixel
         * app's chroma is about 0.7x ours overall, 0.6x in orange, yellow and yellow-green (skin reads red and harsh in ours, paler and peach in the Pixel's),
         * 0.7-0.8x in violet and magenta, 1.0x in blue. The look's own saturation of 0.9 is part of that, so these are the multipliers still needed.
         */
        /**
         * Muted-colour multipliers, by hue as above. History: the flat-area fit gave (1.0, 0.88, 0.8, 0.78, 0.66, 0.62, 0.62, 0.68, 0.76, 0.9, 0.97, 1.0); against
         * the Pixel app on the workshop scene (2026-11-02, phone) that made a muted wooden worktop chroma 19 to the Pixel's 33, and a first softening to ~0.85
         * still gave 23. The worktop and the Pixel's sit at the same hue (140-144 degrees); the Pixel's has 1.7x the chroma. Skin sits at the same hue and
         * chroma and the flat-area fit says the Pixel renders it paler, so muted orange is warmed here (1.35 overshot to 37 against 30, so 1.25) and skin is protected inside a detected face instead
         * ([SKIN_CHROMA], `protectSkin`).
         */
        public val HUE_CHROMA: FloatArray = floatArrayOf(1.0f, 0.92f, 0.88f, 0.9f, 1.25f, 1.25f, 1.2f, 1.0f, 0.9f, 0.95f, 1.0f, 1.0f)
        /**
         * Vivid-colour multipliers, by hue as above. A single boost of 1.3 then 1.4 brought the workshop panels to orange 81 / Pixel 76, red board 52 / 52, teal
         * 26 / 25, but yellow only 52 / 68: the yellow panel has the Pixel's hue (166 against 167 degrees) at about 0.73 of its chroma, and sits partly in the
         * muted-to-vivid ramp, so yellow gets more.
         */
        public val HUE_VIVID: FloatArray = floatArrayOf(1.4f, 1.4f, 1.4f, 1.4f, 1.4f, 1.75f, 1.75f, 1.5f, 1.4f, 1.4f, 1.4f, 1.4f)
        /** The most chroma skin-hued colour (100-170 degrees) may get inside a detected face: the flat-area fit's paler, peach skin. */
        public const val SKIN_CHROMA: Float = 0.85f
        /**
         * The chroma multiplier for display colour [rgb] at [strength] (0..1): 1 near black and white, where colour is not judged. [protectSkin] is true inside a
         * detected face (the GPU renderer knows it; the CPU fallback does not).
         */
        public fun hueScale(r: Float, g: Float, b: Float, strength: Float, protectSkin: Boolean = false): Float {
            val y = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val cb = (b - y) / 1.8556f; val cr = (r - y) / 1.5748f
            if (cb * cb + cr * cr < 1e-8f) return 1f
            var deg = Math.toDegrees(Math.atan2(cr.toDouble(), cb.toDouble())).toFloat(); if (deg < 0f) deg += 360f
            val t = deg / 30f; val i = t.toInt() % 12; val f = t - t.toInt()
            val muted = HUE_CHROMA[i] + (HUE_CHROMA[(i + 1) % 12] - HUE_CHROMA[i]) * f
            val vividBoost = HUE_VIVID[i] + (HUE_VIVID[(i + 1) % 12] - HUE_VIVID[i]) * f
            // The flat-area fit came from muted areas (skin, wood, walls). Vivid objects go the other way: on the bench scene the Pixel's orange panel had chroma
            // 81 to our 49 and its yellows 67-74 to our 55-59. Skin sits near display chroma 0.16, vivid paint from 0.26, so the muted multiplier gives way to the
            // vivid one between 0.16 and 0.27.
            val v = ((cb * cb + cr * cr).let { Math.sqrt(it.toDouble()).toFloat() } - 0.16f) / 0.11f
            val vivid = v.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
            var table = muted + (vividBoost - muted) * vivid
            if (protectSkin && deg in 100f..170f) table = minOf(table, SKIN_CHROMA)
            val lo = ((y - 0.04f) / 0.11f).coerceIn(0f, 1f); val hi = ((y - 0.85f) / 0.13f).coerceIn(0f, 1f)
            val w = lo * lo * (3f - 2f * lo) * (1f - hi * hi * (3f - 2f * hi))
            return 1f + (table - 1f) * strength * w
        }
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
        for (i in 0..2) rgb[i] = (l + (rgb[i] - l) * saturation).coerceIn(0f, 1f)
        if (hueChroma > 0f) {
            val s = hueScale(rgb[0], rgb[1], rgb[2], hueChroma); val y = 0.2126f * rgb[0] + 0.7152f * rgb[1] + 0.0722f * rgb[2]
            for (i in 0..2) rgb[i] = (y + (rgb[i] - y) * s).coerceIn(0f, 1f)
        }
        for (i in 0..2) rgb[i] = curved(rgb[i])
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
    /** Smooths the choice between horizontal and vertical interpolation: a small floor on the gradients, in linear units (0..1 is black to white). */
    private const val DEMOSAIC_EPS = 0.02f

    // where the gradient is aggregated from: the neighbouring red/blue sites of the same colour, as (dx, dy, weight/8)
    private val AGG = arrayOf(intArrayOf(-2, 0, 2), intArrayOf(0, 0, 4), intArrayOf(2, 0, 2), intArrayOf(0, -2, 2), intArrayOf(0, 2, 2), intArrayOf(-2, -2, 1), intArrayOf(2, 2, 1), intArrayOf(-2, 2, 1), intArrayOf(2, -2, 1))

    /**
     * Green at a red or blue site (Hamilton-Adams): interpolate along the row and along the column, each corrected by the second difference of the
     * site's own colour, and blend the two by the inverse square of how much the picture changes along each, with the change aggregated over the
     * neighbouring same-colour sites so one noisy pixel does not flip the choice. Tuned and checked against Malvar-He-Cutler on a host: 1.2 to 2
     * dB better on photographs, with and without noise.
     */
    internal fun greenEstimate(at: (Int, Int) -> Float, x: Int, y: Int): Float {
        val c = at(x, y)
        val gh = (at(x - 1, y) + at(x + 1, y)) / 2f + (2f * c - at(x - 2, y) - at(x + 2, y)) / 4f
        val gv = (at(x, y - 1) + at(x, y + 1)) / 2f + (2f * c - at(x, y - 2) - at(x, y + 2)) / 4f
        var dh = 0f; var dv = 0f
        for (t in AGG) {
            val px = x + t[0]; val py = y + t[1]; val cc = at(px, py)
            dh += t[2] * (abs(at(px - 1, py) - at(px + 1, py)) + abs(2f * cc - at(px - 2, py) - at(px + 2, py)))
            dv += t[2] * (abs(at(px, py - 1) - at(px, py + 1)) + abs(2f * cc - at(px, py - 2) - at(px, py + 2)))
        }
        dh /= 8f; dv /= 8f
        val wh = 1f / ((DEMOSAIC_EPS + dh) * (DEMOSAIC_EPS + dh)); val wv = 1f / ((DEMOSAIC_EPS + dv) * (DEMOSAIC_EPS + dv))
        return max((wh * gh + wv * gv) / (wh + wv), 0f)
    }

    /** How far a pixel's raw signal (0 black, 1 the sensor's limit) is into clipping: 0 below 0.92 of the limit, 1 at the limit, smooth between. */
    internal fun smoothClip(raw: Float): Float { val t = ((raw - 0.92f) / 0.08f).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }

    /**
     * How much of its colour a pixel of linear luminance [y] keeps: 0 at [SHADOW_CHROMA_LO] and below, 1 at [SHADOW_CHROMA_HI] and above, smooth between.
     * Root cause of the blocky, blotchy dark areas seen in dataset scenes of 2026-11-02: in deep shadow the signal is at the noise floor, and the colour
     * matrix (blue row 0.01, -1.05, 2.03) turns the independent noise of the three channels into large colour swings, with negative channels clamped to
     * zero afterwards, so shadows came out as saturated purple/green/brown patches that the denoiser then smoothed into flat blobs. Measured on the raw
     * frames of scene_20261102_091456: the darkest 12% of the picture sits 0.3-0.65 counts (of 959) above black, below one count, so its colour is
     * rounding noise, which the exposure and local-tone gain then lifts into view. Where there is no signal there is no colour to keep, so colour fades
     * out toward the pixel's own luminance, judged BEFORE that gain (judged after it, the first version let the patches through).
     */
    internal fun shadowChroma(y: Float): Float { val t = ((y - SHADOW_CHROMA_LO) / (SHADOW_CHROMA_HI - SHADOW_CHROMA_LO)).coerceIn(0f, 1f); return t * t * (3f - 2f * t) }
    internal const val SHADOW_CHROMA_LO: Float = 0.002f
    internal const val SHADOW_CHROMA_HI: Float = 0.012f

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
        // the demosaic reaches 4 rows each way for the green estimate, plus 1 more for the colour differences: 5 rows of margin
        val by0 = max(y0 - 5, 0); val by1 = min(y1 + 5, height)
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
        // green at every site: measured where the sensor has green, estimated elsewhere (directional, see greenAt)
        val green = FloatArray(bandRows * width)
        for (y in by0 until by1) for (x in 0 until width) green[(y - by0) * width + x] = if (s.cfa.channelAt(x, y) == 1) at(x, y) else greenEstimate(::at, x, y)
        fun g(x: Int, y: Int): Float = green[(y.coerceIn(by0, by1 - 1) - by0) * width + x.coerceIn(0, width - 1)]
        val m = s.ccm
        for (y in y0 until y1) for (x in 0 until width) {
            val v = at(x, y)
            val r: Float; val gr: Float; val b: Float
            val c = s.cfa.channelAt(x, y)
            gr = g(x, y)
            if (c == 1) {
                val redInRow = s.cfa.channelAt(x + 1, y) == 0 || s.cfa.channelAt(x - 1, y) == 0
                // the red neighbours of a green site are beside it on a red row and above and below it on a blue row; blue the other way round
                if (redInRow) { r = gr + (at(x - 1, y) - g(x - 1, y) + at(x + 1, y) - g(x + 1, y)) / 2f; b = gr + (at(x, y - 1) - g(x, y - 1) + at(x, y + 1) - g(x, y + 1)) / 2f }
                else { b = gr + (at(x - 1, y) - g(x - 1, y) + at(x + 1, y) - g(x + 1, y)) / 2f; r = gr + (at(x, y - 1) - g(x, y - 1) + at(x, y + 1) - g(x, y + 1)) / 2f }
            } else {
                // the other colour sits on the four diagonals: its colour difference, weighted toward the diagonal along which green changes least
                val d1 = abs(g(x - 1, y - 1) - g(x + 1, y + 1)); val d2 = abs(g(x + 1, y - 1) - g(x - 1, y + 1))
                val k1 = (at(x - 1, y - 1) - g(x - 1, y - 1) + at(x + 1, y + 1) - g(x + 1, y + 1)) / 2f
                val k2 = (at(x + 1, y - 1) - g(x + 1, y - 1) + at(x - 1, y + 1) - g(x - 1, y + 1)) / 2f
                val w1 = 1f / ((DEMOSAIC_EPS + d1) * (DEMOSAIC_EPS + d1)); val w2 = 1f / ((DEMOSAIC_EPS + d2) * (DEMOSAIC_EPS + d2))
                val other = gr + (w1 * k1 + w2 * k2) / (w1 + w2)
                if (c == 0) { r = v; b = other } else { b = v; r = other }
            }
            var wr = max(r, 0f) * s.wb[0]; var wg = max(gr, 0f) * s.wb[1]; var wb = max(b, 0f) * s.wb[2]
            // A lamp or a bright sky clips the sensor, green first. White balance then multiplies red and blue by gains above 1 while the clipped green
            // stays where it clipped, so the blown area turns magenta. Where the raw signal is at or near its limit the colour is no longer known, so it is
            // eased toward white (every channel to the brightest of the three): a clipped lamp is white, as a lamp is.
            val clipMix = smoothClip(max(r, max(gr, b)))
            if (clipMix > 0f) { val top = max(wr, max(wg, wb)); wr += clipMix * (top - wr); wg += clipMix * (top - wg); wb += clipMix * (top - wb) }
            var cr = m[0] * wr + m[1] * wg + m[2] * wb
            var cg = m[3] * wr + m[4] * wg + m[5] * wb
            var cb = m[6] * wr + m[7] * wg + m[8] * wb
            // colour fades with the signal as the sensor measured it, before the exposure gain lifts it (see shadowChroma)
            run { val yl = max(0.2126f * cr + 0.7152f * cg + 0.0722f * cb, 0f); val k = shadowChroma(yl); cr = (yl + (cr - yl) * k) * exposure; cg = (yl + (cg - yl) * k) * exposure; cb = (yl + (cb - yl) * k) * exposure }
            val disp = floatArrayOf(srgbEncode(shoulder(cr)), srgbEncode(shoulder(cg)), srgbEncode(shoulder(cb)))
            s.look.apply(disp)
            val pr = (disp[0] * 255f + 0.5f).toInt(); val pg = (disp[1] * 255f + 0.5f).toInt(); val pb = (disp[2] * 255f + 0.5f).toInt()
            out[(y - y0) * width + x] = (0xFF shl 24) or (pr shl 16) or (pg shl 8) or pb
        }
    }
}
