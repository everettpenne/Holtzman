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
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * The app's own viewer for what it saved: a swipe through the photos (or the videos, in video mode) newest first, a pinch or double tap to zoom a photo, a tap to hide the
 * controls, and a share button (to any app; the JPEG of a photo that also has a DNG) and an "open in gallery" button for anything the viewer does not do. Back closes it.
 * [uris] is the media library's list when the viewer was opened, [start] the one to show first.
 */
@Composable
fun MediaViewer(uris: List<Uri>, start: Int, video: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    val pager = rememberPagerState(initialPage = start.coerceIn(0, uris.lastIndex)) { uris.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)
    val mime = if (video) "video/mp4" else "image/jpeg"
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, userScrollEnabled = !zoomed, key = { uris[it].toString() }) { page ->
            if (video) VideoPage(uris[page], onTap = { chrome = !chrome })
            else PhotoPage(uris[page], active = pager.currentPage == page, onTap = { chrome = !chrome }, onZoomed = { zoomed = it })
        }
        if (chrome) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                CamCircle(44.dp, "Close", onClose) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    Text("${pager.currentPage + 1} of ${uris.size}", style = IosType.subheadline, color = Color.White, modifier = Modifier.liquidText(androidx.compose.foundation.shape.CircleShape, Color.Black.copy(alpha = .5f)).padding(horizontal = 14.dp, vertical = 6.dp))
                }
                Box(Modifier.size(44.dp))
            }
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(horizontal = 32.dp, vertical = 20.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
                CamCircle(52.dp, "Share", { CaptureContract.share(context, uris[pager.currentPage], mime) }) { Icon(Icons.Rounded.Share, null, tint = Color.White) }
                CamCircle(52.dp, "Open in gallery", { CaptureContract.view(context, uris[pager.currentPage], mime) }) { Icon(Icons.Rounded.OpenInNew, null, tint = Color.White) }
            }
        }
    }
}

/** One photo: decoded to at most about 2400 px on its long side (the viewer is not for pixel peeping, and a 50 MP picture would not fit), then pinched, panned and double-tapped. */
@Composable
private fun PhotoPage(uri: Uri, active: Boolean, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
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
private fun VideoPage(uri: Uri, onTap: () -> Unit) {
    val context = LocalContext.current
    val frame by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { runCatching { context.contentResolver.loadThumbnail(uri, Size(1080, 1080), null) }.getOrNull() } }
    Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = null, onClick = onTap), contentAlignment = Alignment.Center) {
        frame?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        Box(Modifier.size(72.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Color.Black.copy(alpha = .55f)).clickable { CaptureContract.view(context, uri, "video/mp4") }, contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(40.dp))
        }
    }
}
