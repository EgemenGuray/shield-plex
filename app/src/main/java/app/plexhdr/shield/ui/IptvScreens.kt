package app.plexhdr.shield.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.iptv.Channel
import app.plexhdr.shield.iptv.ChannelLogo
import app.plexhdr.shield.iptv.ChannelPreview
import app.plexhdr.shield.iptv.Details
import app.plexhdr.shield.iptv.Episode
import app.plexhdr.shield.iptv.IptvGroup
import app.plexhdr.shield.iptv.IptvPlay
import app.plexhdr.shield.iptv.IptvStore
import app.plexhdr.shield.iptv.Movie
import app.plexhdr.shield.iptv.Playlist
import app.plexhdr.shield.iptv.Programme
import app.plexhdr.shield.iptv.QTag
import app.plexhdr.shield.iptv.QualityBadge
import app.plexhdr.shield.iptv.Recorder
import app.plexhdr.shield.iptv.ResumeEntry
import app.plexhdr.shield.iptv.SearchScope
import app.plexhdr.shield.iptv.Series
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/* Live TV (IPTV) screens: Home, Live TV with preview, TV Guide, Movies, Series, Favorites, Search, Downloads, Recordings
 * and Playlists. INFO, MENU or a long press on a channel or poster opens its options. */

internal enum class Section(val label: String) {
    Home("Home"), Live("Live TV"), Guide("TV Guide"), Movies("Movies"), Series("Series"), Favorites("Favorites"),
    Search("Search"), Downloads("Downloads"), Recordings("Recordings"), Playlists("Playlists"),
}

/** One row of an options sheet. */
data class SheetRow(val label: String, val detail: String? = null, val header: Boolean = false, val action: () -> Unit = {})
data class Sheet(val title: String, val rows: List<SheetRow>)

/** What the Live TV screens remember while you move around (kept across pages and tab switches). */
private object Ui {
    var section by mutableStateOf(Section.Home)
    var liveGroup by mutableStateOf("all")
    var tagFilter by mutableStateOf<Set<QTag>>(emptySet())
    var guideGroup by mutableStateOf("all")
    var guideStart by mutableStateOf(0L)
    var movieGroup by mutableStateOf("all")
    var seriesGroup by mutableStateOf("all")
    var sort by mutableStateOf("added")
    var favTab by mutableStateOf(0)
    var query by mutableStateOf("")
    var scope by mutableStateOf(SearchScope.All)
    var searchTags by mutableStateOf<Set<QTag>>(emptySet())
    var sheet by mutableStateOf<Sheet?>(null)
    var input by mutableStateOf<IptvInput?>(null)
    /** Each section's own search text. */
    val sq = mutableStateMapOf<Section, String>()
}

/** True when the section isn't being searched, or any of the values contains its search text. */
private fun hits(sec: Section, vararg values: String?): Boolean {
    val q = Ui.sq[sec]?.trim()?.lowercase().orEmpty()
    if (q.isEmpty()) return true
    return values.any { it != null && it.lowercase().contains(q) }
}
private fun searching(sec: Section) = !Ui.sq[sec].isNullOrBlank()

/** The search field at the top of a section. It looks through the whole section, not just the open group. */
@Composable
private fun SectionSearch(sec: Section, hint: String) {
    val q = Ui.sq[sec] ?: ""
    var f by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicTextField(
            q, { Ui.sq[sec] = it },
            Modifier.width(330.dp).onFocusChanged { f = it.isFocused }.clip(RoundedCornerShape(50))
                .background(if (q.isNotEmpty()) C.surfaceHi else Color(0x33FFFFFF))
                .border(2.dp, if (f) C.focus else Color.Transparent, RoundedCornerShape(50))
                .padding(horizontal = 20.dp, vertical = 10.dp),
            textStyle = TextStyle(color = C.text, fontSize = 16.sp), cursorBrush = SolidColor(C.accent), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T("⌕  ", size = 16.sp, color = C.faint)
                    Box { if (q.isEmpty()) T("Search $hint", size = 15.sp, color = C.faint, maxLines = 1); inner() }
                }
            },
        )
        if (q.isNotEmpty()) { Spacer(Modifier.width(8.dp)); Pill("✕", { Ui.sq[sec] = "" }) }
    }
}

@Composable
private fun NoMatch(sec: Section) = T("Nothing matches “${Ui.sq[sec]?.trim()}”.", Modifier.padding(D.gutter), color = C.dim)

data class IptvInput(val title: String, val value: String, val number: Boolean = false, val secret: Boolean = false, val done: (String) -> Unit)

private fun clock(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(t))
private fun day(t: Long): String {
    val a = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    return when (Math.floorDiv(t - a, 86_400_000L)) {
        0L -> "Today"; -1L -> "Yesterday"; 1L -> "Tomorrow"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(t))
    }
}

private fun Modifier.onMenu(action: () -> Unit): Modifier = onPreviewKeyEvent { e ->
    if (e.type == KeyEventType.KeyDown && (e.key == Key.Menu || e.key == Key.Info)) { action(); true } else false
}

private fun sheet(title: String, vararg rows: SheetRow) { Ui.sheet = Sheet(title, rows.toList()) }

private fun withPin(g: IptvGroup?, then: () -> Unit) {
    val s = App.iptv
    if (g != null && s.isLocked(g) && !s.unlocked) Ui.input = IptvInput("Enter PIN", "", number = true, secret = true) { if (s.unlock(it)) then() }
    else then()
}

/* ---------------- frame ---------------- */

@Composable
fun IptvHome(nav: Nav) {
    val s = App.iptv
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(top = 76.dp)) {
            LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Section.entries.toList()) { sec -> Pill(sec.label, { Ui.section = sec }, selected = sec == Ui.section) }
            }
            Spacer(Modifier.height(8.dp))
            if (s.playlists.isEmpty() && Ui.section !in setOf(Section.Playlists, Section.Downloads, Section.Recordings)) Welcome(nav)
            else when (Ui.section) {
                Section.Home -> HomeSection(nav)
                Section.Live -> LiveSection(nav)
                Section.Guide -> GuideSection(nav)
                Section.Movies -> LibrarySection(nav, movies = true)
                Section.Series -> LibrarySection(nav, movies = false)
                Section.Favorites -> FavoritesSection(nav)
                Section.Search -> SearchSection(nav)
                Section.Downloads -> JobsSection(nav, Recorder.Kind.Download)
                Section.Recordings -> JobsSection(nav, Recorder.Kind.Recording)
                Section.Playlists -> PlaylistsSection(nav)
            }
        }
        IptvOverlays()
    }
}

/** Options sheets and text entry, drawn over any Live TV page. */
@Composable
fun IptvOverlays() {
    Ui.sheet?.let { sh ->
        ChoiceDialog(sh.title, sh.rows.map { Choice(it.label, it.detail, header = it.header) }, { i ->
            Ui.sheet = null
            sh.rows[i].action()
        }) { Ui.sheet = null }
    }
    Ui.input?.let { inp -> InputDialog(inp) }
}

