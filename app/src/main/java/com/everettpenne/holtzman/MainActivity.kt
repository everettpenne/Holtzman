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
        intent?.getStringExtra("dbg")?.let { com.everettpenne.holtzman.save.DebugFlags.raw = it }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent { HoltzmanRoot() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        com.everettpenne.holtzman.save.DebugFlags.raw = intent.getStringExtra("dbg") ?: ""
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
