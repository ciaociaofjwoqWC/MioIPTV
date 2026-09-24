package com.example.miaiptv

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class Channel(
    val name: String,
    val logo: String?,
    val group: String,
    val url: String,
    val userAgent: String? = null,
    val referrer: String? = null,
)

fun parseM3u(text: String): List<Channel> {
    val out = mutableListOf<Channel>()
    var name = ""; var logo: String? = null; var group = "Altri"
    var ua: String? = null; var ref: String? = null
    val logoRe = Regex("""tvg-logo="([^"]*)"""")
    val groupRe = Regex("""group-title="([^"]*)"""")
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF") -> {
                name = line.substringAfterLast(",").trim()
                logo = logoRe.find(line)?.groupValues?.get(1)?.ifBlank { null }
                group = groupRe.find(line)?.groupValues?.get(1)?.ifBlank { null } ?: "Altri"
            }
            line.startsWith("#EXTVLCOPT:http-user-agent=", true) -> ua = line.substringAfter("=")
            line.startsWith("#EXTVLCOPT:http-referrer=", true) -> ref = line.substringAfter("=")
            line.isNotEmpty() && !line.startsWith("#") -> {
                out += Channel(name.ifBlank { line }, logo, group, line, ua, ref)
                name = ""; logo = null; group = "Altri"; ua = null; ref = null
            }
        }
    }
    return out
}

object Repo {
    // >>> Cambia qui la playlist <<<
    const val PLAYLIST_URL = "https://github.com/Tundrak/IPTV-Italia/raw/main/iptvitaplus.m3u"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // Scarica la playlist; se offline usa l'ultima copia salvata.
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

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("iptv", Context.MODE_PRIVATE)
    var last: String?
        get() = sp.getString("last", null)
        set(v) { sp.edit().putString("last", v).apply() }
    var favs: Set<String>
        get() = sp.getStringSet("favs", emptySet())!!.toSet()
        set(v) { sp.edit().putStringSet("favs", v).apply() }
}
