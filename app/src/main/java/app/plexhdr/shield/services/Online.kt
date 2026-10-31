package app.plexhdr.shield.services

import android.content.Context
import app.plexhdr.shield.plex.PlexClient
import app.plexhdr.shield.plex.PlexException
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.plex.objects
import app.plexhdr.shield.plex.strOrNull
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * Internet services, the way Infuse uses them: TMDB for artwork Plex doesn't have (title logos, backdrops, posters),
 * ratings from IMDb / Letterboxd / Rotten Tomatoes / Metacritic, and OpenSubtitles (matched by file hash first).
 * All need your own free API keys: Settings → Online services, or Keys.kt.
 */
class Online(context: Context, private val settings: Settings, private val plex: PlexClient) {
    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbImages = "https://image.tmdb.org/t/p/"
    private val osBase = "https://api.opensubtitles.com/api/v1"
    private val appUA = "PlexHDR-Shield v0.1"
    private val http: OkHttpClient = plex.http
    private val prefs = context.getSharedPreferences("online", Context.MODE_PRIVATE)
    val subtitleDir = File(context.filesDir, "subtitles").apply { mkdirs() }

    // ---- HTTP

    private suspend fun get(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: JSONObject? = null): ByteArray =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url)
            headers.forEach { (k, v) -> b.header(k, v) }
            if (body != null) b.method(method, body.toString().toRequestBody("application/json".toMediaType()))
            http.newCall(b.build()).execute().use { r ->
                val bytes = r.body?.bytes() ?: ByteArray(0)
                if (!r.isSuccessful) {
                    val msg = runCatching { JSONObject(String(bytes)).strOrNull("message") }.getOrNull()
                    throw PlexException(msg ?: "HTTP ${r.code}")
                }
                bytes
            }
        }

    private suspend fun getJSON(url: String, headers: Map<String, String> = emptyMap(), method: String = "GET", body: JSONObject? = null): JSONObject =
        runCatching { JSONObject(String(get(url, headers, method, body))) }.getOrElse { if (it is PlexException) throw it else JSONObject() }

    // ---- TMDB artwork

    data class Artwork(val logo: String?, val backdrop: String?, val poster: String?)

    private val artMutex = Mutex()
    private val artPending = HashMap<String, Deferred<Artwork?>>()
    private val artMemory = HashMap<String, Artwork>()

    private suspend fun tmdb(path: String, params: Map<String, String> = emptyMap()): JSONObject {
        val key = settings.tmdbKey
        val p = params.toMutableMap<String, Any?>()
        val h = HashMap<String, String>()
        // A v4 "read access token" is a long JWT; a v3 key is 32 hex characters.
        if (key.length > 40) h["Authorization"] = "Bearer $key" else p["api_key"] = key
        return getJSON(PlexClient.url(tmdbBase, path, p), h)
    }

    private fun pick(list: List<JSONObject>, lang: String?): JSONObject? {
        val sorted = list.sortedByDescending { it.optDouble("vote_average", 0.0) }
        fun code(j: JSONObject) = j.strOrNull("iso_639_1")
        if (lang != null) sorted.firstOrNull { code(it) == lang }?.let { return it }
        return sorted.firstOrNull { code(it) == "en" } ?: sorted.firstOrNull { code(it) == null } ?: sorted.firstOrNull()
    }

    /** Logo, backdrop and poster for a movie or show; cached on the Shield. */
    suspend fun artwork(item: PlexItem): Artwork? {
        if (settings.tmdbKey.isEmpty() || !settings.tmdbArtwork) return null
        val kind = when (item.type) { "show" -> "tv"; "movie" -> "movie"; else -> return null }
        val id = item.guids["tmdb"] ?: return null
        val ck = "art.$kind$id"
        artMemory[ck]?.let { return it }
        prefs.getString(ck, null)?.let { s ->
            val o = JSONObject(s)
            return Artwork(o.strOrNull("logo"), o.strOrNull("backdrop"), o.strOrNull("poster")).also { artMemory[ck] = it }
        }
        val job = coroutineScope {
            artMutex.withLock {
                artPending.getOrPut(ck) {
                    async {
                        val r = runCatching { tmdb("/$kind/$id/images", mapOf("include_image_language" to "en,tr,null")) }.getOrNull() ?: return@async null
                        val logos = r.optJSONArray("logos").objects()
                        val backs = r.optJSONArray("backdrops").objects()
                        val posters = r.optJSONArray("posters").objects()
                        val textless = backs.filter { it.strOrNull("iso_639_1") == null }
                        fun path(j: JSONObject?, size: String) = j?.strOrNull("file_path")?.let { tmdbImages + size + it }
                        Artwork(path(pick(logos, "en"), "w780"), path(pick(textless.ifEmpty { backs }, null), "w1280"), path(pick(posters, "en"), "w342"))
                    }
                }
            }
        }
        val a = job.await()
        artMutex.withLock { artPending.remove(ck) }
        if (a != null) {
            artMemory[ck] = a
            prefs.edit().putString(ck, JSONObject().put("logo", a.logo).put("backdrop", a.backdrop).put("poster", a.poster).toString()).apply()
        }
        return a
    }

    // ---- Ratings

    data class Ratings(
        val imdb: String? = null,
        val letterboxd: String? = null,
        val rtCritic: String? = null,
        val rtAudience: String? = null,
        val metaUser: String? = null,
        val metaCritic: String? = null,
    ) {
        val isEmpty get() = listOf(imdb, letterboxd, rtCritic, rtAudience, metaUser, metaCritic).all { it == null }

        fun toJson(): JSONObject = JSONObject().put("imdb", imdb).put("letterboxd", letterboxd).put("rtCritic", rtCritic)
            .put("rtAudience", rtAudience).put("metaUser", metaUser).put("metaCritic", metaCritic)

        companion object {
            fun from(o: JSONObject) = Ratings(o.strOrNull("imdb"), o.strOrNull("letterboxd"), o.strOrNull("rtCritic"),
                o.strOrNull("rtAudience"), o.strOrNull("metaUser"), o.strOrNull("metaCritic"))
        }
    }

    private fun f1(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun pct(v: Double) = "${Math.round(v * 10)}%"

    /** Plex already carries IMDb and Rotten Tomatoes (critic and audience) for items matched by its own agent. */
    fun plexRatings(item: PlexItem): Ratings {
        var out = Ratings()
        for (r in item.ratings) {
            val img = r.optString("image")
            val v = r.optDouble("value", Double.NaN).takeIf { !it.isNaN() } ?: continue
            out = when {
                img.startsWith("imdb:") -> out.copy(imdb = f1(v))
                Regex("^rottentomatoes://image\\.rating\\.(ripe|rotten)").containsMatchIn(img) -> out.copy(rtCritic = pct(v))
                Regex("^rottentomatoes://image\\.rating\\.(upright|spilled)").containsMatchIn(img) -> out.copy(rtAudience = pct(v))
                else -> out
            }
        }
        if (out.imdb == null && item.audienceRatingImage?.contains("imdb") == true) item.audienceRating?.let { out = out.copy(imdb = f1(it)) }
        if (out.rtCritic == null && item.ratingImage?.contains("rottentomatoes") == true) item.rating?.let { out = out.copy(rtCritic = pct(it)) }
        if (out.rtAudience == null && item.audienceRatingImage?.contains("rottentomatoes") == true) item.audienceRating?.let { out = out.copy(rtAudience = pct(it)) }
        return out
    }

    private fun slug(t: String): String = t.lowercase().replace("&", "and")
        .replace(Regex("['’.:]"), "")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun firstMatch(pattern: String, text: String): String? =
        Regex(pattern, RegexOption.IGNORE_CASE).find(text)?.groupValues?.getOrNull(1)

    /**
     * Best effort: a source that fails or changes its page is left out. OMDb (free key) gives IMDb, Rotten Tomatoes and
     * the Metascore. Letterboxd and the Metacritic user score have no public API, so their public pages are read.
     */
    suspend fun ratings(item: PlexItem): Ratings {
        val ck = "ratings.${item.ratingKey}"
        prefs.getString(ck, null)?.let { s ->
            val o = JSONObject(s)
            if (System.currentTimeMillis() / 1000 - o.optLong("at") < 7 * 86400) return Ratings.from(o.getJSONObject("r"))
        }
        var r = plexRatings(item)
        val ids = item.guids
        val isShow = item.type == "show"
        val omdbUrl = if (settings.omdbKey.isEmpty()) null else ids["imdb"]?.let { PlexClient.url("https://www.omdbapi.com", "/", mapOf("i" to it, "apikey" to settings.omdbKey)) }
        val lbUrl = if (isShow) null else ids["tmdb"]?.let { "https://letterboxd.com/tmdb/$it/" }
        val mcUrl = if ((item.type == "movie" || isShow) && item.title.isNotEmpty()) "https://www.metacritic.com/${if (isShow) "tv" else "movie"}/${slug(item.title)}/" else null
        coroutineScope {
            val omdb = async { omdbUrl?.let { runCatching { getJSON(it) }.getOrNull() } }
            val lb = async { page(lbUrl) }
            val mc = async { page(mcUrl) }
            omdb.await()?.let { o ->
                o.strOrNull("imdbRating")?.takeIf { it != "N/A" }?.let { r = r.copy(imdb = it) }
                o.strOrNull("Metascore")?.takeIf { it != "N/A" }?.let { r = r.copy(metaCritic = it) }
                for (x in o.optJSONArray("Ratings").objects()) {
                    if (x.optString("Source") == "Rotten Tomatoes" && r.rtCritic == null) r = r.copy(rtCritic = x.strOrNull("Value"))
                }
            }
            lb.await()?.let { p ->
                firstMatch("name=\"twitter:data2\" content=\"([\\d.]+) out of 5\"", p)?.toDoubleOrNull()?.let { r = r.copy(letterboxd = f1(it)) }
            }
            mc.await()?.let { p ->
                (firstMatch("\"userScore\"\\s*:\\s*\\{[^}]*?\"score\"\\s*:\\s*([\\d.]+)", p) ?: firstMatch("user score[^\\d]{0,200}?(\\d\\.\\d)", p))
                    ?.toDoubleOrNull()?.let { r = r.copy(metaUser = f1(it)) }
                if (r.metaCritic == null) {
                    r = r.copy(metaCritic = firstMatch("\"criticScoreSummary\"\\s*:\\s*\\{[^}]*?\"score\"\\s*:\\s*(\\d+)", p)
                        ?: firstMatch("\"ratingValue\"\\s*:\\s*\"?(\\d+)", p))
                }
            }
        }
        prefs.edit().putString(ck, JSONObject().put("at", System.currentTimeMillis() / 1000).put("r", r.toJson()).toString()).apply()
        return r
    }

    private suspend fun page(url: String?): String? {
        url ?: return null
        return runCatching {
            String(get(url, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 11; SHIELD Android TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Safari/537.36")))
        }.getOrNull()
    }

    // ---- OpenSubtitles

    data class SubtitleResult(
        val source: String,            // "opensubtitles" or "plex"
        val fileId: String,
        val language: String,
        val title: String,
        val hashMatch: Boolean = false,
        val hearingImpaired: Boolean = false,
        val downloads: Int = 0,
        val machineTranslated: Boolean = false,
        val plexStream: app.plexhdr.shield.plex.PlexStream? = null,
    )

    private var osToken: String? = null
    private val osHeaders: Map<String, String>
        get() = buildMap {
            put("Api-Key", settings.openSubtitlesKey); put("Accept", "application/json")
            put("X-User-Agent", appUA); put("User-Agent", appUA)
            osToken?.let { put("Authorization", "Bearer $it") }
        }

    /** Signing in raises the daily download limit; without an account the API key alone allows a few a day. */
    private suspend fun osLogin() {
        if (osToken != null || settings.openSubtitlesUser.isEmpty() || settings.openSubtitlesPassword.isEmpty()) return
        val r = runCatching {
            getJSON("$osBase/login", osHeaders, "POST", JSONObject().put("username", settings.openSubtitlesUser).put("password", settings.openSubtitlesPassword))
        }.getOrNull()
        osToken = r?.strOrNull("token")
    }

    fun resetLogin() { osToken = null }

    /**
     * OpenSubtitles hash: file size plus the first and last 64 KB summed as little-endian 64-bit words. Finds subtitles
     * cut for this exact file. Two range requests to Plex.
     */
    suspend fun fileHash(url: String, size: Long): String? {
        val chunk = 65536L
        if (size < chunk * 2) return null
        return coroutineScope {
            val head = async { runCatching { get(url, mapOf("Range" to "bytes=0-${chunk - 1}")) }.getOrNull() }
            val tail = async { runCatching { get(url, mapOf("Range" to "bytes=${size - chunk}-${size - 1}")) }.getOrNull() }
            val a = head.await() ?: return@coroutineScope null
            val b = tail.await() ?: return@coroutineScope null
            if (a.size < chunk || b.size < chunk) return@coroutineScope null
            var hash = size
            for (buf in listOf(a, b)) {
                var i = 0
                while (i < chunk) {
                    var word = 0L
                    for (k in 7 downTo 0) word = (word shl 8) or (buf[i + k].toLong() and 0xff)
                    hash += word
                    i += 8
                }
            }
            String.format("%016x", hash)
        }
    }

    /** item: movie or episode; show: the episode's show (for its TMDB/IMDb id); languages: ["en", "tr"]. */
    suspend fun searchSubtitles(item: PlexItem, show: PlexItem?, languages: List<String>, fileUrl: String?, fileSize: Long?): List<SubtitleResult> {
        if (settings.openSubtitlesKey.isEmpty()) throw PlexException("No OpenSubtitles API key (Settings → Online services)")
        return coroutineScope {
            val hashJob = async { if (fileUrl != null && fileSize != null) fileHash(fileUrl, fileSize) else null }
            osLogin()
            val hash = hashJob.await()
            val p = mutableMapOf<String, Any?>("languages" to languages.joinToString(","), "order_by" to "download_count")
            hash?.let { p["moviehash"] = it }
            if (item.type == "episode") {
                val sid = (show ?: item).guids
                when {
                    sid["tmdb"] != null -> p["parent_tmdb_id"] = sid["tmdb"]
                    sid["imdb"] != null -> p["parent_imdb_id"] = sid["imdb"]!!.removePrefix("tt")
                    else -> p["query"] = item.grandparentTitle ?: item.title
                }
                p["season_number"] = item.parentIndex
                p["episode_number"] = item.index
            } else {
                val mid = item.guids
                when {
                    mid["tmdb"] != null -> p["tmdb_id"] = mid["tmdb"]
                    mid["imdb"] != null -> p["imdb_id"] = mid["imdb"]!!.removePrefix("tt")
                    else -> { p["query"] = item.title; p["year"] = item.year }
                }
            }
            val r = getJSON(PlexClient.url(osBase, "/subtitles", p), osHeaders)
            r.optJSONArray("data").objects().mapNotNull { d ->
                val a = d.optJSONObject("attributes") ?: return@mapNotNull null
                val f = a.optJSONArray("files").objects().firstOrNull() ?: return@mapNotNull null
                val fid = f.opt("file_id")?.toString() ?: return@mapNotNull null
                SubtitleResult(
                    "opensubtitles", fid, a.optString("language"),
                    a.strOrNull("release") ?: f.strOrNull("file_name") ?: "Subtitle",
                    hashMatch = a.optBoolean("moviehash_match"),
                    hearingImpaired = a.optBoolean("hearing_impaired"),
                    downloads = a.optInt("download_count"),
                    machineTranslated = a.optBoolean("ai_translated") || a.optBoolean("machine_translated"),
                )
            }.sortedWith(compareByDescending<SubtitleResult> { it.hashMatch }.thenByDescending { it.downloads })
        }
    }

    fun cachedSubtitle(name: String): File? = File(subtitleDir, name).takeIf { it.exists() }

    /** Downloads one result to a local .srt file, kept on the Shield so a replay doesn't use up the download quota. */
    suspend fun download(result: SubtitleResult): File {
        val name = "os-${result.fileId}.srt"
        cachedSubtitle(name)?.let { return it }
        osLogin()
        val r = getJSON("$osBase/download", osHeaders, "POST", JSONObject().put("file_id", result.fileId.toLongOrNull() ?: 0).put("sub_format", "srt"))
        val link = r.strOrNull("link") ?: throw PlexException(r.strOrNull("message") ?: "No download link")
        val data = get(link)
        val f = File(subtitleDir, name)
        withContext(Dispatchers.IO) {
            f.writeBytes(data)
            // Keep the 40 most recent downloads.
            subtitleDir.listFiles()?.sortedBy { it.lastModified() }?.let { files ->
                if (files.size > 40) files.take(files.size - 40).forEach { it.delete() }
            }
        }
        return f
    }
}
