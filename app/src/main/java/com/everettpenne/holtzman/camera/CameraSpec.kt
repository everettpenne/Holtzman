package com.everettpenne.holtzman.camera

import kotlin.math.abs

/*
 * The camera's decisions that need no camera: which size to ask for, how to turn the phone's tilt into a JPEG orientation, how to name what a
 * sensor reports. Pure, so they are unit-tested on the JVM; CameraController only carries them out.
 */

/** A pixel size (kept free of android.util.Size so it runs in plain unit tests). */
data class Dim(val width: Int, val height: Int) {
    val area: Long get() = width.toLong() * height
    val ratio: Float get() = width.toFloat() / height
    override fun toString() = "${width}x$height"
}

/** The aspect ratios a photo can be taken in. 4:3 is the sensor's own on a phone, so it loses nothing. */
enum class Aspect(val label: String, val ratio: Float) { FOUR_THREE("4:3", 4f / 3f), SIXTEEN_NINE("16:9", 16f / 9f), SQUARE("1:1", 1f) }

/** The largest of [sizes] whose shape is within [tolerance] of [ratio] (landscape sensors report width > height); null when none match. */
fun pickStillSize(sizes: List<Dim>, ratio: Float = 4f / 3f, tolerance: Float = 0.02f): Dim? =
    sizes.filter { abs(it.ratio - ratio) <= tolerance }.maxByOrNull { it.area }

/**
 * The preview size for a screen [targetWidth] pixels wide: the largest size no wider than [maxWidth] (a phone does not need a 12 MP preview) with
 * the shape of the still, so what is framed is what is shot. Falls back to the smallest size that matches the shape, then to any.
 */
fun pickPreviewSize(sizes: List<Dim>, stillRatio: Float, maxWidth: Int = 1920): Dim? {
    val matching = sizes.filter { abs(it.ratio - stillRatio) <= 0.02f }
    return matching.filter { it.width <= maxWidth }.maxByOrNull { it.area }
        ?: matching.minByOrNull { it.area }
        ?: sizes.filter { it.width <= maxWidth }.maxByOrNull { it.area }
        ?: sizes.minByOrNull { it.area }
}

/**
 * The JPEG_ORIENTATION to request. [sensorOrientation] is the camera's fixed mounting angle; [deviceDegrees] is how far the phone is turned
 * clockwise from upright (0, 90, 180, 270), from the orientation sensor. The front camera turns the other way, because it looks at you.
 */
fun jpegOrientation(sensorOrientation: Int, deviceDegrees: Int, frontFacing: Boolean): Int {
    val d = ((deviceDegrees % 360) + 360) % 360
    val snapped = ((d + 45) / 90 * 90) % 360
    return if (frontFacing) (sensorOrientation - snapped + 360) % 360 else (sensorOrientation + snapped) % 360
}

/** An OrientationEventListener angle (0..359, or -1 when flat) snapped to the nearest 90, keeping [previous] while the phone lies flat. */
fun snapDeviceDegrees(angle: Int, previous: Int): Int =
    if (angle < 0) previous else (((angle + 45) / 90) * 90) % 360

/** The stem of a saved file: "HOLTZ_20261027_141503_042" (local time to the millisecond, so two quick shots never share a name). */
fun captureBaseName(timestampMillis: Long, zoneOffsetMillis: Long): String {
    val t = timestampMillis + zoneOffsetMillis
    val days = Math.floorDiv(t, 86_400_000L)
    val ms = Math.floorMod(t, 86_400_000L)
    // civil-from-days (Howard Hinnant's algorithm): the date without java.time, so this runs on any Android level and on plain JVM tests
    val z = days + 719_468
    val era = Math.floorDiv(z, 146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    val h = ms / 3_600_000; val m = ms / 60_000 % 60; val s = ms / 1000 % 60; val milli = ms % 1000
    return "HOLTZ_%04d%02d%02d_%02d%02d%02d_%03d".format(year, month, day, h, m, s, milli)
}

/**
 * A JPEG without the zero padding some camera pipelines leave after its end. The vendor extensions hand back a fixed-size buffer (a 0.9 MB picture
 * in a 9.4 MB buffer on the Pixel 10a); stored as it came, every Night photo would be ten times its size. Returns [bytes] unchanged when it already
 * ends at the end-of-image marker, or when no marker is found (a damaged file is not made worse by guessing).
 */
fun trimJpeg(bytes: ByteArray): ByteArray {
    var end = bytes.size
    while (end > 2 && bytes[end - 1] == 0.toByte()) end--
    return if (end >= 2 && bytes[end - 2] == 0xFF.toByte() && bytes[end - 1] == 0xD9.toByte() && end < bytes.size) bytes.copyOf(end) else bytes
}

/** The EXIF orientation value for a rotation of 0, 90, 180 or 270 degrees clockwise (1, 6, 3, 8). */
fun exifOrientationFor(degrees: Int): Int = when (((degrees % 360) + 360) % 360) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 }


