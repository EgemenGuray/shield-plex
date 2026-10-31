package app.plexhdr.shield.plex

import app.plexhdr.shield.services.Settings

/**
 * Playback decisions for the Nvidia Shield.
 *
 * What the hardware allows (and how the app uses it):
 *  - The original file is played as it is (direct play) by ExoPlayer on the Shield. MKV, MP4, TS… are demuxed on the
 *    Shield; the server never touches the stream.
 *  - Audio: AC3, E-AC3 (incl. DD+ Atmos), TrueHD (incl. Atmos), DTS, DTS-HD MA (incl. DTS:X) are sent to the HDMI device
 *    as a bitstream when it says it accepts them (Shield setting "Surround sound: Always" or "Auto" with a receiver or
 *    soundbar that reports them). Anything it doesn't accept is decoded by FFmpeg to multichannel PCM.
 *  - Video: HEVC/H.264/VP9 up to 4K60 in hardware, with HDR10 and HLG. The Shield can't send HDR10+ metadata over HDMI
 *    (Nvidia's display driver doesn't support it), so HDR10+ files play as HDR10 and the TV tone-maps them.
 *  - Dolby Vision: the QN900B has none, so profile 7/8 files play their HDR10 base layer with the plain HEVC decoder.
 *  - 8K: the Tegra X1+ decodes up to 4K. 8K files are converted to 4K by the server (audio still copied) unless the
 *    decoder says it can handle them.
 */
object Decide {
    enum class Engine { Direct, Transcode4K }

    /** What the Shield's audio output and video decoders report. Filled in by the player layer. */
    data class Caps(
        val bitstream: Set<String> = emptySet(),   // "ac3", "eac3", "eac3joc", "truehd", "dts", "dtshd"
        val maxPcmChannels: Int = 8,
        val decoderMaxWidth: Int = 4096,
        val decoderMaxHeight: Int = 2160,
        val hdrTypes: Set<String> = emptySet(),    // display HDR types: "HDR10", "HLG", "DV", "HDR10+"
    )

    data class Plan(
        val media: PlexMedia?,
        val mediaIndex: Int,
        val part: PlexPart?,
        val video: PlexStream?,
        val audio: PlexStream?,
        val subtitle: PlexStream?,
        val engine: Engine,
        val bitstream: Boolean,
        val stripDolbyVision: Boolean,
        val note: String,
        val warnings: List<String>,
    ) {
        val hdr get() = hdrLabel(video)
    }

    // Plex mixes ISO 639-2 bibliographic and terminology codes; compare on one of them.
    private val langAlias = mapOf(
        "deu" to "ger", "fra" to "fre", "zho" to "chi", "nld" to "dut", "ces" to "cze", "ell" to "gre", "fas" to "per",
        "ron" to "rum", "slk" to "slo", "sqi" to "alb", "hye" to "arm", "eus" to "baq", "mya" to "bur", "kat" to "geo",
        "isl" to "ice", "mkd" to "mac", "msa" to "may", "cym" to "wel",
    )
    val iso1 = mapOf(
        "en" to "eng", "tr" to "tur", "de" to "ger", "fr" to "fre", "es" to "spa", "it" to "ita", "ja" to "jpn", "ko" to "kor",
        "zh" to "chi", "ru" to "rus", "pt" to "por", "nl" to "dut", "sv" to "swe", "da" to "dan", "no" to "nor", "fi" to "fin",
        "pl" to "pol", "ar" to "ara", "el" to "gre", "he" to "heb", "hi" to "hin", "cs" to "cze", "hu" to "hun", "ro" to "rum",
    )

    fun norm(code: String?): String? {
        val c = code?.lowercase()?.ifEmpty { null } ?: return null
        iso1[c]?.let { return it }
        return langAlias[c] ?: c
    }

    fun sameLang(a: String?, b: String?): Boolean {
        val x = norm(a) ?: return false
        val y = norm(b) ?: return false
        return x == y
    }

    /** ISO 639-1 code for online services (OpenSubtitles wants "en", "tr"). */
    fun iso639_1(code: String?): String? {
        val n = norm(code) ?: return null
        if (n.length == 2) return n
        return iso1.entries.firstOrNull { it.value == n }?.key
    }

    fun isAtmos(s: PlexStream?) = s != null && "${s.extendedDisplayTitle} ${s.displayTitle} ${s.title ?: ""} ${s.profile ?: ""}".lowercase().contains("atmos")
    fun isDtsX(s: PlexStream?) = s != null && "${s.extendedDisplayTitle} ${s.displayTitle} ${s.title ?: ""} ${s.profile ?: ""}".lowercase().contains("dts:x")
    fun isTrueHD(s: PlexStream) = s.codec.contains("truehd") || s.codec == "mlp"
    fun isDTS(s: PlexStream) = s.codec.startsWith("dca") || s.codec.startsWith("dts")
    fun isDtsHD(s: PlexStream) = isDTS(s) && (s.profile ?: "").lowercase().let { it.contains("ma") || it.contains("hra") || it.contains("hd") || it.contains("x") }
    fun isLossless(s: PlexStream) = isTrueHD(s) || s.codec in setOf("flac", "pcm", "lpcm", "alac") || (isDTS(s) && (s.profile ?: "").lowercase().contains("ma"))
    fun isImageSub(s: PlexStream) = s.codec in setOf("pgs", "hdmv_pgs_subtitle", "vobsub", "dvd_subtitle", "dvb_subtitle") || s.codec.contains("pgs")

