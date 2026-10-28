package com.everettpenne.holtzman.ui

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everettpenne.glass.GlassButton
import com.everettpenne.glass.GlassSheet
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.probe.ProbeCollector
import com.everettpenne.holtzman.probe.ProbeFormat

/** What this phone's camera HAL offers an app, as text to read and to save (to Downloads). */
@Composable
fun CameraReportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = remember { ProbeFormat.text(ProbeCollector.collect(context)) }
    var saved by remember { mutableStateOf<String?>(null) }
    GlassSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Camera report", style = IosType.title2, color = MaterialTheme.colorScheme.onSurface)
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurface)
            }
            GlassButton("Save the report", {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Holtzman-camera-report-${android.os.Build.DEVICE}.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = runCatching { context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
                saved = if (uri != null && runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess) "Saved to Downloads" else "Couldn't save the report"
            }, Modifier.fillMaxWidth(), prominent = true)
            saved?.let { Text(it, style = IosType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
