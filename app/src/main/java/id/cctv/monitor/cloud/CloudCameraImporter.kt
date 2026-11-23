package id.cctv.monitor.cloud

import id.cctv.monitor.data.AppPrefs
import id.cctv.monitor.data.Camera
import id.cctv.monitor.data.CameraFlags

/**
 * Memasukkan semua kanal hasil sinkronisasi Hik-Connect ke daftar kamera
 * aplikasi, tanpa perlu BIND manual per kanal.
 *
 * Impor bersifat idempoten: kamera yang sudah terikat ke serial + kanal yang
 * sama hanya diperbarui metadatanya. Kamera hasil konfigurasi bawaan
 * (`CAM-01`..`CAM-09` dengan IP contoh) dipakai ulang lalu sisanya dibuang,
 * sedangkan kamera yang benar-benar diatur pengguna tidak disentuh.
 */
class CloudCameraImporter(private val prefs: AppPrefs) {

    data class Result(
        val devices: Int,
        val added: Int,
        val updated: Int,
        val changed: Boolean
    )

    fun import(devices: List<CloudDevice>): Result {
        // Hanya NVR DS-7608NI-Q1 yang ditampilkan, maksimal 8 kanal per serial.
        // Hik-Connect bisa mengembalikan kanal tambahan/lama yang tidak ada
        // pada instalasi Wi-Fi lokal, jadi kanal di luar model itu dibuang.
        val valid = selectAllowed(devices)
        android.util.Log.d(
            "CloudCameraImporter",
            "import devices=${devices.size} valid=${valid.size}"
        )
        if (valid.isEmpty()) return Result(0, 0, 0, false)

        val cameras = prefs.cameras()
        val original = cameras.toList()
        val allowedBindings = valid.map { bindingKey(it.serial, it.channel) }.toSet()
        // Perbaiki dulu binding lama yang salah pasangan (mis. kamera bernama
        // "Camera 08" tetapi cloudDevice masih menunjuk kanal 1). Tanpa ini,
        // kanal baru selalu ditambahkan sebagai kamera duplikat.
        val repaired = repairStaleBindings(cameras, valid)
        val used = HashSet<Int>()
        val imported = ArrayList<Camera>(valid.size)
        var added = 0
        var updated = 0
        var nextId = (original.maxOfOrNull { it.id } ?: 0) + 1

        for (device in valid) {
            val match = findMatch(repaired, used, device)
            val code = device.verificationCode
                .ifBlank { prefs.cloudVerificationCode(device.channelSerial) }
                .ifBlank { prefs.cloudVerificationCode(device.serial) }
            val cloud = device.copy(verificationCode = code)

            if (match != null) {
                used.add(match.id)
                val existing = match.cloudDevice
                val sameBinding = existing != null &&
                    existing.serial.equals(device.serial, ignoreCase = true) &&
                    existing.channel == device.channel
                val merged = cloud.copy(
                    internalId = match.id,
                    // URL khusus yang pernah diisi pengguna dipertahankan.
                    streamUrl = if (sameBinding) existing?.streamUrl.orEmpty() else "",
                    verificationCode = code.ifBlank {
                        if (sameBinding) existing?.verificationCode.orEmpty() else ""
                    }
                )
                imported.add(
                    match.copy(
                        name = if (isPlaceholder(match) || match.name.isBlank()) {
                            device.name.ifBlank { "CH${device.channel}" }
                        } else {
                            match.name
                        },
                        ip = preferredIp(match.ip, device),
                        channel = device.channel,
                        isNvr = device.isNvr,
                        cloudDevice = merged
                    )
                )
                updated++
            } else {
                val id = nextId++
                imported.add(
                    Camera(
                        id = id,
                        name = device.name.ifBlank { "CH${device.channel}" },
                        ip = preferredIp("", device),
                        channel = device.channel,
                        isNvr = device.isNvr,
                        enabled = true,
                        user = "",
                        pass = "",
                        cloudDevice = cloud.copy(internalId = id)
                    )
                )
                added++
            }
        }

        // Pertahankan kamera pengguna yang tidak punya padanan cloud. Tile lama
        // yang terikat ke kanal NVR di atas batas (9–12) ikut dibuang supaya
        // jumlah kamera kembali ke 8. Flagnya juga dikosongkan agar tidak
        // terbawa bila kanal tersebut suatu hari ditambahkan lagi.
        for (camera in repaired) {
            if (camera.id in used) continue
            val bound = camera.cloudDevice
            if (bound != null &&
                bindingKey(bound.serial, bound.channel) !in allowedBindings
            ) {
                prefs.setFlags(camera.id, CameraFlags())
                continue
            }
            if (isPlaceholder(camera)) continue
            imported.add(camera)
        }
        imported.sortBy { it.id }
        val clean = deduplicateBindings(imported, valid)

        val changed = clean != original
        android.util.Log.d(
            "CloudCameraImporter",
            "import result saved=${cameras.size} clean=${clean.size} changed=$changed added=$added updated=$updated"
        )
        if (changed) {
            // Kode hasil pengambilan otomatis juga disimpan per serial agar
            // semua kanal NVR memakai kunci yang sama.
            for (device in valid) {
                // Jangan menimpa kode yang sudah tersimpan (mis. hasil ISAPI
                // LAN yang paling akurat) dengan nilai dari cloud.
                if (device.encrypted && device.verificationCode.isNotBlank()) {
                    if (prefs.cloudVerificationCode(device.serial).isBlank()) {
                        prefs.saveCloudVerificationCode(device.serial, device.verificationCode)
                    }
                    if (device.channelSerial.isNotBlank() &&
                        prefs.cloudVerificationCode(device.channelSerial).isBlank()
                    ) {
                        prefs.saveCloudVerificationCode(
                            device.channelSerial,
                            device.verificationCode
                        )
                    }
                }
            }
            prefs.saveCameras(clean)
        }
        return Result(valid.size, added, updated, changed)
    }