    /** The key in [Caps.bitstream] this track needs, or null for formats that are always decoded (AAC, FLAC…). */
    fun bitstreamKey(s: PlexStream): String? = when {
        isTrueHD(s) -> "truehd"
        s.codec == "eac3" -> if (isAtmos(s)) "eac3joc" else "eac3"
        s.codec == "ac3" -> "ac3"
        isDTS(s) -> if (isDtsHD(s)) "dtshd" else "dts"
        else -> null
    }

    /**
     * A file with Dolby Vision and HDR10+ (common in remuxes) is labelled HDR10+: the TV can't show Dolby Vision, and
     * the HDR10 base layer is where the HDR10+ metadata lives.
     */
    fun hdrLabel(v: PlexStream?): String {
        if (v == null) return "SDR"
        if (v.hdr10Plus) return "HDR10+"
        if (v.doviPresent) return "Dolby Vision P${v.doviProfile ?: "?"}" + if ((v.doviBLCompatID ?: 0) > 0) " (HDR10 base)" else ""
        return when (v.colorTrc) {
            "smpte2084" -> "HDR10"
            "arib-std-b67" -> "HLG"
            else -> "SDR"
        }
    }

    fun audioLabel(s: PlexStream?) = s?.extendedDisplayTitle ?: "None"

    /** Ranks tracks of one language: lossless first, then Atmos / DTS:X, then channel count. */
    private fun quality(s: PlexStream) = (if (isLossless(s)) 1000 else 0) + (if (isAtmos(s) || isDtsX(s)) 100 else 0) + s.channels

    fun chooseAudio(part: PlexPart?, requested: Int?, settings: Settings): PlexStream? {
        val audios = part?.audioStreams ?: emptyList()
        if (requested != null) audios.firstOrNull { it.id == requested }?.let { return it }
        if (settings.audioPreference == Settings.AudioPreference.ServerDefault) return audios.firstOrNull { it.selected } ?: audios.firstOrNull()
        val lang = settings.audioLanguage.ifEmpty { null }
        var base = if (lang != null) audios.filter { sameLang(it.languageCode, lang) } else emptyList()
        if (base.isEmpty()) {
            val first = audios.firstOrNull { it.selected } ?: audios.firstOrNull()
            if (first != null) base = audios.filter { it.languageCode == first.languageCode }
        }
        val candidates = base.filter { !(it.title ?: "").lowercase().contains("comment") }
        return candidates.maxByOrNull { quality(it) } ?: audios.firstOrNull()
    }

    /** Default subtitle, from the subtitle mode and language. */
    fun chooseSubtitle(part: PlexPart?, audio: PlexStream?, settings: Settings): PlexStream? {
        val subs = part?.subtitleStreams ?: return null
        if (subs.isEmpty()) return null
        val lang = settings.subtitleLanguage.ifEmpty { null } ?: audio?.languageCode
        val inLang = { s: PlexStream -> lang == null || sameLang(s.languageCode, lang) }
        return when (settings.subtitleMode) {
            Settings.SubtitleMode.Off -> null
            Settings.SubtitleMode.Forced -> subs.firstOrNull { it.forced && (sameLang(it.languageCode, audio?.languageCode) || inLang(it)) }
            Settings.SubtitleMode.Always -> {
                // Don't show full subtitles in the language you're already listening to, unless asked for.
                if (audio != null && sameLang(audio.languageCode, lang) && !settings.subtitlesInAudioLanguage) {
                    subs.firstOrNull { it.forced && sameLang(it.languageCode, audio.languageCode) }
                } else {
                    subs.firstOrNull { inLang(it) && !it.forced && !it.hearingImpaired }
                        ?: subs.firstOrNull { inLang(it) && !it.forced }
                        ?: subs.firstOrNull(inLang)
                }
            }
        }
    }

    private fun channelsLabel(c: Int) = when {
        c >= 8 -> "7.1"
        c >= 6 -> "5.1"
        c == 2 -> "stereo"
        else -> "$c ch"
    }

