package com.intercompuente

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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

    companion object {
        private const val REQ_PERMS = 1
        private const val REQ_PROJECTION = 2
    }

    private lateinit var statusView: TextView
    private lateinit var startBtn: Button
    private lateinit var peerInput: EditText
    private lateinit var muteBox: CheckBox
    private lateinit var musicBox: CheckBox
    private val handler = Handler(Looper.getMainLooper())
    private var pendingStart = false

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

        root.addView(title)
        root.addView(help)
        root.addView(peerInput)

        addGainRow(root, pad, "Volumen de la voz recibida", "gain2", 5, 19) { BridgeState.gain = it }

        musicBox = CheckBox(this).apply { text = "Mezclar mi música (captura el audio de otras apps)" }
        root.addView(musicBox)
        addGainRow(root, pad, "Volumen de mi música", "mgain", 2, 9) { BridgeState.musicGain = it }

        muteBox = CheckBox(this).apply { text = "Silenciar mi micrófono" }
        startBtn = Button(this).apply { text = "Iniciar puente" }
        statusView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 13f
            setPadding(0, pad, 0, 0)
        }
        root.addView(muteBox)
        root.addView(startBtn)
        root.addView(statusView)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        startBtn.setOnClickListener {
            if (BridgeState.running) stopBridge() else ensurePermissionsThenStart()
        }

        // solo reacciona a toques del usuario, no a cambios hechos por código
        musicBox.setOnClickListener {
            if (BridgeState.running) {
                if (musicBox.isChecked) requestProjection(false) else stopMusic()
            }
        }
    }

    private fun addGainRow(
        root: LinearLayout,
        pad: Int,
        title: String,
        key: String,
        def: Int,
        maxProgress: Int,
        onGain: (Float) -> Unit
    ) {
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val label = TextView(this).apply {
            textSize = 14f
            setPadding(0, pad / 2, 0, 0)
        }
        val bar = SeekBar(this).apply {
            max = maxProgress
            progress = prefs.getInt(key, def)
        }
        fun update() {
            val g = 1 + bar.progress
            onGain(g.toFloat())
            label.text = title + ": " + g + "x"
            prefs.edit().putInt(key, bar.progress).apply()
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                update()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        update()
        root.addView(label)
        root.addView(bar)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        handler.removeCallbacks(ticker)
        super.onPause()
    }

    // ---------------------------------------------------------------- permisos

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
        if (missing.isEmpty()) proceedStart() else requestPermissions(missing.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            proceedStart()
        } else {
            Toast.makeText(this, "Se necesita el permiso de micrófono", Toast.LENGTH_LONG).show()
        }
    }

    // ---------------------------------------------------------------- inicio / paro

    private fun proceedStart() {
        if (musicBox.isChecked && Build.VERSION.SDK_INT >= 29) {
            requestProjection(true)
        } else {
            startBridge(0, null)
        }
    }

    /** Android pide confirmar la captura de audio; la app solo toma el audio, no la pantalla. */
    private fun requestProjection(thenStart: Boolean) {
        pendingStart = thenStart
        try {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION)
        } catch (e: Exception) {
            musicBox.isChecked = false
            Toast.makeText(this, "No se pudo pedir la captura de audio", Toast.LENGTH_LONG).show()
            if (thenStart) startBridge(0, null)
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PROJECTION) return
        val ok = resultCode == RESULT_OK && data != null
        if (!ok) {
            musicBox.isChecked = false
            Toast.makeText(this, "Sin permiso de captura no hay música", Toast.LENGTH_LONG).show()
        }
        if (pendingStart) {
            startBridge(resultCode, if (ok) data else null)
        } else if (ok && data != null) {
            val i = Intent(this, BridgeService::class.java)
                .putExtra(BridgeService.EXTRA_MP_CODE, resultCode)
                .putExtra(BridgeService.EXTRA_MP_DATA, data)
            startService(i)
        }
        pendingStart = false
    }

    private fun startBridge(mpCode: Int, mpData: Intent?) {
        val i = Intent(this, BridgeService::class.java)
            .putExtra(BridgeService.EXTRA_PEER, peerInput.text.toString().trim())
        if (mpData != null) {
            i.putExtra(BridgeService.EXTRA_MP_CODE, mpCode)
            i.putExtra(BridgeService.EXTRA_MP_DATA, mpData)
        }
        startForegroundService(i)
        BridgeState.running = true
        askBatteryExemption()
    }

    private fun stopBridge() {
        stopService(Intent(this, BridgeService::class.java))
        BridgeState.running = false
    }

    private fun stopMusic() {
        startService(Intent(this, BridgeService::class.java).setAction(BridgeService.ACTION_MUSIC_OFF))
    }

    /** Pide (una sola vez) que Android no cierre la app ni apague el Wi-Fi para ahorrar batería. */
    private fun askBatteryExemption() {
        try {
            val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName) && !prefs.getBoolean("asked_battery", false)) {
                prefs.edit().putBoolean("asked_battery", true).apply()
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                        .setData(Uri.parse("package:$packageName"))
                )
            }
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- pantalla

    private fun btSummary(): String {
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter != null && !adapter.isEnabled) return "apagado"
        } catch (e: Exception) { }
        val names = LinkedHashSet<String>()
        try {
            val am = getSystemService(AudioManager::class.java)
            for (d in am.getDevices(AudioManager.GET_DEVICES_ALL)) {
                val t = d.type
                val isBt = t == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    t == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    (Build.VERSION.SDK_INT >= 31 && t == AudioDeviceInfo.TYPE_BLE_HEADSET)
                if (isBt) {
                    val n = d.productName.toString()
                    names.add(if (n.isBlank()) "dispositivo Bluetooth" else n)
                }
            }
        } catch (e: Exception) { }
        return if (names.isEmpty()) "encendido, sin audio conectado" else names.joinToString(", ")
    }

    private fun refresh() {
        val s = BridgeState
        s.muted = muteBox.isChecked
        startBtn.text = if (s.running) "Detener puente" else "Iniciar puente"
        statusView.text = buildString {
            append("Estado: ").append(if (s.running) "activo" else "detenido").append('\n')
            append("Bluetooth: ").append(btSummary()).append('\n')
            append("Audio: ").append(s.route).append('\n')
            append("Wi-Fi: ").append(s.wifi).append('\n')
            append("Música: ").append(if (s.musicOn) "activa" else "apagada").append('\n')
            append("Otro teléfono: ").append(s.peer).append('\n')
            append("Enviados: ").append(s.tx).append("   Recibidos: ").append(s.rx).append('\n')
            append("Cola: ").append(s.queued).append('\n')
            append("Mis IPs:\n").append(s.myIps)
            if (s.error.isNotEmpty()) append("\n\nERROR: ").append(s.error)
        }
    }
}