@Composable
private fun InputDialog(inp: IptvInput) {
    var text by remember(inp) { mutableStateOf(inp.value) }
    val focus = remember { FocusRequester() }
    BackHandler { Ui.input = null }
    fun done() { Ui.input = null; inp.done(text) }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp).clip(RoundedCornerShape(20.dp)).background(C.surface).padding(28.dp)) {
            T(inp.title, size = 24.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            BasicTextField(
                text, { text = it },
                Modifier.fillMaxWidth().focusRequester(focus).clip(RoundedCornerShape(10.dp)).background(C.surfaceHi).padding(16.dp),
                textStyle = TextStyle(color = C.text, fontSize = 20.sp),
                cursorBrush = SolidColor(C.accent), singleLine = true,
                visualTransformation = if (inp.secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (inp.number) KeyboardType.NumberPassword else KeyboardType.Uri, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { done() }),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Done", { done() })
                Pill("Cancel", { Ui.input = null })
            }
        }
    }
    LaunchedEffect(Unit) { delay(50); runCatching { focus.requestFocus() } }
}

@Composable
private fun Welcome(nav: Nav) {
    val f = remember { FocusRequester() }
    Column(Modifier.fillMaxSize().padding(D.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        T("Watch live TV in Lumen", size = 34.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(14.dp))
        T("Add an M3U playlist or an Xtream Codes login from your TV provider. Lumen reads its channels, TV guide, catch-up, movies and series.",
            Modifier.width(760.dp), size = 17.sp, color = C.dim, align = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Pill("Add Playlist", { nav.push(Screen.IptvPlaylist(null)) }, focusRequester = f)
    }
    LaunchedEffect(Unit) { runCatching { f.requestFocus() } }
}

/* ---------------- cards ---------------- */

@Composable
private fun ChannelCard(ch: Channel, lineup: List<Channel>, nav: Nav) {
    val s = App.iptv
    val now = s.nowNext(ch).first
    Column(Modifier.width(210.dp)) {
        Focusable({ nav.push(Screen.IptvPlayer(IptvPlay.Live(ch, lineup))) }, Modifier.size(210.dp, 118.dp).onMenu { channelOptions(nav, ch) },
            onLongClick = { channelOptions(nav, ch) }) {
            Box(Modifier.fillMaxSize().background(C.surface), contentAlignment = Alignment.Center) {
                ChannelLogo(ch, Modifier.fillMaxSize().padding(18.dp), 28.sp)
            }
            if (s.isFavorite(ch.id)) T("★", Modifier.align(Alignment.TopEnd).padding(6.dp), size = 16.sp, color = C.accent)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            T(ch.name, Modifier.weight(1f, fill = false), size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            s.tags(ch).filter { it.isVideo }.forEach { Spacer(Modifier.width(5.dp)); QualityBadge(it) }
        }
        T(now?.title ?: "", size = 12.sp, color = C.dim, maxLines = 1)
    }
}

@Composable
private fun IptvPoster(title: String, sub: String?, image: String?, id: String, onClick: () -> Unit, onOptions: () -> Unit) {
    val s = App.iptv
    Column(Modifier.width(D.posterW)) {
        Focusable(onClick, Modifier.size(D.posterW, D.posterH).onMenu(onOptions), onLongClick = onOptions) {
            Box(Modifier.fillMaxSize().background(C.surface), contentAlignment = Alignment.Center) {
                T(title, Modifier.padding(10.dp), size = 14.sp, color = C.dim, align = TextAlign.Center, maxLines = 4)
            }
            if (image != null) Img(image, Modifier.fillMaxSize())
            val p = s.progress(id)
            if (p > 0) ProgressBar(p, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            if (s.isFavorite(id)) T("★", Modifier.align(Alignment.TopEnd).padding(6.dp), size = 16.sp, color = C.accent)
        }
        Spacer(Modifier.height(8.dp))
        T(title, size = 13.sp, color = C.dim, maxLines = 1)
        if (!sub.isNullOrEmpty()) T(sub, size = 11.sp, color = C.faint, maxLines = 1)
    }
}

@Composable
private fun MoviePoster(m: Movie, nav: Nav) = IptvPoster(m.name, listOfNotNull(m.year?.toString(), m.rating?.let { "★ %.1f".format(it) }).joinToString(" · "),
    m.poster, m.id, { nav.push(Screen.IptvMovie(m)) }) {
    sheet(m.name,
        SheetRow(if (App.iptv.isFavorite(m.id)) "Remove from Favorites" else "Add to Favorites") { App.iptv.toggleFavorite(m.id) },
        SheetRow("Download") { App.recorder.download(m) })
}

@Composable
private fun SeriesPoster(x: Series, nav: Nav) = IptvPoster(x.name, x.year?.toString(), x.poster, x.id, { nav.push(Screen.IptvSeries(x)) }) {
    sheet(x.name, SheetRow(if (App.iptv.isFavorite(x.id)) "Remove from Favorites" else "Add to Favorites") { App.iptv.toggleFavorite(x.id) })
}

private fun channelOptions(nav: Nav, ch: Channel) {
    val s = App.iptv
    val rec = App.recorder
    val rows = ArrayList<SheetRow>()
    rows += SheetRow("Watch") { nav.push(Screen.IptvPlayer(IptvPlay.Live(ch, listOf(ch)))) }
    rows += SheetRow(if (s.isFavorite(ch.id)) "Remove from Favorites" else "Add to Favorites") { s.toggleFavorite(ch.id) }
    rows += SheetRow("Rename") { Ui.input = IptvInput("Rename ${ch.name}", ch.name) { s.rename(ch, it) } }
    val job = rec.recordingOf(ch)
    if (job != null) rows += SheetRow("Stop Recording") { rec.stop(job.id) }
    else {
        s.nowNext(ch).first?.let { now -> rows += SheetRow("Record “${now.title}”") { rec.record(ch, now) } }
        rows += SheetRow("Record 1 Hour") { rec.record(ch, null, 60) }
    }
    s.groups(IptvGroup.Kind.Live).firstOrNull { it.playlistId == ch.playlistId && it.name == ch.group }?.let { g ->
        rows += SheetRow("Group: ${g.name}", header = true)
        rows += groupRows(g)
    }
    Ui.sheet = Sheet(ch.name, rows)
}

private fun groupRows(g: IptvGroup): List<SheetRow> {
    val s = App.iptv
    val rows = arrayListOf(SheetRow("Hide Group") { s.setHidden(g, true) })
    rows += if (s.pin.isNotEmpty()) SheetRow(if (s.isLocked(g)) "Unlock Group" else "Lock Group with PIN") { s.setLocked(g, !s.isLocked(g)) }
    else SheetRow("Lock Group with PIN", "Set a PIN in Playlists first")
    return rows
}

/* ---------------- home ---------------- */

@Composable
private fun <E> IShelf(title: String, note: String?, list: List<E>, key: (E) -> Any, content: @Composable (E) -> Unit) {
    if (list.isEmpty()) return
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.padding(start = D.gutter, bottom = 4.dp), verticalAlignment = Alignment.Bottom) {
            T(title, size = 20.sp, weight = FontWeight.SemiBold)
            if (note != null) { Spacer(Modifier.width(10.dp)); T(note, size = 13.sp, color = C.dim) }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            items(list, key = key) { content(it) }
        }
    }
}

@Composable
private fun HomeSection(nav: Nav) {
    val s = App.iptv
    val trending by produceState<IptvStore.Trending?>(null, s.movies.size, s.series.size) { value = s.trending() }
    val resume = s.continueEntries()
    val recent = s.recentChannels()
    val hot = s.trendingChannels()
    val favs = s.favoriteChannels()
    LazyColumn(Modifier.fillMaxSize()) {
        item { IShelf("Continue Watching", null, resume, { it.id }) { r -> ResumeCard(r, nav) } }
        item { IShelf("Recently Watched Channels", null, recent.take(20), { it.id }) { ChannelCard(it, recent, nav) } }
        item { IShelf("Trending Channels", "Most watched in Lumen this week", hot, { it.id }) { ChannelCard(it, hot, nav) } }
        item { IShelf("Favorite Channels", null, favs.take(30), { it.id }) { ChannelCard(it, favs, nav) } }
        trending?.let { t ->
            item { IShelf("Trending Movies", if (t.fromTmdb) "Trending on TMDB this week" else "Top rated new additions (add a TMDB key in Settings for real trends)", t.movies, { it.id }) { MoviePoster(it, nav) } }
            item { IShelf("Trending Series", if (t.fromTmdb) "Trending on TMDB this week" else "Top rated", t.series, { it.id }) { SeriesPoster(it, nav) } }
        }
        if (resume.isEmpty() && recent.isEmpty() && hot.isEmpty() && favs.isEmpty() && trending?.movies.isNullOrEmpty() && trending?.series.isNullOrEmpty()) item {
            Box(Modifier.fillMaxWidth().padding(60.dp), contentAlignment = Alignment.Center) {
                T(if (s.isLoading()) "Loading your playlists…" else "Start watching and your channels, movies and series show up here.", size = 17.sp, color = C.dim)
            }
        }
        item { Spacer(Modifier.height(60.dp)) }
    }
}

@Composable
private fun ResumeCard(r: ResumeEntry, nav: Nav) {
    val s = App.iptv
    WideCard(r.title, r.subtitle, r.poster, s.progress(r.id).takeIf { it > 0 }, {
        if (r.movieId != null) s.movie(r.movieId)?.let { nav.push(Screen.IptvPlayer(IptvPlay.Film(it))) }
        else s.seriesById(r.seriesId)?.let { nav.push(Screen.IptvSeries(it)) }
    }, modifier = Modifier.onMenu { sheet(r.title, SheetRow("Remove from Continue Watching") { s.removeResume(r.id) }) })
}

/* ---------------- Live TV ---------------- */

private fun lineup(groupId: String): List<Channel> {
    val s = App.iptv
    var l = when (groupId) {
        "all" -> s.channels
        "favorites" -> s.favoriteChannels()
        "recents" -> s.recentChannels()
        else -> s.groups(IptvGroup.Kind.Live).firstOrNull { it.id == groupId }?.let { s.channelsIn(it) } ?: s.channels
    }
    if (Ui.tagFilter.isNotEmpty()) l = l.filter { s.tags(it).containsAll(Ui.tagFilter) }
    return l
}

@Composable
private fun TagRow(selected: Set<QTag>, onChange: (Set<QTag>) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(QTag.entries.toList()) { t -> Pill(t.label, { onChange(if (t in selected) selected - t else selected + t) }, selected = t in selected) }
    }
}

@Composable
private fun ListRow(text: String, sub: String? = null, on: Boolean = false, height: Dp = 54.dp, leading: (@Composable () -> Unit)? = null,
                    trailing: (@Composable () -> Unit)? = null, onFocus: () -> Unit = {}, onOptions: (() -> Unit)? = null, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth().then(if (onOptions != null) Modifier.onMenu(onOptions) else Modifier), RoundedCornerShape(10.dp), 1.02f,
        onFocused = onFocus, onLongClick = onOptions, showBorder = false) { f ->
        Row(Modifier.fillMaxWidth().height(height).background(if (f) C.focus else if (on) Color(0x22FFFFFF) else Color.Transparent).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                T(text, size = 16.sp, color = if (f) Color.Black else C.text, maxLines = 1)
                if (sub != null) T(sub, size = 12.sp, color = if (f) Color.DarkGray else C.dim, maxLines = 1)
            }
            trailing?.invoke()
        }
    }
}

