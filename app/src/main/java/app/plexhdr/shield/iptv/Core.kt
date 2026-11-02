package app.plexhdr.shield.iptv

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

/*
 * Live TV (IPTV) data: models and the parsers for M3U playlists, Xtream Codes answers and XMLTV guides, catch-up addresses
 * and quality tags. Plain Kotlin and org.json only, so it can be checked off the device.
 */

data class Playlist(
    val id: String, val name: String, val kind: Kind, val url: String,
    val username: String = "", val password: String = "", val epgUrl: String = "", val userAgent: String = "",
    val colorIndex: Int = 0, val enabled: Boolean = true, val lastRefresh: Long = 0,
) {
    enum class Kind { M3U, Xtream }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("kind", kind.name).put("url", url)
        .put("username", username).put("password", password).put("epgUrl", epgUrl).put("userAgent", userAgent)
        .put("colorIndex", colorIndex).put("enabled", enabled).put("lastRefresh", lastRefresh)

    companion object {
        fun fromJson(o: JSONObject) = Playlist(
            o.optString("id"), o.optString("name"), if (o.optString("kind") == "Xtream") Kind.Xtream else Kind.M3U, o.optString("url"),
            o.optString("username"), o.optString("password"), o.optString("epgUrl"), o.optString("userAgent"),
            o.optInt("colorIndex"), o.optBoolean("enabled", true), o.optLong("lastRefresh"),
        )
    }
}

data class Catchup(val mode: String, val days: Int, val source: String?)

data class Channel(
    val id: String, val playlistId: String, val name: String, val group: String, val logo: String?, val url: String,
    val epgId: String?, val number: Int?, val catchup: Catchup?, val userAgent: String?, val streamId: Int?,
)

data class Movie(
    val id: String, val playlistId: String, val name: String, val group: String, val poster: String?, val url: String,
    val year: Int?, val rating: Double?, val added: Long?, val streamId: Int?,
)

data class Episode(
    val id: String, val season: Int, val number: Int, val title: String, val url: String,
    val plot: String? = null, val thumb: String? = null, val durationSec: Int? = null,
)

data class Series(
    val id: String, val playlistId: String, val name: String, val group: String, val poster: String?,
    val rating: Double? = null, val year: Int? = null, val plot: String? = null, val genre: String? = null,
    val seriesId: Int? = null, val episodes: List<Episode>? = null,
)

data class Programme(
    val start: Long, val end: Long, val title: String,
    val desc: String? = null, val category: String? = null, val episode: String? = null,
)

data class Account(val status: String?, val expires: Long?, val maxConnections: Int?, val activeConnections: Int?)

data class Content(
    val channels: List<Channel> = emptyList(), val movies: List<Movie> = emptyList(), val series: List<Series> = emptyList(),
    val liveGroups: List<String> = emptyList(), val movieGroups: List<String> = emptyList(), val seriesGroups: List<String> = emptyList(),
    val epgUrls: List<String> = emptyList(), val serverTimeZone: String? = null, val account: Account? = null,
)

data class Details(
    val plot: String? = null, val cast: String? = null, val director: String? = null, val genre: String? = null,
    val durationSec: Int? = null, val rating: Double? = null, val backdrop: String? = null, val poster: String? = null,
    val episodes: List<Episode> = emptyList(),
)

class IptvException(message: String) : Exception(message)

/** Quality tags shown on channels and used in search. */
enum class QTag(val label: String) {
    UHD("4K"), FHD("FHD"), HD("HD"), SD("SD"), HDR("HDR"), Surround("5.1"), Stereo("Stereo"), HEVC("HEVC");

    val isVideo get() = this == UHD || this == FHD || this == HD || this == SD
    val isAudio get() = this == Surround || this == Stereo

