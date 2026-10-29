package com.everettpenne.holtzman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.GlassChip
import com.everettpenne.glass.GlassSectionHeader
import com.everettpenne.glass.GlassSheet
import com.everettpenne.glass.GlassSlider
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.camera.FlashMode
import com.everettpenne.holtzman.camera.TimerMode
import com.everettpenne.holtzman.save.BurstRender.LookPreset

/** The crops on offer, as the long side over the short side of the picture (the sensor's 4:3 is 1.333): the long side is cut for the narrower ones, the short side for 16:9. */
enum class PhotoRatio(val label: String, val longOverShort: Float) {
    FULL("Full (4:3)", 4f / 3f), SQUARE("1:1", 1f), FOUR_FIVE("4:5", 1.25f), WIDE("16:9", 16f / 9f);
    val isFull: Boolean get() = this == FULL
}

/** The tabs of the controls panel, opened by the button at the bottom right: one slider (or the looks) each. */
enum class ControlTab(val label: String, val icon: ImageVector) {
    LOOK("Look", Icons.Rounded.AutoAwesome), EXPOSURE("Exposure", Icons.Rounded.Exposure), SHADOWS("Shadows", Icons.Rounded.Contrast), WHITE_BALANCE("Warmth", Icons.Rounded.WbIncandescent),
    SHUTTER("Shutter", Icons.Rounded.ShutterSpeed), ISO("ISO", Icons.Rounded.Iso), FOCUS("Focus", Icons.Rounded.CenterFocusStrong)
}

private val ISO_STOPS = intArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200)
private val SHUTTER_DENOMINATORS = intArrayOf(4000, 3200, 2500, 2000, 1600, 1250, 1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40, 30, 25, 20, 15, 13, 10, 8, 6, 5, 4)

/** Slider position (0..1) to an ISO from the usual third-of-a-stop list, 50 to 3200 (only those the sensor offers), and the position of an ISO on that scale. */
fun isoFromT(t: Float, range: IntRange): Int {
    val usable = ISO_STOPS.filter { it in range }.ifEmpty { listOf(range.first) }
    return usable[Math.round(t.coerceIn(0f, 1f) * (usable.size - 1))]
}

/** Slider position (0..1) to a shutter time in ns from the usual list, 1/4000 s at 0 to 1/4 s at 1 (only those the sensor offers). */
fun shutterNsFromT(t: Float, range: LongRange): Long {
    val usable = SHUTTER_DENOMINATORS.map { 1_000_000_000L / it }.filter { it in range }.ifEmpty { listOf(range.first) }
    return usable[Math.round(t.coerceIn(0f, 1f) * (usable.size - 1))]
}

/** A shutter time as photographers write it: 1/250, or 0.3 s from a third of a second up. */
fun shutterLabel(ns: Long): String = if (ns >= 300_000_000L) "%.1f s".format(java.util.Locale.ROOT, ns / 1e9) else "1/${Math.round(1e9 / ns)}"

/** Focus distance from the lens position in diopters. */
fun focusLabel(diopters: Float): String = if (diopters < 0.12f) "\u221E" else (1f / diopters).let { m -> if (m >= 1f) "%.1f m".format(java.util.Locale.ROOT, m) else "${Math.round(m * 100)} cm" }

/** The row of tabs that replaces the PHOTO / VIDEO area while the controls panel is open; a dot marks a tab whose control is off its default. */
@Composable
fun ControlTabs(selected: ControlTab, changed: Set<ControlTab>, tabs: List<ControlTab>, onSelect: (ControlTab) -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        TabButton(Icons.Rounded.RestartAlt, "Reset All", on = false, dot = false, onClick = onReset)
        Box(Modifier.padding(top = 8.dp).size(width = 1.dp, height = 40.dp).background(Color.White.copy(alpha = .25f)))
        tabs.forEach { t -> TabButton(t.icon, t.label, on = t == selected, dot = t in changed) { onSelect(t) } }
    }
}

@Composable
private fun TabButton(icon: ImageVector, label: String, on: Boolean, dot: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(62.dp).clickable(onClick = onClick).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(18.dp)).background(if (on) Holtzman.accent else Color.White.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(24.dp))
            if (dot && !on) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(7.dp).background(Holtzman.accent, CircleShape))
        }
        Text(label, maxLines = 1, softWrap = false, style = IosType.caption2, color = if (on) Holtzman.accent else Color.White.copy(alpha = .8f))
    }
}

