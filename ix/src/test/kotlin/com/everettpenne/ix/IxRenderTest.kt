package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IxRenderTest {
    private val w = 32; private val h = 32
    private val ident = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private fun settings(wb: FloatArray = floatArrayOf(1f, 1f, 1f), shading: IxShadingMap? = null) =
        IxRenderSettings(IxCfa.RGGB, intArrayOf(64, 64, 64, 64), 1024, wb, ident, shading)

    /** A mosaic of a flat grey scene: every site the same fraction of the range. */
    private fun flat(level: Int) = ShortArray(w * h) { level.toShort() }

    private fun render(raw: ShortArray, s: IxRenderSettings, exposure: Float = 1f): IntArray { val out = IntArray(w * h); IxRender.renderRows(raw, w, h, 0, h, s, exposure, out); return out }
    private fun ch(p: Int, shift: Int) = (p shr shift) and 0xFF

    @Test fun aFlatGreyFrameRendersNeutralGrey() {
        // 18% of the range above black: (0.18 * 960 + 64) = 237
        val out = render(flat(237), settings())
        for (i in listOf(w * 8 + 8, w * 15 + 16, w * 20 + 5)) {
            val p = out[i]; assertEquals(ch(p, 16).toFloat(), ch(p, 8).toFloat(), 1f); assertEquals(ch(p, 8).toFloat(), ch(p, 0).toFloat(), 1f)
            assertEquals("18% linear is about 118/255 in sRGB", 118f, ch(p, 8).toFloat(), 3f)
        }
    }

    @Test fun whiteBalanceGainsScaleTheChannels() {
        val out = render(flat(237), settings(wb = floatArrayOf(2f, 1f, 1f)))
        val p = out[w * 10 + 10]
        assertTrue("red above green above... blue equal green", ch(p, 16) > ch(p, 8) + 20 && kotlin.math.abs(ch(p, 8) - ch(p, 0)) <= 1)
    }

    @Test fun aSmoothGradientSurvivesTheDemosaic() {
        // brightness rises left to right, the same in every channel: a good demosaic reproduces a ramp, not zippers
        val raw = ShortArray(w * h) { i -> (64 + (i % w) * 20).toShort() }
        val out = render(raw, settings())
        for (x in 6 until w - 6) { val a = ch(out[w * 16 + x], 8); val b = ch(out[w * 16 + x + 1], 8); assertTrue("not monotone at $x: $a then $b", b >= a - 1) }
        val row = (6 until w - 6).map { ch(out[w * 17 + it], 16) - ch(out[w * 17 + it], 0) }
        assertTrue("colour fringes in a grey ramp: $row", row.all { kotlin.math.abs(it) <= 6 })
    }

    @Test fun shadingGainBrightensTheCorners() {
        val map = IxShadingMap(2, 2, FloatArray(16) { if (it / 4 == 0) 2f else 1f })   // the top-left grid point wants double
        val dark = render(flat(237), settings()); val fixed = render(flat(237), settings(shading = map))
        assertTrue(ch(fixed[w * 1 + 1], 8) > ch(dark[w * 1 + 1], 8) + 20)
        assertEquals(ch(dark[w * 30 + 30], 8).toFloat(), ch(fixed[w * 30 + 30], 8).toFloat(), 2f)
    }

    @Test fun curvesBehave() {
        assertEquals(0f, IxRender.srgbEncode(0f), 1e-6f); assertEquals(1f, IxRender.srgbEncode(1f), 1e-5f); assertEquals(0.4614f, IxRender.srgbEncode(0.18f), 0.002f)
        assertEquals(0.5f, IxRender.shoulder(0.5f), 1e-6f); assertTrue(IxRender.shoulder(3f) < 1f); assertTrue(IxRender.shoulder(0.9f) < 0.9f)
        var prev = -1f; for (i in 0..200) { val v = IxRender.shoulder(i / 40f); assertTrue(v >= prev); prev = v }
        assertEquals(IxRender.shoulder(0.75f - 1e-3f) - IxRender.shoulder(0.75f - 2e-3f), IxRender.shoulder(0.75f + 1e-3f) - IxRender.shoulder(0.75f), 2e-3f)
    }

    @Test fun autoExposureLiftsADarkFrameAndLeavesABrightOne() {
        assertTrue(IxRender.autoExposure(flat(100), w, h, settings()) > 2f)       // ~4% of the range
        assertEquals(1f, IxRender.autoExposure(flat(900), w, h, settings()), 1e-6f)
    }

    @Test fun bandsAgreeWithTheWholeFrame() {
        val raw = ShortArray(w * h) { i -> (64 + ((i * 37) % 500)).toShort() }
        val whole = render(raw, settings())
        val top = IntArray(16 * w); val bottom = IntArray(16 * w)
        IxRender.renderRows(raw, w, h, 0, 16, settings(), 1f, top); IxRender.renderRows(raw, w, h, 16, 32, settings(), 1f, bottom)
        for (i in 0 until 16 * w) { assertEquals(whole[i], top[i]); assertEquals(whole[16 * w + i], bottom[i]) }
    }

    @Test fun badInputIsRefused() {
        for (bad in listOf<() -> Unit>({ IxRender.renderRows(flat(200), w, h, 5, 5, settings(), 1f, IntArray(w * h)) }, { IxRender.renderRows(ShortArray(4), w, h, 0, 4, settings(), 1f, IntArray(w * h)) }, { IxShadingMap(1, 2, FloatArray(8)) })) {
            try { bad(); error("should have been refused") } catch (_: IllegalArgumentException) {}
        }
    }
}
