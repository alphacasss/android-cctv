# In-app auto-update Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Beri aplikasi cara mengecek rilis GitHub, mengunduh APK dengan aman, dan mengajak pemasangan.

**Architecture:** Parser murni untuk JSON GitHub dipisah dari jaringan. `UpdateManager` mengatur jadwal cek dan memilih jalur manual/otomatis. `UpdateWorker` mengunduh lewat WorkManager dengan batasan Wi-Fi pada jalur otomatis, memverifikasi SHA-256, lalu menyiapkan notifikasi. `UpdateInstallActivity` meminta izin pemasangan dan meluncurkan installer.

**Tech Stack:** Kotlin, `HttpURLConnection`, `org.json`, WorkManager 2.9.1, NotificationCompat, FileProvider, JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-24-in-app-updates-design.md`

## Global Constraints
- minSdk 24, targetSdk 34, `versionName = "1.1.4"`.
- Repository: `alphacasss/android-cctv`. Endpoint: `https://api.github.com/repos/alphacasss/android-cctv/releases/latest`.
- Hanya aset nama berakhir `-release.apk`.
- Perbandingan versi: `v1.1.4` → `10104`.
- Pesan UI Bahasa Indonesia, gaya sama dengan Setelan yang ada.
- Jangan request izin notifikasi baru; MainActivity sudah melakukannya.

## Review Focus
- Rilis tanpa aset APK → `parseLatest` mengembalikan null, UI bilang tidak ada APK. Test di Task 1.
- `versionName` malformed → `versionCode` mengembalikan 0, tidak dianggap lebih baru. Test di Task 1.
- `digest` bukan `sha256:` → `sha256` null, unduh tetap dipped with flag unverified. Test di Task 1.
- Unduh gagal/parsial → file dihapus, WorkManager retry, tidak ada notifikasi "siap". Test di Task 3 via inspeksi kode + uji manual.
- Tidak ada izin install → `UpdateInstallActivity` membuka `ACTION_MANAGE_UNKNOWN_APP_SOURCES`. Test di Task 4 via uji manual.

---

### Task 1: Parser rilis dan versi (murni, unit-testable)

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/update/ReleaseInfo.kt`
- Test: `app/src/test/java/id/cctv/monitor/update/ReleaseInfoTest.kt`

**Interfaces:**
- Produces: `data class ReleaseInfo(val version: String, val notes: String, val apkUrl: String, val apkName: String, val sizeBytes: Long, val sha256: String?)`; `object ReleaseParser { fun parseLatest(json: String): ReleaseInfo?; fun versionCode(version: String): Long; fun isNewer(remote: String, current: String): Boolean }`

- [ ] **Step 1: Write failing tests**

Test names and assertions:
- `parseLatest_picks_release_apk_and_sha256`: JSON dengan dua aset (`.txt` dan `cctv-monitor-1.1.5-release.apk`, `digest: "sha256:abc..."`) → `version == "1.1.5"`, `apkName` sesuai, `sha256 == "abc..."`.
- `parseLatest_returns_null_when_no_apk_asset`: JSON hanya punya aset `.txt` → null.
- `parseLatest_ignores_draft_and_prerelease`: JSON dengan `draft: true` lalu `prerelease: true` → null.
- `versionCode_parses_semver`: `"1.1.4"` → `10104L`; `"2.0.0"` → `20000L`; `"v1.2"` → `0L`.
- `isNewer_compares_numeric_version`: `("1.1.5", "1.1.4")` true; `("1.1.4", "1.1.4")` false; `("bogus", "1.1.4")` false.

- [ ] **Step 2: Run test to verify it fails**
Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.update.ReleaseInfoTest" --console=plain`
Expected: FAIL (class belum ada).

- [ ] **Step 3: Implement `ReleaseInfo.kt`**
`parseLatest` memakai `JSONObject`/`JSONArray`, mengabaikan `draft`/`prerelease`, memilih aset pertama dengan nama `endsWith("-release.apk")`. `sha256` diambil dari `digest` bila diawali `sha256:`,否则 null. `versionCode` regex `^(\d+)\.(\d+)\.(\d+)$`, kembalikan 0 bila tidak cocok. `isNewer` membandingkan `versionCode` dan mengembalikan false bila salah satu 0.

- [ ] **Step 4: Run tests to verify they pass**
Run per Step 2. Expected: PASS.

- [ ] **Step 5: Commit**
`git add app/src/main/java/id/cctv/monitor/update/ReleaseInfo.kt app/src/test/java/id/cctv/monitor/update/ReleaseInfoTest.kt && git commit -m "feat: parse GitHub release metadata"`

