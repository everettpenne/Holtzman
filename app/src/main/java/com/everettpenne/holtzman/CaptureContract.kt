package com.everettpenne.holtzman

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * How another app asks Holtzman for a picture, and gets one back: start [ACTION] for a result; [EXTRA_MODE] is "photo" (the default) or "video".
 * The person takes the shot with the shutter (nothing is captured behind their back); Holtzman saves it to the media library as usual and returns
 * its content address, readable by the caller for as long as the result intent lives. Cancelling (Back) returns RESULT_CANCELED.
 *
 *     val take = registerForActivityResult(StartActivityForResult()) { r -> r.data?.data?.let { /* a content:// uri */ } }
 *     take.launch(Intent("com.everettpenne.holtzman.action.CAPTURE").putExtra("com.everettpenne.holtzman.extra.MODE", "photo"))
 */
object CaptureContract {
    const val ACTION = "com.everettpenne.holtzman.action.CAPTURE"
    const val EXTRA_MODE = "com.everettpenne.holtzman.extra.MODE"
    const val MODE_PHOTO = "photo"
    const val MODE_VIDEO = "video"

    /** True when [intent] is a capture request for video; anything else is a photo request. Android's own VIDEO_CAPTURE and VIDEO_CAMERA ask for video too. */
    fun wantsVideo(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_MODE) == MODE_VIDEO || intent?.action == android.provider.MediaStore.ACTION_VIDEO_CAPTURE || intent?.action == android.provider.MediaStore.INTENT_ACTION_VIDEO_CAMERA

    /**
     * Hands a finished capture back to the caller. Android's own requests (IMAGE_CAPTURE, VIDEO_CAPTURE) that name an output (EXTRA_OUTPUT) get the file copied there, as
     * every camera app does; anything else gets the file's address with read permission. The shot stays in the gallery either way.
     */
    fun deliver(context: Context, request: Intent?, uri: Uri, mime: String): Intent {
        val out = request?.let { if (android.os.Build.VERSION.SDK_INT >= 33) it.getParcelableExtra(android.provider.MediaStore.EXTRA_OUTPUT, Uri::class.java) else @Suppress("DEPRECATION") it.getParcelableExtra<Uri>(android.provider.MediaStore.EXTRA_OUTPUT) }
        if (out != null) runCatching {
            context.contentResolver.openInputStream(uri)?.use { input -> context.contentResolver.openOutputStream(out, "wt")?.use { output -> input.copyTo(output) } }
            return Intent().setData(out)
        }
        return result(uri, mime)
    }

    /** Opens one photo or video in whatever gallery the person uses; false when no app can show it. */
    fun view(context: Context, uri: Uri, mime: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).apply { clipData = ClipData.newRawUri("", uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) })
    }.isSuccess

    /** Opens the media library's list of photos (or videos) in a gallery app, for people without Sietch; false when no app offers one. */
    fun showLibrary(context: Context, video: Boolean): Boolean = runCatching {
        val (uri, type) = if (video) android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI to "vnd.android.cursor.dir/video" else android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI to "vnd.android.cursor.dir/image"
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    /** The result a finished capture returns: the file's address, with read permission for the caller. */
    fun result(uri: Uri, mime: String): Intent = Intent().setDataAndType(uri, mime).apply {
        clipData = ClipData.newRawUri("", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Opens the folder of Holtzman's photos in Sietch, if Sietch is installed. Returns false when it is not. */
    fun showInSietch(context: Context, folderPath: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("sietch://browse?path=" + Uri.encode(folderPath))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
