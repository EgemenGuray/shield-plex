package app.plexhdr.shield.player

import android.graphics.Typeface
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.Decide
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.services.Settings
import app.plexhdr.shield.ui.Badge
import app.plexhdr.shield.ui.C
import app.plexhdr.shield.ui.Choice
import app.plexhdr.shield.ui.Focusable
import app.plexhdr.shield.ui.Pill
import app.plexhdr.shield.ui.T
import app.plexhdr.shield.ui.WideCard
import app.plexhdr.shield.ui.formatTime
import app.plexhdr.shield.ui.plexImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(item: PlexItem, startMs: Long?, mediaIndex: Int?, audioId: Int?, subtitleId: Int?, onClose: () -> Unit) {
    val activity = LocalContext.current as android.app.Activity
    val pc = remember { PlayerController(activity, item, mediaIndex, audioId, subtitleId, onClose) }
    DisposableEffect(Unit) {
        pc.start(startMs)
        onDispose { pc.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pc.pause() }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(pc.panel) { if (pc.panel == null) runCatching { rootFocus.requestFocus() } }

    BackHandler {
        when {
            pc.panel != null -> pc.panel = null
            pc.scrubMs != null -> pc.cancelScrub()
            pc.upNext != null -> pc.dismissUpNext()
            else -> pc.close()
        }
    }

    val s = App.settings
    Box(
        Modifier.fillMaxSize().background(Color.Black)
            .focusRequester(rootFocus)
            .onPreviewKeyEvent { e ->
                if (pc.panel != null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        when {
                            pc.upNext != null -> pc.upNext?.let { pc.playItem(it) }
                            pc.introMarker != null -> pc.skipIntro()
                            pc.scrubMs != null -> pc.commitScrub()
                            else -> pc.togglePlay()
                        }
                        true
                    }
                    Key.DirectionLeft, Key.DirectionRight -> {
                        val dir = if (e.key == Key.DirectionLeft) -1 else 1
                        if (!pc.isPlaying || pc.scrubMs != null) pc.scrub(dir)
                        else pc.skip(if (dir < 0) -s.skipBack * 1000L else s.skipForward * 1000L)
                        true
                    }
                    Key.DirectionDown -> { pc.panel = PlayerController.Panel.Info; pc.showOverlay(false); true }
                    Key.DirectionUp -> { pc.showOverlay(autoHide = true); true }
                    Key.MediaPlayPause -> { pc.togglePlay(); true }
                    Key.MediaPlay -> { pc.play(); pc.hideOverlaySoon(); true }
                    Key.MediaPause -> { pc.pause(); true }
                    Key.MediaFastForward, Key.MediaNext -> { pc.jumpChapter(1); true }
                    Key.MediaRewind, Key.MediaPrevious -> { pc.jumpChapter(-1); true }
                    Key.Menu, Key.Info -> { pc.panel = PlayerController.Panel.Info; true }
                    else -> { pc.showOverlay(autoHide = true); false }
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    subtitleView?.visibility = View.GONE
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                }
            },
            update = { v ->
                if (v.player !== pc.player) v.player = pc.player
                v.resizeMode = when (pc.fit) {
                    PlayerController.Fit.Fit -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    PlayerController.Fit.Zoom -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    PlayerController.Fit.Stretch -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        // Subtitles, drawn by the app so external files can be shifted and every track styled the same way.
        AndroidView(
            factory = { ctx -> SubtitleView(ctx) },
            update = { v ->
                val edge = when (s.subtitleStyle) {
                    Settings.SubtitleStyle.Outline -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                    Settings.SubtitleStyle.Shadow -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
                    Settings.SubtitleStyle.Box -> CaptionStyleCompat.EDGE_TYPE_NONE
                }
                val bg = if (s.subtitleStyle == Settings.SubtitleStyle.Box) 0x99000000.toInt() else 0
                v.setStyle(CaptionStyleCompat(s.subtitleColor.argb, bg, 0, edge, android.graphics.Color.BLACK, Typeface.DEFAULT_BOLD))
                v.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * s.subtitleSize)
                v.setBottomPaddingFraction(s.subtitleBottom)
                v.setCues(pc.cues)
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (pc.buffering && pc.error == null) {
            Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(18.dp)) {
                T("Loading…", size = 18.sp)
            }
        }

        AnimatedVisibility(pc.overlayVisible || pc.panel != null || pc.scrubMs != null || !pc.isPlaying,
            Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Overlay(pc)
        }

        pc.introMarker?.let {
            if (pc.upNext == null) Box(Modifier.align(Alignment.BottomEnd).padding(end = 60.dp, bottom = 170.dp)) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(C.focus).padding(horizontal = 26.dp, vertical = 12.dp)) {
                    T("Skip Intro  (Select)", size = 17.sp, color = Color.Black, weight = FontWeight.SemiBold)
                }
            }
        }
        pc.upNext?.let { UpNextCard(pc, it, Modifier.align(Alignment.BottomEnd).padding(end = 60.dp, bottom = 160.dp)) }

        pc.panel?.let { Panel(pc, it, Modifier.align(Alignment.BottomCenter)) }

        pc.error?.let { err ->
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(C.surface).padding(30.dp)) {
                T("Couldn't play this", size = 24.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                T(err, Modifier.width(640.dp), size = 15.sp, color = C.dim)
                Spacer(Modifier.height(10.dp))
                T(pc.plan.note, Modifier.width(640.dp), size = 13.sp, color = C.faint)
                Spacer(Modifier.height(18.dp))
                val fr = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Pill("Close", { pc.close() }, focusRequester = fr)
                }
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            }
        }
    }
}