    /** Pilih hanya kanal dari NVR DS-7608NI-Q1, maksimal [MAX_NVR_CHANNEL]. */
    private fun selectAllowed(devices: List<CloudDevice>): List<CloudDevice> {
        val perSerial = HashMap<String, Int>()
        return devices.filter { device ->
            if (!device.isAllowedModel()) return@filter false
            if (!device.isNvr) return@filter true
            val key = device.serial.ifBlank {
                device.cloudDeviceId.substringBefore('#')
            }
            val used = perSerial.getOrPut(key) { 0 }
            if (used >= MAX_NVR_CHANNEL) return@filter false
            perSerial[key] = used + 1
            true
        }
    }

    private fun CloudDevice.isAllowedModel(): Boolean {
        if (!valid) return false
        val model = deviceType.ifBlank { name }
        // Hik-Connect melaporkan DS-7608NI-Q1 sebagai "NVR-8-F". Nama model
        // datasheet juga diterima supaya filter tetap benar bila cloud berubah.
        return ALLOWED_MODELS.any { model.contains(it, ignoreCase = true) }
    }

    /**
     * Beberapa perangkat pernah tersimpan dengan kanal lama (mis. semua kanal
     * menunjuk ke kanal 1). Bila nama kamera cocok dengan nama kanal cloud yang
     * sama, pindahkan binding ke kanal tersebut agar stream tidak tertukar.
     */
    private fun repairStaleBindings(
        cameras: List<Camera>,
        devices: List<CloudDevice>
    ): List<Camera> {
        val byName = devices.associateBy { it.name.trim().lowercase() }
        if (byName.isEmpty()) return cameras
        return cameras.map { camera ->
            val bound = camera.cloudDevice ?: return@map camera
            val device = byName[camera.name.trim().lowercase()] ?: return@map camera
            if (!device.serial.equals(bound.serial, ignoreCase = true)) return@map camera
            val defaultName = DEFAULT_NAME.matches(camera.name)
            if (device.channel == bound.channel &&
                device.channelSerial == bound.channelSerial &&
                !defaultName
            ) {
                return@map camera
            }
            camera.copy(
                name = if (defaultName) device.name else camera.name,
                channel = device.channel,
                isNvr = device.isNvr,
                ip = camera.ip.ifBlank { device.localIp },
                cloudDevice = bound.copy(
                    cloudDeviceId = device.cloudDeviceId.ifBlank { bound.cloudDeviceId },
                    serial = device.serial,
                    channelSerial = device.channelSerial.ifBlank { bound.channelSerial },
                    name = device.name.ifBlank { bound.name },
                    channel = device.channel,
                    deviceType = device.deviceType.ifBlank { bound.deviceType },
                    firmware = device.firmware.ifBlank { bound.firmware },
                    isNvr = device.isNvr,
                    online = device.online,
                    encrypted = device.encrypted,
                    encryptionPwd = device.encryptionPwd.ifBlank { bound.encryptionPwd },
                    verificationCode = device.verificationCode.ifBlank { bound.verificationCode },
                    localIp = device.localIp.ifBlank { bound.localIp },
                    wanIp = device.wanIp.ifBlank { bound.wanIp }
                )
            )
        }
    }