    companion object {
        fun parse(text: String): List<QTag> {
            val up = " " + text.uppercase().replace(Regex("[|\\[\\]():\\-_]"), " ") + " "
            fun has(vararg ws: String) = ws.any { up.contains(" $it ") }
            val t = ArrayList<QTag>()
            when {
                has("4K", "UHD", "2160P", "2160", "8K") -> t += UHD
                has("FHD", "1080P", "1080", "1080I", "FULLHD", "FULL HD") -> t += FHD
                has("HD", "720P", "720", "HD+") -> t += HD
                has("SD", "576P", "480P", "LQ") -> t += SD
            }
            if (has("HDR", "HDR10", "HLG", "DV", "DOLBY VISION")) t += HDR
            if (has("5.1", "7.1", "DD5.1", "DD+", "AC3", "EAC3", "ATMOS", "DOLBY", "SURROUND")) t += Surround
            if (has("STEREO", "2.0")) t += Stereo
            if (has("HEVC", "H265", "H.265", "X265")) t += HEVC
            return t
        }

        /** Tags from what the player measured: picture height, audio channels, HDR. */
        fun measured(height: Int, channels: Int?, hdr: Boolean): List<QTag> {
            val t = ArrayList<QTag>()
            when {
                height >= 1700 -> t += UHD
                height >= 1000 -> t += FHD
                height >= 700 -> t += HD
                height > 0 -> t += SD
            }
            if (hdr) t += HDR
            if (channels != null && channels > 0) t += if (channels > 2) Surround else Stereo
            return t
        }

        /** Measured tags replace the name's video and audio tags; the rest stay. */
        fun merge(named: List<QTag>, measured: List<QTag>): List<QTag> {
            if (measured.isEmpty()) return named
            val out = named.filter { n -> !(n.isVideo && measured.any { it.isVideo }) && !(n.isAudio && measured.any { it.isAudio }) }.toMutableList()
            measured.forEach { if (it !in out) out += it }
            return out
        }
    }
}

object Text {
    private val strip = listOf(" hd", " fhd", " uhd", " 4k", " sd", " hevc", "(hd)", "[hd]")

    /** Name key for matching channels to the guide: lower case, letters and digits only, no quality words. */
    fun key(name: String?): String {
        var s = " " + (name ?: "").lowercase() + " "
        strip.forEach { s = s.replace(it, "") }
        return s.filter { it.isLetterOrDigit() }
    }

    fun hash(s: String): String {
        var h = 0
        for (ch in s) h = h * 31 + ch.code
        return (h.toLong() and 0xffffffffL).toString(36)
    }

    data class TitleYear(val title: String, val year: Int?)

    fun splitYear(name: String): TitleYear {
        val m = Regex("^(.*?)[\\s\\-]*[(\\[]?((?:19|20)\\d{2})[)\\]]?\\s*$").find(name) ?: return TitleYear(name, null)
        val t = m.groupValues[1].trim()
        return if (t.isEmpty()) TitleYear(name, null) else TitleYear(t, m.groupValues[2].toInt())
    }

    data class EpisodeInfo(val show: String, val season: Int, val episode: Int, val title: String)

    private val epRes = listOf(
        Regex("^(.*?)[\\s._\\-]*[Ss](\\d{1,2})[\\s._\\-]*[Ee](\\d{1,3})(.*)$"),
        Regex("^(.*?)[\\s._\\-]+(\\d{1,2})x(\\d{1,3})(.*)$"),
    )
    private fun tidy(s: String) = s.replace('.', ' ').trim(' ', '-', '_', '|', '\t')

    fun episodeInfo(name: String): EpisodeInfo? {
        for (re in epRes) {
            val m = re.find(name) ?: continue
            val show = tidy(m.groupValues[1])
            if (show.isEmpty()) continue
            val ep = m.groupValues[3].toInt()
            val rest = tidy(m.groupValues[4])
            return EpisodeInfo(show, m.groupValues[2].toInt(), ep, rest.ifEmpty { "Episode $ep" })
        }
        return null
    }

    /** Lower-case title without country prefixes, brackets and years, for matching TMDB trends to a provider's names. */
    fun cleanTitle(s: String): String = key(
        s.replace(Regex("^\\s*[|\\[]?[A-Z]{2,3}[|\\]]?\\s*[-:|]\\s*"), " ").replace(Regex("^\\s*\\|[^|]+\\|\\s*"), " ")
            .replace(Regex("[(\\[][^)\\]]*[)\\]]"), " ").replace(Regex("\\b(19|20)\\d{2}\\b"), " ")
    )

