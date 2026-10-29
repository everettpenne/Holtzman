package com.everettpenne.holtzman.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.view.OrientationEventListener
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.CaptureContract
import com.everettpenne.holtzman.camera.*
import com.everettpenne.holtzman.save.BurstProcessor
import com.everettpenne.holtzman.save.BurstRender
import com.everettpenne.holtzman.save.NoiseCalibration
import com.everettpenne.holtzman.save.PhotoSaver
import com.everettpenne.holtzman.save.UltraHdr
import com.everettpenne.holtzman.save.VideoSaver
import com.everettpenne.holtzman.save.PendingVideo
import com.everettpenne.holtzman.save.Saved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.asCoroutineDispatcher

/** Where finished bursts are processed: one at a time, because the GPU context, the TPU interpreter's buffers and the heap are all sized for one. */
private val burstExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "holtzman-burst").apply { priority = Thread.NORM_PRIORITY } }
private val burstQueue = burstExecutor.asCoroutineDispatcher()

@Composable
fun HoltzmanRoot(startInVideo: Boolean = false, onCaptured: ((android.net.Uri, String) -> Unit)? = null) = HoltzmanTheme { CameraGate(startInVideo, onCaptured) }

/** Asks for the camera, in words, before anything else; the camera is the one permission the app has. */
@Composable
private fun CameraGate(startInVideo: Boolean, onCaptured: ((android.net.Uri, String) -> Unit)?) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) CameraScreen(startInVideo, onCaptured) else Box(Modifier.fillMaxSize().background(Color.Black).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.PhotoCamera, null, tint = Holtzman.accent, modifier = Modifier.size(56.dp))
            Text("Holtzman needs the camera", style = IosType.title2, color = Color.White)
            Text("It uses nothing else: no location, no storage permission, no network. Photos are saved through Android's own media library.",
                style = IosType.subheadline, color = Color.White.copy(alpha = .7f))
            Box(Modifier.clip(RoundedCornerShape(50)).background(Holtzman.accent).clickable { ask.launch(Manifest.permission.CAMERA) }.padding(horizontal = 28.dp, vertical = 14.dp)) {
                Text("Allow the camera", style = IosType.headline, color = Color.Black)
            }
        }
    }
}

