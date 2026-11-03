package com.everettpenne.holtzman.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everettpenne.glass.IosType

/** The dark rounded card the camera's controls open in, over the lower part of the picture. */
@Composable
fun CardFrame(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(30.dp)).background(Color(0xFC1C1C1E)).padding(horizontal = 12.dp, vertical = 10.dp), content = content)
}

/** A card's top line: a way back on the left, a title (or anything) in the middle, something optional on the right. */
@Composable
fun CardHeader(onBack: () -> Unit, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, center: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        // the two sides are the same width, so the title stays centred whatever the right side holds (an AUTO button needs more than a round 44 dp)
        Box(Modifier.width(64.dp).height(44.dp), contentAlignment = Alignment.CenterStart) {
            Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.KeyboardArrowLeft, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { center() }
        Box(Modifier.width(64.dp).height(44.dp), contentAlignment = Alignment.CenterEnd) { trailing?.invoke() }
    }
}

@Composable
fun CardTitle(text: String) = Text(text.uppercase(), maxLines = 1, style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp), color = Color.White.copy(alpha = .9f))

/** The choices of a small control (flash, aspect, timer): plain words, the chosen one yellow. */
@Composable
fun OptionsRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, l ->
            Text(l, maxLines = 1, softWrap = false, style = IosType.body.copy(fontWeight = FontWeight.SemiBold), color = if (i == selected) CameraYellow else Color.White,
                modifier = Modifier.clip(CircleShape).clickable { onSelect(i) }.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

/**
 * The iPhone's slider of tick marks: the ticks up to the value are yellow, the rest grey; touch or drag anywhere on it. [min] and [max] are the marks at
 * its ends (a minus and a plus by default).
 */
@Composable
fun TickSlider(value: Float, onChange: (Float) -> Unit, modifier: Modifier = Modifier, min: String = "−", max: String = "+") {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(min, style = IosType.body, color = Color.White.copy(alpha = .8f))
        Canvas(Modifier.weight(1f).height(34.dp).semantics { contentDescription = "Amount" }
            .pointerInput(Unit) { detectTapGestures { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) { detectDragGestures(onDragStart = { p -> onChange((p.x / size.width).coerceIn(0f, 1f)) }) { change, _ -> change.consume(); onChange((change.position.x / size.width).coerceIn(0f, 1f)) } }) {
            val n = 28
            for (i in 0 until n) {
                val x = (i + 0.5f) / n * size.width
                val on = (i + 0.5f) / n <= value + 0.5f / n
                val tall = i % 4 == 0
                val h = if (tall) size.height * 0.62f else size.height * 0.42f
                drawLine(if (on) CameraYellow else Color.White.copy(alpha = .38f), Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), strokeWidth = 3.dp.toPx())
            }
        }
        Text(max, style = IosType.body, color = Color.White.copy(alpha = .8f))
    }
}

/** The six dots of the button that opens the controls, in the iPhone's pattern: two rows of three. */
@Composable
fun SixDots(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(24.dp)) {
        for (r in 0..1) for (c in 0..2) drawCircle(color, 2.2.dp.toPx(), Offset(size.width * (0.2f + 0.3f * c), size.height * (0.38f + 0.24f * r)))
    }
}

/** The dots-in-a-grid glyph of Photographic Styles: a five-by-five lattice of small dots with a ringed one in the middle. */
@Composable
fun DotGridGlyph(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(28.dp)) {
        val n = 5; val step = size.width / (n + 0.4f)
        for (i in 0 until n) for (j in 0 until n) {
            val c = Offset(step * (i + 0.7f), step * (j + 0.7f))
            if (i == 2 && j == 2) { drawCircle(color, 2.6.dp.toPx(), c); drawCircle(color, 5.dp.toPx(), c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx())) }
            else drawCircle(color.copy(alpha = .8f), 1.1.dp.toPx(), c)
        }
    }
}

/** The controls sheet's sections, in order: what a person reaches for most first. Items a camera does not offer are left out, and a section with none is not shown. */
private val SHEET_GROUPS = listOf(
    "Shoot" to listOf(StripItem.FLASH, StripItem.NIGHT, StripItem.ASPECT, StripItem.TIMER),
    "Look" to listOf(StripItem.EXPOSURE, StripItem.STYLES, StripItem.HDR),
    "Manual" to listOf(StripItem.SHUTTER, StripItem.ISO, StripItem.FOCUS),
    "More" to listOf(StripItem.RAW, StripItem.SETTINGS),
)

/**
 * The controls as the iPhone shows them: a sheet the width of the screen, rounded only at the top and running to the bottom edge, with a handle over rows of
 * round buttons, four across, each with its name in capitals under it, in sections ([SHEET_GROUPS]). [tile] draws what is inside a button.
 * Opaque on purpose: it was 92% opaque and stood half over the picture and half over the black bar below it, so it showed as two shades with the zoom numbers
 * ghosting through (2026-11-02). Its rows are not cut off: the old 380 dp window clipped the last row's names.
 */
@Composable
fun ControlsSheet(items: List<StripItem>, on: Set<StripItem>, changed: Set<StripItem>, onItem: (StripItem) -> Unit, modifier: Modifier = Modifier, tile: @Composable (StripItem, Boolean) -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(Color(0xFF1C1C1E)).navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 16.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp).size(width = 36.dp, height = 4.dp).background(Color.White.copy(alpha = .35f), CircleShape))
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SHEET_GROUPS.forEach { (title, members) ->
                val shown = members.filter { it in items }
                if (shown.isEmpty()) return@forEach
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title.uppercase(), style = IosType.caption2.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp), color = Color.White.copy(alpha = .45f), modifier = Modifier.padding(start = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        shown.forEach { item ->
                            Column(Modifier.width(78.dp).clickable { onItem(item) }.semantics { contentDescription = item.label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(60.dp).clip(CircleShape).background(Color.White.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                                    tile(item, item in on)
                                    if (item in changed && item !in on) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(8.dp).background(CameraYellow, CircleShape))
                                }
                                Text(item.label.uppercase(), maxLines = 1, softWrap = false, style = IosType.caption2.copy(letterSpacing = 0.4.sp), color = if (item in on) CameraYellow else Color.White.copy(alpha = .75f))
                            }
                        }
                        repeat(4 - shown.size) { Spacer(Modifier.width(78.dp)) }
                    }
                }
            }
        }
    }
}

/** PHOTO and VIDEO in a capsule under the shutter, the iPhone's way: the chosen one is lit inside it, in yellow; touching a name or swiping across changes mode. */
@Composable
fun ModeCapsule(videoMode: Boolean, enabled: Boolean, onMode: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf(0f) }
    Row(modifier.clip(CircleShape).background(Color.Black.copy(alpha = .35f)).background(Color.White.copy(alpha = .12f)).padding(3.dp).pointerInput(videoMode, enabled) {
        detectDragGestures(onDragEnd = { if (enabled) { if (drag < -40f && !videoMode) onMode(true) else if (drag > 40f && videoMode) onMode(false) }; drag = 0f }) { _, d -> drag += d.x }
    }, verticalAlignment = Alignment.CenterVertically) {
        listOf(true to "VIDEO", false to "PHOTO").forEach { (v, label) ->
            val sel = videoMode == v
            Text(label, maxLines = 1, softWrap = false, style = IosType.footnote.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = if (sel) CameraYellow else Color.White.copy(alpha = .9f),
                modifier = Modifier.clip(CircleShape).background(if (sel) Color.White.copy(alpha = .2f) else Color.Transparent).clickable(enabled = enabled) { onMode(v) }.padding(horizontal = 18.dp, vertical = 9.dp))
        }
    }
}
