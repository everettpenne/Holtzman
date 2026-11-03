package com.everettpenne.holtzman.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.GlassGroup
import com.everettpenne.glass.GlassListRow
import com.everettpenne.glass.GlassRowDivider
import com.everettpenne.glass.GlassSectionHeader
import com.everettpenne.glass.GlassSheet
import com.everettpenne.glass.GlassSlider
import com.everettpenne.holtzman.save.BurstRender
import com.everettpenne.glass.IosType
import com.everettpenne.glass.LiquidSwitch
import com.everettpenne.holtzman.ml.TpuDenoiser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Holtzman's settings: how photos are made, the dataset tool, and (further down, out of the way) what is only for finding out why a picture came out oddly. */
@Composable
fun SettingsSheet(
    liquid: Boolean = false, onLiquid: (Boolean) -> Unit = {},
    computational: Boolean, onComputational: (Boolean) -> Unit,
    hdr: Boolean, onHdr: (Boolean) -> Unit,
    raw: Boolean, onRaw: (Boolean) -> Unit,
    texture: Float, onTexture: (Float) -> Unit, grain: Float, onGrain: (Float) -> Unit,
    datasetOn: Boolean, datasetKind: String, datasetSaved: Int, onDataset: () -> Unit, onCaptureSettings: () -> Unit, styleName: String, onStyles: () -> Unit,
    onReport: () -> Unit, onCompare: () -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("holtzman", android.content.Context.MODE_PRIVATE) }
    var compare by remember { mutableStateOf(prefs.getBoolean("compare", false)) }
    var keep by remember { mutableStateOf(prefs.getBoolean("keepRaw", false)) }
    var tpu by remember { mutableStateOf<Boolean?>(null) }
    // the tuning tools and switches for finding out why a picture came out oddly are hidden until the version is tapped seven times (and stay shown, until tapped seven times again)
    var developer by remember { mutableStateOf(prefs.getBoolean("developer", false)) }
    var taps by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { tpu = withContext(Dispatchers.Default) { TpuDenoiser.shared(context) != null } }
    GlassSheet(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text("Settings", style = IosType.title2, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))

            GlassSectionHeader("Photos")
            GlassGroup {
                SwitchRow("Computational photo", "Merges six frames into one photo", computational) { onComputational(it) }
                GlassRowDivider()
                SwitchRow("Ultra HDR", "Keeps highlight detail on HDR screens", hdr) { onHdr(it) }
                GlassRowDivider()
                // the camera only opens a RAW stream while one of the three switches wants it, so "no RAW stream open" is not "no RAW on this lens": always allow the switch
                SwitchRow("Save RAW (DNG)", "Also keeps the merged RAW file", raw) { onRaw(it) }
            }

            GlassSectionHeader("Look")
            GlassGroup {
                SliderRow("Texture", "Softer to crisper: fine detail and local contrast", signed(texture), texture, -1f..1f, onTexture)
                GlassRowDivider()
                SliderRow("Grain", "Film grain, from none to heavy", "${(grain * 100).toInt()}%", grain, 0f..1f, onGrain)
                GlassRowDivider()
                val tuned = texture != 0f || kotlin.math.abs(grain - BurstRender.LookTuning.DEFAULT_GRAIN) > 0.005f
                GlassListRow("Reset to the standard look", subtitle = "Applies to computational photos", onClick = if (tuned) ({ onTexture(0f); onGrain(BurstRender.LookTuning.DEFAULT_GRAIN) }) else null,
                    titleColor = if (tuned) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }

            GlassSectionHeader("Camera")
            GlassGroup {
                SwitchRow("iOS 26 style controls", "Round solid cards that swell under the finger", liquid) { onLiquid(it) }
                GlassRowDivider()
                GlassListRow("Photographic Styles", subtitle = "Style, palette and texture", onClick = onStyles,
                    trailing = { Value(styleName); Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
                GlassRowDivider()
                GlassListRow("Capture and guides", subtitle = "Instant capture, low light, grid, level, histogram", onClick = onCaptureSettings,
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }

            if (developer) GlassSectionHeader("Developer: tools")
            if (developer) GlassGroup {
                GlassListRow("Dataset capture", subtitle = if (datasetOn) "On  ·  $datasetKind  ·  $datasetSaved saved" else "Save RAW frames for tuning the camera", onClick = onDataset,
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }

            if (developer) GlassSectionHeader("Developer: advanced")
            if (developer) GlassGroup {
                SwitchRow("Keep the raw burst", "With every photo; about 140 MB each", keep) { keep = it; prefs.edit().putBoolean("keepRaw", it).apply() }
                GlassRowDivider()
                SwitchRow("Also save the unmerged frame", "To judge what the merge did", compare) { compare = it; prefs.edit().putBoolean("compare", it).apply() }
                GlassRowDivider()
                GlassListRow("Take a comparison pair", subtitle = "A default photo and a full-quality one", onClick = onCompare, trailing = { Chevron() })
                GlassRowDivider()
                GlassListRow("Camera report", subtitle = "What this phone's camera offers an app", onClick = onReport, trailing = { Chevron() })
            }

            GlassSectionHeader("About")
            GlassGroup {
                GlassListRow("Version", subtitle = if (developer) "Developer options shown" else null,
                    onClick = { taps++; if (taps >= 7) { taps = 0; developer = !developer; prefs.edit().putBoolean("developer", developer).apply() } },
                    trailing = { Value(com.everettpenne.holtzman.BuildConfig.VERSION_NAME) })
                GlassRowDivider()
                // the learned denoiser left colour blobs on clean scenes and has been off since 2026-11-02 (only the debug switch "tpu" runs it), so "available" is all that can be said
                GlassListRow("Learned denoiser", trailing = { Value(when (tpu) { true -> "Available, off"; false -> "Not available"; null -> "…" }) })
                GlassRowDivider()
                GlassListRow("Camera", trailing = { Value(android.os.Build.MODEL) })
                GlassRowDivider()
                GlassListRow("Subject recognition", subtitle = "DeepLab v3, Apache-2.0", trailing = { Value("On-device") })
                GlassRowDivider()
                GlassListRow("Depth", subtitle = "MiDaS v2.1 small, MIT", trailing = { Value("On-device") })
                GlassRowDivider()
                GlassListRow("Network", subtitle = "No network permission", trailing = { Value("None") })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    GlassListRow(title, subtitle = subtitle, onClick = if (enabled) ({ onChange(!checked) }) else null, trailing = { LiquidSwitch(checked, { if (enabled) onChange(it) }) })
}

private fun signed(v: Float): String { val p = (v * 100).toInt(); return if (p > 0) "+$p%" else "$p%" }

/** A title with its value at the right and a slider under it. */
@Composable
private fun SliderRow(title: String, subtitle: String, valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = IosType.body, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
            Value(valueText)
        }
        Text(subtitle, style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GlassSlider(value, onChange, valueRange = range, description = title)
    }
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Value(text: String) = Text(text, style = IosType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