@Composable
private fun LiveSection(nav: Nav) {
    val s = App.iptv
    val list = if (searching(Section.Live)) lineup("all").filter { hits(Section.Live, it.name, it.group, s.nowNext(it).first?.title) } else lineup(Ui.liveGroup)
    var focused by remember { mutableStateOf<Channel?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = D.gutter), verticalAlignment = Alignment.CenterVertically) {
            SectionSearch(Section.Live, "channels and what's on")
            Box(Modifier.weight(1f)) { TagRow(Ui.tagFilter) { Ui.tagFilter = it } }
        }
        Row(Modifier.fillMaxSize().padding(start = D.gutter, end = D.gutter, top = 8.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            LazyColumn(Modifier.width(250.dp).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Color(0x0DFFFFFF)).padding(6.dp)) {
                items(listOf("all" to "All Channels", "favorites" to "Favorites", "recents" to "Recently Watched")) { (id, label) ->
                    ListRow(label, on = Ui.liveGroup == id, height = 44.dp) { Ui.liveGroup = id }
                }
                items(s.groups(IptvGroup.Kind.Live), key = { it.id }) { g ->
                    ListRow(g.name + if (s.isLocked(g)) (if (s.unlocked) "  🔓" else "  🔒") else "", on = Ui.liveGroup == g.id, height = 44.dp,
                        leading = { SourceDot(g.playlistId) }, onOptions = { Ui.sheet = Sheet(g.name, groupRows(g)) }) {
                        withPin(g) { Ui.liveGroup = g.id }
                    }
                }
            }
            LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
                if (searching(Section.Live)) item { Header("${list.size} channels match “${Ui.sq[Section.Live]?.trim()}”") }
                if (list.isEmpty()) item { T(if (s.isLoading()) "Loading channels…" else if (searching(Section.Live)) "No channel matches." else "No channels here.", Modifier.padding(16.dp), color = C.dim) }
                items(list, key = { it.id }) { ch ->
                    val nn = s.nowNext(ch)
                    val now = nn.first
                    ListRow(ch.name, now?.title, height = 60.dp,
                        leading = {
                            T(ch.number?.toString() ?: "", Modifier.width(44.dp), size = 13.sp, color = C.faint)
                            ChannelLogo(ch, Modifier.size(64.dp, 36.dp))
                            Spacer(Modifier.width(12.dp))
                        },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (App.recorder.recordingOf(ch) != null) T("●", size = 14.sp, color = C.bad)
                                if (ch.catchup != null) T("⟲", size = 14.sp, color = C.dim)
                                if (s.isFavorite(ch.id)) T("★", size = 14.sp, color = C.accent)
                                s.tags(ch).forEach { QualityBadge(it) }
                            }
                        },
                        onFocus = { focused = ch }, onOptions = { channelOptions(nav, ch) },
                    ) { nav.push(Screen.IptvPlayer(IptvPlay.Live(ch, list))) }
                }
            }
            Column(Modifier.width(420.dp)) {
                ChannelPreview(focused, Modifier.size(420.dp, 236.dp))
                Spacer(Modifier.height(14.dp))
                focused?.let { ch ->
                    val (now, next) = s.nowNext(ch)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ChannelLogo(ch, Modifier.size(84.dp, 47.dp), 16.sp)
                        Spacer(Modifier.width(14.dp))
                        T(ch.name, Modifier.weight(1f), size = 20.sp, weight = FontWeight.Bold, maxLines = 2)
                    }
                    now?.let {
                        Spacer(Modifier.height(6.dp))
                        T(it.title, size = 17.sp, maxLines = 2)
                        T("${clock(it.start)} – ${clock(it.end)}", size = 13.sp, color = C.dim)
                        it.desc?.let { d -> Spacer(Modifier.height(6.dp)); T(d, size = 13.sp, color = C.faint, maxLines = 5) }
                    }
                    next?.let { T("Next ${clock(it.start)}  ${it.title}", Modifier.padding(top = 8.dp), size = 13.sp, color = C.dim, maxLines = 1) }
                }
                Spacer(Modifier.height(16.dp))
                T("Select: watch   ·   Menu or hold Select: favourite, rename, record, hide, lock", size = 12.sp, color = C.faint)
            }
        }
    }
}

