package app.plexhdr.shield.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.iptv.IptvPlay
import app.plexhdr.shield.iptv.IptvPlayerScreen
import app.plexhdr.shield.iptv.Movie
import app.plexhdr.shield.iptv.Playlist
import app.plexhdr.shield.iptv.Series
import app.plexhdr.shield.player.PlayerScreen
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.plex.PlexPerson
import app.plexhdr.shield.plex.PlexSection
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data class Library(val section: PlexSection) : Screen
    data class Detail(val item: PlexItem) : Screen
    data class Show(val item: PlexItem) : Screen
    data class Person(val person: PlexPerson) : Screen
    data class Collection(val item: PlexItem) : Screen
    data object Search : Screen
    data object Settings : Screen
    data object Iptv : Screen
    data class IptvMovie(val movie: Movie) : Screen
    data class IptvSeries(val series: Series) : Screen
    data class IptvPlaylist(val playlist: Playlist?) : Screen
    data class IptvPlayer(val play: IptvPlay) : Screen
    data class Player(
        val item: PlexItem, val startMs: Long? = null, val mediaIndex: Int? = null,
        val audioId: Int? = null, val subtitleId: Int? = null,
    ) : Screen
}

/** The back stack. The first entry is the current tab. Each entry keeps its page's state while other pages are on top. */
class Nav(first: Screen = Screen.Home) {
    val entries = mutableStateListOf(NavEntry(first))
    val stack: List<Screen> get() = entries.map { it.screen }
    val top: Screen get() = entries.last().screen
    val size: Int get() = entries.size
    /** Called with each entry that leaves the stack, so its saved state is forgotten. */
    var onDrop: (NavEntry) -> Unit = {}
    fun push(s: Screen) { entries.add(NavEntry(s)) }
    fun pop() { if (entries.size > 1) onDrop(entries.removeAt(entries.lastIndex)) }
    /** Swaps the page on top for another (e.g. the next episode's details). */
    fun replace(s: Screen) { onDrop(entries.set(entries.lastIndex, NavEntry(s))) }
    fun tab(s: Screen) { entries.forEach { onDrop(it) }; entries.clear(); entries.add(NavEntry(s)) }

    /** Movies and episodes open their details page, shows their show page, collections their list. */
    fun open(item: PlexItem) = when (item.type) {
        "show", "season" -> push(Screen.Show(item))
        "collection" -> push(Screen.Collection(item))
        else -> push(Screen.Detail(item))
    }

    fun play(item: PlexItem, startMs: Long? = null, mediaIndex: Int? = null, audioId: Int? = null, subtitleId: Int? = null) =
        push(Screen.Player(item, startMs, mediaIndex, audioId, subtitleId))
}

@Composable
fun AppRoot() {
    val plex = App.plex
    // The startup intro plays once per launch, over the app while it loads (Settings → Startup can turn it off).
    var intro by rememberSaveable { mutableStateOf(App.settings.introAnimation) }
    Box(Modifier.fillMaxSize().background(C.bg)) {
        when {
            plex.token == null && App.settings.iptvOnly -> Main(iptvOnly = true)
            plex.token == null -> SignInScreen()
            plex.server == null -> ServerPicker()
            else -> key(plex.server, plex.user) { Main() }
        }
        if (intro) IntroScreen(App.settings.introSound) { intro = false }
    }
}

