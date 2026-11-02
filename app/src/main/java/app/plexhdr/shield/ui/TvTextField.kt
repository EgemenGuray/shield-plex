package app.plexhdr.shield.ui

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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
 * A text field that behaves on a remote: moving over it doesn't pop up the keyboard or trap the D-pad.
 * OK opens the keyboard; the keyboard's Done / Search key (or Up / Down) closes it and gives the D-pad back.
 * [startEditing] opens the keyboard straight away, for dialogs that exist only to type.
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    fun finish() { editing = false; keyboard?.hide() }
    LaunchedEffect(editing) {
        if (editing) {
            runCatching { focusRequester.requestFocus() }
            delay(60)   // after the field is editable again
            keyboard?.show()
        }
    }
    BasicTextField(
        value, onValueChange,
        modifier
            .focusRequester(focusRequester)
            .onFocusChanged { onFocus(it.isFocused); if (!it.isFocused) editing = false }
            .onPreviewKeyEvent { e ->
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                when {
                    !editing && ok -> { if (e.type == KeyEventType.KeyUp) editing = true; true }
                    // Not typing: the arrows move between controls instead of the cursor.
                    !editing && e.type == KeyEventType.KeyDown -> when (e.key) {
                        Key.DirectionLeft -> focusManager.moveFocus(FocusDirection.Left)
                        Key.DirectionRight -> focusManager.moveFocus(FocusDirection.Right)
                        Key.DirectionUp -> focusManager.moveFocus(FocusDirection.Up)
                        Key.DirectionDown -> focusManager.moveFocus(FocusDirection.Down)
                        else -> false
                    }
                    // Typing (keyboard closed with Back): up / down stop typing and move on.
                    editing && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> {
                        finish()
                        focusManager.moveFocus(if (e.key == Key.DirectionUp) FocusDirection.Up else FocusDirection.Down)
                        true
                    }
                    else -> false
                }
            },
        readOnly = !editing,
        textStyle = textStyle,
        cursorBrush = SolidColor(if (editing) C.accent else androidx.compose.ui.graphics.Color.Transparent),
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(
            onDone = { finish(); onSubmit() }, onSearch = { finish(); onSubmit() }, onGo = { finish(); onSubmit() },
        ),
        decorationBox = decorationBox,
    )
}
