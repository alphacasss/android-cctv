package id.cctv.monitor.data

import java.net.URLEncoder

/**
 * Satu entri kamera. [user]/[pass] kosong berarti memakai kredensial default
 * dari Setelan (AppPrefs).
 */
enum class DayNight { AUTO, NIGHT, DAY }

data class Camera(
    val id: Int,
    var name: String,
    var ip: String,
    var channel: Int,
    var isNvr: Boolean,
    var enabled: Boolean,
    var user: String,
    var pass: String,
    /**
     * Alamat dari luar jaringan lokal: DDNS atau IP publik. Kosong berarti
     * memakai alamat luar otomatis dari Setelan (IP publik terdeteksi).
     * [remoteHttpPort]/[remoteRtspPort] bernilai 0 berarti otomatis
     * (port dasar + nomor kamera).
     */
    var remoteHost: String = "",
    var remoteHttpPort: Int = 0,
    var remoteRtspPort: Int = 0,
    /** Selalu pakai [remoteHost] walau ponsel sedang berada di LAN. */
    var forceRemote: Boolean = false,
    var dayNight: DayNight = DayNight.AUTO,
    var movementDetect: Boolean = true
) {
    val displayName: String
        get() = if (name.isBlank()) "CAM-%02d".format(id) else name

    /** 101 = main stream, 102 = sub stream. */
    fun streamId(main: Boolean): Int = channel * 100 + if (main) 1 else 2

    fun rtspUrl(
        main: Boolean,
        user: String,
        pass: String,
        host: String = ip,
        rtspPort: Int = 554
    ): String {
        val u = encode(user)
        val p = encode(pass)
        val auth = if (u.isEmpty() && p.isEmpty()) "" else "$u:$p@"
        return "rtsp://$auth$host:$rtspPort/Streaming/Channels/${streamId(main)}"
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}

/** Status yang dibuat aplikasi ini (bukan status yang dibaca ulang dari kamera). */
data class CameraFlags(
    val black: Boolean = false,
    val streamOff: Boolean = false,
    val rtspOff: Boolean = false,
    val nvrOff: Boolean = false
) {
    val blocked: Boolean get() = streamOff || rtspOff || nvrOff
    val any: Boolean get() = black || blocked
}
