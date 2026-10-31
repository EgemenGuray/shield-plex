package app.plexhdr.shield.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexItem
import coil3.compose.AsyncImage

@Composable
fun T(
    text: String, modifier: Modifier = Modifier, size: TextUnit = 16.sp, color: Color = C.text,
    weight: FontWeight = FontWeight.Normal, maxLines: Int = Int.MAX_VALUE, align: TextAlign = TextAlign.Start,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    BasicText(
        text, modifier,
        style = TextStyle(color = color, fontSize = size, fontWeight = weight, textAlign = align, lineHeight = lineHeight),
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** A focusable, clickable box that lifts and grows when focused (Apple TV style). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Focusable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(10.dp),
    focusedScale: Float = 1.07f,
    focusRequester: FocusRequester? = null,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    showBorder: Boolean = true,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "focus-scale")
    Box(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(if (focused) 20.dp else 0.dp, shape)
            .clip(shape)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                if (it.isFocused && !focused) onFocused()
                focused = it.isFocused
            }
            .combinedClickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onLongClick = onLongClick, onClick = onClick,
            )
            .then(if (showBorder && focused) Modifier.border(3.dp, C.focus, shape) else Modifier),
    ) { content(focused) }
}

@Composable
fun Img(url: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop) {
    if (url == null) Box(modifier.background(C.surface)) else AsyncImage(model = url, contentDescription = null, modifier = modifier, contentScale = scale)
}

fun plexImage(path: String?, w: Int, h: Int): String? = App.plex.imageUrl(path, w, h)

/** A text button: white when focused, translucent otherwise. */
@Composable
fun Pill(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null,
    selected: Boolean = false, onFocused: () -> Unit = {}, icon: String? = null,
) {
    Focusable(onClick, modifier, RoundedCornerShape(50), 1.06f, focusRequester, onFocused, showBorder = false) { f ->
        Row(
            Modifier
                .background(if (f) C.focus else if (selected) C.surfaceHi else Color(0x33FFFFFF))
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { T(icon, size = 16.sp, color = if (f) Color.Black else C.text); Spacer(Modifier.width(8.dp)) }
            T(label, size = 16.sp, weight = FontWeight.SemiBold, color = if (f) Color.Black else C.text, maxLines = 1)
        }
    }
}

@Composable
fun Badge(text: String) {
    Box(Modifier.border(1.dp, C.dim, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        T(text, size = 12.sp, color = C.dim, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: Color = C.accent) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x55FFFFFF))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** Poster card (2:3). */
@Composable
fun PosterCard(item: PlexItem, onClick: () -> Unit, onFocused: () -> Unit = {}, modifier: Modifier = Modifier, focusRequester: FocusRequester? = null) {
    Column(modifier.width(D.posterW)) {
        Focusable(onClick, Modifier.size(D.posterW, D.posterH), focusRequester = focusRequester, onFocused = onFocused) {
            Img(plexImage(item.posterPath, 300, 450), Modifier.fillMaxSize())
            if (item.isWatched) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(14.dp).clip(RoundedCornerShape(50)).background(C.accent))
            item.progress?.let { ProgressBar(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        T(item.displayTitle, size = 13.sp, color = C.dim, maxLines = 1)
    }
}

/** Wide card (16:9) for Continue Watching, episodes, extras and chapters. */
@Composable
fun WideCard(
    title: String, subtitle: String?, image: String?, progress: Float?, onClick: () -> Unit,
    onFocused: () -> Unit = {}, modifier: Modifier = Modifier, width: Dp = D.wideW, focusRequester: FocusRequester? = null,
) {
    Column(modifier.width(width)) {
        Focusable(onClick, Modifier.width(width).height(width * 9 / 16), focusRequester = focusRequester, onFocused = onFocused) {
            Img(image, Modifier.fillMaxSize())
            if (progress != null) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(10.dp))
        }
        Spacer(Modifier.height(8.dp))
        T(title, size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
        if (subtitle != null) T(subtitle, size = 12.sp, color = C.dim, maxLines = 2)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    T(text, modifier.padding(start = D.gutter, bottom = 12.dp), size = 20.sp, weight = FontWeight.SemiBold)
}

/** A titled row of items. */
@Composable
fun <E> Shelf(
    title: String?, items: List<E>, key: (E) -> Any, modifier: Modifier = Modifier,
    content: @Composable (E) -> Unit,
) {
    if (items.isEmpty()) return
    Column(modifier.padding(vertical = 10.dp)) {
        if (title != null) SectionTitle(title)
        LazyRow(
            contentPadding = PaddingValues(horizontal = D.gutter, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            items(items, key = key) { content(it) }
        }
    }
}

@Composable
fun ItemShelf(title: String, items: List<PlexItem>, open: (PlexItem) -> Unit, wide: Boolean = false, onFocused: (PlexItem) -> Unit = {}) {
    Shelf(title, items, { it.id }) { item ->
        if (wide) {
            val sub = item.episodeLabel?.let { "$it · ${item.title}" } ?: item.year?.toString()
            WideCard(item.displayTitle, sub, plexImage(item.backdropPath, 600, 338), item.progress, { open(item) }, { onFocused(item) })
        } else {
            PosterCard(item, { open(item) }, { onFocused(item) })
        }
    }
}

/** Full-screen artwork with a dark fade to the left and bottom, behind a page. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        Img(url, Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(listOf(Color(0xF00B0B0F), Color(0x990B0B0F), Color(0x000B0B0F)))))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.4f to Color.Transparent, 1f to C.bg)))
    }
}

@Composable
fun Loading(text: String = "Loading…") {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T(text, size = 18.sp, color = C.dim) }
}

@Composable
fun ErrorText(text: String) {
    Box(Modifier.fillMaxSize().padding(D.gutter), contentAlignment = Alignment.Center) { T(text, size = 18.sp, color = C.bad, align = TextAlign.Center) }
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, sec) else String.format("%d:%02d", m, sec)
}

fun formatRuntime(ms: Long?): String? {
    val m = ((ms ?: return null) / 60000).toInt()
    if (m <= 0) return null
    return if (m >= 60) "${m / 60} hr ${m % 60} min" else "$m min"
}
