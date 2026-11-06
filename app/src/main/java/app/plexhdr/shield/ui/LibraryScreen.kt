package app.plexhdr.shield.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexClient
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.plex.PlexSection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val sorts = listOf(
    "originallyAvailableAt:desc" to "Release Date",
    "addedAt:desc" to "Date Added",
    "titleSort" to "Title",
    "rating:desc" to "Rating",
    "lastViewedAt:desc" to "Recently Watched",
)

/** Poster grid with sort (release date by default), unwatched-only, genre and collections. */
@Composable
fun LibraryScreen(nav: Nav, section: PlexSection) {
    val plex = App.plex
    val scope = rememberCoroutineScope()
    // Retained: back from a title, the grid is there at once, at the same place.
    var sort by retain("sort") { mutableStateOf(sorts[0].first) }
    var unwatched by retain("unwatched") { mutableStateOf(false) }
    var genre by retain("genre") { mutableStateOf<PlexClient.Genre?>(null) }
    var genres by retain("genres") { mutableStateOf<List<PlexClient.Genre>>(emptyList()) }
    var showCollections by retain("collections") { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }   // "sort" | "genre"
    val items = retain("items") { mutableStateListOf<PlexItem>() }
    var total by retain("total") { mutableStateOf(0) }
    var loadedFor by retain("loadedFor") { mutableStateOf<List<Any?>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var letters by remember { mutableStateOf<List<PlexClient.FirstCharacter>>(emptyList()) }
    val grid = rememberLazyGridState()
    val jumpFocus = remember { FocusRequester() }
    var jumpIndex by remember { mutableStateOf(-1) }

    suspend fun load(reset: Boolean, size: Int = 100) {
        if (loading) return
        loading = true
        if (reset) { items.clear(); total = 0 }
        runCatching {
            if (showCollections) {
                items.addAll(plex.collections(section.id)); total = items.size
            } else {
                val (list, t) = plex.sectionItems(section.id, PlexClient.ItemQuery(sort = sort, unwatched = unwatched, genre = genre?.key, start = items.size, size = size))
                items.addAll(list); total = t
            }
        }
        loading = false
    }

    val byTitle = sort == "titleSort" && !showCollections
    LaunchedEffect(sort, unwatched, genre, showCollections) {
        val query = listOf(sort, unwatched, genre?.key, showCollections)
        if (loadedFor != query || items.isEmpty()) { loadedFor = query; jumpIndex = -1; load(true); return@LaunchedEffect }
        // Coming back: refresh what's loaded in place (watched marks, progress) without moving the grid.
        if (showCollections || unwatched) return@LaunchedEffect
        runCatching {
            val (list, _) = plex.sectionItems(section.id, PlexClient.ItemQuery(sort = sort, unwatched = false, genre = genre?.key, start = 0, size = items.size))
            if (list.size == items.size && list.indices.all { list[it].id == items[it].id }) list.forEachIndexed { i, x -> items[i] = x }
        }
    }
    LaunchedEffect(section.id) { genres = runCatching { plex.genres(section.id) }.getOrDefault(emptyList()) }
    LaunchedEffect(byTitle, unwatched, genre) {
        letters = if (!byTitle) emptyList()
        else runCatching { plex.firstCharacters(section.id, unwatched, genre?.key) }.getOrDefault(emptyList())
    }
    // The card menu changed an item (watched state): refresh just that item, keeping the grid where it is.
    val seenChanges = remember { CardMenu.changes }
    LaunchedEffect(CardMenu.changes) {
        if (CardMenu.changes == seenChanges) return@LaunchedEffect
        val rk = CardMenu.lastChangedKey ?: return@LaunchedEffect
        val i = items.indexOfFirst { it.ratingKey == rk }
        if (i < 0) return@LaunchedEffect
        runCatching { plex.metadata(rk) }.getOrNull()?.let { fresh -> if (i < items.size && items[i].ratingKey == rk) items[i] = fresh }
    }

    /** Loads up to the first item of [letter] (counts from Plex's firstCharacter list), then scrolls to it and focuses it. */
    suspend fun jumpTo(letter: PlexClient.FirstCharacter) {
        val pos = letters.indexOf(letter)
        if (pos < 0) return
        val offset = letters.take(pos).sumOf { it.size }
        while (items.size <= offset && items.size < total) {
            while (loading) delay(50)
            val before = items.size
            load(false, size = (offset - items.size + 60).coerceIn(100, 1000))
            if (items.size == before) break
        }
        if (items.isEmpty()) return
        // The counts are a guide; land on the first item near there that really starts with the letter.
        val target = (maxOf(0, offset - 40) until items.size).firstOrNull { titleStartsWith(items[it], letter.title) }
            ?: offset.coerceIn(0, items.size - 1)
        jumpIndex = target
        grid.scrollToItem(target)
        repeat(20) { if (runCatching { jumpFocus.requestFocus() }.isSuccess) return; delay(32) }
    }

    Column(Modifier.fillMaxSize().padding(top = 90.dp)) {
        Row(Modifier.padding(horizontal = D.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(section.title, size = 30.sp, weight = FontWeight.Bold)
            Spacer(Modifier.padding(horizontal = 8.dp))
            Pill("Sort: ${sorts.first { it.first == sort }.second}", { menu = if (menu == "sort") null else "sort" })
            Pill(if (unwatched) "Unwatched" else "All", { unwatched = !unwatched }, selected = unwatched)
            Pill(genre?.title ?: "Genre", { menu = if (menu == "genre") null else "genre" }, selected = genre != null)
            Pill("Collections", { showCollections = !showCollections }, selected = showCollections)
            if (byTitle && letters.isNotEmpty()) Pill("A–Z", { menu = if (menu == "az") null else "az" }, selected = menu == "az")
        }
        when (menu) {
            "sort" -> LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sorts) { (k, label) -> Pill(label, { sort = k; menu = null }, selected = k == sort) }
            }
            "genre" -> LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Pill("All genres", { genre = null; menu = null }, selected = genre == null) }
                items(genres) { g -> Pill(g.title, { genre = g; menu = null }, selected = g == genre) }
            }
            "az" -> LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(letters) { l -> Pill(l.title, { menu = null; scope.launch { jumpTo(l) } }) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty() && loading) { Loading(); return@Column }
        if (items.isEmpty()) { Box(Modifier.padding(D.gutter)) { T("Nothing here.", color = C.dim) }; return@Column }
        LazyVerticalGrid(
            columns = GridCells.FixedSize(D.posterW),
            state = grid,
            contentPadding = PaddingValues(horizontal = D.gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            items(items.size) { i ->
                val item = items[i]
                PosterCard(item, { nav.open(item) }, onFocused = {
                    if (!showCollections && i > items.size - 20 && items.size < total) scope.launch { load(false) }
                }, focusRequester = if (i == jumpIndex) jumpFocus else null)
            }
        }
    }
}

