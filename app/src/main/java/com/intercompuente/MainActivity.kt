package com.intercompuente

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var peerInput: EditText
    private lateinit var muteBox: CheckBox
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        val title = TextView(this).apply {
            text = "Intercom Puente"
            textSize = 24f
        }
        val help = TextView(this).apply {
            text = "1) Conecta cada teléfono a su intercomunicador por Bluetooth.\n" +
                "2) Pon ambos en la misma red: uno comparte hotspot y el otro se conecta a él.\n" +
                "3) Pulsa \"Iniciar puente\" en los dos.\n" +
                "Si no se encuentran solos, escribe abajo la IP del otro teléfono."
            textSize = 14f
            setPadding(0, pad / 2, 0, pad / 2)
        }
        peerInput = EditText(this).apply {
            hint = "IP del otro teléfono (opcional)"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val gainLabel = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, 0) }
        val gainBar = SeekBar(this).apply {
            max = 14   // 1.0x a 8.0x en pasos de 0.5x
            progress = prefs.getInt("gain", 4)
        }
        fun applyGainUi() {
            val g = 1f + gainBar.progress * 0.5f
            BridgeState.gain = g
            gainLabel.text = "Volumen de la voz recibida: " + g + "x"
            prefs.edit().putInt("gain", gainBar.progress).apply()
        }
        gainBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                applyGainUi()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        applyGainUi()
        muteBox = CheckBox(this).apply { text = "Silenciar mi micrófono" }
        startBtn = Button(this).apply { text = "Iniciar puente" }
        statusView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }

        root.addView(title)
        root.addView(help)
        root.addView(peerInput)
        root.addView(gainLabel)
        root.addView(gainBar)
        root.addView(muteBox)
        root.addView(startBtn)
        root.addView(statusView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        startBtn.setOnClickListener {
            if (BridgeState.running) stopBridge() else ensurePermissionsThenStart()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    private fun neededPermissions(): List<String> {
        val l = ArrayList<String>()
        l.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 31) l.add(Manifest.permission.BLUETOOTH_CONNECT)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun ensurePermissionsThenStart() {
        val missing = neededPermissions().filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startBridge() else requestPermissions(missing.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startBridge()
        } else {
            Toast.makeText(this, "Se necesita el permiso de micrófono", Toast.LENGTH_LONG).show()
        }
    }

    private fun startBridge() {
        val i = Intent(this, BridgeService::class.java)
            .putExtra(BridgeService.EXTRA_PEER, peerInput.text.toString().trim())
        startForegroundService(i)
        BridgeState.running = true
    }

    private fun stopBridge() {
        stopService(Intent(this, BridgeService::class.java))
        BridgeState.running = false
    }

    private fun refresh() {
        val s = BridgeState
        s.muted = muteBox.isChecked
        startBtn.text = if (s.running) "Detener puente" else "Iniciar puente"
        statusView.text = buildString {
            append("Estado: ").append(if (s.running) "activo" else "detenido").append('\n')
            append("Audio: ").append(s.route).append('\n')
            append("Otro teléfono: ").append(s.peer).append('\n')
            append("Enviados: ").append(s.tx).append("   Recibidos: ").append(s.rx).append('\n')
            append("Cola: ").append(s.queued).append('\n')
            append("Mis IPs:\n").append(s.myIps)
            if (s.error.isNotEmpty()) append("\n\nERROR: ").append(s.error)
        }
    }
}
