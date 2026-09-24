@file:OptIn(UnstableApi::class)

package com.example.miaiptv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val PW = 460.dp          // larghezza anteprima
private val PH = 259.dp          // altezza anteprima (16:9)
private val ACCENT = Color(0xFF2E7DFF)
private val BG = Color(0xFF0B1220)
private val CARD = Color(0xFF131E36)
private val GRAY = Color(0xFFA9B2C3)
private val timeFmt = SimpleDateFormat("HH:mm", Locale.ITALY)
private fun hm(t: Long): String = timeFmt.format(Date(t))

class MainActivity : ComponentActivity() {
    private var started by mutableStateOf(true)
    override fun onStart() { super.onStart(); started = true }
    override fun onStop() { super.onStop(); started = false }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Se l'app crasha, salva l'errore e lo mostra alla prossima apertura
        val crashFile = File(filesDir, "crash.txt")
        val crash = if (crashFile.exists()) crashFile.readText().also { crashFile.delete() } else null
        val old = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile.writeText(e.stackTraceToString().take(1800)) }
            old?.uncaughtException(t, e)
        }
        val prefs = Prefs(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Root(prefs, crash, started) } }
    }
}

@Composable
fun Root(prefs: Prefs, crash: String?, started: Boolean) {
    val ctx = LocalContext.current
    var crashText by remember { mutableStateOf(crash) }
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var epg by remember { mutableStateOf<Map<String, List<Programme>>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val btnFocus = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        err = null
        try {
            val list = Repo.load(ctx)
            if (list.isEmpty()) throw IllegalStateException("Playlist vuota")
            channels = list
            epg = Epg.load(ctx, list)   // la guida arriva dopo, senza bloccare i canali
        } catch (e: Exception) {
            if (channels == null) err = e.message ?: "Errore di rete"
        }
    }

    Box(Modifier.fillMaxSize().background(BG), contentAlignment = Alignment.Center) {
        val list = channels
        val c = crashText
        when {
            c != null -> {
                Column(Modifier.padding(32.dp)) {
                    Text("L'app si era chiusa per un errore:", fontSize = 20.sp)
                    Text(c, fontSize = 10.sp, color = GRAY, maxLines = 22)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { crashText = null }, modifier = Modifier.focusRequester(btnFocus)) { Text("Continua") }
                }
                LaunchedEffect(Unit) { runCatching { btnFocus.requestFocus() } }
            }
            list != null -> IptvApp(list, epg, prefs, started)
            err != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Impossibile caricare i canali", fontSize = 24.sp)
                    Text(err!!, fontSize = 14.sp, color = GRAY)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(btnFocus)) { Text("Riprova") }
                }
                LaunchedEffect(err) { runCatching { btnFocus.requestFocus() } }
            }
            else -> Text("Caricamento canali…", fontSize = 20.sp)
        }
    }
}

fun nowNext(l: List<Programme>, t: Long): Pair<Programme?, Programme?> {
    val cur = l.firstOrNull { it.start <= t && t < it.stop }
    val nxt = l.firstOrNull { it.start >= (cur?.stop ?: t) }
    return cur to nxt
}

