package app.plexhdr.shield.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
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
    // Kept while a page is opened on top: coming back shows the same rows straight away and refreshes them quietly.
    var onDeck by retain("onDeck") { mutableStateOf<List<PlexItem>>(emptyList()) }
    var recent by retain("recent") { mutableStateOf<List<Pair<PlexSection, List<PlexItem>>>>(emptyList()) }
    var loaded by retain("loaded") { mutableStateOf(false) }
    LaunchedEffect(sections) {
        // Retry until something comes back (server asleep, Wi-Fi not up yet after the Shield wakes).
        var wait = 1000L
        while (true) {
            val deck = runCatching { App.plex.onDeck() }
            onDeck = deck.getOrElse { onDeck }
            val before = recent.toMap()
            recent = coroutineScope {
                sections.map { s -> async { s to runCatching { App.plex.recentlyAdded(s.id, 24) }.getOrElse { before[s].orEmpty() } } }.awaitAll()
            }
            loaded = true
            if (deck.isSuccess && (sections.isEmpty() || recent.any { it.second.isNotEmpty() } || onDeck.isNotEmpty())) break
            delay(wait); wait = (wait * 2).coerceAtMost(15_000)
        }
    }
    // The top shelf: what you're watching, then the newest movies and shows.
    val featured = remember(onDeck, recent) {
        (onDeck.take(4) + recent.flatMap { it.second.take(6) }.filter { it.type == "movie" || it.type == "show" || it.type == "season" })
            .distinctBy { it.grandparentRatingKey ?: it.parentRatingKey?.takeIf { _ -> it.type == "season" } ?: it.id }.take(10)
    }
    var heroIndex by retain("heroIndex") { mutableStateOf(0) }
    var focusedItem by retain("focusedItem") { mutableStateOf<PlexItem?>(null) }
    var heroFocused by retain("heroFocused") { mutableStateOf(true) }
    // Focus on the hero's buttons: the top shelf then stays on what you're looking at (OK must open that title).
    var onHeroButtons by remember { mutableStateOf(false) }
    val hero = featured.getOrNull(heroIndex)
    val wanted = if (heroFocused) hero else focusedItem ?: hero
    // Change the full-screen backdrop only once focus rests, not on every card passed while scrolling a row:
    // loading a 4K backdrop per key press made the UI stutter.
    var bgItem by retain("bgItem") { mutableStateOf(wanted) }
    LaunchedEffect(wanted) { delay(350); bgItem = wanted }
    val art by rememberArtwork(bgItem)

    LaunchedEffect(featured.size, heroIndex, heroFocused, onHeroButtons) {
        if (featured.size < 2 || !heroFocused || onHeroButtons) return@LaunchedEffect
        delay(9000)
        heroIndex = (heroIndex + 1) % featured.size
    }

    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Box(Modifier.fillMaxSize()) {
        Crossfade(backdropUrl(bgItem, art), animationSpec = tween(700), label = "home-bg") { Backdrop(it) }
        if (!loaded) { Loading(); return@Box }
        LazyColumn(Modifier.fillMaxSize(), state = list) {
            if (hero != null) item(key = "hero") {
                // Back on the hero buttons: show the whole hero again, not a version scrolled under the top bar.
                Hero(nav, hero, active = heroFocused, onFocus = { heroFocused = true; scope.launch { list.animateScrollToItem(0) } },
                    onButtons = { onHeroButtons = it },
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
private fun Hero(nav: Nav, item: PlexItem, active: Boolean, onFocus: () -> Unit, onButtons: (Boolean) -> Unit, prev: () -> Unit, next: () -> Unit) {
    val art by rememberArtwork(item)
    val ratings by rememberRatings(item)
    val play = remember { FocusRequester() }
    FocusOnShow(play)
    val heroAlpha by animateFloatAsState(if (active) 1f else 0f, label = "hero-alpha")
    Column(
        // Like Apple TV: once focus moves down to the rows, the hero's title and buttons fade out (the backdrop then
        // follows the focused card), so nothing is left half-hidden under the top bar.
        Modifier.fillMaxWidth().heightIn(min = 600.dp).graphicsLayer { alpha = heroAlpha }
            .padding(start = D.gutter, top = 120.dp, end = D.gutter, bottom = 24.dp),
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
        Row(Modifier.onFocusChanged { onButtons(it.hasFocus) }, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
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
