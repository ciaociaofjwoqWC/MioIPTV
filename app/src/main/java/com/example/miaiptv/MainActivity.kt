@file:OptIn(UnstableApi::class)

package com.example.miaiptv

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = Prefs(this)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) { Root(prefs) } }
    }
}

@Composable
fun Root(prefs: Prefs) {
    val ctx = LocalContext.current
    var channels by remember { mutableStateOf<List<Channel>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val retryFocus = remember { FocusRequester() }

    LaunchedEffect(attempt) {
        err = null
        try {
            val list = Repo.load(ctx)
            if (list.isEmpty()) throw IllegalStateException("Playlist vuota")
            channels = list
        } catch (e: Exception) {
            err = e.message ?: "Errore di rete"
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val list = channels
        when {
            list != null -> IptvApp(list, prefs)
            err != null -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Impossibile caricare i canali", fontSize = 28.sp)
                    Text(err!!, fontSize = 18.sp, color = Color.Gray)
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { attempt++ }, modifier = Modifier.focusRequester(retryFocus)) {
                        Text("Riprova", fontSize = 20.sp)
                    }
                }
                LaunchedEffect(err) { runCatching { retryFocus.requestFocus() } }
            }
            else -> Text("Caricamento canali…", fontSize = 24.sp)
        }
    }
}

