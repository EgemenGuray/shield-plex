package app.plexhdr.shield.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.player.Engine
import app.plexhdr.shield.plex.PlexClient
import app.plexhdr.shield.services.Settings
import kotlinx.coroutines.launch

private sealed interface Row_ {
    data class Header(val title: String) : Row_
    data class Item(val label: String, val value: String, val detail: String? = null, val onClick: () -> Unit) : Row_
}

private data class Input(val title: String, val value: String, val secret: Boolean = false, val number: Boolean = false, val onDone: (String) -> Unit)

@Composable
fun SettingsScreen(nav: Nav) {
    val s = App.settings
    val plex = App.plex
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Pair<String, List<Choice>>?>(null) }
    var onPick by remember { mutableStateOf<(Int) -> Unit>({}) }
    var input by remember { mutableStateOf<Input?>(null) }
    var sheet by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
    var servers by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    fun <E : Enum<E>> pick(title: String, values: Array<E>, current: E, label: (E) -> String, detail: (E) -> String? = { null }, set: (E) -> Unit) {
        dialog = title to values.map { Choice(label(it), detail(it), it == current) }
        onPick = { i -> set(values[i]) }
    }
    fun pickList(title: String, values: List<Pair<String, String>>, current: String, set: (String) -> Unit) {
        dialog = title to values.map { Choice(it.second, null, it.first == current) }
        onPick = { i -> set(values[i].first) }
    }
    val languages = listOf("" to "Automatic", "eng" to "English", "tur" to "Turkish", "ger" to "German", "fre" to "French", "spa" to "Spanish",
        "ita" to "Italian", "jpn" to "Japanese", "kor" to "Korean", "rus" to "Russian", "por" to "Portuguese", "dut" to "Dutch")
    fun langName(code: String) = languages.firstOrNull { it.first == code }?.second ?: code
    fun onOff(b: Boolean) = if (b) "On" else "Off"

    val rows = buildList<Row_> {
        add(Row_.Header("Account"))
        add(Row_.Item("Plex user", plex.user?.title ?: "—", "Switch between Plex Home users") {
            scope.launch {
                val users = runCatching { plex.homeUsers() }.getOrElse { status = it.message; return@launch }
                dialog = "Switch user" to users.map { Choice(it.title, if (it.protected) "PIN" else null, it.uuid == plex.user?.uuid) }
                onPick = { i ->
                    val u = users[i]
                    if (u.protected) input = Input("PIN for ${u.title}", "", secret = true, number = true) { pin ->
                        scope.launch { runCatching { plex.switchUser(u, pin) }.onFailure { status = it.message } }
                    } else scope.launch { runCatching { plex.switchUser(u, null) }.onFailure { status = it.message } }
                }
            }
        })
        add(Row_.Item("Server", plex.server?.let { it.name + if (it.relay) " (relay)" else "" } ?: "—", plex.server?.uri) { servers = true })
        add(Row_.Item("Sign out", "", null) { plex.signOut() })

        add(Row_.Header("Sound"))
        add(Row_.Item("Audio output", s.audioOutput.label, s.audioOutput.detail) {
            pick("Audio output", Settings.AudioOutput.values(), s.audioOutput, { it.label }, { it.detail }) { s.audioOutput = it }
        })
        add(Row_.Item("Sound check", "", "What the Shield's HDMI output and decoders report") {
            val caps = Engine.caps(ctx, view.display)
            sheet = listOf(
                "HDMI audio" to "",
                "Bitstream formats" to caps.bitstream.sorted().joinToString(", ").ifEmpty { "none: set Shield Settings → Display & Sound → Advanced sound → Surround sound to Always" },
                "Max PCM channels" to caps.maxPcmChannels.toString(),
                "TrueHD (Atmos)" to if ("truehd" in caps.bitstream) "Passthrough" else "Decoded to PCM",
                "DTS-HD MA / DTS:X" to if ("dtshd" in caps.bitstream) "Passthrough" else if ("dts" in caps.bitstream) "DTS core only" else "Decoded to PCM",
                "E-AC3 (DD+ Atmos)" to if ("eac3" in caps.bitstream) "Passthrough" else "Decoded to PCM",
                "Video" to "",
                "HEVC decoder" to "up to ${caps.decoderMaxWidth}×${caps.decoderMaxHeight}",
                "TV HDR formats" to caps.hdrTypes.sorted().joinToString(", ").ifEmpty { "none reported" },
                "Tips" to "",
                "Shield" to "Settings → Display & Sound → Advanced sound settings → Surround sound: Always, and turn on the formats your soundbar takes.",
                "Samsung TV (eARC)" to "Sound → Expert Settings → HDMI-eARC Mode: Auto, Digital Output Audio Format: Passthrough.",
            )
        })
        add(Row_.Item("Preferred audio language", langName(s.audioLanguage), null) { pickList("Audio language", languages, s.audioLanguage) { s.audioLanguage = it } })
        add(Row_.Item("Audio track", s.audioPreference.label, null) {
            pick("Audio track", Settings.AudioPreference.values(), s.audioPreference, { it.label }) { s.audioPreference = it }
        })

        add(Row_.Header("Subtitles"))
        add(Row_.Item("Subtitles", s.subtitleMode.label, null) { pick("Subtitles", Settings.SubtitleMode.values(), s.subtitleMode, { it.label }) { s.subtitleMode = it } })
        add(Row_.Item("Subtitle language", langName(s.subtitleLanguage), null) { pickList("Subtitle language", languages, s.subtitleLanguage) { s.subtitleLanguage = it } })
        add(Row_.Item("Full subtitles in the audio language", onOff(s.subtitlesInAudioLanguage), null) { s.subtitlesInAudioLanguage = !s.subtitlesInAudioLanguage })
        val sizes = listOf(0.8f to "Small", 1.0f to "Medium", 1.25f to "Large", 1.5f to "Extra large")
        add(Row_.Item("Size", sizes.minByOrNull { kotlin.math.abs(it.first - s.subtitleSize) }!!.second, null) {
            dialog = "Size" to sizes.map { Choice(it.second, null, it.first == s.subtitleSize) }; onPick = { i -> s.subtitleSize = sizes[i].first }
        })
        add(Row_.Item("Colour", s.subtitleColor.label, null) { pick("Colour", Settings.SubtitleColor.values(), s.subtitleColor, { it.label }) { s.subtitleColor = it } })
        add(Row_.Item("Style", s.subtitleStyle.label, null) { pick("Style", Settings.SubtitleStyle.values(), s.subtitleStyle, { it.label }) { s.subtitleStyle = it } })
        val positions = listOf(0.04f to "Low", 0.08f to "Normal", 0.14f to "Raised", 0.2f to "High")
        add(Row_.Item("Position", positions.minByOrNull { kotlin.math.abs(it.first - s.subtitleBottom) }!!.second, null) {
            dialog = "Position" to positions.map { Choice(it.second, null, it.first == s.subtitleBottom) }; onPick = { i -> s.subtitleBottom = positions[i].first }
        })

        add(Row_.Header("Playback"))
        add(Row_.Item("Match frame rate", onOff(s.matchFrameRate), "Switches the TV to 23.976/24/25/50/60 Hz to match the video") { s.matchFrameRate = !s.matchFrameRate })
        add(Row_.Item("Match resolution", onOff(s.matchResolution), "Off: the Shield upscales to the current output (best on the QN900B: leave its own scaler to the Shield's 4K output)") { s.matchResolution = !s.matchResolution })
        add(Row_.Item("Dolby Vision", s.dolbyVision.label, s.dolbyVision.detail) {
            pick("Dolby Vision", Settings.DolbyVision.values(), s.dolbyVision, { it.label }, { it.detail }) { s.dolbyVision = it }
        })
        add(Row_.Item("8K files", s.eightK.label, s.eightK.detail) {
            pick("8K files", Settings.EightK.values(), s.eightK, { it.label }, { it.detail }) { s.eightK = it }
        })
        add(Row_.Item("Autoplay next episode", onOff(s.autoplayNext), null) { s.autoplayNext = !s.autoplayNext })
        add(Row_.Item("Skip intros automatically", onOff(s.autoSkipIntro), null) { s.autoSkipIntro = !s.autoSkipIntro })
        add(Row_.Item("Skip credits automatically", onOff(s.autoSkipCredits), "Only when there's no next episode") { s.autoSkipCredits = !s.autoSkipCredits })
        add(Row_.Item("Skip back", "${s.skipBack} s", null) {
            val v = listOf(5, 10, 15, 30); dialog = "Skip back" to v.map { Choice("$it s", null, it == s.skipBack) }; onPick = { i -> s.skipBack = v[i] }
        })
        add(Row_.Item("Skip forward", "${s.skipForward} s", null) {
            val v = listOf(10, 15, 30, 60); dialog = "Skip forward" to v.map { Choice("$it s", null, it == s.skipForward) }; onPick = { i -> s.skipForward = v[i] }
        })

        add(Row_.Header("Online services (free keys)"))
        fun key(label: String, value: String, secret: Boolean, set: (String) -> Unit) =
            add(Row_.Item(label, if (value.isEmpty()) "Not set" else if (secret) "••••••" else value.take(10) + if (value.length > 10) "…" else "", null) {
                input = Input(label, value, secret = secret) { set(it.trim()); App.online.resetLogin() }
            })
        key("TMDB key", s.tmdbKey, false) { s.tmdbKey = it }
        key("OMDb key", s.omdbKey, false) { s.omdbKey = it }
        key("OpenSubtitles API key", s.openSubtitlesKey, false) { s.openSubtitlesKey = it }
        key("OpenSubtitles username", s.openSubtitlesUser, false) { s.openSubtitlesUser = it }
        key("OpenSubtitles password", s.openSubtitlesPassword, true) { s.openSubtitlesPassword = it }
        add(Row_.Item("Artwork from TMDB", onOff(s.tmdbArtwork), null) { s.tmdbArtwork = !s.tmdbArtwork })
        add(Row_.Item("Show ratings", onOff(s.showRatings), "IMDb, Letterboxd, Rotten Tomatoes, Metacritic") { s.showRatings = !s.showRatings })

        add(Row_.Header("Remote"))
        add(Row_.Item("Player keys", "", null) {
            sheet = listOf(
                "Remote" to "",
                "Select" to "Play / pause. Presses Skip Intro or Up Next when shown",
                "◀ / ▶" to "Back ${s.skipBack} s / forward ${s.skipForward} s. While paused: scrub with thumbnails, Select jumps there",
                "▼" to "Panel: Info, Chapters, Audio, Subtitles, Picture",
                "▲" to "Show the timeline",
                "⏪ / ⏩ (media keys)" to "Previous / next chapter",
                "Back" to "Close the panel, or stop",
            )
        })
        add(Row_.Item("Version", PlexClient.VERSION, "Lumen for SHIELD") {})
    }

    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().padding(top = 90.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 160.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item { status?.let { T(it, size = 15.sp, color = C.warn) } }
            items(rows.size) { i ->
                when (val r = rows[i]) {
                    is Row_.Header -> T(r.title, Modifier.padding(top = 22.dp, bottom = 6.dp), size = 15.sp, color = C.accent, weight = FontWeight.SemiBold)
                    is Row_.Item -> Focusable(r.onClick, Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), 1.02f, if (i == 1) first else null, showBorder = false) { f ->
                        Row(Modifier.fillMaxWidth().background(if (f) C.focus else C.surface).padding(horizontal = 22.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                T(r.label, size = 17.sp, color = if (f) Color.Black else C.text)
                                if (r.detail != null) T(r.detail, size = 13.sp, color = if (f) Color.DarkGray else C.dim, maxLines = 2)
                            }
                            Spacer(Modifier.width(16.dp))
                            T(r.value, size = 16.sp, color = if (f) Color.DarkGray else C.dim, maxLines = 1)
                        }
                    }
                }
            }
        }
        dialog?.let { (title, choices) -> ChoiceDialog(title, choices, { i -> onPick(i); dialog = null }, { dialog = null }) }
        sheet?.let { TextDialog("Info", it) { sheet = null } }
        input?.let { inp -> InputDialog(inp) { input = null } }
        if (servers) Box(Modifier.fillMaxSize().background(C.bg)) {
            BackHandler { servers = false }
            ServerPicker { servers = false }
        }
    }
}

@Composable
private fun InputDialog(inp: Input, close: () -> Unit) {
    BackHandler(onBack = close)
    var text by remember { mutableStateOf(inp.value) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp).background(C.surface, RoundedCornerShape(20.dp)).padding(28.dp)) {
            T(inp.title, size = 24.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Box(Modifier.fillMaxWidth().border(2.dp, C.dim, RoundedCornerShape(10.dp)).padding(14.dp)) {
                BasicTextField(text, { text = it }, Modifier.fillMaxWidth().focusRequester(fr),
                    textStyle = TextStyle(color = C.text, fontSize = 20.sp), singleLine = true, cursorBrush = SolidColor(C.accent),
                    visualTransformation = if (inp.secret) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (inp.number) KeyboardType.NumberPassword else KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { inp.onDone(text); close() }))
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Pill("Save", { inp.onDone(text); close() })
                Pill("Cancel", close)
            }
        }
    }
}