@Composable
private fun SourceDot(playlistId: String) {
    val s = App.iptv
    if (s.playlists.size < 2) return
    Box(Modifier.padding(end = 10.dp).size(9.dp).clip(RoundedCornerShape(50)).background(Color(s.color(s.playlist(playlistId)))))
}

/* ---------------- TV Guide ---------------- */

private const val HOURS = 2.5f

@Composable
private fun GuideSection(nav: Nav) {
    val s = App.iptv
    if (Ui.guideStart == 0L) Ui.guideStart = System.currentTimeMillis() / 1_800_000L * 1_800_000L - 1_800_000L
    val start = Ui.guideStart
    val end = start + (HOURS * 3_600_000).toLong()
    val chans = when (Ui.guideGroup) {
        "all" -> s.channels
        "favorites" -> s.favoriteChannels()
        else -> s.groups(IptvGroup.Kind.Live).firstOrNull { it.id == Ui.guideGroup }?.let { s.channelsIn(it) } ?: s.channels
    }.let { all ->
        if (!searching(Section.Guide)) all
        // Channels whose name matches, or that show a matching programme within a day of the guide's start.
        else s.channels.filter { ch -> hits(Section.Guide, ch.name) || s.programmes(ch, start, start + 86_400_000L).any { hits(Section.Guide, it.title, it.episode, it.category) } }
    }
    fun shift(dir: Int) {
        val maxDays = s.channels.mapNotNull { it.catchup?.days }.maxOrNull() ?: 1
        val now = System.currentTimeMillis()
        Ui.guideStart = (Ui.guideStart + (dir * HOURS * 0.8f * 3_600_000).toLong()).coerceIn(now - maxDays * 86_400_000L, now + 4 * 86_400_000L)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = D.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            val name = when (Ui.guideGroup) { "all" -> "All Channels"; "favorites" -> "Favorites"; else -> s.groups(IptvGroup.Kind.Live).firstOrNull { it.id == Ui.guideGroup }?.name ?: "All Channels" }
            Pill("▤ $name", {
                val gs = s.groups(IptvGroup.Kind.Live)
                Ui.sheet = Sheet("Channels", listOf(SheetRow("All Channels") { Ui.guideGroup = "all" }, SheetRow("Favorites") { Ui.guideGroup = "favorites" }) +
                    gs.map { g -> SheetRow(g.name) { withPin(g) { Ui.guideGroup = g.id } } })
            })
            Pill("◀ Earlier", { shift(-1) })
            Pill("Now", { Ui.guideStart = 0L })
            Pill("Later ▶", { shift(1) })
            SectionSearch(Section.Guide, "channels and programmes")
            Spacer(Modifier.weight(1f))
            T(day(start), size = 17.sp, color = C.dim)
        }
        if (s.guide.programmes.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                T(if (s.guideLoading) "Loading the TV guide…" else "No TV guide yet. Lumen uses the guide your playlist names; you can also add an XMLTV address to the playlist.",
                    Modifier.width(700.dp), size = 16.sp, color = C.dim, align = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Pill("Load Guide", { s.refreshGuide() })
            }
            return@Column
        }
        if (chans.isEmpty() && searching(Section.Guide)) { NoMatch(Section.Guide); return@Column }
        val chW = 220.dp
        val perHour = 300.dp
        Row(Modifier.padding(start = D.gutter + chW, top = 10.dp)) {
            for (i in 0 until (HOURS * 2).toInt()) T(clock(start + i * 1_800_000L), Modifier.width(perHour / 2), size = 13.sp, color = C.dim)
        }
        val now = System.currentTimeMillis()
        Box(Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = D.gutter, end = D.gutter, top = 6.dp, bottom = 40.dp)) {
                items(chans.take(300), key = { it.id }) { ch ->
                    Row(Modifier.height(56.dp).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Row(Modifier.width(chW), verticalAlignment = Alignment.CenterVertically) {
                            ChannelLogo(ch, Modifier.size(48.dp, 28.dp), 11.sp)
                            Spacer(Modifier.width(8.dp))
                            T(ch.name, Modifier.weight(1f), size = 14.sp, maxLines = 1)
                        }
                        val progs = s.programmes(ch, start, end)
                        if (progs.isEmpty()) {
                            GuideCell(ch.name, null, perHour * HOURS, false) { nav.push(Screen.IptvPlayer(IptvPlay.Live(ch, chans))) }
                        } else {
                            var cursor = start
                            progs.forEach { p ->
                                val a = maxOf(p.start, start, cursor)
                                val b = minOf(p.end, end)
                                if (b <= a) return@forEach
                                if (a > cursor) Spacer(Modifier.width(perHour * ((a - cursor) / 3_600_000f)))
                                val marks = (if (App.recorder.isRecording(ch, p)) "● " else "") + (if (s.hasReminder(ch, p)) "🔔 " else "") +
                                    (if (p.end < now && s.canCatchUp(ch, p)) "⟲ " else "")
                                GuideCell(marks + p.title, clock(p.start), perHour * ((b - a) / 3_600_000f), p.start <= now && now < p.end) { programmeSheet(nav, ch, p, chans) }
                                cursor = b
                            }
                        }
                    }
                }
            }
            if (now in start until end) {
                Box(Modifier.offset(x = D.gutter + chW + perHour * ((now - start) / 3_600_000f)).width(2.dp).fillMaxHeight().background(C.bad))
            }
        }
    }
}

@Composable
private fun GuideCell(title: String, sub: String?, width: Dp, airing: Boolean, onClick: () -> Unit) {
    Focusable(onClick, Modifier.width(width).fillMaxHeight().padding(end = 4.dp), RoundedCornerShape(8.dp), 1.03f, showBorder = false) { f ->
        Column(Modifier.fillMaxSize().background(if (f) C.focus else if (airing) Color(0x26FFFFFF) else Color(0x12FFFFFF)).padding(horizontal = 10.dp, vertical = 5.dp)) {
            T(title, size = 14.sp, color = if (f) Color.Black else C.text, maxLines = 1)
            if (sub != null) T(sub, size = 11.sp, color = if (f) Color.DarkGray else C.faint, maxLines = 1)
        }
    }
}

