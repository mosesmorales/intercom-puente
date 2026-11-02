package com.intercompuente

import android.content.Intent
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import kotlin.math.log10

/**
 * Potenciador para apps que anuncian su sesión de audio (Spotify y otros reproductores con
 * soporte de ecualizador). No captura audio: aplica un LoudnessEnhancer directamente a la
 * sesión del reproductor, así que funciona aunque la app bloquee la captura.
 */
object EffectBooster {
    private val sessions = HashMap<Int, String>()            // sesión -> paquete
    private val effects = HashMap<Int, LoudnessEnhancer>()   // sesión -> efecto aplicado
    private var enabled = false
    private var gain = 1f

    /** Procesa los avisos OPEN / CLOSE_AUDIO_EFFECT_CONTROL_SESSION que mandan los reproductores. */
    @Synchronized
    fun handle(intent: Intent) {
        val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, AudioEffect.ERROR_BAD_VALUE)
        if (session <= 0) return   // 0 = mezcla global (ya no se permite), negativo = dato inválido
        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                sessions[session] = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME) ?: "?"
                if (enabled) attach(session)
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                sessions.remove(session)
                detach(session)
            }
        }
    }

    @Synchronized
    fun enable(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            for (s in sessions.keys.toList()) attach(s)
        } else {
            for (s in effects.keys.toList()) detach(s)
        }
    }

    @Synchronized
    fun setGain(g: Float) {
        gain = g
        for (le in effects.values) applyGain(le)
    }

    @Synchronized
    fun status(): String {
        if (!enabled) return "apagado"
        if (sessions.isEmpty()) return "esperando a la app (pausa y vuelve a dar play)"
        val sb = StringBuilder()
        for ((id, pkg) in sessions) {
            if (sb.isNotEmpty()) sb.append("; ")
            sb.append(pkg)
            sb.append(if (effects.containsKey(id)) " (+" + db() + " dB aplicado)" else " (sin aplicar)")
        }
        return sb.toString()
    }

    private fun db(): Int = (20.0 * log10(gain.toDouble().coerceAtLeast(1.0))).toInt()

    private fun attach(session: Int) {
        if (effects.containsKey(session)) return
        try {
            val le = LoudnessEnhancer(session)
            applyGain(le)
            le.setEnabled(true)
            effects[session] = le
        } catch (e: Exception) {
            BridgeState.error = "Potenciador: ${e.message}"
        }
    }

    private fun detach(session: Int) {
        val le = effects.remove(session) ?: return
        try { le.setEnabled(false) } catch (e: Exception) { }
        try { le.release() } catch (e: Exception) { }
    }

    private fun applyGain(le: LoudnessEnhancer) {
        try {
            // ganancia en milibeles: 20 dB = 10x de amplitud
            le.setTargetGain((2000.0 * log10(gain.toDouble().coerceAtLeast(1.0))).toInt())
        } catch (e: Exception) {
            BridgeState.error = "Potenciador: ${e.message}"
        }
    }
}
