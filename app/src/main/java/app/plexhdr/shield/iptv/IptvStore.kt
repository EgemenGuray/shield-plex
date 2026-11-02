package app.plexhdr.shield.iptv

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.plexhdr.shield.services.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A group of channels, movies or series in one source. */
data class IptvGroup(val id: String, val playlistId: String, val kind: Kind, val name: String) {
    enum class Kind { Live, Movie, Series }
}

/** An entry in Continue Watching: a movie, or the last episode watched in a series. */
data class ResumeEntry(
    val id: String, val title: String, val subtitle: String?, val poster: String?,
    val movieId: String? = null, val seriesId: String? = null, val episodeId: String? = null,
)

data class Reminder(val channelId: String, val channelName: String, val title: String, val start: Long)

data class Measured(val height: Int, val channels: Int?, val hdr: Boolean)

data class SearchResult(
    val channels: List<Channel> = emptyList(), val programmes: List<Pair<Channel, Programme>> = emptyList(),
    val movies: List<Movie> = emptyList(), val series: List<Series> = emptyList(),
)

enum class SearchScope(val label: String) { All("Everything"), Channels("Channel names"), Programmes("TV programmes") }

/**
 * Everything Live TV keeps: sources, their channels, movies and series, the TV guide, favourites, hidden and locked
 * groups, renames, Continue Watching and what's been watched. State is Compose state, so screens update by themselves.
 * Downloaded playlists and the guide are cached in the app's files so the library opens instantly.
 */
