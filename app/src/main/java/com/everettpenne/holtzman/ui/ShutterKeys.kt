package com.everettpenne.holtzman.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** The volume keys as a shutter: the activity reports a press, the camera screen listens. One press, one shot. */
object ShutterKeys {
    private val presses = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val flow: SharedFlow<Unit> = presses
    fun press() { presses.tryEmit(Unit) }
}
