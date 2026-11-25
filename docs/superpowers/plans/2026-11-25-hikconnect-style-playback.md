# Hik-Connect Style Playback Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the list-first Playback screen with a Hik-Connect-style date strip, filter chips, and 24-hour timeline that opens the existing single-VLC player at the tapped minute.

**Architecture:** Pure Kotlin helpers own time ratio, clip precedence, filtering, and seek-offset math. A framework Canvas view renders and handles drag; `PlaybackActivity` reuses the existing direct-cloud search/transport chain. The existing `RecordingPlayerActivity` gains two extras but keeps one decoder and RTSP seek disabled.

**Tech Stack:** Kotlin, Android SDK (minSdk 24), Material Components 1.12.0, LibVLC 3.7.7, coroutines, Canvas `View`, JUnit 4.

**Spec:** `docs/superpowers/specs/2026-11-25-hikconnect-style-playback-design.md`

## Global Constraints

- Tidak ada dependency baru. LibVLC tetap `org.videolan.android:libvlc-all:3.7.7`.
- Tidak ada TFLite / on-device ML / decoder grid / polling.
- Satu `MediaPlayer` VLC aktif lewat `SharedVlc`.
- Seek RTSP tetap mati; file cache tetap bisa seek.
- `versionCode` tetap `12`, `versionName` tetap `"1.1.10"`.
- Sertifikat signing tetap `29b29d0ace7d7b782bbb3c25e4c5afb4b0804191fadbb8f18f902aacecc4339d`.
- Activity baru wajib `android:exported="false"`; tidak ada activity baru dalam rencana ini.
- ISAPI XML memakai stamp extended; query RTSP memakai stamp basic. Jangan ubah `PlaybackTime.utcStamp` / `xmlStamp`.
- Jangan `git add -A`. Stage hanya file yang disebut task. Termasuk file artifact recon yang tidak ter-track — jangan stage.
- Belum release / tag / push tanpa perintah lanjutan.

## Review Focus

- Event segment bersarang di continuous clip — `PlaybackTimelineTest.clipAt_eventWinsOverContinuous` memastikan tap event tidak tertelan continuous.
- Tap di celah antar klip — `PlaybackTimelineTest.clipAt_gapPicksNearestClip` memastikan tetap ada klip terdekat.
- Filter Person/Vehicle tanpa metadata — `PlaybackTimelineTest.filter_*` memastikan tidak mengarang hasil.
- `playbackURI` dari search hilang — `PlaybackDownloaderTest.download_blankPlaybackUri_failsClearly` memastikan error jelas, bukan body lama yang return 400.
- Drag timeline dilepas di luar jam mana pun — `PlaybackTimelineTest.pickedMillis_clampsAndSeekOffset` memastikan jam di-clamp ke batas klip dan offset tidak negatif.

---

### Task 1: Metadata klip dari respons search

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackEventKind.kt`
- Modify: `app/src/main/java/id/cctv/monitor/playback/RecordingClip.kt`
- Modify: `app/src/main/java/id/cctv/monitor/playback/PlaybackSearchXml.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackSearchXmlTest.kt`

**Interfaces:**
- Consumes: `PlaybackSearchXml.parse(body)` yang ada.
- Produces:
  - `enum class PlaybackEventKind { CONTINUOUS, EVENT, PERSON, VEHICLE }` dengan `companion object { fun from(raw: String?): PlaybackEventKind }`.
  - `data class RecordingClip(val trackId: Int, val startMillis: Long, val endMillis: Long, val playbackUri: String = "", val eventKind: PlaybackEventKind = PlaybackEventKind.CONTINUOUS)`.
  - `PlaybackSearchXml.parse` mengisi `playbackUri` dari `<playbackURI>` dan `eventKind` dari `<eventType>` lalu `<event>`.

- [ ] **Step 1: Tulis test gagal**

Tambahkan ke `PlaybackSearchXmlTest.kt`:

```kotlin
@Test fun parse_readsPlaybackUriAndEventKind() {
    val xml = """<?xml version="1.0"?><CMSearchResult><matchList>
      <searchMatchItem><trackID>101</trackID>
        <playbackURI>rtsp://nvr/tracks/101/?a=1&amp;b=2</playbackURI>
        <eventType>person</eventType>
        <timeSpan><startTime>2026-11-23T16:52:50Z</startTime>
          <endTime>2026-11-23T17:09:46Z</endTime></timeSpan>
      </searchMatchItem>
    </matchList></CMSearchResult>"""
    val clip = PlaybackSearchXml.parse(xml).single()
    assertEquals("rtsp://nvr/tracks/101/?a=1&b=2", clip.playbackUri)
    assertEquals(PlaybackEventKind.PERSON, clip.eventKind)
}

