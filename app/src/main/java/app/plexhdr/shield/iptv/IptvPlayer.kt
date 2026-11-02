package app.plexhdr.shield.iptv

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.TextUnit
import coil3.compose.AsyncImage
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import app.plexhdr.shield.App
import app.plexhdr.shield.player.Engine
import app.plexhdr.shield.services.Settings
import app.plexhdr.shield.ui.C
import app.plexhdr.shield.ui.Focusable
import app.plexhdr.shield.ui.Img
import app.plexhdr.shield.ui.ProgressBar
import app.plexhdr.shield.ui.T
import app.plexhdr.shield.ui.formatTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What to play: a live channel (with the lineup to zap through), a past programme, a film, an episode or a saved file. */
sealed interface IptvPlay {
    data class Live(val channel: Channel, val lineup: List<Channel>) : IptvPlay
    data class Catchup(val channel: Channel, val programme: Programme) : IptvPlay
    data class Film(val movie: Movie) : IptvPlay
    data class Ep(val series: Series, val episode: Episode, val all: List<Episode>) : IptvPlay
    data class File(val job: Recorder.Job) : IptvPlay
}

private fun clock(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))

/** Media item for an IPTV address: HLS is marked when the address doesn't end in .m3u8 but says so. */
@OptIn(UnstableApi::class)
internal fun iptvItem(url: String): MediaItem {
    val b = MediaItem.Builder().setUri(url)
    val lower = url.lowercase()
    if ("m3u8" in lower || "/hls/" in lower || "type=m3u" in lower) b.setMimeType(MimeTypes.APPLICATION_M3U8)
    return b.build()
}

@OptIn(UnstableApi::class)
class IptvPlayerController(private val activity: Activity, initial: IptvPlay, private val onClose: () -> Unit) {
    private val store = App.iptv
    private val rec = App.recorder
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    @Volatile private var userAgent: String? = null

    enum class Panel { Channels, Options }

    var play by mutableStateOf(initial); private set
    var lineup by mutableStateOf((initial as? IptvPlay.Live)?.lineup ?: emptyList<Channel>()); private set
    var overlay by mutableStateOf(true)
    var panel by mutableStateOf<Panel?>(null)
    var buffering by mutableStateOf(true); private set
    var status by mutableStateOf<String?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var digits by mutableStateOf(""); private set
    var positionMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(0L); private set
    var isPlaying by mutableStateOf(false); private set
    var tracks by mutableStateOf<Tracks>(Tracks.EMPTY); private set
    var fill by mutableStateOf(false)
    var previous: Channel? = null; private set

    private var retries = 0
    private var bufferingSince = 0L
    private var overlayJob: Job? = null
    private var digitJob: Job? = null
    private var lastSave = 0L

    val player: ExoPlayer = run {
        val http = store.http.newBuilder().addInterceptor { chain ->
            val ua = userAgent
            chain.proceed(if (ua != null) chain.request().newBuilder().header("User-Agent", ua).build() else chain.request())
        }.build()
        Engine.stripDolbyVision = App.settings.dolbyVision == Settings.DolbyVision.BaseLayer
        Engine.build(activity, http, App.settings.audioOutput == Settings.AudioOutput.Pcm)
    }

