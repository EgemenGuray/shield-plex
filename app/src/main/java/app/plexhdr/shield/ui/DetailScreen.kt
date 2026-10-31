package app.plexhdr.shield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.player.Engine
import app.plexhdr.shield.plex.Decide
import app.plexhdr.shield.plex.PersonKind
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.plex.PlexPerson
import kotlinx.coroutines.launch

/** Movie or episode details, with what Play will do, track pickers, and rows below. */
@Composable
fun DetailScreen(nav: Nav, initial: PlexItem) {
    val plex = App.plex
    val settings = App.settings
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var item by remember { mutableStateOf(initial) }
    var loaded by remember { mutableStateOf(false) }
    var season by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var similar by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(App.memory.record(initial).mediaIndex ?: 0) }
    var audioId by remember { mutableStateOf(App.memory.record(initial).audioId) }
    var subtitleId by remember { mutableStateOf(App.memory.record(initial).subtitleId) }
    var refresh by remember { mutableIntStateOf(0) }
    val caps = remember { Engine.caps(ctx, view.display) }

    LaunchedEffect(initial.id, refresh) {
        initial.ratingKey?.let { rk -> runCatching { plex.metadata(rk) }.getOrNull()?.let { item = it } }
        loaded = true
        if (item.type == "episode") item.parentRatingKey?.let { season = runCatching { plex.children(it) }.getOrDefault(emptyList()) }
        else similar = item.ratingKey?.let { plex.similar(it) } ?: emptyList()
    }

    val plan = remember(item, version, audioId, subtitleId, settings.audioOutput, settings.dolbyVision) {
        Decide.plan(item, version, audioId, subtitleId, settings, caps)
    }
    val art by rememberArtwork(if (item.type == "episode") null else item)
    val ratings by rememberRatings(item)
    val resumeMs = App.memory.resumePositionMs(item)
    val play = remember { FocusRequester() }
    LaunchedEffect(loaded) { runCatching { play.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(backdropUrl(item, art))
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(start = D.gutter, end = D.gutter, top = 110.dp, bottom = 10.dp)) {
                    if (item.type == "episode") T(item.grandparentTitle ?: "", size = 20.sp, color = C.dim)
                    val logo = if (item.type == "episode") null else logoUrl(item, art)
                    if (logo != null) Img(logo, Modifier.height(120.dp).width(440.dp), ContentScale.Fit)
                    else T(item.title, size = 44.sp, weight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(12.dp))
                    T(metaLine(item), size = 16.sp, color = C.dim)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Decide.badges(plan).forEach { Badge(it) } }
                    ratings?.let { Spacer(Modifier.height(12.dp)); RatingsRow(it) }
                    Spacer(Modifier.height(14.dp))
                    T(item.summary ?: "", Modifier.fillMaxWidth(0.55f), size = 16.sp, maxLines = 4, lineHeight = 22.sp)
                    Spacer(Modifier.height(14.dp))
                    T(plan.note, Modifier.fillMaxWidth(0.6f), size = 14.sp, color = C.accent, maxLines = 3)
                    plan.warnings.forEach { T("⚠ $it", Modifier.fillMaxWidth(0.6f), size = 13.sp, color = C.warn, maxLines = 2) }
                    Spacer(Modifier.height(22.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        item {
                            Pill(if (resumeMs > 0) "Resume ${formatTime(resumeMs)}" else "Play", {
                                nav.play(item, resumeMs, version, audioId, subtitleId)
                            }, focusRequester = play, icon = "▶")
                        }
                        if (resumeMs > 0) item { Pill("Start Over", { nav.play(item, 0L, version, audioId, subtitleId) }) }
                        val trailer = item.extras.firstOrNull { it.subtype == "trailer" } ?: item.extras.firstOrNull()
                        if (trailer != null) item { Pill("Trailer", { nav.play(trailer, 0L) }) }
                        item { Pill("Audio & Subtitles", { dialog = "tracks" }) }
                        if (item.media.size > 1) item { Pill("Version", { dialog = "version" }) }
                        item {
                            Pill(if (item.isWatched) "Mark Unwatched" else "Mark Watched", {
                                scope.launch { plex.setWatched(item, !item.isWatched); refresh++ }
                            })
                        }
                        item { Pill("Info", { dialog = "info" }) }
                    }
                }
            }
            if (item.chapters.isNotEmpty()) item {
                Shelf("Chapters", item.chapters, { it.startMs }) { ch ->
                    WideCard(ch.title, formatTime(ch.startMs), plexImage(ch.thumb, 480, 270), null, {
                        nav.play(item, ch.startMs, version, audioId, subtitleId)
                    }, width = 240.dp)
                }
            }
            if (season.isNotEmpty()) item {
                Shelf("Season ${item.parentIndex ?: ""}", season, { it.id }) { ep ->
                    WideCard("${ep.index ?: ""}. ${ep.title}", formatRuntime(ep.durationMs), plexImage(ep.thumb, 600, 338), ep.progress, {
                        nav.stack[nav.stack.lastIndex] = Screen.Detail(ep)
                    })
                }
            }
            val people = castAndCrew(item)
            if (people.isNotEmpty()) item { CastRow(nav, people) }
            if (item.extras.isNotEmpty()) item {
                Shelf("Extras", item.extras, { it.id }) { x ->
                    WideCard(x.title, formatRuntime(x.durationMs), plexImage(x.thumb, 600, 338), null, { nav.play(x, 0L) })
                }
            }
            if (similar.isNotEmpty()) item { ItemShelf("More Like This", similar, nav::open) }
            item { Spacer(Modifier.height(60.dp)) }
        }

        when (dialog) {
            "tracks" -> {
                val part = plan.part
                val choices = ArrayList<Choice>()
                val actions = ArrayList<() -> Unit>()
                choices += Choice("Audio", header = true); actions += {}
                part?.audioStreams?.forEach { s ->
                    choices += Choice(s.extendedDisplayTitle, Decide.bitstreamKey(s)?.let { k ->
                        if (k in caps.bitstream || (k == "eac3joc" && "eac3" in caps.bitstream)) "Bitstream to the soundbar" else "Decoded to PCM on the Shield"
                    }, s.id == plan.audio?.id)
                    actions += { audioId = s.id; App.memory.update(item) { it.audioId = s.id } }
                }
                choices += Choice("Subtitles", header = true); actions += {}
                choices += Choice("Off", selected = plan.subtitle == null); actions += { subtitleId = -1; App.memory.update(item) { it.subtitleId = -1 } }
                part?.subtitleStreams?.forEach { s ->
                    choices += Choice(s.extendedDisplayTitle, if (s.isExternal) "External file" else "Embedded", s.id == plan.subtitle?.id)
                    actions += { subtitleId = s.id; App.memory.update(item) { it.subtitleId = s.id; it.onlineSubtitle = null } }
                }
                ChoiceDialog("Audio & Subtitles", choices, { i -> actions[i](); dialog = null }, { dialog = null })
            }
            "version" -> ChoiceDialog("Version", item.media.mapIndexed { i, m -> Choice(m.label, m.part?.file?.substringAfterLast('/'), i == plan.mediaIndex) },
                { i -> version = i; audioId = null; subtitleId = null; App.memory.update(item) { it.mediaIndex = i; it.audioId = null; it.subtitleId = null }; dialog = null },
                { dialog = null })
            "info" -> TextDialog("Info", techSheet(plan, caps), { dialog = null })
        }
    }
}

