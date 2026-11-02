package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IxHueChromaTest {
    @Test fun neutralsAndBlueAreLeftAlone() {
        assertEquals(1f, IxLook.hueScale(0.5f, 0.5f, 0.5f, 1f), 1e-6f)
        assertTrue(IxLook.hueScale(0.1f, 0.3f, 0.9f, 1f) >= 0.99f)
    }
    @Test fun mutedSkinAndYellowAreCalmed() {
        assertTrue(IxLook.hueScale(0.9f, 0.68f, 0.52f, 1f) < 0.9f)    // skin
        assertTrue(IxLook.hueScale(0.75f, 0.72f, 0.5f, 1f) < 0.9f)    // muted yellow
    }
    @Test fun vividColoursAreBoostedNotCalmed() {
        assertTrue(IxLook.hueScale(0.9f, 0.85f, 0.2f, 1f) > 1.05f)    // vivid yellow
        assertTrue(IxLook.hueScale(0.85f, 0.4f, 0.1f, 1f) > 1.05f)    // vivid orange
    }
    @Test fun nearBlackAndWhiteAreNotJudged() {
        assertEquals(1f, IxLook.hueScale(0.03f, 0.01f, 0.0f, 1f), 1e-6f)
        assertEquals(1f, IxLook.hueScale(1f, 1f, 0.97f, 1f), 0.01f)
    }
    @Test fun zeroStrengthChangesNothing() {
        assertEquals(1f, IxLook.hueScale(0.9f, 0.68f, 0.52f, 0f), 1e-6f)
    }
    @Test fun applyReducesChromaOfMutedOrangeOnly() {
        val look = IxLook(hueChroma = 1f); val c = floatArrayOf(0.8f, 0.68f, 0.55f); val y = 0.2126f * 0.8f + 0.7152f * 0.68f + 0.0722f * 0.55f
        look.apply(c); assertTrue(c[0] - y < 0.8f - y)
    }
}
