package com.everettpenne.holtzman.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProControlsTest {
    @Test fun isoRunsFromTheLowestToTheHighestStopTheSensorOffers() {
        assertEquals(50, isoFromT(0f, 29..7213)); assertEquals(3200, isoFromT(1f, 29..7213))
        assertEquals(100, isoFromT(0f, 100..1600)); assertEquals(1600, isoFromT(1f, 100..1600))
        var last = 0; for (i in 0..20) { val v = isoFromT(i / 20f, 29..7213); assertTrue("not decreasing at $i", v >= last); last = v }
    }

    @Test fun shutterRunsFromAFastTimeToAQuarterSecondAndNeverLeavesTheSensorsRange() {
        val r = 56_968L..16_000_000_000L
        assertEquals(250_000L, shutterNsFromT(0f, r)); assertEquals(250_000_000L, shutterNsFromT(1f, r))
        assertEquals(1_000_000_000L / 250, shutterNsFromT(0.5f - 0.5f + 12f / 30f, r))
        for (i in 0..30) assertTrue(shutterNsFromT(i / 30f, 1_000_000L..100_000_000L) in 1_000_000L..100_000_000L)
    }

    @Test fun labelsReadTheWayPhotographersWriteThem() {
        assertEquals("1/250", shutterLabel(4_000_000L)); assertEquals("1/4000", shutterLabel(250_000L)); assertEquals("0.3 s", shutterLabel(300_000_000L)); assertEquals("1/4", shutterLabel(250_000_000L))
        assertEquals("∞", focusLabel(0f)); assertEquals("2.0 m", focusLabel(0.5f)); assertEquals("20 cm", focusLabel(5f))
    }
}