    fun decodeBody(bytes: ByteArray): String {
        val raw = if (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
            GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        val utf = Charsets.UTF_8.newDecoder()
        return runCatching { utf.decode(java.nio.ByteBuffer.wrap(raw)).toString() }.getOrElse { String(raw, Charsets.ISO_8859_1) }
    }
}

/* ---------------- M3U ---------------- */

object M3U {
    private val attrRe = Regex("([\\w\\-]+)=(\"([^\"]*)\"|(\\S+))")

    fun attrs(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        attrRe.findAll(s).forEach { m -> out[m.groupValues[1].lowercase()] = if (m.groups[3] != null) m.groupValues[3] else m.groupValues[4] }
        return out
    }

    /** Splits `-1 tvg-id="a,b" ...,Name` at the first comma outside quotes. */
    fun splitInfo(s: String): Pair<String, String> {
        var q = false
        for (i in s.indices) {
            if (s[i] == '"') q = !q else if (s[i] == ',' && !q) return s.substring(0, i) to s.substring(i + 1)
        }
        return s to ""
    }

    enum class Kind { Live, Movie, Series }

    fun classify(url: String, name: String, type: String): Kind {
        when (type) {
            "movie", "movies", "vod" -> return Kind.Movie
            "series", "tvshow" -> return Kind.Series
            "live", "stream" -> return Kind.Live
        }
        val lower = url.lowercase()
        if ("/series/" in lower) return Kind.Series
        if ("/movie/" in lower || "/movies/" in lower) return Kind.Movie
        val ext = Regex("\\.([a-z0-9]+)$").find(lower.substringBefore('?'))?.groupValues?.get(1)
        if (ext in setOf("mp4", "mkv", "avi", "mov", "m4v", "wmv", "webm")) return if (Text.episodeInfo(name) != null) Kind.Series else Kind.Movie
        return Kind.Live
    }

    private fun int(v: String?) = v?.trim()?.toIntOrNull()

    fun parse(text: String, pl: Playlist): Content {
        val pid = pl.id
        val channels = ArrayList<Channel>()
        val movies = ArrayList<Movie>()
        val series = ArrayList<Series>()
        val seriesEps = LinkedHashMap<String, MutableList<Episode>>()
        val seriesIndex = HashMap<String, Int>()
        val lg = LinkedHashSet<String>(); val mg = LinkedHashSet<String>(); val sg = LinkedHashSet<String>()
        val epg = ArrayList<String>()
        var header: Catchup? = null
        var info: Pair<Map<String, String>, String>? = null
        var grp: String? = null
        var ua: String? = null
        val seen = HashSet<String>()

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U")) {
                val h = attrs(line.substring(7))
                listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { k -> h[k]?.split(',')?.forEach { u -> if (u.isNotBlank()) epg += u.trim() } }
                val mode = h["catchup"] ?: h["catchup-type"]
                if (mode != null) header = Catchup(mode, int(h["catchup-days"]) ?: 7, h["catchup-source"])
                continue
            }
            if (line.startsWith("#EXTINF")) {
                val (a, n) = splitInfo(line.substring(line.indexOf(':') + 1))
                info = attrs(a) to n.trim()
                continue
            }
            if (line.startsWith("#EXTGRP:")) { grp = line.substring(8).trim(); continue }
            if (line.startsWith("#EXTVLCOPT:")) {
                val opt = line.substring(11)
                if (opt.startsWith("http-user-agent=", ignoreCase = true)) ua = opt.substring(16)
                continue
            }
            if (line.startsWith("#")) continue

