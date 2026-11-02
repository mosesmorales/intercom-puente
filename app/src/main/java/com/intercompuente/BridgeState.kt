package com.intercompuente

/** Estado compartido entre el servicio (que hace el trabajo) y la pantalla (que lo muestra). */
object BridgeState {
    @Volatile var running: Boolean = false
    @Volatile var muted: Boolean = false
    @Volatile var gain: Float = 6f
    @Volatile var musicGain: Float = 3f
    @Volatile var musicOn: Boolean = false
    @Volatile var route: String = "-"
    @Volatile var peer: String = "-"
    @Volatile var wifi: String = "-"
    @Volatile var myIps: String = "-"
    @Volatile var tx: Long = 0
    @Volatile var rx: Long = 0
    @Volatile var queued: Int = 0
    @Volatile var error: String = ""
}
