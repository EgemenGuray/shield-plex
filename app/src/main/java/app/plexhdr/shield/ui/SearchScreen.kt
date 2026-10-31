package app.plexhdr.shield.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import kotlinx.coroutines.delay

/** Search: the Shield's keyboard (or voice from the remote's mic button through the keyboard); results as you type. */
@Composable
fun SearchScreen(nav: Nav) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlexItem>?>(emptyList()) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    LaunchedEffect(query) {
        if (query.length < 2) { results = emptyList(); return@LaunchedEffect }
        delay(350)
        results = null
        results = runCatching { App.plex.search(query) }.getOrDefault(emptyList())
    }
    Column(Modifier.fillMaxSize().padding(top = 100.dp)) {
        Box(Modifier.padding(horizontal = D.gutter).fillMaxWidth(0.6f).border(2.dp, C.dim, RoundedCornerShape(12.dp))
            .background(C.surface, RoundedCornerShape(12.dp)).padding(16.dp)) {
            if (query.isEmpty()) T("Movies, shows, episodes, collections", size = 20.sp, color = C.faint)
            BasicTextField(query, { query = it }, Modifier.fillMaxWidth().focusRequester(field),
                textStyle = TextStyle(color = C.text, fontSize = 20.sp), singleLine = true, cursorBrush = SolidColor(C.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        }
        val r = results
        when {
            r == null -> Loading("Searching…")
            r.isEmpty() && query.length >= 2 -> Box(Modifier.padding(D.gutter)) { T("No results.", color = C.dim) }
            else -> {
                val groups = r.groupBy { it.type }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf("movie" to "Movies", "show" to "Shows", "episode" to "Episodes", "collection" to "Collections").forEach { (t, label) ->
                        groups[t]?.let { ItemShelf(label, it, nav::open, wide = t == "episode") }
                    }
                }
            }
        }
    }
}
