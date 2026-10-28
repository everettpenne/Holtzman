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

    fun save(still: StillResult): Saved {
        val base = captureBaseName(still.timestampMillis, TimeZone.getDefault().getOffset(still.timestampMillis).toLong()) + if (still.tag.isEmpty()) "" else "_${still.tag}"
        val jpeg = write("$base.jpg", "image/jpeg", still.timestampMillis) { out -> out.write(still.jpeg) }
        if (still.needsExifOrientation) setExifOrientation(jpeg, still.orientation)
        val dng = still.raw?.let { raw ->
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

    private fun write(name: String, mime: String, timeMillis: Long, body: (java.io.OutputStream) -> Unit): Uri {
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

    private fun exifOrientation(degrees: Int): Int = when (degrees) {
        90 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90
        180 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180
        270 -> androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270
        else -> androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }
}