    val channel: Channel? get() = when (val p = play) { is IptvPlay.Live -> p.channel; is IptvPlay.Catchup -> p.channel; else -> null }
    val isLive get() = play is IptvPlay.Live

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING
            bufferingSince = if (buffering) System.currentTimeMillis() else 0
            if (state == Player.STATE_READY) { retries = 0; status = null; error = null }
            if (state == Player.STATE_ENDED) ended()
        }
        override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
        override fun onTracksChanged(t: Tracks) { tracks = t; measure() }
        override fun onPlayerError(e: PlaybackException) { failed(e) }
    }

    fun start() {
        player.addListener(listener)
        load()
        showOverlay()
        scope.launch {
            while (isActive) {
                delay(1000)
                positionMs = player.currentPosition.coerceAtLeast(0)
                durationMs = player.duration.takeIf { it != MC.TIME_UNSET && it > 0 } ?: 0
                // Stall watchdog: a live stream stuck buffering for 12 s is reopened.
                if (isLive && bufferingSince > 0 && System.currentTimeMillis() - bufferingSince > 12_000) { bufferingSince = 0; reconnect("The stream stalled. Reconnecting…") }
                if (!isLive && System.currentTimeMillis() - lastSave > 10_000) { lastSave = System.currentTimeMillis(); savePosition() }
            }
        }
    }

    private fun urlAndStart(): Pair<String?, Long> = when (val p = play) {
        is IptvPlay.Live -> p.channel.url to 0L
        is IptvPlay.Catchup -> store.catchupUrl(p.channel, p.programme) to 0L
        is IptvPlay.Film -> p.movie.url to store.position(p.movie.id)
        is IptvPlay.Ep -> p.episode.url to store.position(p.episode.id)
        is IptvPlay.File -> rec.file(p.job).toURI().toString() to 0L
    }

    private fun load(at: Long? = null) {
        val (url, start) = urlAndStart()
        if (url == null) { error = "This channel has no catch-up for that programme."; return }
        error = null
        userAgent = when (val p = play) {
            is IptvPlay.Live -> p.channel.userAgent
            is IptvPlay.Catchup -> p.channel.userAgent
            is IptvPlay.Film -> store.playlist(p.movie.playlistId)?.userAgent?.ifEmpty { null }
            is IptvPlay.Ep -> store.playlist(p.series.playlistId)?.userAgent?.ifEmpty { null }
            is IptvPlay.File -> null
        }
        when (val p = play) {
            is IptvPlay.Live -> store.played(p.channel)
            is IptvPlay.Film -> store.noteWatching(p.movie)
            is IptvPlay.Ep -> store.noteWatching(p.series, p.episode)
            else -> {}
        }
        player.setMediaItem(iptvItem(url), at ?: start)
        player.prepare()
        player.playWhenReady = true
    }

    private fun failed(e: PlaybackException) {
        if (isLive && retries < 6) { reconnect("Connection lost. Reconnecting… (${retries + 1})"); return }
        error = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The provider refused the stream. Your connection limit may be reached."
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't reach the stream."
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This stream's format isn't supported."
            else -> e.message ?: "Playback failed."
        }
    }

    private fun reconnect(msg: String) {
        status = msg
        val wait = longArrayOf(1000, 2000, 4000, 8000, 15000, 15000)[retries.coerceAtMost(5)]
        retries++
        scope.launch { delay(wait); load(if (isLive) null else player.currentPosition) }
    }

    fun retry() { retries = 0; load(if (isLive) null else positionMs) }

    private fun measure() {
        val ch = (play as? IptvPlay.Live)?.channel ?: return
        val v = player.videoFormat
        val a = player.audioFormat
        val hdr = v?.colorInfo?.colorTransfer.let { it == MC.COLOR_TRANSFER_ST2084 || it == MC.COLOR_TRANSFER_HLG }
        if (v != null && v.height > 0) store.recordMeasured(ch, v.height, a?.channelCount?.takeIf { it > 0 }, hdr)
    }

    private fun ended() {
        val p = play
        if (p is IptvPlay.Ep) {
            store.savePosition(p.episode.id, 0, 1)
            val i = p.all.indexOfFirst { it.id == p.episode.id }
            val next = p.all.getOrNull(i + 1)
            if (next != null && App.settings.autoplayNext) { play = p.copy(episode = next); load(0); showOverlay(); return }
        }
        if (p is IptvPlay.Film) store.savePosition(p.movie.id, 0, 1)
        if (isLive) { reconnect("The stream ended. Reconnecting…"); return }
        close()
    }

    private fun savePosition() {
        val d = player.duration.takeIf { it != MC.TIME_UNSET && it > 0 } ?: return
        when (val p = play) {
            is IptvPlay.Film -> store.savePosition(p.movie.id, player.currentPosition, d)
            is IptvPlay.Ep -> store.savePosition(p.episode.id, player.currentPosition, d)
            else -> {}
        }
    }

    // ---- live controls

    fun tune(ch: Channel) {
        val cur = channel
        if (cur != null && cur.id != ch.id) previous = cur
        play = IptvPlay.Live(ch, lineup.ifEmpty { listOf(ch) })
        retries = 0
        load()
        showOverlay()
    }

    fun zap(dir: Int) {
        val cur = channel ?: return
        if (lineup.size < 2) return
        val i = lineup.indexOfFirst { it.id == cur.id }
        tune(lineup[((if (i < 0) 0 else i) + dir + lineup.size) % lineup.size])
    }

    fun lastChannel() { previous?.let { tune(it) } }

    fun digit(d: Int) {
        digits = (digits + d).takeLast(4)
        digitJob?.cancel()
        digitJob = scope.launch {
            delay(1800)
            val n = digits.toIntOrNull()
            digits = ""
            val pool = lineup.ifEmpty { store.channels }
            val ch = pool.firstOrNull { it.number == n } ?: n?.let { pool.getOrNull(it - 1) }
            if (ch != null) tune(ch)
        }
    }

    // ---- common controls

    fun togglePlay() { if (player.isPlaying) player.pause() else player.play(); showOverlay() }
    fun seekBy(ms: Long) { if (!isLive) { player.seekTo((player.currentPosition + ms).coerceAtLeast(0)); showOverlay() } }

    fun showOverlay(autoHide: Boolean = true) {
        overlay = true
        overlayJob?.cancel()
        if (autoHide) overlayJob = scope.launch { delay(5000); if (panel == null && player.isPlaying) overlay = false }
    }

    fun selectTrack(type: Int, group: Tracks.Group?) {
        val b = player.trackSelectionParameters.buildUpon()
        if (group == null) b.setTrackTypeDisabled(type, true)
        else b.setTrackTypeDisabled(type, false).setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        player.trackSelectionParameters = b.build()
    }

    fun close() {
        savePosition()
        onClose()
    }

    fun release() {
        savePosition()
        scope.cancel()
        player.removeListener(listener)
        player.release()
    }
}

