package com.everettpenne.holtzman

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.everettpenne.holtzman.ui.HoltzmanRoot

/**
 * The camera when another app asked for a picture (see [CaptureContract]): the same screen, but the first photo or video taken is handed back and
 * the screen closes. A separate activity from [MainActivity] (which is singleTop in its own task) so the result goes to the caller.
 */
class CaptureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            HoltzmanRoot(startInVideo = CaptureContract.wantsVideo(intent), onCaptured = { uri, mime ->
                setResult(Activity.RESULT_OK, CaptureContract.result(uri, mime))
                finish()
            })
        }
    }
}
