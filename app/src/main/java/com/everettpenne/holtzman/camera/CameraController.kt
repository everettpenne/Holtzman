package com.everettpenne.holtzman.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.Executor

/** What a finished still is: the JPEG bytes, and (when RAW was asked for and the camera gave it) the DNG-ready sensor data. */
class StillResult(
    val jpeg: ByteArray,
    val orientation: Int,
    val raw: RawCapture?,
    val timestampMillis: Long,
    val characteristics: CameraCharacteristics,
    /** A short word added to the file name ("hq"), or empty. */
    val tag: String = "",
    /** The pipeline did not honour JPEG_ORIENTATION (the vendor extensions): the saver writes the EXIF orientation itself. */
    val needsExifOrientation: Boolean = false,
)

/** One RAW frame and the metadata DngCreator needs to describe it. Call [close] when done: it owns the camera's buffer. */
class RawCapture(val image: Image, val result: TotalCaptureResult) { fun close() = image.close() }

enum class Lens { BACK, FRONT }

/**
 * Camera2, directly: one camera open at a time, a preview stream and a JPEG (and optionally RAW) still stream on one session, all callbacks on a
 * private thread. It makes no decisions it can leave to [CameraSpec]; it opens, previews, shoots and closes, and says what went wrong in words.
 *
 * The still stream is the largest 4:3 JPEG the camera offers. Preview and still share the shape, so the frame on screen is the frame saved.
 */
class CameraController(private val context: Context) {
    private companion object { const val TAG = "HoltzmanCamera" }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("holtzman-camera").also { it.start() }
    private val handler = Handler(thread.looper)

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var extensionSession: CameraExtensionSession? = null
    /** Counts opens: a camera that finishes opening after a newer open began (a mode switched in the meantime) is closed, not used. */
    @Volatile private var generation = 0
    private val executor = Executor { handler.post(it) }
    /** The vendor extension in use (CameraExtensionCharacteristics.EXTENSION_*), or null for the normal pipeline. */
    var extension: Int? = null; private set

    var characteristics: CameraCharacteristics? = null; private set
    var cameraId: String? = null; private set
    var previewSize: Dim? = null; private set
    var stillSize: Dim? = null; private set
    var rawSize: Dim? = null; private set
    val rawAvailable: Boolean get() = rawSize != null

