package id.cctv.monitor.control

import id.cctv.monitor.cloud.CloudAuthManager
import id.cctv.monitor.cloud.HikVisionCloudRepository
import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupKind
import id.cctv.monitor.data.BackupStore
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.data.DayNight
import id.cctv.monitor.data.toDayNight
import id.cctv.monitor.net.CameraXml
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.net.IsapiException
import id.cctv.monitor.net.IsapiResponse
import id.cctv.monitor.net.isapiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.util.Log

data class RestoreReport(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    val summary: String
        get() = if (ok) {
            "Semua kamera berhasil dipulihkan."
        } else {
            "Pemulihan selesai dengan ${errors.size} masalah:\n" + errors.joinToString("\n")
        }
}

/** Hasil satu kamera dalam operasi bulk. */
data class BulkOutcome(
    val label: String,
    val applied: Boolean = true,
    val error: String? = null
)

/** Hasil operasi bulk: matikan / nyalakan semua kamera. */
data class BulkResult(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    val summary: String
        get() = if (ok) {
            "Aksi massal berhasil diterapkan pada semua kamera."
        } else {
            "Aksi massal selesai dengan ${errors.size} masalah:\n" + errors.joinToString("\n")
        }

    companion object {
        fun from(outcomes: List<BulkOutcome>): BulkResult = BulkResult(
            outcomes.mapNotNull { outcome ->
                when {
                    outcome.error != null -> "${outcome.label}: ${outcome.error}"
                    !outcome.applied ->
                        "${outcome.label}: perangkat tidak mengonfirmasi perubahan"
                    else -> null
                }
            }
        )
    }
}

/** Satu baris hasil pemeriksaan kemampuan kamera. */
data class Capability(val name: String, val supported: Boolean, val detail: String)

/** Rute jaringan yang dipakai untuk satu perintah kontrol. */
private enum class Route { DIRECT, CLOUD }

/** Kebijakan rute per fitur; privacy mask berbeda dari kontrol kanal. */
private enum class Feature { PRIVACY, CHANNEL, GENERAL }

private data class RouteAttempt(val route: Route, val client: CamClient)

/**
 * Semua kontrol ISAPI kamera. Logika backup / verifikasi / rollback mengikuti
 * CCTV-Control.ps1 di PC.
 *
 * Rute kontrol sengaja dipisah:
 *  - ISAPI langsung (LAN/VPN/DDNS) untuk kanal, stream, RTSP, dan kontrol umum.
 *  - Cloud passthrough NVR untuk privacy mask, plus fallback saat langsung gagal.
 */