/** A slider on a dark pill with the value in a bubble above it, over the picture's lower edge, the way the Pixel camera app's controls sit above the shutter. */
@Composable
fun ControlSlider(value: Float, range: ClosedFloatingPointRange<Float>, display: String, minIcon: ImageVector, maxIcon: ImageVector, description: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
                  /** For the controls that can be left to the camera: whether it is, and what tapping "Auto" does. */
                  isAuto: Boolean? = null, onAuto: (() -> Unit)? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isAuto != null) Text("Auto", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (isAuto) Color.Black else Color.White,
                modifier = Modifier.clip(CircleShape).background(if (isAuto) Holtzman.accent else Color.Black.copy(alpha = .55f)).clickable(enabled = !isAuto) { onAuto?.invoke() }.padding(horizontal = 14.dp, vertical = 4.dp))
            Text(display, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (isAuto == true) Color.White else Color.Black,
                modifier = Modifier.clip(CircleShape).background(if (isAuto == true) Color.Black.copy(alpha = .55f) else Holtzman.accent).padding(horizontal = 14.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(minIcon, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(20.dp))
            GlassSlider(value, onChange, Modifier.weight(1f), valueRange = range, description = description)
            Icon(maxIcon, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(20.dp))
        }
    }
}

/** The looks, in a scrolling row of chips on the same dark pill. */
@Composable
fun LookChips(selected: LookPreset, onSelect: (LookPreset) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().clip(CircleShape).background(Color.Black.copy(alpha = .55f)).horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        LookPreset.entries.forEach { p -> GlassChip(p == selected, { onSelect(p) }) { Text(p.label, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 6.dp)) } }
    }
}

/** The round buttons under the shutter: photo settings on the left, the controls panel on the right. [on] lights the button (panel open); [dot] says a control is off its default. */
@Composable
fun BottomButton(icon: ImageVector, description: String, on: Boolean, dot: Boolean = false, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clip(CircleShape).background(if (on) Holtzman.accent else Color.White.copy(alpha = .13f)).clickable(onClick = onClick).semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = if (on) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        if (dot && !on) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(8.dp).background(Holtzman.accent, CircleShape))
    }
}

/** The Pixel camera app's "Photo settings": the crop, the timer, the flash and the grid, which used to sit in the top bar. */
@Composable
fun PhotoSettingsSheet(
    ratio: PhotoRatio, onRatio: (PhotoRatio) -> Unit, ratioApplies: Boolean,
    timer: TimerMode, onTimer: (TimerMode) -> Unit,
    flash: FlashMode, onFlash: (FlashMode) -> Unit, flashAvailable: Boolean,
    grid: Boolean, onGrid: (Boolean) -> Unit,
    level: Boolean, onLevel: (Boolean) -> Unit, histogram: Boolean, onHistogram: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Photo settings", style = IosType.title2, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            GlassSectionHeader("Ratio")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoRatio.entries.forEach { r -> GlassChip(r == ratio, { onRatio(r) }) { Text(r.label, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp)) } }
            }
            if (!ratioApplies) Text("The crop applies to computational photos.", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            GlassSectionHeader("Timer")
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TimerMode.entries.forEach { t -> GlassChip(t == timer, { onTimer(t) }) { Text(t.label, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp)) } }
            }
            if (flashAvailable) {
                GlassSectionHeader("Flash")
                Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlashMode.entries.forEach { f -> GlassChip(f == flash, { onFlash(f) }) { Text(f.label, maxLines = 1, softWrap = false, modifier = Modifier.padding(horizontal = 8.dp)) } }
                }
            }
            GlassSectionHeader("Guides")
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(!grid, { onGrid(false) }) { Text("No grid", modifier = Modifier.padding(horizontal = 8.dp)) }
                GlassChip(grid, { onGrid(true) }) { Text("Thirds grid", modifier = Modifier.padding(horizontal = 8.dp)) }
            }
            Row(Modifier.padding(horizontal = 4.dp).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(level, { onLevel(!level) }) { Text("Level", modifier = Modifier.padding(horizontal = 8.dp)) }
                GlassChip(histogram, { onHistogram(!histogram) }) { Text("Histogram", modifier = Modifier.padding(horizontal = 8.dp)) }
            }
        }
    }
}
