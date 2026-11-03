package app.plexhdr.shield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

private val lower = listOf("abcdefghijklm", "nopqrstuvwxyz", "1234567890-_.")
private val upper = listOf("ABCDEFGHIJKLM", "NOPQRSTUVWXYZ", "1234567890-_.")
private val symbols = listOf("/:@?=&%#+~!$", "*()[]{}<>,;'\"", "1234567890-_.")
private val digits = listOf("123", "456", "789", "0")
private val urlSnippets = listOf("http://", "https://", ".com", ".net", ":8080", "/get.php?", "username=", "&password=")

/**
 * Text entry for the remote, like the Tizen and Apple TV keyboards: the keys are on screen, so
 * Delete, Clear and Done are always one press away. The remote's number keys and its delete key
 * type too, and OK on the text box opens the Shield's own keyboard (or phone typing) instead.
 */
@Composable
fun TextEntryDialog(
    title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit,
    number: Boolean = false, secret: Boolean = false, url: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    var mode by remember { mutableStateOf(0) }   // 0 abc, 1 ABC, 2 symbols
    val firstKey = remember { FocusRequester() }
    fun type(s: String) { text += s }
    fun delete() { text = text.dropLast(1) }
    Dialog(onDismiss, DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.width(820.dp).clip(RoundedCornerShape(20.dp)).background(C.surface).padding(28.dp)
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val k = e.key
                    val digit = listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine).indexOf(k)
                    when {
                        digit >= 0 -> { type(digit.toString()); true }
                        k == Key.Backspace || k == Key.MediaRewind -> { delete(); true }
                        else -> false
                    }
                },
        ) {
            T(title, size = 24.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            TvTextField(
                text, { text = it },
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(C.surfaceHi).padding(16.dp),
                textStyle = TextStyle(color = C.text, fontSize = 20.sp),
                keyboardType = if (number) KeyboardType.NumberPassword else if (url) KeyboardType.Uri else KeyboardType.Text,
                secret = secret, onSubmit = { onDone(text) },
            )
            Spacer(Modifier.height(18.dp))
            if (number) {
                digits.forEachIndexed { r, row -> KeyRow(row.map { it.toString() }, 64.dp, if (r == 0) firstKey else null) { type(it) } }
            } else {
                val rows = when (mode) { 1 -> upper; 2 -> symbols; else -> lower }
                rows.forEachIndexed { r, row -> KeyRow(row.map { it.toString() }, 52.dp, if (r == 0) firstKey else null) { type(it) } }
                if (url) KeyRow(urlSnippets, null, null) { type(it) }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!number) {
                    Key(when (mode) { 0 -> "ABC"; 1 -> "#+="; else -> "abc" }, 92.dp) { mode = (mode + 1) % 3 }
                    Key("Space", 150.dp) { type(" ") }
                }
                Key("⌫ Delete", 120.dp) { delete() }
                Key("Clear", 92.dp) { text = "" }
                Key("Cancel", 100.dp) { onDismiss() }
                Key("✓ Done", 120.dp, accent = true) { onDone(text) }
            }
            Spacer(Modifier.height(10.dp))
            T("OK on the text box opens the Shield keyboard · number keys type digits", size = 12.sp, color = C.faint)
        }
        LaunchedEffect(Unit) { repeat(20) { if (runCatching { firstKey.requestFocus() }.isSuccess) return@LaunchedEffect; delay(16) } }
    }
}

@Composable
private fun KeyRow(keys: List<String>, width: Dp?, focus: FocusRequester?, onKey: (String) -> Unit) {
    Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.forEachIndexed { i, k -> Key(k, width, focus = if (i == 0) focus else null) { onKey(k) } }
    }
}

@Composable
private fun Key(label: String, width: Dp?, accent: Boolean = false, focus: FocusRequester? = null, onClick: () -> Unit) {
    Focusable(onClick, if (width != null) Modifier.width(width).height(48.dp) else Modifier.height(48.dp), RoundedCornerShape(8.dp), 1.1f, focus, showBorder = false) { f ->
        Box(
            Modifier.then(if (width != null) Modifier.size(width, 48.dp) else Modifier.height(48.dp))
                .background(if (f) C.focus else if (accent) C.accent.copy(alpha = 0.35f) else C.surfaceHi)
                .padding(horizontal = if (width == null) 14.dp else 0.dp),
            contentAlignment = Alignment.Center,
        ) { T(label, size = 18.sp, weight = FontWeight.SemiBold, color = if (f) Color.Black else C.text, maxLines = 1) }
    }
}
