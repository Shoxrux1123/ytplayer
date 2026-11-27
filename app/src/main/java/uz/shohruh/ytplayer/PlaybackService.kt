package uz.shohruh.ytplayer

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Фоновая служба: держит процесс живым, показывает плеер в шторке
 * и на экране блокировки (через MediaSession), не даёт уснуть Wi-Fi и CPU.
 */
class PlaybackService : Service() {

    companion object {
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 42
        const val ACTION_PLAY = "uz.shohruh.ytplayer.PLAY"
        const val ACTION_PAUSE = "uz.shohruh.ytplayer.PAUSE"
        const val ACTION_NEXT = "uz.shohruh.ytplayer.NEXT"
        const val ACTION_PREV = "uz.shohruh.ytplayer.PREV"
        const val ACTION_CLOSE = "uz.shohruh.ytplayer.CLOSE"
    }

    private lateinit var session: MediaSessionCompat
    private lateinit var nm: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var artUrl: String? = null
    private var artBitmap: Bitmap? = null
    private var lastMetaKey = ""
    private var lastNotifKey = ""
    private var isForeground = false

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()

        session = MediaSessionCompat(this, "YTPlayer").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = MediaHub.play()
                override fun onPause() = MediaHub.pause()
                override fun onSkipToNext() = MediaHub.next()
                override fun onSkipToPrevious() = MediaHub.prev()
                override fun onSeekTo(pos: Long) = MediaHub.seek(pos)
                override fun onStop() = close()
            })
            setSessionActivity(openAppIntent())
            isActive = true
        }

        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "YTPlayer:playback")
            .apply { setReferenceCounted(false) }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "YTPlayer:wifi").apply { setReferenceCounted(false) }

        MediaHub.serviceStarted(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        when (intent?.action) {
            ACTION_PLAY -> MediaHub.play()
            ACTION_PAUSE -> MediaHub.pause()
            ACTION_NEXT -> MediaHub.next()
            ACTION_PREV -> MediaHub.prev()
            ACTION_CLOSE -> close()
        }
        MediaHub.state?.let { render(it) }
        return START_NOT_STICKY
    }

    private fun ensureForeground() {
        val n = buildNotification(MediaHub.state)
        if (!isForeground) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
            )
            isForeground = true
        } else {
            nm.notify(NOTIF_ID, n)
        }
    }

    /** Вызывается при каждом обновлении состояния со страницы. */
    fun render(s: TrackState) {
        // Обложка
        if (s.art.isNotEmpty() && s.art != artUrl) {
            artUrl = s.art
            artBitmap = null
            loadArt(s.art)
        }

        // Метаданные (название, исполнитель, обложка, длительность)
        val metaKey = "${s.id}|${s.title}|${s.artist}|${s.dur / 1000}|${artBitmap != null}"
        if (metaKey != lastMetaKey) {
            lastMetaKey = metaKey
            val md = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, s.title.ifBlank { "YouTube" })
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, s.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, s.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, s.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, s.id)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, if (s.dur > 0) s.dur else -1L)
            artBitmap?.let {
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
                md.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
            }
            session.setMetadata(md.build())
        }

        // Состояние воспроизведения (кнопки и позиция на экране блокировки)
        val actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    if (s.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    s.pos, if (s.playing) 1f else 0f, SystemClock.elapsedRealtime()
                )
                .build()
        )

        updateLocks(s.playing)

        val notifKey = "$metaKey|${s.playing}"
        if (notifKey != lastNotifKey) {
            lastNotifKey = notifKey
            nm.notify(NOTIF_ID, buildNotification(s))
        }
    }

    private fun loadArt(url: String) {
        io.execute {
            val bmp = try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            val scaled = bmp?.let {
                val max = 512
                if (it.width <= max && it.height <= max) it
                else {
                    val k = max.toFloat() / maxOf(it.width, it.height)
                    Bitmap.createScaledBitmap(it, (it.width * k).toInt(), (it.height * k).toInt(), true)
                }
            }
            main.post {
                if (url == artUrl && scaled != null) {
                    artBitmap = scaled
                    lastMetaKey = ""
                    lastNotifKey = ""
                    MediaHub.state?.let { render(it) }
                }
            }
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun updateLocks(playing: Boolean) {
        if (playing) {
            if (wakeLock?.isHeld == false) wakeLock?.acquire()
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        } else {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
    }

    private fun buildNotification(s: TrackState?): Notification {
        val playing = s?.playing == true
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_music)
            .setContentTitle(s?.title?.ifBlank { null } ?: getString(R.string.app_name))
            .setContentText(s?.artist ?: "")
            .setLargeIcon(artBitmap)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_CLOSE, 5))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(playing)
            .addAction(R.drawable.ic_prev, getString(R.string.prev), serviceIntent(ACTION_PREV, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                serviceIntent(if (playing) ACTION_PAUSE else ACTION_PLAY, 2)
            )
            .addAction(R.drawable.ic_next, getString(R.string.next), serviceIntent(ACTION_NEXT, 3))
            .addAction(R.drawable.ic_close, getString(R.string.close), serviceIntent(ACTION_CLOSE, 4))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        return b.build()
    }

    private fun serviceIntent(action: String, code: Int): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, PlaybackService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
            ch.setShowBadge(false)
            ch.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            nm.createNotificationChannel(ch)
        }
    }

    private fun close() {
        MediaHub.pause()
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        isForeground = false
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Приложение смахнули из «Недавних» — музыка продолжает играть.
        // Остановить можно крестиком в уведомлении.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        MediaHub.serviceStopped()
        releaseLocks()
        session.isActive = false
        session.release()
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
