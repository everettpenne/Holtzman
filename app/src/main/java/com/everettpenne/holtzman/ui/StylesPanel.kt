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
import androidx.compose.material3.Text
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
import com.everettpenne.glass.GlassSlider
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.save.BurstRender.LookPreset

/**
 * The Photographic Styles panel, after the iPhone's: a row of style cards, each showing the live picture in that style; under it the Palette, a square pad
 * where the dot's place across is Color (cool to warm) and up and down is Tone (darker to lighter), and an Intensity slider for the chosen style.
 *
 * [color] and [tone] are the dot's place, -1..1 (tone up is positive). The pad is the same control as the Warmth and Shadows tabs: the screen keeps one value
 * for each, so moving one moves the other.
 */
@Composable
fun StylesPanel(
    style: LookPreset, onStyle: (LookPreset) -> Unit,
    intensity: Float, onIntensity: (Float) -> Unit,
    color: Float, tone: Float, onPalette: (Float, Float) -> Unit,
    thumb: Bitmap?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(Color.Black.copy(alpha = .66f)).padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LookPreset.entries.forEach { p -> StyleCard(p, p == style, intensity, tone, thumb) { onStyle(p) } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            PalettePad(color, tone, onPalette, Modifier.size(132.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Readout("Tone", tone); Readout("Color", color)
                }
                Text("Intensity  ${Math.round(intensity * 100)}%", style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = Color.White.copy(alpha = .85f), modifier = Modifier.padding(top = 4.dp))
                GlassSlider(intensity, onIntensity, valueRange = 0f..1f, description = "Style intensity")
                Text("Reset", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = Holtzman.accent,
                    modifier = Modifier.clip(CircleShape).clickable { onStyle(LookPreset.STANDARD); onIntensity(1f); onPalette(0f, 0f) }.padding(vertical = 6.dp, horizontal = 2.dp))
            }
        }
    }
}

@Composable
private fun Readout(label: String, v: Float) {
    val n = Math.round(v * 100)
    Text("$label ${if (n > 0) "+$n" else "$n"}", style = IosType.footnote.copy(fontWeight = FontWeight.SemiBold), color = if (n == 0) Color.White.copy(alpha = .6f) else Holtzman.accent)
}

/** One style: the live picture (or a grey card until there is one) with the style's colours on it, and its name under it. */
@Composable
private fun StyleCard(p: LookPreset, selected: Boolean, intensity: Float, tone: Float, thumb: Bitmap?, onClick: () -> Unit) {
    val matrix = lookMatrix(p, if (selected) intensity else 1f, if (selected) tone else 0f)
    Column(Modifier.width(66.dp).clickable(onClick = onClick).semantics { contentDescription = "Style ${p.label}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(width = 66.dp, height = 86.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFF3A3A3C))
            .border(if (selected) 2.5.dp else 0.dp, if (selected) Holtzman.accent else Color.Transparent, RoundedCornerShape(14.dp))) {
            if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
        }
        Text(p.label, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = IosType.caption2.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, lineHeight = 12.sp), color = if (selected) Holtzman.accent else Color.White.copy(alpha = .85f), modifier = Modifier.heightIn(min = 26.dp))
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
