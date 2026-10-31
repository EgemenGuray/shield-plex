package app.plexhdr.shield.plex

import org.json.JSONObject

class PlexItem(o: JSONObject) : J(o) {
    val ratingKey get() = str("ratingKey")
    val key get() = str("key")
    val id: String get() = ratingKey ?: key ?: title
    val type get() = str("type") ?: ""
    val title get() = str("title") ?: ""
    val originalTitle get() = str("originalTitle")
    val summary get() = str("summary")
    val tagline get() = str("tagline")
    val year get() = int("year")
    val index get() = int("index")
    val parentIndex get() = int("parentIndex")
    val parentTitle get() = str("parentTitle")
    val grandparentTitle get() = str("grandparentTitle")
    val parentRatingKey get() = str("parentRatingKey")
    val grandparentRatingKey get() = str("grandparentRatingKey")
    val contentRating get() = str("contentRating")
    val studio get() = str("studio")
    val durationMs get() = long("duration")
    val viewOffsetMs get() = long("viewOffset") ?: 0L
    val viewCount get() = int("viewCount") ?: 0
    val lastViewedAt get() = long("lastViewedAt")
    val addedAt get() = long("addedAt")
    val leafCount get() = int("leafCount")
    val viewedLeafCount get() = int("viewedLeafCount")
    val childCount get() = int("childCount")
    val originallyAvailableAt get() = str("originallyAvailableAt")
    val thumb get() = str("thumb")
    val art get() = str("art")
    val parentThumb get() = str("parentThumb")
    val grandparentThumb get() = str("grandparentThumb")
    val grandparentArt get() = str("grandparentArt")
    val rating get() = dbl("rating")
    val audienceRating get() = dbl("audienceRating")
    val ratingImage get() = str("ratingImage")
    val audienceRatingImage get() = str("audienceRatingImage")
    val subtype get() = str("subtype")

    val genres get() = tags("Genre")
    val media get() = list("Media").map { PlexMedia(it) }
    val markers get() = list("Marker").map { PlexMarker(it) }
    val chapters get() = list("Chapter").map { PlexChapter(it) }
    val roles get() = list("Role").map { PlexPerson(it, PersonKind.Actor) }
    val crew: List<PlexPerson>
        get() = list("Director").map { PlexPerson(it, PersonKind.Director) } +
            list("Writer").map { PlexPerson(it, PersonKind.Writer) } +
            list("Producer").map { PlexPerson(it, PersonKind.Producer) }
    val clearLogo: String?
        get() = list("Image").firstOrNull { it.optString("type") == "clearLogo" }?.strOrNull("url")
    val guids: Map<String, String>
        get() {
            val out = HashMap<String, String>()
            for (g in list("Guid")) {
                val id = g.optString("id")
                val i = id.indexOf("://")
                if (i > 0) out[id.substring(0, i)] = id.substring(i + 3)
            }
            return out
        }
    val ratings get() = list("Rating")
    val extras get() = obj("Extras")?.optJSONArray("Metadata").objects().map { PlexItem(it) }
    val onDeck get() = obj("OnDeck")?.optJSONArray("Metadata").objects().firstOrNull()?.let { PlexItem(it) }

    val isWatched: Boolean
        get() = if (type == "show" || type == "season") (leafCount ?: 0) > 0 && (viewedLeafCount ?: 0) >= (leafCount ?: 0)
        else viewCount > 0
    val progress: Float?
        get() {
            val d = durationMs ?: return null
            if (d <= 0 || viewOffsetMs <= 0) return null
            return (viewOffsetMs.toFloat() / d).coerceAtMost(1f)
        }
    val isPlayable get() = type == "movie" || type == "episode" || type == "clip"
    val episodeLabel get() = if (type == "episode") "S${parentIndex ?: 0} · E${index ?: 0}" else null
    val backdropPath get() = art ?: grandparentArt ?: thumb
    val posterPath get() = if (type == "episode") grandparentThumb ?: parentThumb ?: thumb else thumb ?: parentThumb
    val displayTitle get() = if (type == "episode") grandparentTitle ?: title else title

    override fun equals(other: Any?) = other is PlexItem && other.id == id && other.viewOffsetMs == viewOffsetMs && other.viewCount == viewCount
    override fun hashCode() = id.hashCode()
}

class PlexMedia(o: JSONObject) : J(o) {
    val id get() = int("id")
    val container get() = str("container")
    val videoResolution get() = str("videoResolution")
    val width get() = int("width")
    val height get() = int("height")
    val bitrate get() = int("bitrate")
    val videoCodec get() = str("videoCodec")
    val audioCodec get() = str("audioCodec")
    val parts get() = list("Part").map { PlexPart(it) }
    val part get() = parts.firstOrNull()
    val is8K get() = (width ?: 0) >= 7000 || videoResolution.equals("8k", true)
    val is4K get() = !is8K && ((width ?: 0) >= 3800 || videoResolution.equals("4k", true))

