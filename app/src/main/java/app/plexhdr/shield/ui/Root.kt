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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** A simple back stack. The first entry is the current tab. */
class Nav(first: Screen = Screen.Home) {
    val stack = mutableStateListOf<Screen>(first)
    val top: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(s: Screen) { stack.clear(); stack.add(s) }

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
    val home = if (iptvOnly) Screen.Iptv else Screen.Home
    val nav = remember { Nav(home) }
    var sections by remember { mutableStateOf<List<PlexSection>>(emptyList()) }
    LaunchedEffect(Unit) { if (!iptvOnly) sections = runCatching { App.plex.sections().filter { it.isVideo } }.getOrDefault(emptyList()) }

    BackHandler(enabled = nav.stack.size > 1 || nav.top != home) {
        if (nav.stack.size > 1) nav.pop() else nav.tab(home)
    }

    val top = nav.top
    val isTab = nav.stack.size == 1
    Box(Modifier.fillMaxSize()) {
        // Each screen keeps its state while it's on the stack only as long as it's on top; that is enough here.
        key(nav.stack.size, top) {
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
        if (isTab) TabBar(nav, sections, iptvOnly)
        if (top !is Screen.Player && top !is Screen.IptvPlayer) IptvReminder(nav)
    }
}

@Composable
private fun TabBar(nav: Nav, sections: List<PlexSection>, iptvOnly: Boolean) {
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) { while (true) { now = Date(); delay(15_000) } }
    val tabs: List<Pair<String, Screen>> = if (iptvOnly) listOf("Live TV" to Screen.Iptv, "Settings" to Screen.Settings)
        else listOf("Home" to Screen.Home, "Live TV" to Screen.Iptv) +
            sections.map { it.title to Screen.Library(it) } + listOf("Search" to Screen.Search, "Settings" to Screen.Settings)
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
            .padding(horizontal = D.gutter, vertical = 18.dp),
    ) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.align(Alignment.CenterStart)) {
            items(tabs, key = { it.first + it.second.hashCode() }) { (label, screen) ->
                val selected = nav.top == screen
                Pill(label, { nav.tab(screen) }, selected = selected)
            }
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
            App.plex.user?.let { T(it.title, size = 15.sp, color = C.dim); Spacer(Modifier.width(18.dp)) }
            T(SimpleDateFormat("HH:mm", Locale.getDefault()).format(now), size = 20.sp, weight = FontWeight.SemiBold)
        }
    }
}