fun techSheet(plan: Decide.Plan, caps: Decide.Caps): List<Pair<String, String>> {
    val out = ArrayList<Pair<String, String>>()
    val m = plan.media
    out += "File" to ""
    out += "Path" to (plan.part?.file ?: "—")
    out += "Container" to (m?.container ?: "—")
    m?.bitrate?.let { out += "Bitrate" to String.format("%.1f Mbps", it / 1000.0) }
    plan.part?.size?.let { out += "Size" to String.format("%.2f GB", it / 1e9) }
    plan.video?.let { v ->
        out += "Video" to ""
        out += "Stream" to v.extendedDisplayTitle
        out += "Resolution" to "${v.width ?: m?.width}×${v.height ?: m?.height}"
        out += "Frame rate" to (v.frameRate?.let { String.format("%.3f fps", it) } ?: "—")
        out += "HDR" to Decide.hdrLabel(v)
        out += "Bit depth" to (v.bitDepth?.toString() ?: "—")
        if (v.doviPresent) out += "Dolby Vision" to "Profile ${v.doviProfile}, base layer compat ${v.doviBLCompatID}"
    }
    plan.audio?.let { a ->
        out += "Audio" to ""
        out += "Track" to a.extendedDisplayTitle
        out += "Channels" to "${a.channels} (${a.audioChannelLayout ?: "—"})"
        out += "Output" to if (plan.bitstream) "Bitstream (passthrough)" else "PCM, decoded on the Shield"
    }
    out += "Playback" to ""
    out += "Plan" to plan.note
    plan.warnings.forEach { out += "Note" to it }
    out += "This Shield" to ""
    out += "Bitstream formats" to caps.bitstream.sorted().joinToString(", ").ifEmpty { "none reported" }
    out += "Max PCM channels" to caps.maxPcmChannels.toString()
    out += "HEVC decoder" to "up to ${caps.decoderMaxWidth}×${caps.decoderMaxHeight}"
    out += "TV HDR formats" to caps.hdrTypes.sorted().joinToString(", ").ifEmpty { "none reported" }
    return out
}

