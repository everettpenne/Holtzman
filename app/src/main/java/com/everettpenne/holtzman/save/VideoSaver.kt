package com.everettpenne.holtzman.save

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.everettpenne.holtzman.camera.captureBaseName
import java.util.TimeZone

/** A video being written: its place in the media library and the file the recorder writes into. */
class PendingVideo(val uri: Uri, val descriptor: ParcelFileDescriptor)

/**
 * Where recordings go: Movies/Holtzman through MediaStore, pending (invisible to other apps) until the recording finishes, and removed if it fails
 * or is too short to be a video. Nothing in the media library is half-written.
 */
class VideoSaver(private val resolver: ContentResolver) {
    fun begin(timeMillis: Long = System.currentTimeMillis()): PendingVideo {
        val name = captureBaseName(timeMillis, TimeZone.getDefault().getOffset(timeMillis).toLong()).replace("HOLTZ_", "HOLTZ_VID_") + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Holtzman")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("The media library would not make $name.")
        val fd = resolver.openFileDescriptor(uri, "w") ?: run { resolver.delete(uri, null, null); throw java.io.IOException("Could not open $name for writing.") }
        return PendingVideo(uri, fd)
    }

    /** Finishes a recording: shown to other apps when [ok], deleted when not. */
    fun finish(video: PendingVideo, ok: Boolean) {
        runCatching { video.descriptor.close() }
        if (ok) resolver.update(video.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        else runCatching { resolver.delete(video.uri, null, null) }
    }
}
