package com.everettpenne.holtzman.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.GlassButton
import com.everettpenne.glass.GlassChip
import com.everettpenne.glass.GlassGroup
import com.everettpenne.glass.GlassListRow
import com.everettpenne.glass.GlassRowDivider
import com.everettpenne.glass.GlassSectionHeader
import com.everettpenne.glass.GlassSheet
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.save.DatasetKind
import com.everettpenne.holtzman.save.DatasetTags

/**
 * Dataset capture: pick what to capture, tag the scene, and start. While it is on, the shutter saves RAW frames and their metadata instead of a photo,
 * into Documents/Holtzman/Dataset, with a preview picture beside them so the Files app shows what each scene is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatasetSheet(
    active: Boolean, kind: DatasetKind, onKind: (DatasetKind) -> Unit,
    tags: Set<String>, onTag: (String) -> Unit,
    note: String, onNote: (String) -> Unit, saved: Int,
    pixelFirst: Boolean, onPixelFirst: (Boolean) -> Unit, pixelAvailable: Boolean,
    onStart: () -> Unit, onStop: () -> Unit, onCalibrate: () -> Unit, onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss) {
        Column(Modifier.heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 12.dp)) {
            Text("Dataset capture", style = IosType.title2, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
            Text("Saves unprocessed RAW frames and their metadata, with a preview, to tune the camera. They go to Documents/Holtzman/Dataset. With the Pixel pairing on, each shot first opens the Pixel Camera app for one photo (take it, tap the tick), then runs the series.",
                style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))

            GlassSectionHeader("Pair with the Pixel camera")
            GlassGroup {
                GlassListRow("Pixel photo first", subtitle = if (pixelAvailable) "Pixel Camera photo first, saved as a pair" else "The Pixel Camera app is not installed",
                    onClick = if (pixelAvailable) ({ onPixelFirst(!pixelFirst) }) else null,
                    trailing = { com.everettpenne.glass.LiquidSwitch(pixelFirst && pixelAvailable, { if (pixelAvailable) onPixelFirst(it) }) })
            }

            GlassSectionHeader("What to capture")
            GlassGroup {
                DatasetKind.values().forEachIndexed { i, k ->
                    if (i > 0) GlassRowDivider()
                    GlassListRow(k.label, subtitle = k.short, onClick = { onKind(k) }, trailing = {
                        if (k == kind) Icon(Icons.Rounded.CheckCircle, "Selected", tint = Holtzman.accent)
                    })
                }
            }

            GlassSectionHeader("Lighting")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                DatasetTags.LIGHT.forEach { t -> GlassChip(t in tags, { onTag(t) }) { Text(t) } }
            }
            GlassSectionHeader("Subject")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
                DatasetTags.SUBJECT.forEach { t -> GlassChip(t in tags, { onTag(t) }) { Text(t) } }
            }

            GlassSectionHeader("Note")
            GlassGroup {
                TextField(note, onNote, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("What and where (optional)") },
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
            }

            Spacer(Modifier.height(18.dp))
            if (!active) GlassButton("Start capturing", onStart, Modifier.fillMaxWidth(), prominent = true)
            else {
                GlassButton("Done", onDismiss, Modifier.fillMaxWidth(), prominent = true)
                Spacer(Modifier.height(8.dp))
                GlassButton("Stop dataset capture", onStop, Modifier.fillMaxWidth())
            }
            if (saved > 0) Text("$saved saved this session", style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, top = 10.dp))

            GlassSectionHeader("Tools")
            GlassGroup {
                GlassListRow("Calibrate the noise model", subtitle = "Two frames at each ISO; phone on a tripod", onClick = onCalibrate,
                    trailing = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
    }
}
