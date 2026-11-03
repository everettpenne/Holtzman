package com.everettpenne.holtzman

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle

/**
 * The camera opened while the phone is locked (STILL_IMAGE_CAMERA_SECURE: the double press of the power button, the lock screen's camera button). Whoever holds a locked
 * phone can take pictures, as with any camera, but must not see the owner's: the screen shows no thumbnail of an older photo, the viewer holds only what was shot here,
 * and nothing is shared or handed to another app (which would show over the lock screen too, or ask to unlock).
 *
 * It closes the moment the screen goes off (the power button, or the timeout below), so the next press of the power button shows the lock screen, not the camera.
 * Before (2026-11-03) it closed only in onStop: a showWhenLocked activity on top of the lock screen is not reliably stopped when the screen goes off, so the camera
 * stayed open behind the dark screen (draining the battery) and came back over the lock screen on every wake, in the way of unlocking. android:turnScreenOn is gone
 * too: the power button's double press wakes the screen itself, and the attribute only let the activity light the screen again on its own.
 */
class SecureCameraActivity : MainActivity() {
    override val locked: Boolean = true

    /** On the lock screen the screen is held on for 30 s after the last touch, not two minutes: then it goes off and the camera closes. */
    override val awakeMs: Long = 30_000L

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onDestroy() {
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}
