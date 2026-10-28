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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import com.everettpenne.holtzman.camera.*
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

@Composable
fun HoltzmanRoot() = HoltzmanTheme { CameraGate() }

/** Asks for the camera, in words, before anything else; the camera is the one permission the app has. */
@Composable
private fun CameraGate() {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) CameraScreen() else Box(Modifier.fillMaxSize().background(Color.Black).padding(32.dp), contentAlignment = Alignment.Center) {
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
private fun CameraScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { CameraController(context) }
    val saver = remember { PhotoSaver(context.contentResolver) }
    var lens by remember { mutableStateOf(Lens.BACK) }
    var raw by remember { mutableStateOf(false) }
    var rawAvailable by remember { mutableStateOf(false) }
    var night by remember { mutableStateOf(false) }
    var hdr by remember { mutableStateOf(false) }
    var videoMode by remember { mutableStateOf(false) }
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
    var surfaceReady by remember { mutableStateOf<SurfaceHolder?>(null) }
    var surfaceDims by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<Saved?>(null) }
    var showProbe by remember { mutableStateOf(false) }
    var deviceDegrees by remember { mutableIntStateOf(0) }
    val qualities = remember(lens) { controller.videoQualitiesFor(lens) }
    LaunchedEffect(qualities) { if (quality == null || quality !in qualities) quality = defaultVideoQuality(qualities) }
    val nightAvailable = remember(lens) { android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT in controller.extensionsFor(lens) }
    // a lens without Night turns Night off
    LaunchedEffect(nightAvailable) { if (!nightAvailable) night = false }
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
    LaunchedEffect(surfaceReady, lens, raw, hdr, resumed, previewShape, nightExtension, videoMode, quality) {
        if (surfaceReady == null || !resumed) { controller.close(); return@LaunchedEffect }
        previewShape?.let { shape ->
            val holder = surfaceReady ?: return@let
            if (surfaceDims != shape.width to shape.height) {
                // changing a SurfaceView's buffer size replaces its Surface a moment later; a camera opened on the old one fails as "unconfigured". Wait for the new size.
                holder.setFixedSize(shape.width, shape.height)
                kotlinx.coroutines.withTimeoutOrNull(1500) { snapshotFlow { surfaceDims }.first { it == shape.width to shape.height } }
            }
        }
        val surface = surfaceReady?.surface ?: run { controller.close(); return@LaunchedEffect }
        controller.open(lens, surface, raw || hdr, if (videoMode) null else nightExtension, if (videoMode) quality else null, onReady = { rawAvailable = controller.rawAvailable; zoom = controller.zoom; flashAvailable = controller.flashAvailable; zoomRange = controller.zoomMin..controller.zoomMax }, onError = { android.util.Log.w("HoltzmanCamera", "camera error (extension=$nightExtension): $it"); status = it; if (nightExtension != null) night = false })
    }
    DisposableEffect(controller) {
        // however a recording ends (stopped, or the screen was left), the file is finished or removed here
        controller.onRecordingEnded = { ok ->
            val v = pendingVideo; pendingVideo = null
            scope.launch(Dispatchers.Main) { recording = false }
            if (v != null) scope.launch(Dispatchers.IO) { videoSaver.finish(v, ok); withContext(Dispatchers.Main) { if (ok) { lastVideo = v.uri; status = "Saved video" } else status = "Recording too short to keep" } }
        }
        onDispose { controller.onRecordingEnded = null }
    }
    LaunchedEffect(recording) { elapsed = 0; while (recording) { delay(1000); elapsed++ } }
    LaunchedEffect(videoMode) { if (videoMode && !audioGranted) askAudio.launch(android.Manifest.permission.RECORD_AUDIO) }
    LaunchedEffect(focusPoint) { if (focusPoint != null) { delay(1200); focusPoint = null } }
    LaunchedEffect(status) { if (status != null) { delay(3500); status = null } }

    fun shoot(highQuality: Boolean = false) {
        if (busy) return
        busy = true
        if (night) status = "Hold still…"
        controller.capture(deviceDegrees, highQuality, if (night) FlashMode.OFF else flash, onResult = { still ->
            // the heavy part (writing the files) leaves the camera thread, so the next preview frame is never held up by storage
            scope.launch(Dispatchers.IO) {
                // Ultra HDR is built from the RAW taken beside the JPEG, only at 1x (the RAW frame is not cropped by the zoom, so the two would not line up)
                val wantHdr = hdr && !night && still.raw != null && kotlin.math.abs(zoom - 1f) < 0.02f
                val hdrBytes = if (wantHdr) UltraHdr.build(still) else null
                val saved = runCatching { saver.save(still, hdrBytes, keepDng = raw) }
                withContext(Dispatchers.Main) {
                    busy = false
                    saved.onSuccess { last = it; status = when { hdrBytes != null && it.dng != null -> "Saved Ultra HDR + DNG"; hdrBytes != null -> "Saved Ultra HDR"; hdr && !night && !wantHdr -> "Saved (Ultra HDR needs 1×)"; hdr && !night -> "Saved (no highlights to recover)"; it.dng != null -> "Saved JPEG + DNG"; night -> "Saved (Night)"; else -> "Saved" } }.onFailure { status = "Couldn't save: ${it.message}" }
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
    LaunchedEffect(Unit) { ShutterKeys.flow.collect { if (!busy) trigger() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // The preview's buffer is landscape (the sensor's own); the screen shows it upright, so the box is its shape turned a quarter.
        val ratio = previewShape?.let { it.height.toFloat() / it.width } ?: (3f / 4f)
        // One SurfaceView for every pipeline (the normal one and the vendor extensions both deliver an upright preview), never recreated when the
        // mode changes: a new view would destroy the surface the camera is about to be given.
        run {
            AndroidView(
                factory = { c -> SurfaceView(c).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(h: SurfaceHolder) { surfaceReady = h }
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) { surfaceDims = w to hh }
                        override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = null }
                    })
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
        if (countdown > 0) Text("$countdown", style = IosType.largeTitle.copy(fontSize = 96.sp), color = Color.White, modifier = Modifier.align(Alignment.Center))

        // top: the probe, and what the camera is saying
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(Icons.Rounded.Analytics, "Camera probe") { showProbe = true }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!videoMode && nightAvailable) Chip("NIGHT", night, enabled = true) { night = !night; if (night) { raw = false; hdr = false } }
                if (!videoMode && !night) Chip("HDR", hdr, enabled = true) { hdr = !hdr }
                if (!videoMode && !night && (rawAvailable || !raw)) Chip("RAW", raw, enabled = true) { raw = !raw; if (!raw) rawAvailable = false }
            }
        }
        // flash (when the lens has one and the pipeline can use it), the timer, and the grid: a second row, over the top of the picture
        Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (videoMode) quality?.let { q -> Chip(q.label, false, enabled = !recording && qualities.size > 1) { quality = q.next(qualities) } }
            if (!videoMode && flashAvailable && !night) Chip(when (flash) { FlashMode.OFF -> "⚡ Off"; FlashMode.AUTO -> "⚡ Auto"; FlashMode.ON -> "⚡ On" }, flash != FlashMode.OFF, enabled = true) { flash = flash.next() }
            if (!videoMode) Chip(if (timer == TimerMode.OFF) "Timer" else timer.label, timer != TimerMode.OFF, enabled = true) { timer = timer.next() }
            Chip("Grid", grid, enabled = true) { grid = !grid }
        }
        status?.let { Text(it, style = IosType.subheadline, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 120.dp)
            .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp)) }

        // recording clock
        if (recording) Row(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 120.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(Holtzman.red, CircleShape)); Spacer(Modifier.width(8.dp))
            Text(recordingClock(elapsed), style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Color.White)
        }
        // PHOTO / VIDEO
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 196.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            listOf(false to "PHOTO", true to "VIDEO").forEach { (v, label) ->
                Text(label, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (videoMode == v) Holtzman.accent else Color.White.copy(alpha = .75f),
                    modifier = Modifier.clickable(enabled = !recording && videoMode != v) { videoMode = v; if (v) { night = false; hdr = false } }.padding(8.dp))
            }
        }
        // zoom: the stops the camera offers, and the ratio now applied
        Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            zoomStops(zoomRange.start, zoomRange.endInclusive).forEach { stop ->
                val on = kotlin.math.abs(zoom - stop) < 0.05f
                Text(if (stop == 1f) "1×" else if (stop < 1f) "%.1f".format(java.util.Locale.ROOT, stop).trimStart('0') else "${stop.toInt()}×", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) Holtzman.accent else Color.White,
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .5f)).clickable { zoom = controller.setZoom(stop) }.padding(horizontal = 14.dp, vertical = 9.dp))
            }
            if (zoomStops(zoomRange.start, zoomRange.endInclusive).none { kotlin.math.abs(zoom - it) < 0.05f })
                Text("%.1f×".format(java.util.Locale.ROOT, zoom), style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Holtzman.accent,
                    modifier = Modifier.clip(CircleShape).background(Color.Black.copy(alpha = .5f)).padding(horizontal = 14.dp, vertical = 9.dp))
        }

        // bottom: the last photo, the shutter, the other lens
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp, start = 32.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .12f)).clickable(enabled = if (videoMode) lastVideo != null else last != null) {
                (if (videoMode) lastVideo else last?.jpeg)?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it, if (videoMode) "video/mp4" else "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
            }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PhotoLibrary, "Last photo", tint = Color.White.copy(alpha = .8f)) }
            // the whole 84 dp circle is the button: the tap area is set before the ring's padding, so it does not shrink while recording
            Box(Modifier.size(84.dp).clip(CircleShape).clickable(enabled = !busy) { trigger() }.semantics { contentDescription = if (videoMode) (if (recording) "Stop recording" else "Record") else "Shutter" }
                .border(4.dp, Color.White, CircleShape).padding(if (recording) 22.dp else 7.dp).clip(if (recording) RoundedCornerShape(8.dp) else CircleShape)
                .background(if (videoMode) Holtzman.red else if (busy) Holtzman.accent.copy(alpha = .5f) else Color.White))
            Box(Modifier.size(56.dp).clip(CircleShape).background(Color.White.copy(alpha = .12f)).clickable { lens = if (lens == Lens.BACK) Lens.FRONT else Lens.BACK },
                contentAlignment = Alignment.Center) { Icon(Icons.Rounded.FlipCameraAndroid, "Switch camera", tint = Color.White) }
        }
    }
    if (showProbe) ProbeSheet(onDismiss = { showProbe = false }, onCompare = { showProbe = false; shoot(highQuality = false); scope.launch { delay(2500); shoot(highQuality = true) } })
}

@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(Color.Black.copy(alpha = .45f)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Chip(label: String, on: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Text(label, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (on) Color.Black else Color.White,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Holtzman.accent else Color.Black.copy(alpha = .45f)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp))
}

