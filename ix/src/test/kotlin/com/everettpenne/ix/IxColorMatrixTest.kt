package com.everettpenne.ix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IxColorMatrixTest {
    @Test
    fun identityLeavesAColourAlone() {
        val colour = IxColor(0.2f, 0.4f, 0.6f, 0.8f)
        val out = IxColorMatrix.IDENTITY.apply(colour)
        assertEquals(colour.r, out.r, 1e-6f)
        assertEquals(colour.g, out.g, 1e-6f)
        assertEquals(colour.b, out.b, 1e-6f)
        assertEquals(colour.a, out.a, 1e-6f)
    }

    @Test
    fun blendAtZeroIsIdentityAndAtOneIsThePreset() {
        val warm = IxStylePresets.matrix(IxStylePreset.WARM)
        assertArrayEquals(IxColorMatrix.IDENTITY.toFloatArray(), warm.blend(0f).toFloatArray(), 1e-6f)
        assertArrayEquals(warm.toFloatArray(), warm.blend(1f).toFloatArray(), 1e-6f)
    }

    @Test
    fun saturationZeroTurnsAColourGrey() {
        val grey = IxColorMath.saturation(0f).apply(IxColor(0.8f, 0.2f, 0.2f))
        assertEquals(grey.r, grey.g, 1e-6f)
        assertEquals(grey.g, grey.b, 1e-6f)
    }

    @Test
    fun compositionAppliesTheRightHandSideFirst() {
        // Scale red by 2, then lift every channel by 0.1: 0.2 -> 0.4 -> 0.5.
        val out = (IxColorMath.lift(0.1f) * IxColorMath.scale(2f, 1f, 1f)).apply(IxColor(0.2f, 0.2f, 0.2f))
        assertEquals(0.5f, out.r, 1e-5f)
        assertEquals(0.3f, out.g, 1e-5f)
    }

    @Test
    fun rendererKeepsTheSizeAndAlpha() {
        val pixels = intArrayOf(0x80FF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt())
        val out = IxStyleRenderer.apply(pixels, 2, 2, IxStylePresets.matrix(IxStylePreset.WARM))
        assertEquals(pixels.size, out.size)
        assertEquals(0x80, out[0] ushr 24)
    }

    @Test
    fun rendererRejectsAMismatchedBuffer() {
        assertThrows(IllegalArgumentException::class.java) {
            IxStyleRenderer.apply(IntArray(3), 2, 2, IxColorMatrix.IDENTITY)
        }
    }

    @Test
    fun aMatrixMustHaveTwentyValues() {
        assertThrows(IllegalArgumentException::class.java) { IxColorMatrix.of(1f, 0f, 0f) }
    }
}