/** The camera: the picture at its true 4:3, a shutter, the other lens, RAW when the camera has it, and the probe. */
@Composable
private fun CameraScreen(startInVideo: Boolean, onCaptured: ((android.net.Uri, String) -> Unit)?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraController(context) }
    val saver = remember { PhotoSaver(context.contentResolver) }
    var lens by remember { mutableStateOf(Lens.BACK) }
    val prefs = remember { context.getSharedPreferences("holtzman", android.content.Context.MODE_PRIVATE) }
    var raw by remember { mutableStateOf(prefs.getBoolean("raw", false)) }
    var rawAvailable by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var hdr by remember { mutableStateOf(prefs.getBoolean("hdr", true)) }
    // the computational burst is the normal way to take a photo; a setting turns it off for a quick single frame
    var burst by remember { mutableStateOf(prefs.getBoolean("computational", true)) }
    LaunchedEffect(raw, hdr, burst) { prefs.edit().putBoolean("raw", raw).putBoolean("hdr", hdr).putBoolean("computational", burst).apply() }
    // the finish controls in Settings (computational photos only): texture -1..1, grain 0..1; each shot takes the values it was taken with
    var texture by remember { mutableStateOf(prefs.getFloat("texture", 0f)) }
    var grain by remember { mutableStateOf(prefs.getFloat("grain", BurstRender.LookTuning.DEFAULT_GRAIN)) }
    LaunchedEffect(texture, grain) { prefs.edit().putFloat("texture", texture).putFloat("grain", grain).apply() }
    // the controls panel (bottom right, as in the Pixel camera app): a look, exposure compensation in stops, shadows and warmth in the Pixel app's -20..20 units.
    // They are for the next shots and start from neutral each time the app opens; the crop is kept.
    var showControls by remember { mutableStateOf(false) }
    var controlTab by remember { mutableStateOf(ControlTab.LOOK) }
    var preset by remember { mutableStateOf(BurstRender.LookPreset.STANDARD) }
    var evValue by remember { mutableStateOf(0f) }
    var shadowsValue by remember { mutableStateOf(0f) }
    var warmthValue by remember { mutableStateOf(0f) }
    // the Pro controls: slider positions 0..1, null = left to the camera
    var isoT by remember { mutableStateOf<Float?>(null) }
    var shutterT by remember { mutableStateOf<Float?>(null) }
    var focusT by remember { mutableStateOf<Float?>(null) }
    val manualExposureOn = isoT != null || shutterT != null
    // the sliders hold the raw position; near the middle the control reads as exactly neutral (a detent), applied here and not in the slider so a slow drag can leave it
    val evEff = if (kotlin.math.abs(evValue) < 0.12f) 0f else evValue
    val shadowsEff = if (kotlin.math.abs(shadowsValue) < 1.5f) 0f else shadowsValue
    val warmthEff = if (kotlin.math.abs(warmthValue) < 1.5f) 0f else warmthValue
    var cropRatio by remember { mutableStateOf(PhotoRatio.entries.getOrElse(prefs.getInt("ratio", 0)) { PhotoRatio.FULL }) }
    var showPhotoSettings by remember { mutableStateOf(false) }
    var longCapture by remember { mutableStateOf(prefs.getBoolean("longCapture", false)) }
    LaunchedEffect(longCapture) { prefs.edit().putBoolean("longCapture", longCapture).apply() }
    var levelOn by remember { mutableStateOf(prefs.getBoolean("level", false)) }
    var histogramOn by remember { mutableStateOf(prefs.getBoolean("histogram", false)) }
    LaunchedEffect(levelOn, histogramOn) { prefs.edit().putBoolean("level", levelOn).putBoolean("histogram", histogramOn).apply() }
    LaunchedEffect(cropRatio) { prefs.edit().putInt("ratio", cropRatio.ordinal).apply() }
    var dataset by remember { mutableStateOf(false) }
    var showDataset by remember { mutableStateOf(false) }
    var datasetKind by remember { mutableStateOf(com.everettpenne.holtzman.save.DatasetKind.HANDHELD) }
    var datasetTags by remember { mutableStateOf(setOf<String>()) }
    var datasetNote by remember { mutableStateOf("") }
    var datasetSaved by remember { mutableIntStateOf(0) }
    var videoMode by remember { mutableStateOf(startInVideo) }
    var recording by remember { mutableStateOf(false) }
    var elapsed by remember { mutableIntStateOf(0) }
    var quality by remember { mutableStateOf<VideoQuality?>(null) }
    var lastVideo by remember { mutableStateOf<android.net.Uri?>(null) }
    val videoSaver = remember { VideoSaver(context.contentResolver) }
    var pendingVideo by remember { mutableStateOf<PendingVideo?>(null) }
    var audioGranted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val askAudio = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { audioGranted = it }
    var zoom by remember { mutableFloatStateOf(1f) }
    var flash by remember { mutableStateOf(FlashMode.OFF) }
    var flashAvailable by remember { mutableStateOf(false) }
    var timer by remember { mutableStateOf(TimerMode.OFF) }
    var grid by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var zoomRange by remember { mutableStateOf(1f..1f) }
    var focusPoint by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    // the viewfinder is a TextureView: the frames can be read back (histogram, focus peaking) and filtered (the looks' colours, live)
    var previewTexture by remember { mutableStateOf<android.graphics.SurfaceTexture?>(null) }
    var previewView by remember { mutableStateOf<android.view.TextureView?>(null) }
    val previewSurface = remember(previewTexture) { previewTexture?.let { android.view.Surface(it) } }
    DisposableEffect(previewSurface) { onDispose { previewSurface?.release() } }
    // the look's colours, live on the viewfinder (an approximation: the saved photo gets the real pipeline)
    LaunchedEffect(previewView, preset) {
        val f = lookFilter(preset)
        previewView?.setLayerPaint(if (f == null) null else android.graphics.Paint().apply { colorFilter = f })
    }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // Bursts captured whose pictures are not yet saved. The shutter frees as soon as the six frames are in memory; the merge, denoise, render and save
    // (about two seconds) run behind it, one burst at a time. Each burst holds six 24 MB buffers against the app's 512 MB heap, so at most two are alive
    // at once (one being processed, one being captured); with two waiting the shutter stays dim until the first is saved.
    var pending by remember { mutableStateOf(0) }
    val shutterBusy = busy || pending >= 2
    var last by remember { mutableStateOf<Saved?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var deviceDegrees by remember { mutableIntStateOf(0) }
    val qualities = remember(lens) { controller.videoQualitiesFor(lens) }
    LaunchedEffect(qualities) { if (quality == null || quality !in qualities) quality = defaultVideoQuality(qualities) }
    val nightAvailable = remember(lens) { android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT in controller.extensionsFor(lens) }
    // a lens without Night turns Night off
    LaunchedEffect(nightAvailable) { if (!nightAvailable) night = false }
    // Night (the vendor's own pipeline) and the computational burst are alternatives; turning Night off brings the burst back
    val computational = burst && !night
    val nightExtension = if (night && nightAvailable) android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT else null
    LaunchedEffect(lens, nightExtension) { zoom = 1f }
    val previewShape = remember(lens, nightExtension, videoMode) { controller.previewSizeFor(lens, if (videoMode) null else nightExtension, videoMode) }

    // the phone's tilt, for the saved picture's orientation
    DisposableEffect(context) {
        val l = object : OrientationEventListener(context) { override fun onOrientationChanged(o: Int) { deviceDegrees = snapDeviceDegrees(o, deviceDegrees) } }
        if (l.canDetectOrientation()) l.enable()
        onDispose { l.disable() }
    }
    // the camera is open only while the screen is showing
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) resumed = true else if (e == Lifecycle.Event.ON_PAUSE) resumed = false }
        lifecycle.addObserver(o); onDispose { lifecycle.removeObserver(o) }
    }
    DisposableEffect(Unit) { onDispose { controller.release() } }
    LaunchedEffect(previewSurface, lens, raw, hdr, computational, dataset, resumed, previewShape, nightExtension, videoMode, quality) {
        val surface = previewSurface
        if (surface == null || !resumed) { controller.close(); return@LaunchedEffect }
        // the camera configures its stream to the texture's buffer size: set it to the preview size before the session is made
        previewShape?.let { shape -> previewTexture?.setDefaultBufferSize(shape.width, shape.height) }
        controller.open(lens, surface, raw || hdr || computational || dataset, if (videoMode) null else nightExtension, if (videoMode) quality else null, computational || dataset, onReady = { rawAvailable = controller.rawAvailable; zoom = controller.zoom; flashAvailable = controller.flashAvailable; zoomRange = controller.zoomMin..controller.zoomMax }, onError = { android.util.Log.w("HoltzmanCamera", "camera error (extension=$nightExtension): $it"); status = it; if (nightExtension != null) night = false })
    }
    DisposableEffect(controller) {
        // however a recording ends (stopped, or the screen was left), the file is finished or removed here
        controller.onRecordingEnded = { ok ->
            val v = pendingVideo; pendingVideo = null
            scope.launch(Dispatchers.Main) { recording = false }
            if (v != null) scope.launch(Dispatchers.IO) { videoSaver.finish(v, ok); withContext(Dispatchers.Main) { if (ok) { lastVideo = v.uri; status = "Saved video"; onCaptured?.invoke(v.uri, "video/mp4") } else status = "Recording too short to keep" } }
        }
        onDispose { controller.onRecordingEnded = null }
    }
    LaunchedEffect(recording) { elapsed = 0; while (recording) { delay(1000); elapsed++ } }
    LaunchedEffect(videoMode) { if (videoMode && !audioGranted) askAudio.launch(android.Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(1200); focusPoint = null } }
    // load the TPU denoiser now, off the main thread, so the first photo does not wait for it
    LaunchedEffect(Unit) { withContext(Dispatchers.Default) { com.everettpenne.holtzman.ml.TpuDenoiser.shared(context) } }
    LaunchedEffect(status) { if (status != null) { delay(if (status!!.startsWith("Saved scene") || status!!.contains("Dataset")) 8000 else 3500); status = null } }

    fun shootBurst() {
        if (shutterBusy) return
        busy = true; status = "Hold still…"
        val tTap = android.os.SystemClock.elapsedRealtime()   // the shot's clock: every stage below logs its offset from the tap (tag HoltzmanTiming)
        fun stamp(what: String) = android.util.Log.i("HoltzmanTiming", "%5d ms  %s".format(android.os.SystemClock.elapsedRealtime() - tTap, what))
        stamp("tap")
        val chars = controller.characteristics
        val processor = BurstProcessor(saver, context.applicationContext)
        val orientation = jpegOrientation(chars?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, lens == Lens.FRONT)
        // each frame is uploaded to the GPU and scored on the processing thread as it arrives, so the merge starts with that already done
        val prep = com.everettpenne.holtzman.save.BurstPrep()
        val tuning = BurstRender.LookTuning(texture, grain, preset, evEff, shadowsEff, warmthEff, manualExposure = manualExposureOn)
        val crop = cropRatio.takeUnless { it.isFull }?.longOverShort
        controller.focusForShot { stamp("focus done, burst requested"); controller.captureBurst(offHeap = true, longCapture = longCapture, onPlan = { n, seconds -> if (n > 6) scope.launch(Dispatchers.Main) { status = "Low light: hold still for %.0f s".format(java.util.Locale.ROOT, seconds + 1f) } }, onFrame = { _, pixels, w, h -> burstExecutor.execute { prep.frame(pixels, w, h) } }, release = { r -> burstExecutor.execute(r) }, onResult = { frames ->
            stamp("all frames in memory")
            scope.launch(Dispatchers.Main) { pending++; busy = false; status = "Saving…"; stamp("shutter ready again") }
            scope.launch(burstQueue) {
                val compare = context.getSharedPreferences("holtzman", android.content.Context.MODE_PRIVATE).getBoolean("compare", false)
                val tShot = System.currentTimeMillis()
                val outcome = runCatching { processor.process(frames, chars!!, orientation, tShot, saveDng = raw, saveReference = compare, ultraHdr = hdr, prep = prep, tuning = tuning, cropLongOverShort = crop) }.also { prep.close() }
                if (context.getSharedPreferences("holtzman", android.content.Context.MODE_PRIVATE).getBoolean("keepRaw", false) || com.everettpenne.holtzman.save.DebugFlags.on("keepraw")) {
                    runCatching {
                        val name = com.everettpenne.holtzman.camera.captureBaseName(tShot, java.util.TimeZone.getDefault().getOffset(tShot).toLong())
                        val processing = org.json.JSONObject().put("hdr", hdr).put("raw", raw).put("pipeline", "burst").put("debugSwitches", com.everettpenne.holtzman.save.DebugFlags.raw)
                        com.everettpenne.holtzman.save.DatasetCapture(controller, saver, context).saveTakenBurst(frames, name, orientation, tShot, processing)
                    }.onFailure { android.util.Log.w("HoltzmanBurst", "could not keep the raw burst", it) }
                }
                frames.forEach { it.free() }   // the processing is finished with the pixels: out of memory before the next burst needs it
                stamp("processed and saved")
                withContext(Dispatchers.Main) {
                    pending--
                    outcome.onSuccess { last = Saved(it.jpeg, it.dng); onCaptured?.invoke(it.jpeg, "image/jpeg"); status = if (it.dng != null) "Saved JPEG + DNG" else "Saved" }.onFailure { status = "Burst failed: ${it.message}"; android.util.Log.w("HoltzmanBurst", it) }
                }
            }
        }, onError = { burstExecutor.execute { prep.close() }; scope.launch(Dispatchers.Main) { busy = false; status = it } }) }
    }

    // Calibration for the burst merge's noise model: two frames at each of several ISOs, same brightness, phone held still on a still scene
    fun calibrateNoise() {
        if (busy) return
        busy = true; status = "Calibrating: keep the phone still"
        val chars = controller.characteristics ?: run { busy = false; return }
        val steps = listOf(100, 200, 400, 800, 1600, 3200)
        fun next(i: Int) {
            if (i >= steps.size) { scope.launch(Dispatchers.Main) { busy = false; status = "Calibration done (see the log)" }; return }
            controller.captureBurst(onResult = { frames ->
                scope.launch(Dispatchers.Default) {
                    runCatching { NoiseCalibration.measure(frames, chars) }.onFailure { android.util.Log.w("HoltzmanNoise", "iso ${steps[i]}: $it") }
                    next(i + 1)
                }
            }, onError = { android.util.Log.w("HoltzmanNoise", "iso ${steps[i]}: $it"); next(i + 1) }, frames = 2, iso = steps[i])
        }
        next(0)
    }

    fun shootDataset() {
        if (busy || pending > 0) return
        busy = true; status = "Starting…"
        val chars = controller.characteristics
        val orientation = jpegOrientation(chars?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, lens == Lens.FRONT)
        val kind = datasetKind; val tags = datasetTags; val note = datasetNote
        scope.launch(Dispatchers.Default) {
            val outcome = runCatching {
                com.everettpenne.holtzman.save.DatasetCapture(controller, saver, context).run(kind, tags, note, orientation) { line -> scope.launch(Dispatchers.Main) { status = line } }
            }
            withContext(Dispatchers.Main) {
                busy = false
                outcome.onSuccess { datasetSaved++; status = "Saved ${it.removePrefix("scene_")} (${datasetSaved} this session)  ·  Files: Documents/Holtzman/Dataset" }.onFailure { status = "Dataset capture failed: ${it.message}"; android.util.Log.w("HoltzmanDataset", it) }
            }
        }
    }

    fun shoot(highQuality: Boolean = false) {
        if (busy) return
        if (dataset) { if (pending == 0) shootDataset(); return }
        if (computational && controller.rawAvailable) { shootBurst(); return }
        if (pending > 0) return
        busy = true
        if (night) status = "Hold still…"
        controller.capture(deviceDegrees, highQuality, if (night) FlashMode.OFF else flash, onResult = { still ->
            // the heavy part (writing the files) leaves the camera thread, so the next preview frame is never held up by storage
            scope.launch(Dispatchers.IO) {
                // Ultra HDR is built from the RAW taken beside the JPEG, only at 1x (the RAW frame is not cropped by the zoom, so the two would not line up)
                val wantHdr = hdr && !night && still.raw != null && kotlin.math.abs(zoom - 1f) < 0.02f
                val hdrBytes = if (wantHdr) UltraHdr.build(still) else null
                val saved = runCatching { saver.save(still, hdrBytes, keepDng = raw && !night) }
                withContext(Dispatchers.Main) {
                    busy = false
                    saved.onSuccess { last = it; onCaptured?.invoke(it.jpeg, "image/jpeg"); status = when { hdrBytes != null && it.dng != null -> "Saved Ultra HDR + DNG"; hdrBytes != null -> "Saved Ultra HDR"; hdr && !night && !wantHdr -> "Saved (Ultra HDR needs 1×)"; hdr && !night -> "Saved (no highlights to recover)"; it.dng != null -> "Saved JPEG + DNG"; night -> "Saved (Night)"; else -> "Saved" } }.onFailure { status = "Couldn't save: ${it.message}" }
                }
            }
        }, onError = { scope.launch(Dispatchers.Main) { busy = false; status = it } })
    }

    /** The shutter: now, or after the self-timer's countdown (a second tick at a time, so it can be seen and a second press cancels it). */
    fun toggleRecording() {
        // the controller is the truth about whether it is recording (the screen's copy is updated from another thread and can lag a tap behind)
        if (controller.recording) { controller.stopRecording { }; return }
        scope.launch(Dispatchers.IO) {
            val pending = runCatching { videoSaver.begin() }.getOrElse { status = "Couldn't start a recording: ${it.message}"; return@launch }
            pendingVideo = pending
            controller.startRecording(pending.descriptor.fileDescriptor, audioGranted, deviceDegrees,
                onStarted = { scope.launch(Dispatchers.Main) { recording = true; if (!audioGranted) status = "Recording without sound" } },
                onError = { scope.launch(Dispatchers.Main) { status = it } })
        }
    }
    fun trigger() {
        if (videoMode) { toggleRecording(); return }
        if (countdown > 0) { countdown = 0; return }          // pressing during the countdown cancels it
        if (timer == TimerMode.OFF) { shoot(); return }
        scope.launch {
            countdown = timer.seconds
            while (countdown > 0) { delay(1000); if (countdown == 0) return@launch; countdown-- }
            shoot()
        }
    }
    LaunchedEffect(Unit) { ShutterKeys.flow.collect { if (!shutterBusy) trigger() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The preview's buffer is landscape (the sensor's own); the screen shows it upright, so the box is its shape turned a quarter.
        val ratio = previewShape?.let { it.height.toFloat() / it.width } ?: (3f / 4f)
        // One view for every pipeline (the normal one and the vendor extensions), never recreated when the mode changes: a new view would destroy the
        // surface the camera is about to be given.
        run {
            AndroidView(
                factory = { c -> android.view.TextureView(c).apply {
                    surfaceTextureListener = object : android.view.TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, w: Int, h: Int) { previewTexture = st }
                        override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, w: Int, h: Int) {}
                        override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean { previewTexture = null; return true }
                        override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
                    }
                    previewView = this
                } },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().aspectRatio(ratio),
            )
        }
        // Touch on the picture: tap to focus (and meter) there, pinch to zoom. The same box as the preview, drawn over it.
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().aspectRatio(ratio)
            .pointerInput(Unit) { detectTapGestures { p -> focusPoint = p; controller.focusAt(p.x / size.width, p.y / size.height) } }
            .pointerInput(Unit) { detectTransformGestures { _, _, factor, _ -> if (factor != 1f) zoom = controller.setZoom(controller.zoom * factor) } }) {
            focusPoint?.let { p ->
                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((p.x - 36.dp.toPx()).toInt(), (p.y - 36.dp.toPx()).toInt()) }.size(72.dp)
                    .border(2.dp, Holtzman.accent, RoundedCornerShape(10.dp)))
            }
        }

        // the thirds grid, over the picture
        if (grid) Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().aspectRatio(ratio).drawBehind {
            val c = Color.White.copy(alpha = .35f)
            for (i in 1..2) { drawLine(c, androidx.compose.ui.geometry.Offset(size.width * i / 3, 0f), androidx.compose.ui.geometry.Offset(size.width * i / 3, size.height), 1.5f); drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height * i / 3), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 3), 1.5f) }
        })
        // the crop, shown as the part of the picture that will be cut off (computational photos only: the phone's own JPEG is not cropped)
        if (computational && rawAvailable && !videoMode && !cropRatio.isFull) Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().aspectRatio(ratio).drawBehind {
            val shade = Color.Black.copy(alpha = .6f); val r = cropRatio.longOverShort; val full = 4f / 3f
            if (r < full) { val band = size.height * (1f - r / full) / 2f; drawRect(shade, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(size.width, band)); drawRect(shade, androidx.compose.ui.geometry.Offset(0f, size.height - band), androidx.compose.ui.geometry.Size(size.width, band)) }
            else { val band = size.width * (1f - full / r) / 2f; drawRect(shade, androidx.compose.ui.geometry.Offset.Zero, androidx.compose.ui.geometry.Size(band, size.height)); drawRect(shade, androidx.compose.ui.geometry.Offset(size.width - band, 0f), androidx.compose.ui.geometry.Size(band, size.height)) }
        })
        // the level line and the histogram, over the picture
        val roll by rememberRollDegrees(levelOn && !videoMode)
        val histogram by rememberHistogram(previewView, histogramOn && !videoMode)
        val peaking by rememberPeaking(previewView, focusT != null && !videoMode)
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().aspectRatio(ratio)) {
            if (peaking != null) androidx.compose.foundation.Image(peaking!!.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
            LevelOverlay(roll, Modifier.matchParentSize())
            HistogramView(histogram, Modifier.align(Alignment.TopStart).padding(10.dp))
        }
        if (countdown > 0) Text("$countdown", style = IosType.largeTitle.copy(fontSize = 96.sp), color = Color.White, modifier = Modifier.align(Alignment.Center))

        // top bar: settings on the left; flash, timer and grid on the right
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BarButton(Icons.Rounded.MoreHoriz, "Settings", on = false) { showSettings = true }
            Spacer(Modifier.weight(1f))
            if (videoMode) quality?.let { q -> TextPill(q.label, enabled = !recording && qualities.size > 1) { quality = q.next(qualities) } }
        }
        // what the camera is doing, centred over the picture: a dataset session, then any message
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (dataset && !videoMode) Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .6f)).clickable { showSettings = false; showDataset = true }.padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(Holtzman.accent, CircleShape))
                Text("Dataset  \u00B7  ${datasetKind.label}" + if (datasetSaved > 0) "  \u00B7  $datasetSaved saved" else "", style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
            }
            if (night && !videoMode) Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .6f)).padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Rounded.NightsStay, null, tint = Holtzman.accent, modifier = Modifier.size(14.dp)); Text("Night", style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
            }
            status?.let { Text(it, style = IosType.footnote, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp).background(Color.Black.copy(alpha = .7f), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) }
        }
        // recording clock
        if (recording) Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp).background(Color.Black.copy(alpha = .6f), CircleShape).padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Holtzman.red, CircleShape)); Spacer(Modifier.width(8.dp))
            Text(recordingClock(elapsed), style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        // zoom: one pill on the picture's lower edge with the stops the camera offers; the ratio now applied is lit
        Box(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().aspectRatio(ratio)) {
            val stops = zoomStops(zoomRange.start, zoomRange.endInclusive)
            if (!showControls || videoMode) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                stops.forEach { stop ->
                    val on = kotlin.math.abs(zoom - stop) < 0.05f
                    Box(Modifier.size(width = 46.dp, height = 36.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = .22f) else Color.Transparent).clickable { zoom = controller.setZoom(stop) }, contentAlignment = Alignment.Center) {
                        Text(if (stop == 1f) "1\u00D7" else if (stop < 1f) "%.1f".format(java.util.Locale.ROOT, stop).trimStart('0') else "${stop.toInt()}\u00D7", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (on) Holtzman.accent else Color.White)
                    }
                }
                if (stops.none { kotlin.math.abs(zoom - it) < 0.05f })
                    Box(Modifier.size(width = 56.dp, height = 36.dp).clip(CircleShape).background(Color.White.copy(alpha = .22f), CircleShape), contentAlignment = Alignment.Center) {
                        Text("%.1f\u00D7".format(java.util.Locale.ROOT, zoom), style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Holtzman.accent)
                    }
            }
            // the control of the open tab, over the picture's lower edge (the Pixel camera app puts its sliders just above the shutter)
            if (showControls && !videoMode) Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 14.dp, end = 14.dp)) {
                when (controlTab) {
                    ControlTab.LOOK -> LookChips(preset, { preset = it })
                    ControlTab.EXPOSURE -> ControlSlider(evValue, -2f..2f, "%+.1f EV".format(java.util.Locale.ROOT, evEff), Icons.Rounded.Remove, Icons.Rounded.Add, "Exposure", { evValue = it })
                    ControlTab.SHADOWS -> ControlSlider(shadowsValue, -20f..20f, "%+d".format(shadowsEff.toInt()), Icons.Rounded.DarkMode, Icons.Rounded.LightMode, "Shadows", { shadowsValue = it })
                    ControlTab.SHUTTER -> ControlSlider(shutterT ?: 0.5f, 0f..1f, shutterT?.let { shutterLabel(shutterNsFromT(it, controller.exposureRangeNs)) } ?: controller.currentExposure?.let { shutterLabel(it.second) } ?: "Auto", Icons.Rounded.Speed, Icons.Rounded.HourglassBottom, "Shutter", { shutterT = it }, isAuto = shutterT == null, onAuto = { shutterT = null })
                    ControlTab.ISO -> ControlSlider(isoT ?: 0.5f, 0f..1f, isoT?.let { "ISO " + isoFromT(it, controller.isoRange) } ?: controller.currentExposure?.let { "ISO " + it.first } ?: "Auto", Icons.Rounded.Remove, Icons.Rounded.Add, "ISO", { isoT = it }, isAuto = isoT == null, onAuto = { isoT = null })
                    ControlTab.FOCUS -> ControlSlider(focusT ?: 0f, 0f..1f, focusT?.let { focusLabel(it * controller.minFocusDiopters) } ?: "Auto", Icons.Rounded.Landscape, Icons.Rounded.FilterCenterFocus, "Focus", { focusT = it }, isAuto = focusT == null, onAuto = { focusT = null })
                    ControlTab.WHITE_BALANCE -> ControlSlider(warmthValue, -20f..20f, "%+d".format(warmthEff.toInt()), Icons.Rounded.AcUnit, Icons.Rounded.WbSunny, "White balance", { warmthValue = it })
                }
            }
        }
        // PHOTO / VIDEO, with the small switches for this shot above the shutter
        if (!showControls || videoMode) Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 156.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!videoMode) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nightAvailable) ShotToggle("Night", night, Icons.Rounded.NightsStay) { night = !night }
                if (!night) ShotToggle("HDR", hdr, Icons.Rounded.HdrOn) { hdr = !hdr }
                if (!night && (rawAvailable || raw)) ShotToggle("RAW", raw, Icons.Rounded.RawOn) { raw = !raw }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                listOf(false to "PHOTO", true to "VIDEO").forEach { (v, label) ->
                    val on = videoMode == v
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable(enabled = !recording && !on) { videoMode = v; if (v) { night = false } }.padding(vertical = 4.dp, horizontal = 4.dp)) {
                        Text(label, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp), color = if (on) Holtzman.accent else Color.White.copy(alpha = .7f))
                        Box(Modifier.padding(top = 4.dp).size(width = 18.dp, height = 3.dp).background(if (on) Holtzman.accent else Color.Transparent, CircleShape))
                    }
                }
            }
        }

        // bottom: the last photo, the shutter, the other lens
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = if (showControls && !videoMode) 122.dp else 66.dp, start = 32.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .12f)).combinedClickable(onLongClick = { if (!CaptureContract.showInSietch(context, android.os.Environment.getExternalStoragePublicDirectory(if (videoMode) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES).path + "/Holtzman")) status = "Sietch is not installed" }, enabled = if (videoMode) lastVideo != null else last != null) {
                (if (videoMode) lastVideo else last?.jpeg)?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, if (videoMode) "video/mp4" else "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
            }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PhotoLibrary, "Last photo", tint = Color.White.copy(alpha = .8f)) }
            // the whole 84 dp circle is the button: the tap area is set before the ring's padding, so it does not shrink while recording
            Box(Modifier.size(84.dp).clip(CircleShape).clickable(enabled = !shutterBusy) { trigger() }.semantics { contentDescription = if (videoMode) (if (recording) "Stop recording" else "Record") else "Shutter" }
                .border(4.dp, Color.White, CircleShape).padding(if (recording) 22.dp else 7.dp).clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                .background(if (videoMode) Holtzman.red else if (shutterBusy) Holtzman.accent.copy(alpha = .5f) else Color.White))
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f)).clickable { lens = if (lens == Lens.BACK) Lens.FRONT else Lens.BACK },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FlipCameraAndroid, "Switch camera", tint = Color.White) }
        }
        val changed = buildSet {
            if (preset != BurstRender.LookPreset.STANDARD) add(ControlTab.LOOK)
            if (evEff != 0f) add(ControlTab.EXPOSURE)
            if (shadowsEff != 0f) add(ControlTab.SHADOWS)
            if (warmthEff != 0f) add(ControlTab.WHITE_BALANCE)
            if (shutterT != null) add(ControlTab.SHUTTER)
            if (isoT != null) add(ControlTab.ISO)
            if (focusT != null) add(ControlTab.FOCUS)
        }
        if (showControls && !videoMode) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(width = 56.dp, height = 20.dp).clickable { showControls = false }.semantics { contentDescription = "Close controls" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).background(Color.White.copy(alpha = .5f), CircleShape))
            }
            val tabs = ControlTab.entries.filter { it != ControlTab.EXPOSURE || !manualExposureOn }   // a hand-set exposure has no compensation
            ControlTabs(controlTab.takeIf { it in tabs } ?: ControlTab.LOOK, changed, tabs, { controlTab = it }, onReset = { preset = BurstRender.LookPreset.STANDARD; evValue = 0f; shadowsValue = 0f; warmthValue = 0f; isoT = null; shutterT = null; focusT = null })
        } else Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp, start = 32.dp, end = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            BottomButton(Icons.Rounded.Settings, "Photo settings", on = false) { showPhotoSettings = true }
            if (!videoMode) BottomButton(Icons.Rounded.Tune, "Controls", on = false, dot = changed.isNotEmpty()) { showControls = true } else Spacer(Modifier.size(46.dp))
        }
    }
    LaunchedEffect(isoT, shutterT) { controller.setManualExposure(isoT?.let { isoFromT(it, controller.isoRange) }, shutterT?.let { shutterNsFromT(it, controller.exposureRangeNs) }) }
    LaunchedEffect(focusT) { controller.setManualFocus(focusT?.let { it * controller.minFocusDiopters }) }
    LaunchedEffect(evEff) { controller.setExposureCompensation(evEff) }
    LaunchedEffect(warmthEff) { controller.setWarmth(1f + 0.006f * warmthEff) }
    if (showPhotoSettings) PhotoSettingsSheet(
        ratio = cropRatio, onRatio = { cropRatio = it }, ratioApplies = computational && rawAvailable,
        timer = timer, onTimer = { timer = it }, flash = flash, onFlash = { flash = it }, flashAvailable = flashAvailable,
        grid = grid, onGrid = { grid = it }, level = levelOn, onLevel = { levelOn = it }, histogram = histogramOn, onHistogram = { histogramOn = it }, longCapture = longCapture, onLongCapture = { longCapture = it }, onDismiss = { showPhotoSettings = false })
    if (showSettings) SettingsSheet(
        computational = burst, onComputational = { burst = it },
        hdr = hdr, onHdr = { hdr = it },
        raw = raw, onRaw = { raw = it },
        texture = texture, onTexture = { texture = it }, grain = grain, onGrain = { grain = it },
        datasetOn = dataset, datasetKind = datasetKind.label, datasetSaved = datasetSaved,
        onDataset = { showSettings = false; showDataset = true },
        onReport = { showSettings = false; showReport = true },
        onCompare = { showSettings = false; shoot(highQuality = false); scope.launch { delay(2500); shoot(highQuality = true) } },
        onDismiss = { showSettings = false })
    if (showDataset) DatasetSheet(
        active = dataset, kind = datasetKind, onKind = { datasetKind = it },
        tags = datasetTags, onTag = { t -> datasetTags = if (t in datasetTags) datasetTags - t else datasetTags + t },
        note = datasetNote, onNote = { datasetNote = it }, saved = datasetSaved,
        onStart = { dataset = true; showDataset = false }, onStop = { dataset = false; showDataset = false },
        onCalibrate = { showDataset = false; calibrateNoise() },
        onDismiss = { showDataset = false })
    if (showReport) CameraReportSheet(onDismiss = { showReport = false })
}

/** A round dark button for the bars over the picture; lit with the accent when its feature is on. */
@Composable
private fun BarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, on: Boolean, badge: String? = null, onClick: () -> Unit) {
    Box(Modifier.size(42.dp).clip(CircleShape).background(if (on) Holtzman.accent.copy(alpha = .9f) else Color.Black.copy(alpha = .45f)).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(22.dp))
        if (badge != null) Text(badge, style = IosType.caption2.copy(fontWeight = FontWeight.Bold), color = Color.Black, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 3.dp))
    }
}

@Composable
private fun TextPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(label, maxLines = 1, softWrap = false, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
        modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .45f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp))
}

/** A small labelled switch for the next shot (Night, HDR, RAW): filled with the accent when on, a quiet outline when off. */
@Composable
private fun ShotToggle(label: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(Modifier.clip(CircleShape).background(if (on) Holtzman.accent else Color.White.copy(alpha = .12f)).border(1.dp, if (on) Color.Transparent else Color.White.copy(alpha = .22f), CircleShape)
        .clickable(onClick = onClick).padding(start = 10.dp, end = 14.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(16.dp))
        Text(label, maxLines = 1, softWrap = false, style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (on) Color.Black else Color.White)
    }
}
