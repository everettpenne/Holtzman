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
