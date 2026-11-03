package app.plexhdr.shield.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

object C {
    val bg = Color(0xFF0B0B0F)
    val surface = Color(0xFF1C1C22)
    val surfaceHi = Color(0xFF2C2C34)
    val text = Color(0xFFF5F5F7)
    val dim = Color(0xFFA1A1AA)
    val faint = Color(0xFF6B6B75)
    val accent = Color(0xFFE5A00D)   // Plex orange
    val focus = Color(0xFFFFFFFF)
    val good = Color(0xFF30D158)
    val warn = Color(0xFFFFB340)
    val bad = Color(0xFFFF453A)
}

object D {
    val gutter = 56.dp
    val posterW = 140.dp
    val posterH = 210.dp
    val wideW = 264.dp
    val wideH = 149.dp
}

/**
 * The layout is drawn on a 1280×720 canvas, the same proportions as the Tizen and Apple TV apps.
 * Android TV reports 960×540 dp, which made every card, title and margin a third too big
 * (the home hero alone was taller than the screen). This scales dp and sp so the screen is
 * always 720 dp tall, whatever resolution or density the box is set to.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TvScale(content: @Composable () -> Unit) {
    val base = LocalDensity.current
    val metrics = androidx.compose.ui.platform.LocalContext.current.resources.displayMetrics
    val heightPx = minOf(metrics.widthPixels, metrics.heightPixels).toFloat()
    val scaled = Density(density = heightPx / CANVAS_HEIGHT, fontScale = base.fontScale)
    // On Android TV, Compose scrolls every focused item to 30 % from the top of its list. That pushed the home
    // hero, the detail page and the rows up under the top bar. Scroll only as far as needed, keeping a margin
    // for the top bar, like the Apple TV and Tizen apps.
    val lead = with(scaled) { 96.dp.toPx() }
    val trail = with(scaled) { 40.dp.toPx() }
    val spec = remember(lead, trail) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val margin = if (containerSize > lead * 4) lead else 0f
                val end = offset + size
                return when {
                    offset >= margin && end <= containerSize - trail -> 0f      // already in view
                    size > containerSize - margin - trail -> offset - margin   // bigger than the view: show its start
                    offset < margin -> offset - margin
                    else -> end - (containerSize - trail)
                }
            }
        }
    }
    CompositionLocalProvider(LocalDensity provides scaled, LocalBringIntoViewSpec provides spec, content = content)
}

private const val CANVAS_HEIGHT = 720f
