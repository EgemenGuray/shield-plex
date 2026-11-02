package app.plexhdr.shield.iptv

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Downloads (films, episodes, catch-up programmes) and recordings (a live channel now, or a programme scheduled from the
 * guide). Streams are written straight to files in the app's Movies folder; HLS channels are recorded segment by segment.
 * Jobs run while Lumen is open.
 */
class Recorder(context: Context, private val store: IptvStore) {
    enum class Kind { Download, Recording }
    enum class State { Scheduled, Running, Done, Failed, Cancelled }

    data class Job(
        val id: String, val kind: Kind, val title: String, val subtitle: String?, val poster: String?, val url: String,
        val userAgent: String?, val file: String, val start: Long, val end: Long?, val channelId: String? = null,
        val state: State = State.Scheduled, val received: Long = 0, val expected: Long? = null, val error: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().put("id", id).put("kind", kind.name).put("title", title).put("subtitle", subtitle ?: "")
            .put("poster", poster ?: "").put("url", url).put("ua", userAgent ?: "").put("file", file).put("start", start).put("end", end ?: 0)
            .put("channelId", channelId ?: "").put("state", state.name).put("received", received).put("expected", expected ?: 0).put("error", error ?: "")

        companion object {
            fun fromJson(o: JSONObject): Job {
                fun s(k: String) = o.optString(k).ifEmpty { null }
                return Job(o.optString("id"), Kind.valueOf(o.optString("kind", "Download")), o.optString("title"), s("subtitle"), s("poster"),
                    o.optString("url"), s("ua"), o.optString("file"), o.optLong("start"), o.optLong("end").takeIf { it > 0 }, s("channelId"),
                    State.valueOf(o.optString("state", "Failed")), o.optLong("received"), o.optLong("expected").takeIf { it > 0 }, s("error"))
            }
        }
    }

    private val prefs = context.getSharedPreferences("iptv-jobs", Context.MODE_PRIVATE)
    val folder: File = (context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir).resolve("Lumen").apply { mkdirs() }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val running = HashMap<String, kotlinx.coroutines.Job>()

    var jobs by mutableStateOf(load()); private set

    init {
        // Anything that was running when Lumen closed has stopped.
        jobs = jobs.map { if (it.state == State.Running) it.copy(state = State.Failed, error = "Interrupted when Lumen closed.") else it }
        save()
        scope.launch { while (isActive) { tick(); delay(10_000) } }
    }

    fun list(kind: Kind) = jobs.filter { it.kind == kind }
    fun file(j: Job) = File(folder, j.file)