@Composable
private fun Main(iptvOnly: Boolean = false) {
    val settings = App.settings
    // Plex and Live TV are two halves of the app, each with its own tabs; it opens in the one used last.
    val liveTv = iptvOnly || settings.liveTvMode
    val nav = remember { Nav(if (liveTv) Screen.Iptv else Screen.Home) }
    var sections by remember { mutableStateOf<List<PlexSection>>(emptyList()) }
    // Keep trying until the server answers: the Shield wakes from sleep before Wi-Fi and the server are reachable,
    // and a single failed call used to leave Home empty until the app was restarted.
    var resumes by remember { mutableStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    LaunchedEffect(resumes) {
        if (iptvOnly) return@LaunchedEffect
        var wait = 1000L
        while (true) {
            val got = runCatching { App.plex.sections().filter { it.isVideo } }.getOrNull()
            if (got != null) { if (got != sections) sections = got; break }
            delay(wait); wait = (wait * 2).coerceAtMost(15_000)
        }
    }

    fun goHome() {
        if (liveTv) { iptvSection = Section.Home; if (nav.size > 1 || nav.top != Screen.Iptv) nav.tab(Screen.Iptv) }
        else nav.tab(Screen.Home)
    }
    fun setMode(live: Boolean) {
        if (!live && iptvOnly) { settings.iptvOnly = false; return }   // Plex leads to signing in
        if (!iptvOnly) settings.liveTvMode = live
        if (live) { iptvSection = Section.Home; nav.tab(Screen.Iptv) } else nav.tab(Screen.Home)
    }

    // Back: closes the page on top; on a tab, goes to that half's Home; on Home, a second Back within 2 s exits.
    val atHome = nav.size == 1 && (if (liveTv) nav.top == Screen.Iptv && iptvSection == Section.Home else nav.top == Screen.Home)
    var exitArmed by remember { mutableStateOf(false) }
    LaunchedEffect(exitArmed) { if (exitArmed) { delay(2000); exitArmed = false } }
    BackHandler(enabled = !(atHome && exitArmed)) {
        when {
            nav.size > 1 -> nav.pop()
            !atHome -> goHome()
            else -> exitArmed = true
        }
    }

    val holder = rememberSaveableStateHolder()
    nav.onDrop = { holder.removeState(it.id) }
    val entry = nav.entries.last()
    val top = entry.screen
    val isTab = nav.size == 1
    Box(Modifier.fillMaxSize()) {
        // Pages under the top one leave the composition but keep their state: their saved state (scroll positions)
        // in the holder, their loaded data in the entry (retain), and the element that had focus.
        key(entry.id) {
            remember { entry.onShow(); 0 }
            holder.SaveableStateProvider(entry.id) {
                CompositionLocalProvider(LocalNavEntry provides entry) {
                    when (top) {
                        Screen.Home -> HomeScreen(nav, sections)
                        is Screen.Library -> LibraryScreen(nav, top.section)
                        is Screen.Detail -> DetailScreen(nav, top.item)
                        is Screen.Show -> ShowScreen(nav, top.item)
                        is Screen.Person -> PersonScreen(nav, top.person)
                        is Screen.Collection -> CollectionScreen(nav, top.item)
                        Screen.Search -> SearchScreen(nav)
                        Screen.Settings -> SettingsScreen(nav)
                        is Screen.Player -> PlayerScreen(top.item, top.startMs, top.mediaIndex, top.audioId, top.subtitleId) { nav.pop() }
                        Screen.Iptv -> IptvHome(nav)
                        is Screen.IptvMovie -> IptvMovieScreen(nav, top.movie)
                        is Screen.IptvSeries -> IptvSeriesScreen(nav, top.series)
                        is Screen.IptvPlaylist -> IptvPlaylistScreen(nav, top.playlist)
                        is Screen.IptvPlayer -> IptvPlayerScreen(top.play) { nav.pop() }
                    }
                }
            }
            LaunchedEffect(Unit) { if (entry.restoring) entry.restoreFocus() }
        }
        if (isTab) TabBar(nav, sections, iptvOnly, liveTv, ::setMode)
        if (top !is Screen.Player && top !is Screen.IptvPlayer) IptvReminder(nav)
        if (exitArmed && atHome) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xE62C2C34)).padding(horizontal = 26.dp, vertical = 12.dp),
            ) { T("Press Back again to exit", size = 16.sp, weight = FontWeight.SemiBold) }
        }
        GlobalHosts(nav)   // card menu, lumen:// deep links, Play Next updates (ui/GlobalHosts.kt)
    }
}