@OptIn(UnstableApi::class)
@Composable
fun IptvPlayerScreen(request: IptvPlay, onClose: () -> Unit) {
    val activity = LocalContext.current as Activity
    val pc = remember { IptvPlayerController(activity, request, onClose) }
    DisposableEffect(Unit) {
        pc.start()
        onDispose { pc.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { pc.player.pause() }
    val root = remember { FocusRequester() }
    LaunchedEffect(pc.panel) { if (pc.panel == null) runCatching { root.requestFocus() } }

    BackHandler {
        when {
            pc.panel != null -> pc.panel = null
            pc.overlay && pc.isLive && pc.error == null -> pc.overlay = false
            else -> pc.close()
        }
    }

    val s = App.settings
    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(root)
            .onPreviewKeyEvent { e ->
                if (pc.panel != null || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val d = digitOf(e.key)
                if (d != null) { pc.digit(d); return@onPreviewKeyEvent true }
                if (pc.error != null && (e.key == Key.DirectionCenter || e.key == Key.Enter)) { pc.retry(); return@onPreviewKeyEvent true }
                if (pc.isLive) when (e.key) {
                    Key.DirectionUp, Key.ChannelUp -> { pc.zap(-1); true }
                    Key.DirectionDown, Key.ChannelDown -> { pc.zap(1); true }
                    Key.DirectionLeft -> { pc.panel = IptvPlayerController.Panel.Channels; pc.showOverlay(false); true }
                    Key.DirectionRight, Key.Menu, Key.Info -> { pc.panel = IptvPlayerController.Panel.Options; pc.showOverlay(false); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (pc.overlay) pc.overlay = false else pc.showOverlay(); true }
                    Key.MediaPlayPause -> { pc.togglePlay(); true }
                    Key.MediaPrevious -> { pc.lastChannel(); true }
                    else -> { pc.showOverlay(); false }
                } else when (e.key) {
                    Key.DirectionLeft, Key.MediaRewind -> { pc.seekBy(-s.skipBack * 1000L); true }
                    Key.DirectionRight, Key.MediaFastForward -> { pc.seekBy(s.skipForward * 1000L); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> { pc.togglePlay(); true }
                    Key.DirectionDown, Key.Menu, Key.Info -> { pc.panel = IptvPlayerController.Panel.Options; pc.showOverlay(false); true }
                    Key.DirectionUp -> { pc.showOverlay(); true }
                    else -> { pc.showOverlay(); false }
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                }
            },
            update = { v ->
                if (v.player !== pc.player) v.player = pc.player
                v.resizeMode = if (pc.fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (pc.buffering && pc.error == null) Box(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(18.dp)) {
            T(pc.status ?: "Loading…", size = 18.sp)
        }
        pc.status?.let { if (!pc.buffering && pc.error == null) Box(Modifier.align(Alignment.TopCenter).padding(30.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x99000000)).padding(14.dp)) { T(it, size = 16.sp) } }
        if (pc.digits.isNotEmpty()) T(pc.digits, Modifier.align(Alignment.TopEnd).padding(40.dp), size = 64.sp, weight = FontWeight.Bold)

        AnimatedVisibility(pc.overlay || pc.panel != null, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), enter = fadeIn(), exit = fadeOut()) {
            Osd(pc)
        }

        when (pc.panel) {
            IptvPlayerController.Panel.Channels -> ChannelPanel(pc, Modifier.align(Alignment.CenterStart))
            IptvPlayerController.Panel.Options -> OptionsPanel(pc, Modifier.align(Alignment.CenterEnd))
            null -> {}
        }

        pc.error?.let { err ->
            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp)).background(C.surface).padding(30.dp)) {
                T("Couldn't play this", size = 24.sp, weight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                T(err, Modifier.width(640.dp), size = 15.sp, color = C.dim)
                Spacer(Modifier.height(10.dp))
                T(if (pc.isLive) "Select: try again  ·  ▲▼ other channels  ·  Back: close" else "Select: try again  ·  Back: close", size = 14.sp, color = C.faint)
            }
        }
    }
}

private fun digitOf(k: Key): Int? = when (k) {
    Key.Zero, Key.NumPad0 -> 0; Key.One, Key.NumPad1 -> 1; Key.Two, Key.NumPad2 -> 2; Key.Three, Key.NumPad3 -> 3; Key.Four, Key.NumPad4 -> 4
    Key.Five, Key.NumPad5 -> 5; Key.Six, Key.NumPad6 -> 6; Key.Seven, Key.NumPad7 -> 7; Key.Eight, Key.NumPad8 -> 8; Key.Nine, Key.NumPad9 -> 9
    else -> null
}

@Composable
private fun Osd(pc: IptvPlayerController) {
    val store = App.iptv
    Column(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000))))
            .padding(start = 56.dp, end = 56.dp, top = 80.dp, bottom = 40.dp),
    ) {
        val ch = pc.channel
        if (pc.isLive && ch != null) {
            val (now, next) = store.nowNext(ch)
            Row(verticalAlignment = Alignment.Top) {
                ChannelLogo(ch, Modifier.size(120.dp, 68.dp), 22.sp)
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ch.number?.let { T("$it  ", size = 26.sp, color = C.dim, weight = FontWeight.Bold) }
                        T(ch.name, size = 26.sp, weight = FontWeight.Bold, maxLines = 1)
                        Spacer(Modifier.width(12.dp))
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(C.bad).padding(horizontal = 6.dp, vertical = 2.dp)) { T("LIVE", size = 12.sp, weight = FontWeight.Bold) }
                        if (App.recorder.recordingOf(ch) != null) { Spacer(Modifier.width(8.dp)); T("● REC", size = 13.sp, color = C.bad, weight = FontWeight.Bold) }
                        store.tags(ch).forEach { Spacer(Modifier.width(6.dp)); QualityBadge(it) }
                    }
                    Spacer(Modifier.height(6.dp))
                    if (now != null) {
                        T(now.title, size = 20.sp, maxLines = 1)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            T("${clock(now.start)} – ${clock(now.end)}", size = 15.sp, color = C.dim)
                            Spacer(Modifier.width(14.dp))
                            ProgressBar(((System.currentTimeMillis() - now.start).toFloat() / (now.end - now.start)).coerceIn(0f, 1f), Modifier.width(260.dp), C.text)
                        }
                    } else T("No guide information", size = 15.sp, color = C.dim)
                    next?.let { T("Next ${clock(it.start)}  ${it.title}", size = 15.sp, color = C.dim, maxLines = 1) }
                }
                T(clock(System.currentTimeMillis()), size = 22.sp, color = C.dim)
            }
            Spacer(Modifier.height(14.dp))
            T("▲▼ Channels   ◀ Channel list   ▶ Audio, subtitles, record   0-9 Number", size = 13.sp, color = C.faint)
        } else {
            val title = when (val p = pc.play) {
                is IptvPlay.Film -> p.movie.name
                is IptvPlay.Ep -> "${p.series.name} · S${p.episode.season} E${p.episode.number} ${p.episode.title}"
                is IptvPlay.Catchup -> "${p.programme.title} · ${p.channel.name} ${clock(p.programme.start)}"
                is IptvPlay.File -> p.job.title
                else -> ""
            }
            T(title, size = 24.sp, weight = FontWeight.Bold, maxLines = 1)
            Spacer(Modifier.height(12.dp))
            if (pc.durationMs > 0) {
                ProgressBar(pc.positionMs.toFloat() / pc.durationMs, Modifier.fillMaxWidth(), C.text)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    T(formatTime(pc.positionMs), size = 15.sp, color = C.dim)
                    T("-" + formatTime(pc.durationMs - pc.positionMs), size = 15.sp, color = C.dim)
                }
            }
            Spacer(Modifier.height(8.dp))
            T(if (pc.isPlaying) "◀ ▶ Skip   Select: Pause   ▼ Audio and subtitles" else "Paused · Select: Play", size = 13.sp, color = C.faint)
        }
    }
}

