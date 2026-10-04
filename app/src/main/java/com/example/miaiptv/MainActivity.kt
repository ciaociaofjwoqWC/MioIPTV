package com.example.miaiptv

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

// L'app è un "contenitore" per Spij TV: mostra il sito pubblicato su GitHub
// Pages, così ogni modifica al sito arriva subito anche sulla TV senza dover
// ricompilare l'APK. In più fa per il sito quello che un browser non può fare
// (vedi NativeBridge).
private const val START_URL = "https://ciaociaofjwoqwc.github.io/MioIPTV/"

class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this)
        web.setBackgroundColor(Color.BLACK)
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true                 // preferiti, ultimi visti…
            mediaPlaybackRequiresUserGesture = false // il video parte senza toccare lo schermo
            // Alcuni canali usano link http: in un sito https il browser li
            // bloccherebbe, nell'app li lasciamo passare.
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        web.addJavascriptInterface(NativeBridge(), "SpijNative")
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.loadDataWithBaseURL(START_URL, OFFLINE_HTML, "text/html", "utf-8", null)
            }
        }
        setContentView(web)
        web.isFocusable = true
        web.requestFocus()

        if (savedInstanceState == null) web.loadUrl(START_URL) else web.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    // Tasto Indietro del telecomando: prima chiede al sito se deve chiudere lo
    // schermo intero; se il sito non ha niente da chiudere, esce dall'app.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.spijBack ? spijBack() : false") { handled ->
            if (handled != "true") finish()
        }
    }

    override fun onPause() {
        super.onPause()
        // Uscendo dall'app (tasto Home) il video si ferma invece di continuare in sottofondo.
        web.evaluateJavascript("window.spijPause && spijPause()", null)
        web.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
        web.evaluateJavascript("window.spijResume && spijResume()", null)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    // Funzioni che il sito può chiamare come window.SpijNative.xxx()
    private class NativeBridge {
        // Restituisce l'indirizzo vero del video dietro un link Rai "relinker".
        @JavascriptInterface
        fun resolve(url: String): String = RaiResolver.resolve(url)
    }
}

private val OFFLINE_HTML = """
<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:18px;
background:#0a0a0c;color:#fff;font-family:sans-serif}p{color:#a3a3ad}
button{background:linear-gradient(90deg,#ff8a1f,#e8457a,#7b3cff);color:#fff;border:0;border-radius:8px;padding:12px 28px;font-size:18px;font-weight:700}
button:focus{outline:3px solid #fff}</style></head>
<body><h2>Nessuna connessione</h2><p>Controlla il Wi-Fi della TV e riprova.</p>
<button autofocus onclick="location.href='$START_URL'">Riprova</button></body></html>
""".trimIndent()
