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

open class MainActivity : ComponentActivity() {
    /** Whether the camera is on the lock screen: see [SecureCameraActivity]. */
    protected open val locked: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Debug switches come in with the launch intent (adb shell am start --es dbg ...). Only a debug build takes them: the activity is exported, so in a release build any app
        // could otherwise turn on, say, keepraw (every raw burst kept, a few hundred MB each) and fill the phone.
        if (BuildConfig.DEBUG) intent?.getStringExtra("dbg")?.let { com.everettpenne.holtzman.save.DebugFlags.raw = it }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        setContent { HoltzmanRoot(startInVideo = CaptureContract.wantsVideo(intent), locked = locked) }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) com.everettpenne.holtzman.save.DebugFlags.raw = intent.getStringExtra("dbg") ?: ""
    }

    // The screen stays on while the camera is used, and goes back to the phone's own timeout after two minutes without a touch. Holtzman never held it on: the screen
    // dimmed and slept while framing a shot, and on the lock screen (where the timeout is a few seconds) the camera opened by the power button was gone before it was used.
    /** How long the screen is held on after the last touch. */
    protected open val awakeMs: Long = 120_000L
    private val idle = android.os.Handler(android.os.Looper.getMainLooper())
    private val letSleep = Runnable { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    private fun stayAwake() { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); idle.removeCallbacks(letSleep); idle.postDelayed(letSleep, awakeMs) }
    override fun onResume() { super.onResume(); stayAwake() }
    override fun onPause() { super.onPause(); idle.removeCallbacks(letSleep) }
    override fun onUserInteraction() { super.onUserInteraction(); stayAwake() }

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