    /**
     * [subtitleId]: null = choose automatically, -1 = off, otherwise a Plex stream id.
     */
    fun plan(item: PlexItem, mediaIndex: Int = 0, audioId: Int? = null, subtitleId: Int? = null, settings: Settings, caps: Caps): Plan {
        val all = item.media
        val media = all.getOrNull(mediaIndex) ?: all.firstOrNull()
        val part = media?.part
        val video = part?.videoStream
        val audio = chooseAudio(part, audioId, settings)
        val subtitle = when (subtitleId) {
            null -> chooseSubtitle(part, audio, settings)
            -1 -> null
            else -> part?.subtitleStreams?.firstOrNull { it.id == subtitleId }
        }
        val warnings = ArrayList<String>()
        val notes = ArrayList<String>()

        // Video
        var engine = Engine.Direct
        val w = video?.width ?: media?.width ?: 0
        val h = video?.height ?: media?.height ?: 0
        val tooBig = w > caps.decoderMaxWidth + 64 || h > caps.decoderMaxHeight + 64
        if (tooBig) {
            if (settings.eightK == Settings.EightK.Auto) {
                engine = Engine.Transcode4K
                notes += "8K is more than the Shield can decode, so the server converts the video to 4K HEVC. The audio is copied untouched."
                warnings += "Converting 8K to 4K loses HDR10+ (the server keeps HDR10 at best). The QN900B plays 8K files natively in the Tizen app."
            } else {
                warnings += "This is ${w}×${h}; the Shield's decoder reports ${caps.decoderMaxWidth}×${caps.decoderMaxHeight} at most, so it will probably fail."
            }
        }
        val hdr = hdrLabel(video)
        var stripDv = false
        if (video != null && video.doviPresent) {
            val hasBase = (video.doviBLCompatID ?: 0) > 0 || video.doviProfile == 7 || video.doviProfile == 8
            if (settings.dolbyVision == Settings.DolbyVision.BaseLayer && hasBase) {
                stripDv = true
                notes += if (video.hdr10Plus) "Dolby Vision + HDR10+ file: plays its HDR10 layer." else "Dolby Vision profile ${video.doviProfile}: plays its HDR10 base layer."
            } else if (video.doviProfile == 5) {
                warnings += "Dolby Vision profile 5 has no HDR10 layer; on a TV without Dolby Vision the colours may be wrong."
            }
        }
        if (hdr == "HDR10+") notes += "The Shield can't send HDR10+ metadata, so the TV gets HDR10 and tone-maps it."

        // Audio
        var bitstream = false
        if (audio != null) {
            val key = bitstreamKey(audio)
            val wantPass = settings.audioOutput == Settings.AudioOutput.Passthrough
            // DD+ Atmos is E-AC3 with JOC; a device that takes E-AC3 takes the Atmos stream too.
            val accepted = key != null && (key in caps.bitstream || (key == "eac3joc" && "eac3" in caps.bitstream))
            if (wantPass && accepted) {
                bitstream = true
                val what = when {
                    isTrueHD(audio) && isAtmos(audio) -> "TrueHD Atmos"
                    isDtsX(audio) -> "DTS:X"
                    else -> audioLabel(audio)
                }
                notes.add(0, "$what goes to the soundbar as a bitstream (lossless, untouched).")
            } else {
                val ch = channelsLabel(minOf(audio.channels, caps.maxPcmChannels))
                val why = when {
                    key == null -> ""
                    !wantPass -> " (passthrough is off in Settings)"
                    else -> " (the HDMI device doesn't accept ${key.uppercase()} as a bitstream; see Settings → Sound check)"
                }
                notes.add(0, "${audioLabel(audio)} is decoded on the Shield to $ch PCM$why.")
                if (isAtmos(audio) || isDtsX(audio)) warnings += "Decoding to PCM drops the Atmos / DTS:X height objects."
            }
        }
        if (subtitle != null && engine == Engine.Transcode4K && !subtitle.isExternal) {
            warnings += "Embedded subtitles aren't carried by the converted stream; pick an external or downloaded one."
        }

        return Plan(media, all.indexOf(media).coerceAtLeast(0), part, video, audio, subtitle, engine, bitstream, stripDv,
            notes.joinToString(" "), warnings)
    }

    /** Apple TV-style badges for the details page. */
    fun badges(plan: Plan): List<String> {
        val out = ArrayList<String>()
        val m = plan.media
        when {
            m?.is8K == true -> out += "8K"
            m?.is4K == true -> out += "4K"
            m?.videoResolution?.let { it.contains("1080") || it.contains("720") } == true -> out += "HD"
        }
        val h = hdrLabel(plan.video)
        if (h == "HDR10+") out += "HDR10+"
        else if (plan.video?.doviPresent == true) out += "Dolby Vision"
        else if (h != "SDR") out += h
        plan.audio?.let { a ->
            when {
                isAtmos(a) -> out += "Dolby Atmos"
                isDtsX(a) -> out += "DTS:X"
                a.channels >= 8 -> out += "7.1"
                a.channels >= 6 -> out += "5.1"
            }
            if (isLossless(a)) out += "Lossless"
        }
        val subs = plan.part?.subtitleStreams ?: emptyList()
        if (subs.any { it.hearingImpaired }) out += "SDH" else if (subs.isNotEmpty()) out += "CC"
        return out
    }
}
