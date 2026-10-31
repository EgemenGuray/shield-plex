package app.plexhdr.shield.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.services.Online

/**
 * Ratings with their logos, in this order: IMDb, Letterboxd, Tomatometer, RT Audience, Metacritic (users), Metascore.
 * The Rotten Tomatoes logos change with the score (fresh tomato / green splat, full / tipped popcorn), and the
 * Metacritic badges take Metacritic's green / yellow / red. Logos are drawn by the app; no images are downloaded.
 */
@Composable
fun RatingsRow(r: Online.Ratings, modifier: Modifier = Modifier) {
    if (r.isEmpty) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
        r.imdb?.let { Rating({ ImdbLogo() }, it) }
        r.letterboxd?.let { Rating({ LetterboxdLogo() }, it) }
        r.rtCritic?.let { v -> Rating({ TomatoLogo(pctValue(v) >= 60) }, v) }
        r.rtAudience?.let { v -> Rating({ PopcornLogo(pctValue(v) >= 60) }, v) }
        r.metaUser?.let { v -> Rating({ MetacriticUserLogo(v) }, v, "Metacritic") }
        r.metaCritic?.let { v -> Rating({ MetascoreLogo(v) }, null, "Metascore") }
    }
}

private fun pctValue(s: String) = s.trimEnd('%').toDoubleOrNull() ?: 0.0

@Composable
private fun Rating(logo: @Composable () -> Unit, value: String?, caption: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        logo()
        if (value != null) { Spacer(Modifier.width(7.dp)); T(value, size = 16.sp, weight = FontWeight.SemiBold) }
        if (caption != null) { Spacer(Modifier.width(6.dp)); T(caption, size = 12.sp, color = C.dim) }
    }
}

@Composable
private fun ImdbLogo() {
    Box(Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xFFF5C518)).padding(horizontal = 5.dp, vertical = 1.dp)) {
        T("IMDb", size = 14.sp, weight = FontWeight.Black, color = Color.Black)
    }
}

@Composable
private fun LetterboxdLogo() {
    Canvas(Modifier.size(width = 34.dp, height = 14.dp)) {
        val r = size.height / 2
        val colors = listOf(Color(0xFFFF8000), Color(0xFF00E054), Color(0xFF40BCF4))
        colors.forEachIndexed { i, c -> drawCircle(c, r, Offset(r + i * (size.width - 2 * r) / 2, r)) }
    }
}

/** Fresh: a red tomato with a green leaf. Rotten (under 60%): a green splat. */
@Composable
private fun TomatoLogo(fresh: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        if (fresh) {
            drawCircle(Color(0xFFFA320A), w * 0.42f, Offset(w / 2, w * 0.56f))
            val leaf = Path().apply {
                moveTo(w * 0.5f, w * 0.18f); lineTo(w * 0.3f, w * 0.05f); lineTo(w * 0.42f, w * 0.22f)
                lineTo(w * 0.22f, w * 0.26f); lineTo(w * 0.5f, w * 0.3f); lineTo(w * 0.78f, w * 0.26f)
                lineTo(w * 0.58f, w * 0.22f); lineTo(w * 0.7f, w * 0.05f); close()
            }
            drawPath(leaf, Color(0xFF00912D))
        } else {
            val g = Color(0xFF0AC855)
            drawCircle(g, w * 0.32f, Offset(w * 0.5f, w * 0.55f))
            listOf(Offset(0.15f, 0.3f), Offset(0.85f, 0.35f), Offset(0.2f, 0.85f), Offset(0.82f, 0.82f), Offset(0.5f, 0.12f))
                .forEach { drawCircle(g, w * 0.11f, Offset(it.x * w, it.y * w)) }
        }
    }
}

/** Upright popcorn bucket (60% and over), tipped green bucket below. */
@Composable
private fun PopcornLogo(upright: Boolean) {
    Canvas(Modifier.size(20.dp)) {
        val w = size.width
        rotate(if (upright) 0f else -100f) {
            val bucket = Path().apply {
                moveTo(w * 0.18f, w * 0.38f); lineTo(w * 0.82f, w * 0.38f); lineTo(w * 0.7f, w * 0.98f); lineTo(w * 0.3f, w * 0.98f); close()
            }
            drawPath(bucket, if (upright) Color(0xFFFA320A) else Color(0xFF0AC855))
            // Stripes
            for (i in 1..2) {
                val x = w * (0.18f + i * 0.213f)
                drawRect(Color.White, Offset(x - w * 0.04f, w * 0.4f), Size(w * 0.08f, w * 0.55f))
            }
            val corn = Color(0xFFFFF4D6)
            listOf(0.28f, 0.5f, 0.72f).forEach { drawCircle(corn, w * 0.14f, Offset(it * w, w * 0.3f)) }
            drawCircle(corn, w * 0.13f, Offset(0.4f * w, w * 0.16f)); drawCircle(corn, w * 0.13f, Offset(0.62f * w, w * 0.16f))
        }
    }
}

private fun metaColor(score: Double, outOf: Double): Color {
    val s = score / outOf * 100
    return when {
        s >= 61 -> Color(0xFF00CE7A)
        s >= 40 -> Color(0xFFFFBD3F)
        else -> Color(0xFFFF6874)
    }
}

/** Metacritic user score (0–10): Metacritic's round mark in the score colour. */
@Composable
private fun MetacriticUserLogo(v: String) {
    val c = metaColor(v.toDoubleOrNull() ?: 0.0, 10.0)
    Box(Modifier.size(22.dp).clip(CircleShape).background(c), contentAlignment = Alignment.Center) {
        T("m", size = 14.sp, weight = FontWeight.Black, color = Color.Black)
    }
}

/** Metascore (0–100): the number on a square in Metacritic's score colour. */
@Composable
private fun MetascoreLogo(v: String) {
    val c = metaColor(v.toDoubleOrNull() ?: 0.0, 100.0)
    Box(Modifier.size(28.dp).clip(RoundedCornerShape(5.dp)).background(c), contentAlignment = Alignment.Center) {
        T(v, size = 14.sp, weight = FontWeight.Bold, color = Color.Black)
    }
}
