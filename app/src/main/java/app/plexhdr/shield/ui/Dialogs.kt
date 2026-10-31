package app.plexhdr.shield.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class Choice(val label: String, val detail: String? = null, val selected: Boolean = false, val header: Boolean = false)

/** A list of choices over a dimmed page. Back closes it. */
@Composable
fun ChoiceDialog(title: String, choices: List<Choice>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    BackHandler(onBack = onDismiss)
    val first = remember { FocusRequester() }
    val focusIndex = choices.indexOfFirst { it.selected && !it.header }.takeIf { it >= 0 } ?: choices.indexOfFirst { !it.header }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(Modifier.width(720.dp).clip(RoundedCornerShape(20.dp)).background(C.surface).padding(28.dp)) {
            T(title, size = 24.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            LazyColumn(Modifier.height(520.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(choices.size) { i ->
                    val c = choices[i]
                    if (c.header) {
                        T(c.label, Modifier.padding(top = 12.dp, bottom = 4.dp), size = 14.sp, color = C.accent, weight = FontWeight.SemiBold)
                    } else {
                        Focusable({ onPick(i) }, Modifier.fillMaxWidth(), RoundedCornerShape(10.dp), 1.02f,
                            if (i == focusIndex) first else null, showBorder = false) { f ->
                            Column(Modifier.fillMaxWidth().background(if (f) C.focus else Color.Transparent).padding(horizontal = 16.dp, vertical = 10.dp)) {
                                T((if (c.selected) "✓  " else "    ") + c.label, size = 17.sp, color = if (f) Color.Black else C.text, maxLines = 2)
                                if (c.detail != null) T(c.detail, Modifier.padding(start = 26.dp), size = 13.sp, color = if (f) Color.DarkGray else C.dim, maxLines = 3)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

/** A text sheet (technical info). */
@Composable
fun TextDialog(title: String, lines: List<Pair<String, String>>, onDismiss: () -> Unit) {
    ChoiceDialog(title, lines.map { (k, v) -> if (v.isEmpty()) Choice(k, header = true) else Choice(k, v) }, { }, onDismiss)
}