---

### Task 2: Repository jaringan +unduh + sha256

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/update/UpdateRepository.kt`

**Interfaces:**
- Consumes: `ReleaseParser`, `ReleaseInfo` (Task 1).
- Produces: `object UpdateRepository { suspend fun latest(): ReleaseInfo?; fun download(url: String, dest: File, onProgress: (percent: Int) -> Unit): File; fun sha256(file: File): String }`

- [ ] **Step 1: Write the code**
`latest()` memakai `HttpURLConnection` GET endpoint Global Constraints dengan header `Accept: application/vnd.github+json` dan `User-Agent: cctv-monitor`, timeout 10 s, baca body, lempar ke `ReleaseParser.parseLatest`; kembalikan null pada exception. `download()` menulis ke `dest` (hapus dulu bila ada), buffer 64 KB, hitung progres dari `Content-Length` atau 0 bila tidak ada, lempar `IOException` bila panjang berbeda dari `Content-Length`. `sha256()` memakai `MessageDigest.getInstance("SHA-256")` streaming 64 KB.

- [ ] **Step 2: Verify compile**
Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
`git add app/src/main/java/id/cctv/monitor/update/UpdateRepository.kt && git commit -m "feat: download and verify release APK"`

---

### Task 3: WorkManager download + notifikasi

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/update/UpdateNotifications.kt`
- Create: `app/src/main/java/id/cctv/monitor/update/UpdateWorker.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/xml/file_paths.xml`

**Interfaces:**
- Consumes: `UpdateRepository`, `ReleaseInfo` (Tasks 1–2).
- Produces: `UpdateNotifications.ensureChannel(context)`, `UpdateNotifications.progress(context, percent)`, `UpdateNotifications.ready(context, file: File)`; `UpdateWorker` with input keys `url`, `version`, `sha256`, `manual`; `UpdateManager.WORK_NAME`.

- [ ] **Step 1: Write the code**
`UpdateNotifications`: channel id `cctv_update` (IMPORTANCE_LOW), `progress()` memperbarui notification id 9100 dengan `NotificationCompat.Builder.setProgress(100, percent, false)` dan `Ongoing`; `ready()` memakai id 9101, `AutoCancel`, `contentIntent` ke `UpdateInstallActivity` lewat `PendingIntent.getActivity` dengan `FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE`, extra `path`.
`UpdateWorker.doWork()`: baca input, `setForegroundAsync` sekaliawal dengan `progress(0)`, panggil `UpdateRepository.download` ke `File(context.getExternalFilesDir("Updates"), "cctv-monitor-$version.apk")`. Bila `sha256` tidak null dan tidak cocok → hapus file, kembalikan `Result.failure()`. Sukses → `UpdateNotifications.ready(...)`, kembalikan `Result.success()`. Gagal → hapus file, kembalikan `Result.retry()`.
Manifest: tambah `REQUEST_INSTALL_PACKAGES` dan activity `.update.UpdateInstallActivity` (`exported=false`). `file_paths.xml`: tambah `<external-files-path name="updates" path="Updates/" />`.

- [ ] **Step 2: Verify compile**
Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
`git add app/src/main/java/id/cctv/monitor/update app/src/main/AndroidManifest.xml app/src/main/res/xml/file_paths.xml && git commit -m "feat: download updates with WorkManager"`

---

### Task 4: Install flow

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/update/UpdateInstallActivity.kt`

**Interfaces:**
- Consumes: `androidx.core.content.FileProvider`, extra `path` dari Task 3.
- Produces: activity yang menerima `path` dan meluncurkan `ACTION_INSTALL_PACKAGE`.

- [ ] **Step 1: Write the code**
`onCreate`: baca `path` extra; bila kosong/tidak ada → `finish()`. Bila `Build.VERSION.SDK_INT >= O` dan `!packageManager.canRequestPackageInstalls()` → `startActivity(Intent(ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))` lalu `finish()`. Jika tidak → `FileProvider.getUriForFile(this, "$packageName.files", file)`, `Intent(ACTION_INSTALL_PACKAGE).setDataAndType(uri, "application/vnd.android.package-archive").addFlag(FLAG_GRANT_READ_URI_PERMISSION).putExtra(EXTRA_NOT_UNKNOWN_SOURCE, true)`, `startActivity(intent)`, `finish()`. Bungkus dengan `try/catch` dan `Toast` bila gagal.

- [ ] **Step 2: Verify compile**
Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
`git add app/src/main/java/id/cctv/monitor/update/UpdateInstallActivity.kt && git commit -m "feat: prompt APK installation"`

---

### Task 5: Penyimpanan setelan + UpdateManager

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/data/AppPrefs.kt`
- Create: `app/src/main/java/id/cctv/monitor/update/UpdateManager.kt`

