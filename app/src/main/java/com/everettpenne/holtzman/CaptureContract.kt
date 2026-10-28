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

    /** True when [intent] is a capture request for video; anything else is a photo request. */
    fun wantsVideo(intent: Intent?): Boolean = intent?.getStringExtra(EXTRA_MODE) == MODE_VIDEO

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
