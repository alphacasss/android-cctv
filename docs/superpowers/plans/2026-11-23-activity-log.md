# Aktifitas & Status Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mengubah card “Aktifitas & Status” menjadi ringkasan status lokal dan catatan aksi kontrol yang persisten.

**Architecture:** Entri log berupa `ActivityLogEntry` disimpan `AppPrefs` lewat codec teks ringan yang murni JVM (mudah diuji). `ControlRepository` menulis entri di titik aksi, sehingga tombol massal maupun sheet per kamera tercatat tanpa mengubah sheet. `MainActivity` merender ringkasan status dan 4 entri terbaru ke ID layout yang sudah ada.

**Tech Stack:** Kotlin, Android SDK, EncryptedSharedPreferences, JUnit 4, Gradle.

**Spec:** `docs/superpowers/specs/2026-11-23-activity-log-design.md`

## Global Constraints
- `CAMERAS_PER_PAGE = 4`, `GRID_COLUMNS = 2`, `versionCode`/`versionName` naik ke `5`/`1.1.3` di task terakhir.
- Maksimal 30 entri tersimpan; tampilkan maksimal 4 entri terbaru.
- Jangan menyimpan kredensial, kode verifikasi, atau kredensial cloud di log.
- Jangan mengubah rute kontrol, logika SDK/relay, atau perilaku pemutaran.
- ID layout `tvActivityInfo` dan `tvActivityLog` harus dipertahankan.
- Semua teks UI berbahasa Indonesia.

## Review Focus
- Baris log rusak/terpotong → `decode` harus mengabaikannya tanpa crash (diuji Task 1).
- Judul/detail mengandung `|`, newline, dan backslash → harus tetap utuh setelah round-trip (diuji Task 1).
- Lebih dari 30 entri → yang tersimpan harus 30 entri terbaru (diuji Task 1).
- Log harus tetap ada setelah aplikasi ditutup/diverifikasi ulang (diverifikasi di perangkat Task 4).
- Ringkasan status ketika tidak ada kamera mati dan tanpa backup → teks tidak boleh negatif/aneh (diuji Task 4 lewat `ActivitySummaryTest`).

---

### Task 1: Model dan codec log

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/data/ActivityLogEntry.kt`
- Create: `app/src/main/java/id/cctv/monitor/data/ActivityLogCodec.kt`
- Test: `app/src/test/java/id/cctv/monitor/data/ActivityLogCodecTest.kt`

**Interfaces:**
- Consumes: tidak ada.
- Produces:
  - `data class ActivityLogEntry(val at: Long, val title: String, val detail: String)`
  - `object ActivityLogCodec { fun encode(entries: List<ActivityLogEntry>): String; fun decode(raw: String?): List<ActivityLogEntry>; fun append(raw: String?, entry: ActivityLogEntry, max: Int = 30): String }`

- [ ] **Step 1: Tulis test yang gagal** di `ActivityLogCodecTest.kt`:
  - `roundTrip_menjagaKarakterKhusus`: encode+decode entri dengan `title = "MATIKAN|RTSP"`, `detail = "baris\nbaru\\akhir"`, `at = 1700000000123L`; samakan dengan entri awal.
  - `decode_mengabaikanBarisRusak`: input `"1700000000000|OK|halo\nbukan|log"` menghasilkan tepat satu entri `(1700000000000, "OK", "halo")`.
  - `append_menyimpanTigaPuluhEntriTerbaru`: encode 30 entri `at = 1L..30L`, lalu `append(..., ActivityLogEntry(31L, "BARU", "x"))`; hasil decode berisi 30 entri, pertama `at = 31L`, terakhir `at = 2L`.

- [ ] **Step 2: Jalankan `./gradlew.bat testDebugUnitTest --tests 'id.cctv.monitor.data.ActivityLogCodecTest' --console=plain`**
  Expected: FAIL karena `ActivityLogEntry`/`ActivityLogCodec` belum ada.

- [ ] **Step 3: Implementasi minimal**
  `ActivityLogEntry.kt` berisi data class. `ActivityLogCodec.kt` memakai format satu baris per entri `at|title|detail`; escape `\`, `|`, dan newline saat encode; decode memotong baris menjadi 3 bagian dengan memahami escape dan membuang baris yang tidak valid; `append` menggabungkan `decode(raw) + entry`, membalik urutan bila perlu agar terbaru di depan, lalu `take(max)` sebelum `encode`.

- [ ] **Step 4: Jalankan ulang test di Step 2**
  Expected: PASS, lalu jalankan `./gradlew.bat testDebugUnitTest --console=plain` dan pastikan 0 failure/error.

- [ ] **Step 5: Commit**
  `git add app/src/main/java/id/cctv/monitor/data/ActivityLogEntry.kt app/src/main/java/id/cctv/monitor/data/ActivityLogCodec.kt app/src/test/java/id/cctv/monitor/data/ActivityLogCodecTest.kt && git commit -m "Add persistent activity log codec"`

### Task 2: Simpan log di AppPrefs

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/data/AppPrefs.kt`

