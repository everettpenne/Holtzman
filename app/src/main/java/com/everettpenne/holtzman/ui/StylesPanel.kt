package com.everettpenne.holtzman.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.everettpenne.glass.IosType
import com.everettpenne.holtzman.save.BurstRender.LookPreset
import com.everettpenne.holtzman.save.BurstRender.TextureStyle

/**
 * The Photographic Styles card, after the iPhone's two steps. First a row of style cards (each the live picture in that style) under the name of the chosen
 * style, and CUSTOMISE. Then PALETTE (the square pad: across is Color, cool to warm; up and down is Tone, darker to lighter; and a tick slider for how much
 * of the style) or TEXTURE (Standard, Soft Skin, Glow, Film and Grain, and a tick slider for how much, applied when the photo is made).
 *
 * [color] and [tone] are the dot's place, -1..1 (tone up is positive). The pad is the same control as the Warmth and Shadows values the screen keeps.
 */
@Composable
fun StylesCard(
    style: LookPreset, onStyle: (LookPreset) -> Unit,
    intensity: Float, onIntensity: (Float) -> Unit,
    color: Float, tone: Float, onPalette: (Float, Float) -> Unit,
    textureStyle: TextureStyle, onTextureStyle: (TextureStyle) -> Unit, textureAmount: Float, onTextureAmount: (Float) -> Unit,
    thumb: Bitmap?, onBack: () -> Unit,
    modifier: Modifier = Modifier,
    startOnTexture: Boolean = false,
) {
    var customise by remember { mutableStateOf(startOnTexture) }
    var tab by remember { mutableStateOf(if (startOnTexture) 1 else 0) }
    CardFrame(modifier) {
        if (!customise) {
            CardHeader(onBack, center = { CardTitle(style.label + if (textureStyle != TextureStyle.STANDARD) " + ${textureStyle.label}" else "") })
            Box(Modifier.align(Alignment.CenterHorizontally).size(4.dp).background(Color.White, CircleShape))
            StyleCarousel(style, onStyle, intensity, tone, thumb)
            Text("CUSTOMISE", style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp), color = Color.White.copy(alpha = .9f),
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(CircleShape).clickable { customise = true; tab = 0 }.padding(horizontal = 16.dp, vertical = 8.dp))
        } else {
            CardHeader({ customise = false }, trailing = if (tab == 1) ({
                Icon(Icons.Rounded.RestartAlt, "Reset texture", tint = Color.White, modifier = Modifier.size(24.dp).clickable { onTextureStyle(TextureStyle.STANDARD); onTextureAmount(0.5f) })
            }) else null, center = {
                Row(Modifier.clip(CircleShape).background(Color.White.copy(alpha = .12f)).padding(3.dp)) {
                    listOf("PALETTE", "TEXTURE").forEachIndexed { i, l ->
                        Text(l, style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp), color = if (i == tab) CameraYellow else Color.White.copy(alpha = .85f),
                            modifier = Modifier.clip(CircleShape).background(if (i == tab) Color.White.copy(alpha = .2f) else Color.Transparent).clickable { tab = i }.padding(horizontal = 14.dp, vertical = 7.dp))
                    }
                }
            })
            if (tab == 0) {
                Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    PalettePad(color, tone, onPalette, Modifier.size(98.dp))
                    TickSlider(intensity, onIntensity, Modifier.width(220.dp))
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
                        TextureStyle.entries.forEach { t ->
                            if (t == TextureStyle.GRAIN) Box(Modifier.padding(top = 8.dp).size(width = 1.dp, height = 34.dp).background(Color.White.copy(alpha = .25f)))
                            TextureButton(t, t == textureStyle) { onTextureStyle(t) }
                        }
                    }
                    TickSlider(textureAmount, onTextureAmount, Modifier.width(220.dp), min = "", max = "")
                    Text("Texture is applied after taking a photo.", style = IosType.caption1, color = Color.White.copy(alpha = .5f))
                }
            }
        }
    }
}

