package app.plexhdr.shield.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.abs

// Ten even columns, so every column lines up from the digits down to the action row.
private val letterRows = listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm.-_")   // row 3 gets one punctuation key
private val symbolRows = listOf("1234567890", "!@#$%^&*()", "-_=+[]{}~|", ";:'\",.?/<>")
private val urlChips = listOf("http://", "https://", ".com", ".net", ".tv", ":8080", "/", ":", "@", "/get.php?username=", "&password=")
private val emailChips = listOf("@", ".com", "@gmail.com", "@outlook.com", "@icloud.com")
private val padKeys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "⌫", "0", "Done")
private val digitKeys = listOf(
    Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine,
    Key.NumPad0, Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

private val KeyW = 68.dp
private val KeyH = 54.dp
private val KeyGap = 8.dp
private val PadW = 120.dp
private val PadH = 72.dp
private val PadGap = 12.dp
private val PanelPadH = 36.dp
private val KeyShape = RoundedCornerShape(12.dp)
private val PanelShape = RoundedCornerShape(28.dp)

/** Width of a key that spans [n] grid columns, so wide keys still line up with the columns above them. */
private fun span(n: Int, w: Dp = KeyW, gap: Dp = KeyGap): Dp = w * n + gap * (n - 1)

private enum class ShiftState { Off, Once, Lock }
private enum class Act { Shift, Symbols, Voice, Space, Delete, Clear, Done }
private enum class Tone { Char, Func, Active, Accent }

/** A cell of the navigation grid, in column units (0..10 on the QWERTY grid, 0..3 on the number pad). */
private class Cell(val start: Float, val width: Float) {
    val center get() = start + width / 2
    operator fun contains(c: Float) = c >= start && c < start + width
}

private fun cells(widths: List<Float>): List<Cell> {
    var s = 0f
    return widths.map { w -> Cell(s, w).also { s += w } }
}

private fun hasVoice(context: Context): Boolean = runCatching {
    @Suppress("DEPRECATION")
    context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
}.getOrDefault(false)

/**
 * Text entry for the remote, like the Apple TV keyboard: a large input line on top and an even
 * 10-column QWERTY grid, so Up / Down stay in the same column and Left / Right wrap around a row.
 * Delete, Clear and Done are always one press away; holding ⌫ clears. The remote's number keys and
 * its delete / rewind key type too, OK on the input line opens the Shield's own keyboard (phone
 * typing, voice), and 🎤 starts Android speech recognition when the box has it.
 *
 * Back closes without calling [onDone], so the caller keeps its original text. [onChange] gets
 * every edit as it happens (search results update live behind the keyboard).
 */
@Composable
fun TextEntryDialog(
    title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit,
    number: Boolean = false, secret: Boolean = false, url: Boolean = false, onChange: (String) -> Unit = {},
) {
    var text by remember { mutableStateOf(initial) }
    fun update(v: String) {
        if (v == text) return
        text = v
        onChange(v)
    }
    var shift by remember { mutableStateOf(ShiftState.Off) }
    var symbols by remember { mutableStateOf(false) }
    fun type(s: String) {
        update(text + s)
        if (shift == ShiftState.Once && s.any { it.isLetter() }) shift = ShiftState.Off
    }
    fun delete() = update(text.dropLast(1))

    val email = !url && !number && listOf("mail", "user", "login", "account").any { title.contains(it, true) }
    val chips = when {
        number -> emptyList()
        url -> urlChips
        email -> emailChips
        else -> emptyList()
    }
    val punct = when { url -> ":"; email -> "@"; else -> "'" }

    // Voice: registered here, outside the dialog window, against the activity's result registry.
    val context = LocalContext.current
    val voiceOk = remember(number) { !number && hasVoice(context) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val said = if (res.resultCode == Activity.RESULT_OK) {
            res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim().orEmpty()
        } else ""
        if (said.isNotEmpty()) {
            val add = if (url || secret) said.replace(" ", "") else said
            update(if (text.isEmpty() || text.endsWith(" ") || url || secret) text + add else "$text $add")
        }
    }
    fun listen() {
        runCatching {
            voice.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, title.ifBlank { "Speak" }),
            )
        }
    }

    val actions: List<Pair<Act, Int>> = buildList {
        add(Act.Shift to 1); add(Act.Symbols to 1)
        if (voiceOk) add(Act.Voice to 1)
        add(Act.Space to if (voiceOk) 3 else 4)
        add(Act.Delete to 1); add(Act.Clear to 1); add(Act.Done to 2)
    }

    // The navigation grid: row 0 is the input line, then the chips (if any), then the keys.
    val cols = if (number) 3f else 10f
    val chipRow = if (chips.isNotEmpty()) 1 else -1
    val firstKeyRow = if (chips.isNotEmpty()) 2 else 1
    val model: List<List<Cell>> = remember(number, chips, voiceOk) {
        buildList {
            add(listOf(Cell(0f, cols)))
            if (chips.isNotEmpty()) add(List(chips.size) { Cell(it * cols / chips.size, cols / chips.size) })
            if (number) repeat(4) { add(cells(List(3) { 1f })) }
            else {
                repeat(4) { add(cells(List(10) { 1f })) }
                add(cells(actions.map { it.second.toFloat() }))
            }
        }
    }
    val reqs = remember { HashMap<Int, FocusRequester>() }
    fun req(r: Int, i: Int): FocusRequester = reqs.getOrPut(r * 100 + i) { FocusRequester() }
    val startRow = if (number) firstKeyRow else firstKeyRow + 1   // "1" on the number pad, "q" on QWERTY
    var cur by remember { mutableStateOf(startRow to 0) }
    var colHint by remember { mutableStateOf(0.5f) }
    var inputFocused by remember { mutableStateOf(false) }
    var inputEditing by remember { mutableStateOf(false) }   // the Shield keyboard is typing into the input line

    fun focusCell(r: Int, i: Int) { runCatching { req(r, i).requestFocus() } }
    fun move(dr: Int, dc: Int) {
        val (r, i) = cur
        val row = model.getOrNull(r) ?: return
        val cell = row.getOrNull(i) ?: return
        if (dc != 0) {
            if (row.size < 2) return
            val ni = Math.floorMod(i + dc, row.size)   // wrap around the row
            colHint = row[ni].center
            focusCell(r, ni)
            return
        }
        val target = model.getOrNull(r + dr) ?: return
        // Keep the column we came from, so Down, Down, Up, Up through the wide keys lands back on the same key.
        val c = if (colHint in cell) colHint else cell.center
        colHint = c
        val ni = target.indexOfFirst { c in it }.takeIf { it >= 0 }
            ?: target.indices.minByOrNull { abs(target[it].center - c) } ?: 0
        focusCell(r + dr, ni)
    }

    val panelW = if (number) span(3, PadW, PadGap) + PanelPadH * 2 else span(10) + PanelPadH * 2

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(panelW)
                .shadow(40.dp, PanelShape)
                .clip(PanelShape)
                .background(Brush.verticalGradient(listOf(Color(0xF0282830), Color(0xF0141418))))
                .border(1.dp, Color(0x22FFFFFF), PanelShape)
                .padding(horizontal = PanelPadH, vertical = 30.dp)
                .onPreviewKeyEvent { e ->
                    // While the Shield keyboard types into the input line, it gets every key.
                    if (inputEditing) return@onPreviewKeyEvent false
                    val down = e.type == KeyEventType.KeyDown
                    val dir = when (e.key) {
                        Key.DirectionLeft -> 0 to -1
                        Key.DirectionRight -> 0 to 1
                        Key.DirectionUp -> -1 to 0
                        Key.DirectionDown -> 1 to 0
                        else -> null
                    }
                    if (dir != null) {
                        if (down) move(dir.first, dir.second)
                        return@onPreviewKeyEvent true
                    }
                    if (!down) return@onPreviewKeyEvent false
                    val digit = digitKeys.indexOf(e.key)
                    when {
                        digit >= 0 -> { type((digit % 10).toString()); true }
                        e.key == Key.Backspace || e.key == Key.MediaRewind -> { delete(); true }
                        else -> false
                    }
                },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                T(title.ifBlank { "Enter text" }, Modifier.weight(1f), size = 18.sp, weight = FontWeight.SemiBold, color = C.dim, maxLines = 1)
                if (text.length >= 20) T("${text.length} characters", size = 13.sp, color = C.faint, maxLines = 1)
            }
            Spacer(Modifier.height(10.dp))

            // The input line. OK on it opens the Shield's own keyboard (phone typing, voice) on the real field.
            val inputShape = RoundedCornerShape(16.dp)
            TvTextField(
                text, { update(it) },
                Modifier.fillMaxWidth()
                    .onFocusChanged { inputFocused = it.isFocused; inputEditing = it.hasFocus && !it.isFocused }
                    .clip(inputShape)
                    .background(Color(0x59000000))
                    .border(2.dp, when { inputEditing -> C.accent; inputFocused -> C.focus; else -> Color(0x1AFFFFFF) }, inputShape)
                    .padding(horizontal = 22.dp, vertical = 14.dp),
                textStyle = TextStyle(color = if (inputEditing) C.text else Color.Transparent, fontSize = 28.sp, fontWeight = FontWeight.Medium),
                keyboardType = when { number -> KeyboardType.NumberPassword; url -> KeyboardType.Uri; email -> KeyboardType.Email; else -> KeyboardType.Text },
                imeAction = if (title.contains("search", true)) ImeAction.Search else ImeAction.Done,
                secret = secret,
                focusRequester = req(0, 0),
                onFocus = { if (it) cur = 0 to 0 },
                onSubmit = { onDone(text) },
                onScreenKeyboard = false,
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.CenterStart) {
                        inner()
                        if (!inputEditing) {
                            val placeholder = when { number -> "Enter digits"; url -> "http://"; else -> "Start typing…" }
                            TypedText(if (secret) "•".repeat(text.length) else text, placeholder)
                        }
                    }
                },
            )
            Spacer(Modifier.height(18.dp))

            if (chips.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(KeyGap),
                ) {
                    chips.forEachIndexed { i, c ->
                        KbKey(c, Modifier.height(42.dp), req(chipRow, i), { cur = chipRow to i }, Tone.Func, 15.sp, chip = true) { type(c) }
                    }
                }
            }

            if (number) {
                Column(verticalArrangement = Arrangement.spacedBy(PadGap)) {
                    for (ri in 0 until 4) Row(horizontalArrangement = Arrangement.spacedBy(PadGap)) {
                        for (ci in 0 until 3) {
                            val label = padKeys[ri * 3 + ci]
                            val r = firstKeyRow + ri
                            KbKey(
                                label, Modifier.size(PadW, PadH), req(r, ci), { cur = r to ci },
                                tone = when (label) { "⌫" -> Tone.Func; "Done" -> Tone.Accent; else -> Tone.Char },
                                size = if (label.length == 1 && label[0].isDigit()) 30.sp else 22.sp,
                                onLongClick = if (label == "⌫") ({ update("") }) else null,
                            ) {
                                when (label) {
                                    "⌫" -> delete()
                                    "Done" -> onDone(text)
                                    else -> type(label)
                                }
                            }
                        }
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(KeyGap)) {
                    val rows = if (symbols) symbolRows else letterRows
                    rows.forEachIndexed { ri, row ->
                        val r = firstKeyRow + ri
                        val keys = row.map { it.toString() } + if (!symbols && ri == 2) listOf(punct) else emptyList()
                        Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                            keys.forEachIndexed { i, k ->
                                val label = if (!symbols && shift != ShiftState.Off) k.uppercase() else k
                                KbKey(label, Modifier.size(KeyW, KeyH), req(r, i), { cur = r to i }) { type(label) }
                            }
                        }
                    }
                    val r = firstKeyRow + 4
                    Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                        actions.forEachIndexed { i, (act, n) ->
                            val m = Modifier.width(span(n)).height(KeyH)
                            val fr = req(r, i)
                            val onFocused = { cur = r to i }
                            when (act) {
                                Act.Shift -> KbKey(
                                    if (shift == ShiftState.Lock) "⇪" else "⇧", m, fr, onFocused,
                                    tone = if (shift != ShiftState.Off && !symbols) Tone.Active else Tone.Func, size = 24.sp,
                                ) {
                                    shift = when (shift) { ShiftState.Off -> ShiftState.Once; ShiftState.Once -> ShiftState.Lock; ShiftState.Lock -> ShiftState.Off }
                                }
                                Act.Symbols -> KbKey(if (symbols) "abc" else "#+=", m, fr, onFocused, Tone.Func, 18.sp) { symbols = !symbols }
                                Act.Voice -> KbKey("🎤", m, fr, onFocused, Tone.Func, 22.sp) { listen() }
                                Act.Space -> KbKey("space", m, fr, onFocused, Tone.Func, 18.sp) { type(" ") }
                                Act.Delete -> KbKey("⌫", m, fr, onFocused, Tone.Func, 22.sp, onLongClick = { update("") }) { delete() }
                                Act.Clear -> KbKey("Clear", m, fr, onFocused, Tone.Func, 16.sp) { update("") }
                                Act.Done -> KbKey("Done", m, fr, onFocused, Tone.Accent, 18.sp) { onDone(text) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            T(
                if (number) "Hold ⌫ to clear  ·  Back to cancel"
                else "Hold ⌫ to clear  ·  Back to cancel  ·  OK on the text opens the Shield keyboard",
                size = 12.sp, color = C.faint, maxLines = 1,
            )
        }
        LaunchedEffect(Unit) {
            repeat(20) {
                if (runCatching { req(startRow, 0).requestFocus() }.isSuccess) return@LaunchedEffect
                delay(16)
            }
        }
    }
}

/** What has been typed, with a blinking caret; scrolls so the end of a long address stays in view. */
@Composable
private fun TypedText(shown: String, placeholder: String) {
    val scroll = rememberScrollState()
    LaunchedEffect(scroll) { snapshotFlow { scroll.maxValue }.collect { scroll.scrollTo(it) } }
    var caretOn by remember { mutableStateOf(true) }
    LaunchedEffect(shown) {
        caretOn = true
        while (true) { delay(530); caretOn = !caretOn }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
        if (shown.isNotEmpty()) {
            // Non-breaking spaces, so a trailing space still moves the caret.
            BasicText(
                shown.replace(' ', ' '),
                style = TextStyle(color = C.text, fontSize = 28.sp, fontWeight = FontWeight.Medium),
                softWrap = false, maxLines = 1,
            )
        }
        Box(
            Modifier.padding(start = 2.dp).width(3.dp).height(34.dp)
                .background(if (caretOn) C.accent else Color.Transparent, RoundedCornerShape(2.dp)),
        )
        if (shown.isEmpty()) T(placeholder, Modifier.padding(start = 8.dp), size = 28.sp, color = C.faint, maxLines = 1)
    }
}

@Composable
private fun KbKey(
    label: String, modifier: Modifier, fr: FocusRequester, onFocused: () -> Unit,
    tone: Tone = Tone.Char, size: TextUnit = 22.sp, onLongClick: (() -> Unit)? = null, chip: Boolean = false,
    onClick: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    Focusable(
        onClick = onClick,
        modifier = modifier.onPreviewKeyEvent { e ->
            if (e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter) pressed = e.type == KeyEventType.KeyDown
            false
        },
        shape = KeyShape,
        focusedScale = if (chip) 1.06f else 1.1f,
        focusRequester = fr,
        onFocused = onFocused,
        onLongClick = onLongClick,
        showBorder = false,
    ) { f ->
        val down = pressed && f
        val press by animateFloatAsState(if (down) 0.9f else 1f, label = "key-press")
        val bg = when {
            down -> Color(0xFFD4D4DA)
            f -> C.focus
            tone == Tone.Accent -> C.accent
            tone == Tone.Active -> C.accent.copy(alpha = 0.45f)
            tone == Tone.Func -> Color(0x1AFFFFFF)
            else -> Color(0x2BFFFFFF)
        }
        val fg = if (f || tone == Tone.Accent) Color.Black else C.text
        Box(
            Modifier.fillMaxHeight()
                .then(if (chip) Modifier else Modifier.fillMaxWidth())
                .graphicsLayer { scaleX = press; scaleY = press }
                .clip(KeyShape)
                .background(bg)
                .then(if (chip) Modifier.padding(horizontal = 16.dp) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            T(label, size = size, weight = if (tone == Tone.Char) FontWeight.Medium else FontWeight.SemiBold, color = fg, maxLines = 1)
        }
    }
}