@Test fun eventKind_fromMapsUnknownToContinuous() {
    assertEquals(PlaybackEventKind.PERSON, PlaybackEventKind.from("Face"))
    assertEquals(PlaybackEventKind.VEHICLE, PlaybackEventKind.from("vehicle"))
    assertEquals(PlaybackEventKind.EVENT, PlaybackEventKind.from("motion"))
    assertEquals(PlaybackEventKind.CONTINUOUS, PlaybackEventKind.from(null))
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.PlaybackSearchXmlTest" --console=plain`
Expected: FAIL, `PlaybackEventKind` unresolved.

- [ ] **Step 3: Implementasi**

Buat `PlaybackEventKind.kt`. `from(raw)` lowercase dan trim; `person`/`face` → `PERSON`, `vehicle`/`car` → `VEHICLE`, teks lain non-kosong → `EVENT`, null/kosong → `CONTINUOUS`.

Tambah dua field ke `RecordingClip` dengan default di atas supaya `RecordingClip(101, start, end)` di test lama tetap kompil.

Di `PlaybackSearchXml.parse`, baca `playbackURI` memakai helper `text(...)` yang sudah ada; baca `eventType`, bila kosong baca `event`, lalu `PlaybackEventKind.from(...)`.

- [ ] **Step 4: Jalankan test, pastikan hijau**

Run: perintah Step 2
Expected: PASS, termasuk 3 test lama.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackEventKind.kt \
  app/src/main/java/id/cctv/monitor/playback/RecordingClip.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackSearchXml.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackSearchXmlTest.kt
git commit -m "feat: parse playback URI and event kind from search XML"
```

---

### Task 2: Helper timeline murni

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackFilter.kt`
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackTimeline.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackTimelineTest.kt`

**Interfaces:**
- Consumes: `RecordingClip`, `PlaybackEventKind` dari Task 1.
- Produces:
  - `enum class PlaybackFilter { ALL, EVENT, PERSON, VEHICLE }`.
  - `object PlaybackTimeline` dengan:
    - `fun ratio(millis: Long, dayStart: Long, dayEnd: Long): Float`
    - `fun clipAt(clips: List<RecordingClip>, ratio: Float, dayStart: Long, dayEnd: Long): RecordingClip?`
    - `fun index(clips: List<RecordingClip>, ratio: Float, dayStart: Long, dayEnd: Long): Int`
    - `fun filter(clips: List<RecordingClip>, filter: PlaybackFilter): List<RecordingClip>`
    - `fun step(size: Int, index: Int, delta: Int): Int`
    - `fun pickedMillis(clip: RecordingClip, requested: Long): Long`
    - `fun seekOffset(clip: RecordingClip, requested: Long): Long`

- [ ] **Step 1: Tulis test gagal**

Buat `PlaybackTimelineTest.kt`:

```kotlin
package id.cctv.monitor.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTimelineTest {
    private val dayStart = 1_700_000_000_000L
    private val dayEnd = dayStart + 24L * 60 * 60 * 1000
    private val continuous = RecordingClip(101, dayStart, dayStart + 3_600_000L)
    private val person = RecordingClip(
        101, dayStart + 1_800_000L, dayStart + 2_100_000L,
        eventKind = PlaybackEventKind.PERSON
    )

    @Test fun ratio_isClamped() {
        assertEquals(0f, PlaybackTimeline.ratio(dayStart - 1, dayStart, dayEnd))
        assertEquals(1f, PlaybackTimeline.ratio(dayEnd + 1, dayStart, dayEnd))
        assertEquals(0.5f, PlaybackTimeline.ratio(dayStart + 43_200_000L, dayStart, dayEnd))
    }

    @Test fun clipAt_eventWinsOverContinuous() {
        val picked = PlaybackTimeline.clipAt(
            listOf(continuous, person), 1_950_000f / 86_400_000f, dayStart, dayEnd
        )
        assertEquals(person, picked)
    }

    @Test fun clipAt_gapPicksNearestClip() {
        val later = RecordingClip(101, dayStart + 10_800_000L, dayStart + 10_900_000L)
        val picked = PlaybackTimeline.clipAt(
            listOf(continuous, later), 0.15f, dayStart, dayEnd
        )
        assertEquals(later, picked)
    }

    @Test fun filter_onlyReturnsMatchingKinds() {
        val clips = listOf(continuous, person)
        assertEquals(2, PlaybackTimeline.filter(clips, PlaybackFilter.ALL).size)
        assertEquals(listOf(person), PlaybackTimeline.filter(clips, PlaybackFilter.PERSON))
        assertEquals(0, PlaybackTimeline.filter(clips, PlaybackFilter.VEHICLE).size)
    }

    @Test fun step_clampsToAvailableClips() {
        assertEquals(-1, PlaybackTimeline.step(0, -1, 1))
        assertEquals(0, PlaybackTimeline.step(2, 0, -1))
        assertEquals(1, PlaybackTimeline.step(2, 0, 1))
        assertEquals(1, PlaybackTimeline.step(2, 1, 1))
    }

    @Test fun pickedMillis_clampsAndSeekOffset() {
        val picked = PlaybackTimeline.pickedMillis(continuous, continuous.startMillis - 5_000L)
        assertEquals(continuous.startMillis, picked)
        assertEquals(0L, PlaybackTimeline.seekOffset(continuous, picked))
        assertEquals(
            1_800_000L,
            PlaybackTimeline.seekOffset(continuous, dayStart + 1_800_000L)
        )
    }
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.PlaybackTimelineTest" --console=plain`
Expected: FAIL, unresolved references.

- [ ] **Step 3: Implementasi**

`ratio` = `(millis - dayStart).toFloat() / (dayEnd - dayStart).toFloat()`, di-clamp `0f..1f`; bila `dayEnd <= dayStart` return `0f`.

`clipAt`: ubah ratio ke millis; kumpulkan klip yang `[startMillis, endMillis]` memuat millis itu. Bila ada lebih dari satu, urutkan prioritas `PERSON(3) > VEHICLE(2) > EVENT(1) > CONTINUOUS(0)`, lalu yang lebih pendek. Bila tidak ada yang memuat, pilih klip dengan jarak minimum dari millis ke intervalnya. Kosong → `null`.

`index` = posisi hasil `clipAt` pada list asli, atau `-1`.

`filter`: `ALL` return `clips`; lainnya `clips.filter { it.eventKind == filter.name.let(PlaybackEventKind::valueOf) }`.

`step`: bila `size == 0` return `-1`; else `(index + delta).coerceIn(0, size - 1)`.

`pickedMillis` = `requested.coerceIn(clip.startMillis, clip.endMillis)`. `seekOffset` = `pickedMillis(clip, requested) - clip.startMillis`, minimal `0L`.

- [ ] **Step 4: Jalankan test, pastikan hijau**

Run: perintah Step 2
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackFilter.kt \
  app/src/main/java/id/cctv/monitor/playback/PlaybackTimeline.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackTimelineTest.kt
git commit -m "test: pin Hik-Connect timeline selection rules"
```

---

### Task 3: Custom timeline View

**Files:**
- Create: `app/src/main/java/id/cctv/monitor/playback/PlaybackTimelineView.kt`

**Interfaces:**
- Consumes: `PlaybackTimeline`, `RecordingClip` dari Task 1–2.
- Produces:
  - `class PlaybackTimelineView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs)`
  - Properti publik: `var dayStart: Long`, `var dayEnd: Long`, `var clips: List<RecordingClip>`, `var selectedIndex: Int`, `var onScrub: ((Long) -> Unit)?`, `var onPick: ((RecordingClip, Long) -> Unit)?`.
  - `fun render()` yang memanggil `invalidate()`.

- [ ] **Step 1: Tambah kelas View dengan state dan callback**

Buat file dengan konstruktor dan properti di atas. Default `dayEnd = dayStart`, `clips = emptyList()`, `selectedIndex = -1`. `render()` hanya `invalidate()`.

- [ ] **Step 2: Implement `onDraw`**

Gambar latar `@color/stroke` setinggi view; untuk tiap klip gambar rect dari `PlaybackTimeline.ratio(start)` sampai `ratio(end)`. Warna: `PERSON`/`VEHICLE`/`EVENT` → `@color/warning`, `CONTINUOUS` → `@color/accent`. Penanda klip terpilih → garis vertical `@color/text`. Clipping ke `0..width`.

- [ ] **Step 3: Implement `onTouchEvent`**

`ACTION_DOWN`: simpan ratio x, panggil `onScrub` dengan millis terpilih, return true.
`ACTION_MOVE`: update ratio, panggil `onScrub` saja (jangan `onPick`).
`ACTION_UP`: `PlaybackTimeline.clipAt(...)`; bila tidak null panggil `onPick(clip, millis)`; return true.
`ACTION_CANCEL`: hanya `render()`, jangan `onPick`.

- [ ] **Step 4: Verifikasi kompilasi**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackTimelineView.kt
git commit -m "feat: draw draggable 24-hour recording timeline"
```

---

### Task 4: Download cloud memakai playbackURI

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/playback/PlaybackDownloader.kt`
- Test: `app/src/test/java/id/cctv/monitor/playback/PlaybackDownloaderTest.kt`

**Interfaces:**
- Consumes: `RecordingClip.playbackUri` dari Task 1.
- Produces: `PlaybackDownloader.download(...)` tetap `Result<File>`; body POST berubah menjadi `downloadRequest` dengan `<playbackURI>`; `playbackUri` kosong → `Result.failure`.

- [ ] **Step 1: Tulis test gagal**

Ubah test pertama:

```kotlin
val uri = "rtsp://192.168.100.34/Streaming/tracks/101/?starttime=20261123T165250Z&endtime=20261123T170946Z"
val clip = RecordingClip(101, 1_700_000_000_000L, 1_700_000_300_000L, playbackUri = uri)
```

Di server, ganti assertion:

```kotlin
assertTrue(body.contains("<playbackURI>$uri</playbackURI>"))
assertTrue(body.contains("xmlns=\"http://www.isapi.org/ver20/XMLSchema\""))
assertFalse(body.contains("<trackID>"))
```

Tambah test baru:

```kotlin
@Test fun download_blankPlaybackUri_failsClearly() {
    val cacheDir = createTempDirectory("nvr-playback").toFile()
    val downloader = PlaybackDownloader(cacheDir)
    val clip = RecordingClip(101, 1_700_000_000_000L, 1_700_000_300_000L)
    val endpoint = Endpoint("127.0.0.1", 1, 554, remote = false)
    val error = runBlocking { downloader.download(clip, endpoint, "u", "p").exceptionOrNull() }
    assertTrue(error?.message?.contains("playbackURI") == true)
    cacheDir.deleteRecursively()
}
```

- [ ] **Step 2: Jalankan test, pastikan gagal**

Run: `.\gradlew.bat testDebugUnitTest --tests "id.cctv.monitor.playback.PlaybackDownloaderTest" --console=plain`
Expected: FAIL pada assertion body lama.

- [ ] **Step 3: Implementasi**

Ganti `requestXml(clip)` menjadi:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<downloadRequest version="1.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
  <playbackURI>${clip.playbackUri}</playbackURI>
</downloadRequest>
```

Sebelum `postDownload`, bila `clip.playbackUri.isBlank()` lempar `IllegalStateException("Perangkat tidak mengembalikan playbackURI rekaman.")`. Jangan jatuh ke body lama. Rute tetap `IsapiClient(endpoint.httpBase, user, pass)`.

- [ ] **Step 4: Jalankan test, pastikan hijau**

Run: perintah Step 2
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/playback/PlaybackDownloader.kt \
  app/src/test/java/id/cctv/monitor/playback/PlaybackDownloaderTest.kt
git commit -m "fix: download NVR clips with playbackURI"
```

---

### Task 5: Player menerima jam pilihan dan offset cache

**Files:**
- Modify: `app/src/main/java/id/cctv/monitor/RecordingPlayerActivity.kt`

**Interfaces:**
- Consumes: `PlaybackTimeline.seekOffset` dari Task 2 dipanggil Activity; player hanya menerima angka.
- Produces:
  - `const val EXTRA_PLAY_MILLIS = "recording_play_millis"`
  - `const val EXTRA_SEEK_OFFSET_MILLIS = "recording_seek_offset_millis"`
  - Player memakai `EXTRA_PLAY_MILLIS` untuk judul waktu bila dikirim, dan `EXTRA_SEEK_OFFSET_MILLIS` untuk seek sekali setelah `Playing` bila seek aktif.

- [ ] **Step 1: Tambah konstanta dan baca extra**

Di companion tambah dua konstanta di atas. Di `onCreate`, setelah `clipStart`/`clipEnd`, baca:

```kotlin
val playMillis = intent.getLongExtra(EXTRA_PLAY_MILLIS, clipStart)
val seekOffset = intent.getLongExtra(EXTRA_SEEK_OFFSET_MILLIS, 0L).coerceAtLeast(0L)
```

Simpan `seekOffsetMillis` sebagai field. Bila `playMillis > clipStart`, judul tetap memakai `EXTRA_TITLE` dari Activity (jangan diganti di sini).

- [ ] **Step 2: Terapkan offset sekali untuk file lokal**

Tambah field `private var seekOffsetApplied = false`. Di `onPlayerEvent`, cabang `Playing`/`Vout`, setelah `playingSeen = true`: bila `seekMode == SeekMode.ENABLED && seekOffsetMillis > 0L && !seekOffsetApplied`, set `player?.time = seekOffsetMillis` dan `seekOffsetApplied = true`. Untuk RTSP jangan pernah set `time`.

- [ ] **Step 3: Verifikasi kompilasi**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL. Perilaku file lama tanpa extra tetap sama karena default offset `0L`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/id/cctv/monitor/RecordingPlayerActivity.kt
git commit -m "feat: open recording player at a chosen timestamp"
```

---

### Task 6: Layar Playback timeline-first

**Files:**
- Create: `app/src/main/res/drawable/ic_chevron_left.xml`
- Create: `app/src/main/res/drawable/ic_chevron_right.xml`
- Create: `app/src/main/res/drawable/ic_calendar.xml`
- Modify: `app/src/main/res/layout/activity_playback.xml`
- Modify: `app/src/main/java/id/cctv/monitor/PlaybackActivity.kt`

**Interfaces:**
- Consumes: semua task sebelumnya; `RecordingPlayerActivity.EXTRA_*`, `PlaybackTimeline`, `PlaybackTimelineView`, `PlaybackFilter`, `PlaybackTransportResolver`, `PlaybackRtspUrl`.
- Produces: halaman Playback dengan id baru:
  - `spinnerPlaybackChannel`, `btnPlaybackPrevDay`, `tvPlaybackDate`, `btnPlaybackNextDay`, `btnPlaybackDate`
  - `filterPlayback` (`MaterialButtonToggleGroup` dengan `app:singleSelection="true"` dan `app:selectionRequired="true"`), child `filterAll`, `filterEvent`, `filterPerson`, `filterVehicle`
  - `timelinePlayback` (`id.cctv.monitor.playback.PlaybackTimelineView`)
  - `tvPlaybackTime`, `btnPlaybackPrev`, `btnPlaybackNext`, `tvPlaybackHint`, `progressPlayback`.

- [ ] **Step 1: Buat tiga vector drawable**

`ic_chevron_left.xml`, `ic_chevron_right.xml`, `ic_calendar.xml`: `<vector>` 24dp, `android:tint="@color/text"`, path panah/kalender sederhana. Tanpa dependency baru.

- [ ] **Step 2: Tulis ulang layout**

Ganti isi `activity_playback.xml`: toolbar tetap; header kanal + baris tanggal (`btnPlaybackPrevDay`, `tvPlaybackDate`, `btnPlaybackNextDay`, `btnPlaybackDate`); `MaterialButtonToggleGroup` `filterPlayback` dengan 4 `MaterialButton` `app:checked` hanya pada `All`; `PlaybackTimelineView` `timelinePlayback` tinggi `72dp`; baris `btnPlaybackPrev`, `tvPlaybackTime`, `btnPlaybackNext`; `tvPlaybackHint`; `progressPlayback` center. Hapus `btnPlaybackSearch` dan `listRecording`.

- [ ] **Step 3: Tulis ulang Activity — state dan search otomatis**

Field: `cameras`, `selectedDay`, `rawClips`, `selectedFilter = PlaybackFilter.ALL`, `selectedTime`, `busy`. `onCreate` mengikat semua id, mengisi spinner, `filterPlayback.addOnButtonCheckedListener` yang memetakan id child ke `PlaybackFilter` lalu `renderTimeline()`, tombol tanggal memakai `DatePickerDialog` yang ada, prev/next hari mengubah `selectedDay` dan memanggil `performSearch()`. Panggil `performSearch()` sekali di akhir `onCreate`. Tidak ada polling.

- [ ] **Step 4: Render timeline**

`renderTimeline()`: `visible = PlaybackTimeline.filter(rawClips, selectedFilter)`; set `timelinePlayback.dayStart/dayEnd` dari `PlaybackTime.dayRange(selectedDay, TimeZone.getDefault())`; set `clips`, `selectedIndex`; `onScrub = { selectedTime = it; updateTimeLabel() }`; `onPick = { clip, millis -> openAt(clip, millis) }`; `render()`. `tvPlaybackHint` menampilkan jumlah klip atau "Tidak ada rekaman pada tanggal ini." bila kosong. `updateTimeLabel()` memformat jam dengan `SimpleDateFormat("HH:mm", Locale.US)` pada timezone default.

- [ ] **Step 5: Buka player dari timeline dan tombol step**

`openAt(clip, requested)`: `picked = PlaybackTimeline.pickedMillis(clip, requested)`; `offset = PlaybackTimeline.seekOffset(clip, picked)`. Resolve transport seperti lama:
- `STREAM_RTSP`: `PlaybackRtspUrl.build(trackId, user, pass, host, rtspPort, PlaybackTime.utcStamp(picked), PlaybackTime.utcStamp(clip.endMillis))`, lalu `startPlayer(mediaUri = uri, ..., playMillis = picked, seekOffset = 0L)`.
- `DOWNLOAD_CACHE`: `downloader.download(clip, ...)` lalu `startPlayer(filePath = ..., playMillis = picked, seekOffset = offset)`.
- `ERROR_NO_ROUTE`: toast lama.

`btnPlaybackPrev/Next`: `ratioOf = PlaybackTimeline.ratio(selectedTime, dayStart, dayEnd)`; `idx = PlaybackTimeline.step(visible.size, PlaybackTimeline.index(visible, ratioOf, dayStart, dayEnd), -1/+1)`; bila `idx >= 0` panggil `openAt(visible[idx], visible[idx].startMillis)`.

`startPlayer` mengirim `EXTRA_TITLE` = `PlaybackUi.rowLabel(clip, camera, zone)`, `EXTRA_START_MILLIS`, `EXTRA_END_MILLIS`, `EXTRA_PLAY_MILLIS`, `EXTRA_SEEK_OFFSET_MILLIS`. `onDestroy` tetap `downloader.clear()`.

- [ ] **Step 6: Verifikasi kompilasi**

Run: `.\gradlew.bat compileDebugKotlin --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res/drawable/ic_chevron_left.xml \
  app/src/main/res/drawable/ic_chevron_right.xml \
  app/src/main/res/drawable/ic_calendar.xml \
  app/src/main/res/layout/activity_playback.xml \
  app/src/main/java/id/cctv/monitor/PlaybackActivity.kt
git commit -m "feat: make playback timeline-first like Hik-Connect"
```

---

### Task 7: Build, device, dan jaga OTA

**Files:** tidak ada file produksi baru.

**Interfaces:**
- Consumes: seluruh task sebelumnya.
- Produces: build hijau + bukti device + kepastian sertifikat + ledger selesai.

- [ ] **Step 1: Build dan unit test**

Run: `.\gradlew.bat assembleDebug assembleRelease testDebugUnitTest --console=plain`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Cek sertifikat**

Run: `keytool -printcert -jarfile app\build\outputs\apk\release\app-release.apk | Select-String "SHA256"`
Expected: `29b29d0ace7d7b782bbb3c25e4c5afb4b0804191fadbb8f18f902aacecc4339d`.

- [ ] **Step 3: Install dan buka UI**

```bash
adb shell am force-stop id.cctv.monitor
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell am start --activity-clear-task -n id.cctv.monitor/.MainActivity
```

Tap nav Playback. `uiautomator dump` harus memuat `tvPlaybackDate`, 4 filter, dan `timelinePlayback`.

- [ ] **Step 4: Verifikasi timeline nyata**

Pilih tanggal `23 Nov 2026` lewat tombol kalender, kanal `CAM-09`. Timeline harus menampilkan segmen. Tap segmen pertama. Expected: `RecordingPlayerActivity` terbuka, judul `23:50–00:23 • CAM-09` (atau klip pertama yang tersedia), overlay hilang, waktu berjalan. Kembali → timeline masih terisi. `logcat` tanpa `FATAL EXCEPTION`.

- [ ] **Step 5: Tutup ledger**

Jalankan `task-done` untuk task ini dengan perintah Step 1 sebagai test command, memakai base SHA yang dicatat `task-start`. Expected: baris `Task 7: complete` muncul di `.superpowers/sdd/2026-11-25-hikconnect-style-playback/progress.md`.

---

## Self-review

- Spec coverage: date strip ✓ T6, filter ✓ T2/T3/T6, timeline drag ✓ T3, overlap precedence ✓ T2, step semantics ✓ T2/T6, metadata ✓ T1, playbackURI download ✓ T4, direct-HTTP limitation ✓ T4/Global, player jam ✓ T5, satu decoder ✓ Global/T5, RAM/OTA ✓ Global/T7, tidak release ✓ Global/T7.
- Type consistency: `RecordingClip`, `PlaybackEventKind`, `PlaybackFilter`, `PlaybackTimeline`, `PlaybackTimelineView`, `EXTRA_PLAY_MILLIS`, `EXTRA_SEEK_OFFSET_MILLIS` memakai nama yang sama di task berikutnya.
- Review Focus: kelima input sudah punya test di T2 dan T4.
- Proportion: plan memuat signature + test + perintah, bukan transkrip seluruh body; titik keputusan ada di Interfaces dan Step 3 tiap task.
