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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt
import androidx.compose.ui.layout.onGloballyPositioned
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
fun HoltzmanRoot(startInVideo: Boolean = false, locked: Boolean = false, onCaptured: ((android.net.Uri, String) -> Unit)? = null) = HoltzmanTheme { CameraGate(startInVideo, locked, onCaptured) }

/** Asks for the camera, in words, before anything else; the camera is the one permission the app has. */
@Composable
private fun CameraGate(startInVideo: Boolean, locked: Boolean, onCaptured: ((android.net.Uri, String) -> Unit)?) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) CameraScreen(startInVideo, locked, onCaptured) else Box(Modifier.fillMaxSize().background(Color.Black).padding(32.dp), contentAlignment = Alignment.Center) {
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
private fun CameraScreen(startInVideo: Boolean, locked: Boolean, onCaptured: ((android.net.Uri, String) -> Unit)?) {
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
    var activeItem by remember { mutableStateOf<StripItem?>(null) }   // the control open under the viewfinder; null shows the strip
    var focusTick by remember { mutableStateOf(0) }
    // paired data collection: take a photo in the Pixel Camera app first, then run the dataset series
    var pixelFirst by remember { mutableStateOf(prefs.getBoolean("pixelFirst", false)) }
    LaunchedEffect(pixelFirst) { prefs.edit().putBoolean("pixelFirst", pixelFirst).apply() }
    var pendingPair by remember { mutableStateOf<PendingPair?>(null) }
    var showChooseStyle by remember { mutableStateOf(false) }          // the full-screen "Choose a Style"
    var startStylesOnTexture by remember { mutableStateOf(false) }      // open the Styles card on its Texture tab
    var lastThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    // the style, its palette and its texture are kept between runs, like the iPhone's
    var preset by remember { mutableStateOf(BurstRender.LookPreset.entries.getOrElse(prefs.getInt("style", 0)) { BurstRender.LookPreset.STANDARD }) }
    var textureStyle by remember { mutableStateOf(BurstRender.TextureStyle.entries.getOrElse(prefs.getInt("textureStyle", 0)) { BurstRender.TextureStyle.STANDARD }) }
    var textureAmount by remember { mutableStateOf(prefs.getFloat("textureAmount", 0.5f)) }
    var evValue by remember { mutableStateOf(0f) }
    var shadowsValue by remember { mutableStateOf(prefs.getFloat("shadows", 0f)) }
    LaunchedEffect(shadowsValue) { prefs.edit().putFloat("shadows", shadowsValue).apply() }
    var warmthValue by remember { mutableStateOf(prefs.getFloat("warmth", 0f)) }
    var styleIntensity by remember { mutableStateOf(prefs.getFloat("styleIntensity", 1f)) }
    LaunchedEffect(preset, textureStyle, textureAmount, warmthValue, styleIntensity) { prefs.edit().putInt("style", preset.ordinal).putInt("textureStyle", textureStyle.ordinal).putFloat("textureAmount", textureAmount).putFloat("warmth", warmthValue).putFloat("styleIntensity", styleIntensity).apply() }
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
    // Instant capture: the burst comes from frames already kept by the camera (the Pixel camera app's zero shutter lag). The longer low-light capture needs its own exposures, so it turns this off.
    var instant by remember { mutableStateOf(prefs.getBoolean("instant", true)) }
    LaunchedEffect(instant) { prefs.edit().putBoolean("instant", instant).apply() }
    var longCapture by remember { mutableStateOf(prefs.getBoolean("longCapture", false)) }
    LaunchedEffect(longCapture) { prefs.edit().putBoolean("longCapture", longCapture).apply() }
    var levelOn by remember { mutableStateOf(prefs.getBoolean("level", false)) }
    // the pro readout (ISO, shutter, compensation, focus, live) over the viewfinder, and subject-aware autofocus; both on unless turned off
    var proReadout by remember { mutableStateOf(prefs.getBoolean("proReadout", false)) }
    var subjectFocus by remember { mutableStateOf(prefs.getBoolean("subjectFocus", true)) }
    LaunchedEffect(proReadout, subjectFocus) { prefs.edit().putBoolean("proReadout", proReadout).putBoolean("subjectFocus", subjectFocus).apply() }
    // AE/AF lock (a long press on the picture): focus and exposure held until the next tap
    var aeAfLock by remember { mutableStateOf(false) }
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
    var viewerItems by remember { mutableStateOf<List<android.net.Uri>?>(null) }
    // On the lock screen ([locked], see SecureCameraActivity) only what is shot here is shown: these are those shots, newest first.
    val sessionShots = remember { mutableStateListOf<android.net.Uri>() }
    val sessionVideos = remember { mutableStateListOf<android.net.Uri>() }
    var viewerStart by remember { mutableStateOf(0) }
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
    LaunchedEffect(previewView, preset, styleIntensity, shadowsEff) {
        val f = lookFilter(preset, styleIntensity, (shadowsEff / 20f).coerceIn(-1f, 1f))
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
    // Below 1x the camera is opened without a RAW stream: with one it stays on the main lens (the .6 view was the 1x view, 2026-11-02), so the ultrawide needs the plain session.
    // The shot there is the camera's own JPEG. Crossing 1x reopens the camera at the zoom it had.
    // the iOS 26 look: round, solid cards over the live picture (a setting, so the flat look stays to compare)
    var liquidSetting by remember { mutableStateOf(prefs.getBoolean("liquidGlass", true)) }
    LaunchedEffect(liquidSetting) { prefs.edit().putBoolean("liquidGlass", liquidSetting).apply() }
    val calmScreen = remember(resumed) { reduceTransparency(context) }   // high-contrast text or animations off: the flat, calmer look whatever the setting says
    val liquid = liquidSetting && !calmScreen
    val wide = zoom < 0.99f && !dataset && !videoMode
    var veil by remember { mutableStateOf(false) }
    var lastWide by remember { mutableStateOf(false) }
    LaunchedEffect(previewSurface, lens, raw, hdr, computational, dataset, resumed, previewShape, nightExtension, videoMode, quality, instant, longCapture, wide) {
        if (wide != lastWide) { veil = true; lastWide = wide }
        val surface = previewSurface
        if (surface == null || !resumed) { controller.close(); return@LaunchedEffect }
        // the camera configures its stream to the texture's buffer size: set it to the preview size before the session is made
        previewShape?.let { shape -> previewTexture?.setDefaultBufferSize(shape.width, shape.height) }
        // The zoom carries over a reopen: leaving the ultrawide for 2x reopens the camera (with its RAW stream back), and starting that at 1x lost the tap on 2.
        controller.open(lens, surface, (raw || hdr || computational || dataset) && !wide, if (videoMode) null else nightExtension, if (videoMode) quality else null, computational && !wide || dataset, instant && !longCapture && computational && !dataset && !wide, if (wide) zoom else maxOf(zoom, 1f), onReady = { rawAvailable = controller.rawAvailable; zoom = controller.zoom; flashAvailable = controller.flashAvailable; zoomRange = controller.zoomMin..controller.zoomMax }, onError = { android.util.Log.w("HoltzmanCamera", "camera error (extension=$nightExtension): $it"); status = it; if (nightExtension != null) night = false })
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
    LaunchedEffect(focusPoint, focusTick, aeAfLock) { if (focusPoint != null && !aeAfLock) { delay(2500); focusPoint = null } }
    // a lens or mode change ends a lock
    LaunchedEffect(lens, videoMode, nightExtension) { if (aeAfLock) { aeAfLock = false; focusPoint = null; controller.unlock() } }
    // the thumbnail beside the shutter: the last photo (or video) taken; on opening, the newest photo the app saved before
    LaunchedEffect(Unit) { if (last == null && !locked) withContext(Dispatchers.IO) { saver.latestPhoto() }?.let { if (last == null) last = Saved(it, null) } }
    LaunchedEffect(Unit) { if (lastVideo == null && !locked) withContext(Dispatchers.IO) { videoSaver.latestVideo() }?.let { if (lastVideo == null) lastVideo = it } }
    LaunchedEffect(last, lastVideo) { if (locked) { last?.jpeg?.let { if (it !in sessionShots) sessionShots.add(0, it) }; lastVideo?.let { if (it !in sessionVideos) sessionVideos.add(0, it) } } }
    LaunchedEffect(last, lastVideo, videoMode) {
        val uri = if (videoMode) lastVideo else last?.jpeg
        lastThumb = if (uri == null) null else withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, android.util.Size(160, 160), null) }.getOrNull() }
    }
    // load the TPU denoiser now, off the main thread, so the first photo does not wait for it
    // (only when it is switched on: noise filtering is off by default since 2026-11-02, and loading it took the TPU and memory at every start for nothing)
    LaunchedEffect(Unit) { if (com.everettpenne.holtzman.save.DebugFlags.on("tpu")) withContext(Dispatchers.Default) { com.everettpenne.holtzman.ml.TpuDenoiser.shared(context) } }
        // a failure stays up longer than a confirmation: it is the one thing that is easy to miss and costs a photo
    LaunchedEffect(status) { if (status != null) { val t = status!!; delay(if (t.startsWith("Saved scene") || t.contains("Dataset")) 8000 else if (t.contains("fail", true) || t.startsWith("Couldn't") || t.startsWith("Could not")) 7000 else 3500); status = null } }
    // the shutter's flash (a quick dip to black over the picture), and the veil while the camera reopens (crossing 1x switches lens session); both drawn without recomposing the screen
    var flashTick by remember { mutableStateOf(0) }
    val shutterDip = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(flashTick) { if (flashTick > 0) { shutterDip.snapTo(.8f); shutterDip.animateTo(0f, androidx.compose.animation.core.tween(220)) } }
    val veilAlpha by androidx.compose.animation.core.animateFloatAsState(if (veil) 1f else 0f, androidx.compose.animation.core.tween(if (veil) 120 else 260), label = "veil")
    LaunchedEffect(veil) { if (veil) { delay(2500); veil = false } }
    // the veil comes off with the first preview frame (the session being ready is earlier, and the picture is still black then), a moment later so that frame is on the screen
    DisposableEffect(controller) { controller.onFirstFrame = { scope.launch { delay(120); veil = false } }; onDispose { controller.onFirstFrame = null } }   // never stuck dark if the camera does not report back

    fun shootBurst() {
        if (shutterBusy) return
        busy = true; status = "Hold still…"; flashTick++
        val tTap = android.os.SystemClock.elapsedRealtime()   // the shot's clock: every stage below logs its offset from the tap (tag HoltzmanTiming)
        fun stamp(what: String) = android.util.Log.i("HoltzmanTiming", "%5d ms  %s".format(android.os.SystemClock.elapsedRealtime() - tTap, what))
        stamp("tap")
        val chars = controller.characteristics
        val processor = BurstProcessor(saver, context.applicationContext)
        val orientation = jpegOrientation(chars?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, lens == Lens.FRONT)
        // each frame is uploaded to the GPU and scored on the processing thread as it arrives, so the merge starts with that already done
        val prep = com.everettpenne.holtzman.save.BurstPrep()
        val tuning = BurstRender.LookTuning(texture, grain, preset, evEff, shadowsEff, warmthEff, intensity = styleIntensity, textureStyle = textureStyle, textureAmount = textureAmount, manualExposure = manualExposureOn)
        val crop = cropRatio.takeUnless { it.isFull }?.longOverShort
        val shotZoom = controller.zoom   // the zoom the viewfinder showed at the tap: the RAW frames are not cropped by it, so the render is
        val deliver: (List<com.everettpenne.holtzman.camera.BurstFrame>) -> Unit = { frames ->
            stamp("all frames in memory")
            scope.launch(Dispatchers.Main) { pending++; busy = false; status = "Saving…"; stamp("shutter ready again") }
            scope.launch(burstQueue) {
                val compare = context.getSharedPreferences("holtzman", android.content.Context.MODE_PRIVATE).getBoolean("compare", false)
                val tShot = System.currentTimeMillis()
                val outcome = runCatching { processor.process(frames, chars!!, orientation, tShot, saveDng = raw, saveReference = compare, ultraHdr = hdr, prep = prep, tuning = tuning, cropLongOverShort = crop, zoom = shotZoom) }.also { prep.close() }
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
                    outcome.onSuccess { last = Saved(it.jpeg, it.dng); onCaptured?.invoke(it.jpeg, "image/jpeg"); status = (if (it.dng != null) "Saved JPEG + DNG" else "Saved") + zoomNote(shotZoom) }.onFailure { status = "Burst failed: ${it.message}"; android.util.Log.w("HoltzmanBurst", it) }
                }
            }
        }
        val fail: (String) -> Unit = { burstExecutor.execute { prep.close() }; scope.launch(Dispatchers.Main) { busy = false; status = it } }
        val frameHook: (Int, java.nio.ByteBuffer, Int, Int) -> Unit = { _, pixels, w, h -> burstExecutor.execute { prep.frame(pixels, w, h) } }
        if (controller.zslEnabled) {
            // instant capture: the frames are already in the ring, so there is nothing to wait for
            status = "Saving…"
            controller.captureFromRing(offHeap = true, onFrame = frameHook, release = { r -> burstExecutor.execute(r) }, onResult = deliver, onError = fail)
        } else controller.focusForShot { stamp("focus done, burst requested"); controller.captureBurst(offHeap = true, longCapture = longCapture, onPlan = { n, seconds -> if (n > 6) scope.launch(Dispatchers.Main) { status = "Low light: hold still for %.0f s".format(java.util.Locale.ROOT, seconds + 1f) } }, onFrame = frameHook, release = { r -> burstExecutor.execute(r) }, onResult = deliver, onError = fail) }
    }

    // Calibration for the burst merge's noise model: two frames at each of several ISOs, same brightness, phone held still on a still scene
    fun calibrateNoise() {
        if (busy) return
        if (controller.zslEnabled) { status = "Turn Instant capture off to calibrate"; return }
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

    // The dataset series itself. After the Pixel Camera app the camera has to open again before it can be used, so a paired capture waits until frames are flowing.
    fun runDatasetSeries(sceneName: String?, pixelDouble: String?, pixelUri: android.net.Uri? = null) {
        busy = true; status = "Starting…"
        val kind = datasetKind; val tags = datasetTags; val note = datasetNote; val lensNow = lens
        var pixelZoomNote: String? = null; var pixelZoomDetail: String? = null
        scope.launch(Dispatchers.Default) {
            val outcome = runCatching {
                if (sceneName != null) {
                    scope.launch(Dispatchers.Main) { status = "Waiting for the camera…" }
                    kotlinx.coroutines.withTimeoutOrNull(10_000) { while (!controller.streaming) delay(100); true } ?: throw java.io.IOException("The camera did not come back after the Pixel Camera app.")
                    delay(800)   // automatic exposure and focus settle
                    // The Pixel app was left at some zoom and took its photo at it; the camera has come back at 1x. Match it, so the pair is of the same view.
                    if (pixelUri != null) {
                        val photo = com.everettpenne.holtzman.save.PixelPairing.readPhoto(context, pixelUri)
                        val wide = withContext(Dispatchers.Main) { previewView?.takeIf { it.isAvailable }?.let { runCatching { it.getBitmap(320, 427) }.getOrNull() } }
                        val range = withContext(Dispatchers.Main) { controller.zoomMin }
                        var target: Float? = null
                        if (photo != null && photo.focalLengthMm != null && photo.focalLengthMm < 3f && range < 0.95f) { target = range; pixelZoomNote = "ultrawide" }   // the wide lens's own focal length
                        else if (photo != null && wide != null) {
                            // a portrait view may meet a landscape photo (the Pixel app turns with the phone): estimateAnyTurn tries the turnings
                            val a = com.everettpenne.holtzman.save.ZoomEstimate.estimateAnyTurn(com.everettpenne.holtzman.save.PixelPairing.lumaOf(wide), photo.luma)
                            target = com.everettpenne.holtzman.save.ZoomEstimate.settle(a)
                            pixelZoomDetail = "%.3f at correlation %.2f".format(java.util.Locale.ROOT, a.zoom, a.score)
                            if (target != null) pixelZoomNote = "%.1f".format(java.util.Locale.ROOT, target)
                        }
                        if (target != null && kotlin.math.abs(target - 1f) > 0.04f) withContext(Dispatchers.Main) { zoom = controller.setZoom(target) }.also { delay(600) }
                        pixelZoomNote = pixelZoomNote ?: "not measured"
                    }
                }
                val chars = controller.characteristics
                val orientation = jpegOrientation(chars?.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90, deviceDegrees, lensNow == Lens.FRONT)
                com.everettpenne.holtzman.save.DatasetCapture(controller, saver, context).run(kind, tags, note, orientation, { line -> scope.launch(Dispatchers.Main) { status = line } }, sceneName, pixelDouble, pixelZoomNote, pixelZoomDetail)
            }
            withContext(Dispatchers.Main) {
                busy = false
                outcome.onSuccess { datasetSaved++; status = "Saved ${it.removePrefix("scene_")}${if (pixelDouble != null) " with its Pixel photo (zoom ${pixelZoomNote ?: "?"})" else ""} (${datasetSaved} this session)  ·  Files: Documents/Holtzman/Dataset" }.onFailure { status = "Dataset capture failed: ${it.message}" }
            }
        }
    }

    var launchPixel: (android.content.Intent) -> Unit = {}
    fun shootDataset() {
        if (busy || pending > 0) return
        if (pixelFirst && com.everettpenne.holtzman.save.PixelPairing.available(context)) {
            // the scene's name is chosen now, so the Pixel photo can carry it
            val started = System.currentTimeMillis()
            val scene = com.everettpenne.holtzman.save.PixelPairing.sceneName(started, datasetKind)
            val uri = runCatching { saver.beginDocument(com.everettpenne.holtzman.save.PixelPairing.FOLDER, "$scene.jpg", "image/jpeg", started) }.getOrNull()
            if (uri == null) { status = "Could not make a file for the Pixel photo"; return }
            pendingPair = PendingPair(scene, uri)
            busy = true; status = "Take the photo in the Pixel camera, then tap the tick"
            runCatching { launchPixel(com.everettpenne.holtzman.save.PixelPairing.captureIntent(uri)) }.onFailure { saver.abandonDocument(uri); pendingPair = null; busy = false; status = "Could not open the Pixel Camera app" }
            return
        }
        runDatasetSeries(null, null)
    }
    // what comes back from the Pixel Camera app: the photo (then the series runs) or nothing (then the empty file is removed)
    val pixelLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val p = pendingPair; pendingPair = null
        if (p == null) { busy = false } else scope.launch(Dispatchers.IO) {
            val size = saver.sizeOf(p.uri)
            withContext(Dispatchers.Main) {
                if (result.resultCode == android.app.Activity.RESULT_OK && size > 0) runDatasetSeries(p.scene, com.everettpenne.holtzman.save.PixelPairing.FOLDER.removePrefix("Dataset/") + "/" + p.scene + ".jpg", p.uri)
                else { saver.abandonDocument(p.uri); busy = false; status = "No Pixel photo, so nothing was saved" }
            }
        }
    }
    launchPixel = { pixelLauncher.launch(it) }

    fun shoot(highQuality: Boolean = false) {
        if (busy) return
        if (dataset) { if (pending == 0) shootDataset(); return }
        // Below 1x the ultrawide lens is in use, but the RAW stream the burst merges is always the main lens (a dataset scene shot at 0.556x has f/1.7 RAW frames
        // showing the 1x view), so a zoomed-out shot is the camera's own JPEG, which does follow the zoom
        if (computational && controller.rawAvailable && controller.zoom >= 0.99f) { shootBurst(); return }
        if (pending > 0) return
        busy = true; flashTick++
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
                    saved.onSuccess { last = it; onCaptured?.invoke(it.jpeg, "image/jpeg"); status = when { hdrBytes != null && it.dng != null -> "Saved Ultra HDR + DNG"; hdrBytes != null -> "Saved Ultra HDR"; hdr && !night && !wantHdr -> "Saved (Ultra HDR needs 1×)"; hdr && !night -> "Saved (no highlights to recover)"; it.dng != null -> "Saved JPEG + DNG"; night -> "Saved (Night)"; else -> "Saved" }; if (zoom < 0.99f || zoom > 1.01f) status = (if (it.dng != null) "Saved JPEG + DNG" else "Saved") + zoomNote(zoom) }.onFailure { status = "Couldn't save: ${it.message}" }
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
    val hostView = androidx.compose.ui.platform.LocalView.current
    fun trigger() {
        hostView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
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

    // what automatic exposure and focus are doing now, for the pro readout (a few times a second is enough to read)
    var liveExposure by remember { mutableStateOf<Pair<Int, Long>?>(null) }
    var liveFocus by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(proReadout, resumed) { while (proReadout && resumed) { liveExposure = controller.currentExposure; liveFocus = controller.autoSample?.focusDiopters; delay(250) } }
    androidx.compose.runtime.CompositionLocalProvider(LocalLiquid provides liquid) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The preview's buffer is landscape (the sensor's own); the screen shows it upright, so the box is its shape turned a quarter.
        val ratio = previewShape?.let { it.height.toFloat() / it.width } ?: (3f / 4f)
        val styleChanged = preset != BurstRender.LookPreset.STANDARD || warmthEff != 0f || shadowsEff != 0f || textureStyle != BurstRender.TextureStyle.STANDARD
        val changed = buildSet {
            if (styleChanged) add(StripItem.STYLES)
            if (evEff != 0f) add(StripItem.EXPOSURE)
            if (shutterT != null) add(StripItem.SHUTTER)
            if (isoT != null) add(StripItem.ISO)
            if (focusT != null) add(StripItem.FOCUS)
            if (!cropRatio.isFull) add(StripItem.ASPECT)
            if (timer != TimerMode.OFF) add(StripItem.TIMER)
        }
        val lit = buildSet { if (flash != FlashMode.OFF) add(StripItem.FLASH); if (night) add(StripItem.NIGHT); if (hdr) add(StripItem.HDR); if (raw) add(StripItem.RAW) }
        val flashIcon = when (flash) { FlashMode.OFF -> Icons.Rounded.FlashOff; FlashMode.AUTO -> Icons.Rounded.FlashAuto; FlashMode.ON -> Icons.Rounded.FlashOn }
        val sheetItems = StripItem.entries.filter {
            when (it) {
                StripItem.FLASH -> flashAvailable && !night
                StripItem.NIGHT -> nightAvailable
                StripItem.HDR -> !night
                StripItem.RAW -> !night && (rawAvailable || raw)
                StripItem.SETTINGS -> !locked   // on the lock screen: the settings hold the developer tools and the dataset sessions, which are the owner's
                StripItem.EXPOSURE -> !manualExposureOn   // a hand-set exposure has no compensation
                else -> true
            }
        }
        val closeCard = { activeItem = null }

        // ---- the top: a centred row of small icons for what is switched on (the iPhone's), then the viewfinder
        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
                if (recording) Row(Modifier.clip(CircleShape).background(Color(0xFFFF3B30)).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(recordingClock(elapsed), style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
                } else if (videoMode) quality?.let { q -> TextPill(q.label, enabled = qualities.size > 1) { quality = q.next(qualities) } }
                else {
                    if (flashAvailable && !night) TopIcon(flashIcon, when (flash) { FlashMode.OFF -> "Flash off"; FlashMode.AUTO -> "Flash auto"; FlashMode.ON -> "Flash on" }, flash != FlashMode.OFF) { activeItem = StripItem.FLASH; showControls = false }
                    if (nightAvailable) TopIcon(Icons.Rounded.NightsStay, if (night) "Night mode on" else "Night mode off", night) { night = !night }
                    if (!cropRatio.isFull) TopIcon(Icons.Rounded.AspectRatio, "Aspect ${cropRatio.label}", true) { activeItem = StripItem.ASPECT; showControls = false }
                    if (timer != TimerMode.OFF) TopIcon(Icons.Rounded.Timer, "Timer ${timer.label}", true) { activeItem = StripItem.TIMER; showControls = false }
                    if (raw && !night) Text("RAW", style = IosType.footnote.copy(fontWeight = FontWeight.Bold), color = CameraYellow, modifier = Modifier.clip(CircleShape).clickable { raw = false }.padding(horizontal = 6.dp, vertical = 8.dp))
                    if (styleChanged) Box(Modifier.size(40.dp).clip(CircleShape).clickable { activeItem = StripItem.STYLES; showControls = false }.semantics { contentDescription = "Photographic style" }, contentAlignment = Alignment.Center) { DotGridGlyph(CameraYellow) }
                }
            }
            // ---- the pro readout: ISO, shutter, compensation and focus as they are now; each opens its dial
            // (its row is kept from the start so the picture does not jump when it appears; the values wait for the camera's first frames, so it never reads "ISO –")
            if (proReadout && !videoMode && nightExtension == null) Box(Modifier.fillMaxWidth().height(34.dp), contentAlignment = Alignment.Center) { if (liveExposure != null || manualExposureOn) {
                val iso = isoT?.let { isoFromT(it, controller.isoRange) } ?: liveExposure?.first
                val ns = shutterT?.let { shutterNsFromT(it, controller.exposureRangeNs) } ?: liveExposure?.second
                val open: (StripItem) -> () -> Unit = { item -> { showControls = false; activeItem = item } }
                ProReadout(listOfNotNull(
                    Triple("ISO ${iso ?: "–"}", isoT != null, open(StripItem.ISO)),
                    Triple(ns?.let { shutterLabel(it) } ?: "–", shutterT != null, open(StripItem.SHUTTER)),
                    if (!manualExposureOn) Triple("EV ${evLabel(evEff)}", evEff != 0f, open(StripItem.EXPOSURE)) else null,
                    Triple(when { focusT != null -> "MF " + focusLabel(focusT!! * controller.minFocusDiopters); aeAfLock -> "AE/AF L"; else -> "AF " + (liveFocus?.let { focusLabel(it) } ?: "") }.trim(), focusT != null || aeAfLock, open(StripItem.FOCUS)),
                ))
            } }
            // ---- the viewfinder
            // The picture is as big as the ratio says (4:3 is the sensor's own shape; 16:9 is taller, 1:1 shorter) and the controls stay where they were, on top of it: the
            // stage is always the 4:3 area under the top row, the picture sits in it (a taller one runs down under the controls) and everything that is not the picture is
            // laid on the stage, not on the picture. The preview is the sensor's 4:3 buffer, scaled to cover the picture and cut to it, as the photo is cut.
            val cropActive = computational && rawAvailable && !videoMode && !cropRatio.isFull
            val pictureHW = if (cropActive) cropRatio.longOverShort else 1f / ratio   // the picture's height over its width
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().layout { m, c ->
                val w = c.maxWidth; val h = maxOf(w / ratio, w * pictureHW).roundToInt(); val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(w, h)); layout(w, h) { p.place(0, 0) }
            }) {
                val w = maxWidth; val stageH = w / ratio; val pictureH = w * pictureHW
                val coverW = maxOf(w, pictureH * ratio); val coverH = coverW / ratio   // the buffer scaled to cover the picture
                val visibleX = w / coverW; val visibleY = pictureH / coverH             // the part of the buffer the picture shows
                // No first-run tip on the lock screen: it covered the subject brackets there, and a stranger's tap would have closed the owner's tip for good.
                var tourSeen by remember { mutableStateOf(locked || prefs.getBoolean("tourSeen", false)) }
                val tracked by rememberSubjectTracking(previewView, controller, subjectFocus && !videoMode && nightExtension == null && focusT == null && resumed, visible = { visibleX to visibleY }) { deviceDegrees }
                val roll by rememberRollDegrees(levelOn && !videoMode)
                val histogram by rememberHistogram(previewView, histogramOn && !videoMode)
                val peaking by rememberPeaking(previewView, focusT != null && !videoMode)
                // ---- the picture
                val pictureTop = if (pictureHW < 1f / ratio) (stageH - pictureH) / 2 else 0.dp
                Box(Modifier.offset(y = pictureTop).size(w, pictureH).clip(androidx.compose.ui.graphics.RectangleShape)) {
                    Box(Modifier.requiredSize(coverW, coverH).align(Alignment.Center)) {   // square-cornered, as the iPhone's
                    // One view for every pipeline (the normal one and the vendor extensions), never recreated when the mode changes: a new view would destroy the
                    // surface the camera is about to be given.
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
                        modifier = Modifier.matchParentSize(),
                    )
                    if (peaking != null) androidx.compose.foundation.Image(peaking!!.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds)
                        SubjectOverlay(tracked, hidden = focusPoint != null || aeAfLock, modifier = Modifier.matchParentSize())
                    }
                    Box(Modifier.matchParentSize().graphicsLayer { alpha = maxOf(shutterDip.value, veilAlpha) }.background(Color.Black))
                    // Touch on the picture: tap to focus (and meter) there, then drag up or down for exposure (the sun beside the focus square), pinch to zoom,
                    // swipe a long way sideways to change mode.
                    Box(Modifier.matchParentSize()
                        .pointerInput(Unit) { detectTapGestures(
                            onTap = { p -> if (!tourSeen) { tourSeen = true; prefs.edit().putBoolean("tourSeen", true).apply(); return@detectTapGestures }   // the first tap only closes the tip
                                if (aeAfLock) { aeAfLock = false; controller.unlock() }; focusPoint = p; focusTick++; controller.focusAt(0.5f + (p.x / size.width - 0.5f) * visibleX, 0.5f + (p.y / size.height - 0.5f) * visibleY) },
                            onLongPress = { p -> if (focusT == null && nightExtension == null && !videoMode) { focusPoint = p; focusTick++; aeAfLock = true; controller.lockAt(0.5f + (p.x / size.width - 0.5f) * visibleX, 0.5f + (p.y / size.height - 0.5f) * visibleY); hostView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) } }) }
                        .pointerInput(Unit) { detectTransformGestures { _, _, factor, _ -> if (factor != 1f) zoom = controller.setZoom(controller.zoom * factor) } }
                        .pointerInput(videoMode, recording) { var total = 0f; detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = { if (!recording) { if (total > 160f && videoMode) { videoMode = false } else if (total < -160f && !videoMode) { videoMode = true; night = false } } }) { _, dx -> total += dx } }
                        .pointerInput(Unit) { detectVerticalDragGestures { change, dy -> if (focusPoint != null && !manualExposureOn) { evValue = (evValue - dy / 110.dp.toPx()).coerceIn(-2f, 2f); focusTick++; change.consume() } } }) {
                        focusPoint?.let { p ->
                            val box = 72.dp
                            Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((p.x - box.toPx() / 2).toInt(), (p.y - box.toPx() / 2).toInt()) }.size(box).border(1.6.dp, CameraYellow, RoundedCornerShape(4.dp)))
                            if (!manualExposureOn) {
                                // the exposure handle: a thin line beside the square with a sun on it that rides up and down with the compensation
                                Box(Modifier.offset { androidx.compose.ui.unit.IntOffset((p.x + box.toPx() / 2 + 14.dp.toPx()).toInt(), (p.y - 55.dp.toPx()).toInt()) }.size(width = 1.5.dp, height = 110.dp).background(CameraYellow.copy(alpha = .8f)))
                                Icon(Icons.Rounded.WbSunny, null, tint = CameraYellow, modifier = Modifier.offset { androidx.compose.ui.unit.IntOffset((p.x + box.toPx() / 2 + 14.dp.toPx() - 11.dp.toPx()).toInt(), (p.y - (evEff / 2f) * 55.dp.toPx() - 11.dp.toPx()).toInt()) }.size(22.dp))
                            }
                        }
                    }
                    if (aeAfLock) Text("AE/AF LOCK", style = IosType.caption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = Color.Black,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp).clip(RoundedCornerShape(4.dp)).background(CameraYellow).padding(horizontal = 8.dp, vertical = 3.dp))
                    // the thirds grid, over the picture
                    if (grid) Box(Modifier.matchParentSize().drawBehind {
                        val c = Color.White.copy(alpha = .35f)
                        for (i in 1..2) { drawLine(c, androidx.compose.ui.geometry.Offset(size.width * i / 3, 0f), androidx.compose.ui.geometry.Offset(size.width * i / 3, size.height), 1.5f); drawLine(c, androidx.compose.ui.geometry.Offset(0f, size.height * i / 3), androidx.compose.ui.geometry.Offset(size.width, size.height * i / 3), 1.5f) }
                    })
                    LevelOverlay(roll, Modifier.matchParentSize())
                }
                // A view is not cut by the clip above (the TextureView draws its whole buffer), so where a shorter picture leaves the scaled buffer showing above and below it, black covers that.
                if (pictureH < stageH) {
                    Box(Modifier.fillMaxWidth().height(pictureTop).background(Color.Black))
                    Box(Modifier.offset(y = pictureTop + pictureH).fillMaxWidth().height(stageH - pictureTop - pictureH).background(Color.Black))
                }
                // ---- on the stage, over the picture and the black round it
                Box(Modifier.size(w, stageH)) {
                    if (!tourSeen && !videoMode) Column(Modifier.align(Alignment.Center).padding(horizontal = 28.dp).liquidPanel(RoundedCornerShape(26.dp), Color(0xE61C1C1E))
                        .clickable { tourSeen = true; prefs.edit().putBoolean("tourSeen", true).apply() }.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Using the camera", style = IosType.headline, color = Color.White)
                        listOf("Tap the picture to focus; drag up or down for exposure", "Press and hold to lock focus and exposure", "Pinch, or slide along the zoom numbers, to zoom", "Swipe sideways for video; the six dots open the controls").forEach {
                            Text(it, style = IosType.subheadline, color = Color.White.copy(alpha = .85f))
                        }
                        Text("Tap to close", style = IosType.footnote, color = CameraYellow)
                    }
                    HistogramView(histogram, Modifier.align(Alignment.TopStart).padding(10.dp))
                    if (countdown > 0) Text("$countdown", style = IosType.largeTitle.copy(fontSize = 96.sp), color = Color.White, modifier = Modifier.align(Alignment.Center))
                    // what the camera is doing, at the top of the picture: a dataset session, then any message
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (dataset && !videoMode) Row(Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .6f)).clickable { showSettings = false; showDataset = true }.padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.size(8.dp).background(CameraYellow, CircleShape))
                            Text("Dataset  ·  ${datasetKind.label}" + if (datasetSaved > 0) "  ·  $datasetSaved saved" else "", style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White, maxLines = 1)
                        }
                        status?.let { Text(it, style = IosType.footnote, color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp).liquidText(RoundedCornerShape(16.dp), Color.Black.copy(alpha = .7f)).padding(horizontal = 14.dp, vertical = 8.dp)) }
                    }
                    // zoom, the iPhone's way: plain numbers on the picture's lower edge; the one in use sits in a dark circle, in yellow, with a multiplication sign
                    if (activeItem == null && !showControls) run {   // the zoom numbers make way for an open card (whose shutter floats where they are) and for the controls sheet
                        val stops = zoomStops(zoomRange.start, zoomRange.endInclusive)
                        // sliding along the numbers zooms continuously (a zoom dial of sorts), as a pinch does; a tap on a number goes straight to it
                        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = if (videoMode) 118.dp else 12.dp)
                            .pointerInput(Unit) { detectHorizontalDragGestures { change, dx -> change.consume(); zoom = controller.setZoom(controller.zoom * kotlin.math.exp(dx / 260f)) } },
                            horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically) {
                            stops.forEach { stop ->
                                val on = kotlin.math.abs(zoom - stop) < 0.05f
                                val num = if (stop < 1f) "%.1f".format(java.util.Locale.ROOT, stop).trimStart('0') else "${stop.toInt()}"
                                Box(Modifier.size(48.dp).padding(if (on) 5.dp else 7.dp).then(if (on) Modifier.liquid(CircleShape, Color.Black.copy(alpha = .5f)) else Modifier.clip(CircleShape)).clickable { zoom = controller.setZoom(stop) }.semantics { contentDescription = "Zoom $num times" + if (on) ", selected" else "" }, contentAlignment = Alignment.Center) {
                                    Text(if (on) "$num×" else num, style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold), color = if (on) CameraYellow else Color.White)
                                }
                            }
                            if (stops.none { kotlin.math.abs(zoom - it) < 0.05f })
                                Box(Modifier.size(44.dp).liquid(CircleShape, Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                                    Text("%.1f×".format(java.util.Locale.ROOT, zoom), style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold), color = CameraYellow)
                                }
                        }
                    }
                }
            }
        }

        // ---- the bottom: the shutter with an exposure button on one side and the controls button on the other, and under it the last photo, the modes, the other lens.
        // With a control's card open the shutter floats just above the card, the two side buttons and the bottom row make way for it, as on the iPhone.
        val cardOpen = activeItem != null && !showControls
        val styleThumb by rememberStyleThumb(previewView, (activeItem == StripItem.STYLES || showChooseStyle) && !videoMode)
        if (!showControls) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {   // the sheet covers the shutter row
            Row(Modifier.fillMaxWidth().padding(horizontal = 38.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                if (!cardOpen && !videoMode && !manualExposureOn) CamCircle(48.dp, "Exposure", { activeItem = StripItem.EXPOSURE; showControls = false }, lit = evEff != 0f) { Icon(Icons.Rounded.Exposure, null, tint = if (evEff != 0f) CameraYellow else Color.White, modifier = Modifier.size(24.dp)) }
                else Spacer(Modifier.size(48.dp))
                // iOS 26 has no bright white ring: a ring of glass around the white disc
                val shutterRing: Modifier = if (liquid) Modifier.liquid(CircleShape, Color.Transparent) else Modifier.border(4.dp, Color.White.copy(alpha = .55f), CircleShape)
                // the shutter: a white disc in a grey ring; in video the disc is red, and a small red square while recording. The whole 80 dp is the button.
                Box(Modifier.size(80.dp).clip(CircleShape).clickable(enabled = !shutterBusy) { trigger() }.semantics { contentDescription = if (videoMode) (if (recording) "Stop recording" else "Record") else "Shutter" }
                    .then(shutterRing).padding(if (recording) 24.dp else 6.dp).clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                    .background(if (videoMode) Color(0xFFFF3B30) else if (shutterBusy) Color.White.copy(alpha = .55f) else Color.White))
                if (!cardOpen) CamCircle(48.dp, "Controls", { showControls = !showControls; activeItem = null }, lit = showControls || changed.isNotEmpty()) { SixDots(color = if (changed.isNotEmpty() && !showControls) CameraYellow else Color.White) }
                else Spacer(Modifier.size(48.dp))
            }
            if (!cardOpen) {
                Spacer(Modifier.height(14.dp))
                val thumbShape = if (liquid) CircleShape else RoundedCornerShape(12.dp)   // iOS 26's review button is round
                Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Box(Modifier.size(46.dp).clip(thumbShape).background(Color.White.copy(alpha = .12f)).then(if (liquid) Modifier else Modifier.border(1.5.dp, Color.White.copy(alpha = .4f), thumbShape)).combinedClickable(onLongClick = { if (!locked && !CaptureContract.showInSietch(context, android.os.Environment.getExternalStoragePublicDirectory(if (videoMode) android.os.Environment.DIRECTORY_MOVIES else android.os.Environment.DIRECTORY_PICTURES).path + "/Holtzman") && !CaptureContract.showLibrary(context, videoMode)) status = "No gallery app found" }, enabled = if (videoMode) lastVideo != null else last != null) {
                        (if (videoMode) lastVideo else last?.jpeg)?.let { current -> scope.launch { val all = if (locked) (if (videoMode) sessionVideos else sessionShots).toList() else withContext(Dispatchers.IO) { if (videoMode) videoSaver.listVideos() else saver.listPhotos() }
                            if (all.isEmpty()) { if (!locked && !CaptureContract.view(context, current, if (videoMode) "video/mp4" else "image/jpeg")) status = "No gallery app found" } else { viewerStart = all.indexOf(current).coerceAtLeast(0); viewerItems = all } } }
                    }, contentAlignment = Alignment.Center) {
                        val thumb = lastThumb
                        if (thumb != null) androidx.compose.foundation.Image(thumb.asImageBitmap(), "Last photo", Modifier.matchParentSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        else Icon(Icons.Rounded.PhotoLibrary, "Last photo", tint = Color.White.copy(alpha = .8f))
                        // a photo is being taken or still being processed: the thumbnail dims and spins until it is saved, so there is always a sign the shot was not lost
                        if (!videoMode && (pending > 0 || busy)) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = .5f)), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(Modifier.size(22.dp), color = CameraYellow, strokeWidth = 2.dp)
                        }
                    }
                    if (!recording) ModeCapsule(videoMode, true, { v -> if (v == videoMode) { showControls = true; activeItem = null } else { videoMode = v; if (v) night = false } })   // as on iOS 26, a tap on the active mode opens the controls else Spacer(Modifier.size(1.dp))
                    CamCircle(46.dp, "Switch camera", { lens = if (lens == Lens.BACK) Lens.FRONT else Lens.BACK }) { Icon(Icons.Rounded.FlipCameraAndroid, null, tint = Color.White) }
                }
            } else {
                Spacer(Modifier.height(10.dp))
                when (activeItem) {
                    StripItem.FLASH -> CardFrame { CardHeader(closeCard) { CardTitle("Flash") }; OptionsRow(FlashMode.entries.map { it.label }, flash.ordinal, { flash = FlashMode.entries[it] }) }
                    StripItem.ASPECT -> CardFrame { CardHeader(closeCard) { CardTitle("Aspect") }; OptionsRow(PhotoRatio.entries.map { it.label }, cropRatio.ordinal, { cropRatio = PhotoRatio.entries[it] }) }
                    StripItem.TIMER -> CardFrame { CardHeader(closeCard) { CardTitle("Timer") }; OptionsRow(TimerMode.entries.map { it.label }, timer.ordinal, { timer = TimerMode.entries[it] }) }
                    StripItem.STYLES -> StylesCard(preset, { preset = it }, styleIntensity, { styleIntensity = it },
                        (warmthValue / 20f).coerceIn(-1f, 1f), (shadowsValue / 20f).coerceIn(-1f, 1f), { c, t -> warmthValue = c * 20f; shadowsValue = t * 20f },
                        textureStyle, { textureStyle = it }, textureAmount, { textureAmount = it }, styleThumb, closeCard, startOnTexture = startStylesOnTexture)
                    // the pro dials: a ruler under a fixed needle, the iPhone's; ISO, shutter and focus start from what the camera is doing now
                    StripItem.EXPOSURE -> {
                        val i = EV_STOPS.indices.minByOrNull { kotlin.math.abs(EV_STOPS[it] - evEff) } ?: 6
                        DialCard("Exposure", evLabel(EV_STOPS[i]), EV_STOPS.map { evLabel(it) }, i, { evValue = EV_STOPS[it] }, closeCard)
                    }
                    StripItem.SHUTTER -> {
                        val stops = shutterStops(controller.exposureRangeNs)
                        val now = shutterT?.let { shutterNsFromT(it, controller.exposureRangeNs) } ?: liveExposure?.second ?: stops[stops.size / 2]
                        val i = stops.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(stops[it].toDouble() / now)) } ?: 0
                        DialCard("Shutter", shutterLabel(now), stops.map { shutterLabel(it) }, i, { shutterT = it / (stops.size - 1f).coerceAtLeast(1f) }, closeCard, auto = shutterT == null, onAuto = { shutterT = null })
                    }
                    StripItem.ISO -> {
                        val stops = isoStops(controller.isoRange)
                        val now = isoT?.let { isoFromT(it, controller.isoRange) } ?: liveExposure?.first ?: stops[stops.size / 2]
                        val i = stops.indices.minByOrNull { kotlin.math.abs(kotlin.math.ln(stops[it].toDouble() / now)) } ?: 0
                        DialCard("ISO", "$now", stops.map { "$it" }, i, { isoT = it / (stops.size - 1f).coerceAtLeast(1f) }, closeCard, auto = isoT == null, onAuto = { isoT = null })
                    }
                    StripItem.FOCUS -> {
                        val stops = focusStops(controller.minFocusDiopters)
                        val now = focusT?.let { it * controller.minFocusDiopters } ?: liveFocus ?: 0f
                        val i = stops.indices.minByOrNull { kotlin.math.abs(stops[it] - now) } ?: 0
                        DialCard("Focus", if (focusT == null) "AF " + focusLabel(now) else focusLabel(now), stops.map { focusLabel(it) }, i, { focusT = it / (stops.size - 1f).coerceAtLeast(1f); if (aeAfLock) { aeAfLock = false; controller.unlock() } }, closeCard, auto = focusT == null, onAuto = { focusT = null }, labelEvery = 5)
                    }
                    else -> {}
                }
            }
        }
        // ---- the controls sheet: over everything below the picture; touching outside it puts it away
        if (showControls) {
            androidx.activity.compose.BackHandler { showControls = false }   // Back puts the sheet away; before, it left the app
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .4f)).clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { showControls = false })
            ControlsSheet(sheetItems, lit, changed, { i ->
                when (i) {
                    StripItem.NIGHT -> night = !night
                    StripItem.HDR -> if (zoom < 0.99f || zoom > 1.01f) status = "Ultra HDR needs 1×" else hdr = !hdr
                    StripItem.RAW -> if (zoom < 0.99f) status = "RAW is not available below 1×" else raw = !raw
                    StripItem.SETTINGS -> { showControls = false; showSettings = true }
                    else -> { showControls = false; startStylesOnTexture = false; activeItem = i }
                }
            }, Modifier.align(Alignment.BottomCenter).glassEnter(androidx.compose.ui.graphics.TransformOrigin(.9f, 1f))) { item, isOn ->
                val tint = if (isOn) CameraYellow else Color.White
                when (item) {
                    StripItem.FLASH -> Icon(flashIcon, null, tint = if (flash != FlashMode.OFF) CameraYellow else Color.White, modifier = Modifier.size(27.dp))
                    StripItem.ASPECT -> Text(when (cropRatio) { PhotoRatio.FULL -> "4:3"; PhotoRatio.SQUARE -> "1:1"; PhotoRatio.FOUR_FIVE -> "4:5"; PhotoRatio.WIDE -> "16:9" }, style = IosType.subheadline.copy(fontWeight = FontWeight.Bold), color = if (cropRatio.isFull) Color.White else CameraYellow)
                    StripItem.TIMER -> if (timer == TimerMode.OFF) Icon(Icons.Rounded.TimerOff, null, tint = Color.White, modifier = Modifier.size(27.dp)) else Text("${timer.seconds}s", style = IosType.subheadline.copy(fontWeight = FontWeight.Bold), color = CameraYellow)
                    StripItem.STYLES -> DotGridGlyph(if (styleChanged) CameraYellow else Color.White)
                    StripItem.ISO -> Text("ISO", style = IosType.subheadline.copy(fontWeight = FontWeight.Bold), color = tint)   // the plus-minus square is Exposure's
                    else -> Icon(item.icon, null, tint = tint, modifier = Modifier.size(27.dp))
                }
            }
        }
    }
    }
    LaunchedEffect(isoT, shutterT) { controller.setManualExposure(isoT?.let { isoFromT(it, controller.isoRange) }, shutterT?.let { shutterNsFromT(it, controller.exposureRangeNs) }) }
    LaunchedEffect(focusT) { controller.setManualFocus(focusT?.let { it * controller.minFocusDiopters }) }
    LaunchedEffect(evEff) { controller.setExposureCompensation(evEff) }
    LaunchedEffect(warmthEff) { controller.setWarmth(1f + 0.006f * warmthEff) }
    if (showPhotoSettings) PhotoSettingsSheet(
        grid = grid, onGrid = { grid = it }, level = levelOn, onLevel = { levelOn = it }, histogram = histogramOn, onHistogram = { histogramOn = it },
        proReadout = proReadout, onProReadout = { proReadout = it }, subjectFocus = subjectFocus, onSubjectFocus = { subjectFocus = it },
        longCapture = longCapture, onLongCapture = { longCapture = it }, instant = instant && !longCapture, onInstant = { instant = it; if (it) longCapture = false },
        onDismiss = { showPhotoSettings = false })
    if (showChooseStyle) {
        val chooseThumb by rememberStyleThumb(previewView, true, 360, 480)
        ChooseStyleScreen(preset, { preset = it }, styleIntensity, chooseThumb, { showChooseStyle = false },
            { showChooseStyle = false; startStylesOnTexture = true; activeItem = StripItem.STYLES })
    }
    viewerItems?.let { items -> MediaViewer(items, viewerStart, videoMode, restricted = locked,
        onDelete = { uri -> withContext(Dispatchers.IO) { if (videoMode) videoSaver.deleteVideo(uri) else saver.deletePhoto(uri) }.also { ok -> if (ok) { sessionShots.remove(uri); sessionVideos.remove(uri) } } },
        onClose = {
            viewerItems = null
            // what the review button shows may have been deleted: look again
            scope.launch { if (videoMode) lastVideo = if (locked) sessionVideos.firstOrNull() else withContext(Dispatchers.IO) { videoSaver.latestVideo() } else last = (if (locked) sessionShots.firstOrNull() else withContext(Dispatchers.IO) { saver.latestPhoto() })?.let { Saved(it, null) } }
        }) }   // the app's own swipeable viewer, over everything
    if (showSettings) SettingsSheet(liquid = liquidSetting, onLiquid = { liquidSetting = it }, 
        computational = burst, onComputational = { burst = it },
        hdr = hdr, onHdr = { hdr = it },
        raw = raw, onRaw = { raw = it },
        texture = texture, onTexture = { texture = it }, grain = grain, onGrain = { grain = it },
        datasetOn = dataset, datasetKind = datasetKind.label, datasetSaved = datasetSaved,
        onCaptureSettings = { showSettings = false; showPhotoSettings = true },
        styleName = preset.label + if (textureStyle != BurstRender.TextureStyle.STANDARD) " + ${textureStyle.label}" else "",
        onStyles = { showSettings = false; showControls = false; showChooseStyle = true },
        onDataset = { showSettings = false; showDataset = true },
        onReport = { showSettings = false; showReport = true },
        onCompare = { showSettings = false; shoot(highQuality = false); scope.launch { delay(2500); shoot(highQuality = true) } },
        onDismiss = { showSettings = false })
    if (showDataset) DatasetSheet(
        active = dataset, kind = datasetKind, onKind = { datasetKind = it },
        tags = datasetTags, onTag = { t -> datasetTags = if (t in datasetTags) datasetTags - t else datasetTags + t },
        note = datasetNote, onNote = { datasetNote = it }, saved = datasetSaved,
        pixelFirst = pixelFirst, onPixelFirst = { pixelFirst = it }, pixelAvailable = com.everettpenne.holtzman.save.PixelPairing.available(context),
        onStart = { dataset = true; showDataset = false }, onStop = { dataset = false; showDataset = false },
        onCalibrate = { showDataset = false; calibrateNoise() },
        onDismiss = { showDataset = false })
    if (showReport) CameraReportSheet(onDismiss = { showReport = false })
}

/** An icon on the top bar, bare like the iPhone's: white, or yellow when its feature is on. */
@Composable
private fun TopIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, on: Boolean, onClick: () -> Unit) {
    if (LocalLiquid.current) CamCircle(40.dp, description, onClick) { Icon(icon, null, tint = if (on) CameraYellow else Color.White, modifier = Modifier.size(22.dp)) }
    else Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) CameraYellow else Color.White, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun TextPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(label, maxLines = 1, softWrap = false, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White,
        modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .45f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp))
}

/** A paired capture waiting for the Pixel Camera app: the scene's name and the file the photo is to be written to. */
private class PendingPair(val scene: String, val uri: android.net.Uri)
