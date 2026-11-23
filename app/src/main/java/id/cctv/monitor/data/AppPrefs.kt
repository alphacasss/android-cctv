package id.cctv.monitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import id.cctv.monitor.cloud.CloudDevice
import org.json.JSONArray
import org.json.JSONObject

/**
 * Penyimpanan setelan + kredensial. Kredensial disimpan lewat
 * EncryptedSharedPreferences (kunci di Android Keystore). Bila perangkat
 * bermasalah dengan Keystore, aplikasi jatuh ke SharedPreferences biasa dan
 * menandai [encrypted] = false agar UI bisa memberi peringatan.
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences
    var encrypted: Boolean = true
        private set

    /**
     * Cache di memori. EncryptedSharedPreferences harus mendekripsi setiap
     * pembacaan; tanpa cache, render grid memanggil puluhan pembacaan dan UI
     * terasa lambat. Cache diperbarui setiap kali data ditulis.
     */
    @Volatile
    private var camerasCache: MutableList<Camera>? = null

    private val flagsCache = java.util.concurrent.ConcurrentHashMap<Int, CameraFlags>()

    @Volatile
    private var remoteSettingsCache: RemoteSettings? = null

    init {
        prefs = try {
            val masterKey = MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context.applicationContext,
                FILE_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (t: Throwable) {
            encrypted = false
            context.applicationContext.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
        }
    }

    var defaultUser: String
        get() = prefs.getString(KEY_USER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_USER, value).apply()

    var defaultPass: String
        get() = prefs.getString(KEY_PASS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_PASS, value).apply()

    /** Catatan aksi kontrol terbaru (terbaru lebih dulu). */
    fun activityLog(): List<ActivityLogEntry> = runCatching {
        ActivityLogCodec.decode(prefs.getString(KEY_ACTIVITY_LOG, null))
    }.getOrDefault(emptyList())

    /** Tambah catatan aksi; jumlah entri dibatasi oleh codec. */
    fun addActivity(title: String, detail: String) {
        runCatching {
            val entry = ActivityLogEntry(
                at = System.currentTimeMillis(),
                title = title.trim(),
                detail = detail.trim()
            )
            val raw = ActivityLogCodec.append(
                prefs.getString(KEY_ACTIVITY_LOG, null),
                entry
            )
            prefs.edit().putString(KEY_ACTIVITY_LOG, raw).apply()
        }
    }

    var autoRestore: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTORE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESTORE, value).apply()

    /** Pakai decoder/renderer resmi Hik-Connect untuk stream cloud. */
    var useOfficialSdk: Boolean
        get() = prefs.getBoolean(KEY_USE_OFFICIAL_SDK, true)
        set(value) = prefs.edit().putBoolean(KEY_USE_OFFICIAL_SDK, value).apply()

    /**
     * Hentikan RTSP/VLC saat ponsel memakai paket data seluler.
     * Selama mode ini aktif, tile yang sebelumnya memakai RTSP dialihkan ke
     * Hik-Connect cloud. Kamera tanpa binding cloud berhenti dengan pesan,
     * bukan terus mencoba RTSP lewat IP publik.
     */
    var blockRtspOnCellular: Boolean
        get() = prefs.getBoolean(KEY_BLOCK_RTSP_CELLULAR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_BLOCK_RTSP_CELLULAR, value).apply()
            bumpConfig()
        }

    /** Jeda sebelum pemulihan otomatis dijalankan saat aplikasi ditinggalkan. */
    var restoreDelaySeconds: Int
        get() = prefs.getInt(KEY_RESTORE_DELAY, 120).coerceIn(10, 3600)
        set(value) = prefs.edit().putInt(KEY_RESTORE_DELAY, value.coerceIn(10, 3600)).apply()

    // ---------------- akses luar jaringan otomatis ----------------

    var remoteAuto: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_AUTO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_REMOTE_AUTO, value).apply()
            remoteSettingsCache = null
        }

    /** DDNS/alamat tetap opsional; kosong = pakai IP publik terdeteksi. */
    var remoteHost: String
        get() = prefs.getString(KEY_REMOTE_HOST, "").orEmpty()
        set(value) {
            prefs.edit().putString(KEY_REMOTE_HOST, value.trim()).apply()
            remoteSettingsCache = null
        }

    var remoteHttpBase: Int
        get() = prefs.getInt(KEY_REMOTE_HTTP_BASE, RemoteSettings.DEFAULT_HTTP_BASE)
            .coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
        set(value) {
            prefs.edit()
                .putInt(
                    KEY_REMOTE_HTTP_BASE,
                    value.coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
                )
                .apply()
            remoteSettingsCache = null
        }

    var remoteRtspBase: Int
        get() = prefs.getInt(KEY_REMOTE_RTSP_BASE, RemoteSettings.DEFAULT_RTSP_BASE)
            .coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
        set(value) {
            prefs.edit()
                .putInt(
                    KEY_REMOTE_RTSP_BASE,
                    value.coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
                )
                .apply()
            remoteSettingsCache = null
        }

    /** Mode VPN vs port forwarding (default: port forwarding). */
    var vpnMode: Boolean
        get() = prefs.getBoolean(KEY_VPN_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_VPN_MODE, value).apply()
            remoteSettingsCache = null
        }

    /** Mode Away: layanan latar memantau gerakan & mengirim notifikasi. */
    var awayMode: Boolean
        get() = prefs.getBoolean(KEY_AWAY_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_AWAY_MODE, value).apply()

    /** Waktu terakhir dialog saran Mode Away ditampilkan (anti-spam). */
    var awayPromptAt: Long
        get() = prefs.getLong(KEY_AWAY_PROMPT, 0L)
        set(value) = prefs.edit().putLong(KEY_AWAY_PROMPT, value).apply()

    /**
     * Sensor gerak hanya menandai manusia/hewan/kendaraan (deteksi objek AI).
     * Bila dimatikan, semua perubahan piksel dianggap gerakan.
     */
    var objectFilter: Boolean
        get() = prefs.getBoolean(KEY_OBJECT_FILTER, true)
        set(value) = prefs.edit().putBoolean(KEY_OBJECT_FILTER, value).apply()

    /**
     * IP publik **rumah** hasil deteksi saat ponsel berada di jaringan rumah.
     * Kosong bila belum pernah terdeteksi di rumah; deteksi dari jaringan lain
     * (mis. seluler) sengaja tidak dipakai karena itu IP operator, bukan IP rumah.
     */
    val publicIp: String
        get() = if (prefs.getBoolean(KEY_PUBLIC_IP_HOME, false)) {
            prefs.getString(KEY_PUBLIC_IP, "").orEmpty()
        } else {
            ""
        }

    val publicIpAt: Long
        get() = if (prefs.getBoolean(KEY_PUBLIC_IP_HOME, false)) {
            prefs.getLong(KEY_PUBLIC_IP_AT, 0L)
        } else {
            0L
        }

    /** Jalankan deteksi ulang bila IP publik berubah atau >5 menit lalu. */
    fun shouldRefreshPublicIp(): Boolean {
        val cached = prefs.getString(KEY_PUBLIC_IP, "").orEmpty()
        return cached.isNotBlank() &&
            System.currentTimeMillis() - publicIpAt >= 5 * 60_000L
    }

    /** Menyimpan hasil deteksi yang dilakukan di jaringan rumah. */
    fun setHomePublicIp(ip: String) {
        prefs.edit()
            .putString(KEY_PUBLIC_IP, ip.trim())
            .putLong(KEY_PUBLIC_IP_AT, System.currentTimeMillis())
            .putBoolean(KEY_PUBLIC_IP_HOME, ip.isNotBlank())
            .apply()
    }

    fun remoteSettings(): RemoteSettings {
        remoteSettingsCache?.let { return it }
        val settings = RemoteSettings(
            enabled = remoteAuto,
            host = remoteHost,
            httpBase = remoteHttpBase,
            rtspBase = remoteRtspBase,
            publicIp = publicIp
        )
        remoteSettingsCache = settings
        return settings
    }

    val configRev: Int
        get() = prefs.getInt(KEY_CONFIG_REV, 0)

    var lastRestoreSummary: String
        get() = prefs.getString(KEY_LAST_RESTORE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_RESTORE, value).apply()

    var lastRestoreAt: Long
        get() = prefs.getLong(KEY_LAST_RESTORE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RESTORE_AT, value).apply()

    /** Terakhir kalanya enkripsi stream NVR dimatikan lewat ISAPI lokal. */
    var lastStreamEncryptionDisableAt: Long
        get() = prefs.getLong(KEY_STREAM_ENCRYPTION_DISABLE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_STREAM_ENCRYPTION_DISABLE_AT, value).apply()

    fun bumpConfig() {
        prefs.edit().putInt(KEY_CONFIG_REV, configRev + 1).apply()
    }

    fun effectiveUser(camera: Camera): String =
        camera.user.ifBlank { defaultUser }

    fun effectivePass(camera: Camera): String =
        camera.pass.ifBlank { defaultPass }

    /**
     * Kode verifikasi perangkat cloud (biasanya 6 huruf pada label NVR),
     * disimpan per serial agar cukup diisi **sekali** dan otomatis dipakai
     * semua kanal NVR tersebut.
     */
    fun cloudVerificationCode(serial: String): String {
        if (serial.isBlank()) return ""
        val raw = prefs.getString(KEY_CLOUD_CODES, null).orEmpty()
        if (raw.isBlank()) return ""
        return runCatching {
            JSONObject(raw).optString(serial, "").trim()
        }.getOrDefault("")
    }

    fun saveCloudVerificationCode(serial: String, code: String) {
        if (serial.isBlank()) return
        val raw = prefs.getString(KEY_CLOUD_CODES, null).orEmpty()
        val obj = runCatching {
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }.getOrDefault(JSONObject())
        // Huruf besar/kecil penting: kunci AES memakai kode apa adanya.
        obj.put(serial, code.trim())
        prefs.edit().putString(KEY_CLOUD_CODES, obj.toString()).apply()
        android.util.Log.d(
            "AppPrefs",
            "simpan kode serial='$serial' len=${code.trim().length}"
        )
    }

    fun cameras(): MutableList<Camera> {
        camerasCache?.let { return it }
        val raw = prefs.getString(KEY_CAMERAS, null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultCameras()
            writeCameras(defaults, bumpRevision = false)
            return defaults
        }
        return try {
            val array = JSONArray(raw)
            val list = ArrayList<Camera>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val perHost = o.optString("remoteHost", "")
                var remoteHttpPort = o.optInt("remoteHttpPort", 0)
                var remoteRtspPort = o.optInt("remoteRtspPort", 0)
                // Data versi 1.0.0 selalu menulis 80/554 walau tidak diisi.
                if (perHost.isBlank() && remoteHttpPort == 80 && remoteRtspPort == 554) {
                    remoteHttpPort = 0
                    remoteRtspPort = 0
                }
                val dayNight = runCatching {
                    DayNight.valueOf(o.optString("dayNight", DayNight.AUTO.name))
                }.getOrDefault(DayNight.AUTO)
                val cloudDevice = o.optJSONObject("cloudDevice")?.let { d ->
                    CloudDevice(
                        internalId = o.optInt("id", 0),
                        cloudDeviceId = d.optString("cloudDeviceId", ""),
                        serial = d.optString("serial", ""),
                        channelSerial = d.optString("channelSerial", ""),
                        name = d.optString("name", ""),
                        channel = d.optInt("channel", 1),
                        deviceType = d.optString("deviceType", ""),
                        firmware = d.optString("firmware", ""),
                        isNvr = d.optBoolean("isNvr", false),
                        online = d.optBoolean("online", true),
                        encrypted = d.optBoolean("encrypted", false),
                        verificationCode = d.optString("verificationCode", ""),
                        encryptionPwd = d.optString("encryptionPwd", ""),
                        localIp = d.optString("localIp", ""),
                        wanIp = d.optString("wanIp", ""),
                        streamUrl = d.optString("streamUrl", ""),
                        username = d.optString("username", ""),
                        password = d.optString("password", ""),
                        rtspPort = d.optInt("rtspPort", 554)
                    )
                }
                list.add(
                    Camera(
                        id = o.getInt("id"),
                        name = o.optString("name", ""),
                        ip = o.optString("ip", ""),
                        channel = o.optInt("channel", 1),
                        isNvr = o.optBoolean("isNvr", false),
                        enabled = o.optBoolean("enabled", true),
                        user = o.optString("user", ""),
                        pass = o.optString("pass", ""),
                        remoteHost = perHost,
                        remoteHttpPort = remoteHttpPort,
                        remoteRtspPort = remoteRtspPort,
                        forceRemote = o.optBoolean("forceRemote", false),
                        dayNight = dayNight,
                        movementDetect = o.optBoolean("movementDetect", true),
                        cloudDevice = cloudDevice
                    )
                )
            }
            val result = if (list.isEmpty()) defaultCameras() else list
            camerasCache = result
            result
        } catch (t: Throwable) {
            defaultCameras()
        }
    }

    fun saveCameras(cameras: List<Camera>) {
        writeCameras(cameras, bumpRevision = true)
    }

    private fun writeCameras(cameras: List<Camera>, bumpRevision: Boolean) {
        val array = JSONArray()
        cameras.forEach { c ->
            array.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("ip", c.ip)
                    put("channel", c.channel)
                    put("isNvr", c.isNvr)
                    put("enabled", c.enabled)
                    put("user", c.user)
                    put("pass", c.pass)
                    put("remoteHost", c.remoteHost)
                    put("remoteHttpPort", c.remoteHttpPort)
                    put("remoteRtspPort", c.remoteRtspPort)
                    put("forceRemote", c.forceRemote)
                    put("dayNight", c.dayNight.name)
                    put("movementDetect", c.movementDetect)
                    c.cloudDevice?.let { d ->
                        put(
                            "cloudDevice",
                            JSONObject().apply {
                                put("cloudDeviceId", d.cloudDeviceId)
                                put("serial", d.serial)
                                put("channelSerial", d.channelSerial)
                                put("name", d.name)
                                put("channel", d.channel)
                                put("deviceType", d.deviceType)
                                put("firmware", d.firmware)
                                put("isNvr", d.isNvr)
                                put("online", d.online)
                                put("encrypted", d.encrypted)
                                put("verificationCode", d.verificationCode)
                                put("encryptionPwd", d.encryptionPwd)
                                put("localIp", d.localIp)
                                put("wanIp", d.wanIp)
                                put("streamUrl", d.streamUrl)
                                put("username", d.username)
                                put("password", d.password)
                                put("rtspPort", d.rtspPort)
                            }
                        )
                    }
                }
            )
        }
        val editor = prefs.edit().putString(KEY_CAMERAS, array.toString())
        if (bumpRevision) editor.putInt(KEY_CONFIG_REV, configRev + 1)
        editor.apply()
        camerasCache = cameras.toMutableList()
    }

    private fun defaultCameras(): MutableList<Camera> {
        val list = ArrayList<Camera>(9)
        for (id in 1..9) {
            val ip = if (id == 9) "192.168.100.34" else "192.168.100.${19 + id}"
            list.add(
                Camera(
                    id = id,
                    name = "CAM-%02d".format(id),
                    ip = ip,
                    channel = 1,
                    isNvr = id == 9,
                    enabled = true,
                    user = "",
                    pass = ""
                )
            )
        }
        return list
    }

    fun flags(cameraId: Int): CameraFlags =
        flagsCache.getOrPut(cameraId) {
            CameraFlags(
                black = prefs.getBoolean(keyFlag("black", cameraId), false),
                streamOff = prefs.getBoolean(keyFlag("stream", cameraId), false),
                rtspOff = prefs.getBoolean(keyFlag("rtsp", cameraId), false),
                nvrOff = prefs.getBoolean(keyFlag("nvr", cameraId), false),
                nightBoost = prefs.getBoolean(keyFlag("boost", cameraId), false)
            )
        }

    fun setFlags(cameraId: Int, flags: CameraFlags) {
        flagsCache[cameraId] = flags
        prefs.edit()
            .putBoolean(keyFlag("black", cameraId), flags.black)
            .putBoolean(keyFlag("stream", cameraId), flags.streamOff)
            .putBoolean(keyFlag("rtsp", cameraId), flags.rtspOff)
            .putBoolean(keyFlag("nvr", cameraId), flags.nvrOff)
            .putBoolean(keyFlag("boost", cameraId), flags.nightBoost)
            .apply()
    }

    fun anyFlagSet(): Boolean {
        for (id in 1..12) {
            if (flags(id).any) return true
        }
        return false
    }

    private fun keyFlag(kind: String, cameraId: Int) = "flag_${kind}_$cameraId"

    companion object {
        private const val FILE_ENCRYPTED = "cctv-secure"
        private const val FILE_FALLBACK = "cctv-fallback"
        private const val KEY_USER = "default_user"
        private const val KEY_PASS = "default_pass"
        private const val KEY_CAMERAS = "cameras_json"
        private const val KEY_ACTIVITY_LOG = "activity_log"
        private const val KEY_CLOUD_CODES = "cloud_verification_codes"
        private const val KEY_CONFIG_REV = "config_rev"
        private const val KEY_AUTO_RESTORE = "auto_restore"
        private const val KEY_AWAY_MODE = "away_mode"
        private const val KEY_AWAY_PROMPT = "away_prompt_at"
        private const val KEY_OBJECT_FILTER = "object_filter"
        private const val KEY_RESTORE_DELAY = "restore_delay"
        private const val KEY_LAST_RESTORE = "last_restore_summary"
        private const val KEY_LAST_RESTORE_AT = "last_restore_at"
        private const val KEY_STREAM_ENCRYPTION_DISABLE_AT = "stream_encryption_disable_at"
        private const val KEY_REMOTE_AUTO = "remote_auto"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_REMOTE_HTTP_BASE = "remote_http_base"
        private const val KEY_REMOTE_RTSP_BASE = "remote_rtsp_base"
        private const val KEY_PUBLIC_IP = "public_ip"
        private const val KEY_PUBLIC_IP_AT = "public_ip_at"
        private const val KEY_PUBLIC_IP_HOME = "public_ip_home"
        private const val KEY_VPN_MODE = "vpn_mode"
        private const val KEY_USE_OFFICIAL_SDK = "use_official_sdk"
        private const val KEY_BLOCK_RTSP_CELLULAR = "block_rtsp_cellular"
        private const val KEY_DAYNIGHT = "day_night"
    }

}

fun String.toDayNight(): DayNight = runCatching {
    val v = this.trim { it <= ' ' }.lowercase()
    when {
        v in listOf("1", "true", "day", "daytime") -> DayNight.DAY
        v in listOf("0", "false", "night") -> DayNight.NIGHT
        else -> DayNight.AUTO
    }
}.getOrDefault(DayNight.AUTO)
