package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IxSceneToneTest {
    @Test fun averageSceneGivesTheFittedMedians() {
        val t = IxSceneTone.targets(floatArrayOf(-4.08625f, 1.47697f, 5.63022f, 2.28115f, 1.04859f, 0.09063f))
        assertEquals(107.2f / 255f, t[4], 0.002f); assertEquals(0f, t[0], 0f); assertEquals(1f, t[7], 0f)
    }
    @Test fun brighterRawGivesBrighterTargetsAndStaysMonotone() {
        val dim = IxSceneTone.targets(floatArrayOf(-7f, 1f, 4f, 1f, 1f, 0.05f)); val bright = IxSceneTone.targets(floatArrayOf(-2f, 1f, 4f, 1f, 1f, 0.05f))
        assertTrue(bright[4] > dim[4])
        for (t in listOf(dim, bright, IxSceneTone.targets(floatArrayOf(5f, 9f, 30f, 9f, 9f, 1f)))) for (i in 1 until t.size) assertTrue(t[i] > t[i - 1])
    }
    @Test fun uniformMosaicMeasuresAsFlat() {
        val w = 512; val h = 512; val raw = ShortArray(w * h) { 1000 }
        val f = IxSceneTone.features(raw, w, h, IxCfa.GRBG, intArrayOf(64, 64, 64, 64), 1023, floatArrayOf(2f, 1f, 1.5f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))!!
        assertEquals(0f, f[1], 0.01f); assertEquals(0f, f[2], 0.01f)
    }
}
