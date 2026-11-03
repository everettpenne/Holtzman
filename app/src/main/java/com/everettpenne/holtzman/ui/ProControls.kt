package com.everettpenne.holtzman.ui

import android.graphics.Bitmap
import android.view.HapticFeedbackConstants
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.camera.CameraController
import com.everettpenne.holtzman.ml.SubjectFinder
import com.everettpenne.ix.IxSubject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** What the viewfinder is following for focus: a face the camera found, or a subject the subject finder found. Boxes are fractions of the upright preview. */
class Tracked(val box: FloatArray, val isFace: Boolean, val label: String)

/**
 * Follows the subject of the viewfinder a few times a second (the subject finder on a 257 x 257 copy of the preview) and hands it to the camera's continuous
 * autofocus; the camera's own face detection still comes first. Returns what is being followed, for drawing. Off ([enabled] false) it clears the camera's
 * subject, so focus goes back to faces and the middle of the frame.
 */
@Composable
fun rememberSubjectTracking(view: TextureView?, controller: CameraController, enabled: Boolean, deviceDegrees: () -> Int): State<Tracked?> {
    val state = remember { mutableStateOf<Tracked?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(view, enabled) {
        if (!enabled || view == null) { controller.subjectBox = null; state.value = null; return@LaunchedEffect }
        val finder = withContext(Dispatchers.Default) { SubjectFinder.shared(context) }
        // depth, for the foreground subject when the segmentation recognises nothing (a bottle, a board, a flag); it takes seconds to compile for the TPU the
        // first time, so it loads beside the loop, which uses it once it is there
        var depth: com.everettpenne.holtzman.ml.DepthFinder? = null
        launch(Dispatchers.Default) { depth = com.everettpenne.holtzman.ml.DepthFinder.shared(context) }
        val small = Bitmap.createBitmap(com.everettpenne.holtzman.ml.DepthFinder.SIZE, com.everettpenne.holtzman.ml.DepthFinder.SIZE, Bitmap.Config.ARGB_8888)
        val smallCanvas = android.graphics.Canvas(small)
        val toSmall = android.graphics.Matrix().apply { setScale(com.everettpenne.holtzman.ml.DepthFinder.SIZE.toFloat() / SubjectFinder.SIZE, com.everettpenne.holtzman.ml.DepthFinder.SIZE.toFloat() / SubjectFinder.SIZE) }
        var timed = 0
        val bmp = Bitmap.createBitmap(SubjectFinder.SIZE, SubjectFinder.SIZE, Bitmap.Config.ARGB_8888)
        val upright = Bitmap.createBitmap(SubjectFinder.SIZE, SubjectFinder.SIZE, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(upright)
        var steady: IxSubject.Subject? = null; var lastCandidate: IxSubject.Subject? = null; var streak = 0
        try {
            while (isActive) {
                val face = controller.faceInPreview()
                if (finder != null && view.isAvailable && runCatching { view.getBitmap(bmp) }.isSuccess) {
                    // The screen is held in portrait, so with the phone turned the preview shows the scene on its side, and the network (trained on upright
                    // pictures) called a black camera case a person. The frame is turned upright for it, and the box turned back.
                    val turn = ((deviceDegrees() % 360) + 360) % 360
                    val input = if (turn == 0) bmp else { canvas.save(); canvas.rotate(turn.toFloat(), SubjectFinder.SIZE / 2f, SubjectFinder.SIZE / 2f); canvas.drawBitmap(bmp, 0f, 0f, null); canvas.restore(); upright }
                    val candidate = withContext(Dispatchers.Default) {
                        runCatching {
                            val t0 = System.nanoTime()
                            val seg = finder.find(input)
                            val t1 = System.nanoTime()
                            // a person, an animal or a clear object from the segmentation; otherwise what stands out in depth (falling back to a weak segment)
                            val found = if (seg != null && seg.strong) seg else depth?.let { smallCanvas.drawBitmap(input, toSmall, null); it.find(small) } ?: seg
                            if (timed < 4) { timed++; android.util.Log.i("HoltzmanSubject", "segmentation ${(t1 - t0) / 1_000_000} ms on ${finder.where}" + (depth?.let { ", depth ${(System.nanoTime() - t1) / 1_000_000} ms on ${it.where}" } ?: ", depth still loading")) }
                            found?.let { unturn(it, turn) }
                        }.onFailure { android.util.Log.w("HoltzmanSubject", "subject finder failed", it) }.getOrNull()
                    }
                    // how many frames running this candidate (or the lack of one) has been seen: a new subject must be seen twice before focus moves to it
                    streak = if ((candidate == null && lastCandidate == null) || (candidate != null && lastCandidate != null && candidate.label == lastCandidate.label && IxSubject.overlap(candidate, lastCandidate) > 0.2f)) streak + 1 else 1
                    lastCandidate = candidate
                    val before = steady
                    steady = IxSubject.steady(steady, candidate, streak)
                    if (steady?.label != before?.label) android.util.Log.i("HoltzmanSubject", "subject (frame turned $turn): ${steady?.name ?: "none"}" + (steady?.let { " %.2f %.2f %.2f %.2f, %.1f%% of the frame".format(java.util.Locale.ROOT, it.left, it.top, it.right, it.bottom, it.cover * 100) } ?: ""))
                }
                val s = steady
                controller.subjectBox = s?.focusBox()
                state.value = when {
                    face != null -> Tracked(face, true, "face")
                    s != null -> Tracked(floatArrayOf(s.left, s.top, s.right, s.bottom), false, s.name)
                    else -> null
                }
                delay(if (finder?.where == "TPU") 150 else 300)
            }
        } finally { controller.subjectBox = null }
    }
    return state
}

/** A subject found in a frame turned [turn] degrees clockwise, with its box turned back to the preview's own orientation. */
internal fun unturn(s: IxSubject.Subject, turn: Int): IxSubject.Subject {
    fun back(u: Float, v: Float): Pair<Float, Float> = when (turn) { 90 -> v to 1f - u; 180 -> 1f - u to 1f - v; 270 -> 1f - v to u; else -> u to v }
    if (turn == 0) return s
    val a = back(s.left, s.top); val b = back(s.right, s.bottom)
    return IxSubject.Subject(s.label, minOf(a.first, b.first), minOf(a.second, b.second), maxOf(a.first, b.first), maxOf(a.second, b.second), s.cover)
}

/**
 * The box around what focus is following, drawn the iPhone's way: a face gets a thin yellow square, any other subject four white corner marks. A tap or a
 * lock ([hidden]) hides it, since focus is then where the user put it.
 */
@Composable
fun SubjectOverlay(tracked: Tracked?, hidden: Boolean, modifier: Modifier = Modifier) {
    if (tracked == null || hidden) return
    Canvas(modifier) {
        val b = tracked.box
        val l = b[0] * size.width; val t = b[1] * size.height; val r = b[2] * size.width; val bt = b[3] * size.height
        if (tracked.isFace) {
            // square on the face, a little larger than the detector's box
            val cx = (l + r) / 2f; val cy = (t + bt) / 2f; val half = maxOf(r - l, bt - t) * 0.6f
            drawRect(CameraYellow, Offset(cx - half, cy - half), androidx.compose.ui.geometry.Size(half * 2, half * 2), style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
        } else {
            val arm = minOf(r - l, bt - t, 40.dp.toPx()) * 0.3f; val w = 2.dp.toPx(); val c = Color.White.copy(alpha = .85f)
            for ((x, sx) in listOf(l to 1f, r to -1f)) for ((y, sy) in listOf(t to 1f, bt to -1f)) {
                drawLine(c, Offset(x, y), Offset(x + sx * arm, y), w, StrokeCap.Round)
                drawLine(c, Offset(x, y), Offset(x, y + sy * arm), w, StrokeCap.Round)
            }
        }
    }
}

/**
 * A pro dial, the iPhone's way: a ruler of [stops] that slides under a fixed yellow needle. Drag sideways to turn it (each stop crossed gives a light tick
 * under the finger); it settles on the nearest stop when let go. Every [labelEvery]th stop is labelled. [index] is the stop now under the needle.
 */
@Composable
fun DialRuler(stops: List<String>, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier, labelEvery: Int = 3, spacing: androidx.compose.ui.unit.Dp = 14.dp, description: String = "Dial") {
    val view = LocalView.current
    var pos by remember { mutableFloatStateOf(index.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(index) { if (!dragging) pos = index.toFloat() }
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Color.White.copy(alpha = .75f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
    Canvas(modifier.fillMaxWidth().height(54.dp).semantics { contentDescription = description }
        .pointerInput(stops.size) {
            val step = spacing.toPx()
            detectHorizontalDragGestures(onDragStart = { dragging = true }, onDragEnd = { dragging = false; val i = pos.roundToInt().coerceIn(0, stops.lastIndex); pos = i.toFloat(); onIndex(i) }, onDragCancel = { dragging = false }) { change, dx ->
                change.consume()
                val before = pos.roundToInt()
                pos = (pos - dx / step).coerceIn(0f, stops.lastIndex.toFloat())
                val now = pos.roundToInt()
                if (now != before) { view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onIndex(now) }
            }
        }
        .pointerInput(stops.size) { detectTapGestures { p -> val step = spacing.toPx(); val i = (pos + (p.x - size.width / 2f) / step).roundToInt().coerceIn(0, stops.lastIndex); pos = i.toFloat(); view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); onIndex(i) } }) {
        val step = spacing.toPx(); val mid = size.width / 2f
        val first = floor(pos - mid / step).toInt().coerceAtLeast(0); val last = (pos + mid / step).toInt().coerceAtMost(stops.lastIndex)
        for (i in first..last) {
            val x = mid + (i - pos) * step
            val edge = 1f - (abs(x - mid) / mid).coerceIn(0f, 1f) * 0.7f   // ticks fade toward the ends, as the iPhone's do
            val major = i % labelEvery == 0
            val h = if (major) 16.dp.toPx() else 9.dp.toPx()
            drawLine(Color.White.copy(alpha = .55f * edge), Offset(x, 30.dp.toPx() - h / 2f), Offset(x, 30.dp.toPx() + h / 2f), if (major) 2.dp.toPx() else 1.2.dp.toPx(), StrokeCap.Round)
            if (major) {
                val m = measurer.measure(stops[i], labelStyle.copy(color = labelStyle.color.copy(alpha = .75f * edge)))
                drawText(m, topLeft = Offset(x - m.size.width / 2f, 42.dp.toPx()))
            }
        }
        drawLine(CameraYellow, Offset(mid, 14.dp.toPx()), Offset(mid, 39.dp.toPx()), 2.5.dp.toPx(), StrokeCap.Round)   // ends above the labels
    }
}

/**
 * A pro control's card: the value now in a yellow capsule (or AUTO), an AUTO button where the control can be left to the camera, and the dial. [auto] null
 * means the control has no automatic setting (exposure compensation).
 */
@Composable
fun DialCard(title: String, value: String, stops: List<String>, index: Int, onIndex: (Int) -> Unit, onBack: () -> Unit, auto: Boolean? = null, onAuto: (() -> Unit)? = null, labelEvery: Int = 3) {
    CardFrame {
        CardHeader(onBack, trailing = {
            if (auto != null) Text("AUTO", maxLines = 1, softWrap = false, style = IosType.caption1.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = if (auto) Color.Black else Color.White,
                modifier = Modifier.clip(CircleShape).background(if (auto) CameraYellow else Color.White.copy(alpha = .16f)).clickable(enabled = !auto) { onAuto?.invoke() }.padding(horizontal = 8.dp, vertical = 5.dp))
        }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CardTitle(title)
                Text(value, style = IosType.subheadline.copy(fontWeight = FontWeight.SemiBold), color = if (auto == true) Color.White else Color.Black,
                    modifier = Modifier.clip(CircleShape).background(if (auto == true) Color.White.copy(alpha = .16f) else CameraYellow).padding(horizontal = 10.dp, vertical = 3.dp))
            }
        }
        DialRuler(stops, index, onIndex, Modifier.padding(horizontal = 4.dp, vertical = 2.dp), labelEvery = labelEvery, description = title)
    }
}

/**
 * The pro readout over the top of the viewfinder: what exposure and focus are doing now (ISO, shutter, compensation, focus), live from the camera, in white
 * when automatic and yellow when set by hand. Each value opens its dial.
 */
@Composable
fun ProReadout(items: List<Triple<String, Boolean, () -> Unit>>, modifier: Modifier = Modifier) {
    Row(modifier.liquidText(CircleShape).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { (text, manual, onClick) ->
            Text(text, maxLines = 1, softWrap = false, style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp, fontFeatureSettings = "tnum"),
                color = if (manual) CameraYellow else Color.White.copy(alpha = .9f),
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 9.dp, vertical = 6.dp))
        }
    }
}

/** Exposure compensation stops for the dial, a third of a stop apart from -2 to +2. */
val EV_STOPS: List<Float> = (-6..6).map { it / 3f }
fun evLabel(ev: Float): String = if (abs(ev) < 0.01f) "0" else "%+.1f".format(java.util.Locale.ROOT, ev)

/** Focus stops for the dial, as diopters from infinity to the closest the lens can focus, spaced evenly in diopters (as the lens moves). */
fun focusStops(minFocusDiopters: Float, n: Int = 31): List<Float> = (0 until n).map { it * minFocusDiopters / (n - 1) }
