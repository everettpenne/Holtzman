package com.everettpenne.holtzman.save

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.util.Log
import com.everettpenne.holtzman.camera.BurstFrame
import com.everettpenne.ix.IxCfa
import com.everettpenne.ix.IxNoise
import java.nio.ByteOrder

/** Measures the sensor's noise from two frames of a still scene at one ISO, and logs it (tag HoltzmanNoise) as a line a script can read. */
object NoiseCalibration {
    fun measure(frames: List<BurstFrame>, chars: CameraCharacteristics): String {
        val a = frames[0]; val b = frames[1]
        fun samples(f: BurstFrame) = ShortArray(f.width * f.height).also { f.pixels.rewind(); f.pixels.order(ByteOrder.nativeOrder()).asShortBuffer().get(it) }
        val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
        val dynamic = a.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
        val black = IntArray(4) { i -> dynamic?.getOrNull(i)?.toInt() ?: pattern?.getOffsetForIndex(i and 1, i shr 1) ?: 64 }
        val white = a.result.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val cfa = when (chars.get(CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT)) {
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GRBG -> IxCfa.GRBG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_GBRG -> IxCfa.GBRG
            CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT_BGGR -> IxCfa.BGGR
            else -> IxCfa.RGGB
        }
        val iso = a.result.get(CaptureResult.SENSOR_SENSITIVITY)
        val ns = a.result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
        val fit = IxNoise.fit(samples(a), samples(b), a.width, a.height, black, white, cfa)
        val line = "iso $iso exposure ${ns?.div(1000)} us black ${black.toList()} white $white: $fit; bins signal ${fit.binSignal.map { "%.0f".format(it) }} variance ${fit.binVariance.map { "%.1f".format(it) }}"
        Log.i("HoltzmanNoise", line)
        return line
    }
}
