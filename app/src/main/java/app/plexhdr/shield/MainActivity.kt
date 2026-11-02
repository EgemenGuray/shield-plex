package app.plexhdr.shield

import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.plexhdr.shield.ui.AppRoot
import app.plexhdr.shield.ui.Intro
import kotlin.math.abs
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AppRoot() }
    }

    /** While the startup intro plays, any button skips it and goes no further. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (Intro.showing) {
            if (event.action == KeyEvent.ACTION_DOWN) Intro.skip()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        App.memory.flush()
    }

    /**
     * Switches the HDMI output to the video's frame rate (and optionally resolution), like the Shield's own "Match content
     * frame rate" does for apps that ask. 23.976 fps plays at 23.976 Hz, 25 at 50 Hz if there's no 25 Hz mode, and so on.
     */
    fun matchDisplay(fps: Float, width: Int, height: Int, matchRate: Boolean, matchResolution: Boolean) {
        if (!matchRate && !matchResolution) return
        val display: Display = window.decorView.display ?: return
        val current = display.mode
        val modes = display.supportedModes.toList()
        val sizeOk = { m: Display.Mode ->
            if (matchResolution && width > 0) {
                // Smallest mode that still holds the video; 4K content on a 4K mode, 1080p on 1080p.
                m.physicalWidth >= width.coerceAtMost(current.physicalWidth) && m.physicalHeight >= height.coerceAtMost(current.physicalHeight)
            } else {
                m.physicalWidth == current.physicalWidth && m.physicalHeight == current.physicalHeight
            }
        }
        val candidates = modes.filter(sizeOk).ifEmpty { listOf(current) }
        val bestSize = candidates.minByOrNull { it.physicalWidth * it.physicalHeight }!!
        val sameSize = candidates.filter { it.physicalWidth == bestSize.physicalWidth && it.physicalHeight == bestSize.physicalHeight }
        val chosen = if (matchRate && fps > 1) {
            // Prefer an exact rate, then the smallest exact multiple (e.g. 50 Hz for 25 fps), else keep the current rate.
            sameSize.filter { m ->
                val ratio = m.refreshRate / fps
                ratio >= 0.999f && abs(ratio - ratio.roundToInt()) < 0.002f * ratio
            }.minByOrNull { it.refreshRate } ?: sameSize.minByOrNull { abs(it.refreshRate - current.refreshRate) }
        } else {
            sameSize.minByOrNull { abs(it.refreshRate - current.refreshRate) }
        } ?: return
        if (chosen.modeId == current.modeId) return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = chosen.modeId }
    }

    fun restoreDisplay() {
        if (window.attributes.preferredDisplayModeId == 0) return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = 0 }
    }
}
