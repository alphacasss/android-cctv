package id.cctv.monitor.cloud

import android.content.Context
import android.util.Base64
import android.util.Log
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.net.IsapiException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Implementasi [HikVisionCloudApi] memakai API web Hik-Connect.
 *
 * Alur:
 *  1. `POST /v3/users/login/v2` (form: account + md5(password)).
 *  2. `GET  /v3/userdevices/v1/devices/pagelist` (header `sessionId`).
 *  3. NVR: `GET /v3/userdevices/v1/cameras/info?deviceSerial=...` → satu entri
 *     per kanal.
 *  4. `PUT /v3/apigateway/login` untuk memperbarui sesi yang kedaluwarsa.
 *
 * Catatan teknis: stream live Hik-Connect memakai protokol relay P2P
 * `ysproto://` (VTM → VTDU) dengan amplop ECDH P-256 + ChaCha20, bukan RTSP.
 * [streamSource] mengambil server VTM, kunci publik relay, `streamBizUrl`, dan
 * token sekali pakai; pemutaran dilakukan `CloudVideoPlayer` lalu jatuh ke
 * rute LAN/DDNS/VPN bila relay gagal (lihat `PlayerManager`).
 */
class HikVisionCloudRepository(
    context: Context,
    private val authManager: CloudAuthManager,
    private val prefs: AppPrefs
) : HikVisionCloudApi {

    private val client = SdkClient(authManager.apiDomain())
    private val streamSourceCache = java.util.concurrent.ConcurrentHashMap<String, CachedSource>()

    private val _status = MutableStateFlow<CloudRepoStatus>(CloudRepoStatus.Idle)
    val status: StateFlow<CloudRepoStatus> = _status.asStateFlow()

    override suspend fun login(email: String, password: String): CloudSession {
        _status.value = CloudRepoStatus.Loading
        return try {
            val session = withContext(Dispatchers.IO) { loginInternal(email, password) }
            authManager.saveSession(session)
            authManager.rememberEmail(email)
            client.switchDomain(session.apiDomain)
            client.setSessionId(session.accessToken)
            _status.value = CloudRepoStatus.Ready
            session
        } catch (e: Exception) {
            val message = friendlyMessage(e)
            Log.e(TAG, "Login cloud gagal: $message", e)
            _status.value = CloudRepoStatus.Error(message)
            throw IllegalStateException(message, e)
        }
    }

    override suspend fun syncDevices(session: CloudSession): List<CloudDevice> {
        _status.value = CloudRepoStatus.Syncing
        return try {
            val fresh = ensureFresh(session)
            val devices = withContext(Dispatchers.IO) {
                try {
                    fetchDevices(fresh)
                } catch (e: CloudApiException) {
                    // Token bisa ditolak walau belum terlihat kedaluwarsa
                    // (mis. jam perangkat bergeser). Refresh sekali lalu ulangi.
                    if (e.httpCode != 401) throw e
                    val renewed = refreshInternal(fresh)
                    authManager.saveSession(renewed)
                    fetchDevices(renewed)
                }
            }
            _status.value = CloudRepoStatus.Ready
            devices
        } catch (e: Exception) {
            val message = friendlyMessage(e)
            Log.e(TAG, "Sinkronisasi device gagal: $message", e)
            _status.value = CloudRepoStatus.Error(message)
            throw IllegalStateException(message, e)
        }
    }

    override suspend fun getStreamUrl(device: CloudDevice): String = device.streamUrl

    /**
     * Lengkapi [CloudDevice.verificationCode] untuk semua kanal sebelum
     * diimpor ke daftar kamera.
     *
     * Urutan sumber kode: cache per serial (cukup diisi sekali untuk NVR),
     * ISAPI lewat cloud (tanpa perlu berada di rumah), lalu ISAPI lokal bila
     * ponsel berada di jaringan yang sama dengan perangkat. Perangkat yang
     * tidak terenkripsi atau sudah punya kode dibiarkan apa adanya.
     */
    suspend fun prepareForImport(
        session: CloudSession?,
        devices: List<CloudDevice>,
        user: String = "",
        pass: String = ""
    ): List<CloudDevice> = withContext(Dispatchers.IO) {
        val current = session ?: authManager.session.value
        val resolved = HashMap<String, String>()
        devices.map { device ->
            if (device.verificationCode.isNotBlank()) return@map device

            val serial = device.serial.ifBlank { device.cloudDeviceId.substringBefore('#') }
            val code = resolved[serial] ?: run {
                val cached = prefs.cloudVerificationCode(serial)
                val value = cached.ifBlank {
                    if (device.encrypted) {
                        resolveVerificationCode(current, device, user, pass)
                    } else {
                        ""
                    }
                }
                // Simpan juga kegagalan agar satu NVR tidak dicoba berulang
                // untuk setiap kanal pada sesi impor yang sama.
                resolved[serial] = value
                value
            }
            if (code.isNotBlank()) device.copy(verificationCode = code) else device
        }
    }

    /**
     * Cari kode verifikasi perangkat ("Stream Encryption") sekali dan simpan
     * per serial agar semua kanal NVR memakai kunci yang sama.
     *
     * Cloud memakai serial perangkat kanal (IPC di balik NVR) pada endpoint
     * ISAPI, jadi serial NVR dicoba lebih dulu lalu serial kanal.
     */
    suspend fun resolveVerificationCode(
        session: CloudSession?,
        device: CloudDevice,
        user: String = "",
        pass: String = ""
    ): String {
        val serial = device.serial.ifBlank { device.cloudDeviceId.substringBefore('#') }
        val channelSerial = device.channelSerial.trim()
        val serials = listOf(channelSerial, serial)
            .filter { it.isNotBlank() }
            .distinct()
        if (serials.isEmpty()) return ""
        for (candidate in serials) {
            prefs.cloudVerificationCode(candidate).takeIf { it.isNotBlank() }?.let {
                return it
            }
        }

        for (candidate in serials) {
            val fromCloud = try {
                fetchCodeFromCloud(session, candidate, device.channel)
            } catch (e: Exception) {
                Log.d(TAG, "Kode $candidate via cloud tidak tersedia: ${e.message}")
                ""
            }
            if (fromCloud.isNotBlank()) {
                serials.forEach { prefs.saveCloudVerificationCode(it, fromCloud) }
                return fromCloud
            }
        }

        val fromLan = fetchCodeFromLan(device, user, pass)
        if (fromLan.isNotBlank()) {
            serials.forEach { prefs.saveCloudVerificationCode(it, fromLan) }
        }
        return fromLan
    }

    /**
     * Segarkan kode verifikasi langsung dari perangkat di LAN (ISAPI digest).
     *
     * Saat ponsel berada di rumah, respons perangkat adalah sumber paling
     * otoritatif: kode yang tersimpan salah (mis. hasil tebakan lama) ikut
     * diperbaiki. Hasil disimpan untuk semua serial yang diberikan agar semua
     * kanal NVR memakai kunci yang sama.
     */
    suspend fun refreshVerificationCodeFromLan(
        host: String,
        user: String,
        pass: String,
        serials: List<String>
    ): String = withContext(Dispatchers.IO) {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) return@withContext ""
        val loginUser = user.ifBlank { prefs.defaultUser }
        val loginPass = pass.ifBlank { prefs.defaultPass }
        if (loginUser.isBlank() || loginPass.isBlank()) return@withContext ""
        val code = fetchCodeFromHost(cleanHost, loginUser, loginPass)
        if (code.isNotBlank()) {
            serials.filter { it.isNotBlank() }.distinct()
                .forEach { prefs.saveCloudVerificationCode(it, code) }
        }
        code
    }

    override suspend fun refreshToken(session: CloudSession): CloudSession {
        return withContext(Dispatchers.IO) { refreshInternal(session) }
    }

    override suspend fun logout(session: CloudSession) {
        client.setSessionId(null)
        authManager.clearSession()
        _status.value = CloudRepoStatus.Idle
    }

    /**
     * Ambil foto sekali-jalan lewat cloud (`/v3/devices/security/capture`).
     * Berguna di luar rumah tanpa port forwarding. Cloud membatasi capture
     * sekitar sekali per 10 menit per kanal.
     *
     * @return JPEG, atau null bila perangkat offline, capture ditolak, atau
     *   snapshot terenkripsi tanpa kode verifikasi yang benar.
     */
    suspend fun captureSnapshot(
        session: CloudSession?,
        device: CloudDevice,
        mainStream: Boolean = true
    ): ByteArray? = withContext(Dispatchers.IO) {
        val current = session ?: authManager.session.value ?: return@withContext null
        val fresh = try {
            ensureFresh(current)
        } catch (e: Exception) {
            Log.w(TAG, "Snapshot cloud dilewati: ${friendlyMessage(e)}")
            return@withContext null
        }
        val serial = device.serial.ifBlank { device.cloudDeviceId }
        if (serial.isBlank()) return@withContext null
        val channel = if (device.channel > 0) device.channel else 1
        val encryptedFlag = if (device.encrypted) 1 else 0
        val query = "deviceSerial=${SdkClient.encode(serial)}" +
            "&channelNo=$channel&isEncrypted=$encryptedFlag"
        val response = try {
            client.putEmpty("/v3/devices/security/capture?$query")
        } catch (e: Exception) {
            Log.w(TAG, "Capture cloud gagal: ${friendlyMessage(e)}")
            return@withContext null
        }
        val picUrl = response.optJSONObject("captureInfo")?.optString("picUrl").orEmpty()
        if (picUrl.isBlank()) return@withContext null
        // URL gambar berasal dari cloud; jangan sampai URL aneh/relatif
        // menjatuhkan seluruh alur snapshot.
        val bytes = try {
            client.getBytes(picUrl)
        } catch (e: Exception) {
            Log.w(TAG, "Unduh snapshot cloud gagal: ${e.message}")
            null
        } ?: return@withContext null
        if (!looksEncrypted(bytes)) return@withContext bytes
        val code = device.verificationCode.trim()
            .ifBlank { prefs.cloudVerificationCode(device.channelSerial) }
            .ifBlank { prefs.cloudVerificationCode(serial) }
        if (code.isBlank()) {
            Log.w(
                TAG,
                "Snapshot cloud terenkripsi; isi kode verifikasi perangkat " +
                    "di BIND KE CLOUD agar gambar bisa dibuka."
            )
            return@withContext null
        }
        return@withContext decryptSnapshot(bytes, code)
    }

    /**
     * Siapkan parameter relay P2P untuk live stream cloud: endpoint VTM,
     * kunci publik relay, `streamBizUrl` kanal, dan token sekali pakai.
     *
     * @return null bila sesi cloud tidak ada, perangkat offline, atau cloud
     *   menolak permintaan.
     */
    override suspend fun streamSource(
        session: CloudSession?,
        device: CloudDevice,
        mainStream: Boolean
    ): CloudStreamSource? = withContext(Dispatchers.IO) {
        val cacheKey = streamCacheKey(device, mainStream)
        val cachedSource = streamSourceCache[cacheKey]
        if (cachedSource != null && System.currentTimeMillis() - cachedSource.at < STREAM_CACHE_MS) {
            return@withContext cachedSource.source
        }
        val current = session ?: authManager.session.value ?: return@withContext null
        val serial = device.serial.ifBlank { device.cloudDeviceId.substringBefore('#') }
        if (serial.isBlank()) return@withContext null
        val channel = if (device.channel > 0) device.channel else 1
        val fresh = try {
            ensureFresh(current)
        } catch (e: Exception) {
            Log.w(TAG, "Stream cloud dilewati: ${friendlyMessage(e)}")
            return@withContext null
        }
        // Kode hasil pemulihan di stream selalu lebih dipercaya daripada kode
        // lama yang mungkin tersimpan salah. Kode kanal diutamakan karena tiap
        // kanal bisa memakai kode berbeda.
        val cached = prefs.cloudVerificationCode(device.channelSerial).ifBlank {
            prefs.cloudVerificationCode(serial)
        }
        val source = if (device.encrypted && cached.isNotBlank()) {
            device.copy(verificationCode = cached)
        } else {
            device
        }
        try {
            loadStreamSource(fresh, source, serial, channel, mainStream)
        } catch (e: CloudApiException) {
            if (e.httpCode != 401) {
                Log.w(TAG, "Stream cloud gagal: ${e.message}")
                return@withContext null
            }
            // Token ditolak walau belum terlihat kedaluwarsa.
            try {
                val renewed = refreshInternal(fresh)
                authManager.saveSession(renewed)
                loadStreamSource(renewed, source, serial, channel, mainStream)
            } catch (err: Exception) {
                Log.w(TAG, "Stream cloud gagal setelah refresh: ${friendlyMessage(err)}")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Stream cloud gagal: ${friendlyMessage(e)}")
            null
        }
    }

    private fun loadStreamSource(
        session: CloudSession,
        device: CloudDevice,
        serial: String,
        channel: Int,
        mainStream: Boolean
    ): CloudStreamSource? {
        client.switchDomain(session.apiDomain)
        client.setSessionId(session.accessToken)

        val vtm = client.getJson("/v3/streaming/vtm/$serial/$channel")
        val config = vtm.optJSONObject("streamServerConfig") ?: return null
        val host = config.optString("domain").trim()
            .ifBlank { config.optString("externalIp").trim() }
        val port = config.optInt("port", 8554)
        if (host.isBlank() || port !in 1..65535) return null

        val publicKeyInfo = config.optJSONObject("publicKey")
        val publicKey = try {
            publicKeyInfo?.optString("key").orEmpty()
                .takeIf { it.isNotBlank() }
                ?.let { Base64.decode(it, Base64.DEFAULT) }
        } catch (e: Exception) {
            Log.w(TAG, "Kunci publik relay tidak valid: ${e.message}")
            null
        }
        val keyVersion = publicKeyInfo?.optInt("version", 1) ?: 1

        val biz = try {
            val cameras = client.getJson(
                "/v3/userdevices/v1/cameras/info?deviceSerial=${SdkClient.encode(serial)}"
            )
            val infos = cameras.optJSONArray("cameraInfos")
            var value = ""
            if (infos != null) {
                for (i in 0 until infos.length()) {
                    val info = infos.optJSONObject(i) ?: continue
                    if (info.optInt("channelNo", 1) == channel) {
                        value = info.optString("streamBizUrl").orEmpty().trim()
                        break
                    }
                }
            }
            value
        } catch (e: Exception) {
            Log.d(TAG, "streamBizUrl tidak tersedia: ${e.message}")
            ""
        }

        val tokenResponse = client.postForm(
            "/api/user/token/get?sessionId=${SdkClient.encode(session.accessToken)}",
            "featureCode=$FEATURE_CODE&count=2"
        )
        val tokenArray = tokenResponse.optJSONArray("tokenArray") ?: return null
        val tokens = ArrayList<String>(tokenArray.length())
        for (i in 0 until tokenArray.length()) {
            val token = tokenArray.optString(i).trim()
            if (token.isNotEmpty()) tokens += token
        }
        if (tokens.isEmpty()) return null

        val source = CloudStreamSource(
            vtmHost = host,
            vtmPort = port,
            publicKey = publicKey,
            keyVersion = keyVersion,
            serial = serial,
            channel = channel,
            streamType = if (mainStream) 1 else 2,
            bizQuery = biz,
            tokens = tokens,
            encrypted = device.encrypted,
            verificationCode = device.verificationCode,
            encryptionPwd = device.encryptionPwd
        )
        streamSourceCache[streamCacheKey(device, mainStream)] =
            CachedSource(source, System.currentTimeMillis())
        return source
    }

    private fun streamCacheKey(device: CloudDevice, mainStream: Boolean): String =
        "${device.channelSerial.ifBlank { device.serial }}|${device.channel}|$mainStream"

    private data class CachedSource(val source: CloudStreamSource, val at: Long)

    // ---------------- internals ----------------

    /**
     * Ambil kode verifikasi lewat ISAPI passthrough cloud. Perangkat tidak
     * perlu berada di jaringan yang sama dengan ponsel.
     *
     * Firmware berbeda memakai endpoint yang berbeda, jadi beberapa kandidat
     * dicoba berurutan sampai ada yang mengembalikan `verificationCode`:
     *  - `/ISAPI/System/Network/EZVIZ` (IPC/NVR yang mendukung Platform Access),
     *  - `/ISAPI/System/Network/EZVIZ/secretKey?format=json`,
     *  - `/ISAPI/Streaming/encryption/secretKey?format=json` (enkripsi stream).
     *
     * Dua gaya passthrough cloud juga didukung: `/v3/userdevices/v1/isapi`
     * (`apiKey`) dan `/api/device/isapi` (`subSerial`/`cmdId`).
     */
    private fun fetchCodeFromCloud(
        session: CloudSession?,
        serial: String,
        channel: Int
    ): String {
        val current = session ?: authManager.session.value ?: return ""
        client.switchDomain(current.apiDomain)
        client.setSessionId(current.accessToken)
        for (path in CODE_PATHS) {
            val variants = listOf(
                "/v3/userdevices/v1/isapi" to (
                    "apiKey=$ISAPI_API_KEY" +
                        "&channelNo=${channel.coerceAtLeast(1)}" +
                        "&deviceSerial=${SdkClient.encode(serial)}" +
                        "&apiData=${SdkClient.encode("GET $path")}"
                    ),
                "/api/device/isapi" to (
                    "subSerial=${SdkClient.encode(serial)}" +
                        "&cmdId=$ISAPI_CMD_ID" +
                        "&transmissionData=${SdkClient.encode("GET $path\r\n")}" +
                        "&clientType=55" +
                        "&sessionId=${SdkClient.encode(current.accessToken)}" +
                        "&lang=2"
                    )
            )
            for ((endpoint, body) in variants) {
                val response = try {
                    client.postForm(endpoint, body)
                } catch (e: Exception) {
                    Log.d(TAG, "ISAPI cloud $path via $endpoint gagal: ${e.message}")
                    continue
                }
                val code = metaCode(response)
                if (code != 200) {
                    Log.d(TAG, "ISAPI cloud $path via $endpoint ditolak (kode $code).")
                    continue
                }
                val data = response.opt("data")
                val text = when (data) {
                    is String -> data
                    is JSONObject -> data.toString()
                    else -> response.toString()
                }
                val found = codeFromPayload(text)
                if (found.isNotBlank()) {
                    Log.d(TAG, "Kode verifikasi $serial didapat via $path.")
                    return found
                }
                Log.d(TAG, "ISAPI cloud $path tanpa kode.")
            }
        }
        Log.d(TAG, "Kode verifikasi $serial tidak tersedia dari cloud (ch=$channel).")
        return ""
    }

    /**
     * Kirim perintah ISAPI (GET/PUT) ke perangkat lewat passthrough cloud Hik-Connect.
     * Perangkat tidak perlu berada di jaringan yang sama dengan ponsel, sehingga
     * kontrol seperti privacy mask, stream on/off, dan day/night bisa digunakan
     * dari jaringan luar tanpa port forwarding.
     *
     * @param method "GET" atau "PUT"
     * @param path jalur ISAPI, contoh `/ISAPI/System/Video/inputs/channels/1/privacyMask`
     * @param body isi XML untuk PUT, null untuk GET
     * @return tubuh respons ISAPI (XML/JSON), atau string kosong bila tidak ada konten
     */
    suspend fun isapiCommand(
        session: CloudSession?,
        device: CloudDevice,
        method: String,
        path: String,
        body: String? = null
    ): String {
        val current = session ?: authManager.session.value
            ?: throw IllegalStateException("Sesi cloud tidak tersedia.")
        client.switchDomain(current.apiDomain)
        client.setSessionId(current.accessToken)

        val serial = device.serial.ifBlank { device.cloudDeviceId.substringBefore('#') }
        val channelNo = device.channel.coerceAtLeast(1)
        val isapiCmd = buildString {
            append(method).append(' ').append(path).append("\r\n")
            val bodyText = body.orEmpty()
            if (bodyText.isNotBlank()) {
                val bytes = bodyText.toByteArray(Charsets.UTF_8)
                append("Content-Type: application/xml; charset=UTF-8\r\n")
                append("Content-Length: ${bytes.size}\r\n")
                append("\r\n")
                append(bodyText)
            } else {
                append("\r\n")
            }
        }

        // Coba kedua endpoint passthrough cloud secara berurutan.
        val variants = listOf(
            "/v3/userdevices/v1/isapi" to (
                "apiKey=$ISAPI_API_KEY" +
                    "&channelNo=$channelNo" +
                    "&deviceSerial=${SdkClient.encode(serial)}" +
                    "&apiData=${SdkClient.encode(isapiCmd)}"
            ),
            "/api/device/isapi" to (
                "subSerial=${SdkClient.encode(serial)}" +
                    "&cmdId=$ISAPI_CMD_ID" +
                    "&transmissionData=${SdkClient.encode(isapiCmd)}" +
                    "&clientType=55" +
                    "&sessionId=${SdkClient.encode(current.accessToken)}" +
                    "&lang=2"
            )
        )

        var lastError = ""
        for ((endpoint, postBody) in variants) {
            val response = try {
                client.postForm(endpoint, postBody)
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.d(TAG, "ISAPI cloud $method $path via $endpoint gagal: $lastError")
                continue
            }
            val code = metaCode(response)
            if (code != 200) {
                lastError = "kode $code"
                Log.d(TAG, "ISAPI cloud $method $path via $endpoint ditolak (kode $code).")
                continue
            }
            val data = response.opt("data")
            val text = when (data) {
                is String -> data
                is JSONObject -> data.toString()
                else -> response.toString()
            }
            Log.d(TAG, "ISAPI cloud $method $path via $endpoint → " +
                text.take(800).replace(Regex("\\s+"), " "))
            if (text.isNotBlank()) return text
            return ""
        }
        throw CloudApiException(
            200,
            "ISAPI cloud $method $path gagal melalui semua endpoint passthrough ($lastError)."
        )
    }

    /** `data` ISAPI bisa berupa XML/JSON mentah, URL-encoded, atau base64. */
    private fun codeFromPayload(raw: String): String {
        val text = raw.trim()
        if (text.isBlank()) return ""
        HikCodeParser.extract(text).takeIf { it.isNotBlank() }?.let { return it }
        val urlDecoded = runCatching { java.net.URLDecoder.decode(text, "UTF-8") }.getOrNull()
        urlDecoded?.takeIf { it != text }?.let { decoded ->
            HikCodeParser.extract(decoded).takeIf { it.isNotBlank() }?.let { return it }
        }
        return runCatching {
            String(Base64.decode(text, Base64.DEFAULT), Charsets.UTF_8)
        }.getOrNull()?.let { HikCodeParser.extract(it) }.orEmpty()
    }

    /** ISAPI lokal (digest auth), dipakai bila ponsel berada di LAN perangkat. */
    private fun fetchCodeFromLan(device: CloudDevice, user: String, pass: String): String {
        val host = device.localIp.trim()
        if (host.isBlank()) return ""
        return fetchCodeFromHost(host, user, pass)
    }

    /**
     * Baca kode verifikasi langsung dari perangkat lewat ISAPI digest.
     *
     * Perangkat di LAN adalah sumber paling otoritatif, tetapi autentikasi
     * Hikvision mengunci akun sementara setelah beberapa kegagalan. Karena itu
     * percobaan berhenti pada 401/403 pertama: endpoint berikutnya tidak dicoba
     * lagi supaya tidak menambah hitungan gagal.
     */
    private fun fetchCodeFromHost(host: String, user: String, pass: String): String {
        val cleanHost = host.trim()
        if (cleanHost.isBlank()) return ""
        val loginUser = user.ifBlank { prefs.defaultUser }
        val loginPass = pass.ifBlank { prefs.defaultPass }
        if (loginUser.isBlank() || loginPass.isBlank()) return ""
        return try {
            val base = when {
                cleanHost.startsWith("http://") || cleanHost.startsWith("https://") -> cleanHost
                else -> "http://$cleanHost"
            }
            val isapi = IsapiClient(
                baseUrl = base,
                user = loginUser,
                pass = loginPass,
                connectTimeoutMs = 4_000,
                readTimeoutMs = 5_000
            )
            for (path in CODE_PATHS) {
                val response = try {
                    isapi.get(path)
                } catch (e: IsapiException) {
                    if (e.code == 401 || e.code == 403) {
                        Log.d(TAG, "ISAPI lokal $cleanHost $path ditolak (${e.code}); berhenti.")
                        return ""
                    }
                    Log.d(TAG, "ISAPI lokal $cleanHost $path gagal: ${e.message}")
                    continue
                }
                // Status saja yang dicatat — nilainya adalah kode rahasia.
                Log.d(TAG, "ISAPI lokal $cleanHost $path HTTP ${response.code}")
                if (response.code !in 200..299) continue
                HikCodeParser.extract(response.body).takeIf { it.isNotBlank() }?.let {
                    return it
                }
            }
            ""
        } catch (e: Exception) {
            Log.d(TAG, "ISAPI lokal $cleanHost tidak tersedia: ${e.message}")
            ""
        }
    }

    private suspend fun loginInternal(email: String, password: String): CloudSession {
        client.switchDomain(CloudAuthManager.DEFAULT_DOMAIN)
        var response = postLogin(email, password)
        var metaCode = metaCode(response)

        // 1100 = akun berada di region lain; ikuti domain yang diberikan.
        if (metaCode == 1100) {
            val domain = response.optJSONObject("loginArea")?.optString("apiDomain").orEmpty()
            if (domain.isBlank()) {
                throw CloudApiException(200, "Cloud meminta domain region, tetapi tidak dikirim.", 1100)
            }
            client.switchDomain(domain)
            response = postLogin(email, password)
            metaCode = metaCode(response)
        }
        if (metaCode != 200) throw cloudError(response, "Login cloud gagal")

        val loginSession = response.optJSONObject("loginSession")
            ?: throw CloudApiException(200, "Respons login tidak berisi session.", metaCode)
        val sessionId = loginSession.optString("sessionId").orEmpty()
        val refreshSessionId = loginSession.optString("rfSessionId").orEmpty()
        if (sessionId.isBlank() || refreshSessionId.isBlank()) {
            throw CloudApiException(200, "Session cloud tidak lengkap.", metaCode)
        }

        val regionDomain = response.optJSONObject("loginArea")
            ?.optString("apiDomain").orEmpty()
            .ifBlank { client.apiDomain }
        client.switchDomain(regionDomain)
        client.setSessionId(sessionId)

        val expiresAt = jwtExpiry(sessionId) ?: (System.currentTimeMillis() + 60 * 60_000L)
        return CloudSession(
            accessToken = sessionId,
            refreshToken = refreshSessionId,
            expiresIn = ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L),
            userId = response.optString("username").ifBlank { email },
            expiresAt = expiresAt,
            apiDomain = client.apiDomain
        )
    }

    private fun postLogin(email: String, password: String): JSONObject {
        val body = "account=${SdkClient.encode(email)}" +
            "&password=${md5(password)}"
        return client.postForm("/v3/users/login/v2", body)
    }

    private suspend fun ensureFresh(session: CloudSession): CloudSession {
        if (!session.isExpired) {
            client.switchDomain(session.apiDomain)
            client.setSessionId(session.accessToken)
            return session
        }
        val refreshed = refreshInternal(session)
        authManager.saveSession(refreshed)
        return refreshed
    }

    private fun refreshInternal(session: CloudSession): CloudSession {
        client.switchDomain(session.apiDomain)
        val body = "refreshSessionId=${SdkClient.encode(session.refreshToken)}" +
            "&featureCode=$FEATURE_CODE"
        val response = try {
            client.putFormNoSession("/v3/apigateway/login", body)
        } catch (e: CloudApiException) {
            throw IllegalStateException(
                "Sesi cloud berakhir. Login ulang dengan email & password.",
                e
            )
        }
        val code = metaCode(response)
        if (code != 200) throw cloudError(response, "Perbarui sesi cloud gagal")
        val info = response.optJSONObject("sessionInfo")
            ?: throw CloudApiException(200, "Respons refresh tidak berisi session.", code)
        val token = info.optString("sessionId").orEmpty()
        val refresh = info.optString("refreshSessionId").orEmpty()
        if (token.isBlank()) {
            throw CloudApiException(200, "Token cloud kosong.", code)
        }
        val expiresAt = jwtExpiry(token) ?: (System.currentTimeMillis() + 60 * 60_000L)
        val updated = session.copy(
            accessToken = token,
            refreshToken = refresh.ifBlank { session.refreshToken },
            expiresAt = expiresAt,
            expiresIn = ((expiresAt - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L)
        )
        client.setSessionId(token)
        return updated
    }

    private fun fetchDevices(session: CloudSession): List<CloudDevice> {
        client.switchDomain(session.apiDomain)
        client.setSessionId(session.accessToken)
        val result = ArrayList<CloudDevice>()
        var offset = 0
        var page = 0
        var hasNext = true
        while (hasNext && page < MAX_PAGES) {
            val path = "/v3/userdevices/v1/devices/pagelist" +
                "?groupId=-1&limit=$PAGE_SIZE&offset=$offset&filter=$DEVICE_FILTER"
            val response = client.getJson(path)
            val code = metaCode(response)
            if (code != 200) throw cloudError(response, "Gagal mengambil daftar device")
            val infos = response.optJSONArray("deviceInfos") ?: JSONArray()
            val connections = response.optJSONObject("connectionInfos")
            val statuses = response.optJSONObject("statusInfos")
            val wifi = response.optJSONObject("wifiInfos")

            for (i in 0 until infos.length()) {
                val info = infos.optJSONObject(i) ?: continue
                appendDevice(result, info, connections, statuses, wifi)
            }
            hasNext = response.optJSONObject("page")?.optBoolean("hasNext") == true
            offset += PAGE_SIZE
            page++
        }
        // internalId harus unik dan stabil selama satu sesi sinkronisasi.
        return result.mapIndexed { index, device -> device.copy(internalId = index) }
    }

    private fun appendDevice(
        out: MutableList<CloudDevice>,
        info: JSONObject,
        connections: JSONObject?,
        statuses: JSONObject?,
        wifi: JSONObject?
    ) {
        val serial = info.optString("deviceSerial").orEmpty()
        if (serial.isBlank()) return
        val fullSerial = info.optString("fullSerial").ifBlank { serial }
        val deviceName = info.optString("name").ifBlank { serial }
        val type = info.optString("deviceType").orEmpty()
        val version = info.optString("version").orEmpty()
        val connection = connections?.optJSONObject(serial)
        val status = statuses?.optJSONObject(serial)
        val wifiInfo = wifi?.optJSONObject(serial)
        val online = status?.optInt("globalStatus", 1) == 1
        val encrypted = status?.optInt("isEncrypt", 0) == 1
        // Cloud selalu mengirim hash kunci enkripsi untuk perangkat berenkripsi;
        // ini cukup untuk membuka video tanpa meminta kode ke pengguna.
        val encryptionPwd = status?.optString("encryptPwd").orEmpty().trim()
        val localIp = cleanIp(connection?.optString("localIp").orEmpty())
            .ifBlank { cleanIp(wifiInfo?.optString("address").orEmpty()) }
        val wanIp = cleanIp(connection?.optString("netIp").orEmpty())
        val isNvr = looksLikeRecorder(type)
        if (isNvr && encrypted) {
            Log.d(TAG, "NVR $serial terenkripsi=${encryptionPwd.isNotBlank()}")
        }

        if (isNvr) {
            val channels = fetchNvrChannels(serial, deviceName)
            if (channels.isNotEmpty()) {
                channels.forEach { channel ->
                    out += CloudDevice(
                        internalId = 0,
                        cloudDeviceId = "$fullSerial#${channel.number}",
                        serial = serial,
                        channelSerial = channel.serial,
                        name = channel.name,
                        channel = channel.number,
                        deviceType = type,
                        firmware = version,
                        isNvr = true,
                        online = online && channel.online,
                        encrypted = encrypted,
                        encryptionPwd = encryptionPwd,
                        localIp = localIp,
                        wanIp = wanIp
                    )
                }
                return
            }
        }
        out += CloudDevice(
            internalId = 0,
            cloudDeviceId = fullSerial,
            serial = serial,
            name = deviceName,
            channel = 1,
            deviceType = type,
            firmware = version,
            isNvr = isNvr,
            online = online,
            encrypted = encrypted,
            encryptionPwd = encryptionPwd,
            localIp = localIp,
            wanIp = wanIp
        )
    }

    /** Kanal kamera yang terpasang pada NVR/DVR. */
    private fun fetchNvrChannels(serial: String, parentName: String): List<ChannelInfo> {
        val response = try {
            client.getJson(
                "/v3/userdevices/v1/cameras/info?deviceSerial=${SdkClient.encode(serial)}"
            )
        } catch (e: Exception) {
            Log.d(TAG, "Daftar kanal $serial tidak tersedia: ${e.message}")
            return emptyList()
        }
        val infos = response.optJSONArray("cameraInfos") ?: return emptyList()
        val channels = ArrayList<ChannelInfo>()
        for (i in 0 until infos.length()) {
            val cameraObj = infos.optJSONObject(i) ?: continue
            val number = cameraObj.optInt("channelNo", 0)
            if (number <= 0) continue
            if (!cameraObj.optBoolean("isShow", true)) continue
            val channelInfo = cameraObj.optJSONObject("deviceChannelInfo")
            val signal = channelInfo?.optString("signalStatus").orEmpty()
            val online = when (signal.lowercase()) {
                "1", "true" -> true
                "0", "false" -> false
                else -> true
            }
            val channelSerial = channelInfo?.optString("channelDeviceSerial")
                .orEmpty().trim()
            val name = cameraObj.optString("cameraName").trim()
                .ifBlank { parentName.ifBlank { "Kamera" } }
            channels += ChannelInfo(number, name, online, channelSerial)
        }
        return channels.distinctBy { it.number }.sortedBy { it.number }
    }

    private data class ChannelInfo(
        val number: Int,
        val name: String,
        val online: Boolean,
        val serial: String
    )

    private fun cloudError(json: JSONObject, fallback: String): CloudApiException {
        val meta = json.optJSONObject("meta")
        val code = meta?.optInt("code", -1) ?: -1
        val raw = meta?.optString("message").orEmpty().trim()
        val message = when (code) {
            1013, 1014 -> "Email atau password Hik-Connect salah."
            1015 -> "Cloud meminta CAPTCHA. Login sekali di aplikasi Hik-Connect resmi, lalu coba lagi."
            in 2002..2010 -> "Perangkat sedang offline di cloud."
            70002 -> "Perangkat sedang offline di cloud."
            else -> if (raw.isNotBlank()) "$fallback (kode $code: $raw)" else "$fallback (kode $code)."
        }
        return CloudApiException(200, message, code)
    }

    private fun friendlyMessage(e: Throwable): String = when (e) {
        is CloudApiException -> e.message ?: "Cloud menolak permintaan."
        is java.net.UnknownHostException -> "Tidak ada koneksi internet / DNS cloud gagal."
        is java.net.SocketTimeoutException -> "Cloud tidak merespons (timeout). Coba lagi."
        else -> e.message ?: "Gagal menghubungi cloud."
    }

    private fun metaCode(json: JSONObject): Int =
        json.optJSONObject("meta")?.optInt("code", -1) ?: -1

    private fun cleanIp(value: String): String =
        if (value.isBlank() || value == "0.0.0.0") "" else value.trim()

    private fun looksEncrypted(bytes: ByteArray): Boolean {
        if (bytes.size < HIK_ENCRYPTED_MAGIC.size) return false
        for (i in HIK_ENCRYPTED_MAGIC.indices) {
            if (bytes[i] != HIK_ENCRYPTED_MAGIC[i]) return false
        }
        return true
    }

    /**
     * Dekripsi snapshot "Image and Video Encryption" Hikvision.
     *
     * Format: 48 byte header diawali `hikencode`, lalu ciphertext AES-128-CBC
     * dengan PKCS#7. Key = kode verifikasi (maks. 16 byte, di-pad nol),
     * IV = "01234567" (di-pad nol). Hasil valid harus diawali JPEG `FF D8`.
     */
    private fun decryptSnapshot(bytes: ByteArray, code: String): ByteArray? {
        if (bytes.size <= HIK_ENCRYPTED_HEADER) return null
        return try {
            val key = ByteArray(16)
            val codeBytes = code.trim().toByteArray(Charsets.UTF_8)
            codeBytes.copyInto(key, endIndex = minOf(codeBytes.size, key.size))
            val iv = ByteArray(16)
            HIK_ENCRYPTED_IV.copyInto(iv)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            val plain = cipher.doFinal(
                bytes,
                HIK_ENCRYPTED_HEADER,
                bytes.size - HIK_ENCRYPTED_HEADER
            )
            if (plain.size > 2 &&
                plain[0] == 0xFF.toByte() &&
                plain[1] == 0xD8.toByte()
            ) {
                plain
            } else {
                Log.w(TAG, "Kode verifikasi salah; hasil dekripsi bukan JPEG.")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Gagal mendekripsi snapshot cloud: ${e.message}")
            null
        }
    }

    private fun looksLikeRecorder(type: String): Boolean {
        val upper = type.uppercase()
        if (upper.contains("NVR") || upper.contains("DVR")) return true
        // DS-76xx/77xx/86xx/96xx adalah NVR; DS-72xx/73xx adalah DVR.
        return listOf("DS-76", "DS-77", "DS-86", "DS-96", "DS-72", "DS-73")
            .any { upper.startsWith(it) }
    }

    private fun md5(text: String): String =
        MessageDigest.getInstance("MD5")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Ambil `exp` (detik) dari sessionId yang berbentuk JWT. */
    private fun jwtExpiry(token: String): Long? {
        return try {
            val payload = token.split('.').getOrNull(1) ?: return null
            val json = String(
                Base64.decode(
                    payload,
                    Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                ),
                Charsets.UTF_8
            )
            val exp = JSONObject(json).optLong("exp", 0L)
            if (exp > 0L) exp * 1000L else null
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        private const val TAG = "HikVisionCloud"
        private const val STREAM_CACHE_MS = 90_000L
        private const val FEATURE_CODE = "deadbeef"
        private const val EZVIZ_PATH = "/ISAPI/System/Network/EZVIZ"
        private const val ISAPI_API_KEY = "100044"
        private const val ISAPI_CMD_ID = "19713"

        /** Endpoint ISAPI yang menyimpan kode verifikasi pada berbagai firmware. */
        private val CODE_PATHS = listOf(
            EZVIZ_PATH,
            "$EZVIZ_PATH/secretKey?format=json",
            "/ISAPI/Streaming/encryption/secretKey?format=json",
            "/ISAPI/Streaming/encryption?format=json"
        )
        private const val PAGE_SIZE = 50
        private const val MAX_PAGES = 20
        private const val DEVICE_FILTER =
            "TIME_PLAN,CONNECTION,SWITCH,STATUS,STATUS_EXT,WIFI,NODISTURB,P2P,KMS,HIDDNS,VTM,TTS"
        private val HIK_ENCRYPTED_MAGIC = "hikencode".toByteArray(Charsets.US_ASCII)
        private const val HIK_ENCRYPTED_HEADER = 48
        private val HIK_ENCRYPTED_IV = "01234567".toByteArray(Charsets.US_ASCII)
    }
}

sealed class CloudRepoStatus {
    object Idle : CloudRepoStatus()
    object Loading : CloudRepoStatus()
    object Syncing : CloudRepoStatus()
    object Ready : CloudRepoStatus()
    data class Error(val message: String) : CloudRepoStatus()
}