/** The row of style cards, the iPhone's way: the chosen one is in the middle, the row snaps to a card when you let go and that card becomes the style. */
@Composable
private fun StyleCarousel(style: LookPreset, onStyle: (LookPreset) -> Unit, intensity: Float, tone: Float, thumb: Bitmap?) {
    val entries = LookPreset.entries
    val state = androidx.compose.foundation.lazy.rememberLazyListState(initialFirstVisibleItemIndex = entries.indexOf(style).coerceAtLeast(0))
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val fling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        val side = (maxWidth - 58.dp) / 2
        androidx.compose.foundation.lazy.LazyRow(state = state, flingBehavior = fling, contentPadding = PaddingValues(horizontal = side), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(entries) { i, p ->
                StyleCard(p, p == style, intensity, tone, thumb) { onStyle(p); scope.launch { state.animateScrollToItem(i) } }
            }
        }
    }
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.isScrollInProgress }.collect { moving ->
            if (!moving) entries.getOrNull(state.firstVisibleItemIndex)?.let { if (it != style) onStyle(it) }
        }
    }
}

private fun signedPercent(v: Float): String { val n = Math.round(v * 100); return if (n > 0) "+$n" else "$n" }

/** One texture: a round button with its name under it; the chosen one is a white disc with a dark icon. */
@Composable
private fun TextureButton(t: TextureStyle, selected: Boolean, onClick: () -> Unit) {
    val icon = when (t) { TextureStyle.STANDARD -> Icons.Rounded.NotInterested; TextureStyle.SOFT_SKIN -> Icons.Rounded.Person; TextureStyle.GLOW -> Icons.Rounded.BlurOn; TextureStyle.FILM -> Icons.Rounded.Movie; TextureStyle.GRAIN -> Icons.Rounded.Grain }
    Column(Modifier.width(62.dp).clickable(onClick = onClick).semantics { contentDescription = "Texture ${t.label}" }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(50.dp).clip(CircleShape).background(if (selected) Color.White else Color.White.copy(alpha = .13f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = if (selected) Color.Black else Color.White, modifier = Modifier.size(24.dp))
        }
        Text(t.label.uppercase(), maxLines = 2, textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = IosType.caption2.copy(fontSize = 9.sp, lineHeight = 11.sp), color = Color.White.copy(alpha = if (selected) 1f else .7f), modifier = Modifier.heightIn(min = 22.dp))
    }
}

/** One style: the live picture with the style's colours on it; the name is in the card's title, so the card is the picture alone. */
@Composable
private fun StyleCard(p: LookPreset, selected: Boolean, intensity: Float, tone: Float, thumb: Bitmap?, onClick: () -> Unit) {
    val matrix = lookMatrix(p, if (selected) intensity else 1f, if (selected) tone else 0f)
    Box(Modifier.size(width = 58.dp, height = if (selected) 82.dp else 74.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF3A3A3C)).clickable(onClick = onClick)
        .border(if (selected) 2.dp else 0.dp, if (selected) CameraYellow else Color.Transparent, RoundedCornerShape(12.dp)).semantics { contentDescription = "Style ${p.label}" }) {
        if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
    }
}

/**
 * The Palette: a dark square of small dots with a colour glow (cool and teal on the left, warm and amber on the right, lighter toward the top), and a white dot
 * you drag, or tap to move it to. Across is Color, up and down is Tone. Double-tap returns it to the middle.
 */
