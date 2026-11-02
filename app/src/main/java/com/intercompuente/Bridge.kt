package com.intercompuente

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Puente de voz entre dos teléfonos por red local (hotspot / WiFi).
 *
 * Cada teléfono toma el audio del micrófono (que Android enruta al intercomunicador
 * Bluetooth) y lo manda por UDP al otro; lo que recibe lo reproduce por la salida
 * de voz (que Android enruta al mismo intercomunicador).
 *
 * Opcionalmente captura la música de otras apps y la reproduce con ganancia por la
 * misma ruta (AudioPlaybackCapture + MediaProjection).
 */
class Bridge(private val ctx: Context, private val onFatal: () -> Unit) {

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SAMPLES = 320            // 20 ms a 16 kHz
        const val FRAME_BYTES = FRAME_SAMPLES * 2
        const val AUDIO_PORT = 40000
        const val DISCOVERY_PORT = 40001
        private val MAGIC = "ICP1".toByteArray(Charsets.US_ASCII)
        private val HELLO = MAGIC + "HELLO".toByteArray(Charsets.US_ASCII)
    }

    @Volatile private var running = false
    @Volatile private var peer: InetAddress? = null
    @Volatile private var pinned = false
    @Volatile private var useBt = false
    @Volatile private var local: Set<String> = emptySet()
    @Volatile private var record: AudioRecord? = null
    @Volatile private var track: AudioTrack? = null
    @Volatile private var audioSocket: DatagramSocket? = null
    @Volatile private var helloSocket: DatagramSocket? = null
    @Volatile private var musicRunning = false

    private val threads = ArrayList<Thread>()
    private var musicThread: Thread? = null
    private val queue = ArrayBlockingQueue<ByteArray>(32)
    private val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLockLowLatency: WifiManager.WifiLock? = null
    private var wifiLockPerf: WifiManager.WifiLock? = null
    private var focusRequest: AudioFocusRequest? = null
    private var prevVoiceVolume = -1
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** Avisa cuando se conecta o desconecta un dispositivo de audio (p. ej. el intercom). */
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            if (running) chooseRoute()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (running) chooseRoute()
        }
    }

    // ---------------------------------------------------------------- ciclo de vida

    @Synchronized
    fun start(manualIp: String?) {
        if (running) return
        running = true
        BridgeState.running = true
        BridgeState.error = ""
        BridgeState.tx = 0
        BridgeState.rx = 0
        BridgeState.queued = 0
        BridgeState.peer = "buscando..."
        try {
            acquireLocks()
            routeAudio()
            try {
                audioManager.registerAudioDeviceCallback(deviceCallback, null)
            } catch (e: Exception) { }
            holdWifiNetwork()
            refreshLocal()

            if (!manualIp.isNullOrBlank()) {
                peer = InetAddress.getByName(manualIp.trim())
                pinned = true
            } else {
                pinned = false
                peer = guessGateway()
            }
            peer?.let { BridgeState.peer = (it.hostAddress ?: "?") + if (pinned) " (fija)" else "" }

            audioSocket = DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(AUDIO_PORT))
                soTimeout = 500
            }
            helloSocket = DatagramSocket(null as SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(DISCOVERY_PORT))
                broadcast = true
                soTimeout = 500
            }

            launch("capture") { captureLoop() }
            launch("receive") { receiveLoop() }
            launch("playback") { playbackLoop() }
            launch("discovery") { discoveryLoop() }
        } catch (e: Exception) {
            BridgeState.error = "No se pudo iniciar: ${e.message}"
            stopInternal()
            onFatal()
        }
    }

    @Synchronized
    fun stop() {
        stopInternal()
    }

    private fun stopInternal() {
        if (!running) return
        running = false
        BridgeState.running = false

        stopMusicInternal()

        try { audioSocket?.close() } catch (e: Exception) { }
        try { helloSocket?.close() } catch (e: Exception) { }
        for (t in threads) t.interrupt()
        for (t in threads) {
            try { t.join(1500) } catch (e: Exception) { }
        }
        threads.clear()

        try { record?.stop() } catch (e: Exception) { }
        try { record?.release() } catch (e: Exception) { }
        record = null
        try { track?.pause() } catch (e: Exception) { }
        try { track?.flush() } catch (e: Exception) { }
        try { track?.release() } catch (e: Exception) { }
        track = null
        queue.clear()

        try { audioManager.unregisterAudioDeviceCallback(deviceCallback) } catch (e: Exception) { }
        releaseWifiNetwork()
        resetAudio()
        try { wakeLock?.release() } catch (e: Exception) { }
        try { wifiLockLowLatency?.release() } catch (e: Exception) { }
        try { wifiLockPerf?.release() } catch (e: Exception) { }
        wakeLock = null
        wifiLockLowLatency = null
        wifiLockPerf = null
        peer = null
        BridgeState.peer = "-"
        BridgeState.route = "-"
        BridgeState.wifi = "-"
    }

    private fun launch(name: String, block: () -> Unit) {
        val t = Thread({
            try {
                block()
            } catch (e: Exception) {
                if (running) BridgeState.error = "$name: ${e.message}"
            }
        }, "bridge-$name")
        t.isDaemon = true
        threads.add(t)
        t.start()
    }

    // ---------------------------------------------------------------- bloqueos y red

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "intercompuente:bridge")
            wl.setReferenceCounted(false)
            wl.acquire()
            wakeLock = wl
        } catch (e: Exception) { }
        try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (Build.VERSION.SDK_INT >= 29) {
                val ll = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "intercompuente:wifi-ll")
                ll.setReferenceCounted(false)
                ll.acquire()
                wifiLockLowLatency = ll
            }
            val hp = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "intercompuente:wifi-hp")
            hp.setReferenceCounted(false)
            hp.acquire()
            wifiLockPerf = hp
        } catch (e: Exception) { }
    }

    /**
     * Pide a Android una red Wi-Fi y la mantiene "en uso". Así el sistema no la da de baja
     * por considerarla innecesaria (típico de un hotspot sin internet) mientras el puente corre.
     */
    private fun holdWifiNetwork() {
        BridgeState.wifi = "sin red Wi-Fi cliente (normal si este teléfono es el hotspot)"
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    BridgeState.wifi = "conectado"
                }

                override fun onLost(network: Network) {
                    BridgeState.wifi = "se perdió, esperando reconexión..."
                }
            }
            cm.requestNetwork(req, cb)
            netCallback = cb
        } catch (e: Exception) {
            BridgeState.wifi = "no disponible: ${e.message}"
        }
    }

    private fun releaseWifiNetwork() {
        val cb = netCallback ?: return
        netCallback = null
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- ruta de audio

    private fun routeAudio() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        boostVolume()
        requestVoiceFocus()
        chooseRoute()
    }

    /** Elige Bluetooth si hay un intercom conectado; si no, el altavoz del teléfono. */
    @Suppress("DEPRECATION")
    private fun chooseRoute() {
        val am = audioManager
        var name = "altavoz del teléfono"
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = am.availableCommunicationDevices
                val bt = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                    ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                val current = am.communicationDevice
                if (bt != null) {
                    val ok = if (!useBt || current == null || current.id != bt.id) {
                        am.setCommunicationDevice(bt)
                    } else {
                        true
                    }
                    useBt = ok
                    name = if (ok) "Bluetooth: ${bt.productName}" else "altavoz del teléfono (falló Bluetooth)"
                } else {
                    if (useBt || current == null) {
                        val spk = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        if (spk != null) am.setCommunicationDevice(spk)
                    }
                    useBt = false
                }
            } else {
                val hasBt = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    .any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
                if (hasBt) {
                    if (!useBt) {
                        am.startBluetoothSco()
                        am.isBluetoothScoOn = true
                    }
                    useBt = true
                    name = "Bluetooth (SCO)"
                } else {
                    if (useBt) {
                        am.stopBluetoothSco()
                        am.isBluetoothScoOn = false
                    }
                    am.isSpeakerphoneOn = true
                    useBt = false
                }
            }
        } catch (e: Exception) {
            BridgeState.error = "Ruta de audio: ${e.message}"
        }
        BridgeState.route = name
    }

    /** Volumen de llamada al máximo (se restaura al detener). */
    private fun boostVolume() {
        try {
            prevVoiceVolume = audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL)
            audioManager.setStreamVolume(
                AudioManager.STREAM_VOICE_CALL,
                audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL),
                0
            )
        } catch (e: Exception) { }
    }

    /** Foco de audio exclusivo: las apps de música se pausan mientras la voz está activa. */
    private fun requestVoiceFocus() {
        if (focusRequest != null) return
        try {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            audioManager.requestAudioFocus(req)
            focusRequest = req
        } catch (e: Exception) { }
    }

    private fun abandonVoiceFocus() {
        val req = focusRequest ?: return
        focusRequest = null
        try {
            audioManager.abandonAudioFocusRequest(req)
        } catch (e: Exception) { }
    }

    /** Sube el volumen de muestras PCM 16-bit little-endian; limitador suave contra distorsión. */
    private fun applyGain(f: ByteArray, gain: Float) {
        if (gain <= 1.01f) return
        var i = 0
        while (i + 1 < f.size) {
            val s = (f[i + 1].toInt() shl 8) or (f[i].toInt() and 0xff)
            val x = (s / 32768f) * gain
            val ax = abs(x)
            // rodilla baja: todo lo que pasa de 0.25 se comprime suavemente hacia 1.0,
            // así con ganancias altas el nivel promedio sube sin recortar de golpe
            val knee = 0.25f
            val y = if (ax < knee) {
                x
            } else {
                val limited = knee + (1f - knee) * tanh((ax - knee) / (1f - knee))
                if (x < 0f) -limited else limited
            }
            val o = (y * 32767f).toInt()
            f[i] = (o and 0xff).toByte()
            f[i + 1] = ((o shr 8) and 0xff).toByte()
            i += 2
        }
    }

    @Suppress("DEPRECATION")
    private fun resetAudio() {
        abandonVoiceFocus()
        try {
            if (prevVoiceVolume >= 0) {
                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, prevVoiceVolume, 0)
            }
        } catch (e: Exception) { }
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) { }
        useBt = false
    }

    private fun btInput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    private fun btOutput(): AudioDeviceInfo? =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }

    // ---------------------------------------------------------------- hilos de audio

    private fun captureLoop() {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, FRAME_BYTES * 4)
        )
        record = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            BridgeState.error = "No se pudo abrir el micrófono (¿permiso?)"
            return
        }
        if (useBt) {
            val d = btInput()
            if (d != null) rec.setPreferredDevice(d)
        }
        rec.startRecording()

        val socket = audioSocket ?: return
        val packet = ByteArray(MAGIC.size + FRAME_BYTES)
        System.arraycopy(MAGIC, 0, packet, 0, MAGIC.size)

        while (running) {
            var got = 0
            while (got < FRAME_BYTES && running) {
                val n = rec.read(packet, MAGIC.size + got, FRAME_BYTES - got)
                if (n > 0) {
                    got += n
                } else {
                    if (n < 0) BridgeState.error = "Error de micrófono ($n)"
                    Thread.sleep(10)
                }
            }
            if (!running) break
            val p = peer
            if (p != null && !BridgeState.muted) {
                try {
                    socket.send(DatagramPacket(packet, packet.size, p, AUDIO_PORT))
                    BridgeState.tx++
                } catch (e: Exception) {
                    if (running) BridgeState.error = "Envío: ${e.message}"
                }
            }
        }
    }

    private fun receiveLoop() {
        val socket = audioSocket ?: return
        val buf = ByteArray(MAGIC.size + FRAME_BYTES + 64)
        val dp = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                dp.length = buf.size
                socket.receive(dp)
                if (dp.length == MAGIC.size + FRAME_BYTES && hasMagic(buf)) {
                    learnPeer(dp.address)
                    BridgeState.rx++
                    val frame = buf.copyOfRange(MAGIC.size, MAGIC.size + FRAME_BYTES)
                    if (!queue.offer(frame)) {
                        queue.poll()
                        queue.offer(frame)
                    }
                }
            } catch (e: SocketTimeoutException) {
                // normal: seguimos esperando
            } catch (e: Exception) {
                if (running) {
                    BridgeState.error = "Recepción: ${e.message}"
                    Thread.sleep(200)
                }
            }
        }
    }

    private fun playbackLoop() {
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf, FRAME_BYTES * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        if (useBt) {
            val d = btOutput()
            if (d != null) t.setPreferredDevice(d)
        }
        t.play()

        val silence = ByteArray(FRAME_BYTES)
        var primed = false
        while (running) {
            if (!primed) {
                if (queue.size >= 3) {
                    primed = true
                } else {
                    Thread.sleep(5)
                    continue
                }
            }
            // si se acumuló demasiado retraso, descartamos lo más viejo
            while (queue.size > 8) queue.poll()
            val f = queue.poll(40, TimeUnit.MILLISECONDS)
            BridgeState.queued = queue.size
            if (f != null) {
                applyGain(f, BridgeState.gain)
                t.write(f, 0, f.size)
            } else {
                t.write(silence, 0, silence.size)
                primed = false
            }
        }
    }

    // ---------------------------------------------------------------- música propia

    /** Captura el audio de otras apps (música) y lo reproduce con ganancia por la ruta del intercom. */
    @Synchronized
    fun startMusic(p: MediaProjection) {
        if (!running || musicRunning) return
        if (Build.VERSION.SDK_INT < 29) {
            BridgeState.error = "La música necesita Android 10 o superior"
            return
        }
        // sin soltar el foco exclusivo, las apps de música se pausan solas y no hay nada que capturar
        abandonVoiceFocus()
        musicRunning = true
        BridgeState.musicOn = true
        val t = Thread({
            try {
                musicLoop(p)
            } catch (e: Exception) {
                if (running) BridgeState.error = "Música: ${e.message}"
            } finally {
                musicRunning = false
                BridgeState.musicOn = false
            }
        }, "bridge-music")
        t.isDaemon = true
        musicThread = t
        t.start()
    }

    @Synchronized
    fun stopMusic() {
        stopMusicInternal()
        if (running) requestVoiceFocus()
    }

    private fun stopMusicInternal() {
        if (!musicRunning && musicThread == null) return
        musicRunning = false
        val t = musicThread
        try { t?.interrupt() } catch (e: Exception) { }
        try { t?.join(1500) } catch (e: Exception) { }
        musicThread = null
        BridgeState.musicOn = false
    }

    private fun buildCapture(cfg: AudioPlaybackCaptureConfiguration, rate: Int): AudioRecord? {
        return try {
            val minBuf = AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(cfg)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBuf, rate / 50 * 2 * 4))
                .build()
        } catch (e: Exception) {
            null
        }
    }

    private fun musicLoop(p: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()

        var rate = 16000
        var rec = buildCapture(cfg, rate)
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec?.release() } catch (e: Exception) { }
            rate = 48000
            rec = buildCapture(cfg, rate)
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            BridgeState.error = "No se pudo capturar el audio de la música"
            return
        }

        val frameBytes = rate / 50 * 2
        val trackMin = AudioTrack.getMinBufferSize(
            rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val mt = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(trackMin, frameBytes * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (useBt) {
            val d = btOutput()
            if (d != null) mt.setPreferredDevice(d)
        }

        try {
            rec.startRecording()
            mt.play()
            val buf = ByteArray(frameBytes)
            while (running && musicRunning) {
                var got = 0
                while (got < frameBytes && running && musicRunning) {
                    val n = rec.read(buf, got, frameBytes - got)
                    if (n > 0) {
                        got += n
                    } else {
                        if (n < 0) BridgeState.error = "Error al capturar la música ($n)"
                        Thread.sleep(10)
                    }
                }
                if (!(running && musicRunning)) break
                applyGain(buf, BridgeState.musicGain)
                mt.write(buf, 0, frameBytes)
            }
        } finally {
            try { rec.stop() } catch (e: Exception) { }
            try { rec.release() } catch (e: Exception) { }
            try { mt.pause() } catch (e: Exception) { }
            try { mt.flush() } catch (e: Exception) { }
            try { mt.release() } catch (e: Exception) { }
        }
    }

    // ---------------------------------------------------------------- descubrimiento

    private fun discoveryLoop() {
        val socket = helloSocket ?: return
        val buf = ByteArray(64)
        val dp = DatagramPacket(buf, buf.size)
        var lastSend = 0L
        while (running) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastSend >= 1000) {
                lastSend = now
                refreshLocal()
                sendHello(socket)
            }
            try {
                dp.length = buf.size
                socket.receive(dp)
                if (isHello(buf, dp.length)) learnPeer(dp.address)
            } catch (e: SocketTimeoutException) {
                // normal
            } catch (e: Exception) {
                if (running) Thread.sleep(200)
            }
        }
    }

    private fun sendHello(socket: DatagramSocket) {
        for (target in broadcastTargets()) {
            try {
                socket.send(DatagramPacket(HELLO, HELLO.size, target, DISCOVERY_PORT))
            } catch (e: Exception) { }
        }
        // si ya conocemos al otro teléfono, también le avisamos directo
        val p = peer
        if (p != null) {
            try {
                socket.send(DatagramPacket(HELLO, HELLO.size, p, DISCOVERY_PORT))
            } catch (e: Exception) { }
        }
    }

    private fun broadcastTargets(): List<InetAddress> {
        val out = ArrayList<InetAddress>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val b = ia.broadcast
                    if (b != null) out.add(b)
                }
            }
        } catch (e: Exception) { }
        try {
            out.add(InetAddress.getByName("255.255.255.255"))
        } catch (e: Exception) { }
        return out
    }

    private fun learnPeer(addr: InetAddress?) {
        if (addr == null || pinned) return
        if (peer == addr) return
        if (addr.isLoopbackAddress) return
        if (local.contains(addr.hostAddress)) return
        peer = addr
        BridgeState.peer = addr.hostAddress ?: "?"
    }

    private fun refreshLocal() {
        val set = HashSet<String>()
        val ips = ArrayList<String>()
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    a.hostAddress?.let { set.add(it) }
                    if (a is Inet4Address) ips.add("${a.hostAddress}  (${ni.name})")
                }
            }
        } catch (e: Exception) { }
        local = set
        BridgeState.myIps = if (ips.isEmpty()) "sin red" else ips.joinToString("\n")
    }

    @Suppress("DEPRECATION")
    private fun guessGateway(): InetAddress? {
        return try {
            val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val g = wm.dhcpInfo?.gateway ?: 0
            if (g == 0) {
                null
            } else {
                InetAddress.getByAddress(
                    byteArrayOf(
                        (g and 0xff).toByte(),
                        ((g shr 8) and 0xff).toByte(),
                        ((g shr 16) and 0xff).toByte(),
                        ((g shr 24) and 0xff).toByte()
                    )
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------- utilidades

    private fun hasMagic(b: ByteArray): Boolean {
        for (i in MAGIC.indices) if (b[i] != MAGIC[i]) return false
        return true
    }

    private fun isHello(b: ByteArray, len: Int): Boolean {
        if (len != HELLO.size) return false
        for (i in HELLO.indices) if (b[i] != HELLO[i]) return false
        return true
    }
}
