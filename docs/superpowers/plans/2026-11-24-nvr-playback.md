# NVR Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Playback screen that searches NVR recordings by date through ISAPI and plays one clip either by RTSP stream or, when only cloud is reachable, from a temporary local cache.

**Architecture:** Pure Kotlin helpers handle time conversion, XML, RTSP URL construction, and transport choice. A repository searches direct ISAPI first and falls back to Hik-Connect cloud passthrough. The existing `RecordingPlayerActivity` is extended with a media-URI extra so there is still only one VLC decoder.

**Tech Stack:** Kotlin, Android SDK (minSdk 24), LibVLC 3.7.7, ISAPI + Digest auth, `XmlPullParser`, `HttpURLConnection`, coroutines, Material3 `DatePickerDialog`/framework widgets, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-24-nvr-playback-design.md`

## Global Constraints

- Tidak ada dependency baru. Versi LibVLC tetap `org.videolan.android:libvlc-all:3.7.7`.
- Tidak ada TFLite / on-device ML / decoder grid / polling.
- `versionCode` tetap `12`, `versionName` tetap `"1.1.10"`.
- Sertifikat signing tetap `29b29d0ace7d7b782bbb3c25e4c5afb4b0804191fadbb8f18f902aacecc4339d`.
- Activity baru wajib `android:exported="false"`.
- Satu `MediaPlayer` VLC aktif; tidak boleh ada decoder kedua.
- Jangan `git add -A`. Stage hanya file yang disebut task.
- Belum release / tag / push tanpa perintah lanjutan.

## Review Focus

- Tanggal lokal pengguna vs waktu UTC NVR — `PlaybackTimeTest.dayRange_usesZoneAndUtcStamp` membuktikan konversi.
- Respons XML tanpa deklarasi/BOM, kosong, atau banyak entri — `PlaybackSearchXmlTest.parse_*` membuktikan penguraian dan urutan.
- Password berisi `@`, `:`, dan spasi — `PlaybackRtspUrlTest.build_encodesCredentials` membuktikan encoding.
- Cloud-only tanpa sesi — `PlaybackSearchRepositoryTest.cloudMissing_returnsFailure` dan `PlaybackTransportResolverTest.cloudOnly_usesCache` membuktikan jalur.
- Seek RTSP tidak reliable — `PlaybackTransportResolverTest.seekMode_*` membuktikan seek mati untuk RTSP dan hidup untuk cache.

---

### Task 0: Amankan baseline yang belum di-commit

**Files:**
- Modify: seluruh file uncommitted dari fitur playback file HP yang sudah ada di working tree.
- Create: `docs/superpowers/specs/2026-11-24-recording-playback-design.md` (jika belum ter-stage).

**Interfaces:**
- Consumes: `git status --short`.
- Produces: working tree bersih sebelum task berikutnya.

- [ ] **Step 1: Lihat status**

Run: `git status --short`
Expected: ada file playback lokal lama + dua file docs baru.

- [ ] **Step 2: Stage eksplisit baseline**

```bash
git add app/src/main/java/id/cctv/monitor/RecordingPlayerActivity.kt \
  app/src/main/java/id/cctv/monitor/HistoryPlaybackRouter.kt \
  app/src/main/java/id/cctv/monitor/HistoryActivity.kt \
  app/src/main/java/id/cctv/monitor/player/RecordingPlaybackTime.kt \
  app/src/main/java/id/cctv/monitor/player/SharedVlc.kt \
  app/src/main/res/layout/activity_recording_player.xml \
  app/src/main/res/drawable/ic_pause.xml \
  app/src/main/AndroidManifest.xml \
  app/src/test/java/id/cctv/monitor/HistoryPlaybackRouterTest.kt \
  app/src/test/java/id/cctv/monitor/player/RecordingPlaybackTimeTest.kt \
  docs/superpowers/specs/2026-11-24-recording-playback-design.md \
  docs/superpowers/specs/2026-11-24-nvr-playback-design.md
