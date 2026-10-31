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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexClient
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.plex.PlexSection
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
    var sort by remember { mutableStateOf(sorts[0].first) }
    var unwatched by remember { mutableStateOf(false) }
    var genre by remember { mutableStateOf<PlexClient.Genre?>(null) }
    var genres by remember { mutableStateOf<List<PlexClient.Genre>>(emptyList()) }
    var showCollections by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<String?>(null) }   // "sort" | "genre"
    val items = remember { mutableStateListOf<PlexItem>() }
    var total by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        if (reset) { items.clear(); total = 0 }
        runCatching {
            if (showCollections) {
                items.addAll(plex.collections(section.id)); total = items.size
            } else {
                val (list, t) = plex.sectionItems(section.id, PlexClient.ItemQuery(sort = sort, unwatched = unwatched, genre = genre?.key, start = items.size, size = 100))
                items.addAll(list); total = t
            }
        }
        loading = false
    }

    LaunchedEffect(sort, unwatched, genre, showCollections) { load(true) }
    LaunchedEffect(section.id) { genres = runCatching { plex.genres(section.id) }.getOrDefault(emptyList()) }

    Column(Modifier.fillMaxSize().padding(top = 90.dp)) {
        Row(Modifier.padding(horizontal = D.gutter), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            T(section.title, size = 30.sp, weight = FontWeight.Bold)
            Spacer(Modifier.padding(horizontal = 8.dp))
            Pill("Sort: ${sorts.first { it.first == sort }.second}", { menu = if (menu == "sort") null else "sort" })
            Pill(if (unwatched) "Unwatched" else "All", { unwatched = !unwatched }, selected = unwatched)
            Pill(genre?.title ?: "Genre", { menu = if (menu == "genre") null else "genre" }, selected = genre != null)
            Pill("Collections", { showCollections = !showCollections }, selected = showCollections)
        }
        when (menu) {
            "sort" -> LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(sorts) { (k, label) -> Pill(label, { sort = k; menu = null }, selected = k == sort) }
            }
            "genre" -> LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Pill("All genres", { genre = null; menu = null }, selected = genre == null) }
                items(genres) { g -> Pill(g.title, { genre = g; menu = null }, selected = g == genre) }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (items.isEmpty() && loading) { Loading(); return@Column }
        if (items.isEmpty()) { Box(Modifier.padding(D.gutter)) { T("Nothing here.", color = C.dim) }; return@Column }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(D.posterW + 24.dp),
            contentPadding = PaddingValues(horizontal = D.gutter, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(items.size) { i ->
                val item = items[i]
                PosterCard(item, { nav.open(item) }, onFocused = {
                    if (!showCollections && i > items.size - 20 && items.size < total) scope.launch { load(false) }
                })
            }
        }
    }
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
            columns = GridCells.Adaptive(D.posterW + 24.dp),
            contentPadding = PaddingValues(horizontal = D.gutter, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(26.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            items(items, key = { it.id }) { item -> PosterCard(item, { nav.open(item) }) }
        }
    }
}
