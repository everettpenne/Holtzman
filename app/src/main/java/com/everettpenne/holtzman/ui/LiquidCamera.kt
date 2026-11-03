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
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

/*
 * The iOS 26 look of the camera's controls (the "Liquid Glass" experiment of 2026-11-02, which became solid cards on 2026-11-03): round buttons that swell under the finger,
 * rounded cards and a sheet that opens out of its button, and a darker, rounder style than the flat one. The controls were first drawn as real glass (a blurred, lensed copy of
 * the viewfinder behind them). A TextureView cannot be recorded into a layer, so that needed a small copy of the live picture read back several times a second; the glass
 * built from it was blotchy (a 72 x 96 picture stretched and lensed) and jittery (it updated in steps), and it cost GPU and CPU on every frame. A neural accelerator does not help:
 * it runs networks, not the render effects glass is made of. So the glass is gone and the cards are solid, which is also what Apple's Reduce Transparency shows.
 */

/** Whether the camera's controls are drawn as glass. */
val LocalLiquid = compositionLocalOf { false }

/** A solid card: [color] clipped to [shape] (nearly opaque, so it reads over any picture and costs nothing to draw). */
private fun Modifier.solidCard(shape: Shape, color: Color): Modifier = this.clip(shape).background(color)

/** A solid card for a small control or capsule; the flat translucent fill [flat] in the plain look. */
@Composable
fun Modifier.liquid(shape: Shape, flat: Color = Color.Black.copy(alpha = .45f)): Modifier =
    if (LocalLiquid.current) solidCard(shape, Color(0xE62C2C2E)) else clip(shape).background(flat)

/** A solid card for a panel (a card, the sheet, a tip), darker than a control. */
@Composable
fun Modifier.liquidPanel(shape: Shape, flat: Color): Modifier =
    if (LocalLiquid.current) solidCard(shape, Color(0xF51C1C1E)) else clip(shape).background(flat)

/** A round control: a glass button that swells under the finger, or the flat dark disc. */
@Composable
fun CamCircle(size: Dp, description: String, onClick: () -> Unit, lit: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val tick = { com.everettpenne.glass.GlassFeel.play(haptic, com.everettpenne.glass.GlassFeel.Cue.TICK); onClick() }
    val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val swell by androidx.compose.animation.core.animateFloatAsState(if (pressed) 1.12f else 1f, com.everettpenne.glass.GlassFeel.press(), label = "swell")
    if (LocalLiquid.current) Box(Modifier.size(size).graphicsLayer { scaleX = swell; scaleY = swell }.solidCard(CircleShape, Color(0xE62C2C2E))
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
    if (LocalLiquid.current) solidCard(shape, Color(0xEB1C1C1E)) else clip(shape).background(flat)

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
