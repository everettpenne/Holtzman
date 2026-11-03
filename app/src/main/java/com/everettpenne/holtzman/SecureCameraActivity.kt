package com.everettpenne.holtzman

/**
 * The camera opened while the phone is locked (STILL_IMAGE_CAMERA_SECURE: the double press of the power button, the lock screen's camera button). Whoever holds a locked
 * phone can take pictures, as with any camera, but must not see the owner's: the screen shows no thumbnail of an older photo, the viewer holds only what was shot here,
 * and nothing is shared or handed to another app (which would show over the lock screen too, or ask to unlock). It closes as soon as it leaves the screen, so it is never
 * found again over the lock screen later.
 */
class SecureCameraActivity : MainActivity() {
    override val locked: Boolean = true

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }
}
