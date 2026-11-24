package id.cctv.monitor.cloud.stream

import java.io.File

/** Kontrak minimal pemutar cloud yang dipakai [id.cctv.monitor.player.PlayerManager]. */
interface CloudPlayer {
    fun start(mainStream: Boolean)
    fun stop()
    fun release()
    fun showSurface(visible: Boolean)

    /** True bila pemutar bisa merekam sesi live yang sedang dipakai. */
    val supportsRecording: Boolean get() = false

    /** Rekam sesi live yang sudah terbuka ke [file]. */
    fun startRecording(file: File): Boolean = false

    /** Hentikan rekaman sesi ini. */
    fun stopRecording(): Boolean = false
}
