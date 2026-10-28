package com.everettpenne.holtzman.save

/**
 * Switches that turn single pipeline stages off, so a picture with a problem can be taken again with one stage out and the cause found by elimination.
 * Set from outside the app with `adb shell am start -n <package>/com.everettpenne.holtzman.MainActivity --es dbg "noraw,noltm"`; empty means everything on.
 * Names: oldweights (the earlier frame-weight rule), noraw (no spatial denoise of the merged mosaic), noclip (no clipped-highlight rule), noltm (no local
 * tone mapping), noauto (the fixed tone curve instead of the adaptive one), nofinish (the plain sharpen pass instead of the noise-aware finish), nograin.
 */
object DebugFlags {
    @Volatile var raw: String = ""
    fun on(name: String): Boolean = raw.split(',').any { it.trim() == name }
}