private fun programmeSheet(nav: Nav, ch: Channel, p: Programme, chans: List<Channel>) {
    val s = App.iptv
    val rec = App.recorder
    val now = System.currentTimeMillis()
    val rows = ArrayList<SheetRow>()
    rows += SheetRow("${ch.name} · ${day(p.start)} ${clock(p.start)} – ${clock(p.end)}", listOfNotNull(p.episode, p.desc).joinToString("\n").ifEmpty { null }, header = false)
    when {
        p.start <= now && now < p.end -> {
            rows += SheetRow("Watch Live") { nav.push(Screen.IptvPlayer(IptvPlay.Live(ch, chans))) }
            if (s.canCatchUp(ch, p)) rows += SheetRow("Watch from the Start") { nav.push(Screen.IptvPlayer(IptvPlay.Catchup(ch, p))) }
        }
        p.end <= now -> if (s.canCatchUp(ch, p)) {
            rows += SheetRow("Watch (Catch-up)") { nav.push(Screen.IptvPlayer(IptvPlay.Catchup(ch, p))) }
            rows += SheetRow("Download") { rec.download(ch, p) }
        } else rows += SheetRow("Not available", "This channel has no catch-up")
        else -> rows += SheetRow(if (s.hasReminder(ch, p)) "Cancel Reminder" else "Remind Me") { s.toggleReminder(ch, p) }
    }
    if (p.end > now) rows += if (rec.isRecording(ch, p)) SheetRow("Recording scheduled") else SheetRow("Record") { rec.record(ch, p) }
    rows += SheetRow(if (s.isFavorite(ch.id)) "Remove Channel from Favorites" else "Add Channel to Favorites") { s.toggleFavorite(ch.id) }
    Ui.sheet = Sheet(p.title, rows)
}

/* ---------------- Movies and Series ---------------- */

@Composable
private fun LibrarySection(nav: Nav, movies: Boolean) {
    val s = App.iptv
    val kind = if (movies) IptvGroup.Kind.Movie else IptvGroup.Kind.Series
    val groups = s.groups(kind)
    val gid = if (movies) Ui.movieGroup else Ui.seriesGroup
    fun setGroup(v: String) { if (movies) Ui.movieGroup = v else Ui.seriesGroup = v }
    val sorts = listOf("added" to "Recently Added", "name" to "Name", "rating" to "Rating", "year" to "Year")
    val sec = if (movies) Section.Movies else Section.Series
    val find = searching(sec)
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = D.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val name = when (gid) { "all" -> "All"; "favorites" -> "Favorites"; else -> groups.firstOrNull { it.id == gid }?.name ?: "All" }
            Pill("▤ $name", {
                Ui.sheet = Sheet(if (movies) "Movies" else "Series", listOf(SheetRow("All") { setGroup("all") }, SheetRow("Favorites") { setGroup("favorites") }) +
                    groups.map { g -> SheetRow(g.name) { withPin(g) { setGroup(g.id) } } })
            })
            Pill("⇅ " + sorts.first { it.first == Ui.sort }.second, {
                Ui.sheet = Sheet("Sort", sorts.map { (k, l) -> SheetRow(l) { Ui.sort = k } })
            })
            SectionSearch(sec, if (movies) "movies" else "series")
        }
        Spacer(Modifier.height(6.dp))
        if (movies) {
            var list = if (find) s.movies.filter { hits(sec, it.name, it.group, it.year?.toString()) }
                else when (gid) { "all" -> s.movies; "favorites" -> s.favoriteMovies(); else -> groups.firstOrNull { it.id == gid }?.let { s.moviesIn(it) } ?: s.movies }
            if (find && list.isEmpty()) { NoMatch(sec); return@Column }
            list = when (Ui.sort) {
                "name" -> list.sortedBy { it.name.lowercase() }
                "rating" -> list.sortedByDescending { it.rating ?: 0.0 }
                "year" -> list.sortedByDescending { it.year ?: 0 }
                else -> list.sortedByDescending { it.added ?: 0 }
            }
            Grid(list, { it.id }) { MoviePoster(it, nav) }
        } else {
            var list = if (find) s.series.filter { hits(sec, it.name, it.group, it.year?.toString()) }
                else when (gid) { "all" -> s.series; "favorites" -> s.favoriteSeries(); else -> groups.firstOrNull { it.id == gid }?.let { s.seriesIn(it) } ?: s.series }
            if (find && list.isEmpty()) { NoMatch(sec); return@Column }
            list = when (Ui.sort) {
                "name" -> list.sortedBy { it.name.lowercase() }
                "rating" -> list.sortedByDescending { it.rating ?: 0.0 }
                "year" -> list.sortedByDescending { it.year ?: 0 }
                else -> list
            }
            Grid(list, { it.id }) { SeriesPoster(it, nav) }
        }
    }
}

@Composable
private fun <E> Grid(list: List<E>, key: (E) -> Any, content: @Composable (E) -> Unit) {
    if (list.isEmpty()) { Box(Modifier.padding(D.gutter)) { T(if (App.iptv.isLoading()) "Loading…" else "Nothing here.", color = C.dim) }; return }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(D.posterW + 24.dp),
        contentPadding = PaddingValues(horizontal = D.gutter, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(list.size, key = { key(list[it]) }) { content(list[it]) }
    }
}

@Composable
fun IptvMovieScreen(nav: Nav, m: Movie) {
    val s = App.iptv
    val d by produceState<Details?>(null, m.id) { value = runCatching { s.details(m) }.getOrDefault(Details()) }
    val play = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        Backdrop(d?.backdrop ?: m.poster)
        Row(Modifier.fillMaxSize().padding(start = D.gutter, end = D.gutter, top = 90.dp), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Img(d?.poster ?: m.poster, Modifier.size(240.dp, 360.dp).clip(RoundedCornerShape(12.dp)))
            Column(Modifier.weight(1f)) {
                T(m.name, size = 38.sp, weight = FontWeight.Bold, maxLines = 2)
                Spacer(Modifier.height(8.dp))
                T(listOfNotNull(m.year?.toString(), d?.durationSec?.let { "${it / 60} min" }, (d?.rating ?: m.rating)?.let { "★ %.1f".format(it) }, d?.genre).joinToString("  ·  "),
                    size = 16.sp, color = C.dim)
                d?.plot?.let { Spacer(Modifier.height(14.dp)); T(it, Modifier.fillMaxWidth(0.8f), size = 16.sp, maxLines = 6, lineHeight = 22.sp) }
                d?.cast?.let { Spacer(Modifier.height(10.dp)); T("Cast: $it", size = 13.sp, color = C.dim, maxLines = 2) }
                d?.director?.let { T("Director: $it", size = 13.sp, color = C.dim, maxLines = 1) }
                Spacer(Modifier.height(24.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val pos = s.position(m.id)
                    Pill(if (pos > 0) "Resume ${formatTime(pos)}" else "Play", { nav.push(Screen.IptvPlayer(IptvPlay.Film(m))) }, focusRequester = play)
                    if (pos > 0) Pill("Play from Start", { s.savePosition(m.id, 0, 1); nav.push(Screen.IptvPlayer(IptvPlay.Film(m))) })
                    Pill(if (s.isFavorite(m.id)) "★ Favorite" else "☆ Favorite", { s.toggleFavorite(m.id) })
                    Pill("Download", { App.recorder.download(m) })
                }
            }
        }
        IptvOverlays()
    }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
}

