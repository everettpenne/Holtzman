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

    @Test fun aFaceLeftInTheShadowsIsLiftedToTheFloor() {
        val base = IxLook.CURVE_X.copyOf()   // identity
        // 0.15 is as dark as a face can be and still reach the floor (0.27) within the curve's slope limit of 2
        val c = IxAutoTone.protectFace(base, faceLuma = 0.15f)
        assertTrue("face at ${at(c, (0.15f * 255).toInt())}", at(c, (0.15f * 255).toInt()) >= 0.27f * 255f - 6f)
        assertTrue("far highlights barely move: ${at(c, 230)}", Math.abs(at(c, 230) - 230f) < 12f)
    }

    @Test fun aFaceAlreadyInRangeLeavesTheCurveAlone() {
        val base = IxAutoTone.curve(histogramOf { 20 + it % 200 })
        val c = IxAutoTone.protectFace(base, faceLuma = 0.45f)
        for (i in base.indices) assertEquals(base[i], c[i], 1e-6f)
    }

    @Test fun aBlownFaceIsEasedDownAndTheCurveStaysMonotone() {
        val c = IxAutoTone.protectFace(IxLook.CURVE_X.copyOf(), faceLuma = 0.92f)
        assertTrue("face at ${at(c, 235)}", at(c, 235) <= 0.72f * 255f + 8f)
        for (i in 1 until c.size) assertTrue("knot $i", c[i] > c[i - 1])
    }

    @Test fun exposureCompensationMovesTheTargetsTheRightWayAndKeepsTheirOrder() {
        val up = IxAutoTone.targetsFor(1f); val down = IxAutoTone.targetsFor(-1f); val base = IxAutoTone.TARGETS
        assertEquals(0f, up.first(), 0f); assertEquals(1f, up.last(), 0f)
        for (k in 1 until base.size - 1) { assertTrue("brighter at $k", up[k] > base[k]); assertTrue("darker at $k", down[k] < base[k]) }
        for (t in listOf(up, down)) for (k in 1 until t.size) assertTrue("ordered at $k", t[k] >= t[k - 1])
        assertTrue("no target reaches white", up[up.size - 2] <= 0.985f)
        assertTrue("zero stops changes nothing", IxAutoTone.targetsFor(0f).contentEquals(base))
    }

    @Test fun shadowsLiftTheLowEndOfTheCurveAndLeaveTheTopAlone() {
        val flat = IxLook.CURVE_X.copyOf()
        val lifted = IxAutoTone.shadows(flat, 1f); val deepened = IxAutoTone.shadows(flat, -1f)
        assertTrue("lifted at 48: ${at(lifted, 48)}", at(lifted, 48) > at(flat, 48) + 8f)
        assertTrue("deepened at 48: ${at(deepened, 48)}", at(deepened, 48) < at(flat, 48) - 4f)
        assertEquals(at(flat, 224), at(lifted, 224), 3f); assertEquals(1f, lifted.last(), 1e-6f); assertEquals(0f, lifted.first(), 0f)
        for (c in listOf(lifted, deepened)) for (i in 1 until c.size) assertTrue("knot $i", c[i] > c[i - 1])
        assertTrue("zero changes nothing", IxAutoTone.shadows(flat, 0f).contentEquals(flat))
    }
}