            val a = info?.first ?: emptyMap()
            val url = line
            val name = info?.second?.ifEmpty { null } ?: a["tvg-name"] ?: url
            val group = a["group-title"]?.ifEmpty { null } ?: grp ?: "Other"
            val logo = a["tvg-logo"]?.ifEmpty { null }
            when (classify(url, name, (a["tvg-type"] ?: a["type"] ?: "").lowercase())) {
                Kind.Live -> {
                    var id = "$pid|${a["tvg-id"] ?: ""}|$name"
                    if (!seen.add(id)) id += "|" + Text.hash(url)
                    val mode = a["catchup"] ?: a["catchup-type"]
                    val days = int(a["timeshift"] ?: a["tvg-rec"] ?: a["catchup-days"]) ?: 0
                    val source = a["catchup-source"] ?: header?.source
                    var cu = when {
                        mode != null -> Catchup(mode, int(a["catchup-days"]) ?: header?.days ?: 7, source)
                        days > 0 -> Catchup(header?.mode ?: "default", days, source)
                        else -> header
                    }
                    if (cu != null && cu.source == null && cu.mode == "default" && ("/live/" in url || url.split('/').size >= 6)) cu = cu.copy(mode = "xc")
                    channels += Channel(
                        id, pid, name, group, logo, url, a["tvg-id"]?.ifEmpty { null },
                        int(a["tvg-chno"] ?: a["channel-number"] ?: a["tvg-num"]), cu,
                        ua ?: a["user-agent"] ?: a["http-user-agent"], null,
                    )
                    lg += group
                }
                Kind.Movie -> {
                    val ty = Text.splitYear(name)
                    movies += Movie("$pid|m|$name|${Text.hash(url)}", pid, ty.title, group, logo, url, ty.year, null, null, null)
                    mg += group
                }
                Kind.Series -> {
                    val ep = Text.episodeInfo(name)
                    val show = ep?.show ?: name
                    val sk = group + "|" + show.lowercase()
                    val e = Episode("$pid|e|${Text.hash(url)}", ep?.season ?: 1, ep?.episode ?: 1, ep?.title ?: name, url, thumb = logo)
                    val list = seriesEps.getOrPut(sk) { ArrayList() }
                    list += e
                    if (sk !in seriesIndex) {
                        seriesIndex[sk] = series.size
                        series += Series("$pid|s|$sk", pid, show, group, logo)
                    }
                    sg += group
                }
            }
            info = null; grp = null; ua = null
        }
        val withEps = series.map { s ->
            val sk = s.id.removePrefix("$pid|s|")
            s.copy(episodes = seriesEps[sk]?.sortedWith(compareBy({ it.season }, { it.number })))
        }
        return Content(channels, movies, withEps, lg.toList(), mg.toList(), sg.toList(), epg)
    }
}

/* ---------------- Xtream Codes ---------------- */

/** Lenient readers: Xtream panels send numbers as numbers or strings, and flags as 0/1, "1" or booleans. */
internal fun JSONObject.s(k: String): String? {
    if (!has(k) || isNull(k)) return null
    val v = opt(k)
    val s = when (v) { is String -> v; is Number, is Boolean -> v.toString(); else -> null }
    return if (s.isNullOrBlank()) null else s
}
internal fun JSONObject.i(k: String): Int? = when (val v = opt(k)) { is Number -> v.toInt(); is String -> v.trim().toIntOrNull() ?: v.trim().toDoubleOrNull()?.toInt(); else -> null }
internal fun JSONObject.d(k: String): Double? = when (val v = opt(k)) { is Number -> v.toDouble(); is String -> v.trim().toDoubleOrNull(); else -> null }
internal fun JSONObject.b(k: String): Boolean = when (val v = opt(k)) { is Boolean -> v; is Number -> v.toInt() != 0; is String -> v == "1" || v.equals("true", true); else -> false }
internal fun yearOf(v: String?): Int? = v?.take(4)?.toIntOrNull()?.takeIf { it > 1880 }