    /** Sisakan satu kamera per pasangan serial+kanal; buang duplikat cloud. */
    private fun deduplicateBindings(
        cameras: List<Camera>,
        devices: List<CloudDevice>
    ): List<Camera> {
        val deviceByKey = devices.associateBy { bindingKey(it.serial, it.channel) }
        val best = HashMap<String, Camera>()
        val bestScore = HashMap<String, Int>()
        for (camera in cameras) {
            val bound = camera.cloudDevice ?: continue
            val key = bindingKey(bound.serial, bound.channel)
            val device = deviceByKey[key]
            val score = when {
                device != null && camera.name.equals(device.name, ignoreCase = true) -> 0
                device != null && DEFAULT_NAME.matches(camera.name) -> 2
                device != null -> 1
                else -> 3
            }
            val current = best[key]
            val currentScore = bestScore[key] ?: Int.MAX_VALUE
            if (current == null || score < currentScore ||
                (score == currentScore && camera.id < current.id)
            ) {
                best[key] = camera
                bestScore[key] = score
            }
        }
        return cameras.filter { camera ->
            val bound = camera.cloudDevice ?: return@filter true
            val key = bindingKey(bound.serial, bound.channel)
            best[key] === camera || !deviceByKey.containsKey(key)
        }
    }

    private fun bindingKey(serial: String, channel: Int): String =
        "${serial.trim().lowercase()}|$channel"

    private fun findMatch(
        cameras: List<Camera>,
        used: Set<Int>,
        device: CloudDevice
    ): Camera? {
        val available = cameras.filter { it.id !in used }

        // 1. Sudah terikat ke serial + kanal yang sama.
        available.firstOrNull { camera ->
            val bound = camera.cloudDevice ?: return@firstOrNull false
            bound.serial.equals(device.serial, ignoreCase = true) &&
                bound.channel == device.channel
        }?.let { return it }

        // 2. Kamera tunggal dengan serial yang sama.
        if (!device.isNvr) {
            available.firstOrNull { camera ->
                val bound = camera.cloudDevice ?: return@firstOrNull false
                bound.serial.equals(device.serial, ignoreCase = true)
            }?.let { return it }
        }

        // 3. Konfigurasi manual NVR: host + kanal yang sama.
        if (device.isNvr && device.localIp.isNotBlank()) {
            available.firstOrNull { camera ->
                camera.cloudDevice == null && camera.isNvr &&
                    camera.channel == device.channel && camera.ip == device.localIp
            }?.let { return it }
        }

        // 4. Kamera tunggal dengan IP lokal yang sama.
        if (!device.isNvr && device.localIp.isNotBlank()) {
            available.firstOrNull { camera ->
                camera.cloudDevice == null && !camera.isNvr &&
                    camera.ip == device.localIp
            }?.let { return it }
        }

        // 5. Placeholder bawaan (urutan kanal mengikuti urutan device).
        available.firstOrNull { isPlaceholder(it) }?.let { return it }
        return null
    }

    private fun preferredIp(current: String, device: CloudDevice): String =
        device.localIp.ifBlank { current }

    /** Kamera contoh dari [AppPrefs.defaultCameras] yang belum pernah diubah. */
    private fun isPlaceholder(camera: Camera): Boolean {
        if (camera.cloudDevice != null) return false
        if (camera.user.isNotBlank() || camera.pass.isNotBlank()) return false
        val match = PLACEHOLDER_NAME.find(camera.name) ?: return false
        val id = match.groupValues[1].toIntOrNull() ?: return false
        if (id !in 1..9) return false
        val expected = if (id == 9) "192.168.100.34" else "192.168.100.${19 + id}"
        return camera.ip == expected
    }

    private companion object {
        val ALLOWED_MODELS = listOf("NVR-8-F", "DS-7608NI-Q1")
        const val MAX_NVR_CHANNEL = 8
        val PLACEHOLDER_NAME = Regex("^CAM-(\\d{2})$")
        val DEFAULT_NAME = Regex("^CAM-\\d{2}$")
    }
}
