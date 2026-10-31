package app.plexhdr.shield.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import app.plexhdr.shield.services.Online

/** TMDB artwork for an item (null until loaded, or without a TMDB key). */
@Composable
fun rememberArtwork(item: PlexItem?): State<Online.Artwork?> = produceState<Online.Artwork?>(null, item?.id) {
    value = item?.let { runCatching { App.online.artwork(it) }.getOrNull() }
}

@Composable
fun rememberRatings(item: PlexItem?): State<Online.Ratings?> = produceState<Online.Ratings?>(null, item?.id) {
    value = if (item == null || !App.settings.showRatings) null
    else {
        // Plex's own ratings show straight away; the online ones fill in after.
        val target = if (item.type == "episode") item else item
        value = App.online.plexRatings(target)
        runCatching { App.online.ratings(target) }.getOrNull() ?: value
    }
}

/** Backdrop: Plex art, else TMDB's. */
fun backdropUrl(item: PlexItem?, art: Online.Artwork?): String? =
    item?.backdropPath?.let { plexImage(it, 1920, 1080) } ?: art?.backdrop

/** Title logo: Plex's clear logo, else TMDB's. */
fun logoUrl(item: PlexItem?, art: Online.Artwork?): String? =
    item?.clearLogo?.let { plexImage(it, 800, 300) } ?: art?.logo

fun metaLine(item: PlexItem): String = listOfNotNull(
    item.episodeLabel,
    item.year?.toString(),
    item.contentRating,
    formatRuntime(item.durationMs),
    item.genres.take(3).joinToString(", ").ifEmpty { null },
).joinToString("  ·  ")