/** A JSON answer that may be an array, an object of objects, or junk. */
internal fun jsonList(text: String?): List<JSONObject> {
    val t = text?.trim() ?: return emptyList()
    return runCatching {
        when {
            t.startsWith("[") -> JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
            t.startsWith("{") -> JSONObject(t).let { o -> o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.toList() }
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}

class Xtream(private val pl: Playlist) {
    val base: String = normalize(pl.url)
    private val u = enc(pl.username)
    private val p = enc(pl.password)

    fun api(action: String? = null, extra: Map<String, String> = emptyMap()): String {
        val q = LinkedHashMap<String, String>()
        q["username"] = pl.username; q["password"] = pl.password
        if (action != null) q["action"] = action
        q.putAll(extra)
        return "$base/player_api.php?" + q.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }
    }

    val epg: String get() = "$base/xmltv.php?username=$u&password=$p"
    fun live(id: Int, ext: String = "ts") = "$base/live/$u/$p/$id.$ext"
    fun movie(id: Int, ext: String) = "$base/movie/$u/$p/$id.$ext"
    fun episode(id: String, ext: String) = "$base/series/$u/$p/$id.$ext"
    fun timeshift(id: Int, startMs: Long, minutes: Int, tz: String?) = "$base/timeshift/$u/$p/$minutes/${tsDate(startMs, tz)}/$id.ts"

    /** Builds the library from the answers to: account, live/vod/series categories and lists (in that order). */
    fun parse(account: String, liveCats: String?, live: String?, vodCats: String?, vod: String?, seriesCats: String?, seriesList: String?): Content {
        val root = runCatching { JSONObject(account) }.getOrNull() ?: throw IptvException("This doesn't look like an Xtream Codes server.")
        val user = root.optJSONObject("user_info") ?: throw IptvException("This doesn't look like an Xtream Codes server.")
        if (user.i("auth") == 0) throw IptvException("The username or password was refused.")
        val acct = Account(user.s("status"), user.i("exp_date")?.let { it * 1000L }, user.i("max_connections"), user.i("active_cons"))
        val tz = root.optJSONObject("server_info")?.s("timezone")
        val pid = pl.id

        fun cats(t: String?): Pair<Map<String, String>, List<String>> {
            val m = HashMap<String, String>(); val order = ArrayList<String>()
            jsonList(t).forEach { x -> val id = x.s("category_id"); val n = x.s("category_name"); if (id != null && n != null) { m[id] = n; order += n } }
            return m to order
        }
        fun groupsUsed(order: List<String>, used: Set<String>) = (order + "Other").distinct().filter { it in used }

        val (lc, lo) = cats(liveCats)
        val usedL = HashSet<String>()
        val channels = jsonList(live).mapNotNull { s ->
            val id = s.i("stream_id") ?: return@mapNotNull null
            val g = lc[s.s("category_id")] ?: "Other"
            usedL += g
            val days = s.i("tv_archive_duration") ?: 0
            Channel(
                "$pid|$id", pid, s.s("name") ?: "Channel $id", g, s.s("stream_icon"), live(id), s.s("epg_channel_id"), s.i("num"),
                if (s.b("tv_archive") && days > 0) Catchup("xc", days, null) else null, pl.userAgent.ifEmpty { null }, id,
            )
        }
        val (vc, vo) = cats(vodCats)
        val usedV = HashSet<String>()
        val movies = jsonList(vod).mapNotNull { s ->
            val id = s.i("stream_id") ?: return@mapNotNull null
            val g = vc[s.s("category_id")] ?: "Other"
            usedV += g
            val ty = Text.splitYear(s.s("name") ?: "Film $id")
            Movie(
                "$pid|m|$id", pid, ty.title, g, s.s("stream_icon"), movie(id, s.s("container_extension") ?: "mp4"),
                ty.year ?: yearOf(s.s("year")) ?: yearOf(s.s("releasedate")), s.d("rating")?.takeIf { it > 0 }, s.i("added")?.let { it * 1000L }, id,
            )
        }
        val (sc, so) = cats(seriesCats)
        val usedS = HashSet<String>()
        val series = jsonList(seriesList).mapNotNull { s ->
            val id = s.i("series_id") ?: return@mapNotNull null
            val g = sc[s.s("category_id")] ?: "Other"
            usedS += g
            Series(
                "$pid|s|$id", pid, s.s("name") ?: "Series $id", g, s.s("cover"), s.d("rating")?.takeIf { it > 0 },
                yearOf(s.s("releaseDate")) ?: yearOf(s.s("release_date")), s.s("plot"), s.s("genre"), id,
            )
        }
        return Content(channels, movies, series, groupsUsed(lo, usedL), groupsUsed(vo, usedV), groupsUsed(so, usedS), listOf(epg), tz, acct)
    }

    fun parseDetails(text: String, seriesId: Int?): Details {
        val root = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
        val info = root.optJSONObject("info") ?: JSONObject()
        val backdrop = info.optJSONArray("backdrop_path")?.optString(0)?.ifEmpty { null } ?: info.s("backdrop_path")
        val eps = ArrayList<Episode>()
        if (seriesId != null) {
            val src = root.opt("episodes")
            val raw = ArrayList<JSONObject>()
            when (src) {
                is JSONArray -> for (i in 0 until src.length()) {
                    when (val x = src.opt(i)) { is JSONArray -> for (j in 0 until x.length()) x.optJSONObject(j)?.let { raw += it }; is JSONObject -> raw += x }
                }
                is JSONObject -> src.keys().forEach { k -> src.optJSONArray(k)?.let { a -> for (j in 0 until a.length()) a.optJSONObject(j)?.let { raw += it } } }
            }
            raw.forEach { e ->
                val ei = e.optJSONObject("info") ?: JSONObject()
                val eid = e.s("id") ?: return@forEach
                eps += Episode(
                    "${pl.id}|x|$seriesId|$eid", e.i("season") ?: 1, e.i("episode_num") ?: 0, e.s("title") ?: "Episode",
                    episode(eid, e.s("container_extension") ?: "mp4"), ei.s("plot"), ei.s("movie_image") ?: ei.s("cover_big"), ei.i("duration_secs"),
                )
            }
            eps.sortWith(compareBy({ it.season }, { it.number }))
        }
        return Details(
            info.s("plot") ?: info.s("description"), info.s("cast") ?: info.s("actors"), info.s("director"), info.s("genre"),
            info.i("duration_secs") ?: info.i("episode_run_time")?.let { it * 60 }, info.d("rating")?.takeIf { it > 0 }, backdrop,
            info.s("movie_image") ?: info.s("cover_big") ?: info.s("cover"), eps,
        )
    }

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        fun normalize(raw: String): String {
            var s = raw.trim()
            if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(s)) s = "http://$s"
            s = s.substringBefore('?')
            for (x in listOf("/player_api.php", "/get.php", "/xmltv.php", "/")) if (s.endsWith(x)) s = s.dropLast(x.length)
            return s.trimEnd('/')
        }

        /** "YYYY-MM-DD:HH-MM" in the server's time zone (UTC when it doesn't say). */
        fun tsDate(ms: Long, tz: String?): String {
            val f = SimpleDateFormat("yyyy-MM-dd:HH-mm", Locale.US)
            f.timeZone = tz?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getTimeZone("UTC")
            return f.format(ms)
        }
    }
}

