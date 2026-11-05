package app.plexhdr.shield.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.focus.FocusRequester
import kotlinx.coroutines.delay
import java.util.UUID

/**
 * One page on the back stack. It outlives the page's composition, so coming back to a page finds what it had loaded
 * ([retain]) and puts focus back where it was, instead of reloading and starting at the top.
 */
class NavEntry(val screen: Screen) {
    /** Stable key for the page's saved state (scroll positions and other rememberSaveable values). */
    val id: String = UUID.randomUUID().toString()
    internal val store = HashMap<String, Any?>()

    /** Composite key hashes of the Focusables that had focus last on this page, newest last. */
    internal val focusHistory = ArrayList<Int>()
    /** The Focusable that has focus right now, if any. */
    internal var focusedNow: Int? = null
    /** Focus requesters of the Focusables on the page, by composite key hash. */
    internal val requesters = HashMap<Int, FocusRequester>()
    /** What the page focuses when first shown, used if the old focus can't be found. */
    internal var fallback: FocusRequester? = null
    internal var shown = 0
    /** True while this showing of the page puts back its old focus: the page's own first focus then stays off. */
    internal var restoring = false

    internal fun focused(key: Int) {
        focusedNow = key
        focusHistory.remove(key)
        focusHistory.add(key)
        if (focusHistory.size > 6) focusHistory.removeAt(0)
    }

    internal fun unfocused(key: Int) { if (focusedNow == key) focusedNow = null }

    /** Called as the page is shown (again). */
    internal fun onShow() {
        restoring = shown > 0 && focusHistory.isNotEmpty()
        focusedNow = null
        shown++
    }

    /** Requests focus on the element that had it, retrying until the page has drawn it (data may still be coming). */
    internal suspend fun restoreFocus() {
        val history = focusHistory.asReversed().toList()
        for (attempt in 0 until 90) {   // about 3 s
            // Wait for the exact element first; after a while settle for an older one that is there.
            val keys = if (attempt < 45) history.take(1) else history
            val r = keys.firstNotNullOfOrNull { requesters[it] }
            if (r != null) runCatching { r.requestFocus() }
            delay(34)
            if (focusedNow != null) return
        }
        restoring = false
        fallback?.let { f -> runCatching { f.requestFocus() } }
    }
}

/** The back stack entry of the page being composed (null outside the pages, e.g. in the top bar). */
val LocalNavEntry = staticCompositionLocalOf<NavEntry?> { null }

/**
 * A value the page keeps while you're on another page and gets back when you return, like `remember` across leaving
 * and coming back. Use it for loaded data: `var items by retain("items") { mutableStateOf(emptyList<PlexItem>()) }`.
 */
@Composable
fun <T> retain(key: String, init: () -> T): T {
    val entry = LocalNavEntry.current
    return remember(entry, key) {
        @Suppress("UNCHECKED_CAST")
        if (entry == null) init() else entry.store.getOrPut(key) { init() } as T
    }
}

/**
 * Focuses [requester] when the page is shown (and again when [key] changes, after [delayMs]), except when coming back
 * to the page: focus then returns to the element that had it.
 */
@Composable
fun FocusOnShow(requester: FocusRequester, key: Any? = Unit, delayMs: Long = 0) {
    val entry = LocalNavEntry.current
    remember(entry, requester) { entry?.fallback = requester; 0 }
    LaunchedEffect(key) {
        if (entry?.restoring == true) return@LaunchedEffect
        if (delayMs > 0) delay(delayMs)
        runCatching { requester.requestFocus() }
    }
}
