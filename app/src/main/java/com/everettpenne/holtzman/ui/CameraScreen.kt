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
import androidx.compose.foundation.layout.*
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
import com.everettpenne.holtzman.save.Saved
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    var surfaceReady by remember { mutableStateOf<SurfaceHolder?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf<Saved?>(null) }
    var showProbe by remember { mutableStateOf(false) }
    var deviceDegrees by remember { mutableIntStateOf(0) }
    val nightAvailable = remember(lens) { android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT in controller.extensionsFor(lens) }
    // a lens without Night turns Night off
    LaunchedEffect(nightAvailable) { if (!nightAvailable) night = false }
    val nightExtension = if (night && nightAvailable) android.hardware.camera2.CameraExtensionCharacteristics.EXTENSION_NIGHT else null
    val previewShape = remember(lens, nightExtension) { controller.previewSizeFor(lens, nightExtension) }

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
    LaunchedEffect(surfaceReady, lens, raw, resumed, previewShape, nightExtension) {
        val surface = surfaceReady?.surface
        if (surface == null || !resumed) { controller.close(); return@LaunchedEffect }
        previewShape?.let { surfaceReady?.setFixedSize(it.width, it.height) }
        controller.open(lens, surface, raw, nightExtension, onReady = { rawAvailable = controller.rawAvailable }, onError = { android.util.Log.w("HoltzmanCamera", "camera error (extension=$nightExtension): $it"); status = it; if (nightExtension != null) night = false })
    }
    LaunchedEffect(status) { if (status != null) { delay(3500); status = null } }

    fun shoot(highQuality: Boolean = false) {
        if (busy) return
        busy = true
        if (night) status = "Hold still…"
        controller.capture(deviceDegrees, highQuality, onResult = { still ->
            // the heavy part (writing the files) leaves the camera thread, so the next preview frame is never held up by storage
            scope.launch(Dispatchers.IO) {
                val saved = runCatching { saver.save(still) }
                withContext(Dispatchers.Main) {
                    busy = false
                    saved.onSuccess { last = it; status = if (it.dng != null) "Saved JPEG + DNG" else if (night) "Saved (Night)" else "Saved" }.onFailure { status = "Couldn't save: ${it.message}" }
                }
            }
        }, onError = { scope.launch(Dispatchers.Main) { busy = false; status = it } })
    }

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
                        override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
                        override fun surfaceDestroyed(h: SurfaceHolder) { surfaceReady = null }
                    })
                } },
                modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().aspectRatio(ratio),
            )
        }

        // top: the probe, and what the camera is saying
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(Icons.Rounded.Analytics, "Camera probe") { showProbe = true }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (nightAvailable) Chip("NIGHT", night, enabled = true) { night = !night; if (night) raw = false }
                if (!night && (rawAvailable || !raw)) Chip("RAW", raw, enabled = true) { raw = !raw; if (!raw) rawAvailable = false }
            }
        }
        status?.let { Text(it, style = IosType.subheadline, color = Color.White, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp)
            .background(Color.Black.copy(alpha = .6f), RoundedCornerShape(50)).padding(horizontal = 16.dp, vertical = 8.dp)) }

        // bottom: the last photo, the shutter, the other lens
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(bottom = 28.dp, start = 32.dp, end = 32.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = .12f)).clickable(enabled = last != null) {
                last?.let { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(it.jpeg, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) } }
            }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.PhotoLibrary, "Last photo", tint = Color.White.copy(alpha = .8f)) }
            Box(Modifier.size(84.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape).background(if (busy) Holtzman.accent.copy(alpha = .5f) else Color.White)
                .clickable(enabled = !busy) { shoot() }.semantics { contentDescription = "Shutter" })
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

