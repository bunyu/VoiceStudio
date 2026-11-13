package com.voicestudio.reader.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import com.voicestudio.reader.R
import com.voicestudio.reader.ReaderApp
import com.voicestudio.reader.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps narration alive with the screen off: a foreground service while the book plays or is being
 * prepared, a media session for the lock screen, headset and Bluetooth buttons, and a CPU wake lock
 * so the voice engine keeps generating ahead.
 */
class ReaderService : Service() {
    companion object {
        private const val CHANNEL = "reading"
        private const val ID = 1
        private const val ACTION_TOGGLE = "com.voicestudio.reader.TOGGLE"
        private const val ACTION_STOP = "com.voicestudio.reader.STOP"

        fun start(context: Context) {
            // Starting from the background can be refused on Android 12+; the service is then already running
            // (playback was started from the app or the notification), so nothing is lost.
            runCatching { ContextCompat.startForegroundService(context, Intent(context, ReaderService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var session: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val narration = ReaderApp.get(this).narration
        session = MediaSession.Builder(this, narration.player)
            .setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .build()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
        scope.launch { narration.state.collect { update() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val narration = ReaderApp.get(this).narration
        when (intent?.action) {
            ACTION_TOGGLE -> narration.toggle()
            ACTION_STOP -> { narration.stop(); finish(); return START_NOT_STICKY }
        }
        // Every start must reach startForeground promptly, even if it then steps down.
        goForeground()
        update()
        return START_NOT_STICKY
    }

    private fun active(): Boolean {
        val s = ReaderApp.get(this).narration.state.value
        return s.playing || s.preparingAll
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        // Android 12+ refuses this from the background unless the user just acted (app, notification or
        // media button); playback then continues and the next user action brings the service forward.
        foreground = runCatching { ServiceCompat.startForeground(this, ID, notification(), type) }.isSuccess
    }

    private fun update() {
        if (active()) {
            if (!foreground) goForeground() else getSystemService(NotificationManager::class.java).notify(ID, notification())
            if (wakeLock?.isHeld != true) wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VoiceStudioReader:narration").apply { acquire(12 * 60 * 60 * 1000L) }
        } else {
            wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
            if (foreground) {
                // Paused: leave a notification to resume from, but let the system reclaim the process if needed.
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
                foreground = false
                getSystemService(NotificationManager::class.java).notify(ID, notification())
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun notification(): Notification {
        val s = ReaderApp.get(this).narration.state.value
        val text = when {
            s.error != null -> getString(R.string.status_problem)
            s.waiting -> getString(R.string.status_waiting)
            s.preparingAll && !s.playing -> getString(R.string.status_preparing, s.preparedTotal, s.totalPieces)
            else -> s.chapterTitle
        }
        fun action(name: String) = PendingIntent.getService(this, name.hashCode(), Intent(this, ReaderService::class.java).setAction(name), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(s.title.ifEmpty { getString(R.string.app_name) })
            .setContentText(text)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(action(ACTION_STOP))
            .setOngoing(active())
            .setOnlyAlertOnce(true)
            .addAction(if (s.playing) R.drawable.ic_pause else R.drawable.ic_play, getString(if (s.playing) R.string.pause else R.string.play), action(ACTION_TOGGLE))
            .addAction(R.drawable.ic_stop, getString(R.string.stop), action(ACTION_STOP))
            .setStyle(MediaStyleNotificationHelper.MediaStyle(session).setShowActionsInCompactView(0, 1))
            .build()
    }

    private fun finish() {
        wakeLock?.takeIf { it.isHeld }?.release(); wakeLock = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foreground = false
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        session.release()
        super.onDestroy()
    }
}