@Composable
private fun Overlay(pc: PlayerController) {
    val item = pc.item
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(340.dp)
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))
        Column(Modifier.padding(start = 60.dp, top = 40.dp)) {
            if (item.type == "episode") T("${item.grandparentTitle ?: ""} · ${item.episodeLabel}", size = 16.sp, color = C.dim)
            T(item.title, size = 28.sp, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Decide.badges(pc.plan).forEach { Badge(it) } }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 60.dp, vertical = 44.dp)) {
            val shown = pc.scrubMs ?: pc.timeMs
            pc.scrubMs?.let { scrub ->
                pc.bif?.image(scrub)?.let { bmp ->
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val frac = if (pc.durationMs > 0) scrub.toFloat() / pc.durationMs else 0f
                        val x = (maxWidth - 320.dp) * frac
                        Image(bmp.asImageBitmap(), null, Modifier.offset(x = x).size(320.dp, 180.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    }
                    Spacer(Modifier.height(10.dp))
                }
            }
            pc.currentChapter?.let { T(it.title, size = 15.sp, color = C.dim) }
            Spacer(Modifier.height(8.dp))
            Timeline(pc, shown)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                T(formatTime(shown), size = 15.sp)
                Spacer(Modifier.weight(1f))
                val endsAt = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(System.currentTimeMillis() + (pc.durationMs - shown)))
                T("Ends at $endsAt", size = 15.sp, color = C.dim)
                Spacer(Modifier.weight(1f))
                T("-" + formatTime(pc.durationMs - shown), size = 15.sp)
            }
            if (!pc.isPlaying && pc.panel == null) {
                Spacer(Modifier.height(10.dp))
                T(if (pc.scrubMs != null) "◀ ▶ scrub · Select to jump · Back to cancel" else "Paused · ◀ ▶ to scrub · ▼ for audio, subtitles and more",
                    size = 13.sp, color = C.faint)
            }
        }
    }
}