/* ---------------- XMLTV ---------------- */

class Guide(
    /** Programmes by guide channel id (lower case), sorted by start. */
    val programmes: Map<String, List<Programme>>,
    /** Guide channel id by name key, for channels whose guide id doesn't match. */
    val names: Map<String, String>,
    val updated: Long,
) {
    fun toTsv(): String {
        val sb = StringBuilder()
        fun c(s: String?) = (s ?: "").replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
        names.forEach { (k, v) -> sb.append("N\t").append(c(k)).append('\t').append(c(v)).append('\n') }
        programmes.forEach { (ch, list) ->
            list.forEach { p ->
                sb.append("P\t").append(c(ch)).append('\t').append(p.start).append('\t').append(p.end).append('\t').append(c(p.title)).append('\t')
                    .append(c(p.desc)).append('\t').append(c(p.category)).append('\t').append(c(p.episode)).append('\n')
            }
        }
        return sb.toString()
    }

    companion object {
        val Empty = Guide(emptyMap(), emptyMap(), 0)

        fun fromTsv(text: String, updated: Long): Guide {
            val progs = HashMap<String, MutableList<Programme>>()
            val names = HashMap<String, String>()
            text.lineSequence().forEach { l ->
                val f = l.split('\t')
                if (f[0] == "N" && f.size >= 3) names[f[1]] = f[2]
                else if (f[0] == "P" && f.size >= 8) {
                    val s = f[2].toLongOrNull() ?: return@forEach
                    val e = f[3].toLongOrNull() ?: return@forEach
                    progs.getOrPut(f[1]) { ArrayList() } += Programme(s, e, f[4], f[5].ifEmpty { null }, f[6].ifEmpty { null }, f[7].ifEmpty { null })
                }
            }
            return Guide(progs, names, updated)
        }
    }
}

