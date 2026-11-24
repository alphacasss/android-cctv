# RAM Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turunkan RAM aplikasi di semua device lewat penghapusan alokasi mati, sampling bitmap, decoder thread cap, dan lazy grid inflation.

**Architecture:** Fungsi murni `BitmapSampler` dipakai oleh jalur klasifikasi dan notifikasi. `VlcLaunchOptions` memusatkan argumen native VLC. `MainActivity` berpindah dari pre-created page views ke binding Adapter. Tidak ada fitur baru.

**Tech Stack:** Kotlin, Android SDK, JUnit4, LibVLC 3.7.7, RecyclerView/ViewPager2.

**Spec:** `docs/superpowers/specs/2026-11-24-ram-optimization-design.md`

## Global Constraints

- minSdk 24, targetSdk 34.
- `assembleDebug testDebugUnitTest` harus GREEN setelah tiap task.
- Tidak ada Robolectric; hanya logika murni yang di-unit-test.
- Jangan mengubah perilaku kamera, cloud, update, atau izin.
- Jangan commit file ignored (`local.properties`, `keystore.properties`, build output).

## Review Focus

1. 8 kamera / 2 halaman → hanya halaman yang dibutuhkan yang boleh punya tile `SurfaceView`; diuji lewat struktur Adapter + verifikasi manual.
2. `objectFilter` aktif → tidak boleh ada model 4,6 MB atau bitmap >160 px; diuji lewat konstanta + `BitmapSamplerTest`.
3. Snapshot gerak besar → notifikasi tidak boleh decode >256 px; diuji lewat `BitmapSamplerTest` + verifikasi kode.
4. 4 stream RTSP bersamaan → decoder thread dibatasi 2; diuji lewat `VlcLaunchOptionsTest`.
5. Ganti halaman saat config berubah → tile lama tidak boleh tertinggal/tergompat; diuji lewat verifikasi kode + build.

---

### Task 1: Bitmap sampler + ObjectFilter tanpa model mati

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/util/BitmapSampler.kt`
- Create: `app/src/test/java/id/cctv/monitor/util/BitmapSamplerTest.kt`
- Modify: `app/src/main/java/id/cctv/monitor/motion/ObjectFilter.kt`
- Modify: `app/src/main/java/id/cctv/monitor/SettingsActivity.kt` (baris teks deskripsi objek)
- Modify: `app/build.gradle.kts`
- Delete: `app/src/main/assets/efficientdet_lite0.tflite`

**Interfaces:**
- Consumes: tidak ada.
- Produces: `object BitmapSampler { fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, maxDimension: Int): Int }`
- Produces: `ObjectFilter` tetap punya `isReady(): Boolean` dan `detect(jpeg: ByteArray): Result`.

- [ ] **Step 1: Tulis test gagal untuk sampler**

`BitmapSamplerTest.kt`:
```kotlin
assertEquals(16, BitmapSampler.sampleSizeFor(1920, 1080, 160))
assertEquals(4, BitmapSampler.sampleSizeFor(640, 480, 160))
assertEquals(1, BitmapSampler.sampleSizeFor(100, 200, 160))
assertEquals(1, BitmapSampler.sampleSizeFor(0, 0, 160))
```

- [ ] **Step 2: Jalankan test, pastikan gagal karena `BitmapSampler` belum ada**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.util.BitmapSamplerTest" --console=plain`
Expected: FAIL, unresolved reference `BitmapSampler`.

- [ ] **Step 3: Implementasi `BitmapSampler.sampleSizeFor`**

Loop power-of-two: `sample` mulai 1, naik selama `largest / (sample * 2) >= maxDimension`; kembalikan 1 untuk input non-positif.

- [ ] **Step 4: Jalankan test, pastikan GREEN**

Run: perintah Step 2.
Expected: PASS.

- [ ] **Step 5: Ubah `ObjectFilter`**

Hapus import/field TFLite, `MODEL_ASSET`, `detector()`, `loadError`, `loadTried`, `lock`. `isReady()` kembalikan `true`. `decode()` pakai `BitmapSampler.sampleSizeFor(..., 160)` dan `Bitmap.Config.RGB_565`. `detect()` tetap menghasilkan label `"ORANG"` dan `raw = "fallback pixel (avg=...)"`, tetapi alokasi `IntArray` mengikuti ukuran bitmap hasil decode (≤160 px).

- [ ] **Step 6: Hapus dependensi + aset + sesuaikan teks**

Hapus baris `implementation("org.tensorflow:tensorflow-lite-task-vision:0.4.4")`. Hapus aset `efficientdet_lite0.tflite`. Ubah teks Setelan dari "Model AI berjalan di ponsel..." menjadi "Filter objek berjalan di ponsel...".

- [ ] **Step 7: Verifikasi + commit**