**Interfaces:**
- Consumes: `ActivityLogCodec`, `ActivityLogEntry` dari Task 1.
- Produces:
  - `fun activityLog(): List<ActivityLogEntry>`
  - `fun addActivity(title: String, detail: String)`

- [ ] **Step 1: Tambahkan konstanta kunci** `KEY_ACTIVITY_LOG = "activity_log"` di `companion object` `AppPrefs`.

- [ ] **Step 2: Implementasi `activityLog()`**
  Baca `prefs.getString(KEY_ACTIVITY_LOG, null)`, kembalikan `ActivityLogCodec.decode(raw)`.

- [ ] **Step 3: Implementasi `addActivity(title, detail)`**
  Tulis `ActivityLogCodec.append(prefs.getString(KEY_ACTIVITY_LOG, null), ActivityLogEntry(System.currentTimeMillis(), title.trim(), detail.trim()))` ke `KEY_ACTIVITY_LOG` dengan `edit().putString(...).apply()`.

- [ ] **Step 4: Verifikasi** `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`
  Expected: BUILD SUCCESSFUL, 0 failure/error.

- [ ] **Step 5: Commit**
  `git add app/src/main/java/id/cctv/monitor/data/AppPrefs.kt && git commit -m "Persist activity log in AppPrefs"`