@Composable
fun IptvSeriesScreen(nav: Nav, x: Series) {
    val s = App.iptv
    val d by produceState<Details?>(null, x.id) { value = runCatching { s.details(x) }.getOrDefault(Details()) }
    val eps = d?.episodes.orEmpty()
    val seasons = eps.map { it.season }.distinct().sorted()
    val resumeEp = eps.firstOrNull { s.position(it.id) > 0 } ?: s.resume.firstOrNull { it.seriesId == x.id }?.let { r -> eps.firstOrNull { it.id == r.episodeId } } ?: eps.firstOrNull()
    var season by remember(x.id, eps.size) { mutableStateOf(resumeEp?.season ?: seasons.firstOrNull() ?: 1) }
    val play = remember { FocusRequester() }
    Box(Modifier.fillMaxSize()) {
        Backdrop(d?.backdrop ?: x.poster)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = D.gutter, end = D.gutter, top = 90.dp, bottom = 60.dp)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Img(d?.poster ?: x.poster, Modifier.size(200.dp, 300.dp).clip(RoundedCornerShape(12.dp)))
                    Column(Modifier.weight(1f)) {
                        T(x.name, size = 38.sp, weight = FontWeight.Bold, maxLines = 2)
                        Spacer(Modifier.height(8.dp))
                        T(listOfNotNull(x.year?.toString(), (d?.rating ?: x.rating)?.let { "★ %.1f".format(it) }, d?.genre ?: x.genre).joinToString("  ·  "), size = 16.sp, color = C.dim)
                        (d?.plot ?: x.plot)?.let { Spacer(Modifier.height(14.dp)); T(it, Modifier.fillMaxWidth(0.8f), size = 16.sp, maxLines = 5, lineHeight = 22.sp) }
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            if (resumeEp != null) Pill((if (s.position(resumeEp.id) > 0) "Resume" else "Play") + " S${resumeEp.season} E${resumeEp.number}",
                                { nav.push(Screen.IptvPlayer(IptvPlay.Ep(x, resumeEp, eps))) }, focusRequester = play)
                            Pill(if (s.isFavorite(x.id)) "★ Favorite" else "☆ Favorite", { s.toggleFavorite(x.id) })
                            if (eps.isNotEmpty()) Pill("Download Season", { eps.filter { it.season == season }.forEach { App.recorder.download(x, it) } })
                        }
                    }
                }
            }
            if (d == null) item { Loading() }
            if (seasons.size > 1) item {
                LazyRow(contentPadding = PaddingValues(vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(seasons) { n -> Pill("Season $n", { season = n }, selected = n == season) }
                }
            }
            items(eps.filter { it.season == season }, key = { it.id }) { e -> EpisodeRow(nav, x, e, eps) }
        }
        IptvOverlays()
    }
    LaunchedEffect(resumeEp?.id) { delay(80); runCatching { play.requestFocus() } }
}

@Composable
private fun EpisodeRow(nav: Nav, x: Series, e: Episode, all: List<Episode>) {
    val s = App.iptv
    Focusable({ nav.push(Screen.IptvPlayer(IptvPlay.Ep(x, e, all))) }, Modifier.fillMaxWidth().padding(vertical = 6.dp)
        .onMenu { sheet(e.title, SheetRow("Download") { App.recorder.download(x, e) }) }, RoundedCornerShape(12.dp), 1.02f,
        onLongClick = { sheet(e.title, SheetRow("Download") { App.recorder.download(x, e) }) }, showBorder = false) { f ->
        Row(Modifier.fillMaxWidth().background(if (f) C.focus else Color.Transparent).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(200.dp, 112.dp).clip(RoundedCornerShape(8.dp)).background(C.surface)) {
                Img(e.thumb ?: x.poster, Modifier.fillMaxSize())
                val p = s.progress(e.id)
                if (p > 0) ProgressBar(p, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
            }
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                T("${e.number}. ${e.title}", size = 17.sp, weight = FontWeight.SemiBold, color = if (f) Color.Black else C.text, maxLines = 1)
                T(listOfNotNull(e.durationSec?.let { "${it / 60} min" }, e.plot).joinToString(" · "), size = 13.sp, color = if (f) Color.DarkGray else C.dim, maxLines = 2)
            }
        }
    }
}

/* ---------------- Favorites ---------------- */

@Composable
private fun FavoritesSection(nav: Nav) {
    val s = App.iptv
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = D.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Live TV", "Movies", "Series").forEachIndexed { i, l -> Pill(l, { Ui.favTab = i }, selected = Ui.favTab == i) }
            SectionSearch(Section.Favorites, "favourites")
        }
        Spacer(Modifier.height(8.dp))
        when (Ui.favTab) {
            0 -> {
                val chans = s.favoriteChannels().filter { hits(Section.Favorites, it.name, it.group, s.nowNext(it).first?.title) }
                if (chans.isEmpty() && searching(Section.Favorites)) NoMatch(Section.Favorites)
                else if (chans.isEmpty()) T("No favourite channels yet. Press Menu (or hold Select) on a channel and choose Add to Favorites.", Modifier.padding(D.gutter), color = C.dim)
                else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = D.gutter, vertical = 8.dp)) {
                    items(chans, key = { it.id }) { ch ->
                        ListRow(ch.name, s.nowNext(ch).first?.title, height = 60.dp,
                            leading = { ChannelLogo(ch, Modifier.size(64.dp, 36.dp)); Spacer(Modifier.width(12.dp)) },
                            trailing = { Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { s.tags(ch).forEach { QualityBadge(it) } } },
                            onOptions = { channelOptions(nav, ch) }) { nav.push(Screen.IptvPlayer(IptvPlay.Live(ch, chans))) }
                    }
                }
            }
            1 -> s.favoriteMovies().filter { hits(Section.Favorites, it.name, it.group) }.let { l ->
                if (l.isEmpty() && searching(Section.Favorites)) NoMatch(Section.Favorites) else Grid(l, { it.id }) { MoviePoster(it, nav) }
            }
            else -> s.favoriteSeries().filter { hits(Section.Favorites, it.name, it.group) }.let { l ->
                if (l.isEmpty() && searching(Section.Favorites)) NoMatch(Section.Favorites) else Grid(l, { it.id }) { SeriesPoster(it, nav) }
            }
        }
    }
}

/* ---------------- Search ---------------- */

