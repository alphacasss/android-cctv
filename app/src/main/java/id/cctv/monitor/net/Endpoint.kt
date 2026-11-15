package id.cctv.monitor.net

import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.RemoteSettings
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Endpoint yang benar-benar dipakai untuk satu kamera.
 *
 * [remote] = true berarti lalu lintas memakai alamat luar karena ponsel tidak
 * berada di subnet kamera, atau pengguna memaksa. [automatic] = true berarti
 * alamat/port berasal dari setelan global (IP publik terdeteksi), bukan dari
 * isian khusus kamera. [offLan] menandai ponsel sedang di luar subnet kamera.
 */
data class Endpoint(
    val host: String,
    val httpPort: Int,
    val rtspPort: Int,
    val remote: Boolean,
    val offLan: Boolean = false,
    val automatic: Boolean = false
) {
    val label: String get() = if (remote) "LUAR" else "LOKAL"

    /** Basis URL ISAPI tanpa garis miring di akhir. */
    val httpBase: String
        get() = if (httpPort == 80) "http://$host" else "http://$host:$httpPort"

    val describe: String
        get() = when {
            remote && automatic -> "$host — luar otomatis (HTTP $httpPort, RTSP $rtspPort)"
            remote -> "$host — luar (HTTP $httpPort, RTSP $rtspPort)"
            else -> "$host — lokal (HTTP $httpPort, RTSP $rtspPort)"
        }
}

/**
 * Menentukan apakah ponsel sedang berada di jaringan yang sama dengan kamera.
 * Perbandingan memakai subnet mask sebenarnya dari setiap antarmuka IPv4
 * (Wi-Fi, Ethernet, VPN), jadi kamera 192.168.100.x tetap dianggap lokal walau
 * ponsel memakai netmask /16.
 */
object EndpointResolver {

    private const val CACHE_MS = 5_000L

    @Volatile
    private var cachedPrefixes: List<Pair<Long, Int>> = emptyList()

    @Volatile
    private var cachedAt = 0L

    fun resolve(
        camera: Camera,
        remote: RemoteSettings = RemoteSettings(),
        forceRemote: Boolean = camera.forceRemote
    ): Endpoint {
        val offLan = !isLocalSubnet(camera.ip)
        val perCameraHost = normalizeHost(camera.remoteHost)
        val globalHost = if (remote.enabled) normalizeHost(remote.effectiveHost) else ""

        val host = perCameraHost.ifBlank { globalHost }
        if (!(forceRemote || offLan) || host.isEmpty()) {
            return Endpoint(
                host = camera.ip,
                httpPort = 80,
                rtspPort = 554,
                remote = false,
                offLan = offLan
            )
        }

        val automatic = perCameraHost.isEmpty()
        val httpPort = camera.remoteHttpPort.takeIf { it in 1..65535 }
            ?: if (remote.enabled) remote.httpPort(camera.id) else 80
        val rtspPort = camera.remoteRtspPort.takeIf { it in 1..65535 }
            ?: if (remote.enabled) remote.rtspPort(camera.id) else 554

        return Endpoint(
            host = host,
            httpPort = httpPort,
            rtspPort = rtspPort,
            remote = true,
            offLan = offLan,
            automatic = automatic
        )
    }

    /** True bila ada antarmuka aktif yang satu subnet dengan [ip]. */
    fun isLocalSubnet(ip: String): Boolean {
        val target = ipv4ToLong(ip) ?: return false
        return localPrefixes().any { (network, prefix) ->
            mask(target, prefix) == network
        }
    }

    /**
     * Membersihkan input pengguna: buang skema `http://`/`https://`, path, dan
     * spasi. Port ditangani terpisah lewat field port.
     */
    fun normalizeHost(raw: String): String {
        var value = raw.trim()
        if (value.isEmpty()) return ""
        value = value.substringAfter("://", value)
        value = value.substringBefore('/').substringBefore('?')
        return value.trim().trimEnd('.')
    }

    /** Validasi sederhana untuk DDNS/IPv4. */
    fun isValidHost(host: String): Boolean {
        if (host.isEmpty() || host.length > 253) return false
        if (host.contains(" ")) return false
        if (ipv4ToLong(host) != null) return true
        if (host.all { it.isDigit() || it == '.' }) return false
        val label = Regex("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        return host.split(".").all { it.isNotEmpty() && label.matches(it) }
    }

    private fun localPrefixes(): List<Pair<Long, Int>> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < CACHE_MS) return cachedPrefixes
        val list = ArrayList<Pair<Long, Int>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (address in nif.interfaceAddresses) {
                    val addr = address.address
                    if (addr !is Inet4Address) continue
                    val host = addr.hostAddress ?: continue
                    val value = ipv4ToLong(host) ?: continue
                    val prefix = address.networkPrefixLength.toInt().coerceIn(0, 32)
                    list.add(mask(value, prefix) to prefix)
                }
            }
        } catch (_: Throwable) {
            // Biarkan kosong: aplikasi akan memakai alamat luar bila ada.
        }
        cachedPrefixes = list
        cachedAt = now
        return list
    }

    private fun mask(value: Long, prefix: Int): Long {
        if (prefix <= 0) return 0
        if (prefix >= 32) return value
        val bits = 0xFFFFFFFFL shl (32 - prefix)
        return value and bits
    }

    private fun ipv4ToLong(text: String): Long? {
        val parts = text.trim().split(".")
        if (parts.size != 4) return null
        var result = 0L
        for (part in parts) {
            val number = part.toIntOrNull() ?: return null
            if (number !in 0..255) return null
            result = (result shl 8) or number.toLong()
        }
        return result and 0xFFFFFFFFL
    }
}
