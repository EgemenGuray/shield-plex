package app.plexhdr.shield.plex

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class PlexException(message: String) : IOException(message)

/** plex.tv PIN / QR sign-in, server discovery, library browsing, playback URLs and progress reporting. */
class PlexClient(context: Context) {
    companion object {
        const val PRODUCT = "Lumen (Shield)"
        const val VERSION = "0.1.0"

        /** Builds a URL with sorted query parameters; null values are left out. */
        fun url(base: String, path: String, params: Map<String, Any?> = emptyMap()): String {
            val b = Uri.parse(base + path).buildUpon()
            for ((k, v) in params.entries.sortedBy { it.key }) if (v != null) b.appendQueryParameter(k, v.toString())
            // "+" is legal in a query but Plex reads it as a space.
            return b.build().toString().replace("+", "%2B")
        }
    }

    private val prefs = context.getSharedPreferences("plex", Context.MODE_PRIVATE)
    val clientId: String = prefs.getString("clientId", null) ?: UUID.randomUUID().toString().also {
        prefs.edit().putString("clientId", it).apply()
    }

    var token by mutableStateOf(prefs.getString("token", null)); private set
    var server by mutableStateOf(PlexServer.fromJson(prefs.getString("server", null))); private set
    var user by mutableStateOf(PlexUser.fromJson(prefs.getString("user", null))); private set

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cache(Cache(File(context.cacheDir, "http"), 64L shl 20))
        .build()

    val deviceName: String = (Build.MODEL ?: "SHIELD").let { if (it.contains("SHIELD", true)) "SHIELD Android TV" else it }

    val headers: Map<String, String>
        get() = mapOf(
            "X-Plex-Product" to PRODUCT,
            "X-Plex-Version" to VERSION,
            "X-Plex-Client-Identifier" to clientId,
            "X-Plex-Platform" to "Android",
            "X-Plex-Platform-Version" to Build.VERSION.RELEASE,
            "X-Plex-Device" to deviceName,
            "X-Plex-Device-Name" to "Lumen on $deviceName",
            "X-Plex-Model" to (Build.MODEL ?: ""),
            "X-Plex-Provides" to "player",
        )

    // ---- HTTP

