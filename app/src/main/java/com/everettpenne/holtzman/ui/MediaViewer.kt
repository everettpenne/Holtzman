package com.everettpenne.holtzman.ui

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.util.Size
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.CaptureContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The app's own viewer for what it saved: a swipe through the photos (or the videos, in video mode) newest first, a pinch or double tap to zoom a photo, a tap to hide the
 * controls, share and "open in gallery" buttons and a delete button (after a question). An Ultra HDR photo is shown in HDR where the screen can (the window is put in HDR
 * colour mode while the viewer is open), marked "HDR". [restricted] (on the lock screen) leaves out sharing and the gallery. [onDelete] deletes one item and says
 * whether it did. [uris] is the media library's list when the viewer was opened, [start] the one to show first. Back closes it.
 */
@Composable
fun MediaViewer(uris: List<Uri>, start: Int, video: Boolean, restricted: Boolean = false, onDelete: suspend (Uri) -> Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val items = remember { androidx.compose.runtime.mutableStateListOf<Uri>().apply { addAll(uris) } }
    if (items.isEmpty()) { onClose(); return }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, items.lastIndex)) { items.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    val hdr = remember { androidx.compose.runtime.mutableStateMapOf<Uri, Boolean>() }
    BackHandler(onBack = onClose)
    HdrWindow(!video)
    val mime = if (video) "video/mp4" else "image/jpeg"
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed, key = { items[it].toString() }) { page ->
            if (video) VideoPage(items[page], restricted, onTap = { chrome = !chrome })
            else PhotoPage(items[page], active = pager.currentPage == page, onTap = { chrome = !chrome }, onZoomed = { zoomed = it }, onLoaded = { hdr[items[page]] = it })
        }
        val current = items.getOrNull(pager.currentPage)
        if (chrome && current != null) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CamCircle(44.dp, "Close", onClose) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("${pager.currentPage + 1} of ${items.size}", style = IosType.subheadline, color = Color.White, modifier = Modifier.liquidText(androidx.compose.foundation.shape.CircleShape, Color.Black.copy(alpha = .5f)).padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                    if (hdr[current] == true) Text("HDR", style = IosType.caption1.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold), color = Color.White, modifier = Modifier.liquidText(androidx.compose.foundation.shape.CircleShape, Color.Black.copy(alpha = .5f)).padding(horizontal = 8.dp, vertical = 4.dp))
                }
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                if (restricted) Box(Modifier.size(52.dp)) else CamCircle(52.dp, "Share", { CaptureContract.share(context, current, mime) }) { Icon(Icons.Rounded.Share, null, tint = Color.White) }
                if (!restricted) CamCircle(52.dp, "Open in gallery", { CaptureContract.view(context, current, mime) }) { Icon(Icons.Rounded.OpenInNew, null, tint = Color.White) }
                CamCircle(52.dp, "Delete", { asking = true }) { Icon(Icons.Rounded.Delete, null, tint = Color.White) }
            }
        }
    }
    if (asking) androidx.compose.material3.AlertDialog(
        onDismissRequest = { asking = false },
        title = { Text(if (video) "Delete this video?" else "Delete this photo?") },
        text = { Text(if (video) "It is removed from your phone." else "It is removed from your phone, with its DNG if it has one.") },
        confirmButton = { androidx.compose.material3.TextButton({
            asking = false
            val target = items.getOrNull(pager.currentPage) ?: return@TextButton
            scope.launch { if (onDelete(target)) items.remove(target) else android.widget.Toast.makeText(context, "Couldn't delete it", android.widget.Toast.LENGTH_SHORT).show() }
        }) { Text("Delete", color = Color(0xFFFF453A)) } },   // destructive, in the system red
        dismissButton = { androidx.compose.material3.TextButton({ asking = false }) { Text("Cancel", color = Color.White) } },
        containerColor = Color(0xFF2C2C2E), titleContentColor = Color.White, textContentColor = Color.White.copy(alpha = .75f),
    )
}

/**
 * Puts the window in HDR colour mode while [on] (and the screen can show HDR), so an Ultra HDR photo's gain map is applied by the display instead of being ignored, and puts it
 * back when the viewer closes. The camera's own screen stays standard.
 */
@Composable
private fun HdrWindow(on: Boolean) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(on) {
        var activity: android.app.Activity? = null; var c: android.content.Context = context
        while (c is android.content.ContextWrapper) { if (c is android.app.Activity) { activity = c; break }; c = c.baseContext }
        val window = activity?.window; val before = window?.colorMode
        if (on && window != null && context.resources.configuration.isScreenHdr) window.colorMode = android.content.pm.ActivityInfo.COLOR_MODE_HDR
        onDispose { if (window != null && before != null) window.colorMode = before }
    }
}

/** One photo: decoded to at most about 2400 px on its long side (the viewer is not for pixel peeping, and a 50 MP picture would not fit), then pinched, panned and double-tapped. */
@Composable
private fun PhotoPage(uri: Uri, active: Boolean, onTap: () -> Unit, onZoomed: (Boolean) -> Unit, onLoaded: (Boolean) -> Unit) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.setTargetSampleSize(max(1, max(info.size.width, info.size.height) / 2400))
                }
            }.getOrNull()
        }
    }
    LaunchedEffect(bitmap) { bitmap?.let { onLoaded(it.hasGainmap()); android.util.Log.i("HoltzmanViewer", "${uri.lastPathSegment}: ${it.width} x ${it.height}, gain map ${it.hasGainmap()}") } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, pan, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + pan
        onZoomed(scale > 1.02f)
    }
    if (!active && scale != 1f) { scale = 1f; offset = Offset.Zero; onZoomed(false) }   // a page swiped away comes back fitted
    Box(Modifier.fillMaxSize().transformable(state, canPan = { scale > 1f })
        .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onDoubleTap = { if (scale > 1f) { scale = 1f; offset = Offset.Zero; onZoomed(false) } else { scale = 2.5f; onZoomed(true) } }) },
        contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y }, contentScale = ContentScale.Fit) }
    }
}

/** One video: its first frame with a play button, which hands the video to a player (the viewer does not play video itself). */
@Composable
private fun VideoPage(uri: Uri, restricted: Boolean, onTap: () -> Unit) {
    val context = LocalContext.current
    val frame by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, Size(1080, 1080), null) }.getOrNull() } }
    Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = null, onClick = onTap), contentAlignment = Alignment.Center) {
        frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        // on the lock screen no player may be opened, so there is no play button that would do nothing
        if (!restricted) Box(Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = .55f)).clickable { CaptureContract.view(context, uri, "video/mp4") }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}
