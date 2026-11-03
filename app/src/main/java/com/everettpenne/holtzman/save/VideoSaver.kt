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
            put(MediaStore.MediaColumns.DATE_TAKEN, timeMillis)   // the gallery sorts and groups by the moment of the shot, not of the write
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("The media library would not make $name.")
        val fd = resolver.openFileDescriptor(uri, "w") ?: run { resolver.delete(uri, null, null); throw java.io.IOException("Could not open $name for writing.") }
        return PendingVideo(uri, fd)
    }

    /** The newest recording the app saved before (the thumbnail beside the shutter in video mode, on opening); null when there are none. Only the app's own files are visible to it. */
    fun latestVideo(): Uri? = runCatching {
        resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf(Environment.DIRECTORY_MOVIES + "/Holtzman%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) else null }
    }.onFailure { android.util.Log.w("HoltzmanSave", "could not find the latest video", it) }.getOrNull()

    /** Finishes a recording: shown to other apps when [ok], deleted when not. */
    fun finish(video: PendingVideo, ok: Boolean) {
        runCatching { video.descriptor.close() }
        if (ok) resolver.update(video.uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        else runCatching { resolver.delete(video.uri, null, null) }
    }

    /** The app's own videos, newest first (at most 300), as the media library lists them; empty when there are none. Only the app's own files are visible to it. */
    fun listVideos(): List<android.net.Uri> = runCatching {
        val args = android.os.Bundle().apply {
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?")
            putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(Environment.DIRECTORY_MOVIES + "/Holtzman%"))
            putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 300)
        }
        resolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { c -> buildList { while (c.moveToNext()) add(android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0))) } } ?: emptyList()
    }.getOrDefault(emptyList())
}