    private fun name(title: String, ext: String): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm", Locale.US).format(Date())
        return title.replace(Regex("[/\\\\:*?\"<>|]"), " ").take(80).trim() + " $stamp.$ext"
    }

    private fun extOf(url: String): String {
        val e = Regex("\\.([a-z0-9]+)$", RegexOption.IGNORE_CASE).find(url.substringBefore('?'))?.groupValues?.get(1)?.lowercase()
        return if (e in setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "webm")) e!! else "ts"
    }

    private fun add(j: Job): Job {
        jobs = listOf(j) + jobs
        save(); tick()
        return j
    }

    fun download(movie: Movie) = add(Job(UUID.randomUUID().toString(), Kind.Download, movie.name, movie.year?.toString(), movie.poster,
        movie.url, store.playlist(movie.playlistId)?.userAgent?.ifEmpty { null }, name(movie.name, extOf(movie.url)), System.currentTimeMillis(), null))

    fun download(s: Series, e: Episode): Job {
        val title = "${s.name} S${e.season} E${e.number}"
        return add(Job(UUID.randomUUID().toString(), Kind.Download, title, e.title, e.thumb ?: s.poster, e.url,
            store.playlist(s.playlistId)?.userAgent?.ifEmpty { null }, name(title, extOf(e.url)), System.currentTimeMillis(), null))
    }

    fun download(ch: Channel, p: Programme): Job? {
        val url = store.catchupUrl(ch, p) ?: return null
        val now = System.currentTimeMillis()
        return add(Job(UUID.randomUUID().toString(), Kind.Download, p.title, ch.name, ch.logo, url, ch.userAgent,
            name(ch.name + " " + p.title, "ts"), now, now + (p.end - p.start) + 600_000))
    }

    fun record(ch: Channel, p: Programme?, minutes: Int = 60): Job {
        val now = System.currentTimeMillis()
        val start = p?.let { maxOf(now, it.start - 60_000) } ?: now
        val end = p?.let { it.end + 120_000 } ?: (now + minutes * 60_000L)
        val f = SimpleDateFormat("HH:mm", Locale.getDefault())
        val title = p?.title ?: ch.name
        return add(Job(UUID.randomUUID().toString(), Kind.Recording, title, "${ch.name} · ${f.format(start)} – ${f.format(end)}", ch.logo,
            ch.url, ch.userAgent, name(ch.name + " " + title, "ts"), start, end, ch.id))
    }

    fun isRecording(ch: Channel, p: Programme) = jobs.any {
        it.kind == Kind.Recording && it.channelId == ch.id && (it.state == State.Scheduled || it.state == State.Running) &&
            it.start <= p.start + 60_000 && (it.end ?: 0) >= p.end
    }

    fun recordingOf(ch: Channel) = jobs.firstOrNull { it.kind == Kind.Recording && it.channelId == ch.id && it.state == State.Running }

    private fun update(id: String, f: (Job) -> Job) {
        jobs = jobs.map { if (it.id == id) f(it) else it }
    }

    private fun job(id: String) = jobs.firstOrNull { it.id == id }

    private fun tick() {
        val now = System.currentTimeMillis()
        jobs.forEach { j ->
            if (j.state == State.Scheduled && j.start <= now) {
                if (j.end != null && j.end <= now) { update(j.id) { it.copy(state = State.Failed, error = "Lumen wasn't open at the scheduled time.") }; save() }
                else begin(j, append = false)
            } else if (j.state == State.Running && j.end != null && j.end <= now) {
                stop(j.id, done = true)
            }
        }
    }

    private fun begin(j: Job, append: Boolean) {
        update(j.id) { it.copy(state = State.Running, error = null, received = if (append) it.received else 0) }
        save()
        running[j.id] = scope.launch {
            val err = withContext(Dispatchers.IO) {
                runCatching {
                    FileOutputStream(file(j), append).use { out ->
                        if (Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(j.url)) hls(j, out) else progressive(j, out)
                    }
                }.exceptionOrNull()
            }
            running.remove(j.id)
            finished(j.id, err)
        }
    }

    private suspend fun progressive(j: Job, out: FileOutputStream) {
        val b = Request.Builder().url(j.url)
        j.userAgent?.let { b.header("User-Agent", it) }
        store.http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) throw IptvException("The server answered ${r.code}.")
            val total = r.body?.contentLength()?.takeIf { it > 0 }
            update(j.id) { it.copy(expected = total) }
            val input = r.body?.byteStream() ?: return
            val buf = ByteArray(256 * 1024)
            var last = 0L
            var got = job(j.id)?.received ?: 0
            while (true) {
                if (!kotlin.coroutines.coroutineContext.isActive) return
                val end = job(j.id)?.end
                if (end != null && System.currentTimeMillis() >= end && j.kind == Kind.Recording) return
                val n = input.read(buf)
                if (n < 0) return
                out.write(buf, 0, n)
                got += n
                if (System.currentTimeMillis() - last > 1500) {
                    last = System.currentTimeMillis()
                    val g = got
                    withContext(Dispatchers.Main) { update(j.id) { it.copy(received = g) } }
                }
            }
        }
    }

    /** HLS: polls the playlist, picks the best variant, and appends each new segment. */
    private suspend fun hls(j: Job, out: FileOutputStream) {
        val seen = HashSet<String>()
        var media = j.url
        var got = job(j.id)?.received ?: 0
        while (kotlin.coroutines.coroutineContext.isActive) {
            val end = job(j.id)?.end
            if (end != null && System.currentTimeMillis() >= end) return
            val text = runCatching { store.get(media, j.userAgent) }.getOrNull()
            if (text == null) { delay(3000); continue }
            val lines = text.lines().map { it.trim() }
            if (lines.any { it.startsWith("#EXT-X-STREAM-INF") }) {
                var best: String? = null; var bw = -1L
                lines.forEachIndexed { i, l ->
                    if (!l.startsWith("#EXT-X-STREAM-INF")) return@forEachIndexed
                    val b = Regex("BANDWIDTH=(\\d+)").find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0
                    val next = lines.getOrNull(i + 1)
                    if (b >= bw && !next.isNullOrEmpty()) { bw = b; best = URL(URL(media), next).toString() }
                }
                val variant = best
                if (variant != null) { media = variant; continue }
            }
            val target = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }?.substringAfter(':')?.toDoubleOrNull() ?: 6.0
            for (l in lines) {
                if (l.isEmpty() || l.startsWith("#")) continue
                val u = URL(URL(media), l).toString()
                if (!seen.add(u)) continue
                val b = Request.Builder().url(u)
                j.userAgent?.let { b.header("User-Agent", it) }
                runCatching {
                    store.http.newCall(b.build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes()?.let { out.write(it); got += it.size }
                    }
                }
            }
            val g = got
            withContext(Dispatchers.Main) { update(j.id) { it.copy(received = g) }; save() }
            if (lines.contains("#EXT-X-ENDLIST")) return
            delay(maxOf(1000L, (target * 500).toLong()))
        }
    }

    private fun finished(id: String, err: Throwable?) {
        val j = job(id) ?: return
        if (j.state != State.Running) return
        if (j.kind == Kind.Recording && j.end != null && j.end > System.currentTimeMillis() + 30_000) {
            // A live recording that drops reconnects and keeps appending until its end time.
            scope.launch { delay(3000); if (job(id)?.state == State.Running) begin(job(id)!!, append = true) }
            return
        }
        update(id) {
            if (err != null && it.kind == Kind.Download) it.copy(state = State.Failed, error = err.message ?: err.toString())
            else it.copy(state = State.Done)
        }
        save()
    }

    fun stop(id: String, done: Boolean = false) {
        running.remove(id)?.cancel()
        update(id) {
            when {
                it.kind == Kind.Recording && (it.received > 0 || done) -> it.copy(state = State.Done, end = minOf(it.end ?: Long.MAX_VALUE, System.currentTimeMillis()))
                done -> it.copy(state = State.Done)
                else -> it.copy(state = State.Cancelled)
            }
        }
        save()
    }

    fun remove(id: String) {
        val j = job(id) ?: return
        running.remove(id)?.cancel()
        file(j).delete()
        jobs = jobs.filter { it.id != id }
        save()
    }

    fun retry(id: String) {
        val j = job(id) ?: return
        if (j.kind == Kind.Recording && j.end != null && j.end <= System.currentTimeMillis()) return
        update(id) { it.copy(state = State.Scheduled, start = minOf(it.start, System.currentTimeMillis()), error = null) }
        save(); tick()
    }

    private fun load(): List<Job> {
        val a = runCatching { JSONArray(prefs.getString("jobs", "[]")) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull { runCatching { Job.fromJson(it) }.getOrNull() }
    }

    private fun save() = prefs.edit().putString("jobs", JSONArray(jobs.map { it.toJson() }).toString()).apply()
}