@Composable
private fun PanelBox(title: String, modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxHeight().width(460.dp).background(Color(0xF0141418)).padding(26.dp)) {
        T(title, size = 22.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        content()
    }
}

@Composable
private fun PanelRow(text: String, sub: String? = null, current: Boolean = false, focus: FocusRequester? = null,
                     leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), 1.02f, focus, showBorder = false) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) C.focus else Color.Transparent).padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                T(text, size = 16.sp, color = if (f) Color.Black else if (current) C.accent else C.text, maxLines = 1)
                if (sub != null) T(sub, size = 12.sp, color = if (f) Color.DarkGray else C.dim, maxLines = 1)
            }
        }
    }
}

/** A channel's logo (from the playlist or the TV guide). Shows the channel's initials until it loads, and if there is none or it fails. */
@Composable
fun ChannelLogo(ch: Channel, modifier: Modifier, textSize: TextUnit = 13.sp) {
    val url = App.iptv.logo(ch)
    var loaded by remember(url) { mutableStateOf(false) }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(if (loaded) Color.Transparent else Color(0x18FFFFFF)), contentAlignment = Alignment.Center) {
        if (!loaded) T(Text.initials(ch.name), size = textSize, weight = FontWeight.Bold, color = C.faint, maxLines = 1)
        if (url != null) AsyncImage(model = url, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            onSuccess = { loaded = true }, onError = { loaded = false })
    }
}

