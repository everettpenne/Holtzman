package com.everettpenne.holtzman.save

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Gainmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log
import com.everettpenne.holtzman.camera.StillResult
import com.everettpenne.ix.IxBlocks
import com.everettpenne.ix.IxCfa
import com.everettpenne.ix.IxGainMap
import com.everettpenne.ix.IxGainMapResult
import com.everettpenne.ix.IxRaw
import java.io.ByteArrayOutputStream
import kotlin.math.pow

/**
 * Ultra HDR for a shot taken with RAW beside the JPEG. The camera offers no Ultra HDR of its own (the probe says so on the Pixel 10a), so the gain
 * map is built here: how much brighter each place is in the RAW's linear light than in the camera's tone-mapped JPEG ([IxGainMap]). The JPEG is the
 * SDR base; Android's gain-map API writes the Ultra HDR file.
 *
 * Returns null whenever the result would not be better than the plain JPEG (no usable RAW, nothing the tone curve compressed, an encoder that
 * did not keep the gain map): the caller then saves the plain JPEG. It never throws past this object; an HDR experiment must not lose a photo.
 */
object UltraHdr {
    private const val TAG = "HoltzmanUltraHdr"
    /** Gain-map resolution: one cell per 8x8 sensor pixels. */
    private const val BLOCK = 8
    private const val MAX_BOOST = 4f

    fun build(still: StillResult): ByteArray? = try {
        val raw = still.raw ?: return null
        val blocks = rawBlocks(raw.image, raw.result, still.characteristics) ?: return null
        val sdr = sdrLuminance(still.jpeg, blocks.width, blocks.height) ?: return null
        val gain = IxGainMap.compute(sdr, blocks, MAX_BOOST)
        if (gain == null) { Log.i(TAG, "no highlight detail to recover in this scene"); null } else encode(still.jpeg, gain)
    } catch (t: Throwable) {
        Log.w(TAG, "Ultra HDR failed; saving the plain JPEG", t)
        null
    }

    private fun rawBlocks(image: android.media.Image, result: android.hardware.camera2.TotalCaptureResult, chars: CameraCharacteristics): IxBlocks? {
        val plane = image.planes[0]
        val rowStride = plane.rowStride / 2
        val width = image.width; val height = image.height
        val samples = ShortArray(rowStride * height)
        plane.buffer.asShortBuffer().let { it.get(samples, 0, minOf(it.remaining(), samples.size)) }
        return blocksOf(samples, width, height, rowStride, result, chars)
    }

