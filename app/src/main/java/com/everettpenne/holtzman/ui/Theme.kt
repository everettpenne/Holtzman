package com.everettpenne.holtzman.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.everettpenne.glass.GlassColors
import com.everettpenne.glass.GlassTheme

/** Holtzman's colours; the theme, type scale and glass kit come from the shared Glass library. A camera is always dark: the page is black. */
object Holtzman {
    /** Warm amber, as the shutter ring and the selected mode: the one colour on a black screen. */
    val accent = Color(0xFFE8A04C)
    val red = Color(0xFFFF453A)
    val green = Color(0xFF34C759)
}

@Composable
fun HoltzmanTheme(content: @Composable () -> Unit) = GlassTheme(GlassColors(accent = Holtzman.accent, red = Holtzman.red, green = Holtzman.green), content)