/**
 * Where a tap on the upright preview lands on the sensor, as a fraction of the sensor's active array (0..1 each way). [u] and [v] are the tap as a
 * fraction of the preview's width (left to right) and height (top to bottom). A sensor mounted at 90 degrees is turned a quarter to look upright, so
 * the axes swap; the zoom crops a centred window, which the tap has to be mapped back out of.
 */
fun sensorPoint(u: Float, v: Float, sensorOrientation: Int, zoom: Float): Pair<Float, Float> {
    val (sx, sy) = when (((sensorOrientation % 360) + 360) % 360) {
        90 -> v to 1f - u
        180 -> 1f - u to 1f - v
        270 -> 1f - v to u
        else -> u to v
    }
    val z = zoom.coerceAtLeast(1f)
    return (0.5f + (sx - 0.5f) / z).coerceIn(0f, 1f) to (0.5f + (sy - 0.5f) / z).coerceIn(0f, 1f)
}

/** A square metering area (left, top, right, bottom in active-array pixels) of [fraction] of the shorter side, centred on the point and kept inside the array. */
fun meteringBox(point: Pair<Float, Float>, arrayWidth: Int, arrayHeight: Int, fraction: Float = 0.12f): IntArray {
    val half = (minOf(arrayWidth, arrayHeight) * fraction / 2f).toInt().coerceAtLeast(1)
    val cx = (point.first * arrayWidth).toInt().coerceIn(half, arrayWidth - half)
    val cy = (point.second * arrayHeight).toInt().coerceIn(half, arrayHeight - half)
    return intArrayOf(cx - half, cy - half, cx + half, cy + half)
}

/** A zoom ratio kept inside what the camera offers. */
fun clampZoom(ratio: Float, min: Float, max: Float): Float = if (ratio.isNaN()) min.coerceAtLeast(1f).coerceAtMost(max) else ratio.coerceIn(min, max)

/**
 * The zoom shortcuts worth offering for a camera whose range is [min]..[max]: the widest it goes when that is below 1x (the Pixel 10a's logical
 * camera reaches 0.56x, its ultrawide lens; a fixed 0.5x would be out of range), then 1x, 2x, 4x and 8x when it can.
 */
fun zoomStops(min: Float, max: Float): List<Float> = buildList {
    if (min < 0.95f) add(min)
    add(1f)
    if (2f <= max) add(2f)
    if (4f <= max) add(4f)   // beyond 2x it is a digital zoom, as on the iPhone, which offers .5 1 2 4 8
    if (8f <= max) add(8f)
}

/** The flash settings a person chooses between. */
enum class FlashMode(val label: String) {
    OFF("Off"), AUTO("Auto"), ON("On");
    fun next(): FlashMode = entries[(ordinal + 1) % entries.size]
}

/** The AE_MODE to request for [flash]: the plain mode when there is no flash unit or the flash is off, otherwise the flash-aware one. */
fun aeModeFor(flash: FlashMode, hasFlash: Boolean): Int = when {
    !hasFlash || flash == FlashMode.OFF -> 1                  // CONTROL_AE_MODE_ON
    flash == FlashMode.AUTO -> 2                              // CONTROL_AE_MODE_ON_AUTO_FLASH
    else -> 3                                                 // CONTROL_AE_MODE_ON_ALWAYS_FLASH
}

/** The self-timer: off, 3 seconds, 10 seconds. */
enum class TimerMode(val seconds: Int, val label: String) {
    OFF(0, "Off"), THREE(3, "3 s"), TEN(10, "10 s");
    fun next(): TimerMode = entries[(ordinal + 1) % entries.size]
}

/** The video sizes on offer. [camcorder] is the CamcorderProfile.QUALITY_* constant (2160P = 8, 1080P = 6, 720P = 5), kept as a number so this file stays Android-free. */
enum class VideoQuality(val label: String, val camcorder: Int) {
    UHD("4K", 8), FHD("1080p", 6), HD("720p", 5);
    /** The next smaller size the phone also offers, wrapping to the largest. */
    fun next(available: List<VideoQuality>): VideoQuality {
        if (available.isEmpty()) return this
        val i = available.indexOf(this)
        return available[(i + 1) % available.size]
    }
}

/** The default size: 1080p when offered (it is what people expect from a phone's default, and what keeps a minute of video near 130 MB), else the largest offered. */
fun defaultVideoQuality(available: List<VideoQuality>): VideoQuality? = if (VideoQuality.FHD in available) VideoQuality.FHD else available.firstOrNull()

