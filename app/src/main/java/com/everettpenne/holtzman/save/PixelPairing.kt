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

    /** The scene's folder name for a series begun at [startedMillis]: also the Pixel photo's file name (plus .jpg). */
    fun sceneName(startedMillis: Long, kind: DatasetKind): String = "scene_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(startedMillis)) + "_" + kind.name.lowercase()
}
