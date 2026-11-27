package uz.shohruh.ytplayer

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.JavascriptInterface
import androidx.core.content.ContextCompat
import org.json.JSONObject

/** Состояние текущего трека, которое присылает страница. */
data class TrackState(
    val id: String,
    val title: String,
    val artist: String,
    val art: String,
    val playing: Boolean,
    val pos: Long,
    val dur: Long,
    val path: String,
    val has: Boolean,
)

/** Связующее звено: страница → уведомление, уведомление → страница. */
object MediaHub {
    @Volatile var state: TrackState? = null
    var service: PlaybackService? = null
    var appForeground = false
    var onPathChanged: ((String) -> Unit)? = null

    private var starting = false
    private var suppressStartUntil = 0L

    fun onState(ctx: Context, s: TrackState) {
        state = s
        onPathChanged?.invoke(s.path)
        val svc = service
        if (svc != null) {
            svc.render(s)
        } else if (s.has && s.playing && !starting && SystemClock.elapsedRealtime() > suppressStartUntil) {
            starting = true
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, PlaybackService::class.java))
            } catch (e: Exception) {
                // Android не разрешил запуск из фона — запустим при следующем обновлении
                Log.w("MediaHub", "cannot start service yet", e)
                starting = false
            }
        }
    }

    fun serviceStarted(svc: PlaybackService) {
        service = svc
        starting = false
    }

    fun serviceStopped() {
        service = null
        starting = false
        suppressStartUntil = SystemClock.elapsedRealtime() + 3000
    }

    // Команды в страницу
    fun play() = WebHolder.js("window.__ytbg && window.__ytbg.play()")
    fun pause() = WebHolder.js("window.__ytbg && window.__ytbg.pause()")
    fun next() = WebHolder.js("window.__ytbg && window.__ytbg.next()")
    fun prev() = WebHolder.js("window.__ytbg && window.__ytbg.prev()")
    fun seek(ms: Long) = WebHolder.js("window.__ytbg && window.__ytbg.seek($ms)")
    fun nav(tab: String) = WebHolder.js("window.__ytbg ? window.__ytbg.nav('$tab') : location.reload()")
    fun setBackground(bg: Boolean) = WebHolder.js("window.__ytbg && window.__ytbg.setBackground($bg)")
}

/** Методы, которые вызывает JavaScript страницы. */
class JsBridge(private val app: Context) {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onState(json: String) {
        val s = try {
            val o = JSONObject(json)
            TrackState(
                id = o.optString("id"),
                title = o.optString("title"),
                artist = o.optString("artist"),
                art = o.optString("art"),
                playing = o.optBoolean("playing"),
                pos = o.optLong("pos"),
                dur = o.optLong("dur"),
                path = o.optString("path"),
                has = o.optBoolean("has"),
            )
        } catch (e: Exception) {
            return
        }
        main.post { MediaHub.onState(app, s) }
    }
}
