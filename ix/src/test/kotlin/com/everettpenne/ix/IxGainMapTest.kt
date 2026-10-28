package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IxGainMapTest {
    private val black = intArrayOf(64, 64, 64, 64)

    /** A flat RAW frame of [value] everywhere. */
    private fun flat(w: Int, h: Int, value: Int) = ShortArray(w * h) { value.toShort() }

    @Test fun cfaChannelsFollowThePattern() {
        assertEquals(listOf(0, 1, 1, 2), listOf(IxCfa.RGGB.channelAt(0, 0), IxCfa.RGGB.channelAt(1, 0), IxCfa.RGGB.channelAt(0, 1), IxCfa.RGGB.channelAt(1, 1)))
        assertEquals(listOf(1, 0, 2, 1), listOf(IxCfa.GRBG.channelAt(0, 0), IxCfa.GRBG.channelAt(1, 0), IxCfa.GRBG.channelAt(0, 1), IxCfa.GRBG.channelAt(1, 1)))
        assertEquals(listOf(2, 1, 1, 0), listOf(IxCfa.BGGR.channelAt(0, 0), IxCfa.BGGR.channelAt(1, 0), IxCfa.BGGR.channelAt(0, 1), IxCfa.BGGR.channelAt(1, 1)))
        assertEquals(listOf(1, 2, 0, 1), listOf(IxCfa.GBRG.channelAt(0, 0), IxCfa.GBRG.channelAt(1, 0), IxCfa.GBRG.channelAt(0, 1), IxCfa.GBRG.channelAt(1, 1)))
    }

    @Test fun aFlatFrameIsItsBlackSubtractedFractionOfTheRange() {
        // level 576 with black 64 and white 1024: (576-64)/(1024-64) = 0.5333, neutral gains 1 -> luminance 0.5333 (weights sum to 1)
        val b = IxRaw.blocks(flat(32, 32, 576), 32, 32, 32, IxCfa.RGGB, black, 1024, floatArrayOf(1f, 1f, 1f), block = 8)
        assertEquals(4, b.width); assertEquals(4, b.height)
        b.luminance.forEach { assertEquals(0.5333f, it, 0.001f) }
        assertTrue(b.clipped.none { it })
    }

    @Test fun whiteBalanceGainsWeightRedAndBlue() {
        val raw = flat(16, 16, 576)
        val flat = IxRaw.blocks(raw, 16, 16, 16, IxCfa.RGGB, black, 1024, floatArrayOf(1f, 1f, 1f), 8).luminance[0]
        val warm = IxRaw.blocks(raw, 16, 16, 16, IxCfa.RGGB, black, 1024, floatArrayOf(2f, 1f, 1f), 8).luminance[0]
        assertEquals(flat * (1f + 0.2126f), warm, 0.002f)   // red doubled adds 0.2126 of the luminance again
    }

    @Test fun clippedBlocksAreMarkedAndStrideIsHonoured() {
        val w = 16; val stride = 20
        val raw = ShortArray(stride * 16) { 576 }
        for (y in 0 until 8) for (x in 0 until 8) raw[y * stride + x] = 1023   // top-left block saturated... below white 1024
        for (y in 0 until 8) for (x in 8 until 16) raw[y * stride + x] = 1024  // top-right block clipped
        val b = IxRaw.blocks(raw, w, 16, stride, IxCfa.RGGB, black, 1024, floatArrayOf(1f, 1f, 1f), 8)
        assertEquals(listOf(false, true, false, false), b.clipped.toList())
        assertTrue(b.luminance[0] > b.luminance[2])   // the brighter block reads brighter
    }

    @Test fun badInputIsRefusedClearly() {
        val ok = flat(16, 16, 576)
        for (bad in listOf<() -> Unit>(
            { IxRaw.blocks(ok, 16, 16, 8, IxCfa.RGGB, black, 1024, floatArrayOf(1f, 1f, 1f)) },
            { IxRaw.blocks(ShortArray(10), 16, 16, 16, IxCfa.RGGB, black, 1024, floatArrayOf(1f, 1f, 1f)) },
            { IxRaw.blocks(ok, 16, 16, 16, IxCfa.RGGB, black, 64, floatArrayOf(1f, 1f, 1f)) },
            { IxRaw.blocks(ok, 4, 4, 4, IxCfa.RGGB, black, 1024, floatArrayOf(1f, 1f, 1f), 8) },
        )) { try { bad(); error("should have been refused") } catch (_: IllegalArgumentException) {} }
    }

    // ---- the gain map ----

    private fun grid(w: Int, h: Int, hdr: (Int) -> Float, clipped: (Int) -> Boolean = { false }) =
        IxBlocks(w, h, FloatArray(w * h) { hdr(it) }, BooleanArray(w * h) { clipped(it) })

    @Test fun aSceneWhereNothingWasCompressedNeedsNoGainMap() {
        val n = 20 * 20
        val sdr = FloatArray(n) { 0.2f }
        assertNull(IxGainMap.compute(sdr, grid(20, 20, { 0.4f })))      // same ratio everywhere: median-normalised to 1
    }

    @Test fun brightCellsTheToneCurveCompressedGetAGain() {
        val w = 20; val h = 20; val n = w * h
        // mid-tones: SDR 0.2, RAW-linear 0.1 (ratio 0.5). The right quarter is a bright sky: SDR 0.9 (compressed), RAW 0.9 (ratio 1.0, twice the median)
        val sdr = FloatArray(n) { i -> if (i % w >= 15) 0.9f else 0.2f }
        val hdr = grid(w, h, { i -> if (i % w >= 15) 0.9f else 0.1f })
        val g = IxGainMap.compute(sdr, hdr, maxBoost = 4f)!!
        assertEquals(w, g.width)
        assertEquals(1f, g.gainAt(2, 10), 0.02f)                         // a mid-tone: unchanged
        assertEquals(2f, g.gainAt(18, 10), 0.1f)                         // the sky: the two-fold the ratio asked for
    }

    @Test fun gainIsCappedAtTheMaximumBoost() {
        val w = 20; val h = 20; val n = w * h
        val sdr = FloatArray(n) { i -> if (i % w >= 15) 0.9f else 0.2f }
        val g = IxGainMap.compute(sdr, grid(w, h, { i -> if (i % w >= 15) 90f else 0.1f }), maxBoost = 4f)!!
        assertEquals(4f, g.gainAt(18, 10), 0.05f)
    }

    @Test fun clippedCellsKeepNoGainAndNoMidtonesMeansNoMap() {
        val w = 20; val h = 20; val n = w * h
        val sdr = FloatArray(n) { i -> if (i % w >= 15) 0.9f else 0.2f }
        val hdr = grid(w, h, { i -> if (i % w >= 15) 0.9f else 0.1f }, clipped = { i -> i % w >= 15 })
        assertNull("only clipped highlights: nothing to recover", IxGainMap.compute(sdr, hdr))
        assertNull("a frame with no mid-tones to calibrate against", IxGainMap.compute(FloatArray(n) { 0.9f }, grid(w, h, { 0.9f })))
    }

    @Test fun mismatchedGridsAreRefused() {
        try { IxGainMap.compute(FloatArray(5), grid(2, 2, { 0.1f })); error("should have been refused") } catch (_: IllegalArgumentException) {}
    }

    @Test fun theKneeKeepsMidtonesAndFadesInTheHighlights() {
        assertEquals(0.0, IxGainMap.knee(0.1f), 1e-9); assertEquals(0.0, IxGainMap.knee(0.3f), 1e-9)
        assertEquals(1.0, IxGainMap.knee(0.7f), 1e-9); assertEquals(1.0, IxGainMap.knee(1f), 1e-9)
        val mid = IxGainMap.knee(0.5f); assertTrue(mid > 0.4 && mid < 0.6)
        // a cell at SDR 0.5 whose ratio asked for 4x gets only part of it
        val w = 20; val h = 20; val n = w * h
        val sdr = FloatArray(n) { i -> if (i % w >= 15) 0.5f else 0.2f }
        val g = IxGainMap.compute(sdr, grid(w, h, { i -> if (i % w >= 15) 0.5f else 0.02f }), maxBoost = 8f)!!
        // ratio there is 0.5/0.5 = 1 against a mid-tone median of 0.1: 10x -> capped at 8; the knee halves it in the log domain: 8^0.5 = 2.83
        assertEquals(2.83f, g.gainAt(18, 10), 0.25f)
    }
}
