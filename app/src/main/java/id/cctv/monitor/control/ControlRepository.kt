package id.cctv.monitor.control

import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.BackupKind
import id.cctv.monitor.data.BackupStore
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags
import id.cctv.monitor.net.CameraXml
import id.cctv.monitor.net.EndpointResolver
import id.cctv.monitor.net.IsapiClient
import id.cctv.monitor.net.IsapiException
import id.cctv.monitor.net.isapiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

data class RestoreReport(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    val summary: String
        get() = if (ok) {
            "Semua kamera berhasil dipulihkan."
        } else {
            "Pemulihan selesai dengan ${errors.size} masalah:\n" + errors.joinToString("\n")
        }
}

/** Hasil operasi bulk: matikan / nyalakan semua kamera. */
data class BulkResult(val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
    val summary: String
        get() = if (ok) {
            "Semua kamera berhasil dimatikan."
        } else {
            "Pemutaran berhasil dihentikan, tetapi ${errors.size} kamera mengalami masalah:\n" + errors.joinToString("\n")
        }
}

/**
 * Semua kontrol ISAPI kamera. Logika backup / verifikasi / rollback mengikuti
 * CCTV-Control.ps1 di PC.
 */
class ControlRepository(
    private val prefs: AppPrefs,
    private val store: BackupStore
) {

    // ---------------- privacy mask (HITAM / NORMAL) ----------------

    suspend fun setBlack(cam: Camera, on: Boolean) {
        withContext(Dispatchers.IO) {
            val client = client(cam)
            val path = privacyPath(cam)
            if (!on) {
                restoreBlack(client, cam, path)
                return@withContext
            }

            val current = client.get(path)
            if (current.code != 200) throw IsapiException(isapiError(current), current.code)
            if (!store.exists(cam.id, BackupKind.PRIVACY)) {
                store.write(cam.id, BackupKind.PRIVACY, current.body)
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
    }

    private fun restoreBlack(client: IsapiClient, cam: Camera, path: String) {
        val backup = store.read(cam.id, BackupKind.PRIVACY)
        if (backup != null) {
            val put = client.put(path, backup)
            if (put.code != 200) throw IsapiException(isapiError(put), put.code)
            store.delete(cam.id, BackupKind.PRIVACY)
        } else {
            val current = client.get(path)
            if (current.code != 200) throw IsapiException(isapiError(current), current.code)
            val updated = CameraXml.setMaskEnabled(current.body, 0, 0, false)
                ?: throw IsapiException("Format privacy mask kamera tidak dikenali.")
            val put = client.put(path, updated)
            if (put.code != 200) throw IsapiException(isapiError(put), put.code)
        }
        prefs.setFlags(cam.id, prefs.flags(cam.id).copy(black = false))
    }

    private fun mainResolution(client: IsapiClient, cam: Camera): Pair<Int, Int> {
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

    suspend fun setStream(cam: Camera, on: Boolean) {
        withContext(Dispatchers.IO) {
            if (cam.isNvr && !on) {
                throw IsapiException(
                    "${cam.displayName} (${cam.ip}) adalah kanal NVR; sub-stream NVR tidak " +
                        "bisa dimatikan per kanal. Gunakan KANAL STOP."
                )
            }
            val client = client(cam)
            val path = streamPath(cam)
            if (on) {
                restoreEnabled(client, cam, path, BackupKind.STREAM, "Format streaming channel kamera tidak dikenali.")
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(streamOff = false))
                return@withContext
            }

            val current = client.get(path)
            if (current.code != 200) throw IsapiException(isapiError(current), current.code)
            if (!store.exists(cam.id, BackupKind.STREAM)) {
                store.write(cam.id, BackupKind.STREAM, current.body)
            }
            val updated = CameraXml.setRootEnabled(current.body, false)
                ?: throw IsapiException("Format streaming channel kamera tidak dikenali.")
            val put = client.put(path, updated)
            if (put.code != 200) throw IsapiException(isapiError(put), put.code)

            var stopped = false
            var lastCode = 200
            for (i in 1..4) {
                delay(350)
                val verify = client.get(path)
                if (verify.code != 200) {
                    lastCode = verify.code
                    break
                }
                if (CameraXml.rootEnabled(verify.body) == false) {
                    stopped = true
                    break
                }
            }
            if (stopped) {
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(streamOff = true))
                return@withContext
            }

            rollback(client, cam, path, BackupKind.STREAM)
            if (lastCode != 200) {
                throw IsapiException(
                    "Kamera ${cam.displayName} (${cam.ip}) menerima perintah, tetapi status " +
                        "stream tidak bisa dibaca (HTTP $lastCode). Coba lagi atau pakai HITAM / RTSP OFF."
                )
            }
            throw IsapiException(
                "Firmware kamera ${cam.displayName} (${cam.ip}) tidak mengizinkan sub-stream 102 " +
                    "dimatikan: perintah diterima, tetapi stream tetap aktif. Gunakan HITAM, " +
                    "PAKSA RTSP OFF, atau KANAL STOP."
            )
        }
    }

    // ---------------- layanan RTSP (paksa mati) ----------------

    suspend fun setRtsp(cam: Camera, on: Boolean) {
        withContext(Dispatchers.IO) {
            if (cam.isNvr) {
                throw IsapiException(
                    "${cam.displayName} adalah NVR (${cam.ip}). Mematikan layanan RTSP NVR akan " +
                        "menghentikan SEMUA kanal sekaligus, jadi tidak diizinkan. Gunakan KANAL STOP."
                )
            }
            val client = client(cam)
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
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(rtspOff = false))
                return@withContext
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

            if (waitForRtsp(client, path, false)) {
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(rtspOff = true))
                return@withContext
            }

            rollback(client, cam, path, BackupKind.RTSP)
            throw IsapiException(
                "Kamera ${cam.displayName} (${cam.ip}) tidak mengizinkan layanan RTSP dimatikan " +
                    "lewat ISAPI. Tidak ada perubahan yang tertinggal."
            )
        }
    }

    private suspend fun waitForRtsp(client: IsapiClient, path: String, active: Boolean): Boolean {
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

    suspend fun setNvr(cam: Camera, on: Boolean) {
        withContext(Dispatchers.IO) {
            if (!cam.isNvr) {
                throw IsapiException("${cam.displayName} bukan kamera NVR.")
            }
            val client = client(cam)
            val path = nvrPath(cam)
            if (on) {
                restoreEnabled(client, cam, path, BackupKind.NVR, "Format kanal NVR tidak dikenali.")
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(nvrOff = false))
                return@withContext
            }

            val current = client.get(path)
            if (current.code != 200) throw IsapiException(isapiError(current), current.code)
            if (!store.exists(cam.id, BackupKind.NVR)) {
                store.write(cam.id, BackupKind.NVR, current.body)
            }
            val updated = CameraXml.setOrAddRootEnabled(current.body, false)
                ?: throw IsapiException("Format kanal NVR tidak dikenali.")
            val put = client.put(path, updated)
            if (put.code != 200) throw IsapiException(isapiError(put), put.code)

            var stopped = false
            var lastCode = 200
            for (i in 1..4) {
                delay(350)
                val verify = client.get(path)
                if (verify.code != 200) {
                    lastCode = verify.code
                    break
                }
                if (CameraXml.rootEnabled(verify.body) == false) {
                    stopped = true
                    break
                }
            }
            if (stopped) {
                prefs.setFlags(cam.id, prefs.flags(cam.id).copy(nvrOff = true))
                return@withContext
            }

            rollback(client, cam, path, BackupKind.NVR)
            if (lastCode != 200) {
                throw IsapiException(
                    "NVR menerima perintah, tetapi status kanal tidak bisa dibaca (HTTP $lastCode). " +
                        "Coba lagi nanti."
                )
            }
            throw IsapiException(
                "Firmware NVR (${cam.ip}) tidak menyediakan tombol aktif/nonaktif kanal lewat ISAPI: " +
                    "perintah diterima tetapi status kanal tidak berubah. Hentikan rekaman kanal ini " +
                    "lewat menu NVR (Manajemen Kamera)."
            )
        }
    }

    // ---------------- pemulihan ----------------

    suspend fun restoreAll(cameras: List<Camera>): RestoreReport = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        for (cam in cameras.filter { it.enabled }.sortedBy { it.id }) {
            val flags = prefs.flags(cam.id)

            if (!cam.isNvr && (flags.rtspOff || store.exists(cam.id, BackupKind.RTSP))) {
                runCatching { setRtsp(cam, true) }
                    .onFailure { errors.add("${cam.displayName}: RTSP — ${it.message}") }
            }
            if (flags.streamOff || store.exists(cam.id, BackupKind.STREAM)) {
                runCatching { setStream(cam, true) }
                    .onFailure { errors.add("${cam.displayName}: stream — ${it.message}") }
            }
            if (cam.isNvr && (flags.nvrOff || store.exists(cam.id, BackupKind.NVR))) {
                runCatching { setNvr(cam, true) }
                    .onFailure { errors.add("${cam.displayName}: kanal NVR — ${it.message}") }
            }
            if (flags.black || store.exists(cam.id, BackupKind.PRIVACY)) {
                runCatching { setBlack(cam, false) }
                    .onFailure { errors.add("${cam.displayName}: privacy mask — ${it.message}") }
            }
        }
        RestoreReport(errors)
    }

    // ---------------- kontrol bulk ----------------

    /** Matikan semua kamera yang aktif sekaligus (tanpa mematikan satu-satu). */
    suspend fun setAllCamerasOff(cameras: List<Camera>): BulkResult = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        for (cam in cameras.filter { it.enabled }) {
            val flags = prefs.flags(cam.id)
            if (flags.blocked) {
                errors.add("${cam.displayName}: sudah mati (status: ${describeFlags(flags)})")
                continue
            }
            if (!cam.isNvr && !flags.streamOff) {
                try { setStream(cam, false) } catch (e: Exception) {
                    errors.add("${cam.displayName}: stream off — ${e.message}")
                }
            }
            if (!flags.rtspOff) {
                try { setRtsp(cam, false) } catch (e: Exception) {
                    errors.add("${cam.displayName}: RTSP off — ${e.message}")
                }
            }
            if (cam.isNvr && !flags.nvrOff) {
                try { setNvr(cam, false) } catch (e: Exception) {
                    errors.add("${cam.displayName}: NVR off — ${e.message}")
                }
            }
        }
        BulkResult(errors)
    }

    /** Nyalakan kembali semua kamera yang aktif sekaligus. */
    suspend fun setAllCamerasOn(cameras: List<Camera>): BulkResult = withContext(Dispatchers.IO) {
        val errors = ArrayList<String>()
        for (cam in cameras.filter { it.enabled }) {
            val flags = prefs.flags(cam.id)
            if (!flags.rtspOff) {
                try { setRtsp(cam, true) } catch (e: Exception) {
                    errors.add("${cam.displayName}: RTSP on — ${e.message}")
                }
            } else {
                // RTSP yang sudah off akan diaktifkan oleh setRtsp di atas
            }
            if (!flags.streamOff && !cam.isNvr) {
                try { setStream(cam, true) } catch (e: Exception) {
                    errors.add("${cam.displayName}: stream on — ${e.message}")
                }
            }
            if (cam.isNvr && !flags.nvrOff) {
                try { setNvr(cam, true) } catch (e: Exception) {
                    errors.add("${cam.displayName}: NVR on — ${e.message}")
                }
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
        val client = try {
            client(cam)
        } catch (e: Exception) {
            return@withContext "GAGAL: ${e.message}"
        }
        val checks = listOf(
            "deviceInfo" to "/ISAPI/System/deviceInfo",
            "privacyMask" to privacyPath(cam),
            "stream ${cam.streamId(false)}" to streamPath(cam),
            "adminAccesses" to RTSP_PATH
        )
        for ((name, path) in checks) {
            try {
                val r = client.get(path)
                if (r.code != 200) {
                    sb.append("\n• $name: ").append(isapiError(r))
                    continue
                }
                when (name) {
                    "deviceInfo" -> {
                        val dev = CameraXml.value(r.body, "deviceName")
                        val model = CameraXml.value(r.body, "model")
                        val fw = CameraXml.value(r.body, "firmwareVersion")
                        val desc = listOfNotNull(dev, model, fw).joinToString(" / ")
                        sb.append("\n• deviceInfo: OK").append(if (desc.isBlank()) "" else " ($desc)")
                    }
                    "privacyMask" -> sb.append("\n• privacyMask: OK — mask ").append(
                        when (CameraXml.maskHasEnabled(r.body)) {
                            true -> "AKTIF"
                            false -> "nonaktif"
                            null -> "tidak terbaca"
                        }
                    )
                    "adminAccesses" -> sb.append("\n• adminAccesses: OK — RTSP ").append(
                        when (CameraXml.rtspEnabled(r.body)) {
                            true -> "AKTIF"
                            false -> "MATI"
                            null -> "tidak terbaca"
                        }
                    )
                    else -> sb.append("\n• $name: OK — ").append(
                        when (CameraXml.rootEnabled(r.body)) {
                            true -> "AKTIF"
                            false -> "MATI"
                            null -> "status tidak terbaca"
                        }
                    )
                }
            } catch (e: Exception) {
                sb.append("\n• $name: GAGAL — ${e.message}")
            }
        }
        sb.toString()
    }

    // ---------------- helper ----------------

    private fun client(cam: Camera): IsapiClient {
        val user = prefs.effectiveUser(cam)
        if (user.isBlank()) {
            throw IsapiException("User kamera belum diisi. Buka Setelan dan isi kredensial default.")
        }
        val endpoint = EndpointResolver.resolve(cam, prefs.remoteSettings())
        return IsapiClient(endpoint.httpBase, user, prefs.effectivePass(cam))
    }

    private fun privacyPath(cam: Camera) =
        "/ISAPI/System/Video/inputs/channels/${cam.channel}/privacyMask"

    private fun streamPath(cam: Camera) =
        "/ISAPI/Streaming/channels/${cam.streamId(false)}"

    private fun nvrPath(cam: Camera) =
        "/ISAPI/ContentMgmt/InputProxy/channels/${cam.channel}"

    /** Aktifkan kembali entri dari backup, atau set <enabled>true</enabled> bila tanpa backup. */
    private fun restoreEnabled(
        client: IsapiClient,
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

    private fun rollback(
        client: IsapiClient,
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
        private const val RTSP_PATH = "/ISAPI/Security/adminAccesses"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }
}