    val label: String
        get() {
            val bits = ArrayList<String>()
            when {
                is8K -> bits.add("8K")
                is4K -> bits.add("4K")
                videoResolution != null -> bits.add(videoResolution!!.let { if (it.uppercase().endsWith("P")) it.uppercase() else it + "p" })
            }
            part?.videoStream?.let { bits.add(Decide.hdrLabel(it)) }
            videoCodec?.let { bits.add(it.uppercase()) }
            part?.audioStreams?.firstOrNull()?.let { bits.add(it.displayTitle) }
            bitrate?.let { bits.add(String.format("%.1f Mbps", it / 1000.0)) }
            return bits.joinToString(" · ")
        }
}

class PlexPart(o: JSONObject) : J(o) {
    val id get() = int("id")
    val key get() = str("key")
    val file get() = str("file")
    val size get() = long("size")
    val container get() = str("container")
    val indexes get() = str("indexes")
    val streams get() = list("Stream").map { PlexStream(it) }

    /** External files have no container index; they are listed after the embedded ones. */
    fun streams(type: Int) = streams.filter { it.streamType == type }.sortedBy { it.index ?: 1_000_000 }
    val videoStream get() = streams(1).firstOrNull()
    val audioStreams get() = streams(2)
    val subtitleStreams get() = streams(3)
}

class PlexStream(o: JSONObject) : J(o) {
    val id get() = int("id") ?: -1
    val streamType get() = int("streamType") ?: 0
    /** Position of the stream in the container (FFmpeg's stream index). null for external subtitle files. */
    val index get() = int("index")
    val codec get() = (str("codec") ?: "").lowercase()
    val languageCode get() = str("languageCode")
    val language get() = str("language")
    val title get() = str("title")
    val channels get() = int("channels") ?: 0
    val selected get() = bool("selected")
    val forced get() = bool("forced")
    val hearingImpaired get() = bool("hearingImpaired")
    val key get() = str("key")
    val isExternal get() = key != null && index == null
    val displayTitle get() = str("displayTitle") ?: codec.uppercase()
    val extendedDisplayTitle get() = str("extendedDisplayTitle") ?: displayTitle
    val profile get() = str("profile")
    val bitrate get() = int("bitrate")
    val bitDepth get() = int("bitDepth")
    val frameRate get() = dbl("frameRate")
    val width get() = int("width")
    val height get() = int("height")
    val colorTrc get() = str("colorTrc")
    val colorPrimaries get() = str("colorPrimaries")
    val doviPresent get() = bool("DOVIPresent")
    val doviProfile get() = int("DOVIProfile")
    val doviBLCompatID get() = int("DOVIBLCompatID")
    val samplingRate get() = int("samplingRate")
    val audioChannelLayout get() = str("audioChannelLayout")
    /** Plex 1.40+ reports HDR10+ in the stream; older servers only mention it in the display title. */
    val hdr10Plus: Boolean
        get() = bool("hdr10plus") || bool("HDR10Plus") || "$extendedDisplayTitle $displayTitle".contains("HDR10+")
}

class PlexMarker(o: JSONObject) : J(o) {
    val type get() = str("type") ?: ""
    val startMs get() = long("startTimeOffset") ?: 0L
    val endMs get() = long("endTimeOffset") ?: 0L
    val isIntro get() = type == "intro"
    val isCredits get() = type == "credits"
}

class PlexChapter(o: JSONObject) : J(o) {
    val title get() = str("tag") ?: "Chapter ${int("index") ?: 0}"
    val startMs get() = long("startTimeOffset") ?: 0L
    val endMs get() = long("endTimeOffset") ?: 0L
    val thumb get() = str("thumb")
}

enum class PersonKind(val param: String, val label: String) {
    Actor("actor", "Actor"), Director("director", "Director"), Writer("writer", "Writer"), Producer("producer", "Producer")
}

class PlexPerson(o: JSONObject, val kind: PersonKind) : J(o) {
    val tagId get() = str("id")
    val name get() = str("tag") ?: ""
    val role get() = str("role")
    val thumb get() = str("thumb")
    val id get() = "${kind.param}:${tagId ?: name}"
}

class PlexSection(o: JSONObject) : J(o) {
    val id get() = str("key") ?: ""
    val title get() = str("title") ?: ""
    val type get() = str("type") ?: ""
    val isVideo get() = type == "movie" || type == "show"
}

data class PlexServer(val name: String, val uri: String, val token: String, val machineId: String, val relay: Boolean) {
    fun toJson(): String = JSONObject().put("name", name).put("uri", uri).put("token", token)
        .put("machineId", machineId).put("relay", relay).toString()

    companion object {
        fun fromJson(s: String?): PlexServer? = try {
            val o = JSONObject(s ?: return null)
            PlexServer(o.getString("name"), o.getString("uri"), o.getString("token"), o.optString("machineId"), o.optBoolean("relay"))
        } catch (e: Exception) { null }
    }
}

data class PlexUser(val title: String, val thumb: String?, val uuid: String?) {
    fun toJson(): String = JSONObject().put("title", title).put("thumb", thumb).put("uuid", uuid).toString()

    companion object {
        fun fromJson(s: String?): PlexUser? = try {
            val o = JSONObject(s ?: return null)
            PlexUser(o.optString("title"), o.strOrNull("thumb"), o.strOrNull("uuid"))
        } catch (e: Exception) { null }
    }
}
