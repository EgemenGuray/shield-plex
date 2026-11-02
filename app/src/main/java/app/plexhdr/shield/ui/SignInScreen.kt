package app.plexhdr.shield.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plexhdr.shield.App
import app.plexhdr.shield.plex.PlexClient
import app.plexhdr.shield.services.Qr
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Sign-in with a 4-character code at plex.tv/link, or by scanning a QR code with a phone. */
@Composable
fun SignInScreen() {
    val plex = App.plex
    var pin by remember { mutableStateOf<PlexClient.Pin?>(null) }
    var strong by remember { mutableStateOf<PlexClient.Pin?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        error = null
        try {
            pin = plex.createPin(strong = false)
            strong = runCatching { plex.createPin(strong = true) }.getOrNull()
            // Codes expire after 15 minutes; make new ones before that.
            val started = System.currentTimeMillis()
            while (System.currentTimeMillis() - started < 14 * 60_000) {
                delay(2000)
                val a = pin?.let { runCatching { plex.checkPin(it) }.getOrDefault(false) } ?: false
                if (a) return@LaunchedEffect
                val b = strong?.let { runCatching { plex.checkPin(it) }.getOrDefault(false) } ?: false
                if (b) return@LaunchedEffect
            }
            attempt++
        } catch (e: Exception) {
            error = "Couldn't reach plex.tv: ${e.message}"
        }
    }

    Row(Modifier.fillMaxSize().padding(80.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T("Lumen", size = 44.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            T("for SHIELD · lossless passthrough", size = 18.sp, color = C.accent)
            Spacer(Modifier.height(40.dp))
            T("On your phone or computer, go to", size = 20.sp, color = C.dim)
            T("plex.tv/link", size = 34.sp, weight = FontWeight.Bold)
            Spacer(Modifier.height(18.dp))
            T("and enter this code:", size = 20.sp, color = C.dim)
            Spacer(Modifier.height(10.dp))
            T(pin?.code ?: "····", size = 72.sp, weight = FontWeight.Bold, color = C.text)
            Spacer(Modifier.height(30.dp))
            error?.let {
                T(it, size = 16.sp, color = C.bad)
                Spacer(Modifier.height(16.dp))
                val fr = remember { FocusRequester() }
                Pill("Try again", { attempt++ }, focusRequester = fr)
                LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
            }
            Spacer(Modifier.height(10.dp))
            Pill("Live TV only (IPTV, no Plex)", { App.settings.iptvOnly = true })
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val qr = remember(strong) { strong?.let { Qr.bitmap(plex.qrUrl(it), 480).asImageBitmap() } }
            Box(Modifier.size(320.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
                if (qr != null) Image(qr, null, Modifier.size(300.dp)) else T("…", color = Color.Black)
            }
            Spacer(Modifier.height(16.dp))
            T("Or scan with your phone\nand sign in to Plex", size = 18.sp, color = C.dim, align = TextAlign.Center)
        }
    }
}

/** Picks a server after sign-in (and from Settings). */
@Composable
fun ServerPicker(onDone: () -> Unit = {}) {
    val plex = App.plex
    val scope = rememberCoroutineScope()
    var servers by remember { mutableStateOf<List<JSONObject>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        servers = runCatching { plex.listServers() }.onFailure { status = it.message }.getOrDefault(emptyList())
        // One server: connect straight away.
        servers?.singleOrNull()?.let { only ->
            status = "Connecting to ${only.optString("name")}…"
            runCatching { plex.connect(only); onDone() }.onFailure { status = it.message }
        }
    }
    Column(Modifier.fillMaxSize().padding(80.dp)) {
        T("Choose a Plex server", size = 34.sp, weight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        status?.let { T(it, size = 16.sp, color = C.dim); Spacer(Modifier.height(16.dp)) }
        val list = servers
        if (list == null) T("Looking for servers…", color = C.dim)
        else if (list.isEmpty()) {
            T("No servers on this Plex account.", color = C.dim)
            Spacer(Modifier.height(16.dp))
            Pill("Sign out", { plex.signOut() })
        } else {
            val first = remember { FocusRequester() }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list.size) { i ->
                    val s = list[i]
                    Pill("${s.optString("name")}${if (s.optBoolean("owned")) "" else " (shared)"}", {
                        status = "Connecting to ${s.optString("name")}…"
                        scope.launch { runCatching { plex.connect(s); onDone() }.onFailure { status = it.message } }
                    }, Modifier.fillMaxWidth(0.5f), focusRequester = if (i == 0) first else null)
                }
            }
            LaunchedEffect(list) { runCatching { first.requestFocus() } }
        }
    }
}
