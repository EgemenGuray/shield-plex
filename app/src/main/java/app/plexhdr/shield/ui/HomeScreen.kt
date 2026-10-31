package app.plexhdr.shield.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.plex.PlexSection
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** Home: a full-screen top shelf, then Continue Watching and Recently Added for every library. */
@Composable
fun HomeScreen(nav: Nav, sections: List<PlexSection>) {
    var onDeck by remember { mutableStateOf<List<PlexItem>>(emptyList()) }
    var recent by remember { mutableStateOf<List<Pair<PlexSection, List<PlexItem>>>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(sections) {
        onDeck = runCatching { App.plex.onDeck() }.getOrDefault(emptyList())
        recent = coroutineScope {
            sections.map { s -> async { s to runCatching { App.plex.recentlyAdded(s.id, 24) }.getOrDefault(emptyList()) } }.awaitAll()
        }
        loaded = true
    }
    // The top shelf: what you're watching, then the newest movies and shows.
    val featured = remember(onDeck, recent) {
        (onDeck.take(4) + recent.flatMap { it.second.take(6) }.filter { it.type == "movie" || it.type == "show" || it.type == "season" })
            .distinctBy { it.grandparentRatingKey ?: it.parentRatingKey?.takeIf { _ -> it.type == "season" } ?: it.id }.take(10)
    }
    var heroIndex by remember { mutableStateOf(0) }
    var focusedItem by remember { mutableStateOf<PlexItem?>(null) }
    var heroFocused by remember { mutableStateOf(true) }
    val hero = featured.getOrNull(heroIndex)
    val bgItem = if (heroFocused) hero else focusedItem ?: hero
    val art by rememberArtwork(bgItem)

    LaunchedEffect(featured.size, heroIndex, heroFocused) {
        if (featured.size < 2 || !heroFocused) return@LaunchedEffect
        delay(9000)
        heroIndex = (heroIndex + 1) % featured.size
    }

    Box(Modifier.fillMaxSize()) {
        Crossfade(backdropUrl(bgItem, art), animationSpec = tween(700), label = "home-bg") { Backdrop(it) }
        if (!loaded) { Loading(); return@Box }
        LazyColumn(Modifier.fillMaxSize()) {
            if (hero != null) item(key = "hero") {
                Hero(nav, hero, onFocus = { heroFocused = true },
                    prev = { heroIndex = (heroIndex - 1 + featured.size) % featured.size },
                    next = { heroIndex = (heroIndex + 1) % featured.size })
            }
            item(key = "deck") {
                ItemShelf("Continue Watching", onDeck, nav::open, wide = true) { focusedItem = it; heroFocused = false }
            }
            recent.forEach { (s, list) ->
                item(key = "recent-${s.id}") {
                    ItemShelf("Recently Added in ${s.title}", list, nav::open) { focusedItem = it; heroFocused = false }
                }
            }
            item(key = "end") { Spacer(Modifier.height(60.dp)) }
        }
    }
}

@Composable
private fun Hero(nav: Nav, item: PlexItem, onFocus: () -> Unit, prev: () -> Unit, next: () -> Unit) {
    val art by rememberArtwork(item)
    val ratings by rememberRatings(item)
    val play = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { play.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().heightIn(min = 640.dp).padding(start = D.gutter, top = 150.dp, end = D.gutter, bottom = 30.dp),
    ) {
        val logo = logoUrl(item, art)
        if (logo != null) Img(logo, Modifier.height(130.dp).width(460.dp), ContentScale.Fit)
        else T(item.displayTitle, size = 48.sp, weight = FontWeight.Bold, maxLines = 2)
        Spacer(Modifier.height(16.dp))
        T(metaLine(item), size = 16.sp, color = C.dim, maxLines = 1)
        ratings?.let { Spacer(Modifier.height(12.dp)); RatingsRow(it) }
        Spacer(Modifier.height(14.dp))
        T(item.summary ?: "", Modifier.fillMaxWidth(0.5f), size = 16.sp, color = C.text, maxLines = 3, lineHeight = 22.sp)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            val resume = item.viewOffsetMs > 0 && item.isPlayable
            Pill(if (resume) "Resume" else "Play", {
                if (item.isPlayable) nav.play(item) else nav.open(item)
            }, Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionLeft) { prev(); true } else false
            }, focusRequester = play, onFocused = onFocus, icon = "▶")
            Pill("More Info", { nav.open(item) }, Modifier.onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionRight) { next(); true } else false
            }, onFocused = onFocus)
        }
    }
}