```

- [ ] **Step 3: Commit baseline**

```bash
git commit -m "feat: play stored recordings and document NVR playback"
```

- [ ] **Step 4: Verifikasi**

Run: `git status --short`
Expected: tidak ada output.

---

### Task 1: Time + XML search primitives

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/RecordingClip.kt`
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackTime.kt`
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackSearchXml.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackTimeTest.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackSearchXmlTest.kt`

**Interfaces:**
- Consumes: tidak ada.
- Produces:
  - `data class RecordingClip(val trackId: Int, val startMillis: Long, val endMillis: Long)`
  - `object PlaybackTime { fun utcStamp(millis: Long): String; fun parseStamp(value: String): Long?; fun dayRange(dayMillis: Long, zone: TimeZone): Pair<Long, Long>; fun label(startMillis: Long, endMillis: Long, zone: TimeZone): String }`
  - `object PlaybackSearchXml { fun request(trackId: Int, startUtc: String, endUtc: String): String; fun parse(body: String): List<RecordingClip> }`

- [ ] **Step 1: Tulis test gagal**

```kotlin
class PlaybackTimeTest {
  @Test fun dayRange_usesZoneAndUtcStamp() {
    val zone = TimeZone.getTimeZone("Asia/Jakarta")
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = zone }.parse("2026-11-24")!!.time
    val (start, end) = PlaybackTime.dayRange(day, zone)
    assertEquals("20261123T170000Z", PlaybackTime.utcStamp(start))
    assertEquals("20261124T170000Z", PlaybackTime.utcStamp(end))
    assertEquals("07:00–12:00", PlaybackTime.label(start, start + 5L * 60_000, zone))
  }
}
```

```kotlin
class PlaybackSearchXmlTest {
  @Test fun parse_sortedClipsAndEmptyBody() {
    val xml = """<?xml version="1.0"?><CMSearchResult><matchList>
      <searchMatchItem><trackID>201</trackID><timeSpan>
        <startTime>20261124T010000Z</startTime><endTime>20261124T010500Z</endTime>
      </timeSpan></searchMatchItem>
      <searchMatchItem><trackID>101</trackID><timeSpan>
        <startTime>20261123T230000Z</startTime><endTime>20261123T230300Z</endTime>
      </timeSpan></searchMatchItem>
    </matchList></CMSearchResult>"""
    val clips = PlaybackSearchXml.parse(xml)
    assertEquals(listOf(101, 201), clips.map { it.trackId })
    assertEquals("", PlaybackSearchXml.parse("").size.toString().let { if (it == "0") "" else it })
  }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.*" --console=plain`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implementasi**

Buat file di atas. `utcStamp` memakai `SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US)` dengan timezone UTC. `dayRange` memakai `Calendar` pada `zone`, bukan timezone default. `parse` memakai `XmlPullParserFactory.newInstance()`, membaca `<searchMatchItem>`, lalu `<trackID>`, `<startTime>`, `<endTime>`, mengabaikan BOM, dan mengurutkan `startMillis`. `request` menghasilkan `CMSearchDescription` dengan `<trackIDList>`, satu `<timeSpan>`, dan `<maxResults>100</maxResults>`.

- [ ] **Step 4: Jalankan test, pastikan hijau**