@Composable
private fun SearchSection(nav: Nav) {
    val s = App.iptv
    var debounced by remember { mutableStateOf(Ui.query) }
    LaunchedEffect(Ui.query) { delay(350); debounced = Ui.query }
    val r = remember(debounced, Ui.searchTags, Ui.scope, s.channels, s.guide) { s.search(debounced, Ui.searchTags, Ui.scope) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 60.dp)) {
        item {
            Row(Modifier.padding(horizontal = D.gutter), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    Ui.query, { Ui.query = it },
                    Modifier.width(460.dp).clip(RoundedCornerShape(50)).background(C.surfaceHi).padding(horizontal = 22.dp, vertical = 12.dp),
                    textStyle = TextStyle(color = C.text, fontSize = 18.sp), cursorBrush = SolidColor(C.accent), singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    decorationBox = { inner -> if (Ui.query.isEmpty()) T("Search channels, programmes, movies, series", size = 16.sp, color = C.faint); inner() },
                )
                SearchScope.entries.forEach { sc -> Pill(sc.label, { Ui.scope = sc }, selected = Ui.scope == sc) }
            }
        }
        item { Spacer(Modifier.height(6.dp)); TagRow(Ui.searchTags) { Ui.searchTags = it } }
        item { IShelf("Channels", null, r.channels.take(60), { it.id }) { ChannelCard(it, r.channels, nav) } }
        if (r.programmes.isNotEmpty()) item { T("On TV", Modifier.padding(start = D.gutter, top = 10.dp, bottom = 6.dp), size = 20.sp, weight = FontWeight.SemiBold) }
        items(r.programmes, key = { it.first.id + it.second.start }) { (ch, p) ->
            val airing = p.start <= System.currentTimeMillis()
            Box(Modifier.padding(horizontal = D.gutter)) {
                ListRow(p.title, listOfNotNull(ch.name, p.category).joinToString(" · "), height = 58.dp,
                    leading = {
                        Column(Modifier.width(110.dp)) {
                            T(if (airing) "Now" else day(p.start), size = 12.sp, color = if (airing) C.bad else C.dim)
                            T(clock(p.start), size = 16.sp, weight = FontWeight.SemiBold)
                        }
                        ChannelLogo(ch, Modifier.size(56.dp, 32.dp), 11.sp); Spacer(Modifier.width(12.dp))
                    },
                    trailing = { Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { s.tags(ch).forEach { QualityBadge(it) } } },
                ) { programmeSheet(nav, ch, p, r.channels.ifEmpty { listOf(ch) }) }
            }
        }
        item { IShelf("Movies", null, r.movies, { it.id }) { MoviePoster(it, nav) } }
        item { IShelf("Series", null, r.series, { it.id }) { SeriesPoster(it, nav) } }
        if (r.channels.isEmpty() && r.programmes.isEmpty() && r.movies.isEmpty() && r.series.isEmpty() && (debounced.length >= 2 || Ui.searchTags.isNotEmpty())) item {
            T("No results", Modifier.padding(D.gutter), color = C.dim)
        }
    }
}

/* ---------------- Downloads and recordings ---------------- */

@Composable
private fun JobsSection(nav: Nav, kind: Recorder.Kind) {
    val rec = App.recorder
    val s = App.iptv
    val sec = if (kind == Recorder.Kind.Download) Section.Downloads else Section.Recordings
    val all = rec.jobs.filter { it.kind == kind }
    val list = all.filter { hits(sec, it.title, it.subtitle) }
    if (all.isNotEmpty()) Box(Modifier.padding(start = D.gutter, bottom = 4.dp)) { SectionSearch(sec, if (kind == Recorder.Kind.Download) "downloads" else "recordings") }
    if (all.isNotEmpty() && list.isEmpty()) { NoMatch(sec); return }
    if (list.isEmpty()) {
        T(if (kind == Recorder.Kind.Download) "No downloads. Download movies and episodes from their pages, or past programmes from the TV Guide."
          else "No recordings. Record a channel from its options or the player, or schedule a programme in the TV Guide.",
            Modifier.padding(D.gutter), color = C.dim)
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = D.gutter, vertical = 8.dp)) {
        item { T("Saved to ${rec.folder.absolutePath}", Modifier.padding(bottom = 10.dp), size = 12.sp, color = C.faint) }
        items(list, key = { it.id }) { j ->
            val state = when (j.state) {
                Recorder.State.Scheduled -> "Scheduled · ${day(j.start)} ${clock(j.start)}"
                Recorder.State.Running -> if (kind == Recorder.Kind.Recording) "● Recording" else "Downloading"
                Recorder.State.Done -> "Ready"
                Recorder.State.Failed -> "Failed"
                Recorder.State.Cancelled -> "Cancelled"
            }
            val size = if (j.received > 0) "${j.received / 1_048_576} MB" else null
            val pct = j.expected?.let { "${j.received * 100 / it}%" }
            fun options() {
                val rows = ArrayList<SheetRow>()
                if (j.state == Recorder.State.Done) rows += SheetRow("Play") { nav.push(Screen.IptvPlayer(IptvPlay.File(j))) }
                if (j.state == Recorder.State.Running || j.state == Recorder.State.Scheduled) rows += SheetRow(if (j.state == Recorder.State.Running) "Stop" else "Cancel") { rec.stop(j.id) }
                if (j.state == Recorder.State.Failed || j.state == Recorder.State.Cancelled) rows += SheetRow("Try Again") { rec.retry(j.id) }
                rows += SheetRow("Delete") { rec.remove(j.id) }
                Ui.sheet = Sheet(j.title, rows)
            }
            ListRow(j.title, listOfNotNull(j.subtitle, state, size, pct, j.error).joinToString(" · "), height = 64.dp,
                leading = {
                    val jch = j.channelId?.let { s.channel(it) }
                    if (jch != null && j.poster == null) ChannelLogo(jch, Modifier.size(64.dp, 40.dp)) else Img(j.poster, Modifier.size(64.dp, 40.dp), ContentScale.Fit)
                    Spacer(Modifier.width(12.dp))
                },
                onOptions = { options() }) {
                if (j.state == Recorder.State.Done) nav.push(Screen.IptvPlayer(IptvPlay.File(j))) else options()
            }
        }
    }
}

/* ---------------- Playlists ---------------- */

