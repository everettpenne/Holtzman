package com.everettpenne.holtzman.save

import android.content.ContentResolver
import android.content.ContentValues
import android.hardware.camera2.DngCreator
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.everettpenne.holtzman.camera.RawCapture
import com.everettpenne.holtzman.camera.StillResult
import androidx.exifinterface.media.ExifInterface
import com.everettpenne.holtzman.camera.captureBaseName
import com.everettpenne.holtzman.camera.exifOrientationFor
import java.util.TimeZone

/** What a save wrote: the content address of each file, in the order the media library holds them. */
class Saved(val jpeg: Uri, val dng: Uri?)

/**
 * Writes a shot to Pictures/Holtzman through MediaStore (which needs no storage permission). A file is "pending" while it is written and only then
 * shown to other apps, so Sietch or Gallery never meets half a photo; a failure deletes what was begun, so a failed save leaves nothing behind.
 */
class PhotoSaver(private val resolver: ContentResolver) {

    /**
     * Writes the shot. [hdrJpeg] replaces the JPEG when an Ultra HDR file was built (it has lost its EXIF orientation in the re-encode, so that is
     * written back); [keepDng] says whether the DNG is wanted (the RAW frame is also taken just to build Ultra HDR, and is then discarded).
     */
    fun save(still: StillResult, hdrJpeg: ByteArray? = null, keepDng: Boolean = true): Saved {
        val base = captureBaseName(still.timestampMillis, TimeZone.getDefault().getOffset(still.timestampMillis).toLong()) + if (still.tag.isEmpty()) "" else "_${still.tag}"
        val jpeg = write("$base.jpg", "image/jpeg", still.timestampMillis) { out -> out.write(hdrJpeg ?: still.jpeg) }
        if (still.needsExifOrientation || hdrJpeg != null) setExifOrientation(jpeg, still.orientation)
        val dng = still.raw?.let { raw ->
            if (!keepDng) { raw.close(); return@let null }
            try {
                write("$base.dng", "image/x-adobe-dng", still.timestampMillis) { out ->
                    DngCreator(still.characteristics, raw.result).use { creator ->
                        creator.setOrientation(exifOrientation(still.orientation))
                        creator.writeImage(out, raw.image)
                    }
                }
            } finally { raw.close() }
        }
        return Saved(jpeg, dng)
    }

    /** A JPEG of [bitmap] at [quality], with the EXIF orientation [degrees] written back afterwards, in Pictures/Holtzman like every other file. */
    fun writeJpeg(name: String, timeMillis: Long, bitmap: android.graphics.Bitmap, quality: Int, degrees: Int): Uri {
        // The rotation goes into the file while it is still pending. The media library reads a file's EXIF once, when it is published, and keeps what it
        // read: a tag added afterwards leaves it believing the picture is upright, and the gallery, Files and anything built on its cache then show a
        // portrait shot (a landscape bitmap in sensor orientation) turned a quarter, which is exactly what the first burst photos looked like.
        return write(name, "image/jpeg", timeMillis, beforePublish = { uri -> setExifOrientation(uri, degrees) }) { out ->
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, out)) throw java.io.IOException("The JPEG encoder refused $name.")
        }
    }

    /** Whether the saved picture at [uri] really carries an Ultra HDR gain map (decoded small, which keeps it). */
    fun hasGainmap(uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })?.hasGainmap() } ?: false
    }.getOrDefault(false)

    /** A DNG written by [body] to Pictures/Holtzman, pending until complete and removed on failure, like every other file. */
    fun writeDng(name: String, timeMillis: Long, body: (java.io.OutputStream) -> Unit): Uri = write(name, "image/x-adobe-dng", timeMillis, body = body)

    /**
     * A file of any type in Documents/Holtzman/[folder] (the gallery does not list Documents, so a dataset of raw frames does not clutter it), pending
     * until complete and removed on failure.
     */
    fun writeDocument(folder: String, name: String, mime: String, timeMillis: Long, body: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Holtzman/" + folder)
            put(MediaStore.MediaColumns.DATE_TAKEN, timeMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw java.io.IOException("The media library would not make $name.")
        try {
            resolver.openOutputStream(uri)?.use(body) ?: throw java.io.IOException("Could not open $name for writing.")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    /**
     * An empty file in Documents/Holtzman/[folder] for another app to write into (the Pixel Camera app, given its Uri), not pending, because a pending file is
     * hidden from other apps. [sizeOf] says whether anything was written; [abandonDocument] removes it.
     */
    fun beginDocument(folder: String, name: String, mime: String, timeMillis: Long): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Holtzman/" + folder)
            put(MediaStore.MediaColumns.DATE_TAKEN, timeMillis)
        }
        return resolver.insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: throw java.io.IOException("The media library would not make $name.")
    }

    /** The size in bytes of the file at [uri], 0 if it is empty or cannot be read. */
    fun sizeOf(uri: Uri): Long = runCatching { resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L }.getOrDefault(0L)

    fun abandonDocument(uri: Uri) { runCatching { resolver.delete(uri, null, null) } }

    private fun write(name: String, mime: String, timeMillis: Long, beforePublish: (Uri) -> Unit = {}, body: (java.io.OutputStream) -> Unit): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Holtzman")
            put(MediaStore.MediaColumns.DATE_TAKEN, timeMillis)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("The media library would not make $name.")
        try {
            resolver.openOutputStream(uri)?.use(body) ?: throw java.io.IOException("Could not open $name for writing.")
            beforePublish(uri)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }
            throw t
        }
        return uri
    }

    /** Writes the orientation tag into a saved JPEG (for pipelines that leave it at 1). A failure leaves the photo as it was: sideways beats lost. */
    private fun setExifOrientation(uri: Uri, degrees: Int) {
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val exif = ExifInterface(pfd.fileDescriptor)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientationFor(degrees).toString())
                exif.saveAttributes()
            }
        }
    }

    companion object {
        fun exifOrientation(degrees: Int): Int = when (degrees) {
            90 -> ExifInterface.ORIENTATION_ROTATE_90
            180 -> ExifInterface.ORIENTATION_ROTATE_180
            270 -> ExifInterface.ORIENTATION_ROTATE_270
            else -> ExifInterface.ORIENTATION_NORMAL
        }
    }

    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }
}
