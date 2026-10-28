package com.everettpenne.ix

import java.util.Random
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test

class IxNoiseTest {
    /** A gradient scene with Gaussian noise of variance shot * signal + read2 (a good stand-in for Poisson at these counts). */
    private fun frame(w: Int, h: Int, black: Int, shot: Float, read2: Float, seed: Long): ShortArray {
        val rnd = Random(seed)
        return ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            val signal = 20f + 700f * (x.toFloat() / w) * (0.5f + 0.5f * y / h)
            (black + signal + rnd.nextGaussian().toFloat() * sqrt(shot * signal + read2)).toInt().coerceIn(0, 1023).toShort()
        }
    }
    private fun sqrt(x: Float) = kotlin.math.sqrt(x)

    @Test fun recoversTheNoiseModelOfTwoFrames() {
        val w = 1024; val h = 768
        val fit = IxNoise.fit(frame(w, h, 64, 1.5f, 8f, 1), frame(w, h, 64, 1.5f, 8f, 2), w, h, intArrayOf(64, 64, 64, 64), 1023)
        assertTrue("shot ${fit.shot}", abs(fit.shot - 1.5f) < 0.15f)
        assertTrue("read variance ${fit.readVariance}", abs(fit.readVariance - 8f) < 4f)
    }

    @Test fun aTexturedSceneDoesNotInflateTheFitBeyondTheMedian() {
        val w = 1024; val h = 768
        // identical frames (a perfectly still scene) apart from noise: the fit must not depend on the scene's own structure
        val fit = IxNoise.fit(frame(w, h, 64, 0.8f, 3f, 5), frame(w, h, 64, 0.8f, 3f, 6), w, h, intArrayOf(64, 64, 64, 64), 1023)
        assertTrue("shot ${fit.shot}", abs(fit.shot - 0.8f) < 0.1f)
    }
}

class IxMergeParamsTest {
    @Test fun noiseGrowsWithIso() {
        val low = mergeParamsForIso(100); val high = mergeParamsForIso(3200)
        assertTrue(high.shot > 30f * low.shot && high.read > 4f * low.read)
        assertTrue(abs(mergeParamsForIso(860).shot - 1.46f) < 0.05f)   // the ISO the first, single-capture fit came from
    }
}

class IxDisplayNoiseTest {
    private fun picture(w: Int, h: Int, sigma: Float, seed: Long): java.nio.IntBuffer {
        val rnd = Random(seed)
        val b = java.nio.IntBuffer.allocate(w * h)
        for (i in 0 until w * h) {
            val x = i % w; val y = i / w
            // a smooth gradient (dark to bright left to right) with a hard edge across the middle, plus Gaussian noise of the given std (in 0..1 units)
            val base = (0.05f + 0.85f * x / w) + if (y > h / 2) 0.1f else 0f
            val v = ((base + rnd.nextGaussian().toFloat() * sigma).coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            b.put(v or (v shl 8) or (v shl 16) or (0xFF shl 24))
        }
        b.rewind(); return b
    }

    @Test fun findsTheNoiseLevelDespiteTheGradientAndTheEdge() {
        val est = IxDisplayNoise.estimate(picture(1200, 900, 0.02f, 3), 1200, 900)
        for (v in est) assertTrue("estimate $v", abs(v - 0.02f) < 0.004f)
    }

    @Test fun aCleanPictureHasNearZeroNoise() {
        val est = IxDisplayNoise.estimate(picture(1200, 900, 0f, 4), 1200, 900)
        for (v in est) assertTrue("estimate $v", v < 0.003f)
    }
}
