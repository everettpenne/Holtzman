package com.everettpenne.holtzman.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.params.OutputConfiguration
import android.media.CamcorderProfile
import android.media.Image
import android.media.MediaRecorder
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

/**
 * One frame of a burst, copied out of the camera's buffer at once (so the camera is not kept waiting for buffers: it holds only a handful, fewer than
 * a burst has frames). The pixels are tight rows of 16-bit samples in a direct (off-heap) buffer; [result] has the exposure, gains and shading map.
 */
class BurstFrame(val pixels: java.nio.ByteBuffer, val width: Int, val height: Int, val result: TotalCaptureResult, private val owner: android.os.SharedMemory? = null) {
    /**
     * Gives the frame's pixels back now, if they live in shared memory (a burst captured with `offHeap`). Those 24 MB buffers are outside the app's Java
     * heap, so a second burst can be captured while the first is still being processed; nothing may read [pixels] afterwards (it would crash the app).
     */
    fun free() { owner?.let { runCatching { android.os.SharedMemory.unmap(pixels) }; it.close() } }
}

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
    companion object { const val TAG = "HoltzmanCamera"; const val BURST_FRAMES = 6; /** Frames the zero-shutter-lag ring keeps (a little over a quarter of a second at 30 frames a second). */ const val RING_FRAMES = 8; /** The most shake (rad/s) the long capture is allowed: a held phone is about 0.01 to 0.03, a phone on a support under 0.003. */ const val LONG_CAPTURE_MAX_SHAKE = 0.012f }

    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val thread = HandlerThread("holtzman-camera").also { it.start() }
    private val handler = Handler(thread.looper)
    private val shake = ShakeMonitor(context)
    /** How fast the phone is turning now (radians a second), for the dataset's record and for choosing exposures. */
    val shakeRate: Float get() = shake.rate

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

    /** The zoom now applied (1.0 is the main lens; below 1 uses the ultrawide on a logical camera), and the range the camera offers. */
    var flashAvailable: Boolean = false; private set
    var zoom: Float = 1f; private set
    var zoomMin: Float = 1f; private set
    var zoomMax: Float = 1f; private set
    private var focusRegion: IntArray? = null
    private var zoomSupportedInExtension = false
    private var repeatingTemplate: CaptureRequest.Builder? = null
    private val afReset = Runnable { clearFocus() }
    /** The frame number of the capture that carried the latest autofocus trigger (-1 until its result arrives): a focus state from an earlier frame says nothing about that scan. */
    @Volatile private var triggerFrame = -1L
    @Volatile private var precapturing = false

    /** What automatic exposure was doing in the latest preview frame: where a burst starts from. Null until a frame has been seen. */
    class AutoSample(val exposureNs: Long, val iso: Int, val focusDiopters: Float?, val afState: Int?, val frame: Long = -1L)
    @Volatile var autoSample: AutoSample? = null; private set
    /** The faces the camera found in its latest preview frame (empty when none, or when the camera cannot detect faces). */
    @Volatile var latestFaces: List<FaceBox> = emptyList(); private set

    /** The face detection mode to ask for: simple (boxes and scores) when the camera offers it. */
    private fun faceDetectMode(): Int? {
        val modes = characteristics?.get(CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES) ?: return null
        return CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE.takeIf { m -> modes.contains(m) }
    }
    private fun facesOf(result: TotalCaptureResult): List<FaceBox> =
        result.get(CaptureResult.STATISTICS_FACES)?.map { FaceBox(it.bounds.left, it.bounds.top, it.bounds.right, it.bounds.bottom, it.score) } ?: emptyList()
    private val previewCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME); val i = result.get(CaptureResult.SENSOR_SENSITIVITY)
            if (e != null && i != null) autoSample = AutoSample(e, i, result.get(CaptureResult.LENS_FOCUS_DISTANCE), result.get(CaptureResult.CONTROL_AF_STATE), result.frameNumber)
            latestFaces = facesOf(result)
            if (zslEnabled) {
                result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ringResults[it] = result }
                if (ringResults.size > RING_FRAMES * 3) { val oldest = ring.firstOrNull()?.timestamp ?: Long.MAX_VALUE; ringResults.keys.filter { it < oldest }.forEach { ringResults.remove(it) } }
                ringFrameCount++
                updateAutoRegion()
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - ringFpsSince >= 4000) { Log.i(TAG, "instant capture: %.1f frames/s into the ring, ${ring.size} kept".format(java.util.Locale.ROOT, ringFrameCount * 1000f / (now - ringFpsSince))); ringFrameCount = 0; ringFpsSince = now }
            }
            streamFrames++
            // the automatic white balance's own answer, kept so the warmth control can start from it
            result.get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { latestGains = it }
            result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { latestTransform = it }
        }
    }

    @Volatile private var latestGains: android.hardware.camera2.params.RggbChannelVector? = null
    @Volatile private var latestTransform: android.hardware.camera2.params.ColorSpaceTransform? = null
    /** The automatic white balance's gains at the moment warmth moved off zero; held while it is off zero, so the picture does not drift under the slider. */
    private var heldGains: android.hardware.camera2.params.RggbChannelVector? = null
    private var heldTransform: android.hardware.camera2.params.ColorSpaceTransform? = null
    @Volatile private var warmthFactor = 1f
    @Volatile private var streamFrames = 0
    /** True once the camera has been delivering preview frames for a moment since it was opened: it is safe to take a shot. */
    val streaming: Boolean get() = session != null && streamFrames >= 8
    @Volatile private var evSteps = 0

    // ---- Instant capture (zero shutter lag). With it on, every preview frame is also a RAW frame; the newest [RING_FRAMES] are kept, and pressing the
    // shutter makes the burst from the newest ones that were exposed alike: no focusing step and no capture wait, the picture is the moment of the press.
    private class RingFrame(val image: android.media.Image, val timestamp: Long)
    private val ring = java.util.ArrayDeque<RingFrame>()   // guarded by itself: the camera thread fills it, close() empties it
    private val ringResults = HashMap<Long, TotalCaptureResult>()   // camera thread only
    @Volatile var zslEnabled = false; private set
    private var ringFrameCount = 0; private var ringFpsSince = android.os.SystemClock.elapsedRealtime()

    private fun installRingListener() {
        rawReader?.setOnImageAvailableListener({ r ->
            try {
                val img = r.acquireNextImage() ?: return@setOnImageAvailableListener
                synchronized(ring) { ring.addLast(RingFrame(img, img.timestamp)); while (ring.size > RING_FRAMES) ring.removeFirst().image.close() }
            } catch (e: IllegalStateException) {   // every buffer is held: let go of the oldest and carry on
                synchronized(ring) { ring.pollFirst()?.image?.close() }
            }
        }, handler)
    }

    // Instant capture has no focusing step before the shot, so the camera's continuous autofocus is aimed where the shot would have aimed it: at the largest
    // face, else the middle of the frame (whole-frame autofocus prefers the far scene: a fresh scan once focused on a factory behind a desk of near objects).
    @Volatile private var autoRegion: IntArray? = null
    private var autoRegionSince = 0L

    private fun desiredAutoRegion(): IntArray? {
        val chars = characteristics ?: return null
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val face = largestFace(latestFaces)
        val r = if (face != null) faceRegion(face, array.width(), array.height(), 0.1f)
                else meteringBox(sensorPoint(0.5f, 0.5f, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, zoom), array.width(), array.height())
        return intArrayOf(r[0] + array.left, r[1] + array.top, r[2] + array.left, r[3] + array.top)
    }

    /** Called with every preview result while instant capture is on: moves the autofocus area when the face (or the lack of one) moved it by a fair amount, at most twice a second. */
    private fun updateAutoRegion() {
        if (manualFocus != null || focusRegion != null) { autoRegion = null; return }
        val want = desiredAutoRegion() ?: return
        val have = autoRegion
        val now = android.os.SystemClock.elapsedRealtime()
        val moved = have == null || Math.abs(want[0] - have[0]) + Math.abs(want[1] - have[1]) > (want[2] - want[0]) / 3
        if (moved && now - autoRegionSince > 500) { autoRegion = want; autoRegionSince = now; resend() }
    }

    private val copyPool by lazy { java.util.concurrent.Executors.newFixedThreadPool(3) }

    /** One RAW image copied into tight rows in a buffer of its own (shared memory when [offHeap]), and the image given back to the camera. */
    private fun copyRaw(img: android.media.Image, offHeap: Boolean): Triple<java.nio.ByteBuffer, android.os.SharedMemory?, Pair<Int, Int>> {
        val plane = img.planes[0]; val w = img.width; val h = img.height
        val shm = if (offHeap) android.os.SharedMemory.create("holtzman-frame", w * h * 2) else null
        val tight = (shm?.mapReadWrite() ?: java.nio.ByteBuffer.allocateDirect(w * h * 2)).order(java.nio.ByteOrder.nativeOrder())
        val src = plane.buffer.duplicate()   // a view of its own: the copies run on several threads
        if (plane.rowStride == w * 2) { src.rewind(); src.limit(w * h * 2); tight.put(src) }
        else for (y in 0 until h) { src.position(y * plane.rowStride); src.limit(y * plane.rowStride + w * 2); tight.put(src) }
        tight.rewind(); img.close()
        return Triple(tight, shm, w to h)
    }

    private fun clearRing() { synchronized(ring) { ring.forEach { runCatching { it.image.close() } }; ring.clear() }; ringResults.clear() }

    /** Whether a shot made from the ring would have frames to use right now (for the screen to decide between the ring and an ordinary burst). */
    val ringReady: Boolean get() = zslEnabled && synchronized(ring) { ring.size >= 4 }

    /**
     * The burst for a press of the shutter, from the ring: the newest frames that share one exposure ([pickRingFrames]), copied out (so the camera gets its
     * buffers back) and delivered like [captureBurst]'s. If autofocus is still scanning it waits up to 400 ms for it to settle, and if fewer than six frames
     * match it waits up to 600 ms for the stream to supply them; with fewer than three it reports a failure. [onFrame] and [release] are as for [captureBurst].
     */
    fun captureFromRing(onResult: (List<BurstFrame>) -> Unit, onError: (String) -> Unit, offHeap: Boolean = false,
                        onFrame: ((Int, java.nio.ByteBuffer, Int, Int) -> Unit)? = null, release: ((Runnable) -> Unit)? = null) {
        if (!zslEnabled) return onError("Instant capture is off.")
        val started = android.os.SystemClock.uptimeMillis()
        fun attempt() {
            if (!zslEnabled) return onError("The camera closed.")
            val waited = android.os.SystemClock.uptimeMillis() - started
            val af = autoSample?.afState
            val scanning = af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN || af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
            val picked: List<RingFrame>
            synchronized(ring) {
                val frames = ring.filter { ringResults.containsKey(it.timestamp) }
                val entries = frames.map { f -> val r = ringResults.getValue(f.timestamp); RingEntry(f.timestamp, r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 1L, r.get(CaptureResult.SENSOR_SENSITIVITY) ?: 1) }
                picked = pickRingFrames(entries, BURST_FRAMES).map { frames[it] }
            }
            if ((scanning && waited < 400) || (picked.size < BURST_FRAMES && waited < 600)) { handler.postDelayed({ attempt() }, 30); return }
            if (picked.size < 3) return onError("The camera's stream was not steady enough for a photo: try again.")
            synchronized(ring) { ring.removeAll(picked.toSet()) }   // these frames now belong to the photo
            val frames = ArrayList<BurstFrame>(picked.size); val results = picked.map { ringResults.getValue(it.timestamp) }
            val tCopy = android.os.SystemClock.elapsedRealtime()
            try {
                // the camera's buffers are slow for the CPU to read (about 45 ms a frame), so the frames are copied side by side
                val copies = picked.map { f -> copyPool.submit(java.util.concurrent.Callable { copyRaw(f.image, offHeap) }) }
                for ((i, c) in copies.withIndex()) {
                    val (tight, shm, size) = c.get()
                    frames += BurstFrame(tight, size.first, size.second, results[i], shm)
                    onFrame?.invoke(i, tight, size.first, size.second)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "could not copy the ring's frames", t)
                picked.forEach { runCatching { it.image.close() } }
                val free = Runnable { frames.forEach { it.free() } }
                if (release != null) release(free) else free.run()
                return onError("Out of memory keeping the burst's frames.")
            }
            Log.i(TAG, "instant capture: ${frames.size} frames from the ring after waiting $waited ms; copying them took ${android.os.SystemClock.elapsedRealtime() - tCopy} ms ")
            onResult(frames)
        }
        handler.post { attempt() }
    }

    // Manual exposure and focus (the Pro controls). Setting only the ISO or only the shutter keeps the picture as bright as automatic exposure had it at the
    // moment the first was set (the other is worked out), so it is "ISO priority" or "shutter priority"; both set is fully manual.
    @Volatile private var manualIso: Int? = null
    @Volatile private var manualNs: Long? = null
    @Volatile private var manualFocus: Float? = null
    private var heldAuto: AutoSample? = null

    /** True while the exposure is the user's (ISO and/or shutter set): the shake-aware exposure and the brightness normalisation stand aside. */
    val manualExposure: Boolean get() = manualIso != null || manualNs != null
    val manualFocusOn: Boolean get() = manualFocus != null
    val isoRange: IntRange get() = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper } ?: 100..3200
    val exposureRangeNs: LongRange get() = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper } ?: 100_000L..250_000_000L
    /** The nearest the lens can focus, in diopters (1 / metres); 0 when focus is fixed. */
    val minFocusDiopters: Float get() = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    /** The exposure the camera is using now, from the latest preview frame (ISO, shutter in ns), for showing beside the sliders. */
    val currentExposure: Pair<Int, Long>? get() = autoSample?.let { it.iso to it.exposureNs }

    /** Sets the ISO and/or the shutter time (null leaves that one to the camera; both null is automatic exposure again). */
    fun setManualExposure(iso: Int?, ns: Long?) {
        val was = manualExposure
        manualIso = iso; manualNs = ns
        if (iso == null && ns == null) heldAuto = null else if (!was) heldAuto = autoSample
        resend()
    }

    /** Focuses at [diopters] (0 = infinity) and holds it; null returns to continuous autofocus. */
    fun setManualFocus(diopters: Float?) { manualFocus = diopters?.coerceIn(0f, minFocusDiopters); resend() }

    /** The exposure compensation range in stops that this camera offers, and its step. */
    val exposureRangeEv: ClosedFloatingPointRange<Float>
        get() { val r = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE); val st = exposureStepEv; return if (r == null) 0f..0f else (r.lower * st)..(r.upper * st) }
    private val exposureStepEv: Float get() = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

    /** Brightens or darkens the live picture (and so the burst's exposures) by [ev] stops, to the nearest step the camera has; returns what it applied. */
    fun setExposureCompensation(ev: Float): Float {
        val st = exposureStepEv; val r = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        if (st <= 0f || r == null) return 0f
        val steps = Math.round(ev / st).coerceIn(r.lower, r.upper)
        if (steps != evSteps) { evSteps = steps; resend() }   // a slider moves through many values per step
        return evSteps * st
    }

    /** Moves the live picture's white balance by [factor]: the red gain is multiplied by it and the blue divided (1 = automatic white balance again). */
    fun setWarmth(factor: Float) {
        warmthFactor = factor
        if (factor == 1f) { heldGains = null; heldTransform = null }
        else if (heldGains == null) { heldGains = latestGains; heldTransform = latestTransform }
        resend()
    }

    /** Set when the camera was opened for video: the size recorded. */
    var videoQuality: VideoQuality? = null; private set
    private var recorder: MediaRecorder? = null
    @Volatile var recording: Boolean = false; private set
    /** Told once when a recording ends, however it ends (stopped, or the screen left): true when a usable file was written. The caller finishes or removes the file. */
    var onRecordingEnded: ((Boolean) -> Unit)? = null

    /** The video sizes the camera of [lens] can record (from the phone's own camcorder profiles). */
    fun videoQualitiesFor(lens: Lens): List<VideoQuality> {
        val id = idFor(lens)?.toIntOrNull() ?: return emptyList()
        return VideoQuality.entries.filter { runCatching { CamcorderProfile.hasProfile(id, it.camcorder) }.getOrDefault(false) }
    }

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
    fun previewSizeFor(lens: Lens, extension: Int? = null, video: Boolean = false): Dim? {
        val id = idFor(lens) ?: return null
        if (video) return pickPreviewSize(previewSizes(id, null), 16f / 9f)
        val still = pickStillSize(stillSizes(id, extension)) ?: return null
        return pickPreviewSize(previewSizes(id, extension), still.ratio)
    }

    /**
     * Opens [lens] and starts the preview on [surface]. [wantRaw] adds a RAW stream when the camera has one. [onReady] runs on the camera thread
     * once frames are flowing; [onError] says what went wrong.
     */
    @SuppressLint("MissingPermission")   // the screen asks for CAMERA before it ever calls this
    fun open(lens: Lens, surface: Surface, wantRaw: Boolean, extension: Int? = null, video: VideoQuality? = null, burst: Boolean = false, zsl: Boolean = false, onReady: () -> Unit, onError: (String) -> Unit) {
        close()
        shake.start()
        val gen = ++generation
        val id = idFor(lens) ?: return onError("This phone has no ${lens.name.lowercase()} camera.")
        val chars = manager.getCameraCharacteristics(id)
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return onError("The camera reports no stream sizes.")
        // for video the picture is 16:9 and there is no still stream at all; the recorder's surface joins the session only while recording
        val still = if (video != null) null else pickStillSize(stillSizes(id, extension)) ?: return onError("No 4:3 photo size.")
        val preview = (if (video != null) pickPreviewSize(previewSizes(id, null), 16f / 9f) else pickPreviewSize(previewSizes(id, extension), still!!.ratio)) ?: return onError("No preview size.")
        // RAW is the normal pipeline's; an extension hands back its own processed JPEG only.
        val raw = if (video == null && extension == null && wantRaw && chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true)
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width.toLong() * it.height }?.let { Dim(it.width, it.height) } else null

        val zr = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
        zoomMin = zr?.lower ?: 1f; zoomMax = zr?.upper ?: 1f; zoom = clampZoom(1f, zoomMin, zoomMax); focusRegion = null
        zoomSupportedInExtension = extension != null && runCatching { manager.getCameraExtensionCharacteristics(id).getAvailableCaptureRequestKeys(extension).contains(CaptureRequest.CONTROL_ZOOM_RATIO) }.getOrDefault(false)
        flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        characteristics = chars; cameraId = id; previewSize = preview; stillSize = still; rawSize = raw; previewSurface = surface; this.extension = extension; videoQuality = video
        jpegReader = still?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 3) }
        zslEnabled = zsl && raw != null && extension == null && video == null
        rawReader = raw?.let { ImageReader.newInstance(it.width, it.height, ImageFormat.RAW_SENSOR, if (zslEnabled) RING_FRAMES + 3 else if (burst) BURST_FRAMES + 1 else 3) }

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
                    repeatingTemplate = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(previewSurface!!)
                        if (zslEnabled) { addTarget(rawReader!!.surface); installRingListener() }   // every frame is also a RAW frame, kept in the ring
                    }
                    val request = previewRequest()
                    s.setRepeatingRequest(request, previewCallback, handler)
                    onReady()
                } catch (e: Exception) { onError("The preview would not start: ${e.message}") }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { if (gen == generation) onError("The camera could not be configured.") }
        }, handler)
    }

    /** The lens's focal length in sensor pixels (focal length over pixel pitch): how far a turn of the camera moves the picture. */
    private fun focalLengthPx(): Float {
        val c = characteristics ?: return 0f
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 0f
        val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 0f
        val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return 0f
        return f / (size.width / px.width)
    }

    /** The repeating preview request with the zoom and focus area now wanted. */
    private fun previewRequest(): CaptureRequest {
        val b = repeatingTemplate!!
        if (extension == null || zoomSupportedInExtension) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        if (extension == null) {
            val mf = manualFocus
            if (mf != null && videoQuality == null) { b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF); b.set(CaptureRequest.LENS_FOCUS_DISTANCE, mf) }
            else b.set(CaptureRequest.CONTROL_AF_MODE, if (videoQuality != null) CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO else CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            val mIso = manualIso; val mNs = manualNs
            if ((mIso != null || mNs != null) && videoQuality == null) {
                val base = heldAuto ?: autoSample
                val product = (base?.exposureNs ?: 20_000_000L).toDouble() * (base?.iso ?: 400)   // brightness automatic exposure had: shutter x ISO
                val ns = (mNs ?: (product / (mIso ?: 400)).toLong()).coerceIn(exposureRangeNs.first, exposureRangeNs.last)
                val iso = (mIso ?: (product / ns).toInt()).coerceIn(isoRange.first, isoRange.last)
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, ns); b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evSteps)
            }
            val g = heldGains; val tr = heldTransform; val k = warmthFactor
            if (k != 1f && g != null && tr != null) {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                b.set(CaptureRequest.COLOR_CORRECTION_GAINS, android.hardware.camera2.params.RggbChannelVector(g.red * k, g.greenEven, g.greenOdd, g.blue / k))
                b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, tr)
            } else {
                b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            }
            faceDetectMode()?.let { b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, it) }
            if (videoQuality != null) stabilise(b)
            val r = focusRegion
            if (r != null) {
                val area = arrayOf(android.hardware.camera2.params.MeteringRectangle(r[0], r[1], r[2] - r[0], r[3] - r[1], android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX))
                b.set(CaptureRequest.CONTROL_AF_REGIONS, area); b.set(CaptureRequest.CONTROL_AE_REGIONS, area)
            } else {
                val a = autoRegion
                if (zslEnabled && manualFocus == null && a != null) b.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(android.hardware.camera2.params.MeteringRectangle(a[0], a[1], a[2] - a[0], a[3] - a[1], android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX)))
                else b.set(CaptureRequest.CONTROL_AF_REGIONS, null)
                b.set(CaptureRequest.CONTROL_AE_REGIONS, null)
            }
        }
        return b.build()
    }

    private fun resend() {
        if (repeatingTemplate == null) return
        handler.post {
            try {
                session?.setRepeatingRequest(previewRequest(), previewCallback, handler)
                extensionSession?.setRepeatingRequest(previewRequest(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
            } catch (e: Exception) { Log.w(TAG, "could not update the preview", e) }
        }
    }

    /** Sets the zoom (clamped to what the camera offers) and returns what was applied. Below 1x a logical camera hands over to its ultrawide lens. */
    fun setZoom(ratio: Float): Float {
        zoom = clampZoom(ratio, zoomMin, zoomMax)
        resend()
        return zoom
    }

    /**
     * Focuses and meters on a point of the upright preview ([u], [v] as fractions of its width and height): the area is set on the preview, an
     * autofocus scan is triggered once, and after a few seconds the camera goes back to continuous focus on the whole frame. The vendor
     * extensions choose their own focus, so this does nothing there.
     */
    fun focusAt(u: Float, v: Float) {
        if (manualFocus != null) return   // focus is the user's
        val chars = characteristics ?: return
        val s = session ?: return
        val device = device ?: return
        if (extension != null) return
        val array = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val z = zoom
        val mapped = sensorPoint(u, v, chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, z)
        lockOn(meteringBox(mapped, array.width(), array.height()).let { intArrayOf(it[0] + array.left, it[1] + array.top, it[2] + array.left, it[3] + array.top) })
    }

    /** Focus and meter on [region] (left, top, right, bottom in the sensor's coordinates) and trigger a scan; continuous focus resumes after 3.5 s. */
    private fun lockOn(region: IntArray) {
        val s = session ?: return
        val device = device ?: return
        focusRegion = region
        resend()
        handler.post {
            try {
                val trigger = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface!!)
                    set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                    set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                }.build()
                triggerFrame = -1L
                s.capture(trigger, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { triggerFrame = result.frameNumber }
                }, handler)
            } catch (e: Exception) { Log.w(TAG, "focus trigger failed", e) }
        }
        handler.removeCallbacks(afReset); handler.postDelayed(afReset, 3500)
    }

    private fun clearFocus() { focusRegion = null; resend() }

    /**
     * Runs one autofocus scan and waits for it to lock (at most 1.5 s), then calls [then], so a shot is focused on what the camera sees as the shutter
     * is pressed rather than on whatever continuous autofocus last settled on: in a scene with a near subject and a far background it had settled on
     * the background, and the first dataset shots came out sharp behind and soft in front. Skipped when the user has tapped to focus (that is already
     * a lock) and in the vendor extensions. Continuous focus resumes a few seconds later.
     */
    fun focusForShot(then: () -> Unit) {
        val s = session; val device = device
        if (s == null || device == null || extension != null || focusRegion != null || manualFocus != null) { then(); return }
        val started = android.os.SystemClock.uptimeMillis()
        // Autofocus over the whole frame prefers the far scene (more contrast to lock onto): a fresh scan still focused on the factory behind a desk of near
        // objects. So the scan is aimed at the centre of the frame, the way a tap on it would be, unless the user has chosen a spot.
        val array = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val face = largestFace(latestFaces)
        if (face != null && array != null) {
            // a face is what the picture is about: focus and meter on it, not on the middle of the frame
            val r = faceRegion(face, array.width(), array.height(), 0.1f)
            Log.i(TAG, "shutter focus on a face (score ${face.score}, ${face.right - face.left} px wide)")
            lockOn(intArrayOf(r[0] + array.left, r[1] + array.top, r[2] + array.left, r[3] + array.top))
        } else focusAt(0.5f, 0.5f)
        handler.post {
            fun poll() {
                val waited = android.os.SystemClock.uptimeMillis() - started
                val state = autoSample?.afState
                // A locked state counts only from a frame after the trigger's own: a lock left over from before it would end the wait with the lens
                // still moving. (This replaces a blind 200 ms minimum wait, which cost every shot about 0.28 s.)
                val fresh = (autoSample?.frame ?: -1L) > triggerFrame && triggerFrame >= 0
                if ((state == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || state == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) && fresh || waited > 1500) {
                    Log.i(TAG, "shutter focus: state $state after ${waited} ms, ${autoSample?.focusDiopters} diopters")
                    handler.removeCallbacks(afReset); handler.postDelayed(afReset, 4000)
                    then()
                } else handler.postDelayed({ poll() }, 10)
            }
            handler.post { poll() }
        }
    }

    /** Electronic stabilisation for video when the camera has it, otherwise optical when it has that. */
    private fun stabilise(b: CaptureRequest.Builder) {
        val chars = characteristics ?: return
        if (chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true)
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON)
        else if (chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON) == true)
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
    }

    /**
     * Starts recording into [fd] (a file the caller made: its name and where it lives are its business). The session is rebuilt with the recorder's
     * surface beside the preview, then the recorder starts; this takes about half a second. [withAudio] adds the microphone. [onStarted] and [onError]
     * run on the camera thread.
     */
    fun startRecording(fd: java.io.FileDescriptor, withAudio: Boolean, deviceDegrees: Int, onStarted: () -> Unit, onError: (String) -> Unit) {
        val camera = device ?: return onError("The camera is not open.")
        val quality = videoQuality ?: return onError("The camera is not in video mode.")
        val id = cameraId?.toIntOrNull() ?: return onError("Unknown camera.")
        val chars = characteristics ?: return
        val gen = generation
        val profile = runCatching { CamcorderProfile.get(id, quality.camcorder) }.getOrNull() ?: return onError("This phone cannot record ${quality.label}.")
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        handler.post {
            try {
                val r = MediaRecorder(context)
                if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(profile.fileFormat)
                r.setOutputFile(fd)
                r.setVideoEncodingBitRate(profile.videoBitRate); r.setVideoFrameRate(profile.videoFrameRate)
                r.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
                r.setVideoEncoder(profile.videoCodec)
                if (withAudio) { r.setAudioEncoder(profile.audioCodec); r.setAudioEncodingBitRate(profile.audioBitRate); r.setAudioSamplingRate(profile.audioSampleRate); r.setAudioChannels(profile.audioChannels) }
                r.setOrientationHint(videoOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, front))
                r.prepare()
                recorder = r
                runCatching { session?.close() }; session = null
                camera.createCaptureSession(listOf(previewSurface!!, r.surface), object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (gen != generation) { s.close(); runCatching { r.release() }; return }
                        session = s
                        try {
                            repeatingTemplate = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply { addTarget(previewSurface!!); addTarget(r.surface) }
                            s.setRepeatingRequest(previewRequest(), null, handler)
                            r.start(); recording = true
                            onStarted()
                        } catch (e: Exception) { Log.w(TAG, "recording would not start", e); abandonRecorder(); onError("Recording would not start: ${e.message}") }
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) { abandonRecorder(); onError("The camera could not be configured for video.") }
                }, handler)
            } catch (e: Exception) { Log.w(TAG, "recorder setup failed", e); abandonRecorder(); onError("Recording would not start: ${e.message}") }
        }
    }

    private fun abandonRecorder() { val was = recording; recording = false; runCatching { recorder?.release() }; recorder = null; if (was.not()) onRecordingEnded?.invoke(false) }

    /** Stops recording and puts the preview back. [onStopped] gets true when a usable file was written (a recording stopped within a moment of starting has none). */
    fun stopRecording(onStopped: (Boolean) -> Unit) {
        val r = recorder
        val camera = device
        handler.post {
            var ok = true
            if (r != null) { try { r.stop() } catch (e: RuntimeException) { ok = false } ; runCatching { r.release() } }
            recorder = null; recording = false
            onRecordingEnded?.invoke(ok)
            runCatching { session?.close() }; session = null
            if (camera != null) startSession(camera, generation, onReady = { onStopped(ok) }, onError = { onStopped(false) }) else onStopped(false)
        }
    }

    /** The vendor-processed pipeline (Night, HDR): the extension owns the capture, so the session is configured differently and only a JPEG comes back. */
    private fun startExtensionSession(camera: CameraDevice, gen: Int, ext: Int, onReady: () -> Unit, onError: (String) -> Unit) {
        val outputs = listOf(OutputConfiguration(previewSurface!!), OutputConfiguration(jpegReader!!.surface))
        val config = ExtensionSessionConfiguration(ext, outputs, executor, object : CameraExtensionSession.StateCallback() {
            override fun onConfigured(session: CameraExtensionSession) {
                if (gen != generation) { session.close(); return }
                extensionSession = session
                try {
                    repeatingTemplate = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply { addTarget(previewSurface!!) }
                    session.setRepeatingRequest(previewRequest(), executor, object : CameraExtensionSession.ExtensionCaptureCallback() {})
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
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply { addTarget(jpegs.surface); set(CaptureRequest.JPEG_ORIENTATION, orientation); if (zoomSupportedInExtension) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom) }.build()
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
    fun capture(deviceDegrees: Int, highQuality: Boolean = false, flash: FlashMode = FlashMode.OFF, onResult: (StillResult) -> Unit, onError: (String) -> Unit) {
        val camera = device ?: return onError("The camera is not open.")
        val chars = characteristics ?: return
        val front0 = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val jpegs = jpegReader ?: return
        val front = chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        val orientation = jpegOrientation(chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, front)
        extensionSession?.let { return captureWithExtension(it, camera, orientation, chars, onResult, onError) }
        val s = session ?: return onError("The camera is not ready.")
        val aeMode = aeModeFor(flash, flashAvailable)
        if (aeMode != 1 && !precapturing) {
            // With the flash in play the exposure is metered first (a precapture sequence), so the flash and the ambient light are balanced; then the shot.
            precapturing = true
            try {
                val pre = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                    addTarget(previewSurface!!)
                    set(CaptureRequest.CONTROL_AE_MODE, aeMode); set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                    set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                }.build()
                s.capture(pre, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                        handler.postDelayed({ capture(deviceDegrees, highQuality, flash, onResult, onError) }, 500)
                    }
                    override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) { precapturing = false; onError("The flash metering failed.") }
                }, handler)
            } catch (e: Exception) { precapturing = false; onError("The flash metering failed: ${e.message}") }
            return
        }
        precapturing = false
        val raws = rawReader
        try {
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(jpegs.surface)
                raws?.let { addTarget(it.surface) }
                set(CaptureRequest.JPEG_ORIENTATION, orientation)
                set(CaptureRequest.JPEG_QUALITY, 97.toByte())
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                set(CaptureRequest.CONTROL_AE_MODE, aeMode)
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

    /**
     * Takes [BURST_FRAMES] RAW frames back to back at the exposure and ISO automatic exposure had just chosen, held fixed so every frame matches
     * (a merge needs equal frames). Each frame comes with its result (exposure, gains, lens-shading map, black levels). [onResult] gets the frames and
     * their results in order, on the camera thread; the caller owns the images and must close them all.
     */
    /**
     * [onFrame] is told of each frame (its index, pixels and size) the moment it is in memory, on the camera thread: hand the work to another thread, and
     * read the pixels only until the frames are freed. [release] runs the code that unmaps a failed burst's off-heap frames: give it the thread that
     * reads them, so the unmapping comes after anything [onFrame] queued there (the default runs it at once).
     */
    fun captureBurst(onResult: (List<BurstFrame>) -> Unit, onError: (String) -> Unit, frames: Int = BURST_FRAMES, iso: Int? = null, fixed: AutoSample? = null, offHeap: Boolean = false,
                     onFrame: ((Int, java.nio.ByteBuffer, Int, Int) -> Unit)? = null, release: ((Runnable) -> Unit)? = null,
                     /** In dim light, with the phone steady: more frames, each exposed longer at a lower ISO (see below). [onPlan] hears how many frames and how many seconds the capture will take. */
                     longCapture: Boolean = false, onPlan: ((Int, Float) -> Unit)? = null) {
        if (zslEnabled) return onError("Instant capture is on: a standard burst needs it off (Photo settings > Capture).")
        val camera = device ?: return onError("The camera is not open.")
        val s = session ?: return onError("The camera is not ready.")
        val raws = rawReader ?: return onError("This camera has no RAW stream for a burst.")
        val auto = fixed ?: autoSample ?: return onError("Wait a moment for the exposure to settle.")
        Log.i(TAG, "burst focus: preview had ${auto.focusDiopters} diopters, AF state ${auto.afState}; shake %.4f rad/s".format(java.util.Locale.ROOT, shake.rate))
        // a fixed ISO is for calibration: the exposure time moves the other way so the picture is as bright as automatic exposure chose
        var useIso = iso ?: auto.iso
        var useNs = if (iso == null) auto.exposureNs else (auto.exposureNs.toDouble() * auto.iso / iso).toLong()
        if (iso == null && fixed == null && !manualExposure) {
            // a shaky hand in dim light: shorter, noisier frames beat blurred ones, and the merge absorbs the noise
            val limit = shakeExposureLimitNs(shake.rate, focalLengthPx())
            val isoMax = minOf(characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.upper ?: 3200, 3200)
            val (ns, isoNew) = shakeAdjusted(useNs, useIso, limit, isoMax)
            if (ns != useNs) Log.i(TAG, "shake %.3f rad/s: exposure %d to %d us, ISO %d to %d".format(java.util.Locale.ROOT, shake.rate, useNs / 1000, ns / 1000, useIso, isoNew))
            useNs = ns; useIso = isoNew
        }
        val isoRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val nsRange = characteristics?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        // The longer low-light capture. Automatic exposure keeps each frame short (a quarter of a second would blur a handheld picture and freeze the
        // viewfinder), so in dim light every frame is a noisy, high-ISO one. With the phone steady, the same light can be gathered in fewer, longer frames at a
        // lower ISO (less read noise per photon) and there are twice as many of them: up to 8x the exposure time, at most a quarter of a second, 12 frames.
        var count = frames
        if (longCapture && iso == null && fixed == null && !manualExposure && shake.rate < LONG_CAPTURE_MAX_SHAKE && (useIso >= 200 || com.everettpenne.holtzman.save.DebugFlags.on("longtest"))) {   // longtest: for trying it in a bright room

            val minIso = isoRange?.lower ?: 50
            val k = if (com.everettpenne.holtzman.save.DebugFlags.on("longtest")) 4.0 else minOf(8.0, 250_000_000.0 / useNs, useIso.toDouble() / minIso)   // longtest: a bright room, only to try the 12-frame path
            if (k > 1.3) {
                Log.i(TAG, "long capture: exposure %d to %d us, ISO %d to %d, 12 frames".format(java.util.Locale.ROOT, useNs / 1000, (useNs * k).toLong() / 1000, useIso, Math.max(minIso, Math.round(useIso / k).toInt())))
                useNs = (useNs * k).toLong(); useIso = Math.max(minIso, Math.round(useIso / k).toInt()); count = 12
            }
        }
        if (longCapture && count == frames) Log.i(TAG, "long capture not used: shake %.4f rad/s (limit %.3f), ISO %d, manual exposure %b".format(java.util.Locale.ROOT, shake.rate, LONG_CAPTURE_MAX_SHAKE, useIso, manualExposure))
        val n = count
        onPlan?.invoke(n, n * useNs / 1e9f)
        if (iso != null && (isoRange?.contains(iso) != true || nsRange?.contains(useNs) != true)) return onError("ISO $iso needs an exposure of ${useNs / 1000} us, outside what this sensor allows.")
        try {
            val builder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                addTarget(raws.surface)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, useNs); set(CaptureRequest.SENSOR_SENSITIVITY, useIso)
                // Focus is held where the preview's autofocus had it, and said so explicitly. With autofocus off and no focus distance in the request the
                // lens goes to whatever the camera defaults to, not where it was: the first side-by-side against the Pixel camera app showed the burst
                // sharp on the floor and soft on the subject the preview had focused on.
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                auto.focusDiopters?.let { set(CaptureRequest.LENS_FOCUS_DISTANCE, it) }
                set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
                // no HAL noise reduction, sharpening or tone curve on a frame that will be merged: the merge is the noise reduction
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                faceDetectMode()?.let { set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, it) }
            }
            val requests = List(n) { builder.build() }
            val images = ArrayList<java.nio.ByteBuffer>(n); val owners = ArrayList<android.os.SharedMemory?>(n); val results = ArrayList<TotalCaptureResult>(n); val dims = intArrayOf(0, 0)
            var delivered = false
            fun freeAll() {
                val mapped = images.toList(); val shms = owners.toList()
                images.clear(); owners.clear(); results.clear()
                val unmap = Runnable { for (i in mapped.indices) shms.getOrNull(i)?.let { shm -> runCatching { android.os.SharedMemory.unmap(mapped[i]) }; shm.close() } }
                if (release != null) release(unmap) else unmap.run()
            }
            fun check() {
                if (delivered || images.size < n || results.size < n) return
                delivered = true
                val burst = List(n) { BurstFrame(images[it], dims[0], dims[1], results[it], owners[it]) }
                // The listener and the capture callback outlive this call, and they hold these lists: left full they keep the whole burst (six 24 MB
                // buffers that count against the app's heap) alive until the next burst replaces them, so two bursts in a row needed twice the memory
                // and the second one ran the app out of it.
                images.clear(); owners.clear(); results.clear()
                raws.setOnImageAvailableListener(null, null)
                onResult(burst)
            }
            val tReq = android.os.SystemClock.elapsedRealtime(); var firstLogged = false
            raws.setOnImageAvailableListener({ r ->
                if (!firstLogged) { firstLogged = true; Log.i("HoltzmanTiming", "first RAW frame ${android.os.SystemClock.elapsedRealtime() - tReq} ms after the burst request (exposure ${useNs / 1000} us, ISO $useIso)") }
                try { r.acquireNextImage()?.let { img ->
                    // copy the rows out (dropping the stride padding) and hand the camera its buffer back straight away
                    val plane = img.planes[0]
                    val w = img.width; val h = img.height
                    // off the Java heap when the caller will free the frames itself (see BurstFrame.free), else an ordinary direct buffer
                    val shm = if (offHeap) android.os.SharedMemory.create("holtzman-frame", w * h * 2) else null
                    val tight = (shm?.mapReadWrite() ?: java.nio.ByteBuffer.allocateDirect(w * h * 2)).order(java.nio.ByteOrder.nativeOrder())
                    val src = plane.buffer
                    if (plane.rowStride == w * 2) { src.rewind(); src.limit(w * h * 2); tight.put(src); src.clear() }   // no padding: one copy
                    else for (y in 0 until h) { src.position(y * plane.rowStride); src.limit(y * plane.rowStride + w * 2); tight.put(src); src.clear() }
                    tight.rewind()
                    img.close()
                    synchronized(images) { dims[0] = w; dims[1] = h; images += tight; owners += shm; onFrame?.invoke(images.size - 1, tight, w, h); check() }
                }
            } catch (t: Throwable) {
                // an allocation failure here would otherwise end the whole app on the camera thread: report it as a failed burst instead
                Log.w(TAG, "burst frame could not be kept", t)
                synchronized(images) { if (!delivered) { delivered = true; freeAll(); onError("Out of memory keeping the burst's frames.") } }
            }
            }, handler)
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) { synchronized(images) { results += result; check() } }
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    synchronized(images) { if (!delivered) { delivered = true; freeAll(); onError("A burst frame failed (reason ${failure.reason}).") } }
                }
            }, handler)
        } catch (e: Exception) { Log.w(TAG, "burst failed", e); onError("The burst failed: ${e.message}") }
    }

    fun close() {
        shake.stop()
        if (recording) {
            val ok = runCatching { recorder?.stop() }.isSuccess
            abandonRecorder(); onRecordingEnded?.invoke(ok)
        }
        handler.removeCallbacks(afReset); repeatingTemplate = null
        generation++   // anything still opening is now stale
        zslEnabled = false; autoRegion = null; streamFrames = 0
        clearRing()
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
