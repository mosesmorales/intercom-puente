package com.intercompuente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/** Servicio en primer plano: mantiene el puente vivo aunque apagues la pantalla. */
class BridgeService : Service() {

    companion object {
        const val ACTION_STOP = "com.intercompuente.STOP"
        const val EXTRA_PEER = "peer"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 1
    }

    private var bridge: Bridge? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        if (bridge == null) {
            val b = Bridge(applicationContext) { stopSelf() }
            bridge = b
            val ip = intent?.getStringExtra(EXTRA_PEER)
            Thread { b.start(ip) }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val b = bridge
        bridge = null
        if (b != null) Thread { b.stop() }.start()
        super.onDestroy()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Puente de audio", NotificationManager.IMPORTANCE_LOW)
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Intercom Puente activo")
            .setContentText("Pasando el audio al otro teléfono")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(open)
            .addAction(0, "Detener", stop)
            .setOngoing(true)
            .build()
    }
}