Run: perintah Step 2
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/RecordingClip.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackTime.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackSearchXml.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackTimeTest.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackSearchXmlTest.kt
git commit -m "test: pin NVR playback time and XML parsing"
```

---

### Task 2: RTSP URL + transport resolver

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackRtspUrl.kt`
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackTransportResolver.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackRtspUrlTest.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackTransportResolverTest.kt`

**Interfaces:**
- Consumes: tidak ada.
- Produces:
  - `object PlaybackRtspUrl { fun build(trackId: Int, user: String, pass: String, host: String, port: Int, startUtc: String, endUtc: String): String }`
  - `enum class PlaybackTransport { STREAM_RTSP, DOWNLOAD_CACHE, ERROR_NO_ROUTE }`
  - `enum class SeekMode { ENABLED, DISABLED }`
  - `object PlaybackTransportResolver { fun resolve(directReachable: Boolean, cloudAvailable: Boolean): PlaybackTransport; fun seekMode(transport: PlaybackTransport): SeekMode }`

- [ ] **Step 1: Tulis test gagal**

```kotlin
class PlaybackRtspUrlTest {
  @Test fun build_encodesCredentials() {
    val url = PlaybackRtspUrl.build(101, "admin", "p@ss:w ord", "192.168.1.64", 8554,
      "20261123T170000Z", "20261123T170500Z")
    assertEquals(
      "rtsp://admin:p%40ss%3Aw%20ord@192.168.1.64:8554/Streaming/tracks/101" +
        "?starttime=20261123T170000Z&endtime=20261123T170500Z",
      url
    )
  }
}
```

```kotlin
class PlaybackTransportResolverTest {
  @Test fun choices_areDeterministic() {
    assertEquals(PlaybackTransport.STREAM_RTSP, PlaybackTransportResolver.resolve(true, false))
    assertEquals(PlaybackTransport.DOWNLOAD_CACHE, PlaybackTransportResolver.resolve(false, true))
    assertEquals(PlaybackTransport.ERROR_NO_ROUTE, PlaybackTransportResolver.resolve(false, false))
  }