@Composable
fun IptvApp(channels: List<Channel>, epg: Map<String, List<Programme>>?, prefs: Prefs, started: Boolean) {
    val order = listOf("Rai", "Mediaset", "Discovery", "Sky", "Altro", "Radio")
    val groups = remember(channels) {
        listOf("Preferiti", "Tutti") +
            channels.map { it.group }.distinct().sortedBy { g -> order.indexOf(g).let { if (it < 0) 99 else it } }
    }
    var favs by remember { mutableStateOf(prefs.favs) }
    var failed by remember { mutableStateOf(setOf<String>()) }
    var groupIdx by remember { mutableIntStateOf(1) }
    var current by remember { mutableStateOf(channels.firstOrNull { it.url == prefs.last } ?: channels.first()) }
    var focusedCh by remember { mutableStateOf(current) }
    var previous by remember { mutableStateOf<Channel?>(null) }
    var fullscreen by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var playError by remember { mutableStateOf(false) }
    var infoTick by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf(false) }
    var numBuf by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val rootFocus = remember { FocusRequester() }

    fun epgOf(ch: Channel): List<Programme> = ch.keys.firstNotNullOfOrNull { epg?.get(it) } ?: emptyList()

    fun listFor(idx: Int): List<Channel> = when (idx) {
        0 -> channels.filter { it.url in favs }
        1 -> channels
        else -> channels.filter { it.group == groups[idx] }
    }

    fun play(c: Channel) {
        if (c.url != current.url) {
            if (fullscreen) previous = current
            current = c
            prefs.last = c.url
            playError = false
            buffering = true
        }
    }

    fun zap(d: Int) {
        var n = channels.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
        repeat(channels.size) {
            n = (n + d + channels.size) % channels.size
            if (channels[n].url !in failed) { play(channels[n]); return }
        }
    }

    fun toggleFav(c: Channel) {
        favs = if (c.url in favs) favs - c.url else favs + c.url
        prefs.favs = favs
    }

    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    LaunchedEffect(numBuf) {
        if (numBuf.isNotEmpty()) {
            delay(1500)
            numBuf.toIntOrNull()?.let { n -> channels.firstOrNull { it.number == n }?.let { play(it) } }
            numBuf = ""
        }
    }
    LaunchedEffect(current.url, infoTick, fullscreen) {
        if (fullscreen) { showInfo = true; delay(4500); showInfo = false }
    }
    // Nella guida l'anteprima segue il canale selezionato (dopo una breve pausa)
    LaunchedEffect(focusedCh.url, fullscreen) {
        if (!fullscreen && focusedCh.url != current.url) { delay(700); play(focusedCh) }
    }
    LaunchedEffect(fullscreen) { if (fullscreen) runCatching { rootFocus.requestFocus() } }
    BackHandler(enabled = fullscreen) { fullscreen = false; focusedCh = current }

    Box(Modifier.fillMaxSize().background(BG)) {
        VideoPlayer(
            channel = current, started = started,
            modifier = if (fullscreen) Modifier.fillMaxSize()
            else Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = 28.dp).size(PW, PH),
            onError = { playError = true; buffering = false; failed = failed + current.url },
            onState = { st ->
                buffering = st == Player.STATE_BUFFERING
                if (st == Player.STATE_READY) { playError = false; failed = failed - current.url }
            },
        )

        if (!fullscreen) {
            val shown = remember(groupIdx) { listFor(groupIdx) }
            val boxFocus = remember { FocusRequester() }
            Row(Modifier.fillMaxSize()) {
                // ---------- Colonna sinistra: categorie + canali ----------
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .padding(start = 28.dp, top = 20.dp, bottom = 10.dp, end = 12.dp)
                        .onPreviewKeyEvent { e ->
                            if (e.type == KeyEventType.KeyDown) when (e.nativeKeyEvent.keyCode) {
                                KeyEvent.KEYCODE_DPAD_LEFT -> { groupIdx = (groupIdx - 1 + groups.size) % groups.size; true }
                                KeyEvent.KEYCODE_DPAD_RIGHT -> { groupIdx = (groupIdx + 1) % groups.size; true }
                                else -> false
                            } else false
                        }
                        .focusRequester(boxFocus).focusable()
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Mia IPTV", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text(hm(now), fontSize = 22.sp, fontWeight = FontWeight.Light)
                    }
                    Spacer(Modifier.height(8.dp))
                    val tabs = rememberLazyListState()
                    LaunchedEffect(groupIdx) { tabs.animateScrollToItem(groupIdx) }
                    LazyRow(state = tabs) {
                        itemsIndexed(groups) { i, g ->
                            Text(
                                g, Modifier.padding(end = 8.dp)
                                    .background(if (i == groupIdx) ACCENT else Color(0x26FFFFFF), RoundedCornerShape(16.dp))
                                    .padding(horizontal = 14.dp, vertical = 5.dp),
                                fontSize = 13.sp,
                            )
                        }
                    }
                    Text("◀ ▶ categorie   ·   OK guarda   ·   OK lungo = preferito", fontSize = 11.sp, color = GRAY,
                        modifier = Modifier.padding(vertical = 6.dp))

                    key(groupIdx) {
                        val target = shown.indexOfFirst { it.url == current.url }.coerceAtLeast(0)
                        val ls = rememberLazyListState(initialFirstVisibleItemIndex = (target - 2).coerceAtLeast(0))
                        val itemFocus = remember { FocusRequester() }
                        LaunchedEffect(Unit) {
                            delay(80)
                            runCatching { if (shown.isEmpty()) boxFocus.requestFocus() else itemFocus.requestFocus() }
                        }
                        if (shown.isEmpty()) {
                            Text("Nessun canale qui.\nTieni premuto OK su un canale per aggiungerlo ai preferiti.",
                                fontSize = 15.sp, color = GRAY)
                        } else {
                            LazyColumn(state = ls) {
                                itemsIndexed(shown) { i, ch ->
                                    ChannelRow(
                                        ch, ch.url in favs, ch.url in failed, ch.url == current.url,
                                        nowNext(epgOf(ch), now).first, now,
                                        if (i == target) Modifier.focusRequester(itemFocus) else Modifier,
                                        onFocus = { focusedCh = ch },
                                        onPlay = { play(ch); focusedCh = ch; fullscreen = true },
                                        onFav = { toggleFav(ch) },
                                    )
                                }
                            }
                        }
                    }
                }
                // ---------- Colonna destra: anteprima (video sotto) + programma ----------
                Column(Modifier.width(PW + 56.dp).padding(top = 28.dp, end = 28.dp)) {
                    Spacer(Modifier.height(PH))
                    Spacer(Modifier.height(10.dp))
                    val (cur, nxt) = nowNext(epgOf(focusedCh), now)
                    Column(Modifier.fillMaxWidth().background(CARD, RoundedCornerShape(12.dp)).padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(focusedCh.logo, null, Modifier.size(34.dp), contentScale = ContentScale.Fit)
                            Spacer(Modifier.width(10.dp))
                            Text("${focusedCh.number}  ${focusedCh.name}", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (cur != null) {
                            Text("IN ONDA ORA", fontSize = 10.sp, color = ACCENT, fontWeight = FontWeight.Bold)
                            Text(cur.title, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${hm(cur.start)} – ${hm(cur.stop)}", fontSize = 12.sp, color = GRAY)
                            Spacer(Modifier.height(4.dp))
                            ProgressBar(((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f))
                            if (cur.desc.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(cur.desc, fontSize = 12.sp, color = GRAY, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Text(if (epg == null) "Caricamento guida programmi…" else "Guida programmi non disponibile",
                                fontSize = 13.sp, color = GRAY)
                        }
                        if (nxt != null) {
                            Spacer(Modifier.height(6.dp))
                            Text("DOPO  ${hm(nxt.start)}  ${nxt.title}", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        } else {
            // ---------- Schermo intero ----------
            Box(
                Modifier.fillMaxSize().focusRequester(rootFocus).focusable().onKeyEvent { e ->
                    val k = e.nativeKeyEvent.keyCode
                    val down = e.type == KeyEventType.KeyDown
                    val up = e.type == KeyEventType.KeyUp
                    when (k) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (down) zap(1); true }
                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) zap(-1); true }
                        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> { if (down) previous?.let { play(it) }; true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { if (up) infoTick++; true }
                        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> { if (up) { fullscreen = false; focusedCh = current }; true }
                        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                            if (down) numBuf = (numBuf + (k - KeyEvent.KEYCODE_0)).takeLast(4); true
                        }
                        else -> false
                    }
                }
            ) {
                if (buffering && !playError) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp))
                if (playError) {
                    Text("Canale non disponibile\nSu/Giù = cambia canale · Indietro = guida",
                        Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(20.dp),
                        fontSize = 18.sp)
                }
                if (numBuf.isNotEmpty()) {
                    Text(numBuf, Modifier.align(Alignment.TopEnd).padding(28.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 20.dp, vertical = 8.dp),
                        fontSize = 40.sp, fontWeight = FontWeight.Bold)
                }
                if (showInfo) {
                    val (cur, nxt) = nowNext(epgOf(current), now)
                    Row(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(28.dp)
                            .background(Color(0xE60B1220), RoundedCornerShape(14.dp)).padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AsyncImage(current.logo, null, Modifier.size(56.dp), contentScale = ContentScale.Fit)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${current.number}  ${current.name}" + if (current.url in favs) "  ★" else "",
                                fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            if (cur != null) {
                                Text("${cur.title}   ${hm(cur.start)} – ${hm(cur.stop)}", fontSize = 15.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                ProgressBar(((now - cur.start).toFloat() / (cur.stop - cur.start)).coerceIn(0f, 1f))
                            }
                            if (nxt != null) Text("Dopo: ${hm(nxt.start)}  ${nxt.title}", fontSize = 13.sp, color = GRAY,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ProgressBar(fraction: Float) {
    Box(Modifier.fillMaxWidth().height(3.dp).background(Color(0x33FFFFFF), RoundedCornerShape(2.dp))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF4FC3F7), RoundedCornerShape(2.dp)))
    }
}

@Composable
fun ChannelRow(
    ch: Channel, isFav: Boolean, isFailed: Boolean, isCurrent: Boolean, prog: Programme?, now: Long,
    modifier: Modifier, onFocus: () -> Unit, onPlay: () -> Unit, onFav: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longDone by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (focused) ACCENT else if (isCurrent) Color(0x22FFFFFF) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocus() }
            .focusable()
            .onKeyEvent { e ->
                val k = e.nativeKeyEvent.keyCode
                if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (e.nativeKeyEvent.repeatCount == 1) { longDone = true; onFav() }
                            true
                        }
                        KeyEventType.KeyUp -> { if (!longDone) onPlay(); longDone = false; true }
                        else -> false
                    }
                } else false
            }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(ch.number.toString(), Modifier.width(34.dp), fontSize = 14.sp, color = if (focused) Color.White else GRAY)
        AsyncImage(ch.logo, null, Modifier.size(38.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(ch.name, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                color = if (isFailed && !focused) Color(0xFF6B7385) else Color.White)
            if (prog != null) {
                Text(prog.title, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (focused) Color(0xFFDDE6FF) else GRAY)
                Spacer(Modifier.height(2.dp))
                ProgressBar(((now - prog.start).toFloat() / (prog.stop - prog.start)).coerceIn(0f, 1f))
            }
        }
        if (isFav) Text("★", fontSize = 16.sp, color = Color(0xFFFFC107))
        if (isFailed) Text(" ✕", fontSize = 14.sp, color = Color(0xFFFF6B6B))
    }
}

@Composable
fun VideoPlayer(
    channel: Channel, started: Boolean, modifier: Modifier,
    onError: () -> Unit, onState: (Int) -> Unit,
) {
    val ctx = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true)).build()
    }
    val errCb by rememberUpdatedState(onError)
    val stCb by rememberUpdatedState(onState)

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { errCb() }
            override fun onPlaybackStateChanged(state: Int) { stCb(state) }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    LaunchedEffect(started) { player.playWhenReady = started }

    LaunchedEffect(channel.url) {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(channel.userAgent ?: "Mozilla/5.0 (Linux; Android 11) MioIPTV")
            .setDefaultRequestProperties(listOfNotNull(channel.referrer?.let { "Referer" to it }).toMap())
        val u = channel.url.lowercase()
        val mime = when {
            ".mpd" in u -> MimeTypes.APPLICATION_MPD
            ".m3u8" in u || "relinker" in u -> MimeTypes.APPLICATION_M3U8
            else -> null
        }
        val item = MediaItem.Builder().setUri(channel.url).apply { if (mime != null) setMimeType(mime) }.build()
        player.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        player.prepare()
    }

    AndroidView(
        factory = {
            PlayerView(it).apply {
                this.player = player
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                setKeepContentOnPlayerReset(true)
            }
        },
        modifier = modifier,
    )
}