@Composable
fun PalettePad(color: Float, tone: Float, onChange: (Float, Float) -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.clip(RoundedCornerShape(14.dp)).semantics { contentDescription = "Palette: color and tone" }
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onChange(0f, 0f) }, onTap = { p -> onChange((p.x / size.width * 2f - 1f).coerceIn(-1f, 1f), -(p.y / size.height * 2f - 1f).coerceIn(-1f, 1f)) }) }
        .pointerInput(Unit) { detectDragGestures(onDragStart = { p -> onChange((p.x / size.width * 2f - 1f).coerceIn(-1f, 1f), -(p.y / size.height * 2f - 1f).coerceIn(-1f, 1f)) }) { change, _ ->
            change.consume(); val p = change.position; onChange((p.x / size.width * 2f - 1f).coerceIn(-1f, 1f), -(p.y / size.height * 2f - 1f).coerceIn(-1f, 1f)) } }) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Color(0xFF17181B))
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF2F7FB0).copy(alpha = .75f), Color(0x002F7FB0), Color(0x00E58A2E), Color(0xFFE58A2E).copy(alpha = .8f))))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.Transparent, Color.Black.copy(alpha = .5f))))
            val n = 9
            for (i in 0 until n) for (j in 0 until n) drawCircle(Color.White.copy(alpha = .3f), 1.1.dp.toPx(), Offset((i + 0.5f) / n * size.width, (j + 0.5f) / n * size.height))
            val c = Offset((color + 1f) / 2f * size.width, (1f - tone) / 2f * size.height)
            drawCircle(Color.Black.copy(alpha = .3f), 9.dp.toPx(), c)
            drawCircle(Color.White, 7.dp.toPx(), c)
        }
    }
}

/**
 * "Choose a Style", the iPhone's full-screen first step (Settings > Camera > Photographic Styles): a close button, the title and a line of help, then a
 * two-by-two of pictures in the style on show, which you swipe through; the style's name under it, a blue "Customise Texture" and a grey "Keep ...".
 * The four pictures are the four corners of the live viewfinder, enlarged, standing in for the four faces the iPhone shows.
 */
@Composable
fun ChooseStyleScreen(style: LookPreset, onStyle: (LookPreset) -> Unit, intensity: Float, thumb: Bitmap?, onClose: () -> Unit, onCustomiseTexture: () -> Unit, modifier: Modifier = Modifier) {
    val entries = LookPreset.entries
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = entries.indexOf(style).coerceAtLeast(0), pageCount = { entries.size })
    androidx.compose.runtime.LaunchedEffect(pager) { androidx.compose.runtime.snapshotFlow { pager.settledPage }.collect { onStyle(entries[it]) } }
    val shown = entries[pager.currentPage]
    Column(modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth()) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color.White.copy(alpha = .16f)).clickable(onClick = onClose).semantics { contentDescription = "Close" }, contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Close, null, tint = Color.White) }
        }
        Text("Choose a Style", style = IosType.title3.copy(fontWeight = FontWeight.SemiBold), color = Color.White, modifier = Modifier.padding(top = 4.dp))
        Text("Swipe to select a Photographic Style, or customise the colour, tone and texture.", style = IosType.callout, color = Color.White.copy(alpha = .65f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), pageSpacing = 12.dp) { page ->
            val matrix = lookMatrix(entries[page], if (entries[page] == style) intensity else 1f)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in 0..1) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (col in 0..1) Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(Color(0xFF2C2C2E))) {
                        if (thumb != null) Image(thumb.asImageBitmap(), null, Modifier.matchParentSize().graphicsLayer { scaleX = 2f; scaleY = 2f; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(col.toFloat(), row.toFloat()) },
                            contentScale = ContentScale.Crop, colorFilter = matrix?.let { ColorFilter.colorMatrix(ColorMatrix(it)) })
                    }
                }
            }
        }
        Text(shown.label.uppercase(), style = IosType.caption1.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp), color = Color.White.copy(alpha = .9f), modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.padding(top = 4.dp, bottom = 12.dp).size(4.dp).background(Color.White, CircleShape))
        Text("Customise Texture", style = IosType.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().clip(CircleShape).background(Color(0xFF0A84FF)).clickable(onClick = onCustomiseTexture).padding(vertical = 15.dp))
        Text("Keep “${shown.label}”", style = IosType.body.copy(fontWeight = FontWeight.SemiBold), color = Color.White, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = .16f)).clickable(onClick = onClose).padding(vertical = 15.dp))
    }
}
