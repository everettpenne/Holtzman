package com.everettpenne.holtzman.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.everettpenne.glass.LocalGlassBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.shadow.Shadow
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
    /** The same picture reduced to a few colour patches: the soft wash behind the bars, in which no scene can be made out. */
    var wash by mutableStateOf<ImageBitmap?>(null)
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
            if (view.isAvailable && runCatching { view.getBitmap(bitmaps[next]) }.isSuccess) {
                glass.shown = images[next]; next = 1 - next
                // two halvings-and-more with filtering, so each patch is an average of its area, not a sample of it
                val mid = Bitmap.createScaledBitmap(bitmaps[1 - next], 24, 32, true)
                glass.wash = mid.asImageBitmap()
            }
            delay(100)
        }
    }
    return glass
}

/** The recorded layer: black with the live picture at the viewfinder's place. It sits under the real viewfinder, so it is never seen, only sampled. */
@Composable
fun PreviewBackdropLayer(glass: PreviewGlass, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().layerBackdrop(glass.backdrop).drawBehind { drawRect(Color.Black) }) {
        // iOS 26: the area behind the controls is more transparent, so something shows past the picture. The bars around it get the frame as a smooth wash of colour: a
        // 24 x 32 reduction, stretched to the screen and blurred wide, so no scene and no block can be made out (stretched plain, the frame was a recognisable zoomed-in picture
        // and the reduction alone showed its pixels).
        Box(Modifier.fillMaxSize().blur(60.dp, BlurredEdgeTreatment.Unbounded).drawBehind {
            val wash = glass.wash
            if (wash != null) drawImage(wash, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), alpha = .6f, filterQuality = FilterQuality.Medium)
        })
        Box(Modifier.fillMaxSize().drawBehind {
            val image = glass.shown; val r = glass.rect
            if (image != null && r.width > 1f) drawImage(image, dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()), dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()), filterQuality = FilterQuality.Medium)
        })
    }
}

/**
 * The camera's glass without the rim light the kit puts on its edges (and without the colour fringe of its lens): the edge bends the picture and nothing more. Built
 * here on the Backdrop library so the shared kit stays as it is.
 */
@Composable
private fun Modifier.plainGlass(shape: Shape, tint: Color, blurRadius: Float, lensHeight: Float, lensAmount: Float, shadowed: Boolean): Modifier {
    val backdrop = LocalGlassBackdrop.current ?: return this.clip(shape).background(tint)
    return drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = { colorControls(brightness = 0f, contrast = 1f, saturation = 1.5f); blur(blurRadius.dp.toPx()); lens(lensHeight.dp.toPx(), lensAmount.dp.toPx()) },
        highlight = null, shadow = { if (shadowed) Shadow.Default else null }, onDrawSurface = { drawRect(tint) },
    )
}

/** Clear glass for a small control or capsule; the flat translucent fill [flat] without glass. */
@Composable
fun Modifier.liquid(shape: Shape, flat: Color = Color.Black.copy(alpha = .45f)): Modifier =
    if (LocalLiquid.current) plainGlass(shape, Color.Black.copy(alpha = .14f), 2f, 10f, 20f, false) else clip(shape).background(flat)

/** Glass for a panel (a card, the sheet, a tip): darker and more frosted than a control, with a soft shadow. */
@Composable
fun Modifier.liquidPanel(shape: Shape, flat: Color): Modifier =
    if (LocalLiquid.current) plainGlass(shape, Color.Black.copy(alpha = .30f), 10f, 20f, 40f, true) else clip(shape).background(flat)

/** A round control: a glass button that swells under the finger, or the flat dark disc. */
@Composable
fun CamCircle(size: Dp, description: String, onClick: () -> Unit, lit: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val tick = { com.everettpenne.glass.GlassFeel.play(haptic, com.everettpenne.glass.GlassFeel.Cue.TICK); onClick() }
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val swell by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1.12f else 1f, com.everettpenne.glass.GlassFeel.press(), label = "swell")
    if (LocalLiquid.current) Box(Modifier.size(size).graphicsLayer { scaleX = swell; scaleY = swell }.plainGlass(CircleShape, Color.White.copy(alpha = .08f), 2f, size.value / 4f, size.value / 2f, false)
        .clickable(interactionSource = src, indication = null, onClick = tick).semantics { contentDescription = description }, contentAlignment = Alignment.Center, content = content)
    else Box(Modifier.size(size).clip(CircleShape).background(Color.Black.copy(alpha = .35f)).background(Color.White.copy(alpha = if (lit) .22f else .13f)).clickable(onClick = tick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center, content = content)
}

/**
 * Glass under text (the readout, the status pill): the more frosted kind, darker and blurrier than a plain control's clear glass, because Apple's guidance is to
 * prefer the regular material where legibility is uncertain (text over a bright or busy picture, direct sun).
 */
@Composable
fun Modifier.liquidText(shape: Shape, flat: Color = Color.Black.copy(alpha = .45f)): Modifier =
    if (LocalLiquid.current) plainGlass(shape, Color.Black.copy(alpha = .34f), 8f, 8f, 16f, false) else clip(shape).background(flat)

/**
 * Apple's Reduce Transparency has no Android twin; the nearest settings of a person who wants a calmer, more legible screen are high-contrast text and animations
 * switched off, so either of them returns the camera to its flat look.
 */
fun reduceTransparency(context: android.content.Context): Boolean = runCatching {
    val cr = context.contentResolver
    android.provider.Settings.Secure.getInt(cr, "high_text_contrast_enabled", 0) == 1 || android.provider.Settings.Global.getFloat(cr, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}.getOrDefault(false)

/** A panel opening out of the button that opened it: it grows from [origin] with the kit's opening spring and fades in, as iOS 26's sheets morph out of their buttons. */
@Composable
fun Modifier.glassEnter(origin: androidx.compose.ui.graphics.TransformOrigin): Modifier {
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, com.everettpenne.glass.GlassFeel.open()) }
    return this.graphicsLayer { val v = t.value; scaleX = .86f + .14f * v; scaleY = .86f + .14f * v; alpha = v.coerceIn(0f, 1f); transformOrigin = origin }
}
