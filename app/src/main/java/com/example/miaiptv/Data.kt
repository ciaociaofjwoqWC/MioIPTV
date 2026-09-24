package com.example.miaiptv

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

data class Channel(
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val number: Int,
    val id: String = "",
    val userAgent: String? = null,
    val referrer: String? = null,
) {
    // chiavi per collegare il canale alla guida programmi (EPG)
    val keys: List<String> = listOf(Epg.norm(id), Epg.norm(name)).filter { it.isNotEmpty() }
}

data class Programme(val start: Long, val stop: Long, val title: String, val desc: String)

fun parseM3u(text: String): List<Channel> {
    val out = mutableListOf<Channel>()
    var name = ""; var logo: String? = null; var group = "Altri"; var id = ""; var chno = 0
    var ua: String? = null; var ref: String? = null
    val logoRe = Regex("""tvg-logo="([^"]*)"""")
    val groupRe = Regex("""group-title="([^"]*)"""")
    val idRe = Regex("""tvg-id="([^"]*)"""")
    val chnoRe = Regex("""tvg-chno="(\d+)"""")
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF") -> {
                name = line.substringAfterLast(",").trim()
                logo = logoRe.find(line)?.groupValues?.get(1)?.ifBlank { null }
                group = groupRe.find(line)?.groupValues?.get(1)?.ifBlank { null } ?: "Altri"
                id = idRe.find(line)?.groupValues?.get(1) ?: ""
                chno = chnoRe.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            }
            line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> ua = line.substringAfter("=")
            line.startsWith("#EXTVLCOPT:http-referrer=", true) -> ref = line.substringAfter("=")
            line.isNotEmpty() && !line.startsWith("#") -> {
                out += Channel(name.ifBlank { line }, logo, group, line, chno, id, ua, ref)
                name = ""; logo = null; group = "Altri"; id = ""; chno = 0; ua = null; ref = null
            }
        }
    }
    // Ordine come sul telecomando: prima per numero canale (Rai 1 = 1, Rai 2 = 2 ...)
    val sorted = out.withIndex()
        .sortedWith(compareBy({ if (it.value.number > 0) it.value.number else 10_000 }, { it.index }))
        .map { it.value }
    var n = sorted.maxOfOrNull { it.number } ?: 0
    return sorted.map { if (it.number > 0) it else it.copy(number = ++n) }
}

object Repo {
    // >>> Cambia qui la playlist <<<
    const val PLAYLIST_URL = "https://github.com/Tundrak/IPTV-Italia/raw/main/iptvitaplus.m3u"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun load(ctx: Context): List<Channel> = withContext(Dispatchers.IO) {
        val cache = File(ctx.cacheDir, "playlist.m3u")
        try {
            client.newCall(Request.Builder().url(PLAYLIST_URL).build()).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val text = r.body!!.string()
                cache.writeText(text)
                parseM3u(text)
            }
        } catch (e: Exception) {
            if (cache.exists()) parseM3u(cache.readText()) else throw e
        }
    }
}

object Epg {
    // >>> Guida programmi (XMLTV, anche .gz). Cambiala se non funziona <<<
    const val URL = "https://epgshare01.online/epgshare01/epg_ripper_IT1.xml.gz"

    fun norm(s: String?): String {
        var t = (s ?: "").lowercase().replace(Regex("[^a-z0-9]"), "")
        if (t.length > 4 && t.endsWith("it")) t = t.removeSuffix("it")
        return t
    }

    private fun time(s: String?): Long {
        if (s == null) return 0
        for (f in listOf("yyyyMMddHHmmss Z", "yyyyMMddHHmmssZ")) {
            try { return SimpleDateFormat(f, Locale.US).parse(s.trim())!!.time } catch (e: Exception) {}
        }
        return 0
    }

    private fun gunzipIfNeeded(i: BufferedInputStream): InputStream {
        i.mark(2); val a = i.read(); val b = i.read(); i.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(i) else i
    }

    suspend fun load(ctx: Context, channels: List<Channel>): Map<String, List<Programme>> =
        withContext(Dispatchers.IO) {
            val f = File(ctx.cacheDir, "epg.xml.gz")
            if (!f.exists() || System.currentTimeMillis() - f.lastModified() > 6 * 3600_000L) {
                try {
                    val tmp = File(ctx.cacheDir, "epg.tmp")
                    val c = Repo.client.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
                    c.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                        if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                        tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
                    }
                    tmp.renameTo(f)
                } catch (e: Exception) {
                    if (!f.exists()) return@withContext emptyMap()
                }
            }
            try {
                val wanted = channels.flatMap { it.keys }.toSet()
                f.inputStream().buffered().use { parse(gunzipIfNeeded(it), wanted) }
            } catch (e: Exception) { emptyMap() }
        }

    private fun parse(input: InputStream, wanted: Set<String>): Map<String, List<Programme>> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val idKey = HashMap<String, String>()
        val out = HashMap<String, MutableList<Programme>>()
        val now = System.currentTimeMillis()
        val from = now - 3 * 3600_000L
        val to = now + 36 * 3600_000L
        var curChan: String? = null
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> {
                        curChan = p.getAttributeValue(null, "id")
                        val k = norm(curChan)
                        if (curChan != null && k in wanted) idKey[curChan] = k
                    }
                    "display-name" -> {
                        val c = curChan
                        if (c != null && c !in idKey) {
                            val k = norm(p.nextText())
                            if (k in wanted) idKey[c] = k
                        }
                    }
                    "programme" -> {
                        val ch = p.getAttributeValue(null, "channel") ?: ""
                        val key = idKey[ch] ?: norm(ch).takeIf { it in wanted }
                        val s = time(p.getAttributeValue(null, "start"))
                        val e = time(p.getAttributeValue(null, "stop"))
                        var title = ""; var desc = ""
                        while (true) {
                            ev = p.next()
                            if (ev == XmlPullParser.END_DOCUMENT || (ev == XmlPullParser.END_TAG && p.name == "programme")) break
                            if (ev == XmlPullParser.START_TAG) when (p.name) {
                                "title" -> { val t = p.nextText(); if (title.isEmpty()) title = t }
                                "desc" -> { val t = p.nextText(); if (desc.isEmpty()) desc = t }
                            }
                        }
                        if (key != null && e > from && s < to && s > 0) {
                            out.getOrPut(key) { mutableListOf() } += Programme(s, e, title.trim(), desc.trim().take(300))
                        }
                    }
                }
            } else if (ev == XmlPullParser.END_TAG && p.name == "channel") curChan = null
            ev = p.next()
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.start } }
    }
}

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    var last: String?
        get() = sp.getString("last", null)
        set(v) { sp.edit().putString("last", v).apply() }
    var favs: Set<String>
        get() = sp.getStringSet("favs", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("favs", v).apply() }
}
