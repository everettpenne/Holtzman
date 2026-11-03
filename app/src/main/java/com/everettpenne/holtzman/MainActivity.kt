package com.everettpenne.holtzman

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.view.KeyEvent
import com.everettpenne.holtzman.ui.HoltzmanRoot
import com.everettpenne.holtzman.ui.ShutterKeys

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug switches come in with the launch intent (adb shell am start --es dbg ...). Only a debug build takes them: the activity is exported, so in a release build any app
        // could otherwise turn on, say, keepraw (every raw burst kept, a few hundred MB each) and fill the phone.
        if (BuildConfig.DEBUG) intent?.getStringExtra("dbg")?.let { com.everettpenne.holtzman.save.DebugFlags.raw = it }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent { HoltzmanRoot(startInVideo = CaptureContract.wantsVideo(intent)) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) com.everettpenne.holtzman.save.DebugFlags.raw = intent.getStringExtra("dbg") ?: ""
    }

    /** The volume keys take the picture, as on a camera; the key's repeat while held is ignored so one press is one shot. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (event.repeatCount == 0) ShutterKeys.press()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) true else super.onKeyUp(keyCode, event)
}
