package com.everettpenne.holtzman

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everettpenne.holtzman.save.GlContext
import com.everettpenne.holtzman.save.GpuMerge
import com.everettpenne.ix.IxBurst
import com.everettpenne.ix.IxDenoise
import com.everettpenne.ix.IxPlane
import com.everettpenne.ix.mergeParamsForIso
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** The GPU merge and its spatial denoise against the CPU engine, on synthetic noisy frames of one still scene. */
@RunWith(AndroidJUnit4::class)
class GpuMergeTest {
    private val w = 256; private val h = 192
    private val black = intArrayOf(64, 64, 64, 64)
    private val gl = runCatching { GlContext() }.getOrNull()

    private fun frame(seed: Long, shot: Float, read2: Float): ShortArray {
        val rnd = Random(seed)
        return ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            val signal = 30f + 400f * (0.5f + 0.5f * Math.sin(x * 0.05).toFloat()) + if (y > h / 2) 80f else 0f
            (64f + signal + rnd.nextGaussian().toFloat() * Math.sqrt((shot * signal + read2).toDouble()).toFloat()).coerceIn(0f, 1023f).toInt().toShort()
        }
    }

    @Test fun theGpuMergeWithDenoiseMatchesTheCpuEngine() {
        assumeNotNull(gl)
        val params = mergeParamsForIso(800)
        val arrays = List(6) { frame(it + 1L, params.shot, params.read * params.read) }
        val buffers = arrays.map { a -> ByteBuffer.allocateDirect(w * h * 2).order(ByteOrder.nativeOrder()).also { it.asShortBuffer().put(a); it.rewind() } }
        val planes = buffers.map { IxPlane(it.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer(), w, h) }
        val cpuMerged = IxBurst.merge(planes, 2, black, params)
        val cpu = IxDenoise.mosaic(cpuMerged.samples, w, h, black, params.shot, params.read * params.read, cpuMerged.meanFrames, 1.6f)
        val gpu = GpuMerge(gl!!).merge(buffers, w, h, 2, black, params, denoise = 1.6f)
        var big = 0; var worst = 0
        for (i in cpu.indices) { val d = Math.abs(cpu[i] - gpu.samples[i]); if (d > 2) big++; worst = maxOf(worst, d) }
        assertTrue("largest difference $worst, $big of ${cpu.size} samples differ by more than 2", big < cpu.size / 200)
        assertTrue("mean frames ${gpu.meanFrames} vs ${cpuMerged.meanFrames}", Math.abs(gpu.meanFrames - cpuMerged.meanFrames) < 0.05f)
    }
}
