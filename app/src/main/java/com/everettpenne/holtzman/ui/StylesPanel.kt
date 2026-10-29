package com.everettpenne.holtzman.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.save.BurstRender.LookPreset
import com.everettpenne.holtzman.save.BurstRender.TextureStyle

/**
 * The Photographic Styles card, after the iPhone's two steps. First a row of style cards (each the live picture in that style) under the name of the chosen
 * style, and CUSTOMISE. Then PALETTE (the square pad: across is Color, cool to warm; up and down is Tone, darker to lighter; and a tick slider for how much
 * of the style) or TEXTURE (Standard, Soft Skin, Glow, Film and Grain, and a tick slider for how much, applied when the photo is made).
 *
 * [color] and [tone] are the dot's place, -1..1 (tone up is positive). The pad is the same control as the Warmth and Shadows values the screen keeps.
 */
@Composable
fun StylesCard(
    style: LookPreset, onStyle: (LookPreset) -> Unit,
    intensity: Float, onIntensity: (Float) -> Unit,
    color: Float, tone: Float, onPalette: (Float, Float) -> Unit,
    textureStyle: TextureStyle, onTextureStyle: (TextureStyle) -> Unit, textureAmount: Float, onTextureAmount: (Float) -> Unit,
    thumb: Bitmap?, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var customise by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(0) }
    CardFrame(modifier) {
        if (!customise) {
            CardHeader(onBack, center = { CardTitle(style.label + if (textureStyle != TextureStyle.STANDARD) " + ${textureStyle.label}" else "") })
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LookPreset.entries.forEach { p -> StyleCard(p, p == style, intensity, tone, thumb) { onStyle(p) } }
            }
            Text("CUSTOMISE", style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp), color = Color.White.copy(alpha = .9f),
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).clickable { customise = true; tab = 0 }.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            CardHeader({ customise = false }, trailing = if (tab == 1) ({
                Icon(Icons.Rounded.RestartAlt, "Reset texture", tint = Color.White, modifier = Modifier.size(24.dp).clickable { onTextureStyle(TextureStyle.STANDARD); onTextureAmount(0.5f) })
            }) else null, center = {
                Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .12f)).padding(3.dp)) {
                    listOf("PALETTE", "TEXTURE").forEachIndexed { i, l ->
                        Text(l, style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = if (i == tab) CameraYellow else Color.White.copy(alpha = .85f),
                            modifier = Modifier.clip(CircleShape).background(if (i == tab) Color.White.copy(alpha = .2f) else Color.Transparent).clickable { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                }
            })
            if (tab == 0) {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PalettePad(color, tone, onPalette, Modifier.size(124.dp))
                    Text("Tone ${signedPercent(tone)}   Color ${signedPercent(color)}", style = IosType.caption1, color = Color.White.copy(alpha = .6f))
                    TickSlider(intensity, onIntensity, Modifier.padding(horizontal = 14.dp))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                        TextureStyle.entries.forEach { t ->
                            if (t == TextureStyle.GRAIN) Box(Modifier.padding(top = 10.dp).size(width = 1.dp, height = 36.dp).background(Color.White.copy(alpha = .25f)))
                            TextureButton(t, t == textureStyle) { onTextureStyle(t) }
                        }
                    }
                    TickSlider(textureAmount, onTextureAmount, Modifier.padding(horizontal = 14.dp))
                    Text("Texture is applied after taking a photo.", style = IosType.caption1, color = Color.White.copy(alpha = .5f), modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 4.dp))
                }
            }
        }
    }
}

private fun signedPercent(v: Float): String { val n = Math.round(v * 100); return if (n > 0) "+$n" else "$n" }

