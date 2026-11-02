package app.plexhdr.shield.services

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** User settings, stored in SharedPreferences and observable from Compose. */
class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    enum class SubtitleMode(val label: String) { Off("Off"), Forced("Forced only"), Always("Always") }
    enum class SubtitleColor(val label: String, val argb: Int) {
        White("White", 0xFFFFFFFF.toInt()), Yellow("Yellow", 0xFFFFD60A.toInt()),
        Cyan("Cyan", 0xFF64D2FF.toInt()), Green("Green", 0xFF30D158.toInt())
    }
    enum class SubtitleStyle(val label: String) { Outline("Outline"), Shadow("Shadow"), Box("Box") }

    /** How audio leaves the Shield. */
    enum class AudioOutput(val label: String, val detail: String) {
        Passthrough("Passthrough (bitstream)", "TrueHD, DTS-HD MA, DTS:X, E-AC3 and AC3 go to the receiver untouched. Formats the HDMI device doesn't accept are decoded to PCM."),
        Pcm("Decode to PCM", "Everything is decoded on the Shield to multichannel PCM (up to 7.1). Atmos and DTS:X height objects are lost."),
    }

    /** Which audio track to pick when you haven't chosen one for the title. */
    enum class AudioPreference(val label: String) { Best("Best track (lossless first)"), ServerDefault("Plex's selected track") }

    /** What to do with Dolby Vision files on a TV without Dolby Vision (the QN900B). */
    enum class DolbyVision(val label: String, val detail: String) {
        BaseLayer("Play the HDR10 base layer", "Profile 7 and 8 files use the plain HEVC decoder, so the TV gets ordinary HDR10 (and the Shield never tries Dolby Vision on a TV that can't show it)."),
        Native("Let the Shield decide", "Uses the Shield's Dolby Vision decoder. Pick this only if the TV supports Dolby Vision."),
    }

    /** 8K files: the Shield's Tegra X1+ decodes up to 4K60. */
    enum class EightK(val label: String, val detail: String) {
        Auto("Ask the server for 4K when needed", "If the Shield's decoder can't open 8K, Plex converts the video to 4K HEVC. Audio is still passed through."),
        DirectOnly("Always try the original", "Never converts. 8K files fail to play on a decoder that can't handle them."),
    }

    var audioLanguage by pref("audioLanguage", "")
    var audioPreference by enumPref("audioPreference", AudioPreference.Best)
    var audioOutput by enumPref("audioOutput", AudioOutput.Passthrough)
    var subtitleMode by enumPref("subtitleMode", SubtitleMode.Forced)
    var subtitleLanguage by pref("subtitleLanguage", "")
    var subtitlesInAudioLanguage by pref("subtitlesInAudioLanguage", false)
    var subtitleSize by pref("subtitleSize", 1.0f)
    var subtitleColor by enumPref("subtitleColor", SubtitleColor.White)
    var subtitleStyle by enumPref("subtitleStyle", SubtitleStyle.Outline)
    var subtitleBottom by pref("subtitleBottom", 0.08f)
    var autoplayNext by pref("autoplayNext", true)
    var autoSkipIntro by pref("autoSkipIntro", false)
    var autoSkipCredits by pref("autoSkipCredits", false)
    var skipBack by pref("skipBack", 10)
    var skipForward by pref("skipForward", 30)
    var matchFrameRate by pref("matchFrameRate", true)
    var matchResolution by pref("matchResolution", false)
    var dolbyVision by enumPref("dolbyVision", DolbyVision.BaseLayer)
    var eightK by enumPref("eightK", EightK.Auto)
    var tmdbArtwork by pref("tmdbArtwork", true)
    var showRatings by pref("showRatings", true)
    var introAnimation by pref("introAnimation", true)
    var introSound by pref("introSound", true)

    var tmdbKey by pref("tmdbKey", BuildKeys.TMDB)
    var omdbKey by pref("omdbKey", BuildKeys.OMDB)
    var openSubtitlesKey by pref("openSubtitlesKey", BuildKeys.OPENSUBTITLES)
    var openSubtitlesUser by pref("openSubtitlesUser", BuildKeys.OPENSUBTITLES_USER)
    var openSubtitlesPassword by pref("openSubtitlesPassword", BuildKeys.OPENSUBTITLES_PASSWORD)

    // --- Delegates: each setting is Compose state, written through to SharedPreferences.

    private inner class Pref<T>(private val key: String, def: T, read: (SharedPreferences) -> T, private val write: (SharedPreferences.Editor, T) -> Unit) :
        ReadWriteProperty<Any?, T> {
        private val state: MutableState<T> = mutableStateOf(if (p.contains(key)) read(p) else def)
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            p.edit().also { write(it, value) }.apply()
        }
    }

    private fun pref(key: String, def: String) = Pref(key, def, { it.getString(key, def) ?: def }, { e, v -> e.putString(key, v) })
    private fun pref(key: String, def: Boolean) = Pref(key, def, { it.getBoolean(key, def) }, { e, v -> e.putBoolean(key, v) })
    private fun pref(key: String, def: Int) = Pref(key, def, { it.getInt(key, def) }, { e, v -> e.putInt(key, v) })
    private fun pref(key: String, def: Float) = Pref(key, def, { it.getFloat(key, def) }, { e, v -> e.putFloat(key, v) })
    private inline fun <reified E : Enum<E>> enumPref(key: String, def: E) = Pref(key, def,
        { sp -> sp.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: def },
        { e, v -> e.putString(key, v.name) })
}