@Composable
private fun PlaylistsSection(nav: Nav) {
    val s = App.iptv
    val find = searching(Section.Playlists)
    fun ok(vararg v: String?) = hits(Section.Playlists, *v)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = D.gutter, vertical = 8.dp)) {
        item { Box(Modifier.padding(bottom = 6.dp)) { SectionSearch(Section.Playlists, "playlists and settings") } }
        if (!find) item { ListRow("＋ Add Playlist") { nav.push(Screen.IptvPlaylist(null)) } }
        items(s.playlists.filter { ok(it.name, it.url, if (it.kind == Playlist.Kind.Xtream) "Xtream Codes" else "M3U") }, key = { it.id }) { p ->
            val c = s.content(p.id)
            val a = c?.account
            val detail = listOfNotNull(
                if (p.kind == Playlist.Kind.Xtream) "Xtream Codes" else "M3U playlist", if (!p.enabled) "Off" else null,
                c?.let { "${it.channels.size} channels · ${it.movies.size} movies · ${it.series.size} series" },
                a?.status, a?.expires?.let { "expires " + SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(it)) },
                a?.maxConnections?.let { "${a.activeConnections ?: 0}/$it connections" },
                if (s.isLoading(p.id)) "Refreshing…" else null, s.errors[p.id],
            ).joinToString(" · ")
            ListRow(p.name, detail, height = 60.dp, leading = {
                Box(Modifier.padding(end = 14.dp).size(14.dp).clip(RoundedCornerShape(50)).background(Color(s.color(p))))
            }) {
                Ui.sheet = Sheet(p.name, listOf(
                    SheetRow("Refresh") { s.refreshLater(p.id) },
                    SheetRow("Edit") { nav.push(Screen.IptvPlaylist(p)) },
                    SheetRow(if (p.enabled) "Turn Off" else "Turn On") { s.setEnabled(p.id, !p.enabled) },
                    SheetRow("Delete") { s.deletePlaylist(p.id) },
                ))
            }
        }
        item { Header("TV Guide") }
        item {
            ListRow("Refresh Guide", when {
                s.guideLoading -> "Loading…"
                s.guide.updated > 0 -> "Updated ${day(s.guide.updated)} ${clock(s.guide.updated)}"
                else -> s.errors["guide"] ?: "Not loaded"
            }) { s.refreshGuide() }
        }
        item { ListRow("Refresh Everything") { s.refreshAll() } }
        item { Header("Parental Control") }
        if (s.pin.isEmpty()) item { ListRow("Set a PIN", "Locked groups ask for it") { Ui.input = IptvInput("New PIN", "", number = true, secret = true) { s.savePin(it) } } }
        else {
            item {
                ListRow(if (s.unlocked) "Lock Locked Groups Again" else "Unlock Locked Groups") {
                    if (s.unlocked) s.lock() else Ui.input = IptvInput("Enter PIN", "", number = true, secret = true) { s.unlock(it) }
                }
            }
            item { ListRow("Change PIN") { Ui.input = IptvInput("New PIN", "", number = true, secret = true) { s.savePin(it) } } }
        }
        val hidden = (s.groups(IptvGroup.Kind.Live, true) + s.groups(IptvGroup.Kind.Movie, true) + s.groups(IptvGroup.Kind.Series, true)).filter { it.id in s.hidden }
        if (hidden.isNotEmpty()) {
            item { Header("Hidden Groups (Select to show again)") }
            items(hidden, key = { "h" + it.id }) { g ->
                ListRow(g.name, when (g.kind) { IptvGroup.Kind.Live -> "Live TV"; IptvGroup.Kind.Movie -> "Movies"; else -> "Series" }) { s.setHidden(g, false) }
            }
        }
        if (App.plex.token == null) item {
            Column {
                Header("Plex")
                ListRow("Sign in to Plex", "Use Lumen with your Plex libraries too") { App.settings.iptvOnly = false }
            }
        }
        item { Header("Library") }
        item { ListRow("Clear Recently Watched") { s.clearRecents() } }
        items(s.reminders, key = { "r" + it.channelId + it.start }) { r ->
            ListRow("🔔 ${r.title}", "${r.channelName} · ${day(r.start)} ${clock(r.start)}") {}
        }
    }
}

@Composable
private fun Header(text: String) = T(text.uppercase(), Modifier.padding(start = 14.dp, top = 22.dp, bottom = 6.dp), size = 13.sp, color = C.faint, weight = FontWeight.SemiBold)

/** Add or edit a source: each field opens the TV keyboard. */
@Composable
fun IptvPlaylistScreen(nav: Nav, existing: Playlist?) {
    val s = App.iptv
    var p by remember { mutableStateOf(existing ?: Playlist("", "", Playlist.Kind.Xtream, "")) }
    val first = remember { FocusRequester() }
    fun field(title: String, value: String, secret: Boolean = false, set: (String) -> Unit) { Ui.input = IptvInput(title, value, secret = secret, done = set) }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = D.gutter, end = D.gutter, top = 60.dp, bottom = 60.dp)) {
            item { T(if (existing == null) "Add Playlist" else "Edit Playlist", Modifier.padding(bottom = 16.dp), size = 32.sp, weight = FontWeight.Bold) }
            item {
                Focusable({ p = p.copy(kind = if (p.kind == Playlist.Kind.Xtream) Playlist.Kind.M3U else Playlist.Kind.Xtream) },
                    Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), 1.02f, first, showBorder = false) { f -> FormRow("Type", if (p.kind == Playlist.Kind.Xtream) "Xtream Codes" else "M3U playlist", f) }
            }
            item { FormItem("Name", p.name.ifEmpty { "—" }) { field("Name", p.name) { p = p.copy(name = it) } } }
            item { FormItem(if (p.kind == Playlist.Kind.Xtream) "Server (http://host:port)" else "Playlist address", p.url.ifEmpty { "—" }) { field("Address", p.url) { p = p.copy(url = it.trim()) } } }
            if (p.kind == Playlist.Kind.Xtream) {
                item { FormItem("Username", p.username.ifEmpty { "—" }) { field("Username", p.username) { p = p.copy(username = it.trim()) } } }
                item { FormItem("Password", if (p.password.isEmpty()) "—" else "••••••") { field("Password", p.password, secret = true) { p = p.copy(password = it) } } }
            }
            item { FormItem("Guide address (XMLTV, optional)", p.epgUrl.ifEmpty { "—" }) { field("Guide address", p.epgUrl) { p = p.copy(epgUrl = it.trim()) } } }
            item { FormItem("User agent (optional)", p.userAgent.ifEmpty { "—" }) { field("User agent", p.userAgent) { p = p.copy(userAgent = it.trim()) } } }
            item {
                val ok = p.url.isNotBlank() && (p.kind == Playlist.Kind.M3U || (p.username.isNotBlank() && p.password.isNotEmpty()))
                Row(Modifier.padding(top = 24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Pill(if (existing == null) "Add Playlist" else "Save", {
                        if (!ok) return@Pill
                        val name = p.name.ifBlank { p.url.removePrefix("http://").removePrefix("https://").split('/', ':').firstOrNull()?.ifEmpty { null } ?: "Playlist" }
                        s.savePlaylist(p.copy(name = name))
                        Ui.section = Section.Home
                        nav.pop()
                    }, selected = ok)
                    Pill("Cancel", { nav.pop() })
                }
                if (!ok) T(if (p.kind == Playlist.Kind.Xtream) "Fill in the server, username and password." else "Fill in the playlist address.", Modifier.padding(top = 10.dp), size = 13.sp, color = C.dim)
            }
        }
        IptvOverlays()
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
private fun FormItem(label: String, value: String, onClick: () -> Unit) {
    Focusable(onClick, Modifier.fillMaxWidth().padding(vertical = 3.dp), RoundedCornerShape(10.dp), 1.02f, showBorder = false) { f -> FormRow(label, value, f) }
}

@Composable
private fun FormRow(label: String, value: String, f: Boolean) {
    Row(Modifier.fillMaxWidth().background(if (f) C.focus else Color(0x14FFFFFF)).padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        T(label, Modifier.width(320.dp), size = 16.sp, color = if (f) Color.Black else C.dim)
        T(value, Modifier.weight(1f), size = 16.sp, color = if (f) Color.Black else C.text, maxLines = 1)
    }
}

/** A guide reminder that's due, shown on top of any screen. */
@Composable
fun IptvReminder(nav: Nav) {
    val s = App.iptv
    val r = s.dueReminder ?: return
    val ch = s.channel(r.channelId)
    ChoiceDialog(r.title, listOf(Choice("Starting now on ${r.channelName}", header = true), Choice("Watch"), Choice("Dismiss")), { i ->
        s.dueReminder = null
        if (i == 1 && ch != null) nav.push(Screen.IptvPlayer(IptvPlay.Live(ch, s.channels)))
    }) { s.dueReminder = null }
}
