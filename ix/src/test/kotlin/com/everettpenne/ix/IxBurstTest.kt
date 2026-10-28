package com.everettpenne.ix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ShortBuffer
import java.util.Random
import kotlin.math.sin

class IxBurstTest {
    private val black = intArrayOf(64, 64, 64, 64)
    private val w = 160; private val h = 128

    /** A smooth, textured scene (sum of sines) in raw counts above black, rendered as a flat-colour Bayer mosaic. */
    private fun scene(x: Int, y: Int): Double = 300.0 + 120 * sin(x * 0.11) * sin(y * 0.07) + 80 * sin((x + 2 * y) * 0.05)

    private fun frame(shiftX: Int, shiftY: Int, noise: Double, seed: Long, movingBox: Boolean = false): IxPlane {
        val rnd = Random(seed)
        val a = ShortArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var v = scene(x + shiftX, y + shiftY)
            if (movingBox && x in 100 until 130 && y in 40 until 80) v += 200       // something that is only in this frame
            val sigma = noise * kotlin.math.sqrt(v / 300.0)
            a[y * w + x] = (black[0] + v + rnd.nextGaussian() * sigma).toInt().coerceIn(0, 1023).toShort()
        }
        return IxPlane(ShortBuffer.wrap(a), w, h)
    }

    /** RMS error of a patch against the noiseless scene (the truth every aligned frame is an estimate of). */
    private fun rmsError(samples: ShortArray): Double {
        var s = 0.0; var n = 0
        for (y in 30 until 90) for (x in 40 until 100) { val e = (samples[y * w + x].toInt() and 0xFFFF) - (black[0] + scene(x, y)); s += e * e; n++ }
        return kotlin.math.sqrt(s / n)
    }
    private fun std(m: IxMerged) = rmsError(m.samples)
    private fun stdOf(p: IxPlane): Double = rmsError(ShortArray(w * h) { p.samples.get(it) })

    @Test fun planesAreValidated() {
        for (bad in listOf<() -> Unit>({ IxPlane(ShortBuffer.allocate(10), 4, 4) }, { IxPlane(ShortBuffer.allocate(16), 3, 4) }, { IxPlane(ShortBuffer.allocate(16), 4, 4, 2) })) {
            try { bad(); error("should have been refused") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun alignmentFindsAWholeCellShift() {
        val ref = frame(0, 0, 0.0, 1); val alt = frame(4, 6, 0.0, 2)           // its content sits 4 px left and 6 px up of where the reference has it, so a tile of the reference is found at cells (-2, -3)
        val flow = IxBurst.align(ref, alt, black)
        val middle = (flow.tilesY / 2) * flow.tilesX + flow.tilesX / 2
        assertEquals(-2, flow.dx[middle]); assertEquals(-3, flow.dy[middle])
    }

    @Test fun anIdenticalFrameIsNotMoved() {
        val ref = frame(0, 0, 4.0, 3)
        val flow = IxBurst.align(ref, frame(0, 0, 4.0, 4), black)
        assertTrue(flow.dx.all { it == 0 } && flow.dy.all { it == 0 })
    }

    @Test fun mergingAShakyBurstReducesNoise() {
        val shifts = listOf(0 to 0, 2 to 0, 0 to 2, 4 to 2, 2 to 4, 6 to 0)
        val frames = shifts.mapIndexed { i, (sx, sy) -> frame(sx, sy, 14.0, 10L + i) }
        val ref = IxBurst.sharpest(frames).coerceIn(0, frames.lastIndex)
        val merged = IxBurst.merge(frames, 0, black)
        // the merged patch is aligned to frame 0; compare its noise with frame 0's own
        val before = stdOf(frames[0]); val after = std(merged)
        assertTrue("noise $before -> $after", after < before * 0.7)
        assertTrue("most cells merged: ${merged.meanFrames}", merged.meanFrames > 4f)
        assertTrue(ref in frames.indices)
    }

    @Test fun mergingKeepsTheSignalLevel() {
        val frames = (0 until 4).map { frame(0, 0, 10.0, 20L + it) }
        val merged = IxBurst.merge(frames, 0, black)
        val a = ShortArray(w * h) { frames[0].samples.get(it) }
        assertEquals(IxBurst.patchStats(a, w, 40, 30, 60).first, IxBurst.patchStats(merged.samples, w, 40, 30, 60).first, 3.0)
    }

    @Test fun somethingThatMovedIsNotGhosted() {
        val ref = frame(0, 0, 6.0, 30)
        val others = (1..3).map { frame(0, 0, 6.0, 30L + it, movingBox = true) }
        val merged = IxBurst.merge(listOf(ref) + others, 0, black)
        // inside the box the other frames are 200 counts brighter: the merge must stay near the reference there, not average toward them
        val inside = IxBurst.patchStats(merged.samples, w, 105, 45, 20).first
        val refInside = IxBurst.patchStats(ShortArray(w * h) { ref.samples.get(it) }, w, 105, 45, 20).first
        assertTrue("ghosted: $inside vs reference $refInside", inside < refInside + 30)
    }

    @Test fun theSharpestFrameIsChosen() {
        val sharp = frame(0, 0, 0.0, 40)
        val blurred = run {
            val a = ShortArray(w * h) { i -> val x = i % w; val y = i / w; (black[0] + (scene(x, y) + scene(x + 6, y + 6)) / 2).toInt().toShort() }
            IxPlane(ShortBuffer.wrap(a), w, h)
        }
        assertEquals(0, IxBurst.sharpest(listOf(sharp, blurred))); assertEquals(1, IxBurst.sharpest(listOf(blurred, sharp)))
    }
}

class IxMergeNoiseTest {
    private val w = 128; private val h = 96
    private val black = intArrayOf(64, 64, 64, 64)

    @org.junit.Test fun sixStillFramesReduceNoiseCloseToTheIdeal() {
        val params = mergeParamsForIso(800)
        val signal = 200f
        val frames = List(6) { f ->
            val rnd = java.util.Random(100L + f)
            ShortArray(w * h) { (64f + signal + rnd.nextGaussian().toFloat() * Math.sqrt((params.shot * signal + params.read * params.read).toDouble()).toFloat()).toInt().toShort() }
        }
        val planes = frames.map { IxPlane(java.nio.ShortBuffer.wrap(it), w, h) }
        val merged = IxBurst.merge(planes, 0, black, params)
        fun std(a: ShortArray): Double { var s = 0.0; var q = 0.0; for (v in a) { s += v; q += v.toDouble() * v }; val n = a.size; return Math.sqrt(q / n - (s / n) * (s / n)) }
        val ratio = std(frames[0]) / std(merged.samples)
        // the ideal for 6 frames is sqrt(6) = 2.45; a weight that already falls at one noise deviation (the earlier rule) managed only about 2.0
        org.junit.Assert.assertTrue("noise reduced by $ratio", ratio > 2.3)
        org.junit.Assert.assertTrue("frames per cell ${merged.meanFrames}", merged.meanFrames > 5.8f)
    }
}
