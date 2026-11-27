package uz.shohruh.ytplayer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Один WebView на всё приложение. Он переживает пересоздание Activity,
 * поэтому музыка не прерывается при повороте экрана, смене темы и т.п.
 */
@SuppressLint("StaticFieldLeak", "SetJavaScriptEnabled")
object WebHolder {
    const val HOME = "https://m.youtube.com/"

    // Обычный мобильный Chrome — так YouTube отдаёт полноценную мобильную версию
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; SM-A536B) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/131.0.0.0 Mobile Safari/537.36"

    var webView: WebView? = null
        private set
    private var ctxWrapper: MutableContextWrapper? = null
    private var script: String = ""
    private var docStartSupported = false

    fun isCreated() = webView != null

    fun obtain(activity: Activity): WebView {
        webView?.let {
            ctxWrapper?.baseContext = activity
            return it
        }
        val app = activity.applicationContext
        script = app.assets.open("inject.js").bufferedReader().use { it.readText() }
        val wrapper = MutableContextWrapper(activity)
        ctxWrapper = wrapper
        val w = WebView(wrapper)
        configure(w, app)
        webView = w
        return w
    }

    /** Activity уничтожена, но WebView живёт дальше (играет в фоне). */
    fun detach(app: Context) {
        ctxWrapper?.baseContext = app.applicationContext
        webView?.let { (it.parent as? ViewGroup)?.removeView(it) }
    }

    fun destroy() {
        webView?.let {
            (it.parent as? ViewGroup)?.removeView(it)
            it.destroy()
        }
        webView = null
        ctxWrapper = null
    }

    fun js(code: String) {
        webView?.post { webView?.evaluateJavascript(code, null) }
    }

    private fun configure(w: WebView, app: Context) {
        w.setBackgroundColor(Color.parseColor("#0F0F0F"))
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = UA
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(w, true)
        }
        ensureDarkPref()

        w.addJavascriptInterface(JsBridge(app), "YTBridge")

        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(
                w, script, setOf("https://*.youtube.com", "https://youtube.com")
            )
            docStartSupported = true
        }

        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val u = req.url
                val scheme = u.scheme ?: return true
                if (scheme != "http" && scheme != "https") return true // intent://, vnd.youtube:// — блокируем
                val host = u.host ?: return false
                if (isAllowedHost(host)) return false
                // Внешние ссылки (в описании видео и т.п.) — в обычный браузер
                try {
                    view.context.startActivity(
                        Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Log.w("WebHolder", "no browser", e)
                }
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!docStartSupported && isYoutube(url)) view.evaluateJavascript(script, null)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                if (isYoutube(url)) {
                    if (!docStartSupported) view.evaluateJavascript(script, null)
                    view.evaluateJavascript(
                        "window.__ytbg && window.__ytbg.setBackground(${!MediaHub.appForeground})", null
                    )
                }
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun isYoutube(url: String?): Boolean {
        val host = url?.let { Uri.parse(it).host } ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com")
    }

    private fun isAllowedHost(host: String): Boolean =
        host == "youtube.com" || host.endsWith(".youtube.com") ||
            host == "youtu.be" ||
            host.endsWith("ytimg.com") || host.endsWith("gstatic.com") ||
            host.endsWith("googleusercontent.com") || host.endsWith("googleapis.com") ||
            host.endsWith("googlevideo.com") ||
            host.contains("google.") // accounts.google.com, accounts.google.co.uz — вход в аккаунт

    /** Включаем тёмную тему YouTube через cookie PREF (флаг f6 = 0x400). */
    private fun ensureDarkPref() {
        val cm = CookieManager.getInstance()
        val all = cm.getCookie("https://m.youtube.com") ?: ""
        val pref = all.split(";").map { it.trim() }
            .firstOrNull { it.startsWith("PREF=") }?.removePrefix("PREF=")
        val f6 = Regex("(^|&)f6=([0-9a-fA-F]+)")
        val newPref = when {
            pref.isNullOrEmpty() -> "f6=400"
            f6.containsMatchIn(pref) -> pref.replace(f6) { m ->
                val v = (m.groupValues[2].toLongOrNull(16) ?: 0L) or 0x400L and 0x80000L.inv()
                m.groupValues[1] + "f6=" + java.lang.Long.toHexString(v)
            }
            else -> "$pref&f6=400"
        }
        cm.setCookie(
            "https://m.youtube.com",
            "PREF=$newPref; Domain=.youtube.com; Path=/; Secure; Max-Age=63072000"
        )
        cm.flush()
    }
}
