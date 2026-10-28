package com.everettpenne.holtzman.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface

/** What a finished still is: the JPEG bytes, and (when RAW was asked for and the camera gave it) the DNG-ready sensor data. */
class StillResult(
    val jpeg: ByteArray,
    val orientation: Int,
    val raw: RawCapture?,
    val timestampMillis: Long,
    val characteristics: CameraCharacteristics,
    /** A short word added to the file name ("hq"), or empty. */
    val tag: String = "",
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

    /** Sizes the preview should offer for [lens], so the screen can be laid out before the camera is open. Null when the lens is missing. */
    fun previewSizeFor(lens: Lens): Dim? {
        val id = idFor(lens) ?: return null
        val map = manager.getCameraCharacteristics(id).get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return null
        val still = pickStillSize(map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { Dim(it.width, it.height) }) ?: return null
        return pickPreviewSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty().map { Dim(it.width, it.height) }, still.ratio)
    }

    /**
     * Opens [lens] and starts the preview on [surface]. [wantRaw] adds a RAW stream when the camera has one. [onReady] runs on the camera thread
     * once frames are flowing; [onError] says what went wrong.
     */
    @SuppressLint("MissingPermission")   // the screen asks for CAMERA before it ever calls this
    fun open(lens: Lens, surface: Surface, wantRaw: Boolean, onReady: () -> Unit, onError: (String) -> Unit) {
        close()
        val id = idFor(lens) ?: return onError("This phone has no ${lens.name.lowercase()} camera.")
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return onError("The camera reports no stream sizes.")
        val still = pickStillSize(map.getOutputSizes(ImageFormat.JPEG).orEmpty().map { Dim(it.width, it.height) }) ?: return onError("No 4:3 photo size.")
        val preview = pickPreviewSize(map.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty().map { Dim(it.width, it.height) }, still.ratio)
            ?: return onError("No preview size.")
        val raw = if (wantRaw && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true)
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }?.let { Dim(it.width, it.height) } else null

        characteristics = chars; cameraId = id; previewSize = preview; stillSize = still; rawSize = raw; previewSurface = surface
        jpegReader = ImageReader.newInstance(still.width, still.height, ImageFormat.JPEG, 3)
        rawReader = raw?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, 3) }

        manager.openCamera(id, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) { device = camera; startSession(camera, onReady, onError) }
            override fun onDisconnected(camera: CameraDevice) { camera.close(); device = null; onError("The camera was disconnected.") }
            override fun onError(camera: CameraDevice, error: Int) { camera.close(); device = null; onError("The camera failed to open (error $error).") }
        }, handler)
    }

    private fun startSession(camera: CameraDevice, onReady: () -> Unit, onError: (String) -> Unit) {
        val surfaces = listOfNotNull(previewSurface, jpegReader?.surface, rawReader?.surface)
        camera.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
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
            override fun onConfigureFailed(s: CameraCaptureSession) = onError("The camera could not be configured.")
        }, handler)
    }

    /**
     * Takes one still. [deviceDegrees] is the phone's tilt (see [jpegOrientation]). With RAW on, one request feeds both streams so the two files
     * are the same instant. [onResult] runs on the camera thread; the caller owns the [RawCapture] it gets and must close it.
     */
    fun capture(deviceDegrees: Int, highQuality: Boolean = false, onResult: (StillResult) -> Unit, onError: (String) -> Unit) {
        val camera = device ?: return onError("The camera is not open.")
        val s = session ?: return onError("The camera is not ready.")
        val chars = characteristics ?: return
        val jpegs = jpegReader ?: return
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val orientation = jpegOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, front)
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
            jpegs.setOnImageAvailableListener({ r -> r.acquireNextImage()?.use { image -> gather.jpeg(image.planes[0].buffer.let { b -> ByteArray(b.remaining()).also { b.get(it) } }) } }, handler)
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
        runCatching { session?.close() }; session = null
        runCatching { device?.close() }; device = null
        runCatching { jpegReader?.close() }; jpegReader = null
        runCatching { rawReader?.close() }; rawReader = null
    }

    fun release() { close(); thread.quitSafely() }

    /** Waits for every part of one shot, then delivers it once. Used from the camera thread only. */
    private class Gather(private val wantRaw: Boolean, private val done: (ByteArray, RawCapture?) -> Unit) {
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
            val r = result ?: return
            if (wantRaw) { val img = rawImage ?: return; delivered = true; done(j, RawCapture(img, r)) } else { delivered = true; done(j, null) }
        }
    }
}
