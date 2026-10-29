package com.everettpenne.holtzman.ui

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.everettpenne.glass.IosType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * How far the phone is rolled from level, in degrees, to the nearest quarter turn (so a phone held on its side reads as level too): positive when the
 * right edge is low. Null while the phone points up or down at the sky or ground, where "level" means nothing, or while [enabled] is false.
 */
@Composable
fun rememberRollDegrees(enabled: Boolean): State<Float?> {
    val context = LocalContext.current
    val roll = remember { mutableStateOf<Float?>(null) }
    DisposableEffect(enabled) {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var smoothed: Float? = null
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val g = sqrt(x * x + y * y + z * z)
                if (g < 1f || abs(z) / g > 0.8f) { smoothed = null; roll.value = null; return }   // pointing at the sky or the ground
                var deg = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()   // 0 when upright
                deg -= 90f * Math.round(deg / 90f)   // to the nearest quarter turn: -45..45
                val s = smoothed?.let { it + 0.25f * (deg - it) } ?: deg
                smoothed = s; roll.value = s
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (enabled && sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI) else roll.value = null
        onDispose { sm.unregisterListener(l) }
    }
    return roll
}

/** The level line over the picture, like the Pixel camera app's: shown while the phone is within about 10 degrees of level, white off level and the accent colour within 1 degree. */
@Composable
fun LevelOverlay(rollDegrees: Float?, modifier: Modifier = Modifier) {
    val roll = rollDegrees ?: return
    if (abs(roll) > 10f) return
    val level = abs(roll) < 1f
    val color = if (level) Holtzman.accent else Color.White.copy(alpha = .85f)
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            val c = center; val half = size.width * 0.18f
            // the line stays level with the horizon: it turns against the phone's roll
            rotate(-roll, c) { drawLine(color, Offset(c.x - half, c.y), Offset(c.x + half, c.y), strokeWidth = if (level) 4f else 3f) }
            if (!level) drawLine(color.copy(alpha = .4f), Offset(c.x - half * 0.6f, c.y), Offset(c.x + half * 0.6f, c.y), strokeWidth = 2f)
        }
        Text("${Math.round(abs(roll))}°", style = IosType.caption1, color = color, modifier = Modifier.align(Alignment.Center).offset(y = (-16).dp))
    }
}

/**
 * The brightness histogram of the viewfinder, 64 bins from black to white, refreshed about five times a second from a small copy of the frame while
 * [enabled]. Also the shares of pixels at the very bottom and top, for the clipping marks.
 */
class Histogram(val bins: FloatArray, val blackShare: Float, val whiteShare: Float)

@Composable
fun rememberHistogram(view: TextureView?, enabled: Boolean): State<Histogram?> {
    val state = remember { mutableStateOf<Histogram?>(null) }
    LaunchedEffect(view, enabled) {
        if (!enabled || view == null) { state.value = null; return@LaunchedEffect }
        val bmp = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        val px = IntArray(120 * 160)
        while (isActive) {
            val ok = view.isAvailable && runCatching { view.getBitmap(bmp) }.isSuccess
            if (ok) state.value = withContext(Dispatchers.Default) {
                bmp.getPixels(px, 0, 120, 0, 0, 120, 160)
                val h = FloatArray(64); var black = 0; var white = 0
                for (p in px) {
                    val y = (0.2126f * ((p shr 16) and 255) + 0.7152f * ((p shr 8) and 255) + 0.0722f * (p and 255))
                    h[(y / 4f).toInt().coerceIn(0, 63)] += 1f
                    if (y < 4f) black++; if (y > 250f) white++
                }
                val peak = h.max().coerceAtLeast(1f)
                Histogram(FloatArray(64) { sqrt(h[it] / peak) }, black / px.size.toFloat(), white / px.size.toFloat())   // square root: the shadows' small counts stay visible
            }
            delay(200)
        }
    }
    return state
}

/** The histogram as a small translucent card; a red mark at an end says more than 2% of the picture is clipped there. */
@Composable
fun HistogramView(h: Histogram?, modifier: Modifier = Modifier) {
    if (h == null) return
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(Color.Black.copy(alpha = .5f)).padding(8.dp)) {
        Canvas(Modifier.size(width = 120.dp, height = 52.dp)) {
            val w = size.width / h.bins.size
            h.bins.forEachIndexed { i, v -> drawRect(Color.White.copy(alpha = .85f), Offset(i * w, size.height * (1f - v)), androidx.compose.ui.geometry.Size(w * 0.85f, size.height * v)) }
            if (h.blackShare > 0.02f) drawRect(Color(0xFFFF5A4F), Offset(0f, 0f), androidx.compose.ui.geometry.Size(6f, size.height))
            if (h.whiteShare > 0.02f) drawRect(Color(0xFFFF5A4F), Offset(size.width - 6f, 0f), androidx.compose.ui.geometry.Size(6f, size.height))
        }
    }
}
