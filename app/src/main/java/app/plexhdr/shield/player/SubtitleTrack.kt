package app.plexhdr.shield.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.extractor.text.ssa.SsaParser
import androidx.media3.extractor.text.subrip.SubripParser
import androidx.media3.extractor.text.webvtt.WebvttParser

/**
 * An external subtitle file (from Plex or downloaded from OpenSubtitles), parsed with Media3's own parsers and drawn
 * by the app, so its timing can be shifted freely.
 */
@OptIn(UnstableApi::class)
class SubtitleTrack private constructor(private val entries: List<CuesWithTiming>) {
    fun cuesAt(timeUs: Long): List<Cue> {
        val out = ArrayList<Cue>()
        for (e in entries) {
            if (e.startTimeUs > timeUs) break
            val end = if (e.durationUs == C.TIME_UNSET) e.endTimeUs else e.startTimeUs + e.durationUs
            if (timeUs < end) out += e.cues
        }
        return out
    }

    companion object {
        fun parse(bytes: ByteArray, format: String): SubtitleTrack? {
            val parser: SubtitleParser = when (format.lowercase()) {
                "ass", "ssa" -> SsaParser()
                "vtt", "webvtt" -> WebvttParser()
                else -> SubripParser()
            }
            val out = ArrayList<CuesWithTiming>()
            return runCatching {
                parser.parse(bytes, 0, bytes.size, SubtitleParser.OutputOptions.allCues()) { out.add(it) }
                SubtitleTrack(out.filter { it.startTimeUs != C.TIME_UNSET }.sortedBy { it.startTimeUs })
            }.getOrNull()
        }
    }
}