@Composable
fun IptvApp(channels: List<Channel>, prefs: Prefs) {
    val groups = remember(channels) {
        listOf("Preferiti", "Tutti") + channels.map { it.group }.distinct().sorted()
    }
    val numberOf = remember(channels) { channels.withIndex().associate { it.value.url to it.index + 1 } }

    var favs by remember { mutableStateOf(prefs.favs) }
    var groupIdx by remember { mutableIntStateOf(1) }
    var current by remember { mutableStateOf(channels.firstOrNull { it.url == prefs.last } ?: channels.first()) }
    var previous by remember { mutableStateOf<Channel?>(null) }
    var showList by remember { mutableStateOf(false) }
    var playError by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(false) }
    var banner by remember { mutableStateOf<Channel?>(null) }
    var numBuf by remember { mutableStateOf("") }
    val rootFocus = remember { FocusRequester() }

    fun listFor(idx: Int): List<Channel> = when (idx) {
        0 -> channels.filter { it.url in favs }
        1 -> channels
        else -> channels.filter { it.group == groups[idx] }
    }

    fun play(c: Channel) {
        if (c.url != current.url) {
            previous = current
            current = c
            prefs.last = c.url
        }
        playError = false
    }

    fun zap(d: Int) {
        val l = listFor(groupIdx).ifEmpty { channels }
        val i = l.indexOfFirst { it.url == current.url }
        play(if (i < 0) l[0] else l[(i + d + l.size) % l.size])
    }

    fun toggleFav(c: Channel) {
        favs = if (c.url in favs) favs - c.url else favs + c.url
        prefs.favs = favs
    }

    // Numeri dal telecomando: salta al canale N dopo 1,5 s
    LaunchedEffect(numBuf) {
        if (numBuf.isNotEmpty()) {
            delay(1500)
            numBuf.toIntOrNull()?.let { n -> channels.getOrNull(n - 1)?.let { play(it) } }
            numBuf = ""
        }
    }
    // Banner col nome canale per 3 secondi
    LaunchedEffect(current.url) { banner = current; delay(3000); banner = null }
    // Quando la lista si chiude il focus torna alla schermata video
    LaunchedEffect(showList) { if (!showList) runCatching { rootFocus.requestFocus() } }
    BackHandler(enabled = showList) { showList = false }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .focusable()
            .onKeyEvent { e ->
                if (showList) return@onKeyEvent false
                val k = e.nativeKeyEvent.keyCode
                val down = e.type == KeyEventType.KeyDown
                val up = e.type == KeyEventType.KeyUp
                when (k) {
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { if (down) zap(1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { if (down) zap(-1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_LAST_CHANNEL -> {
                        if (down) previous?.let { play(it) }; true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                    KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE -> { if (up) showList = true; true }
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                        if (down) numBuf = (numBuf + (k - KeyEvent.KEYCODE_0)).takeLast(4); true
                    }
                    else -> false
                }
            }
    ) {
        VideoPlayer(current, onError = { playError = true }, onBuffering = { buffering = it })

        if (buffering && !playError) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(56.dp))
        }
        if (playError) {
            Text(
                "Canale non disponibile\nSu/Giù per cambiare canale",
                Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(24.dp),
                fontSize = 24.sp,
            )
        }
        banner?.let { b ->
            if (!showList) Text(
                "${numberOf[b.url]}   ${b.name}" + if (b.url in favs) "  ★" else "",
                Modifier.align(Alignment.BottomStart).padding(40.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 12.dp),
                fontSize = 28.sp, fontWeight = FontWeight.Bold,
            )
        }
        if (numBuf.isNotEmpty()) {
            Text(
                numBuf, Modifier.align(Alignment.TopEnd).padding(40.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(horizontal = 24.dp, vertical = 12.dp),
                fontSize = 48.sp, fontWeight = FontWeight.Bold,
            )
        }
        if (showList) {
            // Per "Preferiti" la lista è una fotografia: togliere una stella non fa sparire subito la riga
            val shown = remember(groupIdx) { listFor(groupIdx) }
            ChannelOverlay(
                title = groups[groupIdx], shown = shown, numberOf = numberOf, favs = favs, currentUrl = current.url,
                onGroup = { d -> groupIdx = (groupIdx + d + groups.size) % groups.size },
                onPlay = { play(it); showList = false },
                onFav = { toggleFav(it) },
                modifier = Modifier.align(Alignment.CenterStart),
            )
        }
    }
}

@Composable
fun ChannelOverlay(
    title: String, shown: List<Channel>, numberOf: Map<String, Int>, favs: Set<String>, currentUrl: String,
    onGroup: (Int) -> Unit, onPlay: (Channel) -> Unit, onFav: (Channel) -> Unit, modifier: Modifier,
) {
    val boxFocus = remember { FocusRequester() }
    Box(
        modifier
            .fillMaxHeight().width(540.dp)
            .background(Color(0xEB0B1220))
            .padding(24.dp)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown) when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { onGroup(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { onGroup(1); true }
                    else -> false
                } else false
            }
            .focusRequester(boxFocus)
            .focusable()
    ) {
        // key(title) ricrea la lista da capo quando si cambia categoria
        key(title) {
            val target = shown.indexOfFirst { it.url == currentUrl }.coerceAtLeast(0)
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (target - 2).coerceAtLeast(0))
            val itemFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                delay(80)
                runCatching { if (shown.isEmpty()) boxFocus.requestFocus() else itemFocus.requestFocus() }
            }
            Column {
                Text("◀  $title  ▶", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${shown.size} canali · OK guarda · tieni premuto OK = preferito",
                    fontSize = 14.sp, color = Color.Gray,
                )
                Spacer(Modifier.height(12.dp))
                if (shown.isEmpty()) {
                    Text("Nessun canale qui.\nTieni premuto OK su un canale per aggiungerlo ai preferiti.", fontSize = 18.sp)
                } else {
                    LazyColumn(state = listState) {
                        itemsIndexed(shown) { i, ch ->
                            ChannelRow(
                                ch, numberOf[ch.url] ?: 0, ch.url in favs, ch.url == currentUrl,
                                if (i == target) Modifier.focusRequester(itemFocus) else Modifier,
                                { onPlay(ch) }, { onFav(ch) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChannelRow(
    ch: Channel, number: Int, isFav: Boolean, isCurrent: Boolean,
    modifier: Modifier, onPlay: () -> Unit, onFav: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longDone by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color(0xFF2E7DFF) else Color.Transparent)
            .onFocusChanged { focused = it.isFocused }
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(number.toString(), Modifier.width(52.dp), fontSize = 16.sp, color = Color(0xFFB0B8C8))
        AsyncImage(ch.logo, null, Modifier.size(44.dp), contentScale = ContentScale.Fit)
        Spacer(Modifier.width(12.dp))
        Text(
            ch.name, Modifier.weight(1f), fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
        )
        if (isFav) Text("★", fontSize = 22.sp, color = Color(0xFFFFC107))
    }
}

@Composable
fun VideoPlayer(channel: Channel, onError: () -> Unit, onBuffering: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build().apply { playWhenReady = true } }
    val errCb by rememberUpdatedState(onError)
    val bufCb by rememberUpdatedState(onBuffering)

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { errCb() }
            override fun onPlaybackStateChanged(state: Int) { bufCb(state == Player.STATE_BUFFERING) }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }

    LaunchedEffect(channel.url) {
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(channel.userAgent ?: "Mozilla/5.0 (Linux; Android 11) MioIPTV")
            .setDefaultRequestProperties(listOfNotNull(channel.referrer?.let { "Referer" to it }).toMap())
        val item = MediaItem.Builder().setUri(channel.url).apply {
            if (channel.url.contains(".m3u8")) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
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
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}
