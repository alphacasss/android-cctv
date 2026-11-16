package id.cctv.monitor.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
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

    var autoRestore: Boolean
        get() = prefs.getBoolean(KEY_AUTO_RESTORE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_RESTORE, value).apply()

    /** Jeda sebelum pemulihan otomatis dijalankan saat aplikasi ditinggalkan. */
    var restoreDelaySeconds: Int
        get() = prefs.getInt(KEY_RESTORE_DELAY, 120).coerceIn(10, 3600)
        set(value) = prefs.edit().putInt(KEY_RESTORE_DELAY, value.coerceIn(10, 3600)).apply()

    // ---------------- akses luar jaringan otomatis ----------------

    var remoteAuto: Boolean
        get() = prefs.getBoolean(KEY_REMOTE_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE_AUTO, value).apply()

    /** DDNS/alamat tetap opsional; kosong = pakai IP publik terdeteksi. */
    var remoteHost: String
        get() = prefs.getString(KEY_REMOTE_HOST, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_REMOTE_HOST, value.trim()).apply()

    var remoteHttpBase: Int
        get() = prefs.getInt(KEY_REMOTE_HTTP_BASE, RemoteSettings.DEFAULT_HTTP_BASE)
            .coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
        set(value) = prefs.edit()
            .putInt(
                KEY_REMOTE_HTTP_BASE,
                value.coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
            )
            .apply()

    var remoteRtspBase: Int
        get() = prefs.getInt(KEY_REMOTE_RTSP_BASE, RemoteSettings.DEFAULT_RTSP_BASE)
            .coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
        set(value) = prefs.edit()
            .putInt(
                KEY_REMOTE_RTSP_BASE,
                value.coerceIn(RemoteSettings.MIN_BASE, RemoteSettings.MAX_BASE)
            )
            .apply()

    /** Mode VPN vs port forwarding (default: port forwarding). */
    var vpnMode: Boolean
        get() = prefs.getBoolean(KEY_VPN_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_VPN_MODE, value).apply()

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

    fun remoteSettings(): RemoteSettings = RemoteSettings(
        enabled = remoteAuto,
        host = remoteHost,
        httpBase = remoteHttpBase,
        rtspBase = remoteRtspBase,
        publicIp = publicIp
    )

    val configRev: Int
        get() = prefs.getInt(KEY_CONFIG_REV, 0)

    var lastRestoreSummary: String
        get() = prefs.getString(KEY_LAST_RESTORE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_RESTORE, value).apply()

    var lastRestoreAt: Long
        get() = prefs.getLong(KEY_LAST_RESTORE_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_RESTORE_AT, value).apply()

    fun bumpConfig() {
        prefs.edit().putInt(KEY_CONFIG_REV, configRev + 1).apply()
    }

    fun effectiveUser(camera: Camera): String =
        camera.user.ifBlank { defaultUser }

    fun effectivePass(camera: Camera): String =
        camera.pass.ifBlank { defaultPass }

    fun cameras(): MutableList<Camera> {
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
                        dayNight = DayNight.AUTO,
                        movementDetect = o.optBoolean("movementDetect", true)
                    )
                )
            }
            if (list.isEmpty()) defaultCameras() else list
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
                }
            )
        }
        val editor = prefs.edit().putString(KEY_CAMERAS, array.toString())
        if (bumpRevision) editor.putInt(KEY_CONFIG_REV, configRev + 1)
        editor.apply()
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

    fun flags(cameraId: Int): CameraFlags = CameraFlags(
        black = prefs.getBoolean(keyFlag("black", cameraId), false),
        streamOff = prefs.getBoolean(keyFlag("stream", cameraId), false),
        rtspOff = prefs.getBoolean(keyFlag("rtsp", cameraId), false),
        nvrOff = prefs.getBoolean(keyFlag("nvr", cameraId), false)
    )

    fun setFlags(cameraId: Int, flags: CameraFlags) {
        prefs.edit()
            .putBoolean(keyFlag("black", cameraId), flags.black)
            .putBoolean(keyFlag("stream", cameraId), flags.streamOff)
            .putBoolean(keyFlag("rtsp", cameraId), flags.rtspOff)
            .putBoolean(keyFlag("nvr", cameraId), flags.nvrOff)
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
        private const val KEY_CONFIG_REV = "config_rev"
        private const val KEY_AUTO_RESTORE = "auto_restore"
        private const val KEY_RESTORE_DELAY = "restore_delay"
        private const val KEY_LAST_RESTORE = "last_restore_summary"
        private const val KEY_LAST_RESTORE_AT = "last_restore_at"
        private const val KEY_REMOTE_AUTO = "remote_auto"
        private const val KEY_REMOTE_HOST = "remote_host"
        private const val KEY_REMOTE_HTTP_BASE = "remote_http_base"
        private const val KEY_REMOTE_RTSP_BASE = "remote_rtsp_base"
        private const val KEY_PUBLIC_IP = "public_ip"
        private const val KEY_PUBLIC_IP_AT = "public_ip_at"
        private const val KEY_PUBLIC_IP_HOME = "public_ip_home"
        private const val KEY_VPN_MODE = "vpn_mode"
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
