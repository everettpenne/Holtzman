package com.everettpenne.holtzman.save

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.os.Build
import android.os.PowerManager
import android.util.Size
import com.everettpenne.holtzman.camera.BurstFrame
import com.everettpenne.holtzman.camera.CameraController
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What a dataset shot is for. */
enum class DatasetKind(val label: String, val detail: String) {
    HANDHELD("Handheld burst", "6 RAW frames as the camera exposes them. For the merge, alignment and motion."),
    CLEAN("Clean reference", "40 RAW frames at a low ISO, one fixed exposure. Tripod: they average into a near noise-free picture."),
    LADDER("ISO ladder", "2 RAW frames at each of ISO 100 to 3200, equal brightness. Tripod: for the noise model."),
}

/** The words a scene can be tagged with, so the set can be sliced later. */
object DatasetTags {
    val LIGHT = listOf("sun", "shade", "overcast", "indoor warm", "indoor daylight", "dim", "night", "backlit")
    val SUBJECT = listOf("people", "skin", "text", "foliage", "fabric", "food", "texture", "sky", "moving subject", "low contrast")
}

/**
 * Captures RAW frames and their metadata to disk for tuning and training: no merging, no rendering, nothing the pipeline could have changed. One
 * folder per scene, Documents/Holtzman/Dataset/<scene>/, holding the DNGs and a meta.json that says what was shot, how, and on what.
 */
class DatasetCapture(private val controller: CameraController, private val saver: PhotoSaver, private val context: Context) {
    private suspend fun burst(frames: Int, iso: Int? = null, fixed: CameraController.AutoSample? = null): List<BurstFrame> = suspendCancellableCoroutine { c ->
        controller.captureBurst(onResult = { c.resume(it) }, onError = { c.resumeWithException(IOException(it)) }, frames = frames, iso = iso, fixed = fixed)
    }

    /** Runs one shot of [kind]; [progress] gets a line as it goes. Returns the scene folder's name. Call off the main thread. */
    suspend fun run(kind: DatasetKind, tags: Set<String>, note: String, orientation: Int, progress: (String) -> Unit): String {
        val chars = controller.characteristics ?: throw IOException("The camera is not ready.")
        val auto = controller.autoSample ?: throw IOException("Wait a moment for the exposure to settle.")
        val started = System.currentTimeMillis()
        val scene = "scene_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(started)) + "_" + kind.name.lowercase()
        val folder = "Dataset/$scene"
        val files = JSONArray()
        var written = 0

        fun save(frame: BurstFrame, name: String, group: String) {
            frame.pixels.rewind()
            saver.writeDocument(folder, name, "image/x-adobe-dng", started) { out ->
                DngCreator(chars, frame.result).use {
                    it.setOrientation(PhotoSaver.exifOrientation(orientation))
                    it.writeByteBuffer(out, Size(frame.width, frame.height), frame.pixels, 0)
                }
            }
            files.put(frameJson(name, group, frame, chars))
            written++
        }

        when (kind) {
            DatasetKind.HANDHELD -> {
                progress("Hold still…")
                val frames = burst(6)
                frames.forEachIndexed { i, f -> progress("Saving ${i + 1} of ${frames.size}"); save(f, "frame_%03d.dng".format(i), "burst") }
            }
            DatasetKind.CLEAN -> {
                // one exposure for every frame, from the automatic one at the start: the lowest ISO that keeps it under two seconds
                val minIso = (chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.lower ?: 100).coerceAtLeast(100)
                var iso = minIso
                var ns = auto.exposureNs.toDouble() * auto.iso / iso
                if (ns > 2e9) { iso = Math.ceil(auto.iso * auto.exposureNs / 2e9).toInt(); ns = auto.exposureNs.toDouble() * auto.iso / iso }
                val fixed = CameraController.AutoSample(ns.toLong(), iso, auto.focusDiopters, auto.afState)
                val total = 40
                var i = 0
                while (i < total) {
                    progress("Frame ${i + 1} of $total, ISO $iso")
                    val frames = burst(minOf(6, total - i), fixed = fixed)
                    for (f in frames) { save(f, "frame_%03d.dng".format(i), "clean"); i++ }
                }
            }
            DatasetKind.LADDER -> {
                for (iso in listOf(100, 200, 400, 800, 1600, 3200)) {
                    progress("ISO $iso")
                    val frames = burst(2, iso = iso)
                    frames.forEachIndexed { k, f -> save(f, "iso_%04d_%d.dng".format(iso, k), "iso$iso") }
                }
            }
        }

        val meta = JSONObject().apply {
            put("scene", scene); put("kind", kind.name); put("tags", JSONArray(tags.sorted())); put("note", note)
            put("startedAt", started); put("finishedAt", System.currentTimeMillis())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("build", Build.FINGERPRINT)
            put("app", "Holtzman dataset capture 1")
            put("zoom", controller.zoom.toDouble()); put("orientationDegrees", orientation)
            put("sensorOrientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1)
            put("thermalStatus", thermal())
            put("autoAtStart", JSONObject().put("exposureNs", auto.exposureNs).put("iso", auto.iso).put("focusDiopters", auto.focusDiopters?.toDouble() ?: JSONObject.NULL))
            put("frames", files)
        }
        saver.writeDocument(folder, "meta.json", "application/json", started) { it.write(meta.toString(2).toByteArray()) }
        progress("Saved $written frames")
        return scene
    }

    private fun thermal(): Int = runCatching { (context.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus }.getOrDefault(-1)

    private fun frameJson(name: String, group: String, f: BurstFrame, chars: CameraCharacteristics): JSONObject {
        val r = f.result
        val o = JSONObject().put("file", name).put("group", group).put("width", f.width).put("height", f.height)
        r.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { o.put("exposureNs", it) }
        r.get(CaptureResult.SENSOR_SENSITIVITY)?.let { o.put("iso", it) }
        r.get(CaptureResult.SENSOR_TIMESTAMP)?.let { o.put("timestamp", it) }
        r.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { o.put("focusDiopters", it.toDouble()) }
        r.get(CaptureResult.CONTROL_AF_STATE)?.let { o.put("afState", it) }
        r.get(CaptureResult.LENS_APERTURE)?.let { o.put("aperture", it.toDouble()) }
        r.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)?.let { o.put("black", JSONArray(it.map { v -> v.toDouble() })) }
        (r.get(CaptureResult.SENSOR_DYNAMIC_WHITE_LEVEL) ?: chars.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL))?.let { o.put("white", it) }
        r.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { o.put("wbGains", JSONArray(listOf(it.red, it.greenEven, it.greenOdd, it.blue).map { v -> v.toDouble() })) }
        r.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { t -> o.put("ccm", JSONArray((0 until 9).map { i -> t.getElement(i % 3, i / 3).toDouble() })) }
        // the camera's own noise model for this frame: (S, O) per colour channel, variance = S * signal + O in normalised units
        r.get(CaptureResult.SENSOR_NOISE_PROFILE)?.let { np -> o.put("noiseProfile", JSONArray(np.map { p -> JSONArray(listOf(p.first, p.second)) })) }
        return o
    }
}
