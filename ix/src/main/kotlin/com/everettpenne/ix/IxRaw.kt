package com.everettpenne.ix

import kotlin.math.max

/**
 * Reading a RAW frame for the one purpose Ultra HDR needs: how bright each small block of the picture really was, in linear light, before the
 * camera's tone curve. Not a RAW developer: no demosaic, no colour matrix, no lens-shading map. Each block averages many sensor pixels, which is all
 * a gain map (a low-resolution picture of "how much brighter than the SDR version") needs.
 */
public enum class IxCfa(public val redX: Int, public val redY: Int) {
    /** R G / G B, red at the top-left of each 2x2 cell. */
    RGGB(0, 0),
    /** G R / B G. */
    GRBG(1, 0),
    /** G B / R G. */
    GBRG(0, 1),
    /** B G / G R. */
    BGGR(1, 1);

    /** The channel (0 red, 1 green, 2 blue) at pixel ([x], [y]). */
    public fun channelAt(x: Int, y: Int): Int {
        val px = (x and 1) - redX
        val py = (y and 1) - redY
        return when {
            px == 0 && py == 0 -> 0
            px != 0 && py != 0 -> 2
            else -> 1
        }
    }
}

/** A grid of block averages: linear luminance (0 = black, 1 = the sensor's white level, before white balance can push it past 1), and where it clipped. */
public class IxBlocks(
    public val width: Int,
    public val height: Int,
    public val luminance: FloatArray,
    /** True where a meaningful share of the block's pixels reached the sensor's white level: the true brightness is unknown (at least this much). */
    public val clipped: BooleanArray,
)

public object IxRaw {
    /**
     * Averages [raw] (16-bit samples, [rowStride] samples per row, [width] x [height]) into blocks of [block] x [block] sensor pixels.
     *
     * Each sample has its channel's [blackLevel] removed and is scaled by the sensor's [whiteLevel]; the red and blue means are multiplied by the
     * white-balance [gains] (red, green, blue) and the three are combined with Rec. 709 weights into one linear luminance.
     * [clipFraction] of a block's samples at or above [whiteLevel] marks it clipped.
     *
     * [blackLevel] is indexed by position in the 2x2 cell: (x and 1) + 2 * (y and 1).
     */
    public fun blocks(
        raw: ShortArray, width: Int, height: Int, rowStride: Int, cfa: IxCfa,
        blackLevel: IntArray, whiteLevel: Int, gains: FloatArray, block: Int = 8, clipFraction: Float = 0.02f,
    ): IxBlocks {
        require(width > 0 && height > 0 && block > 0) { "bad dimensions" }
        require(rowStride >= width) { "rowStride $rowStride is narrower than width $width" }
        require(raw.size >= rowStride * (height - 1) + width) { "raw has ${raw.size} samples, needs at least ${rowStride * (height - 1) + width}" }
        require(blackLevel.size == 4 && gains.size == 3) { "need 4 black levels and 3 gains" }
        require(whiteLevel > blackLevel.max()) { "white level must exceed the black levels" }
        val bw = width / block
        val bh = height / block
        require(bw > 0 && bh > 0) { "image is smaller than one block" }
        val lum = FloatArray(bw * bh)
        val clipped = BooleanArray(bw * bh)
        val sum = DoubleArray(3)
        val count = IntArray(3)
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                sum.fill(0.0); count.fill(0)
                var clippedCount = 0
                val y0 = by * block
                val x0 = bx * block
                for (y in y0 until y0 + block) {
                    val rowBase = y * rowStride
                    for (x in x0 until x0 + block) {
                        val v = raw[rowBase + x].toInt() and 0xFFFF
                        val ch = cfa.channelAt(x, y)
                        val black = blackLevel[(x and 1) + 2 * (y and 1)]
                        if (v >= whiteLevel) clippedCount++
                        sum[ch] += (v - black).coerceAtLeast(0).toDouble() / (whiteLevel - black)
                        count[ch]++
                    }
                }
                val r = (sum[0] / max(count[0], 1)) * gains[0]
                val g = (sum[1] / max(count[1], 1)) * gains[1]
                val b = (sum[2] / max(count[2], 1)) * gains[2]
                val i = by * bw + bx
                lum[i] = (0.2126 * r + 0.7152 * g + 0.0722 * b).toFloat()
                clipped[i] = clippedCount.toFloat() / (block * block) >= clipFraction
            }
        }
        return IxBlocks(bw, bh, lum, clipped)
    }
}
