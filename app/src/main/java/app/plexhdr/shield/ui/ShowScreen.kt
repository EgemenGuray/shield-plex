package app.plexhdr.shield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import kotlinx.coroutines.launch

/** Show page: backdrop, play next episode, seasons, episodes, cast, more like this. */
@Composable
fun ShowScreen(nav: Nav, initial: PlexItem) {
    val plex = App.plex
    val scope = rememberCoroutineScope()
    var show by remember { mutableStateOf<PlexItem?>(null) }
    var seasons by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var seasonIndex by remember { mutableIntStateOf(0) }
    var episodes by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var similar by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(initial.id, refresh) {
        // A season opens its show, on that season.
        val showKey = if (initial.type == "season") initial.parentRatingKey else initial.ratingKey
        val s = showKey?.let { runCatching { plex.metadata(it) }.getOrNull() } ?: initial
        show = s
        seasons = s.ratingKey?.let { runCatching { plex.children(it) }.getOrDefault(emptyList()) } ?: emptyList()
        if (initial.type == "season") seasonIndex = seasons.indexOfFirst { it.ratingKey == initial.ratingKey }.coerceAtLeast(0)
        else s.onDeck?.parentRatingKey?.let { k -> seasonIndex = seasons.indexOfFirst { it.ratingKey == k }.coerceAtLeast(0) }
        similar = s.ratingKey?.let { plex.similar(it) } ?: emptyList()
    }
    LaunchedEffect(seasons, seasonIndex) {
        episodes = seasons.getOrNull(seasonIndex)?.ratingKey?.let { runCatching { plex.children(it) }.getOrDefault(emptyList()) } ?: emptyList()
    }

    val s = show ?: run { Loading(); return }
    val art by rememberArtwork(s)
    val ratings by rememberRatings(s)
    val next = s.onDeck ?: episodes.firstOrNull { !it.isWatched } ?: episodes.firstOrNull()
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }

    Box(Modifier.fillMaxSize()) {
        Backdrop(backdropUrl(s, art))
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().padding(start = D.gutter, end = D.gutter, top = 110.dp)) {
                    val logo = logoUrl(s, art)
                    if (logo != null) Img(logo, Modifier.height(120.dp).width(440.dp), ContentScale.Fit)
                    else T(s.title, size = 44.sp, weight = FontWeight.Bold, maxLines = 2)
                    Spacer(Modifier.height(12.dp))
                    T(listOfNotNull(s.year?.toString(), s.contentRating, s.childCount?.let { "$it seasons" }, s.genres.take(3).joinToString(", ").ifEmpty { null }, s.studio)
                        .joinToString("  ·  "), size = 16.sp, color = C.dim)
                    ratings?.let { Spacer(Modifier.height(12.dp)); RatingsRow(it) }
                    Spacer(Modifier.height(14.dp))
                    T(s.summary ?: "", Modifier.fillMaxWidth(0.55f), size = 16.sp, maxLines = 4, lineHeight = 22.sp)
                    Spacer(Modifier.height(22.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (next != null) item {
                            val label = (if (next.viewOffsetMs > 0) "Resume " else "Play ") + (next.episodeLabel ?: "")
                            Pill(label, { nav.play(next) }, focusRequester = play, icon = "▶")
                        }
                        item {
                            Pill(if (s.isWatched) "Mark Unwatched" else "Mark Watched", {
                                scope.launch { plex.setWatched(s, !s.isWatched); refresh++ }
                            }, focusRequester = if (next == null) play else null)
                        }
                    }
                }
            }
            if (seasons.size > 1) item {
                LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(seasons.size) { i ->
                        // Moving onto a season loads it.
                        Pill(seasons[i].title, { seasonIndex = i }, selected = i == seasonIndex, onFocused = { seasonIndex = i })
                    }
                }
            }
            item {
                Shelf(null, episodes, { it.id }) { ep ->
                    WideCard("${ep.index ?: ""}. ${ep.title}", listOfNotNull(formatRuntime(ep.durationMs), ep.summary).joinToString(" · "),
                        plexImage(ep.thumb, 600, 338), ep.progress, { nav.push(Screen.Detail(ep)) })
                }
            }
            val people = castAndCrew(s)
            if (people.isNotEmpty()) item { CastRow(nav, people) }
            if (similar.isNotEmpty()) item { ItemShelf("More Like This", similar, nav::open) }
            item { Spacer(Modifier.height(60.dp)) }
        }
    }
}