    /** The camera of [lens] to use: the first of that facing that is not a depth or monochrome helper; the logical camera when there is one. */
    fun idFor(lens: Lens): String? {
        val want = if (lens == Lens.BACK) CameraCharacteristics.LENS_FACING_BACK else CameraCharacteristics.LENS_FACING_FRONT
        return manager.cameraIdList.firstOrNull { id ->
            val c = manager.getCameraCharacteristics(id)
            c.get(CameraCharacteristics.LENS_FACING) == want &&
                (c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) == true)
        }
    }

    /** The vendor extensions (Night, HDR, ...) the camera of [lens] offers a third-party app, as CameraExtensionCharacteristics.EXTENSION_* values. */
    fun extensionsFor(lens: Lens): Set<Int> {
        val id = idFor(lens) ?: return emptySet()
        return runCatching { manager.getCameraExtensionCharacteristics(id).supportedExtensions.toSet() }.getOrDefault(emptySet())
    }

    private fun stillSizes(id: String, extension: Int?): List<Dim> =
        if (extension != null) manager.getCameraExtensionCharacteristics(id).getExtensionSupportedSizes(extension, ImageFormat.JPEG).map { Dim(it.width, it.height) }
        else manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG).orEmpty().map { Dim(it.width, it.height) }

    private fun previewSizes(id: String, extension: Int?): List<Dim> =
        if (extension != null) manager.getCameraExtensionCharacteristics(id).getExtensionSupportedSizes(extension, SurfaceTexture::class.java).map { Dim(it.width, it.height) }
        else manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty().map { Dim(it.width, it.height) }

    /** The preview size for [lens] (and [extension], whose sizes differ), so the screen can be laid out before the camera is open. Null when the lens is missing. */
    fun previewSizeFor(lens: Lens, extension: Int? = null): Dim? {
        val id = idFor(lens) ?: return null
        val still = pickStillSize(stillSizes(id, extension)) ?: return null
        return pickPreviewSize(previewSizes(id, extension), still.ratio)
    }

    /**
     * Opens [lens] and starts the preview on [surface]. [wantRaw] adds a RAW stream when the camera has one. [onReady] runs on the camera thread
     * once frames are flowing; [onError] says what went wrong.
     */
    @SuppressLint("MissingPermission")   // the screen asks for CAMERA before it ever calls this
    fun open(lens: Lens, surface: Surface, wantRaw: Boolean, extension: Int? = null, onReady: () -> Unit, onError: (String) -> Unit) {
        close()
        val gen = ++generation
        val id = idFor(lens) ?: return onError("This phone has no ${lens.name.lowercase()} camera.")
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return onError("The camera reports no stream sizes.")
        val still = pickStillSize(stillSizes(id, extension)) ?: return onError("No 4:3 photo size.")
        val preview = pickPreviewSize(previewSizes(id, extension), still.ratio) ?: return onError("No preview size.")
        // RAW is the normal pipeline's; an extension hands back its own processed JPEG only.
        val raw = if (extension == null && wantRaw && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true)
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }?.let { Dim(it.width, it.height) } else null

        characteristics = chars; cameraId = id; previewSize = preview; stillSize = still; rawSize = raw; previewSurface = surface; this.extension = extension
        jpegReader = ImageReader.newInstance(still.width, still.height, ImageFormat.JPEG, 3)
        rawReader = raw?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 3) }

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (gen != generation) { camera.close(); return }
                device = camera; startSession(camera, gen, onReady, onError)
            }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); if (gen == generation) { device = null; onError("The camera was disconnected.") } }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); if (gen == generation) { device = null; onError("The camera failed to open (error $error).") } }
        }, handler)
    }

    private fun startSession(camera: CameraDevice, gen: Int, onReady: () -> Unit, onError: (String) -> Unit) {
        extension?.let { return startExtensionSession(camera, gen, it, onReady, onError) }
        val surfaces = listOfNotNull(previewSurface, jpegReader?.surface, rawReader?.surface)
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (gen != generation) { s.close(); return }
                session = s
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface!!)
                        set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    }.build()
                    s.setRepeatingRequest(request, null, handler)
                    onReady()
                } catch (e: Exception) { onError("The preview would not start: ${e.message}") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { if (gen == generation) onError("The camera could not be configured.") }
        }, handler)
    }

    /** The vendor-processed pipeline (Night, HDR): the extension owns the capture, so the session is configured differently and only a JPEG comes back. */
    private fun startExtensionSession(camera: CameraDevice, gen: Int, ext: Int, onReady: () -> Unit, onError: (String) -> Unit) {
        val outputs = listOf(OutputConfiguration(previewSurface!!), OutputConfiguration(jpegReader!!.surface))
        val config = ExtensionSessionConfiguration(ext, outputs, executor, object : CameraExtensionSession.StateCallback() {
            override fun onConfigured(session: CameraExtensionSession) {
                if (gen != generation) { session.close(); return }
                extensionSession = session
                try {
                    val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface!!) }.build()
                    session.setRepeatingRequest(request, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
                    onReady()
                } catch (e: Exception) { onError("The preview would not start: ${e.message}") }
            }
            override fun onConfigureFailed(session: CameraExtensionSession) { if (gen == generation) onError("This phone could not start the extension.") }
        })
        try { camera.createExtensionSession(config) } catch (e: Exception) { onError("The extension would not open: ${e.message}") }
    }

    private fun captureWithExtension(session: CameraExtensionSession, camera: CameraDevice, orientation: Int, chars: CameraCharacteristics, onResult: (StillResult) -> Unit, onError: (String) -> Unit) {
        val jpegs = jpegReader ?: return
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(jpegs.surface); set(CaptureRequest.JPEG_ORIENTATION, orientation) }.build()
            val gather = Gather(wantRaw = false, needsResult = false) { jpeg, _ -> onResult(StillResult(jpeg, orientation, null, System.currentTimeMillis(), chars, "night", needsExifOrientation = true)) }
            jpegs.setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { image -> gather.jpeg(image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) }.let(::trimJpeg) }) } }, handler)
            session.capture(request, executor, object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureFailed(session: CameraExtensionSession, request: CaptureRequest) { gather.fail(); onError("The extension shot failed.") }
            })
        } catch (e: Exception) { Log.w(TAG, "extension capture failed", e); onError("The shot failed: ${e.message}") }
    }

    /**
     * Takes one still. [deviceDegrees] is the phone's tilt (see [jpegOrientation]). With RAW on, one request feeds both streams so the two files
     * are the same instant. [onResult] runs on the camera thread; the caller owns the [RawCapture] it gets and must close it.
     */
    fun capture(deviceDegrees: Int, highQuality: Boolean = false, onResult: (StillResult) -> Unit, onError: (String) -> Unit) {
        val camera = device ?: return onError("The camera is not open.")
        val chars = characteristics ?: return
        val front0 = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val jpegs = jpegReader ?: return
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val orientation = jpegOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, front)
        extensionSession?.let { return captureWithExtension(it, camera, orientation, chars, onResult, onError) }
        val s = session ?: return onError("The camera is not ready.")
        val raws = rawReader
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegs.surface)
                raws?.let { addTarget(it.surface) }
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (highQuality) {
                    // Every processing stage at its best setting (slower): the comparison the probe makes against the default.
                    set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
                    set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_HIGH_QUALITY)
                    set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY)
                    set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_HIGH_QUALITY)
                    set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY)
                    set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
                }
            }.build()

            // A still arrives as up to three events on up to two threads (the JPEG image, the RAW image, the capture result); gather them, then hand over once.
            val gather = Gather(wantRaw = raws != null) { jpeg, raw -> onResult(StillResult(jpeg, orientation, raw, System.currentTimeMillis(), chars, if (highQuality) "hq" else "")) }
            jpegs.setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { image -> gather.jpeg(image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) }.let(::trimJpeg) }) } }, handler)
            raws?.setOnImageAvailableListener({ r -> r.acquireNextImage()?.let { gather.rawImage(it) } }, handler)
            s.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { gather.result(result) }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { gather.fail(); onError("The shot failed (reason ${failure.reason}).") }
            }, handler)
        } catch (e: Exception) {
            Log.w(TAG, "capture failed", e); onError("The shot failed: ${e.message}")
        }
    }

    fun close() {
        generation++   // anything still opening is now stale
        runCatching { extensionSession?.close() }; extensionSession = null; extension = null
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { rawReader?.close() }; rawReader = null
    }

    fun release() { close(); thread.quitSafely() }

    /** Waits for every part of one shot, then delivers it once. Used from the camera thread only. */
    private class Gather(private val wantRaw: Boolean, private val needsResult: Boolean = true, private val done: (ByteArray, RawCapture?) -> Unit) {
        private var jpeg: ByteArray? = null
        private var rawImage: Image? = null
        private var result: TotalCaptureResult? = null
        private var delivered = false

        fun jpeg(bytes: ByteArray) { jpeg = bytes; check() }
        fun rawImage(image: Image) { rawImage = image; check() }
        fun result(r: TotalCaptureResult) { result = r; check() }
        fun fail() { rawImage?.close(); rawImage = null; delivered = true }

        private fun check() {
            if (delivered) return
            val j = jpeg ?: return
            val r = result ?: if (needsResult) return else null
            if (wantRaw && r != null) { val img = rawImage ?: return; delivered = true; done(j, RawCapture(img, r)) } else { delivered = true; done(j, null) }
        }
    }
}
