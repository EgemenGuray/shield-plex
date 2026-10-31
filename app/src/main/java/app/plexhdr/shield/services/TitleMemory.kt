package app.plexhdr.shield.services

import android.content.Context
import app.plexhdr.shield.plex.PlexClient
import app.plexhdr.shield.plex.PlexItem
import org.json.JSONObject
import java.io.File

/**
 * What the Shield remembers per title, on top of what Plex stores: the resume point (kept even if the server didn't
 * get the last progress report, or for a Plex Home user without sync), the version, the audio track, the subtitle and
 * a downloaded online subtitle.
 */
class TitleMemory(context: Context, private val plex: PlexClient) {
    data class Record(
        var at: Long = 0,                 // last change, ms
        var positionMs: Long? = null,
        var positionAt: Long? = null,     // ms
        var mediaIndex: Int? = null,
        var audioId: Int? = null,
        var subtitleId: Int? = null,      // Plex stream id; -1 = subtitles off
        var onlineSubtitle: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().put("at", at).put("positionMs", positionMs).put("positionAt", positionAt)
            .put("mediaIndex", mediaIndex).put("audioId", audioId).put("subtitleId", subtitleId).put("onlineSubtitle", onlineSubtitle)

        companion object {
            fun from(o: JSONObject): Record {
                fun l(k: String) = if (o.has(k) && !o.isNull(k)) o.optLong(k) else null
                fun i(k: String) = if (o.has(k) && !o.isNull(k)) o.optInt(k) else null
                return Record(o.optLong("at"), l("positionMs"), l("positionAt"), i("mediaIndex"), i("audioId"), i("subtitleId"),
                    if (o.isNull("onlineSubtitle")) null else o.optString("onlineSubtitle").ifEmpty { null })
            }
        }
    }

    private val maxRecords = 400
    private val file = File(context.filesDir, "title-memory.json")
    private val data = HashMap<String, Record>()
    private var dirty = false

    init {
        runCatching {
            val o = JSONObject(file.readText())
            for (k in o.keys()) data[k] = Record.from(o.getJSONObject(k))
        }
    }

    private fun key(item: PlexItem) = "${plex.server?.machineId ?: ""}:${item.ratingKey ?: ""}"

    fun record(item: PlexItem): Record = data[key(item)]?.copy() ?: Record()

    fun update(item: PlexItem, change: (Record) -> Unit) {
        val r = record(item)
        change(r)
        r.at = System.currentTimeMillis()
        data[key(item)] = r
        if (data.size > maxRecords) data.minByOrNull { it.value.at }?.key?.let { data.remove(it) }
        dirty = true
    }

    /** Near the start or the end there's nothing to resume. */
    fun savePosition(item: PlexItem, timeMs: Long, durationMs: Long) = update(item) { r ->
        r.positionAt = System.currentTimeMillis()
        r.positionMs = if (timeMs < 15_000 || (durationMs > 0 && timeMs.toDouble() / durationMs > 0.92)) null else timeMs
    }

    /** Whichever is newer: the Shield's own record or the server's. */
    fun resumePositionMs(item: PlexItem): Long {
        val server = item.viewOffsetMs
        val r = record(item)
        val at = r.positionAt ?: return server
        val serverAt = (item.lastViewedAt ?: 0) * 1000
        return if (at > serverAt + 30_000) r.positionMs ?: 0 else server
    }

    fun flush() {
        if (!dirty) return
        val o = JSONObject()
        for ((k, v) in data) o.put(k, v.toJson())
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }
        dirty = false
    }
}
