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
enum class DatasetKind(val label: String, val detail: String, val short: String) {
    HANDHELD("Handheld burst", "6 RAW frames as the camera exposes them. For the merge, alignment and motion.", "6 frames, as you would shoot"),
    CLEAN("Clean reference", "40 RAW frames at a low ISO, one fixed exposure. Tripod: they average into a near noise-free picture.", "40 frames of a still scene; tripod"),
    LADDER("ISO ladder", "2 RAW frames at each of ISO 100 to 3200, equal brightness. Tripod: for the noise model.", "2 frames at each ISO; tripod"),
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

    private suspend fun focus() = suspendCancellableCoroutine<Unit> { c -> controller.focusForShot { c.resume(Unit) } }

    /** Runs one shot of [kind]; [progress] gets a line as it goes. Returns the scene folder's name. Call off the main thread. */
    suspend fun run(kind: DatasetKind, tags: Set<String>, note: String, orientation: Int, progress: (String) -> Unit, sceneName: String? = null, pixelDouble: String? = null): String {
        val chars = controller.characteristics ?: throw IOException("The camera is not ready.")
        if (controller.autoSample == null) throw IOException("Wait a moment for the exposure to settle.")
        progress("Focusing…"); focus()
        val auto = controller.autoSample ?: throw IOException("Wait a moment for the exposure to settle.")
        val started = System.currentTimeMillis()
        val scene = sceneName ?: PixelPairing.sceneName(started, kind)   // a paired capture chose its name before the Pixel photo was taken
        val folder = "Dataset/$scene"
        val files = JSONArray()
        var written = 0
        var first: BurstFrame? = null

        fun save(frame: BurstFrame, name: String, group: String) {
            frame.pixels.rewind()
            saver.writeDocument(folder, name, "image/x-adobe-dng", started) { out ->
                DngCreator(chars, frame.result).use {
                    it.setOrientation(PhotoSaver.exifOrientation(orientation))
                    it.writeByteBuffer(out, Size(frame.width, frame.height), frame.pixels, 0)
                }
            }
            files.put(frameJson(name, group, frame, chars))
            if (first == null) first = frame
            written++
        }

        var failure: Throwable? = null
        try {
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

        } catch (t: Throwable) { failure = t }

        first?.let { progress("Making a preview…"); writePreview(it, folder, orientation, started) }
        val meta = JSONObject().apply {
            put("scene", scene); put("kind", kind.name); put("tags", JSONArray(tags.sorted())); put("note", note)
            put("startedAt", started); put("finishedAt", System.currentTimeMillis())
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("build", Build.FINGERPRINT)
            put("app", "Holtzman dataset capture 1")
            // the Pixel Camera app's photo of the same moment, taken first, when this was a paired capture: a path under Documents/Holtzman/Dataset
            pixelDouble?.let { put("pixelDouble", it); put("pairing", "pixel-first"); PixelPairing.version(context)?.let { v -> put("pixelCameraVersion", v) } }
            put("zoom", controller.zoom.toDouble()); put("orientationDegrees", orientation)
            put("sensorOrientation", chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: -1)
            put("thermalStatus", thermal())
            put("shakeRateAtStart", controller.shakeRate.toDouble())
            put("autoAtStart", JSONObject().put("exposureNs", auto.exposureNs).put("iso", auto.iso).put("focusDiopters", auto.focusDiopters?.toDouble() ?: JSONObject.NULL))
            put("frames", files)
            put("complete", failure == null)
            failure?.let { put("error", "${it.javaClass.simpleName}: ${it.message}") }
        }
        saver.writeDocument(folder, "meta.json", "application/json", started) { it.write(meta.toString(2).toByteArray()) }
        failure?.let { progress("Stopped after $written frames: ${it.message}"); throw it }
        progress("Saved $written frames")
        return scene
    }

