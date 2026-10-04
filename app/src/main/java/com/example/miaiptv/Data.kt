package com.example.miaiptv

import java.net.HttpURLConnection
import java.net.URL

// I canali Rai passano da un "relinker": un indirizzo che risponde con un
// rimando (redirect) al video vero. Il relinker non dà ai browser il permesso
// di leggere la risposta (CORS), quindi dal sito il canale non parte. Dall'app
// invece la richiesta si può fare: qui si segue il rimando e si restituisce al
// sito l'indirizzo finale del video, che il sito può poi aprire normalmente.
object RaiResolver {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 11; TV) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"

    fun resolve(url: String): String {
        // Il relinker in http risponde 403: in https funziona.
        var current = url.replaceFirst("http://", "https://")
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", UA)
            }
            try {
                val code = conn.responseCode
                if (code !in 300..399) return if (code == 200) current else ""
                val location = conn.getHeaderField("Location") ?: return ""
                current = URL(URL(current), location).toString()
                // Appena si esce dal relinker abbiamo l'indirizzo del video.
                if (!current.contains("relinker")) return current
            } catch (e: Exception) {
                return ""
            } finally {
                conn.disconnect()
            }
        }
        return current
    }
}
