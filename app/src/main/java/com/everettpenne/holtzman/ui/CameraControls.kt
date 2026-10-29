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

/** The iPhone camera's yellow, for everything that is on or selected. */
val CameraYellow = Color(0xFFFFD60A)

/** What the controls sheet (the six-dot button beside the shutter) offers, in the iPhone's order and then the Pro controls: NIGHT, HDR and RAW are switches, SETTINGS opens the app's settings, the rest open a card of their own. */
enum class StripItem(val label: String, val icon: ImageVector) {
    FLASH("Flash", Icons.Rounded.FlashOn), NIGHT("Night Mode", Icons.Rounded.NightsStay), ASPECT("Aspect", Icons.Rounded.AspectRatio),
    TIMER("Timer", Icons.Rounded.Timer), EXPOSURE("Exposure", Icons.Rounded.Exposure), STYLES("Styles", Icons.Rounded.AutoAwesome),
    HDR("HDR", Icons.Rounded.HdrOn), RAW("RAW", Icons.Rounded.RawOn), SHUTTER("Shutter", Icons.Rounded.ShutterSpeed),
    ISO("ISO", Icons.Rounded.Iso), FOCUS("Focus", Icons.Rounded.CenterFocusStrong), SETTINGS("Settings", Icons.Rounded.Settings)
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

/** A slider on a dark pill with the value in a bubble above it, over the picture's lower edge, the way the Pixel camera app's controls sit above the shutter. */
@Composable
fun ControlSlider(value: Float, range: ClosedFloatingPointRange<Float>, display: String, minIcon: ImageVector, maxIcon: ImageVector, description: String, onChange: (Float) -> Unit, modifier: Modifier = Modifier,
                  /** For the controls that can be left to the camera: whether it is, and what tapping "Auto" does. */
                  isAuto: Boolean? = null, onAuto: (() -> Unit)? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (isAuto != null) Text("Auto", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (isAuto) Color.Black else Color.White,
                modifier = Modifier.clip(CircleShape).background(if (isAuto) CameraYellow else Color.Black.copy(alpha = .55f)).clickable(enabled = !isAuto) { onAuto?.invoke() }.padding(horizontal = 14.dp, vertical = 4.dp))
            Text(display, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (isAuto == true) Color.White else Color.Black,
                modifier = Modifier.clip(CircleShape).background(if (isAuto == true) Color.Black.copy(alpha = .55f) else CameraYellow).padding(horizontal = 14.dp, vertical = 4.dp))
        }
        Row(Modifier.fillMaxWidth().clip(CircleShape).background(Color.Black.copy(alpha = .55f)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(minIcon, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(20.dp))
            GlassSlider(value, onChange, Modifier.weight(1f), valueRange = range, accent = CameraYellow, description = description)
            Icon(maxIcon, null, tint = Color.White.copy(alpha = .8f), modifier = Modifier.size(20.dp))
        }
    }
}

/** Capture and guides: what is not on the camera screen's controls strip. Reached from Settings. */
@Composable
fun PhotoSettingsSheet(
    grid: Boolean, onGrid: (Boolean) -> Unit,
    level: Boolean, onLevel: (Boolean) -> Unit, histogram: Boolean, onHistogram: (Boolean) -> Unit,
    longCapture: Boolean, onLongCapture: (Boolean) -> Unit,
    instant: Boolean, onInstant: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Capture and guides", style = IosType.title2, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            GlassSectionHeader("Capture")
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(!instant, { onInstant(false) }) { Text("Standard", modifier = Modifier.padding(horizontal = 8.dp)) }
                GlassChip(instant, { onInstant(true) }) { Text("Instant", modifier = Modifier.padding(horizontal = 8.dp)) }
            }
            Text("Instant keeps the last frames from the camera, so a photo is the moment you press, with no focusing or capture wait. Autofocus is the camera's own continuous focus.", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            GlassSectionHeader("Low light")
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(!longCapture, { onLongCapture(false) }) { Text("Standard", modifier = Modifier.padding(horizontal = 8.dp)) }
                GlassChip(longCapture, { onLongCapture(true) }) { Text("Longer capture", modifier = Modifier.padding(horizontal = 8.dp)) }
            }
            Text("Longer capture takes up to a few seconds in the dark, when the phone is steady, for less noise.", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            GlassSectionHeader("Guides")
            Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassChip(grid, { onGrid(!grid) }) { Text("Grid", modifier = Modifier.padding(horizontal = 8.dp)) }
                GlassChip(level, { onLevel(!level) }) { Text("Level", modifier = Modifier.padding(horizontal = 8.dp)) }
                GlassChip(histogram, { onHistogram(!histogram) }) { Text("Histogram", modifier = Modifier.padding(horizontal = 8.dp)) }
            }
        }
    }
}