class ControlRepository(
    private val prefs: AppPrefs,
    private val store: BackupStore,
    private val cloudRepo: HikVisionCloudRepository? = null,
    private val authManager: CloudAuthManager? = null
) {

    // ---------------- privacy mask (HITAM / NORMAL) ----------------

    suspend fun setBlack(cam: Camera, on: Boolean) = withContext(Dispatchers.IO) {
        val path = privacyPath(cam)
        var lastError: Exception? = null
        for (attempt in attempts(cam, Feature.PRIVACY)) {
            try {
                Log.d(TAG, "setBlack cam=${cam.id} on=$on route=${attempt.client.label} path=$path")
                if (on) applyBlack(attempt.client, cam, path) else restoreBlack(attempt.client, cam, path)
                Log.d(TAG, "setBlack berhasil cam=${cam.id} on=$on route=${attempt.client.label}")
                return@withContext
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "setBlack cam=${cam.id} via ${attempt.client.label} gagal: ${e.message}")
            }
        }
        throw lastError ?: IsapiException("Tidak ada rute kontrol yang tersedia untuk privacy mask.")
    }

    private suspend fun applyBlack(client: CamClient, cam: Camera, path: String) {
        val current = client.get(path)
        if (current.code != 200) throw IsapiException(isapiError(current), current.code)
        if (!store.exists(cam.id, BackupKind.PRIVACY)) {
            store.write(cam.id, BackupKind.PRIVACY, current.body)
            Log.d(TAG, "privacy mask backup disimpan untuk cam=${cam.id}")
        }
        val (w, h) = mainResolution(client, cam)
        val updated = CameraXml.setMaskEnabled(current.body, w, h, true)
            ?: throw IsapiException("Format privacy mask kamera tidak dikenali.")
        val put = client.put(path, updated)
        if (put.code != 200) throw IsapiException(isapiError(put), put.code)

        val verify = client.get(path)
        if (verify.code != 200 || CameraXml.maskHasEnabled(verify.body) != true) {
            rollback(client, cam, path, BackupKind.PRIVACY)
            throw IsapiException(
                "Kamera menerima perintah tetapi privacy mask tidak aktif " +
                    "(mungkin firmware membatasi)."
            )
        }
        prefs.setFlags(cam.id, prefs.flags(cam.id).copy(black = true))
    }

    private suspend fun restoreBlack(client: CamClient, cam: Camera, path: String) {
        val clientType = if (client is CamClient.Cloud) "cloud" else "lokal"
        Log.d(TAG, "restoreBlack cam=${cam.id} client=$clientType path=$path")
        val backup = store.read(cam.id, BackupKind.PRIVACY)

        if (backup != null) {
            // Coba PUT backup hingga 4 kali dengan jeda, agar lewat cloud
            // yang kadang tidak stabil tetap bisa pulihkan layar hitam.
            var lastCode = 0
            for (i in 1..4) {
                val put = client.put(path, backup)
                lastCode = put.code
                Log.d(TAG, "restoreBlack backup percobaan $i code=${put.code}")
                // Firmware NVR menolak XML backup dengan badXmlFormat. Tidak ada
                // gunanya mengulang PUT yang sama — langsung coba modifikasi XML.
                if (put.body.contains("badXmlFormat") ||
                    put.body.contains("Invalid XML Format")) {
                    break
                }
                if (put.code == 200) {
                    val cur = client.get(path)
                    if (cur.code == 200 && CameraXml.maskHasEnabled(cur.body) == false) {
                        store.delete(cam.id, BackupKind.PRIVACY)
                        Log.d(TAG, "privacy mask backup dipulihkan untuk cam=${cam.id} (percobaan $i)")
                        prefs.setFlags(cam.id, prefs.flags(cam.id).copy(black = false))
                        return
                    }
                    Log.d(TAG, "restoreBlack backup PUT 200 tapi mask masih aktif percobaan $i")
                }
                if (i < 4) {
                    withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1_200) }
                }
            }

            // Backup PUT terima HTTP 200 tapi mask masih aktif (firmware tidak menerapkan).
            // Coba modifikasi XML langsung: set enabled=false pada privacy mask terkini.
            Log.d(TAG, "backup PUT tidak efektif, coba modify XML langsung cam=${cam.id}")
            val current = client.get(path)
            if (current.code == 200) {
                val updated = CameraXml.setMaskEnabled(current.body, 0, 0, false)
                if (updated != null) {
                    for (i in 1..4) {
                        val put2 = client.put(path, updated)
                        lastCode = put2.code
                        Log.d(TAG, "restoreBlack modify percobaan $i code=${put2.code}")
                        if (put2.code == 200) {
                            val cur2 = client.get(path)
                            if (cur2.code == 200 && CameraXml.maskHasEnabled(cur2.body) == false) {
                                store.delete(cam.id, BackupKind.PRIVACY)
                                Log.d(TAG, "privacy mask dimatikan via modify XML cam=${cam.id} (percobaan $i)")
                                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(black = false))
                                return
                            }
                            Log.d(TAG, "restoreBlack modify PUT 200 tapi mask masih aktif percobaan $i")
                        }
                        if (i < 4) {
                            withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1_200) }
                        }
                    }
                }
            }
            throw IsapiException(
                "Gagal menonaktifkan privacy mask via $clientType (HTTP $lastCode, 4x percobaan). " +
                    "Kamera masih dalam mode hitam. Coba nyalakan ulang kamera (power cycle), " +
                    "atau nonaktifkan privacy mask melalui antarmuka web kamera / aplikasi Hik-Connect."
            )
        } else {
            val current = client.get(path)
            if (current.code != 200) throw IsapiException(isapiError(current), current.code)
            val updated = CameraXml.setMaskEnabled(current.body, 0, 0, false)
                ?: throw IsapiException("Format privacy mask kamera tidak dikenali.")
            var lastCode = 0
            for (i in 1..4) {
                val put = client.put(path, updated)
                lastCode = put.code
                Log.d(TAG, "restoreBlack modify percobaan $i code=${put.code}")
                if (put.code == 200) {
                    val cur3 = client.get(path)
                    if (cur3.code == 200 && CameraXml.maskHasEnabled(cur3.body) == false) {
                        Log.d(TAG, "privacy mask dimatikan via modify XML cam=${cam.id} (percobaan $i)")
                        prefs.setFlags(cam.id, prefs.flags(cam.id).copy(black = false))
                        return
                    }
                    Log.d(TAG, "restoreBlack modify PUT 200 tapi mask masih aktif percobaan $i")
                }
                if (i < 4) {
                    withContext(Dispatchers.IO) { kotlinx.coroutines.delay(1_200) }
                }
            }
            throw IsapiException(
                "Gagal menonaktifkan privacy mask via $clientType (HTTP $lastCode, 4x percobaan). " +
                    "Kamera masih dalam mode hitam. Coba nyalakan ulang kamera (power cycle), " +
                    "atau nonaktifkan privacy mask melalui antarmuka web kamera / aplikasi Hik-Connect."
            )
        }
    }

    private suspend fun mainResolution(client: CamClient, cam: Camera): Pair<Int, Int> {
        return try {
            val r = client.get("/ISAPI/Streaming/channels/${cam.channel}01")
            if (r.code != 200) return DEFAULT_WIDTH to DEFAULT_HEIGHT
            val w = CameraXml.value(r.body, "videoResolutionWidth")?.toIntOrNull()
            val h = CameraXml.value(r.body, "videoResolutionHeight")?.toIntOrNull()
            if (w != null && h != null && w > 0 && h > 0) w to h else DEFAULT_WIDTH to DEFAULT_HEIGHT
        } catch (e: Exception) {
            DEFAULT_WIDTH to DEFAULT_HEIGHT
        }
    }

    // ---------------- sub-stream (STOP / AKTIF) ----------------

    /**
     * Matikan / aktifkan sub-stream (102). Berlaku untuk kamera langsung maupun
     * kanal NVR — keduanya memakai resource `/ISAPI/Streaming/channels/{id}`.
     *
     * Flag aplikasi selalu diubah agar grid berhenti memutar stream walau
     * firmware perangkat mengabaikan perubahan ISAPI (kasus pada beberapa NVR).
     * @return true bila perangkat mengonfirmasi perubahan status.
     */
    suspend fun setStream(cam: Camera, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        val kind = if (cam.isNvr) BackupKind.NVR_SUB else BackupKind.STREAM
        val path = streamPath(cam)
        var deviceApplied = false
        for (attempt in attempts(cam, Feature.CHANNEL)) {
            var routeApplied = false
            if (cam.isNvr) {
                try {
                    routeApplied = applyInputProxyChannel(attempt.client, cam, on)
                } catch (e: Exception) {
                    Log.w(TAG, "setStream inputProxy via ${attempt.client.label} gagal: ${e.message}")
                }
            }
            try {
                val legacyApplied = applyChannelEnabled(
                    attempt.client, cam, path, kind, on, "sub-stream"
                )
                routeApplied = routeApplied || legacyApplied
            } catch (e: Exception) {
                Log.w(TAG, "setStream $path via ${attempt.client.label} gagal: ${e.message}")
            }
            Log.d(
                TAG,
                "setStream cam=${cam.id} on=$on route=${attempt.client.label} " +
                    "deviceApplied=$routeApplied"
            )
            if (routeApplied) {
                deviceApplied = true
                break
            }
        }
        prefs.setFlags(cam.id, prefs.flags(cam.id).copy(streamOff = !on))
        deviceApplied
    }

    /** GET → backup → PUT → verifikasi untuk satu resource streaming channel. */
    private suspend fun applyChannelEnabled(
        client: CamClient,
        cam: Camera,
        path: String,
        kind: BackupKind,
        on: Boolean,
        label: String
    ): Boolean {
        if (on) {
            restoreEnabled(
                client, cam, path, kind,
                "Format $label kamera tidak dikenali."
            )
            return true
        }
        val current = client.get(path)
        if (current.code != 200) return false
        if (!store.exists(cam.id, kind)) store.write(cam.id, kind, current.body)
        val updated = CameraXml.setRootEnabled(current.body, false) ?: return false
        val put = client.put(path, updated)
        if (put.code != 200) return false
        delay(350)
        val verify = client.get(path)
        val applied = verify.code == 200 && CameraXml.rootEnabled(verify.body) == false
        if (!applied) Log.w(TAG, "$label $path: perangkat tidak mengonfirmasi enabled=false")
        return applied
    }

    // ---------------- layanan RTSP (paksa mati) ----------------

    suspend fun setRtsp(cam: Camera, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (cam.isNvr) {
            // Mematikan layanan RTSP level NVR akan menghentikan SEMUA kanal.
            // Untuk kanal NVR, "RTSP OFF" diartikan sebagai penghentian kanal
            // ini saja (main + sub), memakai jalur streaming channel yang benar.
            val applied = setNvr(cam, on)
            prefs.setFlags(cam.id, prefs.flags(cam.id).copy(rtspOff = !on))
            return@withContext applied
        }

        var lastError: Exception? = null
        for (attempt in attempts(cam, Feature.CHANNEL)) {
            try {
                Log.d(TAG, "setRtsp cam=${cam.id} on=$on route=${attempt.client.label}")
                applyRtsp(attempt.client, cam, on)
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(rtspOff = !on))
                Log.d(TAG, "setRtsp berhasil cam=${cam.id} on=$on route=${attempt.client.label}")
                return@withContext true
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "setRtsp cam=${cam.id} via ${attempt.client.label} gagal: ${e.message}")
            }
        }
        throw lastError ?: IsapiException("Tidak ada rute kontrol yang tersedia untuk RTSP.")
    }

    private suspend fun applyRtsp(client: CamClient, cam: Camera, on: Boolean) {
        val path = RTSP_PATH
        if (on) {
            val backup = store.read(cam.id, BackupKind.RTSP)
            if (backup != null) {
                val put = client.put(path, backup)
                if (put.code != 200) throw IsapiException(isapiError(put), put.code)
                if (!waitForRtsp(client, path, true)) {
                    throw IsapiException(
                        "Kamera ${cam.displayName} (${cam.ip}) menerima perintah aktifkan RTSP, " +
                            "tetapi statusnya belum terbaca aktif. Backup dipertahankan; klik " +
                            "RTSP ON lagi atau reboot kamera."
                    )
                }
                store.delete(cam.id, BackupKind.RTSP)
            } else {
                val current = client.get(path)
                if (current.code != 200) throw IsapiException(isapiError(current), current.code)
                val updated = CameraXml.setRtspEnabled(current.body, true)
                    ?: throw IsapiException(
                        "Daftar layanan kamera tidak memuat entri RTSP; layanan RTSP tidak " +
                            "bisa diaktifkan lewat ISAPI."
                    )
                val put = client.put(path, updated)
                if (put.code != 200) throw IsapiException(isapiError(put), put.code)
                if (!waitForRtsp(client, path, true)) {
                    throw IsapiException(
                        "Kamera ${cam.displayName} (${cam.ip}) menerima perintah aktifkan RTSP, " +
                            "tetapi statusnya belum terbaca aktif. Coba lagi atau reboot kamera."
                    )
                }
            }
            return
        }

        val current = client.get(path)
        if (current.code != 200) throw IsapiException(isapiError(current), current.code)
        if (!store.exists(cam.id, BackupKind.RTSP)) {
            store.write(cam.id, BackupKind.RTSP, current.body)
        }
        val updated = CameraXml.setRtspEnabled(current.body, false)
            ?: throw IsapiException(
                "Daftar layanan kamera tidak memuat entri RTSP; jalan paksa ini tidak " +
                    "tersedia di firmware tersebut."
            )
        val put = client.put(path, updated)
        if (put.code != 200) throw IsapiException(isapiError(put), put.code)

        if (!waitForRtsp(client, path, false)) {
            rollback(client, cam, path, BackupKind.RTSP)
            throw IsapiException(
                "Kamera ${cam.displayName} (${cam.ip}) tidak mengizinkan layanan RTSP dimatikan " +
                    "lewat ISAPI. Tidak ada perubahan yang tertinggal."
            )
        }
    }

    private suspend fun waitForRtsp(client: CamClient, path: String, active: Boolean): Boolean {
        for (i in 1..4) {
            delay(400)
            val verify = try {
                client.get(path)
            } catch (e: Exception) {
                return false
            }
            if (verify.code != 200) return false
            if (CameraXml.rtspEnabled(verify.body) == active) return true
        }
        return false
    }

    // ---------------- kanal NVR ----------------

    /**
     * Hentikan / aktifkan kanal NVR.
     *
     * Firmware DS-7608NI-Q1 menerima PUT `<enabled>false</enabled>` pada
     * streaming channel (101/102), tetapi GET tetap mengembalikan `true`.
     * Jalur yang benar-benar memutus sumber video adalah `InputProxyChannel`:
     * IP sumber kamera diganti alamat dummy yang tidak bisa dijangkau NVR,
     * lalu dikembalikan dari backup saat kanal dinyalakan. Ini menghentikan
     * main + sub stream secara fisik, bukan hanya flag aplikasi.
     *
     * Legacy streaming channel tetap dicoba sebagai fallback. Flag aplikasi
     * juga tetap diubah supaya pemutaran di grid berhenti walau sebagian rute
     * gagal. Nilai kembalian menunjukkan apakah perangkat ikut berubah.
     */
    suspend fun setNvr(cam: Camera, on: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (!cam.isNvr) {
            throw IsapiException("${cam.displayName} bukan kamera NVR.")
        }
        val paths = listOf(
            "/ISAPI/Streaming/channels/${cam.streamId(true)}" to BackupKind.NVR,
            "/ISAPI/Streaming/channels/${cam.streamId(false)}" to BackupKind.NVR_SUB
        )
        var deviceApplied = false
        for (attempt in attempts(cam, Feature.CHANNEL)) {
            var sourceApplied = false
            try {
                sourceApplied = applyInputProxyChannel(attempt.client, cam, on)
            } catch (e: Exception) {
                Log.w(TAG, "setNvr inputProxy via ${attempt.client.label} gagal: ${e.message}")
            }

            var legacyApplied = true
            for ((path, kind) in paths) {
                try {
                    if (!applyChannelEnabled(
                            attempt.client, cam, path, kind, on, "kanal NVR"
                        )
                    ) {
                        legacyApplied = false
                    }
                } catch (e: Exception) {
                    legacyApplied = false
                    Log.w(TAG, "setNvr $path via ${attempt.client.label} gagal: ${e.message}")
                }
            }
            val routeApplied = sourceApplied || legacyApplied
            Log.d(
                TAG,
                "setNvr cam=${cam.id} on=$on route=${attempt.client.label} " +
                    "sourceApplied=$sourceApplied legacyApplied=$legacyApplied " +
                    "deviceApplied=$routeApplied"
            )
            if (routeApplied) {
                deviceApplied = true
                break
            }
        }
        // Flag lokal adalah sumber keputusan pemutaran di MainActivity.
        prefs.setFlags(cam.id, prefs.flags(cam.id).copy(nvrOff = !on))
        deviceApplied
    }

    /**
     * Set mode day/night kamera. NIGHT = IR/mode malam, DAY = siang/normal, AUTO = otomatis.
     * Strategi: baca konfigurasi yang ada (GET) lalu ubah nilainya dan PUT ulang dokumen
     * lengkap. Firmware umumnya menolak PUT potongan XML (HTTP 400/403).
     */
    suspend fun setDayNight(cam: Camera, mode: DayNight): Boolean = withContext(Dispatchers.IO) {
        val value = when (mode) {
            DayNight.NIGHT -> "night"
            DayNight.DAY -> "day"
            DayNight.AUTO -> "auto"
        }
        val errors = ArrayList<String>()
        for (attempt in attempts(cam, Feature.GENERAL)) {
            val routeErrors = ArrayList<String>()
            for (path in dayNightPaths(cam)) {
                try {
                    val current = attempt.client.get(path)
                    if (current.code == 200) {
                        val body = current.body.orEmpty()
                        val updated = CameraXml.setValue(body, "dayNight", value)
                            ?: CameraXml.setValue(body, "mode", value)
                            ?: CameraXml.setValue(body, "dayNightMode", value)
                            ?: CameraXml.setValue(body, "DayNightFilter", value)
                        if (updated != null) {
                            val put = attempt.client.put(path, updated)
                            if (put.code == 200) {
                                val verify = attempt.client.get(path)
                                val state = if (verify.code == 200) {
                                    parseDayNightState(verify.body.orEmpty(), mode)
                                } else {
                                    mode
                                }
                                persistDayNight(cam, state)
                                Log.d(TAG, "setDayNight cam=${cam.id} route=${attempt.client.label} OK")
                                return@withContext true
                            }
                            routeErrors += "$path → PUT HTTP ${put.code}"
                        } else {
                            routeErrors += "$path → struktur day/night tidak dikenali"
                        }
                    } else {
                        routeErrors += "$path → HTTP ${current.code}"
                    }
                } catch (e: Exception) {
                    routeErrors += "$path → ${e.message}"
                    Log.w(TAG, "dayNight via ${attempt.client.label} gagal: ${e.message}")
                }
            }
            errors += routeErrors.map { "${attempt.client.label}: $it" }
        }
        // NVRIP sering tidak mengekspos dayNight. Tetap simpan pilihan pengguna
        // agar overlay mode malam dan status aplikasi berubah dengan jujur.
        persistDayNight(cam, mode)
        Log.w(TAG, "setDayNight cam=${cam.id} mode=$mode fallback lokal; detail=${errors.joinToString("; ")}")
        false
    }

    private fun parseDayNightState(body: String, fallback: DayNight): DayNight {
        val b = body.lowercase()
        return when {
            Regex("<daynight>\\s*night\\s*</daynight>").containsMatchIn(b) ||
                Regex("<mode>\\s*night\\s*</mode>").containsMatchIn(b) ||
                Regex("<daynight>\\s*0\\s*</daynight>").containsMatchIn(b) ||
                Regex("<daynight>\\s*false\\s*</daynight>").containsMatchIn(b) -> DayNight.NIGHT
            Regex("<daynight>\\s*day\\s*</daynight>").containsMatchIn(b) ||
                Regex("<mode>\\s*day\\s*</mode>").containsMatchIn(b) ||
                Regex("<daynight>\\s*1\\s*</daynight>").containsMatchIn(b) ||
                Regex("<daynight>\\s*true\\s*</daynight>").containsMatchIn(b) -> DayNight.DAY
            Regex("<daynight>\\s*auto\\s*</daynight>").containsMatchIn(b) ||
                Regex("<mode>\\s*auto\\s*</mode>").containsMatchIn(b) -> DayNight.AUTO
            else -> fallback
        }
    }

                    // ---------------- pemulihan ----------------

    suspend fun restoreAll(cameras: List<Camera>): RestoreReport = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        for (cam in cameras.filter { it.enabled }.sortedBy { it.id }) {
            val flags = prefs.flags(cam.id)

            if (flags.rtspOff) {
                runCatching { setRtsp(cam, true) }
                    .onFailure { errors.add("${cam.displayName}: RTSP — ${it.message}") }
                // Flag lokal tetap dibersihkan walau perangkat tidak merespons.
                prefs.setFlags(
                    cam.id,
                    prefs.flags(cam.id).copy(rtspOff = false, nvrOff = false)
                )
            }
            if (flags.streamOff || store.exists(cam.id, BackupKind.STREAM) ||
                store.exists(cam.id, BackupKind.INPUT_PROXY)
            ) {
                runCatching { setStream(cam, true) }
                    .onFailure { errors.add("${cam.displayName}: stream — ${it.message}") }
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(streamOff = false))
            }
            if (cam.isNvr && (flags.nvrOff ||
                    store.exists(cam.id, BackupKind.NVR) ||
                    store.exists(cam.id, BackupKind.NVR_SUB) ||
                    store.exists(cam.id, BackupKind.INPUT_PROXY))
            ) {
                runCatching { setNvr(cam, true) }
                    .onFailure { errors.add("${cam.displayName}: kanal NVR — ${it.message}") }
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(nvrOff = false))
            } else if (!cam.isNvr && !flags.rtspOff && store.exists(cam.id, BackupKind.RTSP)) {
                runCatching { setRtsp(cam, true) }
                    .onFailure { errors.add("${cam.displayName}: RTSP — ${it.message}") }
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(rtspOff = false))
            }
            if (flags.black || store.exists(cam.id, BackupKind.PRIVACY)) {
                runCatching { setBlack(cam, false) }
                    .onFailure { errors.add("${cam.displayName}: privacy mask — ${it.message}") }
            }
        }
        RestoreReport(errors)
    }

    // ---------------- kontrol bulk ----------------

    /**
     * Hentikan pemutaran semua kamera sekaligus.
     *
     * Aksi massal sengaja murni lokal: flag `blocked` langsung membuat grid dan
     * fullscreen berhenti memutar, tanpa menunggu puluhan panggilan ISAPI cloud
     * yang bisa timeout. Firmware NVR yang diuji juga mengabaikan perubahan
     * `<enabled>`, jadi panggilan jaringan tidak memberi manfaat tambahan.
     */
    suspend fun setAllCamerasOff(cameras: List<Camera>): BulkResult = withContext(Dispatchers.IO) {
        for (cam in cameras.filter { it.enabled }) {
            val flags = prefs.flags(cam.id)
            if (flags.blocked) continue
            prefs.setFlags(
                cam.id,
                flags.copy(streamOff = true, rtspOff = true, nvrOff = true)
            )
        }
        BulkResult(emptyList())
    }

    /**
     * Matikan RTSP semua kamera. Kanal NVR diputus lewat InputProxy (main + sub),
     * kamera non-NVR memakai layanan RTSP perangkat. Flag aplikasi tetap dipaksa
     * mati bila sebagian rute gagal, supaya pemutaran di ponsel ini berhenti.
     */
    suspend fun setAllRtspOff(cameras: List<Camera>): BulkResult = withContext(Dispatchers.IO) {
        val outcomes = mutableListOf<BulkOutcome>()
        for (cam in cameras.filter { it.enabled }) {
            val outcome = try {
                BulkOutcome(cam.displayName, applied = setRtsp(cam, false))
            } catch (e: Exception) {
                BulkOutcome(
                    cam.displayName,
                    error = (e.message ?: "Gagal mematikan RTSP perangkat.") +
                        " (pemutaran di aplikasi tetap dihentikan)"
                )
            }
            outcomes += outcome
            prefs.setFlags(cam.id, prefs.flags(cam.id).copy(rtspOff = true))
        }
        BulkResult.from(outcomes)
    }

    /**
     * Nyalakan kembali pemutaran semua kamera. Backup InputProxy dipulihkan bila
     * ada, jadi kanal NVR yang pernah diputus kembali mendapat alamat kamera asli.
     */
    suspend fun setAllCamerasOn(cameras: List<Camera>): BulkResult = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        for (cam in cameras.filter { it.enabled }) {
            val flags = prefs.flags(cam.id)
            val needsDeviceRestore = store.exists(cam.id, BackupKind.INPUT_PROXY) ||
                store.exists(cam.id, BackupKind.RTSP) ||
                store.exists(cam.id, BackupKind.STREAM) ||
                store.exists(cam.id, BackupKind.NVR) ||
                store.exists(cam.id, BackupKind.NVR_SUB)
            if (needsDeviceRestore) {
                runCatching { if (cam.isNvr) setNvr(cam, true) else setRtsp(cam, true) }
                    .onFailure { errors += "${cam.displayName}: ${it.message ?: "Gagal memulihkan perangkat."}" }
            }
            prefs.setFlags(
                cam.id,
                flags.copy(streamOff = false, rtspOff = false, nvrOff = false)
            )
        }
        BulkResult(errors)
    }

    /**
     * Hitamkan / pulihkan semua kamera lewat privacy mask.
     *
     * Firmware NVR yang diuji menerima PUT `enabled=false` tetapi mengabaikannya,
     * sehingga matikan kanal tidak berpengaruh ke klien lain. Privacy mask justru
     * diterapkan perangkat dan membuat semua penonton melihat layar hitam.
     */
    suspend fun setAllBlack(cameras: List<Camera>, on: Boolean): BulkResult =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            for (cam in cameras.filter { it.enabled }) {
                try {
                    setBlack(cam, on)
                } catch (e: Exception) {
                    errors += "${cam.displayName}: ${e.message ?: "Gagal mengubah privacy mask."}"
                }
            }
            BulkResult(errors)
        }

    private fun describeFlags(flags: CameraFlags): String = when {
        flags.rtspOff -> "RTSP OFF"
        flags.streamOff -> "STREAM OFF"
        flags.nvrOff -> "NVR OFF"
        flags.black -> "HITAM"
        else -> "NORMAL"
    }

    // ---------------- diagnostik ----------------

    suspend fun probe(cam: Camera): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        val endpoint = EndpointResolver.resolve(cam, prefs.remoteSettings())
        sb.append(cam.displayName).append(" — ").append(endpoint.describe).append("\n")
        if (endpoint.remote && cam.ip.isNotBlank() && !EndpointResolver.isLocalSubnet(cam.ip)) {
            sb.append("Alamat lokal: ").append(cam.ip).append(" (tidak dijangkau dari jaringan ini)\n")
        }
        val checks = listOf(
            "deviceInfo" to "/ISAPI/System/deviceInfo",
            "privacyMask" to privacyPath(cam),
            "stream ${cam.streamId(false)}" to streamPath(cam),
            "adminAccesses" to RTSP_PATH
        )
        val routes = try {
            attempts(cam, Feature.GENERAL)
        } catch (e: Exception) {
            return@withContext "GAGAL: ${e.message}"
        }
        for (attempt in routes) {
            sb.append("\n[Rute ${attempt.client.label}]\n")
            for ((name, path) in checks) {
                try {
                    val r = attempt.client.get(path)
                    if (r.code != 200) {
                        sb.append("• $name: ").append(isapiError(r)).append('\n')
                        continue
                    }
                    when (name) {
                        "deviceInfo" -> {
                            val dev = CameraXml.value(r.body, "deviceName")
                            val model = CameraXml.value(r.body, "model")
                            val fw = CameraXml.value(r.body, "firmwareVersion")
                            val desc = listOfNotNull(dev, model, fw).joinToString(" / ")
                            sb.append("• deviceInfo: OK")
                                .append(if (desc.isBlank()) "" else " ($desc)").append('\n')
                        }
                        "privacyMask" -> sb.append("• privacyMask: OK — mask ").append(
                            when (CameraXml.maskHasEnabled(r.body)) {
                                true -> "AKTIF"
                                false -> "nonaktif"
                                null -> "tidak terbaca"
                            }
                        ).append('\n')
                        "adminAccesses" -> sb.append("• adminAccesses: OK — RTSP ").append(
                            when (CameraXml.rtspEnabled(r.body)) {
                                true -> "AKTIF"
                                false -> "MATI"
                                null -> "tidak terbaca"
                            }
                        ).append('\n')
                        else -> sb.append("• $name: OK — ").append(
                            when (CameraXml.rootEnabled(r.body)) {
                                true -> "AKTIF"
                                false -> "MATI"
                                null -> "status tidak terbaca"
                            }
                        ).append('\n')
                    }
                } catch (e: Exception) {
                    sb.append("• $name: GAGAL — ${e.message}\n")
                }
            }
        }
        sb.toString()
    }

    // ---------------- probe kemampuan kamera ----------------

    /**
     * Periksa resource ISAPI mana yang benar-benar tersedia. Berguna untuk
     * mengetahui apakah kamera punya IR, sensor gerak, lampu putih, PTZ, dsb.
     */
    suspend fun probeCapabilities(cam: Camera): List<Capability> = withContext(Dispatchers.IO) {
        val checks = linkedMapOf(
            "Snapshot JPEG" to listOf("/ISAPI/Streaming/channels/${cam.streamId(false)}/picture"),
            "Mode malam / IR" to dayNightPaths(cam),
            "Sensor gerak" to movementDetectPaths(cam),
            "Privacy mask" to listOf(privacyPath(cam)),
            "Lampu putih" to supplementLightPaths(cam),
            "Gambar (WDR/contrast)" to listOf(
                "/ISAPI/System/Image/display/channels/${cam.channel}",
                "/ISAPI/System/Video/inputs/channels/${cam.channel}/wdr"
            ),
            "PTZ" to listOf("/ISAPI/PTZCtrl/channels/${cam.channel}/status"),
            "Audio" to listOf("/ISAPI/System/Audio/channels/${cam.channel}")
        )
        var best: List<Capability> = emptyList()
        for (attempt in attempts(cam, Feature.GENERAL)) {
            val result = checks.map { (name, paths) ->
                var detail = "tidak ada"
                var supported = false
                for (path in paths) {
                    try {
                        val r = attempt.client.get(path)
                        if (r.code == 200) {
                            supported = true
                            detail = "tersedia"
                            break
                        }
                        detail = "HTTP ${r.code}"
                        if (r.code == 403) {
                            supported = true
                            detail = "ada, ditolak 403"
                            break
                        }
                    } catch (e: Exception) {
                        detail = e.message ?: "gagal"
                    }
                }
                Capability(name, supported, "$detail (${attempt.client.label})")
            }
            if (result.any { it.supported }) return@withContext result
            best = result
        }
        best
    }

    /** Nyalakan/matikan lampu putih (pengganti IR pada kamera tertentu). */
    suspend fun setSupplementLight(cam: Camera, on: Boolean) = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        for (attempt in attempts(cam, Feature.GENERAL)) {
            val routeErrors = ArrayList<String>()
            for (path in supplementLightPaths(cam)) {
                try {
                    val current = attempt.client.get(path)
                    if (current.code != 200) {
                        routeErrors += "$path → HTTP ${current.code}"
                        continue
                    }
                    val updated = CameraXml.setRootEnabled(current.body, on)
                        ?: CameraXml.setValue(current.body, "enabled", on.toString())
                    if (updated == null) {
                        routeErrors += "$path → struktur tidak dikenali"
                        continue
                    }
                    val put = attempt.client.put(path, updated)
                    if (put.code == 200) return@withContext
                    routeErrors += "$path → PUT HTTP ${put.code}"
                } catch (e: Exception) {
                    routeErrors += "$path → ${e.message}"
                }
            }
            errors += routeErrors.map { "${attempt.client.label}: $it" }
        }
        throw IsapiException(
            "Lampu putih tidak didukung/diizinkan kamera. Detail: ${errors.joinToString("; ")}"
        )
    }

    private fun supplementLightPaths(cam: Camera): List<String> = listOf(
        "/ISAPI/System/Video/inputs/channels/${cam.channel}/supplementLight",
        "/ISAPI/System/Image/display/channels/${cam.channel}/supplementLight",
        "/ISAPI/System/Video/inputs/channels/${cam.channel}/WhiteLight",
        "/ISAPI/System/Video/inputs/channels/${cam.channel}/SupplementLight",
        // Kanal IP pada NVR: konfigurasi kamera dibungkus InputProxyChannel.
        "/ISAPI/ContentMgmt/InputProxy/channels/${cam.channel}/supplementLight",
        "/ISAPI/ContentMgmt/InputProxy/channels/${cam.channel}/WhiteLight"
    )

    // ---------------- helper ----------------

    /**
     * Klien ISAPI langsung. Dipakai untuk semua kontrol yang harus diubah
     * pada perangkat: kanal, stream, RTSP, mode malam, gerak, lampu, dan probe.
     * Beda dengan rute cloud, klien ini memakai kredensial kamera.
     */
    private fun directClient(cam: Camera): CamClient {
        val user = prefs.effectiveUser(cam)
        if (user.isBlank()) {
            throw IsapiException("User kamera belum diisi. Buka Setelan dan isi kredensial default.")
        }
        val endpoint = EndpointResolver.resolve(cam, prefs.remoteSettings())
        Log.d(TAG, "route DIRECT cam=${cam.id} endpoint=${endpoint.describe}")
        return CamClient.Local(
            IsapiClient(endpoint.httpBase, user, prefs.effectivePass(cam))
        )
    }

    /**
     * Klien passthrough khusus NVR. Serial yang dipakai adalah serial NVR,
     * bukan serial IPC di baliknya; passthrough IPC tidak didukung cloud.
     * Mengembalikan null bila sesi/binding tidak tersedia.
     */
    private fun cloudClient(cam: Camera): CamClient? {
        val cloud = cam.cloudDevice
        if (!cam.isNvr || cloud == null || !cloud.valid ||
            cloudRepo == null || authManager == null || authManager.session.value == null
        ) {
            return null
        }
        Log.d(
            TAG,
            "route CLOUD cam=${cam.id} serial=${cloud.serial} channel=${cloud.channel}"
        )
        return CamClient.Cloud(CloudIsapiClient(cloudRepo, authManager, cloud))
    }

    /**
     * Urutan rute eksplisit per fitur. Ini inti pemisahan lokal/cloud:
     * privacy mask NVR mengutamakan cloud, sisanya mengutamakan ISAPI langsung.
     */
    private fun attempts(cam: Camera, feature: Feature): List<RouteAttempt> {
        val cloud = cloudClient(cam)
        val offLan = runCatching {
            EndpointResolver.resolve(cam, prefs.remoteSettings()).offLan
        }.getOrDefault(false)
        val order = when {
            feature == Feature.PRIVACY && cam.isNvr && cloud != null ->
                listOf(Route.CLOUD, Route.DIRECT)
            // Saat di luar LAN, endpoint publik NVR sering menolak koneksi.
            // Passthrough cloud lebih cepat dan terbukti diterapkan perangkat.
            cam.isNvr && offLan && cloud != null ->
                listOf(Route.CLOUD, Route.DIRECT)
            cloud != null -> listOf(Route.DIRECT, Route.CLOUD)
            else -> listOf(Route.DIRECT)
        }
        val result = ArrayList<RouteAttempt>(order.size)
        for (route in order) {
            val client = when (route) {
                Route.DIRECT -> try {
                    directClient(cam)
                } catch (e: Exception) {
                    Log.w(TAG, "route DIRECT tidak tersedia cam=${cam.id}: ${e.message}")
                    null
                }
                Route.CLOUD -> cloud
            }
            if (client != null) result += RouteAttempt(route, client)
        }
        if (result.isEmpty()) {
            throw IsapiException("Tidak ada rute kontrol yang tersedia untuk ${cam.displayName}.")
        }
        return result
    }

    private fun privacyPath(cam: Camera) =
        "/ISAPI/System/Video/inputs/channels/${cam.channel}/privacyMask"

    private fun streamPath(cam: Camera) =
        "/ISAPI/Streaming/channels/${cam.streamId(false)}"

    private fun inputProxyPath(cam: Camera) =
        "/ISAPI/ContentMgmt/InputProxy/channels/${cam.channel}"

    /**
     * Putuskan/sambungkan kembali sumber video kanal NVR lewat InputProxy.
     *
     * Firmware NVR mengabaikan `<enabled>false</enabled>` pada resource
     * streaming channel, tetapi benar-benar memutus kanal bila alamat IP
     * sumber pada `InputProxyChannel` diganti ke alamat TEST-NET yang tidak
     * dapat dijangkau. XML asli disimpan sebagai backup dan dipakai untuk
     * mengembalikan alamat sumber.
     */
    private suspend fun applyInputProxyChannel(
        client: CamClient,
        cam: Camera,
        on: Boolean
    ): Boolean {
        if (!cam.isNvr) return false
        val path = inputProxyPath(cam)
        val current = client.get(path)
        if (current.code != 200) {
            Log.w(TAG, "inputProxy $path via ${client.label}: HTTP ${current.code}")
            return false
        }
        val body = current.body.orEmpty()
        val currentIp = inputProxyIp(body)

        if (on) {
            val backup = store.read(cam.id, BackupKind.INPUT_PROXY)
            if (backup == null) {
                val ok = !InputProxyAddress.isDummy(currentIp)
                Log.d(TAG, "inputProxy restore cam=${cam.id} tanpa backup ok=$ok ip=$currentIp")
                return ok
            }
            val put = client.put(path, backup)
            if (put.code != 200) {
                // Beberapa firmware membalas statusCode 4 walaupun perubahan
                // alamat sumber tetap diterapkan. Verifikasi GET jadi penentu.
                Log.w(TAG, "inputProxy restore cam=${cam.id} via ${client.label}: PUT ${put.code}")
            }
            delay(400)
            val verify = client.get(path)
            val restoredIp = if (verify.code == 200) inputProxyIp(verify.body.orEmpty()) else null
            if (InputProxyAddress.isDummy(restoredIp)) {
                Log.w(TAG, "inputProxy restore cam=${cam.id}: alamat belum pulih ($restoredIp)")
                return false
            }
            store.delete(cam.id, BackupKind.INPUT_PROXY)
            Log.d(TAG, "inputProxy restore cam=${cam.id} route=${client.label} ip=$restoredIp")
            return true
        }

        val dummyIp = InputProxyAddress.dummyIp(cam.channel)
        if (InputProxyAddress.isDummy(currentIp)) {
            Log.d(TAG, "inputProxy cam=${cam.id} sudah terputus ip=$currentIp")
            return true
        }
        if (currentIp == null) {
            Log.w(TAG, "inputProxy cam=${cam.id}: ipAddress tidak ditemukan")
            return false
        }
        val backup = store.read(cam.id, BackupKind.INPUT_PROXY) ?: body.also {
            store.write(cam.id, BackupKind.INPUT_PROXY, it)
        }
        val offlineBody = replaceInputProxyIp(body, dummyIp) ?: return false
        val put = client.put(path, offlineBody)
        if (put.code != 200) {
            Log.w(TAG, "inputProxy off cam=${cam.id} via ${client.label}: PUT ${put.code}")
        }
        delay(400)
        val verify = client.get(path)
        val applied = verify.code == 200 &&
            inputProxyIp(verify.body.orEmpty()) == dummyIp
        if (!applied) {
            runCatching { client.put(path, backup) }
            Log.w(TAG, "inputProxy off cam=${cam.id}: perangkat tidak mengonfirmasi IP dummy")
        } else {
            Log.d(TAG, "inputProxy off cam=${cam.id} route=${client.label} ip=$dummyIp")
        }
        return applied
    }

    private fun inputProxyIp(body: String): String? =
        Regex("<ipAddress>\\s*([^<]+?)\\s*</ipAddress>")
            .find(body)?.groupValues?.get(1)?.trim()

    private fun replaceInputProxyIp(body: String, ip: String): String? {
        val regex = Regex("<ipAddress>\\s*[^<]+?\\s*</ipAddress>")
        if (!regex.containsMatchIn(body)) return null
        return regex.replace(body, "<ipAddress>$ip</ipAddress>")
    }

    private fun dayNightPaths(cam: Camera): List<String> = listOf(
        "/ISAPI/System/Video/inputs/channels/${cam.channel}/dayNight",
        "/ISAPI/System/Image/display/channels/${cam.channel}",
        "/ISAPI/System/Video/inputs/channels/${cam.streamId(true)}/dayNight",
        // Kanal IP pada NVR: konfigurasi kamera dibungkus InputProxyChannel.
        "/ISAPI/ContentMgmt/InputProxy/channels/${cam.channel}/dayNight",
        "/ISAPI/ContentMgmt/InputProxy/channels/${cam.channel}/image"
    )

    private fun movementDetectPaths(cam: Camera): List<String> = listOf(
        "/ISAPI/System/Video/inputs/channels/${cam.channel}/motionDetection",
        "/ISAPI/System/Event/notification/1/motionDetection",
        "/ISAPI/System/Event/notification/1/channels/${cam.channel}/motionDetection",
        "/ISAPI/System/Event/notification/1/channels/${cam.streamId(true)}/isMovementDetect",
        "/ISAPI/System/Event/notification/1/channels/${cam.channel}/isMovementDetect"
    )

    /** Simpan mode malam + tulis ulang daftar kamera agar persisten. */
    private fun persistDayNight(cam: Camera, dayNight: DayNight) {
        val all = prefs.cameras().toMutableList()
        val idx = all.indexOfFirst { it.id == cam.id }
        if (idx >= 0) {
            all[idx].dayNight = dayNight
            prefs.saveCameras(all)
        }
    }

    /**
     * Aktifkan/nonaktifkan deteksi gerakan (sensor gerak).
     * Firmware Hikvision umumnya menolak PUT potongan XML, jadi konfigurasi
     * dibaca dulu lalu diubah dan dikirim utuh. Beberapa jalur ISAPI dicoba
     * karena letak resource berbeda antar model/firmware.
     */
    suspend fun setMovementDetect(cam: Camera, on: Boolean) = withContext(Dispatchers.IO) {
        val value = on.toString()
        val errors = ArrayList<String>()
        for (attempt in attempts(cam, Feature.GENERAL)) {
            val routeErrors = ArrayList<String>()
            for (path in movementDetectPaths(cam)) {
                try {
                    val current = attempt.client.get(path)
                    if (current.code != 200) {
                        routeErrors += "$path → HTTP ${current.code}"
                        continue
                    }
                    val body = current.body.orEmpty()
                    val updated = CameraXml.setRootEnabled(body, on)
                        ?: CameraXml.setValue(body, "isMovementDetect", value)
                        ?: CameraXml.setValue(body, "isMotionDetection", value)
                        ?: CameraXml.setValue(body, "motionDetection", value)
                        ?: CameraXml.setValue(body, "enabled", value)
                    if (updated == null) {
                        routeErrors += "$path → struktur XML tidak dikenali"
                        continue
                    }
                    val put = attempt.client.put(path, updated)
                    if (put.code != 200) {
                        routeErrors += "$path → PUT HTTP ${put.code}"
                        continue
                    }
                    val verify = attempt.client.get(path)
                    val state = if (verify.code == 200) {
                        readMotionState(verify.body.orEmpty(), on)
                    } else {
                        on
                    }
                    persistMovementDetect(cam, state)
                    Log.d(TAG, "setMovementDetect cam=${cam.id} route=${attempt.client.label} OK")
                    return@withContext
                } catch (e: Exception) {
                    routeErrors += "$path → ${e.message}"
                    Log.w(TAG, "movementDetect via ${attempt.client.label} gagal: ${e.message}")
                }
            }
            errors += routeErrors.map { "${attempt.client.label}: $it" }
        }
        throw IsapiException(
            "Sensor gerak tidak didukung/diizinkan kamera (HTTP 403/404). " +
                "Detail: ${errors.joinToString("; ")}"
        )
    }

    private fun readMotionState(body: String, fallback: Boolean): Boolean {
        val v = body.lowercase()
        return when {
            v.contains("<enabled>true</enabled>") -> true
            v.contains("<enabled>false</enabled>") -> false
            v.contains("<ismovementdetect>true") -> true
            v.contains("<ismovementdetect>false") -> false
            v.contains("<ismotiondetection>true") -> true
            v.contains("<ismotiondetection>false") -> false
            else -> fallback
        }
    }

    /** Simpan mode gerakan + tulis ulang daftar kamera agar persisten. */
    private fun persistMovementDetect(cam: Camera, enabled: Boolean) {
        val all = prefs.cameras().toMutableList()
        val idx = all.indexOfFirst { it.id == cam.id }
        if (idx >= 0) {
            all[idx].movementDetect = enabled
            prefs.saveCameras(all)
        }
    }

    /** Aktifkan kembali entri dari backup, atau set <enabled>true</enabled> bila tanpa backup. */
    private suspend fun restoreEnabled(
        client: CamClient,
        cam: Camera,
        path: String,
        kind: BackupKind,
        parseError: String
    ) {
        val backup = store.read(cam.id, kind)
        if (backup != null) {
            val put = client.put(path, backup)
            if (put.code != 200) throw IsapiException(isapiError(put), put.code)
            store.delete(cam.id, kind)
            return
        }
        val current = client.get(path)
        if (current.code != 200) throw IsapiException(isapiError(current), current.code)
        val updated = CameraXml.setOrAddRootEnabled(current.body, true) ?: throw IsapiException(parseError)
        val put = client.put(path, updated)
        if (put.code != 200) throw IsapiException(isapiError(put), put.code)
    }

    private suspend fun rollback(
        client: CamClient,
        cam: Camera,
        path: String,
        kind: BackupKind
    ) {
        val backup = store.read(cam.id, kind) ?: return
        try {
            val result = client.put(path, backup)
            if (result.code == 200) store.delete(cam.id, kind)
        } catch (_: Exception) {
            // biarkan backup tetap ada agar bisa dipulihkan nanti
        }
    }

    companion object {
        private const val TAG = "ControlRepository"
        private const val RTSP_PATH = "/ISAPI/Security/adminAccesses"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }
}