**Interfaces:**
- Consumes: `UpdateRepository`, `ReleaseInfo`, `UpdateWorker` (Tasks 1–3), `AppPrefs`.
- Produces: `var AppPrefs.autoUpdate: Boolean`, `var AppPrefs.lastUpdateCheckAt: Long`; `object UpdateManager { const val WORK_NAME = "cctv_update"; suspend fun check(context: Context, manual: Boolean): CheckResult; fun maybeCheckOnLaunch(context: Context) }` dengan `sealed class CheckResult { object UpToDate; data class Available(val release: ReleaseInfo); object Failed }`.

- [ ] **Step 1: Write the code**
`AppPrefs`: tambah key `auto_update` (default true), `last_update_check_at` (default 0), property getter/setter mengikuti pola `autoRestore`.
`UpdateManager.check`: panggil `UpdateRepository.latest()`; baca `lastUpdateCheckAt` dan tulis `System.currentTimeMillis()`; bila null → `Failed`; bila `!isNewer(release.version, BuildConfig.VERSION_NAME)` → `UpToDate`;否则 `Available`.
`maybeCheckOnLaunch`: bila `!autoUpdate` atau selisih waktu < 24 jam → return; else `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { check(context, false) }`; bila `Available` enqueue `OneTimeWorkRequestBuilder<UpdateWorker>()` dengan input `url/version/sha256/manual=false` dan `Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()`, `ExistingWorkPolicy.KEEP`, nama `WORK_NAME`. Manual dipanggil UI dengan `manual=true`, tanpa batasan jaringan.

- [ ] **Step 2: Verify compile**
Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**
`git add app/src/main/java/id/cctv/monitor/data/AppPrefs.kt app/src/main/java/id/cctv/monitor/update/UpdateManager.kt && git commit -m "feat: schedule update checks"`

---

### Task 6: UI Setelan + hook startup

**Files:**
- Modify: `app/src/main/res/layout/activity_settings.xml`
- Modify: `app/src/main/java/id/cctv/monitor/SettingsActivity.kt`
- Modify: `app/src/main/java/id/cctv/monitor/MainActivity.kt`

**Interfaces:**
- Consumes: `UpdateManager.CheckResult`, `UpdateManager.check`, `AppPrefs.autoUpdate`, `lastUpdateCheckAt` (Task 5).

- [ ] **Step 1: Write the code**
Layout: di dalam kartu sebelum TextView versi di bawah, tambah TextView judul "Pembaruan Aplikasi", `MaterialSwitch` id `switchAutoUpdate`, TextView id `tvUpdateStatus`, `MaterialButton` id `btnCheckUpdate` (OutlinedButton, teks "Cek pembaruan sekarang").
`SettingsActivity`: inisialisasi switch dari prefs, simpan perubahan; `btnCheckUpdate` → `lifecycleScope.launch(Dispatchers.IO)` panggil `UpdateManager.check(this, true)`, `withContext(Dispatchers.Main)` tampilkan dialog `MaterialAlertDialogBuilder`: `UpToDate` → "Versi Anda sudah terbaru", `Available` → versi + `release.notes` + tombol "Unduh & pasang" yang enqueue worker manual, `Failed` → "Gagal mengecek pembaruan". Perbarui `tvUpdateStatus` dengan versi terpasang dan waktu cek terakhir.
`MainActivity.onCreate`: panggil `UpdateManager.maybeCheckOnLaunch(this)` setelah inisialisasi prefs.

- [ ] **Step 2: Verify build + tests**
Run: `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL, semua test lulus.

- [ ] **Step 3: Commit**
`git add app/src/main/res/layout/activity_settings.xml app/src/main/java/id/cctv/monitor/SettingsActivity.kt app/src/main/java/id/cctv/monitor/MainActivity.kt && git commit -m "feat: add update settings UI"`

---

### Task 7: Verifikasi perangkat

- [ ] **Step 1:** Build release-signed APK lokal, pasang di HP `R9RYB03HG7E`, buka Setelan → "Cek pembaruan sekarang".
- [ ] **Step 2:** Konfirmasi dialog menampilkan versi terbaru (`1.1.4`) atau "sudah terbaru" bila sama.
- [ ] **Step 3:** Konfirmasi tombol "Cek pembaruan" tidak membekukan UI dan tidak mengunduh saat versi sama.
- [ ] **Step 4:** Commit kecil bila ada perbaikan dari verifikasi; jangan commit perangkat/test artifacts.