@Composable
private fun Timeline(pc: PlayerController, shown: Long) {
    val d = pc.durationMs.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp)) {
        val w = maxWidth
        Box(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center).clip(RoundedCornerShape(3.dp)).background(Color(0x55FFFFFF)))
        // Intro and credits segments
        pc.item.markers.forEach { m ->
            val start = w * (m.startMs.toFloat() / d)
            val len = w * ((m.endMs - m.startMs).toFloat() / d)
            Box(Modifier.offset(x = start).width(len).height(6.dp).align(Alignment.CenterStart).background(Color(0x55E5A00D)))
        }
        Box(Modifier.fillMaxWidth(pc.timeMs.toFloat() / d).height(6.dp).align(Alignment.CenterStart).clip(RoundedCornerShape(3.dp)).background(C.text))
        // Chapter ticks
        pc.chapters.drop(1).forEach { c ->
            Box(Modifier.offset(x = w * (c.startMs.toFloat() / d)).width(2.dp).fillMaxHeight().align(Alignment.CenterStart).background(Color(0xAA000000)))
        }
        if (pc.scrubMs != null) {
            Box(Modifier.offset(x = w * (shown.toFloat() / d) - 2.dp).width(4.dp).fillMaxHeight().align(Alignment.CenterStart).background(C.accent))
        }
    }
}

@Composable
private fun UpNextCard(pc: PlayerController, next: PlexItem, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xE61C1C22)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        app.plexhdr.shield.ui.Img(plexImage(next.thumb, 400, 225), Modifier.size(200.dp, 112.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.width(300.dp)) {
            T("Up Next in ${pc.upNextCountdown}", size = 14.sp, color = C.accent)
            T("${next.episodeLabel ?: ""} · ${next.title}", size = 18.sp, weight = FontWeight.SemiBold, maxLines = 2)
            Spacer(Modifier.height(6.dp))
            T("Select to play now · Back to keep watching", size = 12.sp, color = C.dim)
        }
    }
}

@Composable
private fun Panel(pc: PlayerController, panel: PlayerController.Panel, modifier: Modifier) {
    val first = remember(panel) { FocusRequester() }
    LaunchedEffect(panel) { runCatching { first.requestFocus() } }
    Column(modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xF2000000), Color(0xF2000000))))
        .padding(start = 60.dp, end = 60.dp, top = 40.dp, bottom = 36.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(PlayerController.Panel.values().size) { i ->
                val p = PlayerController.Panel.values()[i]
                Pill(p.title, { pc.panel = p }, selected = p == panel, onFocused = { pc.panel = p }, focusRequester = if (p == panel) first else null)
            }
        }
        Spacer(Modifier.height(18.dp))
        Box(Modifier.height(260.dp)) {
            when (panel) {
                PlayerController.Panel.Info -> InfoTab(pc)
                PlayerController.Panel.Chapters -> {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp), contentPadding = PaddingValues(vertical = 10.dp)) {
                        items(pc.chapters.size) { i ->
                            val c = pc.chapters[i]
                            WideCard(c.title, formatTime(c.startMs), plexImage(c.thumb, 480, 270), null, { pc.seekTo(c.startMs); pc.panel = null }, width = 240.dp)
                        }
                    }
                    if (pc.chapters.isEmpty()) T("This file has no chapters.", color = C.dim)
                }
                PlayerController.Panel.Audio -> {
                    val streams = pc.plan.part?.audioStreams ?: emptyList()
                    OptionList(streams.map { a ->
                        Choice(a.extendedDisplayTitle, Decide.bitstreamKey(a)?.let { k ->
                            if (App.settings.audioOutput == Settings.AudioOutput.Passthrough && (k in pc.caps.bitstream || (k == "eac3joc" && "eac3" in pc.caps.bitstream))) "Bitstream" else "Decoded to PCM"
                        }, a.id == pc.plan.audio?.id)
                    }) { i -> pc.chooseAudio(streams[i]) }
                }
                PlayerController.Panel.Subtitles -> SubtitlesTab(pc)
                PlayerController.Panel.Picture -> {
                    val fits = PlayerController.Fit.values()
                    val speeds = listOf(0.75f, 1f, 1.25f, 1.5f)
                    val choices = fits.map { Choice(it.label, null, it == pc.fit) } +
                        (if (!pc.plan.bitstream) speeds.map { Choice("Speed ${it}×", null, it == pc.speed) } else emptyList())
                    OptionList(choices) { i -> if (i < fits.size) pc.fit = fits[i] else pc.setPlaybackSpeed(speeds[i - fits.size]) }
                }
            }
        }
    }
}

