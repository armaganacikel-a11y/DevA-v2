package tr.devlab.devasocks

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Kalici (foreground) servis: bildirimle yayinda kalir, MIUI batarya kesmelerine
 * karsi onplanda yasar. Sunucu ornegi omrunu burada tasir.
 */
class SocksService : Service() {

    private var server: Socks5Server? = null
    private val handler = Handler(Looper.getMainLooper())

    private val notifyTick = object : Runnable {
        override fun run() {
            if (DevAState.running) {
                notifyStatus()
                handler.postDelayed(this, REFRESH_MS)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopProxy()
            return START_NOT_STICKY
        }
        startProxy()
        return START_STICKY
    }

    private fun startProxy() {
        val existing = server
        if (existing != null && existing.isActive) {
            goForeground() // startForegroundService her baslistiginda startForeground garanti edilmeli
            return
        }
        if (existing != null) {
            try { existing.stop() } catch (_: Exception) {}
        }
        val fresh = Socks5Server(DevAState.port) { count ->
            DevAState.activeConnections.set(count)
        }
        try {
            fresh.start()
        } catch (e: Exception) {
            LogBus.log("[!] Servis baslatilamadi: " + (e.message ?: e.javaClass.simpleName))
            DevAState.running = false
            stopSelf()
            return
        }
        server = fresh
        DevAState.running = true
        goForeground()
        handler.removeCallbacks(notifyTick)
        handler.post(notifyTick)
    }

    private fun stopProxy() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        DevAState.running = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        LogBus.log("[-] Dev-A v2 durduruldu")
    }

    override fun onDestroy() {
        try { server?.stop() } catch (_: Exception) {}
        server = null
        DevAState.running = false
        super.onDestroy()
    }

    // ---------- bildirim ----------

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text, DevAState.port, DevAState.activeConnections.get()))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun notifyStatus() {
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_START = "tr.devlab.devasocks.action.START"
        const val ACTION_STOP = "tr.devlab.devasocks.action.STOP"
        private const val CHANNEL_ID = "deva_status"
        private const val NOTIFICATION_ID = 7
        private const val REFRESH_MS = 2000L
    }
}
