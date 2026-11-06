package app.plexhdr.shield.ui

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.services.WatchNext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The options menu of a Plex card (long-press Select, or the Menu key). Cards only ask for it; [GlobalHosts], which the
 * main screen renders once, shows it. Screens watch [changes] to reload after an action changed something on the server.
 */
object CardMenu {
    class Request(val item: PlexItem, val continueWatching: Boolean)

    var request by mutableStateOf<Request?>(null)

    /** Bumped after an action changed an item on the server (watched state, Continue Watching). */
    var changes by mutableIntStateOf(0); private set

    /** Rating key of the item the last change was about. */
    var lastChangedKey: String? = null; private set

    fun show(item: PlexItem, continueWatching: Boolean = false) { request = Request(item, continueWatching) }

    fun changed(ratingKey: String?) {
        lastChangedKey = ratingKey
        changes++
        WatchNext.syncSoon(1500)
    }
}

/** lumen://item/<ratingKey> (the home screen's Play Next row): MainActivity stores it here, [GlobalHosts] opens it. */
object DeepLink {
    var pending by mutableStateOf<String?>(null)

    fun uriFor(ratingKey: String) = "lumen://item/$ratingKey"

    /** True if the intent was a Lumen link. */
    fun handle(intent: Intent?): Boolean {
        val u = intent?.data ?: return false
        if (u.scheme != "lumen" || u.host != "item") return false
        pending = u.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return false
        return true
    }
}

/** App-wide overlays and hooks that need the navigator: the card menu, deep links, Watch Next updates after playback. */
@Composable
fun GlobalHosts(nav: Nav) {
    val scope = rememberCoroutineScope()

    // Deep link: open the item's page (closing the player first if one is open).
    val link = DeepLink.pending
    LaunchedEffect(link) {
        val rk = link ?: return@LaunchedEffect
        var item: PlexItem? = null
        // The Shield may still be waking up (Wi-Fi, server); try for a little while.
        for (wait in longArrayOf(0, 1000, 2000, 4000, 8000)) {
            delay(wait)
            item = runCatching { App.plex.metadata(rk) }.getOrNull()
            if (item != null) break
        }
        if (DeepLink.pending == rk) DeepLink.pending = null
        val found = item ?: return@LaunchedEffect
        while (nav.top is Screen.Player || nav.top is Screen.IptvPlayer) nav.pop()
        if ((nav.top as? Screen.Detail)?.item?.ratingKey != rk) nav.open(found)
    }

    // Playback changes what's on deck: refresh the home screen's Play Next row after leaving the player.
    val inPlayer = nav.top is Screen.Player
    var wasInPlayer by remember { mutableStateOf(false) }
    LaunchedEffect(inPlayer) {
        if (wasInPlayer && !inPlayer) WatchNext.syncSoon(2500)
        wasInPlayer = inPlayer
    }

    val req = CardMenu.request ?: return
    val actions = remember(req) { menuActions(nav, req, scope) }
    val item = req.item
    val title = if (item.type == "episode") listOfNotNull(item.grandparentTitle, item.episodeLabel).joinToString(" · ") else item.title
    ChoiceDialog(title, actions.map { Choice(it.first) }, { i ->
        CardMenu.request = null
        actions.getOrNull(i)?.second?.invoke()
    }, { CardMenu.request = null })
}

/** A stand-in item for the show/season pages, which load the rest themselves. */
private fun stub(ratingKey: String, type: String, title: String?, parentRatingKey: String? = null) = PlexItem(
    JSONObject().put("ratingKey", ratingKey).put("type", type).put("title", title ?: "")
        .apply { if (parentRatingKey != null) put("parentRatingKey", parentRatingKey) }
)

private fun menuActions(nav: Nav, req: CardMenu.Request, scope: CoroutineScope): List<Pair<String, () -> Unit>> {
    val plex = App.plex
    val item = req.item
    val rk = item.ratingKey
    val out = ArrayList<Pair<String, () -> Unit>>()
    fun add(label: String, action: () -> Unit) { out.add(label to action) }
    if (item.isPlayable) {
        add((if (item.viewOffsetMs > 0) "Resume" else "Play")) { nav.play(item) }
        add("Play from Beginning") { nav.play(item, 0L) }
    } else if (item.type == "show" || item.type == "season") {
        add("Play") { scope.launch { upNext(item)?.let { nav.play(it) } ?: nav.open(item) } }
    }
    if (rk != null && item.type != "collection") {
        add((if (item.isWatched) "Mark as Unwatched" else "Mark as Watched")) {
            scope.launch { plex.setWatched(item, !item.isWatched); CardMenu.changed(rk) }
        }
    }
    if (rk != null && (req.continueWatching || (item.isPlayable && item.viewOffsetMs > 0))) {
        add("Remove from Continue Watching") {
            scope.launch { runCatching { plex.removeFromContinueWatching(rk) }; CardMenu.changed(rk) }
        }
    }
    when (item.type) {
        "episode" -> {
            item.grandparentRatingKey?.let { show -> add("Go to Show") { nav.push(Screen.Show(stub(show, "show", item.grandparentTitle))) } }
            item.parentRatingKey?.let { season ->
                add("Go to Season") { nav.push(Screen.Show(stub(season, "season", item.parentTitle, item.grandparentRatingKey))) }
            }
        }
        "season" -> item.parentRatingKey?.let { show -> add("Go to Show") { nav.push(Screen.Show(stub(show, "show", item.parentTitle))) } }
    }
    add("More Info") { nav.open(item) }
    return out
}

/** The episode to play for a show or season: the one on deck, else the first unwatched, else the first. */
private suspend fun upNext(item: PlexItem): PlexItem? = runCatching<PlexItem?> {
    val plex = App.plex
    val rk = item.ratingKey ?: return null
    if (item.type == "season") {
        val eps = plex.children(rk)
        return eps.firstOrNull { !it.isWatched } ?: eps.firstOrNull()
    }
    plex.metadata(rk)?.onDeck?.let { return it }
    for (season in plex.children(rk).filter { (it.index ?: 1) > 0 }.ifEmpty { plex.children(rk) }) {
        val eps = season.ratingKey?.let { plex.children(it) } ?: continue
        eps.firstOrNull { !it.isWatched }?.let { return it }
    }
    null
}.getOrNull()