private class Tab(val label: String, val selected: Boolean, val open: () -> Unit)

@Composable
private fun TabBar(nav: Nav, sections: List<PlexSection>, iptvOnly: Boolean, liveTv: Boolean, setMode: (Boolean) -> Unit) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    val top = nav.top
    // Each half has its own tabs. Live TV's sections are tabs of one page, so they change the section, not the page.
    val tabs: List<Tab> = if (liveTv) {
        iptvTabs.map { sec ->
            Tab(sec.label, top == Screen.Iptv && (iptvSection == sec || (sec == Section.Recordings && iptvSection == Section.Downloads))) {
                iptvSection = sec
                if (nav.size > 1 || top != Screen.Iptv) nav.tab(Screen.Iptv)
            }
        } + Tab("Settings", top == Screen.Settings) { nav.tab(Screen.Settings) }
    } else {
        listOf(Tab("Home", top == Screen.Home) { nav.tab(Screen.Home) }) +
            sections.map { s -> Tab(s.title, top == Screen.Library(s)) { nav.tab(Screen.Library(s)) } } +
            listOf(Tab("Search", top == Screen.Search) { nav.tab(Screen.Search) }, Tab("Settings", top == Screen.Settings) { nav.tab(Screen.Settings) })
    }
    Row(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .padding(horizontal = D.gutter, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Coming up into the bar lands on the current tab, not on whichever tab or switch was focused last.
        val current = remember { FocusRequester() }
        var inBar by remember { mutableStateOf(false) }
        Row(
            Modifier.weight(1f).onFocusChanged { if (it.hasFocus && !inBar) runCatching { current.requestFocus() }; inBar = it.hasFocus },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Segmented(listOf("Plex", "Live TV"), if (liveTv) 1 else 0, { setMode(it == 1) })
            Spacer(Modifier.width(20.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.weight(1f)) {
                items(tabs, key = { (if (liveTv) "live-" else "plex-") + it.label }) { t ->
                    TabPill(t.label, t.open, t.selected, if (t.selected) current else null)
                }
            }
        }
        Spacer(Modifier.width(18.dp))
        App.plex.user?.let { T(it.title, size = 15.sp, color = C.dim, maxLines = 1); Spacer(Modifier.width(18.dp)) }
        T(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), size = 20.sp, weight = FontWeight.SemiBold)
    }
}

/** A top bar tab: a slimmer [Pill]. */
@Composable
private fun TabPill(label: String, onClick: () -> Unit, selected: Boolean, focusRequester: FocusRequester?) {
    Focusable(onClick, shape = RoundedCornerShape(50), focusedScale = 1.06f, focusRequester = focusRequester, showBorder = false) { f ->
        Box(Modifier.background(if (f) C.focus else if (selected) C.surfaceHi else Color.Transparent).padding(horizontal = 16.dp, vertical = 9.dp)) {
            T(label, size = 16.sp, weight = FontWeight.SemiBold, color = if (f) Color.Black else if (selected) C.text else C.dim, maxLines = 1)
        }
    }
}

/** A compact two-or-more-way switch; the active option is filled, the focused one white. */
@Composable
internal fun Segmented(options: List<String>, active: Int, onPick: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFFFFF)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            key(i) {
                Focusable({ onPick(i) }, shape = RoundedCornerShape(50), focusedScale = 1f, showBorder = false) { f ->
                    Box(Modifier.background(if (f) C.focus else if (i == active) C.accent else Color.Transparent).padding(horizontal = 16.dp, vertical = 7.dp)) {
                        T(label, size = 15.sp, weight = FontWeight.SemiBold, color = if (f || i == active) Color.Black else C.dim, maxLines = 1)
                    }
                }
            }
        }
    }
}
