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
    val LOOK = com.everettpenne.ix.IxLook(blackPoint = 0.04f, contrast = 0.4f, saturation = 1.15f, sharpen = 1.8f, sharpenCore = 1.5f,
        denoise = 0.7f, detail = 4.5f, detailCore = 0.5f, clarity = 0.4f, chromaSmoothing = 0.85f,
        // quantile-matched to the Pixel camera app's picture of the same scene: shadows and mids a little lifted, highlights compressed
        curve = floatArrayOf(0f, 4f, 13f, 34f, 55f, 75f, 106f, 132f, 156f, 180f, 203f, 245f).map { it / 255f }.toFloatArray())
    /** What the average of the picture is brought to, linear: lower than middle grey, as the Pixel app's pictures sit. */
    const val EXPOSURE_TARGET = 0.12f

    fun settings(result: TotalCaptureResult, chars: CameraCharacteristics, black: IntArray): IxRenderSettings {
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
        return IxRenderSettings(cfa, black, white, wb, ccm, shading, look = LOOK)
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
