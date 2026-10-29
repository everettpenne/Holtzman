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
        Box(Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onBack).semantics { contentDescription = "Back" }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.KeyboardArrowLeft, null, tint = Color.White, modifier = Modifier.size(28.dp)) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { center() }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { trailing?.invoke() }
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

/** The controls as the iPhone shows them: a sheet with a handle over a grid of three round buttons across, each with its name in capitals under it. */
@Composable
fun ControlsSheet(items: List<StripItem>, icons: Map<StripItem, ImageVector>, on: Set<StripItem>, changed: Set<StripItem>, onItem: (StripItem) -> Unit, modifier: Modifier = Modifier) {
    CardFrame(modifier) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 2.dp, bottom = 10.dp).size(width = 36.dp, height = 4.dp).background(Color.White.copy(alpha = .35f), CircleShape))
        Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { item ->
                        Column(Modifier.width(96.dp).clickable { onItem(item) }.semantics { contentDescription = item.label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            Box(Modifier.size(62.dp).clip(CircleShape).background(Color.White.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
                                Icon(icons[item] ?: item.icon, null, tint = if (item in on) CameraYellow else Color.White, modifier = Modifier.size(27.dp))
                                if (item in changed && item !in on) Box(Modifier.align(Alignment.TopEnd).padding(11.dp).size(8.dp).background(CameraYellow, CircleShape))
                            }
                            Text(item.label.uppercase(), maxLines = 1, softWrap = false, style = IosType.caption2.copy(letterSpacing = 0.5.sp), color = if (item in on) CameraYellow else Color.White.copy(alpha = .75f))
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.width(96.dp)) }
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
