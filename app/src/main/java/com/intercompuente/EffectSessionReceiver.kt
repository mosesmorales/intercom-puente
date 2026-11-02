package com.intercompuente

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Recibe los avisos de sesión de audio de Spotify y otros reproductores (declarado en el manifiesto). */
class EffectSessionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val i = intent ?: return
        EffectBooster.handle(i)
    }
}
