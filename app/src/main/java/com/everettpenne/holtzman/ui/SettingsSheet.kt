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
import com.everettpenne.glass.IosType
import com.everettpenne.glass.LiquidSwitch
import com.everettpenne.holtzman.ml.TpuDenoiser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Holtzman's settings: how photos are made, the dataset tool, and (further down, out of the way) what is only for finding out why a picture came out oddly. */
@Composable
fun SettingsSheet(
    computational: Boolean, onComputational: (Boolean) -> Unit,
    hdr: Boolean, onHdr: (Boolean) -> Unit,
    raw: Boolean, rawAvailable: Boolean, onRaw: (Boolean) -> Unit,
    datasetOn: Boolean, datasetKind: String, datasetSaved: Int, onDataset: () -> Unit,
    onReport: () -> Unit, onCompare: () -> Unit, onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("holtzman", android.content.Context.MODE_PRIVATE) }
    var compare by remember { mutableStateOf(prefs.getBoolean("compare", false)) }
    var keep by remember { mutableStateOf(prefs.getBoolean("keepRaw", false)) }
    var tpu by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { tpu = withContext(Dispatchers.Default) { TpuDenoiser.shared(context) != null } }
    GlassSheet(onDismiss) {
        Column(Modifier.heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text("Settings", style = IosType.title2, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))

            GlassSectionHeader("Photos")
            GlassGroup {
                SwitchRow("Computational photo", "Merges six frames, denoises on the TPU", computational) { onComputational(it) }
                GlassRowDivider()
                SwitchRow("Ultra HDR", "Keeps highlight detail on HDR screens", hdr) { onHdr(it) }
                GlassRowDivider()
                SwitchRow("Save RAW (DNG)", if (rawAvailable || raw) "Also keeps the merged RAW file" else "This lens has no RAW", raw, enabled = rawAvailable || raw) { onRaw(it) }
            }

            GlassSectionHeader("Tools")
            GlassGroup {
                GlassListRow("Dataset capture", subtitle = if (datasetOn) "On  ·  $datasetKind  ·  $datasetSaved saved" else "Save RAW frames for tuning the camera", onClick = onDataset,
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }

            GlassSectionHeader("Advanced")
            GlassGroup {
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
                GlassListRow("Version", trailing = { Value(com.everettpenne.holtzman.BuildConfig.VERSION_NAME) })
                GlassRowDivider()
                GlassListRow("Denoiser", trailing = { Value(when (tpu) { true -> "Learned, on the TPU"; false -> "Classical"; null -> "…" }) })
                GlassRowDivider()
                GlassListRow("Camera", trailing = { Value(android.os.Build.MODEL) })
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    GlassListRow(title, subtitle = subtitle, onClick = if (enabled) ({ onChange(!checked) }) else null, trailing = { LiquidSwitch(checked, { if (enabled) onChange(it) }) })
}

@Composable
private fun Chevron() = Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Value(text: String) = Text(text, style = IosType.body, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
