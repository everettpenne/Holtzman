package com.everettpenne.holtzman.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.GlassSheet
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.save.DatasetKind
import com.everettpenne.holtzman.save.DatasetTags

/** What the next dataset shot will be: its kind, its tags and a note. The shutter takes it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DatasetSheet(
    kind: DatasetKind, onKind: (DatasetKind) -> Unit,
    tags: Set<String>, onTag: (String) -> Unit,
    note: String, onNote: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GlassSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Dataset capture", style = IosType.title2)
            Text("Saves RAW frames and their metadata, unprocessed, to Documents/Holtzman/Dataset. Tripod for the last two kinds.", style = IosType.footnote)
            DatasetKind.values().forEach { k ->
                val on = k == kind
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) Holtzman.accent else Color.Black.copy(alpha = .08f)).clickable { onKind(k) }.padding(14.dp)) {
                    Text(k.label, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (on) Color.Black else Color.Unspecified)
                    Text(k.detail, style = IosType.footnote, color = if (on) Color.Black.copy(alpha = .75f) else Color.Unspecified)
                }
            }
            Text("Lighting", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { DatasetTags.LIGHT.forEach { TagChip(it, it in tags) { onTag(it) } } }
            Text("Subject", style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { DatasetTags.SUBJECT.forEach { TagChip(it, it in tags) { onTag(it) } } }
            OutlinedTextField(note, onNote, Modifier.fillMaxWidth(), label = { Text("Note (what and where)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TagChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(label, style = IosType.footnote.copy(fontWeight = FontWeight.Medium), color = if (on) Color.Black else Color.Unspecified,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Holtzman.accent else Color.Black.copy(alpha = .08f)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp))
}