@Composable
private fun ChannelPanel(pc: IptvPlayerController, modifier: Modifier) {
    val store = App.iptv
    val list = pc.lineup
    val cur = pc.channel
    val start = list.indexOfFirst { it.id == cur?.id }.coerceAtLeast(0)
    val state = rememberLazyListState(maxOf(0, start - 4))
    val focus = remember { FocusRequester() }
    PanelBox("Channels", modifier) {
        LazyColumn(state = state, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(list, key = { _, c -> c.id }) { i, c ->
                val now = store.nowNext(c).first
                PanelRow((c.number?.let { "$it  " } ?: "") + c.name, now?.title, c.id == cur?.id, if (i == start) focus else null,
                    leading = { ChannelLogo(c, Modifier.size(56.dp, 32.dp), 11.sp) }) {
                    pc.panel = null; pc.tune(c)
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
}

@Composable
private fun OptionsPanel(pc: IptvPlayerController, modifier: Modifier) {
    val first = remember { FocusRequester() }
    val rows = ArrayList<Triple<String?, String, () -> Unit>>()   // header, label, action
    var header: String? = "Audio"
    pc.tracks.groups.filter { it.type == MC.TRACK_TYPE_AUDIO }.forEachIndexed { i, g ->
        val f = g.getTrackFormat(0)
        val label = listOfNotNull(f.language, f.codecs ?: f.sampleMimeType?.substringAfter('/'), if (f.channelCount > 0) "${f.channelCount} ch" else null)
            .joinToString(" · ").ifEmpty { "Track ${i + 1}" }
        rows += Triple(header, (if (g.isSelected) "✓ " else "") + label, { pc.selectTrack(MC.TRACK_TYPE_AUDIO, g) }); header = null
    }
    header = "Subtitles"
    rows += Triple(header, "Off", { pc.selectTrack(MC.TRACK_TYPE_TEXT, null) }); header = null
    pc.tracks.groups.filter { it.type == MC.TRACK_TYPE_TEXT }.forEachIndexed { i, g ->
        val f = g.getTrackFormat(0)
        rows += Triple(null, (if (g.isSelected) "✓ " else "") + (f.label ?: f.language ?: "Track ${i + 1}"), { pc.selectTrack(MC.TRACK_TYPE_TEXT, g) })
    }
    val ch = (pc.play as? IptvPlay.Live)?.channel
    if (ch != null) {
        val rec = App.recorder
        val job = rec.recordingOf(ch)
        header = "Record"
        if (job != null) rows += Triple(header, "Stop recording", { rec.stop(job.id) })
        else {
            App.iptv.nowNext(ch).first?.let { now -> rows += Triple(header, "Record “${now.title}”", { rec.record(ch, now); Unit }); header = null }
            rows += Triple(header, "Record the next hour", { rec.record(ch, null, 60); Unit })
        }
        pc.previous?.let { p -> rows += Triple("Last channel", p.name, { pc.lastChannel() }) }
        rows += Triple("Favourite", if (App.iptv.isFavorite(ch.id)) "Remove from Favorites" else "Add to Favorites", { App.iptv.toggleFavorite(ch.id) })
    }
    rows += Triple("Picture", if (pc.fill) "Original size" else "Fill screen", { pc.fill = !pc.fill })
    PanelBox("Options", modifier) {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            itemsIndexed(rows) { i, (h, label, act) ->
                Column {
                    if (h != null) T(h, Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp), size = 13.sp, color = C.accent, weight = FontWeight.SemiBold)
                    PanelRow(label, focus = if (i == 0) first else null) { act(); pc.panel = null; pc.showOverlay() }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { first.requestFocus() } }
}

/** A muted live picture of the focused channel, for the Live TV list. */
@OptIn(UnstableApi::class)
@Composable
fun ChannelPreview(channel: Channel?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(App.iptv.http))))
            .build().apply { volume = 0f; playWhenReady = true }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { player.stop() }
    LaunchedEffect(channel?.id) {
        player.stop()
        if (channel == null) return@LaunchedEffect
        delay(900)   // only once the focus settles
        player.setMediaItem(iptvItem(channel.url))
        player.prepare()
    }
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xFF121216))) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false; isFocusable = false; setShutterBackgroundColor(android.graphics.Color.TRANSPARENT); this.player = player } },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun QualityBadge(tag: QTag) {
    val (bg, fg) = when (tag) {
        QTag.UHD -> Color(0xFFF5C518) to Color.Black
        QTag.FHD -> Color(0xE6FFFFFF) to Color.Black
        QTag.HDR -> Color(0xFF8A5CF6) to Color.White
        QTag.Surround -> Color(0x3330D158) to C.good
        else -> Color(0x33FFFFFF) to C.text
    }
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(bg).padding(horizontal = 5.dp, vertical = 1.dp)) {
        T(tag.label, size = 11.sp, color = fg, weight = FontWeight.Bold, maxLines = 1)
    }
}