    suspend fun request(
        method: String, url: String, token: String? = null, timeoutMs: Long = 15_000,
        accept: String = "application/json", extra: Map<String, String> = emptyMap(),
    ): ByteArray = withContext(Dispatchers.IO) {
        val b = Request.Builder().url(url).header("Accept", accept)
        headers.forEach { (k, v) -> b.header(k, v) }
        extra.forEach { (k, v) -> b.header(k, v) }
        if (token != null) b.header("X-Plex-Token", token)
        when (method) {
            "GET" -> b.get()
            else -> b.method(method, ByteArray(0).toRequestBody(null))
        }
        val client = if (timeoutMs != 15_000L) http.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build() else http
        client.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw PlexException("${Uri.parse(url).path} → HTTP ${r.code}")
            r.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun json(method: String, url: String, token: String? = null, timeoutMs: Long = 15_000): Any {
        val d = request(method, url, token, timeoutMs)
        if (d.isEmpty()) return JSONObject()
        val s = String(d)
        return try {
            if (s.trimStart().startsWith("[")) JSONArray(s) else JSONObject(s)
        } catch (e: Exception) { JSONObject() }
    }

    private suspend fun obj(method: String, url: String, token: String? = null) = json(method, url, token) as? JSONObject ?: JSONObject()

    // ---- Sign-in: a 4-character code for plex.tv/link and a QR code that opens Plex's sign-in page on a phone.

    data class Pin(val id: Long, val code: String)

    suspend fun createPin(strong: Boolean): Pin {
        val r = obj("POST", url("https://plex.tv", "/api/v2/pins", mapOf("strong" to strong.toString())))
        val id = r.optLong("id", -1)
        val code = r.optString("code")
        if (id < 0 || code.isEmpty()) throw PlexException("plex.tv did not return a code")
        return Pin(id, code)
    }

    /** The page the QR code opens: Plex's own sign-in, which approves this device directly. */
    fun qrUrl(strongPin: Pin): String =
        "https://app.plex.tv/auth#?clientID=${Uri.encode(clientId)}&code=${Uri.encode(strongPin.code)}" +
            "&context%5Bdevice%5D%5Bproduct%5D=${Uri.encode(PRODUCT)}"

    /** True once the code has been entered at plex.tv/link (or approved from the QR page). */
    suspend fun checkPin(pin: Pin): Boolean {
        val r = obj("GET", url("https://plex.tv", "/api/v2/pins/${pin.id}"))
        val t = r.strOrNull("authToken") ?: return false
        storeToken(t)
        runCatching { account() }
        return true
    }

    private fun storeToken(t: String?) {
        token = t
        prefs.edit().putString("token", t).apply()
    }

    private fun saveServer(s: PlexServer?) {
        server = s
        prefs.edit().putString("server", s?.toJson()).apply()
    }

    private fun saveUser(u: PlexUser?) {
        user = u
        prefs.edit().putString("user", u?.toJson()).apply()
    }

    fun signOut() {
        storeToken(null); saveServer(null); saveUser(null)
    }

    suspend fun account(): PlexUser {
        val u = obj("GET", url("https://plex.tv", "/api/v2/user"), token)
        val pu = PlexUser(u.strOrNull("title") ?: u.optString("username"), u.strOrNull("thumb"), u.strOrNull("uuid"))
        saveUser(pu)
        return pu
    }

    // ---- Plex Home users

    class HomeUser(o: JSONObject) : J(o) {
        val uuid get() = str("uuid") ?: ""
        val title get() = str("title") ?: ""
        val thumb get() = str("thumb")
        val protected get() = bool("protected")
        val admin get() = bool("admin")
    }

    suspend fun homeUsers(): List<HomeUser> {
        val r = json("GET", url("https://plex.tv", "/api/v2/home/users"), token)
        val list = (r as? JSONObject)?.optJSONArray("users") ?: (r as? JSONArray)
        return list.objects().map { HomeUser(it) }
    }

    /** Switching gives a token for that user; the server must then be re-resolved, because its access token is per user. */
    suspend fun switchUser(u: HomeUser, pin: String?) {
        val r = obj("POST", url("https://plex.tv", "/api/v2/home/users/${u.uuid}/switch", mapOf("pin" to pin)), token)
        val t = r.strOrNull("authToken") ?: throw PlexException("Plex did not accept the switch")
        val machineId = server?.machineId
        storeToken(t)
        saveUser(PlexUser(r.strOrNull("title") ?: u.title, r.strOrNull("thumb") ?: u.thumb, r.strOrNull("uuid") ?: u.uuid))
        saveServer(null)
        val servers = listServers()
        (servers.firstOrNull { it.optString("clientIdentifier") == machineId } ?: servers.firstOrNull())?.let { connect(it) }
    }

    // ---- Servers

    suspend fun listServers(): List<JSONObject> {
        val r = json("GET", url("https://plex.tv", "/api/v2/resources", mapOf("includeHttps" to 1, "includeRelay" to 1)), token)
        return (r as? JSONArray).objects().filter { it.optString("provides").contains("server") }
    }

    /** Tries the server's connections, local first and relay last, and keeps the first one that answers. */
    suspend fun connect(resource: JSONObject): PlexServer {
        val conns = resource.optJSONArray("connections").objects().sortedWith(
            compareBy<JSONObject> { it.optBoolean("relay") }.thenBy { !it.optBoolean("local") }
        )
        val tok = resource.strOrNull("accessToken") ?: token ?: ""
        val name = resource.strOrNull("name") ?: "Plex"
        for (c in conns) {
            val uri = c.strOrNull("uri") ?: continue
            try {
                request("GET", url(uri, "/identity"), tok, timeoutMs = 4_000)
                val s = PlexServer(name, uri, tok, resource.optString("clientIdentifier"), c.optBoolean("relay"))
                saveServer(s)
                return s
            } catch (e: Exception) {
                continue
            }
        }
        throw PlexException("Could not reach $name on any address")
    }

    // ---- Library

    suspend fun api(path: String, params: Map<String, Any?> = emptyMap()): JSONObject {
        val s = server ?: throw PlexException("No Plex server selected")
        val r = json("GET", url(s.uri, path, params), s.token)
        return (r as? JSONObject)?.optJSONObject("MediaContainer") ?: JSONObject()
    }

    private fun items(mc: JSONObject) = mc.optJSONArray("Metadata").objects().map { PlexItem(it) }

    suspend fun sections(): List<PlexSection> = api("/library/sections").optJSONArray("Directory").objects().map { PlexSection(it) }

    data class ItemQuery(
        val sort: String = "originallyAvailableAt:desc",
        val unwatched: Boolean = false,
        val genre: String? = null,
        val start: Int = 0,
        val size: Int = 100,
        val person: PlexPerson? = null,
    )

    suspend fun sectionItems(id: String, q: ItemQuery): Pair<List<PlexItem>, Int> {
        val p = mutableMapOf<String, Any?>(
            "X-Plex-Container-Start" to q.start, "X-Plex-Container-Size" to q.size,
            "sort" to q.sort, "unwatched" to if (q.unwatched) 1 else null, "genre" to q.genre, "includeGuids" to 1,
        )
        q.person?.let { p[it.kind.param] = it.tagId }
        val mc = api("/library/sections/$id/all", p)
        val list = items(mc)
        return list to (mc.optInt("totalSize", list.size))
    }

    suspend fun recentlyAdded(sectionId: String, size: Int = 20) =
        items(api("/library/sections/$sectionId/recentlyAdded", mapOf("X-Plex-Container-Start" to 0, "X-Plex-Container-Size" to size, "includeGuids" to 1)))

    data class Genre(val key: String, val title: String)

    suspend fun genres(sectionId: String): List<Genre> =
        api("/library/sections/$sectionId/genre").optJSONArray("Directory").objects().mapNotNull {
            val k = it.strOrNull("key") ?: return@mapNotNull null
            Genre(k, it.optString("title"))
        }

    suspend fun collections(sectionId: String) = items(api("/library/sections/$sectionId/collections"))
    suspend fun collectionItems(ratingKey: String) = items(api("/library/collections/$ratingKey/children"))
    suspend fun onDeck() = items(api("/library/onDeck", mapOf("includeGuids" to 1)))

    /** Chapters, intro/credits markers, extras, the show's next-up episode, cast and crew in one call. */
    suspend fun metadata(ratingKey: String): PlexItem? = items(
        api("/library/metadata/$ratingKey", mapOf("includeChapters" to 1, "includeMarkers" to 1, "includeExtras" to 1, "includeOnDeck" to 1, "includeGuids" to 1))
    ).firstOrNull()

    suspend fun children(ratingKey: String) = items(api("/library/metadata/$ratingKey/children"))
    suspend fun similar(ratingKey: String) = runCatching { items(api("/library/metadata/$ratingKey/similar", mapOf("count" to 20))) }.getOrDefault(emptyList())

    /** Every movie and show in the video libraries that this person is credited on, newest first. */
    suspend fun personItems(person: PlexPerson): List<PlexItem> {
        if (person.tagId == null) return emptyList()
        val out = ArrayList<PlexItem>()
        for (s in runCatching { sections() }.getOrDefault(emptyList()).filter { it.isVideo }) {
            out += runCatching { sectionItems(s.id, ItemQuery(size = 300, person = person)).first }.getOrDefault(emptyList())
        }
        return out.distinctBy { it.id }.sortedByDescending { it.originallyAvailableAt ?: "" }
    }

    suspend fun search(query: String): List<PlexItem> {
        val mc = api("/hubs/search", mapOf("query" to query, "limit" to 20, "includeCollections" to 1))
        return mc.optJSONArray("Hub").objects().flatMap { hub ->
            if (hub.optString("type") in setOf("movie", "show", "episode", "collection")) hub.optJSONArray("Metadata").objects().map { PlexItem(it) }
            else emptyList()
        }
    }

    suspend fun setWatched(item: PlexItem, watched: Boolean) {
        val s = server ?: return
        val rk = item.ratingKey ?: return
        runCatching {
            request("GET", url(s.uri, if (watched) "/:/scrobble" else "/:/unscrobble", mapOf("key" to rk, "identifier" to "com.plexapp.plugins.library")), s.token)
        }
    }

    /** Next episode after this one: the next in its season, else the first episode of the next season. */
    suspend fun nextEpisode(ep: PlexItem): PlexItem? {
        if (ep.type != "episode") return null
        val season = ep.parentRatingKey ?: return null
        val eps = runCatching { children(season) }.getOrDefault(emptyList())
        var next = eps.filter { (it.index ?: 0) > (ep.index ?: 0) }.minByOrNull { it.index ?: 0 }
        if (next == null) {
            val show = ep.grandparentRatingKey ?: return null
            val seasons = runCatching { children(show) }.getOrDefault(emptyList())
            val ns = seasons.filter { (it.index ?: 0) > (ep.parentIndex ?: 0) }.minByOrNull { it.index ?: 0 }
            next = ns?.ratingKey?.let { rk -> runCatching { children(rk).firstOrNull() }.getOrNull() }
        }
        return next?.ratingKey?.let { runCatching { metadata(it) }.getOrNull() }
    }

    // ---- Artwork

    fun imageUrl(path: String?, width: Int, height: Int): String? {
        if (path == null) return null
        val s = server ?: return null
        if (path.startsWith("http") && !path.startsWith(s.uri)) return path
        return url(s.uri, "/photo/:/transcode", mapOf("width" to width, "height" to height, "minSize" to 1, "upscale" to 1, "url" to path, "X-Plex-Token" to s.token))
    }

    // ---- Playback

    /** The original file over HTTP. ExoPlayer demuxes it on the Shield; nothing is converted. */
    fun directPlayUrl(part: PlexPart): String? {
        val s = server ?: return null
        val key = part.key ?: return null
        return url(s.uri, key, mapOf("X-Plex-Token" to s.token, "X-Plex-Client-Identifier" to clientId))
    }

    /**
     * For files the Shield can't decode (8K): the server converts the video to 4K HEVC and copies the audio untouched,
     * in an MKV stream, so TrueHD/DTS still reach the receiver as a bitstream.
     */
    fun transcode4kUrl(item: PlexItem, mediaIndex: Int, offsetMs: Long, session: String): String? {
        val s = server ?: return null
        val rk = item.ratingKey ?: return null
        val extra = listOf(
            "add-transcode-target(type=videoProfile&context=streaming&protocol=http&container=mkv&videoCodec=hevc&audioCodec=truehd,dca,eac3,ac3,aac,flac)",
            "add-limitation(scope=videoCodec&scopeName=hevc&type=upperBound&name=video.width&value=3840)",
            "add-limitation(scope=videoCodec&scopeName=hevc&type=upperBound&name=video.height&value=2160)",
        ).joinToString("+")
        val p = HashMap<String, Any?>(headers)
        p["X-Plex-Token"] = s.token
        p["X-Plex-Client-Profile-Name"] = "Generic"
        p["X-Plex-Client-Profile-Extra"] = extra
        p["X-Plex-Session-Identifier"] = session
        p["session"] = session
        p["path"] = "/library/metadata/$rk"
        p["mediaIndex"] = mediaIndex
        p["partIndex"] = 0
        p["protocol"] = "http"
        p["fastSeek"] = 1
        p["directPlay"] = 0
        p["directStream"] = 0
        p["directStreamAudio"] = 1
        p["videoQuality"] = 100
        p["maxVideoBitrate"] = 120_000
        p["videoResolution"] = "3840x2160"
        p["subtitles"] = "none"
        p["offset"] = offsetMs / 1000
        return url(s.uri, "/video/:/transcode/universal/start.mkv", p)
    }

    suspend fun stopTranscode(session: String) {
        val s = server ?: return
        runCatching { request("GET", url(s.uri, "/video/:/transcode/universal/stop", mapOf("session" to session)), s.token) }
    }

    fun streamUrl(key: String): String? {
        if (key.startsWith("http")) return key
        val s = server ?: return null
        return url(s.uri, key, mapOf("X-Plex-Token" to s.token))
    }

    /** Trick-play thumbnails (BIF), generated by the server when "video preview thumbnails" is on. */
    suspend fun previewIndex(part: PlexPart): ByteArray? {
        val s = server ?: return null
        val id = part.id ?: return null
        if (part.indexes?.contains("sd") != true) return null
        return runCatching { request("GET", url(s.uri, "/library/parts/$id/indexes/sd"), s.token, timeoutMs = 60_000, accept = "*/*") }.getOrNull()
    }

    /** Persists the chosen audio/subtitle stream on the server, so other clients use the same tracks next time. */
    suspend fun selectStreams(partId: Int, audio: Int?, subtitle: Int?) {
        val s = server ?: return
        val p = mutableMapOf<String, Any?>("allParts" to 1)
        audio?.let { p["audioStreamID"] = it }
        subtitle?.let { p["subtitleStreamID"] = it }
        runCatching { request("PUT", url(s.uri, "/library/parts/$partId", p), s.token) }
    }

    suspend fun timeline(item: PlexItem, state: String, timeMs: Long, durationMs: Long) {
        val s = server ?: return
        val rk = item.ratingKey ?: return
        runCatching {
            request("GET", url(s.uri, "/:/timeline", mapOf(
                "ratingKey" to rk, "key" to "/library/metadata/$rk", "state" to state, "time" to timeMs, "duration" to durationMs,
            )), s.token)
        }
    }

    // ---- The server's own subtitle search (its OpenSubtitles agent)

    suspend fun searchServerSubtitles(item: PlexItem, language: String): List<PlexStream> {
        val rk = item.ratingKey ?: return emptyList()
        val mc = runCatching { api("/library/metadata/$rk/subtitles", mapOf("language" to language, "hearingImpaired" to 0, "forced" to 0)) }.getOrNull()
        return mc?.optJSONArray("Stream").objects().map { PlexStream(it) }
    }

    suspend fun saveServerSubtitle(item: PlexItem, result: PlexStream) {
        val s = server ?: return
        val rk = item.ratingKey ?: return
        request("PUT", url(s.uri, "/library/metadata/$rk/subtitles", mapOf(
            "key" to result.key, "codec" to result.codec, "language" to result.languageCode,
            "hearingImpaired" to if (result.hearingImpaired) 1 else 0, "forced" to if (result.forced) 1 else 0,
            "providerTitle" to result.str("providerTitle"),
        )), s.token)
    }
}
