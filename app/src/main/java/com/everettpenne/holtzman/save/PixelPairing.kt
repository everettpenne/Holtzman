package com.everettpenne.holtzman.save

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Paired data collection: a photo taken in the Google Pixel Camera app, then Holtzman's dataset series, as one pair. The Pixel Camera app is asked, with the
 * standard "take one picture" request, to write its photo to a file of ours in Documents/Holtzman/Dataset/PixelDoubles, named like the scene it belongs to,
 * so the pairing is in the names (and in the scene's meta.json) and not a guess from the time. Nothing here needs the Pixel app's cooperation beyond
 * that request; if it is not installed, dataset capture just runs without it.
 */
object PixelPairing {
    const val PACKAGE = "com.google.android.GoogleCamera"
    /** Where the Pixel photos go, under Documents/Holtzman. */
    const val FOLDER = "Dataset/PixelDoubles"

    fun available(context: Context): Boolean = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.isSuccess

    fun version(context: Context): String? = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0).versionName }.getOrNull()

    /** The request to the Pixel Camera app: one photo, written to [output]. */
    fun captureIntent(output: Uri): Intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).setPackage(PACKAGE)
        .putExtra(MediaStore.EXTRA_OUTPUT, output)
        .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .also { it.clipData = ClipData.newRawUri("", output) }

    /** What was read from the Pixel photo: its picture as brightness values (upright, small), and the lens' focal length in mm (null if not recorded). */
    class Photo(val luma: ZoomEstimate.Luma, val focalLengthMm: Float?)

    /** Reads the Pixel photo at [uri], upright, down to about [target] pixels on its long side. Null if it cannot be read. */
    fun readPhoto(context: Context, uri: Uri, target: Int = 320): Photo? = runCatching {
        val resolver = context.contentResolver
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1; while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= target) sample *= 2
        val bmp = resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return@runCatching null
        val exif = resolver.openInputStream(uri)?.use { android.media.ExifInterface(it) }
        val turn = when (exif?.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        val upright = if (turn == 0f) bmp else android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, android.graphics.Matrix().apply { postRotate(turn) }, true)
        Photo(lumaOf(upright), exif?.getAttributeDouble(android.media.ExifInterface.TAG_FOCAL_LENGTH, 0.0)?.takeIf { it > 0 }?.toFloat())
    }.getOrNull()

    /** A bitmap as brightness values. */
    fun lumaOf(b: android.graphics.Bitmap): ZoomEstimate.Luma {
        val px = IntArray(b.width * b.height); b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return ZoomEstimate.Luma(b.width, b.height, FloatArray(px.size) { val p = px[it]; 0.2126f * ((p shr 16) and 255) + 0.7152f * ((p shr 8) and 255) + 0.0722f * (p and 255) })
    }

    /** The scene's folder name for a series begun at [startedMillis]: also the Pixel photo's file name (plus .jpg). */
    fun sceneName(startedMillis: Long, kind: DatasetKind): String = "scene_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedMillis)) + "_" + kind.name.lowercase()
}
