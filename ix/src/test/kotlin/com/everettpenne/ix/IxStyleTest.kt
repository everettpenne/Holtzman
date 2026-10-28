package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IxStyleTest {
    @Test
    fun presetIdsAreUnique() {
        val ids = IxStylePreset.entries.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun strengthZeroIsIdentityForEveryPreset() {
        IxStylePreset.entries.forEach { preset ->
            val out = IxStyle(preset, strength = 0f).matrix().apply(IxColor(0.3f, 0.5f, 0.7f))
            assertEquals(0.3f, out.r, 1e-6f)
            assertEquals(0.5f, out.g, 1e-6f)
            assertEquals(0.7f, out.b, 1e-6f)
        }
    }

    @Test
    fun warmPushesRedAheadOfBlue() {
        val out = IxStylePresets.matrix(IxStylePreset.WARM).apply(IxColor(0.5f, 0.5f, 0.5f))
        assertTrue("warm should raise red above blue, was ${out.r} vs ${out.b}", out.r > out.b)
    }

    @Test
    fun coolPushesBlueAheadOfRed() {
        val out = IxStylePresets.matrix(IxStylePreset.COOL).apply(IxColor(0.5f, 0.5f, 0.5f))
        assertTrue("cool should raise blue above red, was ${out.b} vs ${out.r}", out.b > out.r)
    }

    @Test
    fun strengthMustBeInRange() {
        assertThrows(IllegalArgumentException::class.java) {
            IxStyle(IxStylePreset.WARM, strength = 1.01f)
        }
    }
}