    /**
     * Saves the frames of a burst that has already been taken and processed (a normal photo) as a dataset scene named after the photo, so a picture
     * that came out strangely can be re-run offline from exactly its frames. Call off the main thread; the frames' buffers must still be alive.
     */
    fun saveTakenBurst(frames: List<BurstFrame>, photoName: String, orientation: Int, timeMillis: Long, processing: JSONObject) {
        val chars = controller.characteristics ?: return
        val scene = "photo_$photoName"
        val folder = "Dataset/$scene"
        val files = JSONArray()
        frames.forEachIndexed { i, f ->
            f.pixels.rewind()
            val name = "frame_%03d.dng".format(i)
            saver.writeDocument(folder, name, "image/x-adobe-dng", timeMillis) { out ->
                DngCreator(chars, f.result).use { it.setOrientation(PhotoSaver.exifOrientation(orientation)); it.writeByteBuffer(out, Size(f.width, f.height), f.pixels, 0) }
            }
            files.put(frameJson(name, "burst", f, chars))
        }
        frames.firstOrNull()?.let { writePreview(it, folder, orientation, timeMillis) }
        val meta = JSONObject().apply {
            put("scene", scene); put("kind", "PHOTO"); put("tags", JSONArray(listOf("kept with a normal photo"))); put("note", "frames of the picture $photoName.jpg")
            put("startedAt", timeMillis); put("device", "${Build.MANUFACTURER} ${Build.MODEL}"); put("app", "Holtzman kept burst 1")
            put("zoom", controller.zoom.toDouble()); put("orientationDegrees", orientation); put("thermalStatus", thermal())
            put("processing", processing); put("frames", files); put("complete", true)
        }
        saver.writeDocument(folder, "meta.json", "application/json", timeMillis) { it.write(meta.toString(2).toByteArray()) }
    }

    /**
     * A small picture of what a scene holds, rendered from one of its frames with the app's own pipeline and saved beside the DNGs as preview.jpg,
     * so the Files app shows what was captured (a DNG has no thumbnail there). Failure is not fatal: the frames are what matter.
     */
    private fun writePreview(frame: BurstFrame, folder: String, orientation: Int, timeMillis: Long) {
        val chars = controller.characteristics ?: return
        runCatching {
            GlContext().use { gl ->
                GpuRender(gl, ownsContext = false).use { gpu ->
                    frame.pixels.rewind()
                    val raw = ShortArray(frame.width * frame.height).also { frame.pixels.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().get(it) }
                    val dynamic = frame.result.get(CaptureResult.SENSOR_DYNAMIC_BLACK_LEVEL)
                    val pattern = chars.get(CameraCharacteristics.SENSOR_BLACK_LEVEL_PATTERN)
                    val black = IntArray(4) { i -> dynamic?.getOrNull(i)?.toInt() ?: pattern?.getOffsetForIndex(i and 1, i shr 1) ?: 64 }
                    val settings = BurstRender.settings(frame.result, chars, black)
                    val exposure = com.everettpenne.ix.IxRender.autoExposure(raw, frame.width, frame.height, settings, BurstRender.EXPOSURE_TARGET)
                    val full = gpu.toBitmap(raw, frame.width, frame.height, settings, exposure)
                    val m = android.graphics.Matrix().apply { postRotate(orientation.toFloat()) }
                    val small = android.graphics.Bitmap.createScaledBitmap(full, frame.width / 3, frame.height / 3, true)
                    full.recycle()
                    val upright = android.graphics.Bitmap.createBitmap(small, 0, 0, small.width, small.height, m, true)
                    saver.writeDocument(folder, "preview.jpg", "image/jpeg", timeMillis) { out -> upright.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, out) }
                    small.recycle(); upright.recycle()
                }
            }
        }.onFailure { android.util.Log.w("HoltzmanDataset", "no preview for $folder", it) }
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
        r.get(CaptureResult.STATISTICS_FACES)?.takeIf { it.isNotEmpty() }?.let { fs ->
            o.put("faces", JSONArray(fs.map { f -> JSONObject().put("left", f.bounds.left).put("top", f.bounds.top).put("right", f.bounds.right).put("bottom", f.bounds.bottom).put("score", f.score) }))
        }
        return o
    }
}
