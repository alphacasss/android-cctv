package id.cctv.monitor.cloud

import java.io.Serializable

/**
 * Satu perangkat/kamera yang terikat ke akun Hik-Connect.
 *
 * Untuk NVR, satu entri mewakili **satu kanal kamera** (channel) supaya bisa
 * dipetakan langsung ke tile CCTV. Data ini menyimpan metadata hasil
 * sinkronisasi cloud. Live stream diputar lewat relay P2P cloud bila ponsel
 * berada di luar subnet kamera; [streamUrl] hanya dipakai bila ada penyedia
 * stream eksternal yang memberi URL siap pakai.
 */
data class CloudDevice(
    /** Id internal aplikasi (id kamera di [id.cctv.monitor.data.Camera]). */
    val internalId: Int = 0,
    /** Id perangkat di Hik-Connect (biasanya `fullSerial`). */
    var cloudDeviceId: String = "",
    /** Serial perangkat tanpa suffix kanal (dipakai endpoint cloud). */
    var serial: String = "",
    /**
     * Serial perangkat kanal (mis. IPC di balik NVR). Cloud memakai serial
     * ini untuk endpoint ISAPI seperti `/ISAPI/System/Network/EZVIZ`.
     */
    var channelSerial: String = "",
    var name: String = "",
    /** Kanal kamera pada perangkat (1..N untuk NVR). */
    var channel: Int = 1,
    var deviceType: String = "",
    var firmware: String = "",
    var isNvr: Boolean = false,
    var online: Boolean = true,
    /** True bila gambar/video perangkat memakai enkripsi (isEncrypt). */
    var encrypted: Boolean = false,
    /**
     * Kode verifikasi perangkat (biasanya 6 huruf pada label NVR). Dipakai
     * untuk mendekripsi snapshot dan video live perangkat berenkripsi.
     */
    var verificationCode: String = "",
    /**
     * Nilai `encryptPwd` dari status cloud (32 digit hex). Cloud Hik-Connect
     * selalu menyertakannya untuk perangkat berenkripsi, jadi aplikasi bisa
     * mendekripsi video tanpa meminta kode 6 huruf ke pengguna.
     */
    var encryptionPwd: String = "",
    /** IP lokal yang dilaporkan cloud (berguna saat berada di rumah). */
    var localIp: String = "",
    /** IP WAN perangkat bila dilaporkan cloud. */
    var wanIp: String = "",
    /**
     * URL stream siap pakai (rtsp:// atau http(s)://) dari penyedia eksternal.
     * Kosong = aplikasi memakai relay P2P cloud atau rute RTSP biasa.
     */
    var streamUrl: String = "",
    /** Kredensial opsional untuk [streamUrl] (diisi SDK/penyedia stream). */
    var username: String = "",
    var password: String = "",
    var rtspPort: Int = 554
) : Serializable {

    val displayName: String
        get() = buildString {
            append(name.ifBlank { serial.ifBlank { "Cloud device" } })
            if (isNvr && channel > 0) append(" • CH").append(channel)
        }

    /** True bila metadata device sudah cukup untuk dipakai. */
    val valid: Boolean
        get() = cloudDeviceId.isNotBlank() || serial.isNotBlank()
}
