package app.plexhdr.shield.player

import android.content.Context
import android.os.Handler
import android.view.Display
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import app.plexhdr.shield.plex.Decide
import okhttp3.OkHttpClient

/**
 * Builds the ExoPlayer used for playback, set up for an HDMI receiver/soundbar:
 *  - Audio passthrough: the audio sink reads what the HDMI device accepts (AC3, E-AC3, E-AC3 JOC, TrueHD, DTS, DTS-HD) and
 *    ExoPlayer's MediaCodec audio renderer sends those as a bitstream. Everything else falls through to FFmpeg, which
 *    decodes to PCM.
 *  - "Decode to PCM" mode hands the sink PCM-only capabilities, so FFmpeg decodes everything.
 *  - Dolby Vision on a TV without it: the video renderer rewrites a Dolby Vision track with an HDR10 base layer to plain
 *    HEVC before choosing a decoder, so the Shield plays the HDR10 layer. (The enhancement layer's NAL units are ignored
 *    by the HEVC decoder.)
 */
@OptIn(UnstableApi::class)
object Engine {
    @Volatile var stripDolbyVision = false

    fun build(context: Context, http: OkHttpClient, pcmOnly: Boolean): ExoPlayer {
        val renderers = object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink {
                val b = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                @Suppress("DEPRECATION")
                if (pcmOnly) b.setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                return b.build()
            }

            override fun buildVideoRenderers(
                context: Context, extensionRendererMode: Int, mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
                eventHandler: Handler, eventListener: VideoRendererEventListener, allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>,
            ) {
                @Suppress("DEPRECATION")
                out.add(BaseLayerVideoRenderer(context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback, eventHandler, eventListener))
            }
        }
            // ON = MediaCodec (and passthrough) first, FFmpeg for what MediaCodec and the HDMI device can't do.
            // PREFER = FFmpeg first, so lossless formats are decoded in software with every channel kept.
            .setExtensionRendererMode(if (pcmOnly) DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER else DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters()
                .setExceedRendererCapabilitiesIfNecessary(true)
                .setExceedVideoConstraintsIfNecessary(true)
                .setTunnelingEnabled(false)
                .setPreferredTextLanguage(null)
                .setSelectUndeterminedTextLanguage(false))
        }

        val extractors = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
            .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
        val dataSource = DefaultDataSource.Factory(context, OkHttpDataSource.Factory(http))
        val sources = DefaultMediaSourceFactory(dataSource, extractors)

        return ExoPlayer.Builder(context, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(sources)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
    }

    /** MediaCodec video renderer that turns Dolby Vision tracks into their HEVC base layer when asked to. */
    @Suppress("DEPRECATION")
    private class BaseLayerVideoRenderer(
        context: Context, selector: MediaCodecSelector, joiningMs: Long, fallback: Boolean,
        handler: Handler, listener: VideoRendererEventListener,
    ) : MediaCodecVideoRenderer(context, selector, joiningMs, fallback, handler, listener, 50) {

        private fun map(f: Format): Format {
            if (!stripDolbyVision || f.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return f
            val base = if (f.codecs?.startsWith("dvav") == true || f.codecs?.startsWith("dva1") == true) MimeTypes.VIDEO_H264 else MimeTypes.VIDEO_H265
            return f.buildUpon().setSampleMimeType(base).setCodecs(null).build()
        }

        override fun supportsFormat(mediaCodecSelector: MediaCodecSelector, format: Format): Int =
            super.supportsFormat(mediaCodecSelector, map(format))

        override fun onInputFormatChanged(formatHolder: FormatHolder): androidx.media3.exoplayer.DecoderReuseEvaluation? {
            formatHolder.format = formatHolder.format?.let { map(it) }
            return super.onInputFormatChanged(formatHolder)
        }
    }

    /** What the HDMI audio device, the video decoders and the display report. */
    fun caps(context: Context, display: Display?): Decide.Caps {
        val bitstream = HashSet<String>()
        var maxCh = 8
        runCatching {
            val ac = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
            maxCh = ac.maxChannelCount
            val map = mapOf(
                C.ENCODING_AC3 to "ac3", C.ENCODING_E_AC3 to "eac3", C.ENCODING_E_AC3_JOC to "eac3joc",
                C.ENCODING_DOLBY_TRUEHD to "truehd", C.ENCODING_DTS to "dts", C.ENCODING_DTS_HD to "dtshd",
            )
            for ((enc, name) in map) if (ac.supportsEncoding(enc)) bitstream += name
        }
        var w = 4096
        var h = 2160
        runCatching {
            val info = MediaCodecUtil.getDecoderInfo(MimeTypes.VIDEO_H265, false, false)
            val vc = info?.capabilities?.videoCapabilities
            if (vc != null) {
                w = vc.supportedWidths.upper
                h = vc.supportedHeights.upper
            }
        }
        val hdr = HashSet<String>()
        runCatching {
            @Suppress("DEPRECATION")
            for (t in display?.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)) {
                when (t) {
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> hdr += "Dolby Vision"
                    Display.HdrCapabilities.HDR_TYPE_HDR10 -> hdr += "HDR10"
                    Display.HdrCapabilities.HDR_TYPE_HLG -> hdr += "HLG"
                    4 -> hdr += "HDR10+"
                }
            }
        }
        return Decide.Caps(bitstream, maxCh, w, h, hdr)
    }
}
