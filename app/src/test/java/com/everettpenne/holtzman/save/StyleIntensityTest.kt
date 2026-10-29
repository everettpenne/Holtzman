package com.everettpenne.holtzman.save

import com.everettpenne.holtzman.save.BurstRender.LookPreset
import com.everettpenne.holtzman.save.BurstRender.LookTuning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StyleIntensityTest {
    private val base = BurstRender.LOOK

    @Test fun noIntensityIsTheStandardLookWhateverTheStyle() {
        for (p in LookPreset.entries) {
            val l = BurstRender.look(LookTuning(preset = p, intensity = 0f))
            assertEquals("${p.label} saturation", base.saturation, l.saturation, 1e-6f)
            assertEquals("${p.label} contrast", base.contrast, l.contrast, 1e-6f)
            assertEquals("${p.label} black point", base.blackPoint, l.blackPoint, 1e-6f)
            assertEquals("${p.label} shadows", 0f, l.shadows, 1e-6f)
            assertEquals("${p.label} grain", LookTuning.DEFAULT_GRAIN * LookTuning.GRAIN_LEVELS, l.grain, 1e-5f)
            assertEquals("${p.label} warmth", 1f, LookTuning(preset = p, intensity = 0f).warmthFactor, 1e-6f)
        }
    }

    @Test fun fullIntensityIsTheStyleAndHalfIsMidway() {
        val full = BurstRender.look(LookTuning(preset = LookPreset.DRAMATIC, intensity = 1f))
        val half = BurstRender.look(LookTuning(preset = LookPreset.DRAMATIC, intensity = 0.5f))
        assertEquals(0.82f, full.saturation, 1e-6f); assertEquals(0.78f, full.contrast, 1e-6f)
        assertEquals((base.saturation + 0.82f) / 2f, half.saturation, 1e-6f); assertEquals((base.contrast + 0.78f) / 2f, half.contrast, 1e-6f)
        assertEquals(-9f / 20f, full.shadows, 1e-6f); assertEquals(-4.5f / 20f, half.shadows, 1e-6f)
    }

    @Test fun theUsersOwnControlsAreNotScaledByIntensity() {
        val l = BurstRender.look(LookTuning(preset = LookPreset.WARM, intensity = 0f, shadows = 10f, warmth = 8f, exposureEv = 0.5f))
        assertEquals(10f / 20f, l.shadows, 1e-6f); assertEquals(0.5f, l.exposureEv, 1e-6f)
        assertEquals(1f + 0.006f * 8f, LookTuning(preset = LookPreset.WARM, intensity = 0f, warmth = 8f).warmthFactor, 1e-6f)
        assertEquals(1f + 0.006f * (8f + 10f), LookTuning(preset = LookPreset.WARM, intensity = 1f, warmth = 8f).warmthFactor, 1e-6f)
    }

    @Test fun everyStyleHasAUniqueLabelAndStandardIsFirst() {
        assertEquals(LookPreset.STANDARD, LookPreset.entries.first())
        assertEquals(LookPreset.entries.size, LookPreset.entries.map { it.label }.toSet().size)
        assertTrue(LookPreset.entries.size >= 10)
    }
}

class TextureStyleTest {
    private val base = BurstRender.LOOK
    @Test fun standardTextureChangesNothingAtAnyAmount() {
        val plain = BurstRender.look(BurstRender.LookTuning())
        for (a in listOf(0f, 0.5f, 1f)) {
            val l = BurstRender.look(BurstRender.LookTuning(textureStyle = BurstRender.TextureStyle.STANDARD, textureAmount = a))
            assertEquals(plain.detail, l.detail, 1e-6f); assertEquals(plain.grain, l.grain, 1e-6f); assertEquals(plain.contrast, l.contrast, 1e-6f); assertEquals(plain.shadows, l.shadows, 1e-6f)
        }
    }

    @Test fun softSkinSmoothsAndGrainAddsGrainInProportionToTheAmount() {
        val plain = BurstRender.look(BurstRender.LookTuning())
        val soft = BurstRender.look(BurstRender.LookTuning(textureStyle = BurstRender.TextureStyle.SOFT_SKIN, textureAmount = 1f))
        val softHalf = BurstRender.look(BurstRender.LookTuning(textureStyle = BurstRender.TextureStyle.SOFT_SKIN, textureAmount = 0.5f))
        assertTrue(soft.detail < softHalf.detail && softHalf.detail < plain.detail)
        val grain = BurstRender.look(BurstRender.LookTuning(textureStyle = BurstRender.TextureStyle.GRAIN, textureAmount = 1f))
        val grainHalf = BurstRender.look(BurstRender.LookTuning(textureStyle = BurstRender.TextureStyle.GRAIN, textureAmount = 0.5f))
        assertTrue(grain.grain > grainHalf.grain && grainHalf.grain > plain.grain)
        assertEquals((BurstRender.LookTuning.DEFAULT_GRAIN + 0.8f) * BurstRender.LookTuning.GRAIN_LEVELS, grain.grain, 1e-4f)
    }

    @Test fun glowLowersContrastAndLiftsShadows() {
        val plain = BurstRender.look(BurstRender.LookTuning()); val glow = BurstRender.look(BurstRender.LookTuning(textureStyle = BurstRender.TextureStyle.GLOW, textureAmount = 1f))
        assertEquals(plain.contrast - 0.12f, glow.contrast, 1e-6f); assertEquals(6f / 20f, glow.shadows, 1e-6f)
    }
}