### Task 3: Catat aksi dari ControlRepository

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/control/ControlRepository.kt`

**Interfaces:**
- Consumes: `AppPrefs.addActivity(title, detail)` dari Task 2.
- Produces: tidak ada API baru; efek samping berupa entri log.

- [ ] **Step 1: Tambah helper privat** `private fun logSingle(cam: Camera, action: String, ok: Boolean)` yang memanggil
  `prefs.addActivity(cam.displayName, "$action " + if (ok) "berhasil" else "gagal")`.

- [ ] **Step 2: Pasang logging di akhir method per kamera** dengan `logSingle`:
  `setBlack`, `setStream`, `setRtsp`, `setNvr`, `setDayNight`, `setSupplementLight`, `setMovementDetect`.
  Gunakan aksi: `Privacy mask`, `Stream`, `RTSP`, `Kanal NVR`, `Mode siang/malam`, `Lampu tambahan`, `Deteksi gerak`.
  Untuk method yang melempar exception saat gagal, panggil `logSingle(..., ok = true)` tepat sebelum `return`, dan bungkus pemanggilan dengan `runCatching` bila perlu mencatat kegagalan tanpa mengubah hasil method.

- [ ] **Step 3: Pasang logging di method bulk/restore**
  - `setAllCamerasOff` → `prefs.addActivity("MATIKAN SEMUA", "${targets.size} kamera • ${report.errors.size} gagal")`
  - `setAllCamerasOn` → judul `NYALAKAN SEMUA`
  - `setAllRtspOff` → judul `MATIKAN SEMUA RTSP`
  - `setAllBlack` → judul `HITAMKAN SEMUA` / `PULIHKAN PRIVACY MASK`
  - `restoreAll` → judul `PULIHKAN SEMUA`, detail `${report.errors.size} masalah`
  Detail memakai jumlah kamera dan jumlah error, tanpa nama file backup atau kredensial.

- [ ] **Step 4: Verifikasi** `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`
  Expected: BUILD SUCCESSFUL, 0 failure/error.

- [ ] **Step 5: Commit**
  `git add app/src/main/java/id/cctv/monitor/control/ControlRepository.kt && git commit -m "Record control actions in activity log"`

### Task 4: Tampilkan log dan status di beranda

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Create: `app/src/main/java/id/cctv/monitor/data/ActivitySummary.kt`
- Test: `app/src/test/java/id/cctv/monitor/data/ActivitySummaryTest.kt`
- Modify: `app/src/main/java/id/cctv/monitor/MainActivity.kt`

**Interfaces:**
- Consumes: `AppPrefs.activityLog()`, `AppPrefs.flags(id)`, `BackupStore.count()`.
- Produces: `object ActivitySummary { fun format(total: Int, off: Int, backups: Int): String }`

- [ ] **Step 1: Tulis test `ActivitySummaryTest.kt` yang gagal**
  - `tanpaStatusMati`: `format(8, 0, 0)` → `"8 kamera aktif • tidak ada status mati"`.
  - `denganStatusMatiDanBackup`: `format(8, 2, 1)` → `"8 kamera aktif • 2 dalam status mati • 1 backup belum dipulihkan"`.
  Jalankan `./gradlew.bat testDebugUnitTest --tests 'id.cctv.monitor.data.ActivitySummaryTest' --console=plain`, pastikan FAIL karena class belum ada, lalu implementasi minimal dan pastikan PASS.

- [ ] **Step 2: Ubah label** di `activity_main.xml` dari `Riwayat Akun` menjadi `Riwayat Aktivitas`. ID tidak diubah.

- [ ] **Step 3: Tambah view field** di `MainActivity`: `private lateinit var tvActivityInfo: TextView`, `private lateinit var tvActivityLog: TextView`; temukan keduanya di `onCreate` setelah `tvTotalCameras`.

- [ ] **Step 4: Tambah `private fun renderActivityLog()`**
  Hitung `total = camerasById.values.count { it.enabled }`, `off` = jumlah kamera dengan `blocked || rtspOff || nvrOff || streamOff`, `backups = store.count()`. Set `tvActivityInfo.text = ActivitySummary.format(total, off, backups)`. Ambil `prefs.activityLog().take(4)`; bila kosong set `"Belum ada aktivitas tercatat."`, jika tidak tiap entri menjadi `"HH:mm  title — detail"` dengan `SimpleDateFormat("HH:mm", Locale.getDefault())`.

- [ ] **Step 5: Pasang pemanggilan `renderActivityLog()`**
  Di akhir `onCreate`, di `onResume()` (tambahkan override bila belum ada), setelah blok aksi massal `startTurnOffAll`, `startTurnOnAll`, `startTurnOffAllRtsp`, setelah `startRestoreAll`, dan di akhir `onCameraChanged` serta listener `CameraControlSheet`.

- [ ] **Step 6: Verifikasi perangkat**
  Jalankan `./gradlew.bat testDebugUnitTest assembleDebug --console=plain`, pasang APK, buka beranda, pastikan card menampilkan ringkasan dan entri. Lakukan `MATIKAN SEMUA RTSP`, `NYALAKAN SEMUA`, lalu force-stop dan buka ulang aplikasi; entri harus tetap tampil. Pastikan `FATAL_COUNT=0` dan grid masih menampilkan `8 kamera Hikvision di jaringan rumah`.

- [ ] **Step 7: Commit**
  `git add app/src/main/res/layout/activity_main.xml app/src/main/java/id/cctv/monitor/data/ActivitySummary.kt app/src/test/java/id/cctv/monitor/data/ActivitySummaryTest.kt app/src/main/java/id/cctv/monitor/MainActivity.kt && git commit -m "Show live activity log on home card"`

### Task 5: Bump versi, push, dan rilis

**Files:**
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Ubah** `versionCode = 4` → `5`, `versionName = "1.1.2"` → `"1.1.3"`.

- [ ] **Step 2: Verifikasi penuh**
  `./gradlew.bat testDebugUnitTest assembleRelease --console=plain`; pastikan BUILD SUCCESSFUL, 0 failure/error; salin APK ke `dist/cctv-monitor-1.1.3-release.apk` dan catat SHA-256.

- [ ] **Step 3: Commit dan push**
  `git add app/build.gradle.kts && git commit -m "Release 1.1.3"` lalu `git push origin main`; verifikasi `origin/main` berubah.

- [ ] **Step 4: Buat GitHub Release `v1.1.3`** dengan APK tersebut, lalu unduh ulang lewat API dan cocokkan SHA-256.

- [ ] **Step 5: Lapor** commit, URL release, SHA-256, dan hasil verifikasi.
