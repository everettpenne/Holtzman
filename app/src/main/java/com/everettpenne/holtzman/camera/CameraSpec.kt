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