    /** The scene's linear brightness on the gain map's grid, from a RAW mosaic and the metadata of the frame it came from. */
    private fun blocksOf(samples: ShortArray, width: Int, height: Int, rowStride: Int, result: android.hardware.camera2.TotalCaptureResult, chars: CameraCharacteristics): IxBlocks? {
        val cfa = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_RGGB -> IxCfa.RGGB
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> IxCfa.GRBG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> IxCfa.GBRG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> IxCfa.BGGR
            else -> return null
        }
        // the black level the frame was actually read with (it drifts with temperature), else the static pattern
        val dynamic = result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val black = IntArray(4) { i ->
            dynamic?.getOrNull(i)?.toInt() ?: pattern?.getOffsetForIndex(i and 1, i shr 1) ?: return null
        }
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: return null
        // white balance: the scene's neutral point in camera RGB; gains bring it to equal channels
        val neutral = result.get(CaptureResult.SENSOR_NEUTRAL_COLOR_POINT) ?: return null
        val n = FloatArray(3) { neutral[it].toFloat() }
        if (n.any { it <= 0f }) return null
        val gains = floatArrayOf(n[1] / n[0], 1f, n[1] / n[2])
        return IxRaw.blocks(samples, width, height, rowStride, cfa, black, white, gains, BLOCK)
    }

    /** Linear luminance of the JPEG on a [w] x [h] grid (the picture shrunk by averaging, which is what a block of RAW pixels also is). */
    private fun sdrLuminance(jpeg: ByteArray, w: Int, h: Int): FloatArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= w && bounds.outHeight / (sample * 2) >= h) sample *= 2
        val small = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val grid = if (small.width == w && small.height == h) small else Bitmap.createScaledBitmap(small, w, h, true)
        val px = IntArray(w * h)
        grid.getPixels(px, 0, w, 0, 0, w, h)
        val lut = FloatArray(256) { i -> val c = i / 255.0; (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat() }
        return FloatArray(w * h) { i ->
            val p = px[i]
            0.2126f * lut[(p shr 16) and 0xFF] + 0.7152f * lut[(p shr 8) and 0xFF] + 0.0722f * lut[p and 0xFF]
        }
    }

    private fun encode(jpeg: ByteArray, gain: IxGainMapResult): ByteArray? {
        val base = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
        val map = Bitmap.createBitmap(gain.width, gain.height, Bitmap.Config.ALPHA_8)
        map.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(gain.data))
        val g = Gainmap(map).apply {
            setRatioMin(1f, 1f, 1f)
            setRatioMax(gain.maxBoost, gain.maxBoost, gain.maxBoost)
            setGamma(1f, 1f, 1f)
            setEpsilonSdr(1f / 64f, 1f / 64f, 1f / 64f)
            setEpsilonHdr(1f / 64f, 1f / 64f, 1f / 64f)
            minDisplayRatioForHdrTransition = 1f
            displayRatioForFullHdr = gain.maxBoost
        }
        base.setGainmap(g)
        val out = ByteArrayOutputStream(jpeg.size + gain.data.size)
        if (!base.compress(Bitmap.CompressFormat.JPEG, 97, out)) return null
        val bytes = out.toByteArray()
        // the file must really carry the gain map; if this encoder dropped it, the plain JPEG is the honest result
        val check = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return if (check != null && check.hasGainmap()) bytes else { Log.w(TAG, "the encoder did not keep the gain map"); null }
    }

    /**
     * Ultra HDR for a burst: the merged mosaic is the scene's linear light, [sdr] the finished (tone-mapped) picture rendered from it, in the same
     * orientation. Attaches the gain map to [sdr] so that compressing it as a JPEG writes an Ultra HDR file. Returns whether it did; false (and
     * [sdr] untouched) when there is nothing in the highlights worth recovering, or on any failure, so the caller saves a plain JPEG.
     */
    fun attachGainmap(sdr: Bitmap, mosaic: ShortArray, width: Int, height: Int, result: android.hardware.camera2.TotalCaptureResult, chars: CameraCharacteristics): Boolean = try {
        val blocks = blocksOf(mosaic, width, height, width, result, chars)
        if (blocks == null) false else {
            // the picture on the gain map's grid, shrunk in halvings (each halving averages; one big step would skip most pixels)
            var small = sdr
            while (small.width / 2 >= blocks.width && small.height / 2 >= blocks.height) small = Bitmap.createScaledBitmap(small, small.width / 2, small.height / 2, true)
            val grid = if (small.width == blocks.width && small.height == blocks.height) small else Bitmap.createScaledBitmap(small, blocks.width, blocks.height, true)
            val px = IntArray(blocks.width * blocks.height).also { grid.getPixels(it, 0, blocks.width, 0, 0, blocks.width, blocks.height) }
            val lut = FloatArray(256) { i -> val c = i / 255.0; (if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)).toFloat() }
            val lum = FloatArray(px.size) { i -> val p = px[i]; 0.2126f * lut[(p shr 16) and 0xFF] + 0.7152f * lut[(p shr 8) and 0xFF] + 0.0722f * lut[p and 0xFF] }
            val gain = IxGainMap.compute(lum, blocks, MAX_BOOST)
            if (gain == null) { Log.i(TAG, "no highlight detail to recover in this burst"); false } else {
                val map = Bitmap.createBitmap(gain.width, gain.height, Bitmap.Config.ALPHA_8)
                map.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(gain.data))
                sdr.setGainmap(Gainmap(map).apply {
                    setRatioMin(1f, 1f, 1f); setRatioMax(gain.maxBoost, gain.maxBoost, gain.maxBoost); setGamma(1f, 1f, 1f)
                    setEpsilonSdr(1f / 64f, 1f / 64f, 1f / 64f); setEpsilonHdr(1f / 64f, 1f / 64f, 1f / 64f)
                    minDisplayRatioForHdrTransition = 1f; displayRatioForFullHdr = gain.maxBoost
                })
                true
            }
        }
    } catch (t: Throwable) { Log.w(TAG, "Ultra HDR for the burst failed; saving the plain JPEG", t); false }
}
