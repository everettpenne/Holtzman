package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IxHueChromaTest {
    @Test fun neutralsAndBlueAreNotCalmed() {
        assertEquals(1f, IxLook.hueScale(0.5f, 0.5f, 0.5f, 1f), 1e-6f)
        assertTrue(IxLook.hueScale(0.1f, 0.3f, 0.9f, 1f) >= 0.99f)
    }
    @Test fun mutedWoodIsWarmedButSkinInAFaceIsKeptCalm() {
        val wood = IxLook.hueScale(0.506f, 0.416f, 0.294f, 1f)   // the workshop worktop as we rendered it
        assertTrue("wood $wood", wood > 1.2f)
        assertTrue(IxLook.hueScale(0.9f, 0.68f, 0.52f, 1f, protectSkin = true) <= IxLook.SKIN_CHROMA + 1e-6f)
    }
    @Test fun vividYellowGetsMoreThanOtherVividColours() {
        val yellow = IxLook.hueScale(0.9f, 0.85f, 0.2f, 1f); val orange = IxLook.hueScale(0.85f, 0.4f, 0.1f, 1f)
        assertTrue("yellow $yellow orange $orange", yellow > orange && orange > 1.3f)
    }
    @Test fun skinProtectionLeavesOtherHuesAlone() {
        assertEquals(IxLook.hueScale(0.1f, 0.3f, 0.9f, 1f), IxLook.hueScale(0.1f, 0.3f, 0.9f, 1f, protectSkin = true), 1e-6f)
    }
    @Test fun nearBlackAndWhiteAreNotJudged() {
        assertEquals(1f, IxLook.hueScale(0.03f, 0.01f, 0.0f, 1f), 1e-6f)
        assertEquals(1f, IxLook.hueScale(1f, 1f, 0.97f, 1f), 0.01f)
    }
    @Test fun zeroStrengthChangesNothing() {
        assertEquals(1f, IxLook.hueScale(0.9f, 0.68f, 0.52f, 0f), 1e-6f)
    }
}
