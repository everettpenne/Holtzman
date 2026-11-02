package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IxHueChromaTest {
    @Test fun neutralsAndBlueAreLeftAlone() {
        assertEquals(1f, IxLook.hueScale(0.5f, 0.5f, 0.5f, 1f), 1e-6f)
        assertEquals(1f, IxLook.hueScale(0.1f, 0.3f, 0.9f, 1f), 0.05f)
    }
    @Test fun skinAndYellowAreCalmed() {
        assertTrue(IxLook.hueScale(0.9f, 0.68f, 0.52f, 1f) < 0.75f)   // skin
        assertTrue(IxLook.hueScale(0.9f, 0.85f, 0.2f, 1f) < 0.7f)     // yellow
    }
    @Test fun nearBlackAndWhiteAreNotJudged() {
        assertEquals(1f, IxLook.hueScale(0.03f, 0.01f, 0.0f, 1f), 1e-6f)
        assertEquals(1f, IxLook.hueScale(1f, 1f, 0.97f, 1f), 0.01f)
    }
    @Test fun zeroStrengthChangesNothing() {
        assertEquals(1f, IxLook.hueScale(0.9f, 0.68f, 0.52f, 0f), 1e-6f)
    }
    @Test fun applyReducesChromaOfOrangeOnly() {
        val look = IxLook(hueChroma = 1f); val c = floatArrayOf(0.9f, 0.6f, 0.3f); val y = 0.2126f * 0.9f + 0.7152f * 0.6f + 0.0722f * 0.3f
        look.apply(c); assertTrue(c[0] - y < 0.9f - y)
    }
}
