package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IxAutoToneTest {
    private fun histogramOf(values: (Int) -> Int): IntArray { val h = IntArray(256); for (i in 0 until 100000) h[values(i).coerceIn(0, 255)]++; return h }
    private fun at(curve: FloatArray, level: Int): Float {
        val x = level / 255f; val xs = IxLook.CURVE_X
        for (i in 1 until xs.size) if (x <= xs[i]) { val t = (x - xs[i - 1]) / (xs[i] - xs[i - 1]); return (curve[i - 1] + (curve[i] - curve[i - 1]) * t) * 255f }
        return curve.last() * 255f
    }

    @Test fun aDarkPictureIsBroughtUpTowardTheTargetMedian() {
        // everything between levels 5 and 70, evenly: the median sits near 37
        val c = IxAutoTone.curve(histogramOf { 5 + it % 66 })
        assertTrue("curve at the median ${at(c, 37)}", at(c, 37) > 60f)
    }

    @Test fun aBrightPictureIsEasedDown() {
        val c = IxAutoTone.curve(histogramOf { 120 + it % 136 })
        assertTrue("curve at the median ${at(c, 188)}", at(c, 188) < 150f)
    }

    @Test fun theCurveIsMonotoneAndWithinTheSlopeLimits() {
        val c = IxAutoTone.curve(histogramOf { (it % 7) * 3 })   // a nearly black, almost flat picture: the extreme case
        val xs = IxLook.CURVE_X
        for (i in 1 until c.size) {
            val slope = (c[i] - c[i - 1]) / (xs[i] - xs[i - 1])
            assertTrue("slope $slope at knot $i", slope >= 0.449f && slope <= 2.001f)
        }
    }

    @Test fun strengthZeroLeavesThePictureAlone() {
        val c = IxAutoTone.curve(histogramOf { it % 256 }, strength = 0f, minSlope = 0f, maxSlope = 10f)
        for (i in c.indices) assertEquals(IxLook.CURVE_X[i], c[i], 1e-4f)
    }
}
