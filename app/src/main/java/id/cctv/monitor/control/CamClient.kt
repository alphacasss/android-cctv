package id.cctv.monitor.control

import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.net.IsapiResponse

/**
 * Abstraksi klien kontrol kamera yang bisa diimplementasikan oleh
 * klien ISAPI lokal ([Local]) atau klien passthrough cloud Hik-Connect
 * ([Cloud]). Semua operasi jaringan memakai [suspend] supaya bisa dipanggil
 * dari konteks koroutine baik di jaringan lokal maupun luar.
 */
sealed class CamClient {
    /** Nama rute untuk log diagnostik; bukan label UI. */
    abstract val label: String

    abstract suspend fun get(path: String): IsapiResponse
    abstract suspend fun put(path: String, body: String): IsapiResponse

    /** Klien ISAPI langsung ke IP kamera (jaringan lokal / VPN / DDNS yang terhubung). */
    class Local(private val client: IsapiClient) : CamClient() {
        override val label: String = "ISAPI-LANGSUNG"
        override suspend fun get(path: String): IsapiResponse = client.get(path)
        override suspend fun put(path: String, body: String): IsapiResponse =
            client.put(path, body)
    }

    /** Klien ISAPI lewat passthrough Hik-Connect cloud (jaringan luar, tanpa port forwarding). */
    class Cloud(private val client: CloudIsapiClient) : CamClient() {
        override val label: String = "CLOUD-PASSTHROUGH"
        override suspend fun get(path: String): IsapiResponse = client.get(path)
        override suspend fun put(path: String, body: String): IsapiResponse =
            client.put(path, body)
    }
}