/** True if the item's sort title starts with [letter] ("#" = anything that isn't a letter). */
private fun titleStartsWith(item: PlexItem, letter: String): Boolean {
    val first = (item.str("titleSort") ?: item.title).trim().firstOrNull() ?: return false
    return if (letter == "#") !first.isLetter() else first.toString().equals(letter.take(1), ignoreCase = true) ||
        java.text.Normalizer.normalize(first.toString(), java.text.Normalizer.Form.NFD).firstOrNull()?.toString().equals(letter.take(1), ignoreCase = true)
}

/** Items of a collection. */
@Composable
fun CollectionScreen(nav: Nav, collection: PlexItem) {
    var items by remember { mutableStateOf<List<PlexItem>?>(null) }
    LaunchedEffect(collection.id) {
        items = runCatching { App.plex.collectionItems(collection.ratingKey ?: "") }.getOrDefault(emptyList())
    }
    GridPage(nav, collection.title, items)
}

@Composable
fun GridPage(nav: Nav, title: String, items: List<PlexItem>?, header: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().padding(top = 50.dp)) {
        T(title, Modifier.padding(horizontal = D.gutter), size = 32.sp, weight = FontWeight.Bold)
        header()
        if (items == null) { Loading(); return@Column }
        LazyVerticalGrid(
            columns = GridCells.FixedSize(D.posterW),
            contentPadding = PaddingValues(horizontal = D.gutter, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            items(items, key = { it.id }) { item -> PosterCard(item, { nav.open(item) }) }
        }
    }
}
