package com.everettpenne.holtzman.ui

import android.content.ContentValues
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.everettpenne.holtzman.ml.AcceleratorSpike
import kotlinx.coroutines.launch
import com.everettpenne.holtzman.probe.ProbeCollector
import com.everettpenne.holtzman.probe.ProbeFormat

/**
 * The fact-finding run: what this phone's camera HAL offers a third-party app, as text to read and to save. "Take comparison pair" shoots one
 * default photo and one with every processing stage at high quality, so they can be set beside the stock camera's.
 */
@Composable
fun ProbeSheet(onDismiss: () -> Unit, onCompare: () -> Unit) {
    val context = LocalContext.current
    val report = remember { ProbeCollector.collect(context) }
    val text = remember(report) { ProbeFormat.text(report) }
    var saved by remember { mutableStateOf<String?>(null) }
    var spike by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    GlassSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Camera probe", style = IosType.title2)
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) }
            GlassButton("Save the report", {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, "Holtzman-probe-${android.os.Build.DEVICE}.txt")
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = runCatching { context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
                saved = if (uri != null && runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } }.isSuccess) "Saved to Downloads" else "Couldn't save the report"
            }, Modifier.fillMaxWidth(), prominent = true)
            GlassButton("Take a comparison pair", onCompare, Modifier.fillMaxWidth(), prominent = false)
            GlassButton("Test the Tensor accelerators", {
                spike = "Running…"
                scope.launch(kotlinx.coroutines.Dispatchers.Default) { val t = AcceleratorSpike.text(AcceleratorSpike.run(context)); spike = t }
            }, Modifier.fillMaxWidth(), prominent = false)
            spike?.let { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp) }
            saved?.let { Text(it, style = IosType.footnote) }
            Spacer(Modifier.height(8.dp))
        }
    }
}
