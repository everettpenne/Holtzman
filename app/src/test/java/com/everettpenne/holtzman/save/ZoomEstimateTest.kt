package com.everettpenne.holtzman.save

import com.everettpenne.holtzman.save.ZoomEstimate.Luma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class ZoomEstimateTest {
    /** A smooth random picture with detail at several scales, [w] x [h]. */
    private fun scene(w: Int, h: Int, seed: Long): Luma {
        val r = Random(seed); val v = FloatArray(w * h)
        repeat(60) {
            val cx = r.nextFloat() * w; val cy = r.nextFloat() * h; val rad = 4f + r.nextFloat() * 40f; val amp = (r.nextFloat() - 0.5f) * 160f
            for (y in 0 until h) for (x in 0 until w) { val d2 = ((x - cx) * (x - cx) + (y - cy) * (y - cy)) / (rad * rad); if (d2 < 9f) v[y * w + x] += amp * Math.exp(-d2.toDouble()).toFloat() }
        }
        return Luma(w, h, FloatArray(w * h) { 128f + v[it] })
    }

    /** What a camera at [zoom] sees of [wide]: its middle 1/zoom, resampled to [w] x [h], moved by [shift] pixels of the wide picture. */
    private fun zoomed(wide: Luma, zoom: Float, w: Int, h: Int, shiftX: Float = 0f, shiftY: Float = 0f): Luma {
        val cw = wide.width / zoom; val ch = wide.height / zoom; val x0 = (wide.width - cw) / 2f + shiftX; val y0 = (wide.height - ch) / 2f + shiftY
        return Luma(w, h, FloatArray(w * h) { wide.at(x0 + ((it % w) + 0.5f) / w * cw, y0 + ((it / w) + 0.5f) / h * ch) })
    }

    @Test fun findsTheZoomOfACentreCrop() {
        val wide = scene(320, 240, 7)
        for (z in listOf(1f, 2f, 3f, 4f, 8f)) {
            val r = ZoomEstimate.estimate(wide, zoomed(wide, z, 160, 120))
            assertEquals("zoom $z", z, r.zoom, z * 0.06f); assertTrue("score ${r.score}", r.score > 0.8f)
            assertEquals(z, ZoomEstimate.settle(r)!!, 0.01f)
        }
    }

    @Test fun toleratesAHandHeldShift() {
        val wide = scene(320, 240, 11)
        val r = ZoomEstimate.estimate(wide, zoomed(wide, 2f, 160, 120, shiftX = 6f, shiftY = -4f))
        assertEquals(2f, r.zoom, 0.2f); assertTrue(r.score > 0.5f)
    }

    @Test fun anUnrelatedPictureIsNotBelieved() {
        val r = ZoomEstimate.estimate(scene(320, 240, 1), scene(160, 120, 2))
        assertNull(ZoomEstimate.settle(r))
    }

    @Test fun settleKeepsAnInBetweenZoomToATenth() {
        assertEquals(1.5f, ZoomEstimate.settle(ZoomEstimate.Result(1.52f, 0.9f))!!, 1e-4f)
        assertEquals(2f, ZoomEstimate.settle(ZoomEstimate.Result(2.07f, 0.9f))!!, 1e-4f)
        assertNotNull(ZoomEstimate.settle(ZoomEstimate.Result(1f, 0.9f)))
        assertEquals(1f, ZoomEstimate.settle(ZoomEstimate.Result(0.884f, 0.99f))!!, 1e-4f)   // a Pixel 1x photo is wider than our 1x: stay at 1x
    }

    @Test fun aPhotoTurnedAQuarterStillMatches() {
        val wide = scene(240, 320, 5)                       // a portrait view
        val shot = ZoomEstimate.turned(zoomed(wide, 2f, 120, 160), true)   // the same scene shot with the phone turned: landscape
        assertTrue(shot.width > shot.height)
        val r = ZoomEstimate.estimateAnyTurn(wide, shot)
        assertEquals(2f, r.zoom, 0.15f); assertTrue(r.score > 0.8f)
    }
}
