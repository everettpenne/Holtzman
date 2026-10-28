package com.everettpenne.ix

import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.sqrt

class IxDenoiseTest {
    private val w = 256; private val h = 256
    private val black = intArrayOf(64, 64, 64, 64)
    private val shot = 1.4f; private val read = 4.5f; private val frames = 6f

    /** A flat dark half and a bright half with a hard vertical edge, plus noise of the model's size. */
    private fun scene(seed: Long, noisy: Boolean): ShortArray {
        val rnd = Random(seed)
        return ShortArray(w * h) { i ->
            val signal = if (i % w < w / 2) 60f else 360f
            val sigma = if (noisy) sqrt((shot * signal + read) / frames) else 0f
            (64f + signal + rnd.nextGaussian().toFloat() * sigma).toInt().toShort()
        }
    }
    private fun std(a: ShortArray, x0: Int, x1: Int): Float { var s = 0.0; var q = 0.0; var n = 0; for (y in 20 until h - 20) for (x in x0 until x1) { val v = a[y * w + x].toDouble(); s += v; q += v * v; n++ }; return sqrt(q / n - (s / n) * (s / n)).toFloat() }

    @Test fun smoothsNoiseOnFlatAreas() {
        val noisy = scene(1, true); val den = IxDenoise.mosaic(noisy, w, h, black, shot, read, frames)
        val before = std(noisy, 20, 100); val after = std(den, 20, 100)
        assertTrue("noise $before -> $after", after < before * 0.6f)
        val b2 = std(noisy, 156, 236); val a2 = std(den, 156, 236)
        assertTrue("bright noise $b2 -> $a2", a2 < b2 * 0.6f)
    }

    @Test fun keepsAHardEdgeHard() {
        val den = IxDenoise.mosaic(scene(2, true), w, h, black, shot, read, frames)
        // two samples either side of the edge (same colour: x = 126 and 130 are both even columns): the step must survive almost whole
        var step = 0f; var n = 0
        for (y in 20 until h - 20 step 2) { step += (den[y * w + 130].toInt() - den[y * w + 124].toInt()); n++ }
        step /= n
        assertTrue("step $step of 300", step > 270f)
    }

    @Test fun leavesACleanPictureAlone() {
        val clean = scene(3, false); val den = IxDenoise.mosaic(clean, w, h, black, shot, read, frames)
        var worst = 0; for (i in clean.indices) worst = maxOf(worst, kotlin.math.abs(clean[i] - den[i]))
        // only the cells straddling the edge may move, and by little: the edge falls between two cells
        assertTrue("largest change $worst", worst <= 2)
    }
}