Run: `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL.
Commit: `refactor: trim object filter allocations`.

---

### Task 2: Batasi thread decoder VLC

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/player/VlcLaunchOptions.kt`
- Create: `app/src/test/java/id/cctv/monitor/player/VlcLaunchOptionsTest.kt`
- Modify: `app/src/main/java/id/cctv/monitor/player/SharedVlc.kt`

**Interfaces:**
- Consumes: tidak ada.
- Produces: `internal object VlcLaunchOptions { const val DECODER_THREADS: Int; fun base(): ArrayList<String> }`.

- [ ] **Step 1: Tulis test gagal**

`VlcLaunchOptionsTest.kt`:
```kotlin
assertEquals(2, VlcLaunchOptions.DECODER_THREADS)
assertTrue(VlcLaunchOptions.base().contains("--no-audio"))
assertTrue(VlcLaunchOptions.base().contains("--avcodec-threads=2"))
assertTrue(VlcLaunchOptions.base().contains("--drop-late-frames"))
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.player.VlcLaunchOptionsTest" --console=plain`
Expected: FAIL, unresolved reference `VlcLaunchOptions`.

- [ ] **Step 3: Implementasi `VlcLaunchOptions.base()`**

Kembalikan argumen lama `SharedVlc` plus `--avcodec-threads=2`.

- [ ] **Step 4: Pakai dari `SharedVlc`**

Ganti `arrayListOf(...)` di `LibVLC(...)` dengan `VlcLaunchOptions.base()`.

- [ ] **Step 5: Verifikasi + commit**

Run test Step 2 + `.\gradlew.bat assembleDebug --console=plain`.
Expected: PASS / BUILD SUCCESSFUL.
Commit: `perf: cap VLC decoder threads`.

---

### Task 3: Lazy grid page binding

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/MainActivity.kt`

**Interfaces:**
- Consumes: `tiles: HashMap<Int, Tile>`, `cameraPages: List<List<Camera>>`, `createPageView(...)` yang sudah ada.
- Produces: Adapter yang menyusun tile saat `onBindViewHolder`, bukan di `buildCameraPages`.

- [ ] **Step 1: Hapus pre-creation page view**

Di `buildCameraPages`, hapus `val pageViews = pages.map { ... }`. Adapter memakai `cameraPages[position]` di `onBindViewHolder`.

- [ ] **Step 2: Bind page di Adapter**

`onCreateViewHolder` inflate `R.layout.page_camera_grid` dan kembalikan `PageHolder`. `onBindViewHolder` memanggil `bindPage(container, cameraPages[position])`.

- [ ] **Step 3: Implementasi `bindPage(container: ViewGroup, pageCameras: List<Camera>)**

Pastikan `container` adalah `GridLayout`, set `columnCount = GRID_COLUMNS` dan `rowCount` sesuai jumlah baris, `removeAllViews()`, lalu untuk tiap kamera: reuse `tiles[id]` bila ada, jika tidak inflate `item_camera`, isi `Tile`, daftarkan click, lalu `addView`. Simpan di `tiles`.

- [ ] **Step 4: Bersihkan tile mati saat rebuild**

Di awal `buildCameraPages`, untuk setiap `tiles.keys` yang tidak ada di `cameras`, panggil `playerManager?.detach(id)`, hapus dari `tiles`, dan hapus state terkait.

- [ ] **Step 5: Verifikasi + commit**

Run: `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL.
Commit: `perf: bind camera pages lazily`.

---

### Task 4: Sampling bitmap notifikasi + classify width

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/away/AwayService.kt`
- Modify: `app/src/main/java/id/cctv/monitor/motion/MotionMonitor.kt`

**Interfaces:**
- Consumes: `BitmapSampler.sampleSizeFor` dari Task 1.

- [ ] **Step 1: Turunkan lebar classify**

Ubah `MotionMonitor.CLASSIFY_WIDTH` dari `640` menjadi `320`.

- [ ] **Step 2: Sampling bitmap notifikasi**

Di `AwayService.onSnapshot`, sebelum `BitmapFactory.decodeByteArray`, baca bounds dengan `inJustDecodeBounds = true`, hitung `sample = BitmapSampler.sampleSizeFor(bounds.outWidth, bounds.outHeight, 256)`, lalu decode dengan `inSampleSize = sample` dan `Bitmap.Config.RGB_565`. Jangan recycle bitmap sebelum notifikasi selesai dirender.

- [ ] **Step 3: Verifikasi + commit**

Run: `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL.
Commit: `perf: sample away notification bitmaps`.

---

### Task 5: Verifikasi akhir

- [ ] **Step 1:** Run `.\gradlew.bat assembleDebug testDebugUnitTest --console=plain` — expected GREEN.
- [ ] **Step 2:** Jika device tersedia, pasang APK dan bandingkan `adb shell dumpsys meminfo id.cctv.monitor` sebelum/sesudah dengan 4 kamera aktif.
- [ ] **Step 3:** Review diff `main..HEAD` oleh reviewer segar.
- [ ] **Step 4:** Laporkan hasil; integrasi menyusul sesuai pilihan user.