/** One texture: a round button with its name under it; the chosen one is a white disc with a dark icon. */
@Composable
private fun TextureButton(t: TextureStyle, selected: Boolean, onClick: () -> Unit) {
    val icon = when (t) { TextureStyle.STANDARD -> Icons.Rounded.NotInterested; TextureStyle.SOFT_SKIN -> Icons.Rounded.Person; TextureStyle.GLOW -> Icons.Rounded.BlurOn; TextureStyle.FILM -> Icons.Rounded.Movie; TextureStyle.GRAIN -> Icons.Rounded.Grain }
    Column(Modifier.width(62.dp).clickable(onClick = onClick).semantics { contentDescription = "Texture ${t.label}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(if (selected) Color.White else Color.White.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
        Text(t.label.uppercase(), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = IosType.caption2.copy(fontSize = 9.sp, lineHeight = 11.sp), color = Color.White.copy(alpha = if (selected) 1f else .7f), modifier = Modifier.heightIn(min = 22.dp))
    }
}

/** One style: the live picture (or a grey card until there is one) with the style's colours on it, and its name under it. */
@Composable
private fun StyleCard(p: LookPreset, selected: Boolean, intensity: Float, tone: Float, thumb: Bitmap?, onClick: () -> Unit) {
    val matrix = lookMatrix(p, if (selected) intensity else 1f, if (selected) tone else 0f)
    Column(Modifier.width(66.dp).clickable(onClick = onClick).semantics { contentDescription = "Style ${p.label}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(width = 66.dp, height = 86.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF3A3A3C))
            .border(if (selected) 2.5.dp else 0.dp, if (selected) CameraYellow else Color.Transparent, RoundedCornerShape(14.dp))) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
        }
        Text(p.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = IosType.caption2.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 12.sp), color = if (selected) CameraYellow else Color.White.copy(alpha = .85f), modifier = Modifier.heightIn(min = 26.dp))
    }
}

/**
 * The Palette: cool on the left to warm on the right, lighter at the top to darker at the bottom, a faint grid, and a white dot you drag (or tap to move
 * it to). Double-tap returns it to the middle.
 */
@Composable
fun PalettePad(color: Float, tone: Float, onChange: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).semantics { contentDescription = "Palette: color and tone" }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onChange(0f, 0f) }, onTap = { p -> onChange((p.x / size.width * 2f - 1f).coerceIn(-1f, 1f), -(p.y / size.height * 2f - 1f).coerceIn(-1f, 1f)) }) }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { p -> onChange((p.x / size.width * 2f - 1f).coerceIn(-1f, 1f), -(p.y / size.height * 2f - 1f).coerceIn(-1f, 1f)) }) { change, _ ->
            change.consume(); val p = change.position; onChange((p.x / size.width * 2f - 1f).coerceIn(-1f, 1f), -(p.y / size.height * 2f - 1f).coerceIn(-1f, 1f)) } }) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF4E86E8), Color(0xFF8E9399), Color(0xFFF2AE4A))))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .5f), Color.Transparent, Color.Black.copy(alpha = .5f))))
            val line = Color.White.copy(alpha = .22f)
            for (k in 1..3) {
                drawLine(line, Offset(size.width * k / 4f, 0f), Offset(size.width * k / 4f, size.height), 1.5f)
                drawLine(line, Offset(0f, size.height * k / 4f), Offset(size.width, size.height * k / 4f), 1.5f)
            }
            val c = Offset((color + 1f) / 2f * size.width, (1f - tone) / 2f * size.height)
            drawCircle(Color.Black.copy(alpha = .25f), 15.dp.toPx(), c)
            drawCircle(Color.White, 12.dp.toPx(), c)
            drawCircle(Color.Black.copy(alpha = .35f), 12.dp.toPx(), c, style = Stroke(1.5f))
        }
        Text("Warm", style = IosType.caption2, color = Color.White.copy(alpha = .8f), modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp))
        Text("Cool", style = IosType.caption2, color = Color.White.copy(alpha = .8f), modifier = Modifier.align(Alignment.CenterStart).padding(start = 6.dp))
        Text("Lighter", style = IosType.caption2, color = Color.White.copy(alpha = .8f), modifier = Modifier.align(Alignment.TopCenter).padding(top = 4.dp))
        Text("Darker", style = IosType.caption2, color = Color.White.copy(alpha = .8f), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp))
    }
}