@Composable
private fun OptionList(choices: List<Choice>, onPick: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(choices.size) { i ->
            val c = choices[i]
            Focusable({ onPick(i) }, Modifier.width(300.dp), RoundedCornerShape(12.dp), 1.04f, showBorder = false) { f ->
                Column(Modifier.fillMaxWidth().background(if (f) C.focus else if (c.selected) C.surfaceHi else C.surface).padding(16.dp)) {
                    T((if (c.selected) "✓ " else "") + c.label, size = 15.sp, color = if (f) Color.Black else C.text, maxLines = 2, weight = FontWeight.SemiBold)
                    c.detail?.let { T(it, size = 12.sp, color = if (f) Color.DarkGray else C.dim, maxLines = 2) }
                }
            }
        }
    }
}

@Composable
private fun InfoTab(pc: PlayerController) {
    LazyColumn {
        item {
            T(pc.plan.note, size = 15.sp, color = C.accent)
            pc.plan.warnings.forEach { T("⚠ $it", size = 13.sp, color = C.warn) }
            Spacer(Modifier.height(10.dp))
            T("Video: ${pc.videoInfo.ifEmpty { "—" }}", size = 14.sp, color = C.dim)
            T("Audio: ${pc.audioInfo.ifEmpty { "—" }}", size = 14.sp, color = C.dim)
            T("Source: ${pc.plan.media?.label ?: "—"}", size = 14.sp, color = C.dim)
            T("Engine: ${if (pc.plan.engine == Decide.Engine.Direct) "Direct play (original file)" else "Server converts the video to 4K, audio copied"}", size = 14.sp, color = C.dim)
            T("Shield bitstream formats: ${pc.caps.bitstream.sorted().joinToString(", ").ifEmpty { "none reported" }}", size = 14.sp, color = C.dim)
            if (pc.pcmFallback) T("Passthrough failed for this title; audio is decoded on the Shield.", size = 14.sp, color = C.warn)
        }
    }
}

@Composable
private fun SubtitlesTab(pc: PlayerController) {
    val subs = pc.plan.part?.subtitleStreams ?: emptyList()
    Column {
        val base = listOf(Choice("Off", null, pc.plan.subtitle == null && pc.onlineSubtitle == null)) +
            subs.map { Choice(it.extendedDisplayTitle, if (it.isExternal) "External" else if (Decide.isImageSub(it)) "Embedded image (PGS)" else "Embedded", it.id == pc.plan.subtitle?.id) } +
            (pc.onlineSubtitle?.let { listOf(Choice("Downloaded subtitle", it, true)) } ?: emptyList()) +
            listOf(Choice("Search online…", "OpenSubtitles (file hash first) and your server", false),
                Choice("Delay −0.1 s", "Now ${pc.subtitleDelayMs / 1000.0} s", false), Choice("Delay +0.1 s", "Embedded tracks: only positive delays", false))
        OptionList(base) { i ->
            val extraStart = 1 + subs.size + (if (pc.onlineSubtitle != null) 1 else 0)
            when {
                i == 0 -> pc.chooseSubtitle(null)
                i <= subs.size -> pc.chooseSubtitle(subs[i - 1])
                i < extraStart -> {}
                i == extraStart -> pc.searchOnline()
                i == extraStart + 1 -> pc.adjustSubtitleDelay(-100)
                else -> pc.adjustSubtitleDelay(100)
            }
        }
        pc.onlineStatus?.let { T(it, size = 14.sp, color = C.dim) }
        if (pc.onlineResults.isNotEmpty()) {
            OptionList(pc.onlineResults.map {
                Choice("${it.language.uppercase()} · ${it.title}", listOfNotNull(
                    if (it.hashMatch) "Matches this file" else null, if (it.source == "plex") "via Plex" else "${it.downloads} downloads",
                    if (it.hearingImpaired) "SDH" else null, if (it.machineTranslated) "machine translated" else null,
                ).joinToString(" · "))
            }) { i -> pc.pickOnline(pc.onlineResults[i]) }
        }
    }
}