class IptvStore(private val context: Context, private val settings: Settings) {
    private val prefs = context.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "iptv").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val refreshLock = Mutex()

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    var playlists by mutableStateOf(readPlaylists()); private set
    private val contents = HashMap<String, Content>()
    var guide by mutableStateOf(Guide.Empty); private set
    var guideLoading by mutableStateOf(false); private set
    val loading = mutableStateMapOf<String, Boolean>()
    val errors = mutableStateMapOf<String, String>()

    var channels by mutableStateOf<List<Channel>>(emptyList()); private set
    var movies by mutableStateOf<List<Movie>>(emptyList()); private set
    var series by mutableStateOf<List<Series>>(emptyList()); private set
    private var byId: Map<String, Channel> = emptyMap()
    var ready by mutableStateOf(false); private set

    var favorites by mutableStateOf(readSet("favorites")); private set
    var recents by mutableStateOf(readList("recents")); private set
    var hidden by mutableStateOf(readSet("hidden")); private set
    var locked by mutableStateOf(readSet("locked")); private set
    var renames by mutableStateOf(readMap("renames")); private set
    var pin by mutableStateOf(prefs.getString("pin", "") ?: ""); private set
    var unlocked by mutableStateOf(false); private set
    var positions by mutableStateOf(readPositions()); private set
    var measured by mutableStateOf(readMeasured()); private set
    var resume by mutableStateOf(readResume()); private set
    private var plays: Map<String, List<Long>> = readPlays()
    var reminders by mutableStateOf(readReminders()); private set
    var dueReminder by mutableStateOf<Reminder?>(null)

    private val detailCache = HashMap<String, Details>()
    private val tagCache = HashMap<String, List<QTag>>()
    private val keyCache = HashMap<String, String?>()

    fun start() {
        scope.launch {
            withContext(Dispatchers.IO) {
                playlists.forEach { p -> runCatching { readCache(p) }.getOrNull()?.let { synchronized(contents) { contents[p.id] = it } } }
                val g = File(dir, "guide.tsv")
                if (g.exists()) runCatching { Guide.fromTsv(g.readText(), prefs.getLong("guideUpdated", 0)) }.getOrNull()?.let { guide = it }
            }
            rebuild()
            ready = true
            refreshStale()
        }
        scope.launch { while (true) { delay(20_000); checkReminders() } }
    }

    // ---------------- sources ----------------

    fun playlist(id: String) = playlists.firstOrNull { it.id == id }
    fun content(id: String): Content? = synchronized(contents) { contents[id] }
    fun isLoading(id: String? = null) = if (id == null) loading.isNotEmpty() else loading[id] == true

    private val palette = listOf(0xFF0A84FF, 0xFFFF9F0A, 0xFF30D158, 0xFFFF375F, 0xFFBF5AF2, 0xFF40C8E0, 0xFFFFD60A, 0xFFFF453A)
    fun color(p: Playlist?): Long = palette[((p?.colorIndex ?: 0) % palette.size + palette.size) % palette.size]

    fun savePlaylist(p: Playlist) {
        val existing = playlists.indexOfFirst { it.id == p.id }
        playlists = if (existing >= 0) playlists.toMutableList().also { it[existing] = p }
        else playlists + p.copy(id = p.id.ifEmpty { UUID.randomUUID().toString() }, colorIndex = playlists.size)
        writePlaylists()
        val saved = if (existing >= 0) p else playlists.last()
        scope.launch { refresh(saved.id) }
    }

    fun deletePlaylist(id: String) {
        playlists = playlists.filter { it.id != id }
        synchronized(contents) { contents.remove(id) }
        File(dir, "$id.cache").delete()
        writePlaylists()
        rebuild()
    }

    fun setEnabled(id: String, on: Boolean) {
        playlists = playlists.map { if (it.id == id) it.copy(enabled = on) else it }
        writePlaylists(); rebuild()
    }

    private suspend fun refreshStale() {
        for (p in playlists) if (p.enabled && (content(p.id) == null || System.currentTimeMillis() - p.lastRefresh > 12 * 3_600_000L)) refresh(p.id, guideToo = false)
        if (guide.programmes.isEmpty() || System.currentTimeMillis() - guide.updated > 6 * 3_600_000L) refreshGuide()
    }

    fun refreshAll() = scope.launch {
        for (p in playlists) if (p.enabled) refresh(p.id, guideToo = false)
        refreshGuide()
    }

    fun refreshLater(id: String) = scope.launch { refresh(id) }

    suspend fun refresh(id: String, guideToo: Boolean = true) {
        val pl = playlist(id) ?: return
        if (loading[id] == true) return
        loading[id] = true
        errors.remove(id)
        try {
            val c = withContext(Dispatchers.IO) {
                if (pl.kind == Playlist.Kind.Xtream) {
                    val x = Xtream(pl)
                    val account = get(x.api(), pl.userAgent)
                    val acts = listOf("get_live_categories", "get_live_streams", "get_vod_categories", "get_vod_streams", "get_series_categories", "get_series")
                    val r = kotlinx.coroutines.coroutineScope { acts.map { a -> async { runCatching { get(x.api(a), pl.userAgent) }.getOrNull() } }.awaitAll() }
                    val parsed = x.parse(account, r[0], r[1], r[2], r[3], r[4], r[5])
                    val cache = JSONObject().put("kind", "xtream").put("account", account)
                    acts.forEachIndexed { i, a -> cache.put(a, r[i] ?: "") }
                    File(dir, "$id.cache").writeText(cache.toString())
                    parsed
                } else {
                    val text = get(pl.url.trim(), pl.userAgent)
                    if (!text.contains("#EXTINF") && !text.contains("#EXTM3U")) throw IptvException("This address didn't return an M3U playlist.")
                    File(dir, "$id.cache").writeText("M3U\n$text")
                    M3U.parse(text, pl)
                }
            }
            synchronized(contents) { contents[id] = c }
            playlists = playlists.map { if (it.id == id) it.copy(lastRefresh = System.currentTimeMillis()) else it }
            writePlaylists()
            loading.remove(id)
            rebuild()
            if (guideToo) refreshGuide()
        } catch (e: Exception) {
            loading.remove(id)
            errors[id] = e.message ?: e.toString()
        }
    }

    private fun readCache(p: Playlist): Content? {
        val f = File(dir, "${p.id}.cache")
        if (!f.exists()) return null
        val text = f.readText()
        if (text.startsWith("M3U\n")) return M3U.parse(text.substring(4), p)
        val o = JSONObject(text)
        fun s(k: String) = o.optString(k).ifEmpty { null }
        return Xtream(p).parse(o.optString("account"), s("get_live_categories"), s("get_live_streams"), s("get_vod_categories"),
            s("get_vod_streams"), s("get_series_categories"), s("get_series"))
    }

    /** GET as text (gzip guides unpacked). */
    fun get(url: String, userAgent: String? = null): String {
        val b = Request.Builder().url(url)
        b.header("User-Agent", userAgent?.ifEmpty { null } ?: "Lumen/1.0 (Android TV)")
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IptvException("The server answered ${r.code}.")
            return Text.decodeBody(r.body?.bytes() ?: ByteArray(0))
        }
    }

    // ---------------- guide ----------------

    fun refreshGuide() = scope.launch {
        if (guideLoading) return@launch
        val urls = LinkedHashMap<String, String?>()
        playlists.filter { it.enabled }.forEach { p ->
            (content(p.id)?.epgUrls.orEmpty() + p.epgUrl.split(',')).map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { urls.putIfAbsent(it, p.userAgent) }
        }
        if (urls.isEmpty()) return@launch
        guideLoading = true
        val all = synchronized(contents) { contents.values.flatMap { it.channels } }
        val ids = all.mapNotNull { it.epgId?.lowercase() }.toSet()
        val names = all.map { Text.key(it.name) }.toSet()
        val maxDays = (all.mapNotNull { it.catchup?.days }.maxOrNull() ?: 1).coerceIn(1, 14)
        val from = System.currentTimeMillis() - maxDays * 86_400_000L
        val to = System.currentTimeMillis() + 4 * 86_400_000L
        val merged = withContext(Dispatchers.IO) {
            val progs = HashMap<String, List<Programme>>()
            val nm = HashMap<String, String>()
            var failed: String? = null
            urls.forEach { (u, ua) ->
                runCatching {
                    val g = XMLTV.parse(get(u, ua), ids, names, from, to)
                    g.programmes.forEach { (k, v) -> progs.putIfAbsent(k, v) }
                    g.names.forEach { (k, v) -> nm.putIfAbsent(k, v) }
                }.onFailure { failed = it.message }
            }
            if (progs.isEmpty()) { failed?.let { errors["guide"] = "Guide: $it" }; null }
            else Guide(progs, nm, System.currentTimeMillis()).also {
                errors.remove("guide")
                runCatching { File(dir, "guide.tsv").writeText(it.toTsv()) }
            }
        }
        if (merged != null) {
            guide = merged
            synchronized(keyCache) { keyCache.clear() }
            prefs.edit().putLong("guideUpdated", merged.updated).apply()
        }
        guideLoading = false
    }

    private fun epgKey(ch: Channel): String? = synchronized(keyCache) {
        keyCache.getOrPut(ch.id) {
            val g = guide
            val id = ch.epgId?.lowercase()
            when {
                id != null && g.programmes.containsKey(id) -> id
                g.names[Text.key(ch.name)]?.let { g.programmes.containsKey(it) } == true -> g.names[Text.key(ch.name)]
                else -> content(ch.playlistId)?.channels?.firstOrNull { it.id == ch.id }?.let { g.names[Text.key(it.name)] }
            }
        }
    }

    fun programmes(ch: Channel, from: Long? = null, to: Long? = null): List<Programme> {
        val list = epgKey(ch)?.let { guide.programmes[it] } ?: return emptyList()
        if (from == null || to == null) return list
        return list.filter { it.end > from && it.start < to }
    }

    fun nowNext(ch: Channel, at: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = programmes(ch)
        for (i in list.indices) {
            if (list[i].end > at) return if (list[i].start <= at) list[i] to list.getOrNull(i + 1) else null to list[i]
        }
        return null to null
    }

    fun catchupUrl(ch: Channel, p: Programme): String? = CatchupUrl.url(ch, p, playlist(ch.playlistId), content(ch.playlistId)?.serverTimeZone)
    fun canCatchUp(ch: Channel, p: Programme) = CatchupUrl.available(ch, p)

    // ---------------- library ----------------

    private fun groupKey(pid: String, kind: IptvGroup.Kind, name: String) = "$pid|${kind.name}|$name"

    private fun rebuild() {
        val ch = ArrayList<Channel>(); val mv = ArrayList<Movie>(); val se = ArrayList<Series>()
        val ids = HashMap<String, Channel>()
        for (p in playlists) {
            val c = content(p.id) ?: continue
            if (!p.enabled) continue
            fun ok(kind: IptvGroup.Kind, g: String): Boolean {
                val k = groupKey(p.id, kind, g)
                return k !in hidden && (unlocked || k !in locked)
            }
            c.channels.forEach { x ->
                if (!ok(IptvGroup.Kind.Live, x.group)) return@forEach
                var y = x
                renames[x.id]?.let { y = y.copy(name = it) }
                if (y.userAgent == null && p.userAgent.isNotEmpty()) y = y.copy(userAgent = p.userAgent)
                ch += y; ids[y.id] = y
            }
            c.movies.forEach { if (ok(IptvGroup.Kind.Movie, it.group)) mv += it }
            c.series.forEach { if (ok(IptvGroup.Kind.Series, it.group)) se += it }
        }
        synchronized(tagCache) { tagCache.clear() }
        synchronized(keyCache) { keyCache.clear() }
        byId = ids
        channels = ch; movies = mv; series = se
    }

    fun channel(id: String): Channel? = byId[id]

    fun groups(kind: IptvGroup.Kind, includeHidden: Boolean = false): List<IptvGroup> = playlists.filter { it.enabled }.flatMap { p ->
        val c = content(p.id) ?: return@flatMap emptyList()
        val names = when (kind) { IptvGroup.Kind.Live -> c.liveGroups; IptvGroup.Kind.Movie -> c.movieGroups; IptvGroup.Kind.Series -> c.seriesGroups }
        names.map { IptvGroup(groupKey(p.id, kind, it), p.id, kind, it) }.filter { includeHidden || it.id !in hidden }
    }

    fun channelsIn(g: IptvGroup) = channels.filter { it.playlistId == g.playlistId && it.group == g.name }
    fun moviesIn(g: IptvGroup) = movies.filter { it.playlistId == g.playlistId && it.group == g.name }
    fun seriesIn(g: IptvGroup) = series.filter { it.playlistId == g.playlistId && it.group == g.name }

    fun isLocked(g: IptvGroup) = g.id in locked
    fun setHidden(g: IptvGroup, on: Boolean) { hidden = if (on) hidden + g.id else hidden - g.id; writeSet("hidden", hidden); rebuild() }
    fun setLocked(g: IptvGroup, on: Boolean) { locked = if (on) locked + g.id else locked - g.id; writeSet("locked", locked); rebuild() }
    fun savePin(p: String) { pin = p; prefs.edit().putString("pin", p).apply() }
    fun unlock(p: String): Boolean { if (p != pin) return false; unlocked = true; rebuild(); return true }
    fun lock() { unlocked = false; rebuild() }

    fun isFavorite(id: String) = id in favorites
    fun toggleFavorite(id: String) { favorites = if (id in favorites) favorites - id else favorites + id; writeSet("favorites", favorites) }
    fun favoriteChannels() = favorites.mapNotNull { byId[it] }
    fun favoriteMovies() = movies.filter { it.id in favorites }
    fun favoriteSeries() = series.filter { it.id in favorites }
    fun recentChannels() = recents.mapNotNull { byId[it] }
    fun clearRecents() { recents = emptyList(); writeList("recents", recents) }

    fun rename(ch: Channel, name: String?) {
        renames = if (name.isNullOrBlank()) renames - ch.id else renames + (ch.id to name.trim())
        writeMap("renames", renames); rebuild()
    }

    fun tags(ch: Channel): List<QTag> {
        val named = synchronized(tagCache) { tagCache.getOrPut(ch.id) { QTag.parse(ch.name + " " + ch.group) } }
        val m = measured[ch.id] ?: return named
        return QTag.merge(named, QTag.measured(m.height, m.channels, m.hdr))
    }

    fun recordMeasured(ch: Channel, height: Int, channels: Int?, hdr: Boolean) {
        if (height <= 0) return
        val m = Measured(height, channels ?: measured[ch.id]?.channels, hdr)
        if (measured[ch.id] == m) return
        measured = measured + (ch.id to m)
        prefs.edit().putString("measured", JSONObject().also { o ->
            measured.forEach { (k, v) -> o.put(k, JSONObject().put("h", v.height).put("c", v.channels ?: 0).put("hdr", v.hdr)) }
        }.toString()).apply()
    }

    /** A channel was watched: recents, and the play log behind Trending Channels. */
    fun played(ch: Channel) {
        recents = (listOf(ch.id) + recents.filter { it != ch.id }).take(30)
        writeList("recents", recents)
        val now = System.currentTimeMillis()
        val cut = now - 14 * 86_400_000L
        plays = (plays + (ch.id to (plays[ch.id].orEmpty() + now))).mapValues { (_, v) -> v.filter { it > cut } }.filterValues { it.isNotEmpty() }
        prefs.edit().putString("plays", JSONObject().also { o -> plays.forEach { (k, v) -> o.put(k, JSONArray(v)) } }.toString()).apply()
    }

    /** Your most-watched channels of the last seven days. */
    fun trendingChannels(): List<Channel> {
        val since = System.currentTimeMillis() - 7 * 86_400_000L
        return plays.mapValues { (_, v) -> v.count { it > since } }.filter { it.value > 0 && byId.containsKey(it.key) }
            .entries.sortedByDescending { it.value }.take(20).mapNotNull { byId[it.key] }
    }

    data class Trending(val movies: List<Movie>, val series: List<Series>, val fromTmdb: Boolean)

    /** TMDB's trending lists matched to your sources; without a TMDB key, the best-rated new additions. */
    suspend fun trending(): Trending = withContext(Dispatchers.IO) {
        val key = settings.tmdbKey.trim()
        fun fetch(media: String): List<Pair<String, Int?>> {
            if (key.isEmpty()) return emptyList()
            return runCatching {
                val bearer = key.length > 40
                val b = Request.Builder().url("https://api.themoviedb.org/3/trending/$media/week" + if (bearer) "" else "?api_key=$key")
                if (bearer) b.header("Authorization", "Bearer $key")
                http.newCall(b.build()).execute().use { r ->
                    if (!r.isSuccessful) return@runCatching emptyList()
                    val a = JSONObject(r.body?.string() ?: "{}").optJSONArray("results") ?: JSONArray()
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
                        val t = o.optString("title").ifEmpty { o.optString("name") }
                        t to (o.optString("release_date").ifEmpty { o.optString("first_air_date") }).take(4).toIntOrNull()
                    }
                }
            }.getOrDefault(emptyList())
        }
        fun <T> match(list: List<T>, name: (T) -> String, year: (T) -> Int?, wanted: List<Pair<String, Int?>>): List<T> {
            val idx = list.groupBy { Text.cleanTitle(name(it)) }
            return wanted.mapNotNull { (t, y) -> idx[Text.cleanTitle(t)]?.let { c -> c.firstOrNull { year(it) == y } ?: c.first() } }.distinct()
        }
        val tm = match(movies, { it.name }, { it.year }, fetch("movie"))
        val ts = match(series, { it.name }, { it.year }, fetch("tv"))
        if (tm.isNotEmpty() || ts.isNotEmpty()) return@withContext Trending(tm, ts, true)
        val yearAgo = System.currentTimeMillis() - 365 * 86_400_000L
        val fm = movies.filter { (it.added ?: 0) > yearAgo || it.added == null }.sortedWith(compareByDescending<Movie> { it.rating ?: 0.0 }.thenByDescending { it.added ?: 0 }).take(20)
        val fs = series.sortedByDescending { it.rating ?: 0.0 }.take(20)
        Trending(fm, fs, false)
    }

    // ---------------- resume ----------------

    fun position(id: String): Long = positions[id]?.first ?: 0
    fun progress(id: String): Float = positions[id]?.let { (p, d) -> if (d > 0) p.toFloat() / d else 0f } ?: 0f

    fun savePosition(id: String, ms: Long, durationMs: Long) {
        if (durationMs <= 0) return
        positions = if (ms < 30_000 || ms > durationMs * 0.95) positions - id else positions + (id to (ms to durationMs))
        prefs.edit().putString("positions", JSONObject().also { o -> positions.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.first, v.second))) } }.toString()).apply()
        if (id !in positions) { resume = resume.filter { it.id != id || it.seriesId != null }; writeResume() }
    }

    fun noteWatching(movie: Movie) = note(ResumeEntry(movie.id, movie.name, movie.year?.toString(), movie.poster, movieId = movie.id))
    fun noteWatching(s: Series, e: Episode) =
        note(ResumeEntry(e.id, s.name, "S${e.season} E${e.number} · ${e.title}", e.thumb ?: s.poster, seriesId = s.id, episodeId = e.id))

    private fun note(e: ResumeEntry) {
        resume = (listOf(e) + resume.filter { it.id != e.id && !(e.seriesId != null && it.seriesId == e.seriesId) }).take(40)
        writeResume()
    }

    fun removeResume(id: String) {
        resume = resume.filter { it.id != id }
        positions = positions - id
        writeResume()
        prefs.edit().putString("positions", JSONObject().also { o -> positions.forEach { (k, v) -> o.put(k, JSONArray(listOf(v.first, v.second))) } }.toString()).apply()
    }

    fun continueEntries(): List<ResumeEntry> {
        val m = movies.associateBy { it.id }
        val s = series.associateBy { it.id }
        return resume.filter { r -> if (r.movieId != null) m.containsKey(r.movieId) && positions.containsKey(r.id) else s.containsKey(r.seriesId) }
    }

    fun movie(id: String?) = movies.firstOrNull { it.id == id }
    fun seriesById(id: String?) = series.firstOrNull { it.id == id }

    suspend fun details(movie: Movie): Details {
        detailCache[movie.id]?.let { return it }
        val pl = playlist(movie.playlistId)
        if (pl == null || pl.kind != Playlist.Kind.Xtream || movie.streamId == null) return Details()
        return withContext(Dispatchers.IO) {
            val x = Xtream(pl)
            x.parseDetails(get(x.api("get_vod_info", mapOf("vod_id" to movie.streamId.toString())), pl.userAgent), null)
        }.also { detailCache[movie.id] = it }
    }

    suspend fun details(s: Series): Details {
        detailCache[s.id]?.let { return it }
        if (s.episodes != null) return Details(plot = s.plot, episodes = s.episodes)
        val pl = playlist(s.playlistId)
        if (pl == null || pl.kind != Playlist.Kind.Xtream || s.seriesId == null) return Details()
        return withContext(Dispatchers.IO) {
            val x = Xtream(pl)
            x.parseDetails(get(x.api("get_series_info", mapOf("series_id" to s.seriesId.toString())), pl.userAgent), s.seriesId)
        }.also { detailCache[s.id] = it }
    }

    // ---------------- reminders ----------------

    fun hasReminder(ch: Channel, p: Programme) = reminders.any { it.channelId == ch.id && it.start == p.start }
    fun toggleReminder(ch: Channel, p: Programme) {
        reminders = if (hasReminder(ch, p)) reminders.filterNot { it.channelId == ch.id && it.start == p.start }
        else reminders + Reminder(ch.id, ch.name, p.title, p.start)
        writeReminders()
    }

    private fun checkReminders() {
        val now = System.currentTimeMillis()
        val due = reminders.firstOrNull { it.start <= now + 60_000 && it.start > now - 600_000 }
        val keep = reminders.filter { it != due && it.start > now - 600_000 }
        if (keep.size != reminders.size) { reminders = keep; writeReminders() }
        if (due != null) dueReminder = due
    }

    // ---------------- search ----------------

    fun search(query: String, wantTags: Set<QTag>, scope: SearchScope): SearchResult {
        val q = query.trim().lowercase()
        if (q.length < 2 && wantTags.isEmpty()) return SearchResult()
        fun match(s: String?) = q.isEmpty() || (s != null && s.lowercase().contains(q))
        val pool = if (wantTags.isEmpty()) channels else channels.filter { c -> tags(c).containsAll(wantTags) }
        val chans = if (scope != SearchScope.Programmes) pool.filter { match(it.name) }.take(200) else emptyList()
        if (q.isEmpty() || scope == SearchScope.Channels) return SearchResult(channels = chans)
        val mv = if (scope == SearchScope.All) movies.filter { match(it.name) }.take(80) else emptyList()
        val se = if (scope == SearchScope.All) series.filter { match(it.name) }.take(80) else emptyList()
        val now = System.currentTimeMillis()
        val seen = HashSet<String>()
        val out = ArrayList<Pair<Channel, Programme>>()
        for (c in pool) {
            if (out.size >= 300) break
            val k = epgKey(c) ?: continue
            if (!seen.add(k)) continue
            guide.programmes[k]?.forEach { p ->
                if (p.end > now && p.start < now + 2 * 86_400_000L && (match(p.title) || match(p.category) || match(p.episode))) out += c to p
            }
        }
        return SearchResult(chans, out.sortedBy { it.second.start }.take(80), mv, se)
    }

    // ---------------- persistence ----------------

    private fun readPlaylists(): List<Playlist> {
        val a = runCatching { JSONArray(prefs.getString("playlists", "[]")) }.getOrDefault(JSONArray())
        val list = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Playlist.fromJson(it) }
        if (list.isNotEmpty()) return list
        // Sources set at build time (IptvKeys), so nothing has to be typed with the remote.
        return IptvKeys.PLAYLISTS.mapIndexed { i, p -> p.copy(id = UUID.randomUUID().toString(), colorIndex = i) }
            .also { if (it.isNotEmpty()) prefs.edit().putString("playlists", JSONArray(it.map { p -> p.toJson() }).toString()).apply() }
    }
    private fun writePlaylists() = prefs.edit().putString("playlists", JSONArray(playlists.map { it.toJson() }).toString()).apply()

    private fun readSet(k: String): Set<String> = readList(k).toSet()
    private fun readList(k: String): List<String> {
        val a = runCatching { JSONArray(prefs.getString(k, "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.optString(it) }
    }
    private fun writeSet(k: String, v: Set<String>) = writeList(k, v.toList())
    private fun writeList(k: String, v: List<String>) = prefs.edit().putString(k, JSONArray(v).toString()).apply()
    private fun readMap(k: String): Map<String, String> {
        val o = runCatching { JSONObject(prefs.getString(k, "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().associateWith { o.optString(it) }
    }
    private fun writeMap(k: String, v: Map<String, String>) = prefs.edit().putString(k, JSONObject(v).toString()).apply()

    private fun readPositions(): Map<String, Pair<Long, Long>> {
        val o = runCatching { JSONObject(prefs.getString("positions", "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().mapNotNull { k -> o.optJSONArray(k)?.let { k to (it.optLong(0) to it.optLong(1)) } }.toMap()
    }
    private fun readMeasured(): Map<String, Measured> {
        val o = runCatching { JSONObject(prefs.getString("measured", "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().mapNotNull { k ->
            o.optJSONObject(k)?.let { k to Measured(it.optInt("h"), it.optInt("c").takeIf { c -> c > 0 }, it.optBoolean("hdr")) }
        }.toMap()
    }
    private fun readPlays(): Map<String, List<Long>> {
        val o = runCatching { JSONObject(prefs.getString("plays", "{}")!!) }.getOrDefault(JSONObject())
        return o.keys().asSequence().mapNotNull { k -> o.optJSONArray(k)?.let { a -> k to (0 until a.length()).map { a.optLong(it) } } }.toMap()
    }
    private fun readResume(): List<ResumeEntry> {
        val a = runCatching { JSONArray(prefs.getString("resume", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            fun s(k: String) = it.optString(k).ifEmpty { null }
            ResumeEntry(it.optString("id"), it.optString("title"), s("subtitle"), s("poster"), s("movieId"), s("seriesId"), s("episodeId"))
        }
    }
    private fun writeResume() = prefs.edit().putString("resume", JSONArray(resume.map {
        JSONObject().put("id", it.id).put("title", it.title).put("subtitle", it.subtitle ?: "").put("poster", it.poster ?: "")
            .put("movieId", it.movieId ?: "").put("seriesId", it.seriesId ?: "").put("episodeId", it.episodeId ?: "")
    }).toString()).apply()
    private fun readReminders(): List<Reminder> {
        val a = runCatching { JSONArray(prefs.getString("reminders", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Reminder(it.optString("c"), it.optString("n"), it.optString("t"), it.optLong("s")) }
    }
    private fun writeReminders() = prefs.edit().putString("reminders", JSONArray(reminders.map {
        JSONObject().put("c", it.channelId).put("n", it.channelName).put("t", it.title).put("s", it.start)
    }).toString()).apply()
}
