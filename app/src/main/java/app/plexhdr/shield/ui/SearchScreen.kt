package app.plexhdr.shield.ui

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import kotlinx.coroutines.delay
import org.json.JSONArray

/** The last 10 searches, newest first. */
object SearchHistory {
    private const val MAX = 10
    private val prefs get() = App.instance.getSharedPreferences("search", Context.MODE_PRIVATE)

    var items by mutableStateOf(read()); private set

    private fun read(): List<String> = runCatching {
        val a = JSONArray(prefs.getString("recent", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optString(it).ifBlank { null } }
    }.getOrDefault(emptyList())

    private fun write() = prefs.edit().putString("recent", JSONArray(items).toString()).apply()

    fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        items = (listOf(q) + items.filter { !it.equals(q, ignoreCase = true) }).take(MAX)
        write()
    }

    fun clear() { items = emptyList(); write() }
}

/**
 * Search: Lumen's keyboard, or voice (the 🎤 pill, or the remote's search / mic key if it reaches the app); results as
 * you type. With nothing typed, the recent searches.
 */
@Composable
fun SearchScreen(nav: Nav) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<PlexItem>?>(emptyList()) }
    var shownFor by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val changes = CardMenu.changes
    LaunchedEffect(query, changes) {
        if (query.length < 2) { results = emptyList(); shownFor = ""; return@LaunchedEffect }
        // A refresh after the card menu changed something keeps the results (and focus) on screen meanwhile.
        val refresh = shownFor == query
        if (!refresh) delay(350)
        if (!refresh) results = null
        val got = runCatching { App.plex.search(query) }.getOrNull()
        if (got != null || !refresh) { results = got ?: emptyList(); shownFor = query }
    }

    val ctx = LocalContext.current
    val voiceIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Search Plex")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }
    val voiceAvailable = remember { runCatching { ctx.packageManager.queryIntentActivities(voiceIntent, 0).isNotEmpty() }.getOrDefault(false) }
    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) { query = text; SearchHistory.add(text) }
    }
    fun listen() { if (voiceAvailable) runCatching { voice.launch(voiceIntent) } }

    // With recent searches to show, don't cover them with the keyboard straight away.
    val history = SearchHistory.items
    val startTyping = remember { history.isEmpty() }
    LaunchedEffect(Unit) { if (!startTyping) { delay(50); runCatching { field.requestFocus() } } }
    val open: (PlexItem) -> Unit = { SearchHistory.add(query); nav.open(it) }

    Column(
        Modifier.fillMaxSize().padding(top = 100.dp).onPreviewKeyEvent { e ->
            val code = e.nativeKeyEvent.keyCode
            val isVoiceKey = e.key == Key.Search || code == android.view.KeyEvent.KEYCODE_SEARCH ||
                code == android.view.KeyEvent.KEYCODE_VOICE_ASSIST
            if (isVoiceKey && voiceAvailable) { if (e.type == KeyEventType.KeyUp) listen(); true } else false
        },
    ) {
        Row(Modifier.padding(horizontal = D.gutter), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.fillMaxWidth(0.6f).border(2.dp, C.dim, RoundedCornerShape(12.dp))
                .background(C.surface, RoundedCornerShape(12.dp)).padding(16.dp)) {
                if (query.isEmpty()) T("Movies, shows, episodes, collections", size = 20.sp, color = C.faint)
                TvTextField(query, { query = it }, Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = C.text, fontSize = 20.sp), imeAction = ImeAction.Search, title = "Search",
                    startEditing = startTyping, focusRequester = field, onSubmit = { SearchHistory.add(query) })
            }
            if (voiceAvailable) {
                Spacer(Modifier.width(16.dp))
                Pill("Voice", { listen() }, icon = "🎤")
            }
        }
        val r = results
        when {
            r == null -> Loading("Searching…")
            query.length < 2 -> RecentSearches(history) { query = it; SearchHistory.add(it) }
            r.isEmpty() -> Box(Modifier.padding(D.gutter)) { T("No results.", color = C.dim) }
            else -> {
                val groups = r.groupBy { it.type }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    listOf("movie" to "Movies", "show" to "Shows", "episode" to "Episodes", "collection" to "Collections").forEach { (t, label) ->
                        groups[t]?.let { ItemShelf(label, it, open, wide = t == "episode") }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentSearches(history: List<String>, pick: (String) -> Unit) {
    Column(Modifier.padding(top = 28.dp)) {
        if (history.isEmpty()) {
            T("Your recent searches will show here.", Modifier.padding(horizontal = D.gutter), size = 16.sp, color = C.dim)
            return@Column
        }
        Row(Modifier.padding(horizontal = D.gutter), verticalAlignment = Alignment.CenterVertically) {
            T("Recent searches", size = 20.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(20.dp))
            Pill("Clear", { SearchHistory.clear() })
        }
        Spacer(Modifier.height(4.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(history, key = { it }) { q -> Pill(q, { pick(q) }, icon = "🕘") }
        }
    }
}
