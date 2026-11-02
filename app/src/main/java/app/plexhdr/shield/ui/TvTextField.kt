package app.plexhdr.shield.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import kotlinx.coroutines.delay

/**
 * A text field that behaves on a remote.
 *
 * It is two layers: an outer box that the D-pad lands on like any button, and the real text field
 * inside, which can only take focus while typing. Pressing OK on the box hands focus to the inner
 * field, which starts a fresh input session and opens the keyboard. Done / Search on the keyboard,
 * or Up / Down after the keyboard was closed with Back, gives focus back to the box.
 *
 * (The previous version toggled `readOnly` on a field that already had focus. Compose doesn't
 * start an input session in that case, so the keyboard never came up and the arrows kept scrolling.)
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: TextStyle,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Done,
    secret: Boolean = false,
    startEditing: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
    onFocus: (Boolean) -> Unit = {},
    onSubmit: () -> Unit = {},
    decorationBox: @Composable (inner: @Composable () -> Unit) -> Unit = { it() },
) {
    var editing by remember { mutableStateOf(startEditing) }
    val field = remember { FocusRequester() }
    var fieldFocused by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    fun finish(moveTo: FocusDirection? = null) {
        editing = false
        keyboard?.hide()
        runCatching { focusRequester.requestFocus() }
        if (moveTo != null) focusManager.moveFocus(moveTo)
    }

    LaunchedEffect(editing) {
        if (!editing) return@LaunchedEffect
        delay(30)   // let focusProperties(canFocus = true) apply first
        runCatching { field.requestFocus() }
        // The input session starts on focus; ask for the keyboard a few times in case it isn't ready yet.
        for (wait in longArrayOf(50, 200, 500)) { delay(wait); keyboard?.show() }
    }

    Box(
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocus(it.hasFocus) }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    !editing && ok -> { if (e.type == KeyEventType.KeyUp) editing = true; true }
                    editing && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> {
                        finish(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down); true
                    }
                    else -> false
                }
            }
            .focusable(),
    ) {
        BasicTextField(
            value, onValueChange,
            Modifier.fillMaxWidth()
                .focusRequester(field)
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    // Leaving the field (Back out of a dialog, focus moved by code) ends typing.
                    if (fieldFocused && !it.isFocused) editing = false
                    fieldFocused = it.isFocused
                },
            textStyle = textStyle,
            cursorBrush = SolidColor(if (editing) C.accent else Color.Transparent),
            singleLine = true,
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(
                onDone = { finish(); onSubmit() }, onSearch = { finish(); onSubmit() }, onGo = { finish(); onSubmit() },
            ),
            decorationBox = decorationBox,
        )
    }
}