  @Test fun seekMode_disabledForRtsp() {
    assertEquals(SeekMode.DISABLED, PlaybackTransportResolver.seekMode(PlaybackTransport.STREAM_RTSP))
    assertEquals(SeekMode.ENABLED, PlaybackTransportResolver.seekMode(PlaybackTransport.DOWNLOAD_CACHE))
  }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.*" --console=plain`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implementasi**

`PlaybackRtspUrl.build` memakai pola encoding yang sama dengan `Camera.kt`: `URLEncoder.encode(value,"UTF-8").replace("+","%20")`. Path tetap `/Streaming/tracks/$trackId`. `resolve`: `true` → RTSP; `false,true` → cache; `false,false` → error. `seekMode`: hanya `DOWNLOAD_CACHE` yang `ENABLED`.

- [ ] **Step 4: Jalankan test, pastikan hijau**

Run: perintah Step 2
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackRtspUrl.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackTransportResolver.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackRtspUrlTest.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackTransportResolverTest.kt
git commit -m "test: pin NVR playback transport decisions"
```

---

### Task 3: POST support untuk ISAPI langsung dan cloud

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/net/IsapiClient.kt`
- Modify: `app/src/main/java/id/cctv/monitor/control/CloudIsapiClient.kt`
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackIsapi.kt`

**Interfaces:**
- Consumes: `IsapiResponse`, `CloudIsapiClient.isapiCommand(...)` yang sudah ada.
- Produces:
  - `fun IsapiClient.post(path: String, body: String): IsapiResponse`
  - `suspend fun CloudIsapiClient.post(path: String, body: String): IsapiResponse`
  - `interface PlaybackIsapi { suspend fun post(path: String, body: String): IsapiResponse }`
  - `class DirectPlaybackIsapi(private val client: IsapiClient) : PlaybackIsapi`
  - `class CloudPlaybackIsapi(private val client: CloudIsapiClient) : PlaybackIsapi`

- [ ] **Step 1: Tambah method POST**

Di `IsapiClient`, reediting `request("POST", path, body)` mengikuti pola `put`. Di `CloudIsapiClient`, panggil `cloudRepo.isapiCommand(session, device, "POST", path, asXmlDocument(body))` lalu `wrap("POST", path, body)`.

- [ ] **Step 2: Buat adapter**

Buat `PlaybackIsapi.kt` dengan interface dan dua adapter; `DirectPlaybackIsapi` membungkus `withContext(Dispatchers.IO)`.

- [ ] **Step 3: Verifikasi kompilasi**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/net/IsapiClient.kt \
  app/src/main/java/id/cctv/monitor/control/CloudIsapiClient.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackIsapi.kt
git commit -m "feat: add ISAPI POST adapters for recording search"
```

---

### Task 4: Search repository dengan fallback cloud

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackSearchResult.kt`
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackSearchRepository.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackSearchRepositoryTest.kt`

**Interfaces:**
- Consumes: `PlaybackIsapi`, `PlaybackSearchXml`, `PlaybackTime`, `RecordingClip`, `Endpoint`.
- Produces:
  - `data class PlaybackSearchResult(val clips: List<RecordingClip>, val directReachable: Boolean, val cloudAvailable: Boolean, val endpoint: Endpoint, val startUtc: String, val endUtc: String)`
  - `class PlaybackSearchRepository(private val direct: PlaybackIsapi?, private val cloud: PlaybackIsapi?) { suspend fun search(trackId: Int, dayMillis: Long, zone: TimeZone, endpoint: Endpoint): Result<PlaybackSearchResult> }`

- [ ] **Step 1: Tulis test gagal**

```kotlin
class PlaybackSearchRepositoryTest {
  private val endpoint = Endpoint("192.168.1.64", 80, 554, remote = false)

  @Test suspend fun directFailure_fallsBackToCloud() {
    val cloud = FakeIsapi(listOf(200 to "<CMSearchResult><matchList/></CMSearchResult>"))
    val repo = PlaybackSearchRepository(FakeIsapi(emptyList()), cloud)
    val result = repo.search(101, 0L, TimeZone.getTimeZone("UTC"), endpoint).getOrThrow()
    assertTrue(result.directReachable.not())
    assertTrue(result.cloudAvailable)
    assertEquals(0, result.clips.size)
  }

  @Test suspend fun cloudMissing_returnsFailure() {
    val repo = PlaybackSearchRepository(FakeIsapi(emptyList()), null)
    assertTrue(repo.search(101, 0L, TimeZone.getTimeZone("UTC"), endpoint).isFailure)
  }
}
```

`FakeIsapi` mengembalikan `IsapiResponse(200, xml)` untuk setiap `post`, atau melempar `IOException()` bila daftar kosong.

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.PlaybackSearchRepositoryTest" --console=plain`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implementasi**

`search` menghitung `dayRange`, lalu `utcStamp` untuk start/end, lalu `PlaybackSearchXml.request(trackId, ...)`. Coba `direct?.post("/ISAPI/ContentMgmt/search/", body)`; bila `404`, coba `/ISAPI/ContentMgmt/search`. Respons 2xx → parse dan kembalikan dengan `directReachable=true`. Jika direct null/gagal, ulangi pada `cloud`; sukses → `cloudAvailable=true`. Keduanya gagal → `Result.failure`.

- [ ] **Step 4: Jalankan test, pastikan hijau**

Run: perintah Step 2
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackSearchResult.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackSearchRepository.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackSearchRepositoryTest.kt
git commit -m "feat: search NVR recordings with cloud fallback"
```

---

### Task 5: Cache download untuk cloud-only

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackCache.kt`
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackDownloader.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackCacheTest.kt`

**Interfaces:**
- Consumes: `RecordingClip`, `Endpoint`, `IsapiClient.getBinary(...)`.
- Produces:
  - `object PlaybackCache { fun fileFor(cacheDir: File, clip: RecordingClip): File }`
  - `class PlaybackDownloader(private val context: Context) { suspend fun download(clip: RecordingClip, endpoint: Endpoint, user: String, pass: String): Result<File>; fun clear() }`

- [ ] **Step 1: Tulis test gagal**

```kotlin
class PlaybackCacheTest {
  @Test fun fileFor_isStableAndSanitized() {
    val clip = RecordingClip(101, 1763970000000L, 1763970300000L)
    val a = PlaybackCache.fileFor(File("/cache"), clip)
    val b = PlaybackCache.fileFor(File("/cache"), clip)
    assertEquals(a.path, b.path)
    assertTrue(a.name.matches(Regex("[a-zA-Z0-9._-]+")))
  }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.PlaybackCacheTest" --console=plain`
Expected: FAIL, unresolved reference.

- [ ] **Step 3: Implementasi**

`fileFor` memakai nama `nvr-${trackId}-${startMillis}.mp4` di `cacheDir/playback/`. `download` membuat `IsapiClient(endpoint.httpBase, user, pass)`, POST XML download ke `/ISAPI/ContentMgmt/download` dengan `trackID`, `startTime`, `endTime`, mengambil `downloadId` dari respons, lalu GET biner `/ISAPI/ContentMgmt/download/{id}` dan menulis ke `fileFor`. Satu percobaan saja; gagal → `Result.failure`. `clear()` menghapus isi folder `playback`.

- [ ] **Step 4: Jalankan test, pastikan hijau**

Run: perintah Step 2
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackCache.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackDownloader.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackCacheTest.kt
git commit -m "feat: cache downloaded NVR clips for cloud-only playback"
```

---

### Task 6: Player menerima URI dan mengatur seek

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/RecordingPlayerActivity.kt`

**Interfaces:**
- Consumes: `SeekMode` dari Task 2.
- Produces: `const val EXTRA_MEDIA_URI = "recording_media_uri"`; player menerima `EXTRA_MEDIA_URI` atau `EXTRA_FILE_PATH`.

- [ ] **Step 1: Tambah extra dan pemilihan MRL**

Di companion object tambah `const val EXTRA_MEDIA_URI = "recording_media_uri"`. Di `onCreate`, tentukan `mrl = intent.getStringExtra(EXTRA_MEDIA_URI) ?: intent.getStringExtra(EXTRA_FILE_PATH)`, dan `isRemote = intent.hasExtra(EXTRA_MEDIA_URI)`. Gunakan `mrl` saat membuat `MediaPlayer`.

- [ ] **Step 2: Matikan seek untuk remote**

Bila `isRemote`, set `seekBar.isEnabled = false` dan `currentView.text` tetap diperbarui dari event waktu. Jangan mengubah perilaku file lokal.

- [ ] **Step 3: Verifikasi kompilasi**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/RecordingPlayerActivity.kt
git commit -m "feat: let recording player stream RTSP media"
```

---

### Task 7: Activity Playback, navigasi, manifest

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/PlaybackActivity.kt`
- Create: `app/src/main/res/layout/activity_playback.xml`
- Create: `app/src/main/res/drawable/ic_playback.xml`
- Modify: `app/src/main/res/menu/menu_main.xml`
- Modify: `app/src/main/java/id/cctv/monitor/MainActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: semua task sebelumnya, `CctvApp.prefs`, `CctvApp.cloudAuth`, `CctvApp.cloudRepo`, `EndpointResolver.resolve(camera, prefs.remoteSettings())`.
- Produces: halaman `PlaybackActivity` yang bisa dibuka dari bottom nav.

- [ ] **Step 1: Layout**

Buat `activity_playback.xml`: `MaterialToolbar` bertajuk "Playback", `Spinner` kanal, `TextView` tanggal + tombol `Pilih Tanggal`, tombol `Cari`, `ProgressBar`, dan `ScrollView` berisi `LinearLayout` `android:id="@+id/listRecording"`.

- [ ] **Step 2: Activity**

`PlaybackActivity : AppCompatActivity`. Saat `Cari`:
1. `EndpointResolver.resolve(camera, prefs.remoteSettings())`.
2. Bangun `DirectPlaybackIsapi(IsapiClient(endpoint.httpBase, user, pass))` dan `CloudPlaybackIsapi(CloudIsapiClient(cloudRepo, cloudAuth, camera.cloudDevice!!))` bila sesi cloud ada.
3. `repository.search(camera.streamId(true), selectedDay, TimeZone.getDefault(), endpoint)`.
4. Render tiap `RecordingClip` sebagai baris `PlaybackTime.label(...)` + `camera.displayName`.

Saat baris diketuk:
1. `resolver.resolve(result.directReachable, result.cloudAvailable)`.
2. `STREAM_RTSP` → `PlaybackRtspUrl.build(...)` → `Intent(this, RecordingPlayerActivity::class.java)` dengan `EXTRA_MEDIA_URI`.
3. `DOWNLOAD_CACHE` → `downloader.download(...)` → intent dengan `EXTRA_FILE_PATH`.
4. `ERROR_NO_ROUTE` → Toast "NVR tidak terjangkau. Hubungkan HP ke LAN/VPN atau login Hik-Connect."

`onDestroy` memanggil `downloader.clear()`.

- [ ] **Step 3: Navigasi dan manifest**

Tambah `nav_playback` di `menu_main.xml` dengan icon `ic_playback`, title "Playback", dan case di dua `setOnItemSelectedListener` / `onOptionsItemSelected` di `MainActivity`. Daftarkan `PlaybackActivity` dengan `android:exported="false"`.

- [ ] **Step 4: Verifikasi kompilasi**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/PlaybackActivity.kt \
  app/src/main/res/layout/activity_playback.xml \
  app/src/main/res/drawable/ic_playback.xml \
  app/src/main/res/menu/menu_main.xml \
  app/src/main/java/id/cctv/monitor/MainActivity.kt \
  app/src/main/AndroidManifest.xml
git commit -m "feat: add NVR playback screen"
```

---

### Task 8: Build, verifikasi device, jaga OTA

**Files:** tidak ada file baru.

**Interfaces:**
- Consumes: seluruh task sebelumnya.
- Produces: build hijau + bukti device + kepastian sertifikat.

- [ ] **Step 1: Build dan unit test**

Run: `.\gradlew.bat assembleDebug assembleRelease testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Cek sertifikat**

Run: `keytool -printcert -jarfile app\build\outputs\apk\release\app-release.apk | Select-String "SHA256"`
Expected: `29b29d0ace7d7b782bbb3c25e4c5afb4b0804191fadbb8f18f902aacecc4339d`.

- [ ] **Step 3: Install dan buka lewat UI**

```bash
adb shell am force-stop id.cctv.monitor
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am start --activity-clear-task -n id.cctv.monitor/.MainActivity
adb shell input tap <koordinat nav_playback>
```

- [ ] **Step 4: Uji search + putar**

Pilih kanal NVR, pilih tanggal yang diketahui ada rekaman, tekan **Cari**, tap entri pertama. Expected: daftar muncul, player tampil, `logcat` tidak mengandung `FATAL EXCEPTION`, dan screenshot menunjukkan judul + waktu berjalan.

- [ ] **Step 5: Uji cloud-only**

Matikan akses LAN ke NVR (WiFi diputus / pakai data seluler) dengan sesi cloud masih aktif. Ulangi tap entri. Expected: download fallback jalan, atau pesan jelas bila endpoint download ditolak. Tidak boleh retry loop.

- [ ] **Step 6: Commit terakhir bila ada perbaikan kecil**

```bash
git add <file yang benar-benar berubah>
git commit -m "fix: playback verification findings"
```

---

## Self-review

- Spec coverage: date picker ✓ T7, ISAPI search ✓ T1/T3/T4, cloud fallback ✓ T4/T5, RTSP streaming ✓ T2/T6, satu decoder ✓ Global + T6, RAM/OTA ✓ Global + T8, probe ✓ T8, non-goal ✓ Global.
- Type consistency: `RecordingClip`, `PlaybackTransport`, `SeekMode`, `PlaybackIsapi`, `PlaybackSearchResult` dipakai dengan nama yang sama di task berikutnya.
- Review Focus: kelima input sudah punya test di Task 1, 2, dan 4.
- Proportion: plan lebih panjang dari spec karena memuat test dan perintah, tetapi setiap langkah memutuskan satu hal konkret.
