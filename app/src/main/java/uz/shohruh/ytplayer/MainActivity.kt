package uz.shohruh.ytplayer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.addCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.bottomnavigation.BottomNavigationView

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var container: FrameLayout
    private lateinit var bottomNav: BottomNavigationView
    private var selectingProgrammatically = false
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        container = findViewById(R.id.webContainer)
        bottomNav = findViewById(R.id.bottomNav)

        val firstStart = !WebHolder.isCreated()
        webView = WebHolder.obtain(this)
        (webView.parent as? ViewGroup)?.removeView(webView)
        container.addView(
            webView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        webView.webChromeClient = chromeClient

        bottomNav.setOnItemSelectedListener { item ->
            if (!selectingProgrammatically) MediaHub.nav(tabFor(item.itemId))
            true
        }
        bottomNav.setOnItemReselectedListener { item -> MediaHub.nav(tabFor(item.itemId)) }

        // Подсветка вкладки по текущему адресу страницы
        MediaHub.onPathChanged = { path -> highlightTab(path) }

        onBackPressedDispatcher.addCallback(this) {
            when {
                fullscreenView != null -> fullscreenCallback?.onCustomViewHidden()
                webView.canGoBack() -> webView.goBack()
                else -> moveTaskToBack(true) // не закрываем — музыка играет дальше
            }
        }

        if (firstStart) webView.loadUrl(urlFromIntent(intent) ?: WebHolder.HOME)

        requestNotificationPermission()
        askBatteryOptimizationOnce()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        urlFromIntent(intent)?.let { webView.loadUrl(it) }
    }

    override fun onResume() {
        super.onResume()
        MediaHub.appForeground = true
        MediaHub.setBackground(false)
    }

    override fun onPause() {
        // ВАЖНО: не вызываем webView.onPause()/pauseTimers() — иначе звук остановится
        MediaHub.appForeground = false
        MediaHub.setBackground(true)
        super.onPause()
    }

    override fun onDestroy() {
        MediaHub.onPathChanged = null
        webView.webChromeClient = WebChromeClient()
        WebHolder.detach(applicationContext)
        super.onDestroy()
    }

    private fun tabFor(id: Int) = when (id) {
        R.id.nav_shorts -> "shorts"
        R.id.nav_subs -> "subs"
        R.id.nav_history -> "history"
        else -> "home"
    }

    private fun highlightTab(path: String) {
        val id = when {
            path == "/" || path.isEmpty() -> R.id.nav_home
            path.startsWith("/shorts") -> R.id.nav_shorts
            path.startsWith("/feed/subscriptions") -> R.id.nav_subs
            path.startsWith("/feed/history") -> R.id.nav_history
            else -> return
        }
        if (bottomNav.selectedItemId != id) {
            selectingProgrammatically = true
            bottomNav.selectedItemId = id
            selectingProgrammatically = false
        }
    }

    private fun urlFromIntent(i: Intent?): String? {
        val u = i?.data ?: return null
        val host = u.host ?: return null
        return when {
            host == "youtu.be" -> u.lastPathSegment?.let { "https://m.youtube.com/watch?v=$it" }
            host.endsWith("youtube.com") -> u.buildUpon().authority("m.youtube.com").build().toString()
            else -> null
        }
    }

    // ---------- Полноэкранное видео ----------
    private val chromeClient = object : WebChromeClient() {
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (fullscreenView != null) { callback.onCustomViewHidden(); return }
            fullscreenView = view
            fullscreenCallback = callback
            (window.decorView as FrameLayout).addView(
                view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            )
            WindowCompat.getInsetsController(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        override fun onHideCustomView() {
            fullscreenView?.let { (window.decorView as FrameLayout).removeView(it) }
            fullscreenView = null
            fullscreenCallback = null
            WindowCompat.getInsetsController(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // ---------- Разрешения ----------
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    @SuppressLint("BatteryLife")
    private fun askBatteryOptimizationOnce() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("battery_asked", false)) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        prefs.edit().putBoolean("battery_asked", true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_text)
            .setPositiveButton(R.string.allow) { _, _ ->
                try {
                    startActivity(
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                            .setData(Uri.parse("package:$packageName"))
                    )
                } catch (_: Exception) {
                }
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }
}
