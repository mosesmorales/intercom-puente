package com.intercompuente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Servicio en primer plano: mantiene el puente vivo aunque apagues la pantalla. */
class BridgeService : Service() {

    companion object {
        const val ACTION_STOP = "com.intercompuente.STOP"
        const val ACTION_MUSIC_OFF = "com.intercompuente.MUSIC_OFF"
        const val EXTRA_PEER = "peer"
        const val EXTRA_MP_CODE = "mp_code"
        const val EXTRA_MP_DATA = "mp_data"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIF_ID = 1
    }

    private var bridge: Bridge? = null
    private var projection: MediaProjection? = null
    private var btRegistered = false

    /** Al apagar el Bluetooth (donde van los intercomunicadores) el puente se detiene. */
    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            if (i.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val st = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (st == BluetoothAdapter.STATE_TURNING_OFF || st == BluetoothAdapter.STATE_OFF) {
                BridgeState.error = "Bluetooth apagado: puente detenido"
                stopSelf()
            }
        }
    }

    /** Avisos de sesión de audio de Spotify y similares (por si llegan como difusión implícita). */
    private val effectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val i = intent ?: return
            EffectBooster.handle(i)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (action == ACTION_MUSIC_OFF) {
            stopMusic()
            return START_NOT_STICKY
        }

        val mpData: Intent? = intent?.getParcelableExtra(EXTRA_MP_DATA)
        val mpCode = intent?.getIntExtra(EXTRA_MP_CODE, 0) ?: 0
        val wantsMusic = mpData != null && Build.VERSION.SDK_INT >= 29

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            if (wantsMusic) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            startForeground(NOTIF_ID, notification, type)
        } else {
            startForeground(NOTIF_ID, notification)
        }

        registerBtReceiver()

        var mp: MediaProjection? = null
        if (wantsMusic && mpData != null) mp = createProjection(mpCode, mpData)

        val current = bridge
        if (current == null) {
            val b = Bridge(applicationContext) { stopSelf() }
            bridge = b
            val ip = intent?.getStringExtra(EXTRA_PEER)
            val p = mp
            Thread {
                b.start(ip)
                if (p != null) b.startMusic(p)
            }.start()
        } else if (mp != null) {
            val p = mp
            Thread { current.startMusic(p) }.start()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (btRegistered) {
            try { unregisterReceiver(btReceiver) } catch (e: Exception) { }
            try { unregisterReceiver(effectReceiver) } catch (e: Exception) { }
            btRegistered = false
        }
        EffectBooster.enable(false)
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
        val b = bridge
        bridge = null
        if (b != null) Thread { b.stop() }.start()
        super.onDestroy()
    }

    private fun registerBtReceiver() {
        if (btRegistered) return
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val effectFilter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(btReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(effectReceiver, effectFilter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(btReceiver, filter)
            registerReceiver(effectReceiver, effectFilter)
        }
        btRegistered = true
    }

    private fun createProjection(code: Int, data: Intent): MediaProjection? {
        return try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(code, data)
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    val b = bridge
                    if (b != null) Thread { b.stopMusic() }.start()
                }
            }, Handler(Looper.getMainLooper()))
            projection = p
            p
        } catch (e: Exception) {
            BridgeState.error = "Música: ${e.message}"
            null
        }
    }

    private fun stopMusic() {
        val b = bridge
        if (b != null) Thread { b.stopMusic() }.start()
        try { projection?.stop() } catch (e: Exception) { }
        projection = null
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