/** Actors with their roles, then directors, writers, producers (one card per person, all jobs listed). */
fun castAndCrew(item: PlexItem): List<Pair<PlexPerson, String>> {
    val out = LinkedHashMap<String, Pair<PlexPerson, MutableList<String>>>()
    for (p in item.roles) out.getOrPut(p.name) { p to mutableListOf() }.second += (p.role ?: "Actor")
    for (p in item.crew) out.getOrPut(p.name) { p to mutableListOf() }.second += p.kind.label
    return out.values.map { (p, jobs) -> p to jobs.distinct().joinToString(", ") }
}

@Composable
fun CastRow(nav: Nav, people: List<Pair<PlexPerson, String>>) {
    Shelf("Cast & Crew", people, { it.first.id }) { (p, job) ->
        Column(Modifier.width(130.dp)) {
            Focusable({ nav.push(Screen.Person(p)) }, Modifier.size(120.dp), CircleShape) {
                Img(plexImage(p.thumb, 240, 240) ?: p.thumb, Modifier.fillMaxSize().clip(CircleShape))
                if (p.thumb == null) Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    T(p.name.split(" ").mapNotNull { it.firstOrNull()?.toString() }.take(2).joinToString(""), size = 30.sp, color = C.dim)
                }
            }
            Spacer(Modifier.height(8.dp))
            T(p.name, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            T(job, size = 12.sp, color = C.dim, maxLines = 2)
        }
    }
}

/** Everything in your libraries this person is credited on. */
@Composable
fun PersonScreen(nav: Nav, person: PlexPerson) {
    var items by remember { mutableStateOf<List<PlexItem>?>(null) }
    LaunchedEffect(person.id) {
        // Search every credit kind: an actor who also directed shows up under both.
        val all = ArrayList<PlexItem>()
        for (k in PersonKind.values()) {
            val p = PlexPerson(person.o, k)
            all += App.plex.personItems(p)
        }
        items = all.distinctBy { it.id }.sortedByDescending { it.originallyAvailableAt ?: "" }
    }
    GridPage(nav, person.name, items)
}
