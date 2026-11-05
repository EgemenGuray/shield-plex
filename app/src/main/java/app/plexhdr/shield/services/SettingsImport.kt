package app.plexhdr.shield.services

import android.content.Context
import app.plexhdr.shield.App
import app.plexhdr.shield.iptv.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Settings → "Import settings from address": fetches a JSON file (see README, "Importing settings") and applies it.
 * Every key is optional; playlists already present (same URL and username) are skipped.
 */
object SettingsImport {
    const val DEFAULT_URL = "http://192.168.0.151:8765/lumen.json"

    private val prefs get() = App.instance.getSharedPreferences("import", Context.MODE_PRIVATE)

    /** The address used last time, for the text entry. */
    var lastUrl: String
        get() = prefs.getString("url", null) ?: DEFAULT_URL
        set(v) { prefs.edit().putString("url", v).apply() }

    /** Fetches and applies the file; returns what was imported, one line each (label to detail). */
    suspend fun run(url: String): List<Pair<String, String>> {
        lastUrl = url
        val text = withContext(Dispatchers.IO) {
            App.plex.http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code} from $url")
                r.body?.string() ?: ""
            }
        }
        val o = runCatching { JSONObject(text) }.getOrElse { throw IOException("Not a JSON object: ${it.message}") }
        return apply(o)
    }

    private fun JSONObject.s(key: String): String? = if (has(key) && !isNull(key)) optString(key).trim() else null

    fun apply(o: JSONObject): List<Pair<String, String>> {
        val s = App.settings
        val out = ArrayList<Pair<String, String>>()
        val keys = ArrayList<String>()
        o.s("tmdbKey")?.let { s.tmdbKey = it; keys += "TMDB" }
        o.s("omdbKey")?.let { s.omdbKey = it; keys += "OMDb" }
        o.s("openSubtitlesKey")?.let { s.openSubtitlesKey = it; keys += "OpenSubtitles key" }
        o.s("openSubtitlesUser")?.let { s.openSubtitlesUser = it; keys += "OpenSubtitles user" }
        o.s("openSubtitlesPassword")?.let { s.openSubtitlesPassword = it; keys += "OpenSubtitles password" }
        if (keys.isNotEmpty()) { App.online.resetLogin(); out += "Keys" to keys.joinToString(", ") }

        o.s("audioLanguage")?.let { s.audioLanguage = it; out += "Audio language" to it.ifEmpty { "Automatic" } }
        o.s("subtitleLanguage")?.let { s.subtitleLanguage = it; out += "Subtitle language" to it.ifEmpty { "Automatic" } }
        o.s("subtitleMode")?.let { v ->
            val mode = Settings.SubtitleMode.values().firstOrNull { it.name.equals(v, true) || it.label.equals(v, true) }
            if (mode != null) { s.subtitleMode = mode; out += "Subtitles" to mode.label } else out += "Subtitles" to "unknown mode \"$v\", skipped"
        }

        o.optJSONArray("playlists")?.let { arr ->
            val store = App.iptv
            val added = ArrayList<String>()
            var skipped = 0
            var invalid = 0
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val url = p.s("url").orEmpty()
                if (url.isEmpty()) { invalid++; continue }
                val user = p.s("username").orEmpty()
                if (store.playlists.any { it.url.trim().equals(url, true) && it.username.trim() == user }) { skipped++; continue }
                val kind = if (p.s("kind").equals("Xtream", true)) Playlist.Kind.Xtream else Playlist.Kind.M3U
                val name = p.s("name").orEmpty().ifEmpty { android.net.Uri.parse(url).host ?: "Playlist" }
                store.savePlaylist(Playlist(
                    // An "id" keeps favourites exported from another device pointing at the same playlist.
                    id = p.s("id").orEmpty(), name = name, kind = kind, url = url, username = user,
                    password = p.s("password").orEmpty(), epgUrl = p.s("epgUrl").orEmpty(), userAgent = p.s("userAgent").orEmpty(),
                ))
                added += name
            }
            out += "Playlists" to buildList {
                add(if (added.isEmpty()) "none added" else "added ${added.joinToString(", ")}")
                if (skipped > 0) add("$skipped already present")
                if (invalid > 0) add("$invalid without a URL")
            }.joinToString("; ")
        }

        o.optJSONArray("favorites")?.let { arr ->
            val store = App.iptv
            var added = 0
            for (id in favoriteIds(arr)) if (!store.isFavorite(id)) { store.toggleFavorite(id); added++ }
            out += "Favourites" to "$added added"
        }

        if (out.isEmpty()) out += "Nothing imported" to "The file had none of the known keys."
        return out
    }

    /**
     * Favourites are Live TV ids: plain strings as Lumen stores them ("<playlistId>|<streamId>" and so on), or
     * {"playlist": "<playlist name or URL>", "id": "<rest of the id>"} to point at a playlist by name or URL.
     */
    private fun favoriteIds(arr: JSONArray): List<String> {
        val playlists = App.iptv.playlists
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            when (val v = arr.opt(i)) {
                is String -> v.trim().takeIf { it.isNotEmpty() }?.let { out += it }
                is JSONObject -> {
                    val rest = v.s("id")?.takeIf { it.isNotEmpty() } ?: continue
                    val ref = v.s("playlist").orEmpty()
                    val pl = playlists.firstOrNull { it.name.equals(ref, true) || it.url.equals(ref, true) || it.id == ref } ?: continue
                    out += "${pl.id}|$rest"
                }
            }
        }
        return out
    }
}
