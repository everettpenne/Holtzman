package com.everettpenne.holtzman.save

import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import com.everettpenne.ix.IxCfa
import com.everettpenne.ix.IxRender
import com.everettpenne.ix.IxRenderSettings
import com.everettpenne.ix.IxShadingMap

/**
 * Stage 3: a RAW mosaic to a picture, with everything taken from the frame's own metadata (levels, the camera's white balance and colour matrix,
 * the lens-shading map), so the colours follow what the stock camera chose for the same moment.
 */
object BurstRender {
    /** The finishing look, tuned against the Pixel camera app's output of the same scenes (docs/ix-pipeline.md). */
    val LOOK = com.everettpenne.ix.IxLook(blackPoint = 0.04f, contrast = 0.4f, saturation = 0.9f, sharpen = 1.8f, sharpenCore = 1.5f,
        denoise = 0.7f, detail = 4.5f, detailCore = 0.5f, clarity = 0.4f, chromaSmoothing = 0.85f, localTone = 0.35f, grain = 1.2f,
        autoTone = 1f,
        // used only if the adaptive tone cannot run on a device: the curve fitted to the Pixel app's picture of the first scene (a dim room)
        curve = floatArrayOf(0f, 4f, 13f, 34f, 55f, 75f, 106f, 132f, 156f, 180f, 203f, 245f).map { it / 255f }.toFloatArray())
    /**
     * The two finish controls in Settings. [texture] runs from -1 (softer) to +1 (crisper), 0 = the tuned look: it scales the fine-detail gain and the
     * medium-scale contrast with it, and the sharpening by about half as much. [grain] is film grain from 0 (none) to 1 (heavy, [GRAIN_LEVELS] display
     * levels of 255 at the strongest); [DEFAULT_GRAIN] is the tuned look's 1.2 levels.
     */
    class LookTuning(val texture: Float = 0f, val grain: Float = DEFAULT_GRAIN) {
        companion object { const val DEFAULT_GRAIN = 0.2f; const val GRAIN_LEVELS = 6f }
    }

    private fun tuned(l: com.everettpenne.ix.IxLook, t: LookTuning): com.everettpenne.ix.IxLook {
        val x = t.texture.coerceIn(-1f, 1f)
        return com.everettpenne.ix.IxLook(l.blackPoint, l.contrast, l.saturation, sharpen = l.sharpen * (1f + 0.6f * x), sharpenCore = l.sharpenCore, curve = l.curve,
            denoise = l.denoise, detail = l.detail * (1f + x), detailCore = l.detailCore, clarity = l.clarity * (1f + x), chromaSmoothing = l.chromaSmoothing,
            localTone = l.localTone, grain = t.grain.coerceIn(0f, 1f) * LookTuning.GRAIN_LEVELS, autoTone = l.autoTone)
    }

    /** [LOOK] with the user's [tuning], and any stages the debug switches turned off. */
    fun look(tuning: LookTuning = LookTuning()): com.everettpenne.ix.IxLook {
        val l = tuned(LOOK, tuning)
        if (DebugFlags.raw.isEmpty()) return l
        return com.everettpenne.ix.IxLook(l.blackPoint, l.contrast, l.saturation, l.sharpen, l.sharpenCore, l.curve,
            denoise = if (DebugFlags.on("nofinish")) 0f else l.denoise, detail = l.detail, detailCore = l.detailCore, clarity = l.clarity, chromaSmoothing = l.chromaSmoothing,
            localTone = if (DebugFlags.on("noltm")) 0f else l.localTone, grain = if (DebugFlags.on("nograin")) 0f else l.grain, autoTone = if (DebugFlags.on("noauto")) 0f else l.autoTone)
    }

    /** What the average of the picture is brought to, linear: lower than middle grey, as the Pixel app's pictures sit. */
    const val EXPOSURE_TARGET = 0.12f

    fun settings(result: TotalCaptureResult, chars: CameraCharacteristics, black: IntArray, tuning: LookTuning = LookTuning()): IxRenderSettings {
        val cfa = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> IxCfa.GRBG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> IxCfa.GBRG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> IxCfa.BGGR
            else -> IxCfa.RGGB
        }
        val white = result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val g = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
        val wb = if (g != null) floatArrayOf(g.red, (g.greenEven + g.greenOdd) / 2f, g.blue) else floatArrayOf(1f, 1f, 1f)
        val t = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
        val ccm = if (t != null) FloatArray(9) { i -> t.getElement(i % 3, i / 3).toFloat() } else floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val lsm = result.get(CaptureResult.STATISTICS_LENS_SHADING_CORRECTION_MAP)
        val shading = lsm?.takeIf { it.columnCount >= 2 && it.rowCount >= 2 }?.let { m ->
            val gains = FloatArray(m.columnCount * m.rowCount * 4)
            for (r in 0 until m.rowCount) for (c in 0 until m.columnCount) for (k in 0 until 4) gains[(r * m.columnCount + c) * 4 + k] = m.getGainFactor(k, c, r)
            IxShadingMap(m.columnCount, m.rowCount, gains)
        }
        return IxRenderSettings(cfa, black, white, wb, ccm, shading, look = look(tuning))
    }

    /** Renders the whole mosaic to a bitmap, a band of rows at a time. */
    fun toBitmap(raw: ShortArray, width: Int, height: Int, s: IxRenderSettings, exposure: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val band = 64
        val rows = IntArray(band * width)
        var y = 0
        while (y < height) {
            val end = minOf(y + band, height)
            IxRender.renderRows(raw, width, height, y, end, s, exposure, rows)
            bitmap.setPixels(rows, 0, width, 0, y, width, end - y)
            y = end
        }
        return bitmap
    }
}
