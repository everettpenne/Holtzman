package com.everettpenne.holtzman.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.everettpenne.glass.GlassCircleButton
import com.everettpenne.glass.frostedGlass
import com.everettpenne.glass.glassSurface
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/*
 * The Liquid Glass experiment (2026-11-02): the camera's controls drawn as the suite's glass (Glass: a blurred, lensed, rim-lit copy of what is behind the
 * control) instead of flat translucent fills. The Glass kit samples a Compose layer, and the viewfinder is a TextureView that no layer can record, so a
 * low-resolution copy of the live picture is read back about ten times a second and drawn into a layer of its own, hidden under the real viewfinder, at the
 * viewfinder's place. The controls sample that. A setting turns it off (the flat look is kept beside it), so the two can be compared.
 */

/** Whether the camera's controls are drawn as glass. */
val LocalLiquid = compositionLocalOf { false }

/** The layer the glass samples, the picture it holds and where in the screen the viewfinder is. */
class PreviewGlass(val backdrop: LayerBackdrop) {
    var rect by mutableStateOf(Rect.Zero)
    var shown by mutableStateOf<ImageBitmap?>(null)
}

@Composable
fun rememberPreviewGlass(view: TextureView?, enabled: Boolean): PreviewGlass {
    val backdrop = rememberLayerBackdrop()
    val glass = remember(backdrop) { PreviewGlass(backdrop) }
    LaunchedEffect(view, enabled) {
        if (!enabled || view == null) return@LaunchedEffect
        val bitmaps = Array(2) { Bitmap.createBitmap(96, 128, Bitmap.Config.ARGB_8888) }
        val images = bitmaps.map { it.asImageBitmap() }
        var next = 0
        while (isActive) {
            if (view.isAvailable && runCatching { view.getBitmap(bitmaps[next]) }.isSuccess) { glass.shown = images[next]; next = 1 - next }
            delay(100)
        }
    }
    return glass
}

/** The recorded layer: black with the live picture at the viewfinder's place. It sits under the real viewfinder, so it is never seen, only sampled. */
@Composable
fun PreviewBackdropLayer(glass: PreviewGlass, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().layerBackdrop(glass.backdrop).drawBehind {
        drawRect(Color.Black)
        val image = glass.shown; val r = glass.rect
        if (image != null && r.width > 1f) drawImage(image, dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()), dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()), filterQuality = FilterQuality.Medium)
    })
}

/** Clear glass for a small control or capsule; the flat translucent fill [flat] without glass. */
@Composable
fun Modifier.liquid(shape: Shape, flat: Color = Color.Black.copy(alpha = .45f)): Modifier =
    if (LocalLiquid.current) frostedGlass(shape, lensHeight = 10f, lensAmount = 20f) else clip(shape).background(flat)

/** Glass for a panel (a card, the sheet, a tip): darker and more frosted than a control, with a soft shadow. */
@Composable
fun Modifier.liquidPanel(shape: Shape, flat: Color): Modifier =
    if (LocalLiquid.current) glassSurface(shape, tint = Color.Black.copy(alpha = .30f), blurRadius = 10f, lensHeight = 20f, lensAmount = 40f, shadowed = true) else clip(shape).background(flat)

/** A round control: a glass button that swells under the finger, or the flat dark disc. */
@Composable
fun CamCircle(size: Dp, description: String, onClick: () -> Unit, lit: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    if (LocalLiquid.current) GlassCircleButton(onClick, size = size, description = description, shadowed = false, content = content)
    else Box(Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = .35f)).background(Color.White.copy(alpha = if (lit) .22f else .13f)).clickable(onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center, content = content)
}
