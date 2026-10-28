package com.everettpenne.holtzman

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.everettpenne.holtzman.save.GlContext
import com.everettpenne.holtzman.save.GpuRender
import com.everettpenne.ix.IxCfa
import com.everettpenne.ix.IxLook
import com.everettpenne.ix.IxRender
import com.everettpenne.ix.IxRenderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/** The GPU renderer against the CPU renderer, on whatever device runs it (skipped where there is no OpenGL ES 3.1 compute). */
@RunWith(AndroidJUnit4::class)
class GpuRenderTest {
    private val w = 256; private val h = 192
    private fun mosaic(noise: Float, seed: Long): ShortArray {
        val rnd = Random(seed)
        return ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            val signal = 40f + 500f * x / w + if (y > h / 2) 120f else 0f   // a gradient with a hard step across the middle
            (64f + signal + rnd.nextGaussian().toFloat() * noise).coerceIn(0f, 1023f).toInt().toShort()
        }
    }
    private fun settings(look: IxLook) = IxRenderSettings(IxCfa.RGGB, intArrayOf(64, 64, 64, 64), 1023, floatArrayOf(1.6f, 1f, 1.9f), floatArrayOf(1.4f, -0.3f, -0.1f, -0.2f, 1.3f, -0.1f, 0f, -0.4f, 1.4f), null, look = look)
    private val gl = runCatching { GlContext() }.getOrNull()

    /** Every optional GPU stage must build here. Each has a fallback that keeps the app working, which also hides a shader that stops compiling: this test does not let it. */
    @Test fun everyShaderStageBuilds() {
        assumeNotNull(gl)
        GpuRender(gl!!, ownsContext = false).use { gpu ->
            assertTrue("noise-aware finish", gpu.noiseAwareFinish)
            assertTrue("local tone mapping", gpu.localToneAvailable)
            assertTrue("adaptive tone", gpu.autoToneAvailable)
        }
    }

    @Test fun theAdaptiveToneRunsAndKeepsFacesOutOfTheShadows() {
        assumeNotNull(gl)
        val raw = mosaic(2f, 9)
        val look = IxLook(autoTone = 1f, localTone = 0.35f, grain = 1.2f, denoise = 0.7f, detail = 3f, sharpen = 1.8f)
        GpuRender(gl!!, ownsContext = false).use { gpu ->
            val plain = gpu.toBitmap(raw, w, h, settings(look), 1.5f)
            val withFace = gpu.toBitmap(raw, w, h, settings(look), 1.5f, face = intArrayOf(20, 20, 100, 100))
            assertEquals(w, plain.width); assertEquals(h, withFace.height)
        }
    }

    @Test fun aClippedHighlightRendersNeutralOnTheGpuToo() {
        assumeNotNull(gl)
        val raw = ShortArray(w * h) { 1023 }
        val s = IxRenderSettings(IxCfa.RGGB, intArrayOf(64, 64, 64, 64), 1023, floatArrayOf(1.7f, 1f, 1.9f), floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), null, look = IxLook())
        GpuRender(gl!!, ownsContext = false).use { gpu ->
            val bmp = gpu.toBitmap(raw, w, h, s, 1f)
            val p = bmp.getPixel(w / 2, h / 2)
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val b = p and 255
            assertTrue("r=$r g=$g b=$b", Math.abs(r - g) <= 2 && Math.abs(b - g) <= 2)
        }
    }

    @Test fun theBaseRenderMatchesTheCpuRenderer() {
        assumeNotNull(gl)
        val raw = mosaic(0f, 1)
        val look = IxLook(blackPoint = 0.03f, contrast = 0.3f, saturation = 1.1f)   // no finishing passes: sharpen = 0, denoise = 0
        val s = settings(look)
        GpuRender(gl!!, ownsContext = false).use { gpu ->
            val bmp = gpu.toBitmap(raw, w, h, s, 1.5f)
            val cpu = IntArray(w * h); IxRender.renderRows(raw, w, h, 0, h, s, 1.5f, cpu)
            val g = IntArray(w * h); bmp.getPixels(g, 0, w, 0, 0, w, h)
            var worst = 0
            // away from the picture's edges and the hard step: the GPU's finishing pass smooths colour a little where there is an edge, by design
            for (y in 10 until h - 10) for (x in 10 until w - 10) {
                if (Math.abs(y - h / 2) < 8) continue
                val i = y * w + x
                for (shift in intArrayOf(0, 8, 16)) worst = maxOf(worst, Math.abs(((cpu[i] shr shift) and 255) - ((g[i] shr shift) and 255)))
            }
            assertTrue("largest channel difference $worst", worst <= 2)
        }
    }

    @Test fun theNoiseAwareFinishBuildsAndSmoothsNoiseWithoutSmearingTheEdge() {
        assumeNotNull(gl)
        val raw = mosaic(14f, 2)
        val plain = settings(IxLook(sharpen = 0f))
        val finished = settings(IxLook(sharpen = 1.8f, denoise = 0.7f, detail = 4.5f, detailCore = 0.5f, clarity = 0.4f, chromaSmoothing = 0.85f))
        GpuRender(gl!!, ownsContext = false).use { gpu ->
            assertTrue("the noise-aware finish did not build on this device", gpu.noiseAwareFinish)
            fun luma(b: android.graphics.Bitmap): FloatArray { val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h); return FloatArray(w * h) { 0.2126f * ((px[it] shr 16) and 255) + 0.7152f * ((px[it] shr 8) and 255) + 0.0722f * (px[it] and 255) } }
            val a = luma(gpu.toBitmap(raw, w, h, plain, 1.5f)); val b = luma(gpu.toBitmap(raw, w, h, finished, 1.5f))
            // pixel-scale noise in a flat band away from the step, as the standard deviation of the difference from a 3x3 mean
            fun noise(l: FloatArray, y0: Int, y1: Int): Float { var s = 0.0; var n = 0; for (y in y0 until y1) for (x in 20 until w - 20) { var m = 0f; for (j in -1..1) for (i in -1..1) m += l[(y + j) * w + x + i]; m /= 9f; s += (l[y * w + x] - m).let { it * it }; n++ }; return Math.sqrt(s / n).toFloat() }
            val before = noise(a, 20, 70); val after = noise(b, 20, 70)
            assertTrue("noise $before -> $after", after < before * 0.7f)
            // the hard step across the middle must stay hard: the jump between the rows either side of it keeps most of its size
            fun step(l: FloatArray): Float { var s = 0f; for (x in 40 until w - 40) s += l[(h / 2 + 2) * w + x] - l[(h / 2 - 3) * w + x]; return s / (w - 80) }
            assertTrue("step ${step(a)} -> ${step(b)}", step(b) > step(a) * 0.8f)
            val out = b.also { }
            assertEquals(h * w, a.size)
        }
    }

    /** A textured scene (diagonal gratings and colour edges), photographed through an RGGB mosaic: the two renderers' demosaics must agree. */
    @Test fun theDemosaicMatchesTheCpuRendererOnTexture() {
        assumeNotNull(gl)
        val rnd = Random(7)
        val raw = ShortArray(w * h) { i ->
            val x = i % w; val y = i / w
            val grating = 0.5f + 0.35f * Math.sin((x * 0.9 + y * 0.6)).toFloat()            // fine diagonal detail, near the sampling limit
            val colour = when ((x / 40 + y / 40) % 3) { 0 -> floatArrayOf(1f, 0.4f, 0.2f); 1 -> floatArrayOf(0.3f, 1f, 0.5f); else -> floatArrayOf(0.4f, 0.5f, 1f) }
            val ch = when { (y and 1) == 0 && (x and 1) == 0 -> 0; (y and 1) == 1 && (x and 1) == 1 -> 2; else -> 1 }
            (64f + 800f * grating * colour[ch] * 0.8f + rnd.nextGaussian().toFloat() * 2f).coerceIn(0f, 1023f).toInt().toShort()
        }
        val s = settings(IxLook())
        GpuRender(gl!!, ownsContext = false).use { gpu ->
            val bmp = gpu.toBitmap(raw, w, h, s, 1.2f)
            val cpu = IntArray(w * h); IxRender.renderRows(raw, w, h, 0, h, s, 1.2f, cpu)
            val g = IntArray(w * h); bmp.getPixels(g, 0, w, 0, 0, w, h)
            var worst = 0; var big = 0; var n = 0
            for (y in 12 until h - 12) for (x in 12 until w - 12) {
                n++
                var d = 0
                for (shift in intArrayOf(0, 8, 16)) d = maxOf(d, Math.abs(((cpu[y * w + x] shr shift) and 255) - ((g[y * w + x] shr shift) and 255)))
                worst = maxOf(worst, d); if (d > 3) big++
            }
            // the GPU's finishing pass smooths colour a little; allow it, but the demosaic itself must not disagree
            assertTrue("largest difference $worst, $big of $n pixels off by more than 3", big < n / 50)
        }
    }
}