object XMLTV {
    private val dateRe = Regex("^(\\d{4})(\\d{2})(\\d{2})(\\d{2})(\\d{2})(\\d{2})?\\s*([+\\-]\\d{4})?")

    fun date(s: String?): Long? {
        val m = dateRe.find(s?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].ifEmpty { "0" }.toInt())
        var t = cal.timeInMillis
        if (g[7].isNotEmpty()) {
            val sign = if (g[7][0] == '-') -1 else 1
            t -= sign * (g[7].substring(1, 3).toInt() * 60 + g[7].substring(3, 5).toInt()) * 60_000L
        }
        return t
    }

    private val entRe = Regex("&(amp|lt|gt|quot|apos|#\\d+|#x[0-9a-fA-F]+);")
    fun decode(s: String): String = entRe.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
            else -> if (e[1] == 'x' || e[1] == 'X') e.substring(2).toInt(16).toChar().toString() else e.substring(1).toInt().toChar().toString()
        }
    }

    private val chRe = Regex("<channel\\b[^>]*\\bid=\"([^\"]*)\"[^>]*>([\\s\\S]*?)</channel>")
    private val dnRe = Regex("<display-name[^>]*>([\\s\\S]*?)</display-name>")
    private val pRe = Regex("<programme\\b([^>]*)>([\\s\\S]*?)</programme>")
    private val cdata = Regex("<!\\[CDATA\\[([\\s\\S]*?)]]>")
    private val tagRes = HashMap<String, Regex>()

    private fun tag(body: String, name: String): String? {
        val re = tagRes.getOrPut(name) { Regex("<$name\\b[^>]*>([\\s\\S]*?)</$name>") }
        return re.find(body)?.let { decode(cdata.replace(it.groupValues[1], "$1")).trim().ifEmpty { null } }
    }

    /** Keeps only the wanted channels (by guide id or name key) and programmes overlapping [from, to). */
    fun parse(text: String, wantedIds: Set<String>, wantedNames: Set<String>, from: Long, to: Long): Guide {
        val progs = HashMap<String, MutableList<Programme>>()
        val names = HashMap<String, String>()
        val kept = HashSet<String>()
        chRe.findAll(text).forEach { m ->
            val id = decode(m.groupValues[1]).lowercase()
            var keep = id in wantedIds
            dnRe.findAll(m.groupValues[2]).forEach { d ->
                val k = Text.key(decode(d.groupValues[1]))
                if (k in wantedNames) keep = true
                names.putIfAbsent(k, id)
            }
            if (keep) kept += id
        }
        pRe.findAll(text).forEach { m ->
            val a = m.groupValues[1]
            val ch = Regex("channel=\"([^\"]*)\"").find(a)?.groupValues?.get(1)?.let { decode(it).lowercase() } ?: return@forEach
            if (ch !in kept && ch !in wantedIds) return@forEach
            val start = date(Regex("start=\"([^\"]*)\"").find(a)?.groupValues?.get(1)) ?: return@forEach
            val stop = date(Regex("stop=\"([^\"]*)\"").find(a)?.groupValues?.get(1)) ?: (start + 3_600_000)
            if (stop <= from || start >= to) return@forEach
            val body = m.groupValues[2]
            var sub = tag(body, "sub-title")
            Regex("<episode-num[^>]*system=\"xmltv_ns\"[^>]*>([^<]*)<").find(body)?.let { en ->
                val parts = en.groupValues[1].split('.')
                val sn = parts.getOrNull(0)?.trim()?.toIntOrNull()
                val ep = parts.getOrNull(1)?.trim()?.toIntOrNull()
                if (sn != null && ep != null) sub = "S${sn + 1} E${ep + 1}" + (sub?.let { " · $it" } ?: "")
            }
            progs.getOrPut(ch) { ArrayList() } += Programme(start, stop, tag(body, "title") ?: "", tag(body, "desc"), tag(body, "category"), sub)
        }
        progs.values.forEach { it.sortBy { p -> p.start } }
        return Guide(progs, names, System.currentTimeMillis())
    }
}

