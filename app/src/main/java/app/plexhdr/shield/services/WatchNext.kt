package app.plexhdr.shield.services

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tvprovider.media.tv.WatchNextProgram
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publishes Plex's On Deck (Continue Watching) to the Android TV home screen's "Play Next" row. Each entry opens
 * Lumen on that item (lumen://item/<ratingKey>). Entries that left On Deck are removed; an entry the user removed from
 * the row is left alone until it leaves On Deck.
 */
object WatchNext {
    private const val TAG = "LumenWatchNext"

    // TvContractCompat constants (declared on package-private column interfaces, so spelled out here).
    private const val TYPE_MOVIE = 0                 // PreviewProgramColumns.TYPE_MOVIE
    private const val TYPE_TV_EPISODE = 3            // PreviewProgramColumns.TYPE_TV_EPISODE
    private const val ASPECT_RATIO_MOVIE_POSTER = 5  // PreviewProgramColumns.ASPECT_RATIO_MOVIE_POSTER (2:3)
    private const val WATCH_NEXT_TYPE_CONTINUE = 0   // WatchNextPrograms.WATCH_NEXT_TYPE_CONTINUE
    private const val WATCH_NEXT_TYPE_NEXT = 1       // WatchNextPrograms.WATCH_NEXT_TYPE_NEXT

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var pending: Job? = null

    /** Syncs after [delayMs]; a newer request replaces one that hasn't started yet. */
    fun syncSoon(delayMs: Long = 0) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            lock.withLock { runCatching { sync(App.instance) }.onFailure { Log.w(TAG, "sync failed: ${it.message}") } }
        }
    }

    private fun supported(context: Context) =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

    private suspend fun sync(context: Context) {
        if (!supported(context)) return
        val plex = App.plex
        if (plex.token == null || plex.server == null) { ours(context).forEach { delete(context, it.id) }; return }
        // Throws when the server can't be reached: then nothing is removed.
        val deck = plex.onDeck().filter { it.isPlayable && it.ratingKey != null }
        val keys = deck.mapNotNull { it.ratingKey }.toSet()
        val existing = ours(context)
        existing.filter { it.internalProviderId !in keys }.forEach { delete(context, it.id) }
        val byKey = existing.filter { it.internalProviderId in keys }.associateBy { it.internalProviderId }
        val now = System.currentTimeMillis()
        deck.forEachIndexed { i, item ->
            val rk = item.ratingKey ?: return@forEachIndexed
            val old = byKey[rk]
            if (old != null && !old.isBrowsable) return@forEachIndexed   // the user removed it from Play Next
            // Keep On Deck's order: most recently watched first.
            val engaged = item.lastViewedAt?.let { it * 1000 } ?: (now - i * 60_000L)
            val values = program(item, engaged).toContentValues()
            if (old == null) context.contentResolver.insert(TvContractCompat.WatchNextPrograms.CONTENT_URI, values)
            else context.contentResolver.update(TvContractCompat.buildWatchNextProgramUri(old.id), values, null, null)
        }
    }

    /** This app's Play Next entries (the provider only returns the caller's own rows). */
    private fun ours(context: Context): List<WatchNextProgram> {
        val out = ArrayList<WatchNextProgram>()
        context.contentResolver.query(TvContractCompat.WatchNextPrograms.CONTENT_URI, WatchNextProgram.PROJECTION, null, null, null)?.use { c ->
            while (c.moveToNext()) out.add(WatchNextProgram.fromCursor(c))
        }
        return out.filter { !it.internalProviderId.isNullOrEmpty() }
    }

    private fun delete(context: Context, id: Long) {
        runCatching { context.contentResolver.delete(TvContractCompat.buildWatchNextProgramUri(id), null, null) }
    }

    private fun program(item: PlexItem, engagedMs: Long): WatchNextProgram {
        val rk = item.ratingKey ?: ""
        val b = WatchNextProgram.Builder()
        val episode = item.type == "episode"
        b.setType(if (episode) TYPE_TV_EPISODE else TYPE_MOVIE)
        b.setWatchNextType(if (item.viewOffsetMs > 0 || !episode) WATCH_NEXT_TYPE_CONTINUE else WATCH_NEXT_TYPE_NEXT)
        b.setLastEngagementTimeUtcMillis(engagedMs)
        b.setTitle(item.displayTitle)
        if (episode) {
            b.setEpisodeTitle(item.title)
            item.parentIndex?.let { b.setSeasonNumber(it) }
            item.index?.let { b.setEpisodeNumber(it) }
        }
        item.summary?.let { b.setDescription(it) }
        App.plex.imageUrl(item.posterPath, 400, 600)?.let { b.setPosterArtUri(Uri.parse(it)) }
        b.setPosterArtAspectRatio(ASPECT_RATIO_MOVIE_POSTER)
        item.durationMs?.takeIf { it > 0 }?.let { b.setDurationMillis(it.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        if (item.viewOffsetMs > 0) b.setLastPlaybackPositionMillis(item.viewOffsetMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        b.setIntentUri(Uri.parse("lumen://item/$rk"))
        b.setInternalProviderId(rk)
        return b.build()
    }
}
