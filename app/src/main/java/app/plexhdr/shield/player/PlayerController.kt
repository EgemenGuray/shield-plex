package app.plexhdr.shield.player

import android.app.Activity
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import app.plexhdr.shield.App
import app.plexhdr.shield.MainActivity
import app.plexhdr.shield.plex.Decide
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.plex.PlexMarker
import app.plexhdr.shield.plex.PlexStream
import app.plexhdr.shield.services.Bif
import app.plexhdr.shield.services.Online
import app.plexhdr.shield.services.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** One playback session: the ExoPlayer, the plan for the title, tracks, markers, Up Next and progress reporting. */
@OptIn(UnstableApi::class)
class PlayerController(
    private val activity: Activity,
    item: PlexItem,
    mediaIndex: Int?,
    audioId: Int?,
    subtitleId: Int?,
    private val onClose: () -> Unit,
) {
    enum class Panel(val title: String) { Info("Info"), Chapters("Chapters"), Audio("Audio"), Subtitles("Subtitles"), Picture("Picture") }
    enum class Fit(val label: String) { Fit("Original"), Zoom("Zoom to fill"), Stretch("Stretch") }

    private val settings: Settings = App.settings
    private val plex = App.plex
    private val memory = App.memory
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    var item by mutableStateOf(item); private set
    var caps by mutableStateOf(Engine.caps(activity, activity.window.decorView.display)); private set
    var onlineSubtitle by mutableStateOf<String?>(null); private set
    var plan by mutableStateOf(initialPlan(item, mediaIndex, audioId, subtitleId)); private set
    var player by mutableStateOf<ExoPlayer?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var buffering by mutableStateOf(true); private set
    var timeMs by mutableStateOf(0L); private set
    var durationMs by mutableStateOf(item.durationMs ?: 0L); private set
    var overlayVisible by mutableStateOf(true)
    var panel by mutableStateOf<Panel?>(null)
    var scrubMs by mutableStateOf<Long?>(null); private set
    var introMarker by mutableStateOf<PlexMarker?>(null); private set
    var upNext by mutableStateOf<PlexItem?>(null); private set
    var upNextCountdown by mutableStateOf(10); private set
    var error by mutableStateOf<String?>(null); private set
    var subtitleDelayMs by mutableStateOf(0L); private set
    var speed by mutableStateOf(1f); private set
    var fit by mutableStateOf(Fit.Fit)
    var cues by mutableStateOf<List<Cue>>(emptyList()); private set
    var bif by mutableStateOf<Bif?>(null); private set
    var pcmFallback by mutableStateOf(false); private set
    var videoInfo by mutableStateOf(""); private set
    var audioInfo by mutableStateOf(""); private set
    val onlineResults = mutableStateListOf<Online.SubtitleResult>()
    var onlineStatus by mutableStateOf<String?>(null); private set

    private var external: SubtitleTrack? = null
    private var embeddedCues: List<Cue> = emptyList()
    private var appliedTracks = false
    private var retried = false
    private var transcodeSession: String? = null
    private var transcodeOffsetMs = 0L
    private var lastReport = 0L
    private var hideJob: Job? = null
    private var countdownJob: Job? = null
    private var upNextDismissed = false
    private val introSkipped = HashSet<Long>()
    private val creditsSkipped = HashSet<Long>()
    private var nextEpisode: PlexItem? = null
    private var show: PlexItem? = null
    private var scrubStep = 10_000L
    private var lastScrubMove = 0L

    private fun initialPlan(item: PlexItem, mediaIndex: Int?, audioId: Int?, subtitleId: Int?): Decide.Plan {
        val mem = memory.record(item)
        if (subtitleId == null && mem.subtitleId == null) onlineSubtitle = mem.onlineSubtitle
        return Decide.plan(item, mediaIndex ?: mem.mediaIndex ?: 0, audioId ?: mem.audioId, subtitleId ?: mem.subtitleId, settings, caps)
    }

    // ---- Opening

    fun start(startMs: Long?) {
        open(startMs ?: memory.resumePositionMs(item))
        scope.launch { loadExtras() }
        hideOverlaySoon()
        scope.launch {
            while (isActive) {
                tick()
                delay(200)
            }
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            buffering = state == Player.STATE_BUFFERING || state == Player.STATE_IDLE
            if (state == Player.STATE_READY) {
                val d = player?.duration ?: C.TIME_UNSET
                if (d != C.TIME_UNSET && d > 0) durationMs = d + transcodeOffsetMs
                retried = false
            }
            if (state == Player.STATE_ENDED) finished()
        }

        override fun onIsPlayingChanged(playing: Boolean) {
            isPlaying = playing
            reportTimeline(force = true)
            activity.window.decorView.keepScreenOn = playing
        }

        override fun onTracksChanged(tracks: Tracks) {
            describeTracks(tracks)
            if (!appliedTracks && !tracks.isEmpty) {
                appliedTracks = true
                applyTracks()
            }
        }

        override fun onCues(cueGroup: CueGroup) {
            val list = cueGroup.cues
            val d = subtitleDelayMs
            if (d <= 0) embeddedCues = list else handler.postDelayed({ embeddedCues = list }, d)
        }

        override fun onPlayerError(e: PlaybackException) = playbackError(e)
    }

    private fun buildPlayer(): ExoPlayer {
        Engine.stripDolbyVision = plan.stripDolbyVision
        val pcm = pcmFallback || settings.audioOutput == Settings.AudioOutput.Pcm
        return Engine.build(activity, plex.http, pcm).also { it.addListener(listener) }
    }

    private fun open(startMs: Long) {
        error = null
        appliedTracks = false
        embeddedCues = emptyList()
        val p = plan
        val part = p.part
        val url: String?
        var playerStart = startMs
        if (p.engine == Decide.Engine.Transcode4K) {
            transcodeSession?.let { s -> scope.launch { plex.stopTranscode(s) } }
            val session = UUID.randomUUID().toString()
            transcodeSession = session
            transcodeOffsetMs = startMs
            playerStart = 0
            url = plex.transcode4kUrl(item, p.mediaIndex, startMs, session)
        } else {
            transcodeOffsetMs = 0
            url = part?.let { plex.directPlayUrl(it) }
        }
        if (url == null) { error = "This item has no playable file."; return }
        matchDisplay()
        val old = player
        old?.removeListener(listener)
        old?.release()
        val exo = buildPlayer()
        player = exo
        exo.setMediaItem(MediaItem.Builder().setUri(url).setMediaId(item.id).build(), playerStart)
        exo.playbackParameters = exo.playbackParameters.withSpeed(speed)
        exo.prepare()
        exo.playWhenReady = true
        loadExternalSubtitle()
    }

    private fun matchDisplay() {
        val v = plan.video ?: return
        val fps = v.frameRate?.toFloat() ?: return
        (activity as? MainActivity)?.matchDisplay(fps, v.width ?: 0, v.height ?: 0, settings.matchFrameRate, settings.matchResolution)
    }

    private suspend fun loadExtras() {
        plan.part?.let { part -> plex.previewIndex(part)?.let { data -> bif = withContext(Dispatchers.Default) { Bif.parse(data) } } }
        if (item.type == "episode") {
            item.grandparentRatingKey?.let { show = runCatching { plex.metadata(it) }.getOrNull() }
            nextEpisode = plex.nextEpisode(item)
        }
    }

    // ---- Errors: one more direct-play attempt, then decode the audio on the Shield.

    private fun playbackError(e: PlaybackException) {
        val pos = currentPosition()
        if (!retried) {
            retried = true
            open(pos)
            return
        }
        if (!pcmFallback && plan.bitstream && settings.audioOutput == Settings.AudioOutput.Passthrough) {
            pcmFallback = true
            plan = plan.copy(bitstream = false, note = "Passthrough failed (${e.errorCodeName}); the audio is now decoded on the Shield. " + plan.note)
            open(pos)
            return
        }
        error = "${e.errorCodeName}: ${e.cause?.message ?: e.message ?: "playback failed"}"
    }

    private fun finished() {
        scope.launch { plex.setWatched(item, true) }
        memory.savePosition(item, durationMs, durationMs)
        val next = nextEpisode
        if (item.type == "episode" && settings.autoplayNext && next != null) playItem(next) else close()
    }

    // ---- Tracks

    /** Plex stream → ExoPlayer track group: same container track id first (Plex index + 1), else same position. */
    private fun findGroup(type: Int, stream: PlexStream, all: List<PlexStream>): Tracks.Group? {
        val groups = player?.currentTracks?.groups?.filter { it.type == type } ?: return null
        val idx = stream.index
        if (idx != null) {
            groups.firstOrNull { g -> g.mediaTrackGroup.getFormat(0).id?.substringAfterLast(':')?.toIntOrNull() == idx + 1 }?.let { return it }
        }
        val embedded = all.filter { !it.isExternal }
        val pos = embedded.indexOfFirst { it.id == stream.id }
        return groups.getOrNull(pos)
    }

    private fun applyTracks() {
        plan.audio?.let { selectAudioTrack(it) }
        applySubtitleChoice()
    }

    private fun selectAudioTrack(s: PlexStream) {
        val exo = player ?: return
        val g = findGroup(C.TRACK_TYPE_AUDIO, s, plan.part?.audioStreams ?: emptyList()) ?: return
        exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
    }

    private fun setTextTrack(s: PlexStream?) {
        val exo = player ?: return
        val b = exo.trackSelectionParameters.buildUpon()
        val g = s?.let { findGroup(C.TRACK_TYPE_TEXT, it, plan.part?.subtitleStreams ?: emptyList()) }
        if (g == null) {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
        }
        exo.trackSelectionParameters = b.build()
    }

    private fun applySubtitleChoice() {
        val s = plan.subtitle
        if (onlineSubtitle != null || s == null || s.isExternal) setTextTrack(null) else setTextTrack(s)
    }

    private fun describeTracks(tracks: Tracks) {
        for (g in tracks.groups) {
            if (!g.isSelected) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSelected(i)) continue
                val f = g.getTrackFormat(i)
                when (g.type) {
                    C.TRACK_TYPE_VIDEO -> videoInfo = listOfNotNull(f.sampleMimeType, f.codecs, "${f.width}×${f.height}",
                        if (f.frameRate > 0) String.format("%.3f fps", f.frameRate) else null,
                        f.colorInfo?.let { c -> if (c.colorTransfer == C.COLOR_TRANSFER_ST2084) "PQ (HDR10)" else if (c.colorTransfer == C.COLOR_TRANSFER_HLG) "HLG" else null },
                    ).joinToString(" · ")
                    C.TRACK_TYPE_AUDIO -> audioInfo = listOfNotNull(f.sampleMimeType, f.codecs?.ifEmpty { null }, "${f.channelCount} ch", "${f.sampleRate} Hz",
                        if (plan.bitstream && !pcmFallback) "bitstream" else "decoded to PCM").joinToString(" · ")
                }
            }
        }
    }

    fun chooseAudio(s: PlexStream) {
        memory.update(item) { it.audioId = s.id }
        plan.part?.id?.let { pid -> scope.launch { plex.selectStreams(pid, s.id, null) } }
        val before = plan
        plan = Decide.plan(item, plan.mediaIndex, s.id, plan.subtitle?.id ?: -1, settings, caps)
        // A bitstream ↔ PCM change needs a different renderer path; reopen at the same point.
        if (before.bitstream != plan.bitstream || plan.engine == Decide.Engine.Transcode4K) open(currentPosition()) else selectAudioTrack(s)
    }

    fun chooseSubtitle(s: PlexStream?) {
        memory.update(item) { it.subtitleId = s?.id ?: -1; it.onlineSubtitle = null }
        onlineSubtitle = null
        plan = plan.copy(subtitle = s)
        plan.part?.id?.let { pid -> scope.launch { plex.selectStreams(pid, null, s?.id ?: 0) } }
        loadExternalSubtitle()
        applySubtitleChoice()
    }

    /** External Plex subtitles and downloaded ones are parsed here and drawn by the app. */
    private fun loadExternalSubtitle() {
        external = null
        val name = onlineSubtitle
        val s = plan.subtitle
        scope.launch {
            external = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        name != null -> App.online.cachedSubtitle(name)?.let { SubtitleTrack.parse(it.readBytes(), "srt") }
                        s != null && (s.isExternal || plan.engine == Decide.Engine.Transcode4K) && s.key != null -> {
                            val url = plex.streamUrl(s.key!!) ?: return@runCatching null
                            val bytes = plex.request("GET", url, accept = "*/*")
                            SubtitleTrack.parse(bytes, s.codec.ifEmpty { "srt" }.let { if (it == "subrip") "srt" else it })
                        }
                        else -> null
                    }
                }.getOrNull()
            }
        }
    }

    fun useOnlineSubtitle(file: File) {
        onlineSubtitle = file.name
        plan = plan.copy(subtitle = null)
        memory.update(item) { it.onlineSubtitle = file.name; it.subtitleId = null }
        setTextTrack(null)
        loadExternalSubtitle()
    }

    fun searchOnline() {
        onlineResults.clear()
        onlineStatus = "Searching…"
        scope.launch {
            val langs = listOfNotNull(Decide.iso639_1(settings.subtitleLanguage), Decide.iso639_1(plan.audio?.languageCode), "en").distinct()
            val part = plan.part
            val fileUrl = part?.let { plex.directPlayUrl(it) }
            val results = ArrayList<Online.SubtitleResult>()
            var err: String? = null
            runCatching { App.online.searchSubtitles(item, show, langs, fileUrl, part?.size) }
                .onSuccess { results += it }.onFailure { err = it.message }
            for (l in langs.take(2)) {
                val code = Decide.iso1[l] ?: l
                plex.searchServerSubtitles(item, code).forEach {
                    results += Online.SubtitleResult("plex", it.key ?: "", it.languageCode ?: l, it.str("providerTitle")?.let { p -> "${it.displayTitle} · $p" } ?: it.displayTitle, plexStream = it)
                }
            }
            onlineResults.addAll(results.take(60))
            onlineStatus = if (results.isEmpty()) (err ?: "Nothing found") else null
        }
    }

    fun pickOnline(r: Online.SubtitleResult) {
        onlineStatus = "Downloading…"
        scope.launch {
            runCatching {
                if (r.source == "plex" && r.plexStream != null) {
                    plex.saveServerSubtitle(item, r.plexStream)
                    // The server adds it to the file as an external stream; reload the item to get it.
                    item.ratingKey?.let { rk -> plex.metadata(rk) }?.let { fresh ->
                        item = fresh
                        val sub = fresh.media.getOrNull(plan.mediaIndex)?.part?.subtitleStreams?.lastOrNull { it.isExternal }
                        plan = plan.copy(part = fresh.media.getOrNull(plan.mediaIndex)?.part ?: plan.part)
                        chooseSubtitle(sub)
                    }
                } else {
                    useOnlineSubtitle(App.online.download(r))
                }
            }.onSuccess { onlineStatus = null; panel = null }.onFailure { onlineStatus = it.message }
        }
    }

    fun adjustSubtitleDelay(deltaMs: Long) { subtitleDelayMs += deltaMs }

    fun setPlaybackSpeed(s: Float) {
        speed = s
        player?.let { it.playbackParameters = it.playbackParameters.withSpeed(s) }
    }

    fun chooseVersion(index: Int) {
        memory.update(item) { it.mediaIndex = index }
        plan = Decide.plan(item, index, null, null, settings, caps)
        open(currentPosition())
    }

    // ---- Transport

    fun currentPosition(): Long = (player?.currentPosition ?: 0L) + transcodeOffsetMs

    fun togglePlay() {
        val exo = player ?: return
        if (exo.isPlaying) { exo.pause(); showOverlay(autoHide = false) } else { exo.play(); hideOverlaySoon() }
    }

    fun play() { player?.play() }
    fun pause() { player?.pause(); showOverlay(autoHide = false) }

    fun seekTo(ms: Long) {
        val target = ms.coerceIn(0, (durationMs - 1000).coerceAtLeast(0))
        if (plan.engine == Decide.Engine.Transcode4K) { open(target); return }
        timeMs = target
        player?.seekTo(target)
    }

    fun skip(deltaMs: Long) {
        seekTo(currentPosition() + deltaMs)
        showOverlay(autoHide = true)
    }

    /** While paused (or already scrubbing), ◀ ▶ move a scrub head with preview thumbnails; holding speeds up. */
    fun scrub(direction: Int) {
        val now = System.currentTimeMillis()
        scrubStep = if (now - lastScrubMove < 350) (scrubStep * 3 / 2).coerceAtMost(300_000) else 10_000
        lastScrubMove = now
        val from = scrubMs ?: currentPosition()
        scrubMs = (from + direction * scrubStep).coerceIn(0, durationMs)
        showOverlay(autoHide = false)
    }

    fun commitScrub() {
        scrubMs?.let { seekTo(it) }
        scrubMs = null
        player?.play()
        hideOverlaySoon()
    }

    fun cancelScrub() { scrubMs = null }

    data class Chapter(val title: String, val startMs: Long, val thumb: String?)

    val chapters: List<Chapter>
        get() = item.chapters.map { Chapter(it.title, it.startMs, it.thumb) }

    val currentChapter: Chapter? get() = chapters.lastOrNull { it.startMs <= timeMs }

    fun jumpChapter(d: Int) {
        val ch = chapters
        if (ch.isEmpty()) { skip(d * 60_000L); return }
        val t = currentPosition()
        if (d > 0) ch.firstOrNull { it.startMs > t + 1000 }?.let { seekTo(it.startMs) }
        else seekTo(ch.lastOrNull { it.startMs < t - 3000 }?.startMs ?: 0)
        showOverlay(autoHide = true)
    }

    fun showOverlay(autoHide: Boolean) {
        overlayVisible = true
        hideJob?.cancel()
        if (autoHide) hideOverlaySoon()
    }

    fun hideOverlaySoon() {
        hideJob?.cancel()
        hideJob = scope.launch {
            delay(5000)
            if (isPlaying && panel == null && scrubMs == null) overlayVisible = false
        }
    }

    // ---- Ticker: time, subtitles, markers, Up Next, progress

    private fun tick() {
        val exo = player ?: return
        val t = currentPosition()
        timeMs = t
        if (durationMs <= 0) item.durationMs?.let { durationMs = it }
        val ext = external
        cues = if (ext != null) ext.cuesAt((t - subtitleDelayMs) * 1000) else embeddedCues
        if (exo.playbackState == Player.STATE_READY) reportTimeline(force = false)
        checkMarkers(t)
    }

    private fun checkMarkers(t: Long) {
        val intro = item.markers.firstOrNull { it.isIntro && t >= it.startMs && t < it.endMs - 1000 }
        if (intro != null && settings.autoSkipIntro && intro.startMs !in introSkipped) {
            introSkipped += intro.startMs
            seekTo(intro.endMs)
            return
        }
        introMarker = intro
        val credits = item.markers.firstOrNull { it.isCredits && t >= it.startMs && t < it.endMs - 1000 }
        val next = nextEpisode
        val nearEnd = durationMs > 0 && t > durationMs - 30_000
        if (next != null && settings.autoplayNext && !upNextDismissed && (credits != null || nearEnd)) {
            if (upNext == null) startUpNext(next)
        } else if (credits != null && settings.autoSkipCredits && credits.startMs !in creditsSkipped && next == null) {
            creditsSkipped += credits.startMs
            seekTo(credits.endMs)
        }
    }

    private fun startUpNext(next: PlexItem) {
        upNext = next
        upNextCountdown = 10
        countdownJob?.cancel()
        countdownJob = scope.launch {
            while (upNextCountdown > 0) {
                delay(1000)
                if (isPlaying) upNextCountdown--
            }
            playItem(next)
        }
    }

    fun dismissUpNext() {
        countdownJob?.cancel()
        upNext = null
        upNextDismissed = true
    }

    fun skipIntro() { introMarker?.let { introSkipped += it.startMs; seekTo(it.endMs) } }

    /** Switches to another title (Up Next), keeping the subtitle language. */
    fun playItem(next: PlexItem) {
        countdownJob?.cancel()
        saveProgress()
        val subLang = plan.subtitle?.languageCode
        scope.launch {
            val full = next.ratingKey?.let { runCatching { plex.metadata(it) }.getOrNull() } ?: next
            item = full
            upNext = null
            upNextDismissed = false
            introSkipped.clear()
            creditsSkipped.clear()
            nextEpisode = null
            bif = null
            onlineSubtitle = memory.record(full).onlineSubtitle
            var p = Decide.plan(full, memory.record(full).mediaIndex ?: 0, memory.record(full).audioId, memory.record(full).subtitleId, settings, caps)
            if (subLang != null && p.subtitle == null) {
                p.part?.subtitleStreams?.firstOrNull { Decide.sameLang(it.languageCode, subLang) && !it.forced }?.let { p = p.copy(subtitle = it) }
            }
            plan = p
            durationMs = full.durationMs ?: 0
            open(memory.resumePositionMs(full).let { if (full.isWatched) 0 else it })
            loadExtras()
        }
    }

    private fun reportTimeline(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastReport < 10_000) return
        lastReport = now
        val state = if (isPlaying) "playing" else if (buffering) "buffering" else "paused"
        val t = currentPosition()
        val d = durationMs
        memory.savePosition(item, t, d)
        val it = item
        scope.launch { plex.timeline(it, state, t, d) }
    }

    private fun saveProgress() {
        val t = currentPosition()
        memory.savePosition(item, t, durationMs)
        memory.flush()
        val it = item
        val d = durationMs
        // Fire and forget, outside the scope that is about to be cancelled.
        CoroutineScope(Dispatchers.IO).launch { plex.timeline(it, "stopped", t, d) }
    }

    fun close() {
        saveProgress()
        onClose()
    }

    fun release() {
        countdownJob?.cancel()
        transcodeSession?.let { s -> CoroutineScope(Dispatchers.IO).launch { plex.stopTranscode(s) } }
        player?.removeListener(listener)
        player?.release()
        player = null
        (activity as? MainActivity)?.restoreDisplay()
        activity.window.decorView.keepScreenOn = false
        scope.cancel()
    }
}
