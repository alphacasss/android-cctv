package id.cctv.monitor.cloud.stream

/** Kontrak minimal pemutar cloud yang dipakai [id.cctv.monitor.player.PlayerManager]. */
interface CloudPlayer {
    fun start(mainStream: Boolean)
    fun stop()
    fun release()
    fun showSurface(visible: Boolean)
}