/* ---------------- catch-up ---------------- */

object CatchupUrl {
    /** Fills a catch-up template: {utc} {start} {end} {duration} {offset} {Y} {m} {d} {H} {M} {S}, ${...} and {duration:60}. */
    fun fill(tpl: String, start: Long, end: Long, now: Long): String {
        val s = start / 1000; val e = end / 1000; val n = now / 1000
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = start }
        fun two(x: Int) = x.toString().padStart(2, '0')
        val v = mapOf(
            "utc" to s, "start" to s, "timestamp" to n, "utcend" to e, "end" to e, "lutc" to n, "now" to n,
            "duration" to e - s, "offset" to n - s,
            "Y" to c.get(Calendar.YEAR), "m" to two(c.get(Calendar.MONTH) + 1), "d" to two(c.get(Calendar.DAY_OF_MONTH)),
            "H" to two(c.get(Calendar.HOUR_OF_DAY)), "M" to two(c.get(Calendar.MINUTE)), "S" to two(c.get(Calendar.SECOND)),
        )
        val out = Regex("\\$?\\{(duration|offset):(\\d+)\\}").replace(tpl) { m ->
            val div = m.groupValues[2].toLong().coerceAtLeast(1)
            ((if (m.groupValues[1] == "duration") e - s else n - s) / div).toString()
        }
        return Regex("\\$?\\{(\\w+)\\}").replace(out) { m -> v[m.groupValues[1]]?.toString() ?: m.value }
    }

    fun url(ch: Channel, p: Programme, pl: Playlist?, serverTz: String?, now: Long = System.currentTimeMillis()): String? {
        val c = ch.catchup ?: return null
        val end = minOf(p.end, now)
        val mode = c.mode.lowercase()
        val sep = if ('?' in ch.url) "&" else "?"
        if (mode == "xc" || mode == "xtream") {
            val minutes = maxOf(1, ((p.end - p.start + 59_999) / 60_000).toInt())
            if (pl != null && pl.kind == Playlist.Kind.Xtream && ch.streamId != null) return Xtream(pl).timeshift(ch.streamId, p.start, minutes, serverTz)
            val m = Regex("^(https?://[^/]+)/(?:live/)?([^/]+)/([^/]+)/(\\d+)(\\.\\w+)?$").find(ch.url.substringBefore('?')) ?: return null
            val g = m.groupValues
            return "${g[1]}/timeshift/${g[2]}/${g[3]}/$minutes/${Xtream.tsDate(p.start, serverTz)}/${g[4]}.ts"
        }
        if (mode == "append") return c.source?.let { ch.url + fill(it, p.start, end, now) }
        if (mode == "shift" || mode == "timeshift") return ch.url + sep + "utc=${p.start / 1000}&lutc=${now / 1000}"
        if (mode.startsWith("flussonic") || mode == "fs") {
            val st = p.start / 1000; val du = (end - p.start) / 1000
            val hls = Regex("/(index|video|mono)[^/]*\\.m3u8")
            if (hls.containsMatchIn(ch.url)) return hls.replace(ch.url, "/index-$st-$du.m3u8")
            val ts = Regex("/mpegts(\\?|$)")
            if (ts.containsMatchIn(ch.url)) return ts.replace(ch.url) { "/timeshift_abs-$st.ts" + it.groupValues[1] }
            return null
        }
        if (c.source != null) {
            val f = fill(c.source, p.start, end, now)
            return if (Regex("^https?:", RegexOption.IGNORE_CASE).containsMatchIn(f)) f else ch.url + f
        }
        return ch.url + sep + "utc=${p.start / 1000}&lutc=${now / 1000}"
    }

    fun available(ch: Channel, p: Programme, now: Long = System.currentTimeMillis()): Boolean {
        val c = ch.catchup ?: return false
        return c.days > 0 && p.start < now && p.start > now - c.days * 86_400_000L
    }
}
