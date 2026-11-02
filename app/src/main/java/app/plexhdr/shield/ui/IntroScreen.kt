package app.plexhdr.shield.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import app.plexhdr.shield.R
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Startup intro: in the dark the light in the corner of the L ignites, the Lumen chime strikes, the light spills along
 * the L, then the camera flies into the light and the app fades in. 3.6 s; any remote button skips it.
 * Same timeline as the Apple app (IntroView.swift) and Tizen (app/js/intro.js); the chime file is shared.
 */
object IntroTimeline {
    const val DURATION = 3.6f
    const val STRIKE = 0.95f
    private const val ZOOM0 = 2.4f
    private const val ZOOM1 = 3.2f
    private const val FADE0 = 3.05f

    class State(
        val ignite: Float, val flare: Float, val radius: Float, val glow: Float,
        val zoom: Float, val pan: Float, val wash: Float, val fade: Float,
    )

    private fun clamp(x: Float) = x.coerceIn(0f, 1f)
    private fun smooth(a: Float, b: Float, x: Float): Float { val v = clamp((x - a) / (b - a)); return v * v * (3 - 2 * v) }
    private fun easeOut(x: Float) = 1 - (1 - clamp(x)).pow(3)
    private fun easeIn(x: Float) = clamp(x).pow(3)

    fun state(t: Float): State {
        val ignite = smooth(0.35f, STRIKE, t)
        val flare = if (t < 0.5f) 0f else exp(-((t - STRIKE) / 0.22f).pow(2))
        val spill = easeOut((t - 0.55f) / 1.35f)
        val breathe = if (t > 1.9f && t < ZOOM0) 0.06f * sin((t - 1.9f) / 0.5f * Math.PI.toFloat()) else 0f
        val z = clamp((t - ZOOM0) / (ZOOM1 - ZOOM0))
        return State(
            ignite = ignite,
            flare = flare,
            radius = 3 + 61 * spill,
            glow = min(1f, ignite * (0.85f + 0.55f * flare) + breathe),
            zoom = exp(ln(70f) * easeIn(z)),
            pan = easeOut(z * 1.4f),
            wash = 0.5f * smooth(0.45f, 1f, z),
            fade = 1 - smooth(FADE0, DURATION, t),
        )
    }
}

/** Whether the intro is on screen; MainActivity routes every key to [skip] while it is. */
object Intro {
    var showing by mutableStateOf(false)
    internal var onSkip: (() -> Unit)? = null
    fun skip() { onSkip?.invoke() }
}

/** The chime outlives the intro so its tail rings out under the app. */
private object IntroChime {
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    fun play(context: Context) {
        release()
        player = MediaPlayer.create(
            context, R.raw.lumen_chime,
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build(),
            0,
        )?.apply {
            setOnCompletionListener { release() }
            start()
        }
    }

    /** Skipped: fade out over ~0.4 s instead of cutting. */
    fun fadeOut() {
        val p = player ?: return
        for (i in 1..8) main.postDelayed({ if (player === p) runCatching { p.setVolume(1 - i / 8f, 1 - i / 8f) } }, i * 50L)
        main.postDelayed({ if (player === p) release() }, 450L)
    }

    private fun release() { player?.let { runCatching { it.release() } }; player = null }
}

private val markPath = Path().apply {
    moveTo(33f, 16f); lineTo(33f, 62f)
    quadraticTo(33f, 79f, 50f, 79f); lineTo(86f, 79f)
}

private fun rgb(r: Int, g: Int, b: Int, a: Float) = Color(r, g, b, (a.coerceIn(0f, 1f) * 255).toInt())

@Composable
fun IntroScreen(playSound: Boolean, onFinish: () -> Unit) {
    val context = LocalContext.current
    var t by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    val finish = remember {
        { skipped: Boolean ->
            if (!done) {
                done = true
                Intro.showing = false
                Intro.onSkip = null
                if (skipped && playSound) IntroChime.fadeOut()
                onFinish()
            }
        }
    }
    LaunchedEffect(Unit) {
        Intro.showing = true
        Intro.onSkip = { finish(true) }
        if (playSound) IntroChime.play(context)
        val t0 = withFrameNanos { it }
        while (!done && t < IntroTimeline.DURATION) {
            withFrameNanos { t = (it - t0) / 1e9f }
        }
        finish(false)
    }
    Canvas(Modifier.fillMaxSize().graphicsLayer { alpha = IntroTimeline.state(t).fade }) { drawIntro(t) }
}

private fun DrawScope.drawIntro(t: Float) {
    val s = IntroTimeline.state(t)
    val w = size.width; val h = size.height
    drawRect(Color.Black)
    val unit = min(w, h) * 0.0058f
    val light = Offset(33f, 79f)       // light source: the corner of the L
    val focus = Offset(38f, 74f)       // zoom target: brightest point on the bend
    val restX = w / 2 + (focus.x - 59.5f) * unit
    val restY = h / 2 + (focus.y - 47.5f) * unit
    withTransform({
        translate(restX + (w / 2 - restX) * s.pan, restY + (h / 2 - restY) * s.pan)
        scale(unit * s.zoom, unit * s.zoom, pivot = Offset.Zero)
        translate(-focus.x, -focus.y)
    }) {
        if (s.glow > 0.001f) {
            drawCircle(
                Brush.radialGradient(
                    0f to rgb(255, 207, 138, 0.85f * s.glow), 0.4f to rgb(255, 154, 77, 0.3f * s.glow), 1f to rgb(255, 122, 61, 0f),
                    center = Offset(36f, 75f), radius = 28f + 10f * s.flare,
                ),
                radius = 40f, center = Offset(36f, 75f),
            )
        }
        if (s.ignite > 0.001f) {
            val a = s.ignite
            drawPath(
                markPath,
                Brush.radialGradient(
                    0f to rgb(255, 255, 255, a), 0.22f to rgb(255, 230, 184, a), 0.55f to rgb(255, 154, 77, a), 1f to rgb(255, 90, 61, 0f),
                    center = light, radius = s.radius,
                ),
                style = Stroke(width = 13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        if (s.flare > 0.01f) {
            drawRect(
                Brush.radialGradient(
                    0f to rgb(255, 240, 215, 0.55f * s.flare), 0.15f to rgb(255, 190, 120, 0.25f * s.flare), 1f to rgb(255, 120, 60, 0f),
                    center = light, radius = 70f,
                ),
                topLeft = Offset(light.x - 70f, light.y - 70f), size = Size(140f, 140f), blendMode = BlendMode.Plus,
            )
            // A thin horizontal streak, like light leaking past the edge of a curtain.
            drawRect(
                Brush.horizontalGradient(
                    0f to rgb(255, 170, 100, 0f), 0.5f to rgb(255, 236, 205, 0.5f * s.flare), 1f to rgb(255, 170, 100, 0f),
                    startX = light.x - 90f, endX = light.x + 90f,
                ),
                topLeft = Offset(light.x - 90f, light.y - 0.9f), size = Size(180f, 1.8f), blendMode = BlendMode.Plus,
            )
        }
    }
    if (s.wash > 0.001f) drawRect(rgb(255, 228, 190, s.wash))
}