/** The recording clock, m:ss (h:mm:ss from an hour). */
fun recordingClock(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** The orientation hint for a video file: the sensor's angle turned by the phone's tilt, as for a photo. */
fun videoOrientation(sensorOrientation: Int, deviceDegrees: Int, frontFacing: Boolean): Int = jpegOrientation(sensorOrientation, deviceDegrees, frontFacing)


/** A face the camera found: its box in the sensor's active-array coordinates (left, top, right, bottom) and the camera's confidence, 1 to 100. */
class FaceBox(val left: Int, val top: Int, val right: Int, val bottom: Int, val score: Int) { val area: Int get() = (right - left) * (bottom - top) }

/** The biggest face the camera is reasonably sure of, or null. */
fun largestFace(faces: List<FaceBox>, minScore: Int = 60): FaceBox? = faces.filter { it.score >= minScore && it.area > 0 }.maxByOrNull { it.area }

/** A face's box grown by [margin] each way (so the region covers the whole head's contrast, not only the skin) and kept inside the array. */
fun faceRegion(face: FaceBox, arrayWidth: Int, arrayHeight: Int, margin: Float = 0.2f): IntArray {
    val mx = ((face.right - face.left) * margin).toInt(); val my = ((face.bottom - face.top) * margin).toInt()
    return intArrayOf((face.left - mx).coerceIn(0, arrayWidth - 1), (face.top - my).coerceIn(0, arrayHeight - 1), (face.right + mx).coerceIn(1, arrayWidth), (face.bottom + my).coerceIn(1, arrayHeight))
}

/** A box in active-array coordinates scaled to a RAW frame of [rawWidth] x [rawHeight] pixels, kept inside it. */
fun scaleToRaw(box: IntArray, arrayWidth: Int, arrayHeight: Int, rawWidth: Int, rawHeight: Int): IntArray {
    val sx = rawWidth.toFloat() / arrayWidth; val sy = rawHeight.toFloat() / arrayHeight
    return intArrayOf((box[0] * sx).toInt().coerceIn(0, rawWidth - 1), (box[1] * sy).toInt().coerceIn(0, rawHeight - 1), (box[2] * sx).toInt().coerceIn(1, rawWidth), (box[3] * sy).toInt().coerceIn(1, rawHeight))
}

/**
 * The longest exposure that keeps hand shake from blurring a frame by more than [maxBlurPx] pixels: shake turns the camera at [rate] radians a second,
 * which moves the picture across the sensor at rate x [focalPx] pixels a second. The merge can align one frame to another but cannot undo blur inside
 * a frame, so a dim scene held in a shaky hand is better shot as shorter, noisier frames. Kept between [minNs] and [maxNs].
 */
fun shakeExposureLimitNs(rate: Float, focalPx: Float, maxBlurPx: Float = 1.5f, minNs: Long = 4_000_000L, maxNs: Long = 250_000_000L): Long {
    if (rate <= 1e-4f || focalPx <= 0f) return maxNs
    return ((maxBlurPx / (rate * focalPx)) * 1e9).toLong().coerceIn(minNs, maxNs)
}

/**
 * Trades exposure time for ISO so the picture stays as bright: an exposure longer than [limitNs] is cut to it and the ISO raised in proportion, up to
 * [isoMax]; if the ISO tops out first, the exposure grows back just enough to keep the brightness (a shaky dark scene gets some blur rather than a
 * dark picture).
 */
fun shakeAdjusted(exposureNs: Long, iso: Int, limitNs: Long, isoMax: Int): Pair<Long, Int> {
    if (exposureNs <= limitNs) return exposureNs to iso
    val wanted = Math.ceil(iso.toDouble() * exposureNs / limitNs).toInt()
    val newIso = wanted.coerceAtMost(maxOf(isoMax, iso))
    val newNs = (exposureNs.toDouble() * iso / newIso).toLong()
    return newNs to newIso
}

/** What the controller knows about a frame in the zero-shutter-lag ring: when it was taken and how it was exposed. */
class RingEntry(val timestampNs: Long, val exposureNs: Long, val iso: Int)

private fun sameExposure(a: RingEntry, b: RingEntry): Boolean =
    Math.abs(a.exposureNs.toDouble() / b.exposureNs - 1.0) < 0.03 && Math.abs(a.iso.toDouble() / b.iso - 1.0) < 0.03

/**
 * Which frames of the ring (oldest first) make the burst: the newest frame and those before it that were exposed the same way (the merge assumes equal
 * exposures, and automatic exposure may still be settling), with no gap between neighbours longer than [maxGapNs] (a stall in the stream), at most
 * [count]. Returns their indices, oldest first.
 */
fun pickRingFrames(ring: List<RingEntry>, count: Int, maxGapNs: Long = 100_000_000L): List<Int> {
    if (ring.isEmpty()) return emptyList()
    val newest = ring.last(); val picked = ArrayList<Int>()
    var i = ring.size - 1; var later = newest.timestampNs
    while (i >= 0 && picked.size < count) {
        val e = ring[i]
        if (!sameExposure(e, newest) || later - e.timestampNs > maxGapNs) break
        picked.add(i); later = e.timestampNs; i--
    }
    return picked.reversed()
}
