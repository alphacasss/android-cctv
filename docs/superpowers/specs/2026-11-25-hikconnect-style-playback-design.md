# Playback bergaya Hik-Connect — Desain

Status: **Disetujui lewat delegasi** — pengguna: "semua aku serahkan ke kamu oke" (2026-11-25).
Tanggal: 2026-11-25
Desain sebelumnya: `docs/superpowers/specs/2026-11-24-nvr-playback-design.md` (list-first, sudah diimplementasi).

## Intent

Layar Playback yang sekarang masih **list-first** (spinner → tanggal → **Cari Rekaman** → daftar klip → player). Aku ubah jadi **timeline-first** seperti yang kulihat langsung di aplikasi Hik-Connect terpasang di HP: tanggal bisa mundur/maju, filter, bilah 24 jam, tap bilah → langsung putar pada jam itu.

Ini bukan paritas penuh. Aturan lama proyek tetap dipegang: **satu decoder VLC**, **seek RTSP mati**, tanpa dependency baru, tanpa ML, tanpa polling.

## Yang kulihat di Hik-Connect (recon perangkat)

- Video di atas, toolbar: `Playback / Live`, snapshot, rec, audio, download.
- Bawah video: `‹ Today ›`, kalender, filter `All / Event / Person / Vehicle`.
- Bilah waktu 24 jam berwarna (continuous vs event), bisa ditap/digeser, ada teks jam `00:01:14`, dan tombol langkah `« »`.
- Tap bilah → lompat ke jam itu. Event Playback memakai daftar 7 hari terakhir.

Sketsa target:

```
[ toolbar: Playback ]
[ CAM-09 ▾ ]  ‹  23 Nov 2026  ›  [📅]
[ All ][ Event ][ Person ][ Vehicle ]
00:00  ▓▓▓░░▓▓░░░░ 24:00
        «  07:12  »
[ ketuk bilah untuk memutar ]
```

## Tujuan

1. `PlaybackActivity` menampilkan **date strip + filter + timeline 24 jam**, bukan daftar berurut.
2. Tap timeline memilih klip **dan jam di dalamnya**, lalu membuka `RecordingPlayerActivity` yang sama pada jam itu.
3. Jalur LAN/VPN tetap streaming RTSP; cloud-only tetap download → cache → hapus. Download memakai `playbackURI` asli dari respons search.
4. Aturan tampilan dan pemetaan waktu ditaruh di helper Kotlin murni supaya bisa diuji di JVM.

## Non-goal

- Empat kamera sekaligus, pinch-zoom halus, scrub decoder langsung, speed control, snapshot/audio/download di player.
- TFLite / on-device ML / klasifikasi orang-kendaraan buatan aplikasi.
- Dependency baru, native SDK, decoder kedua, polling.
- Ubah `versionCode` (`12`), `versionName` (`1.1.10`), keystore, atau sertifikat.
- Release / tag / push tanpa perintah lanjutan.

## Constraint

- Satu `MediaPlayer` VLC aktif lewat `SharedVlc`.
- RTSP replay: `seekBar` nonaktif, waktu dipacu jam lokal + `LengthChanged(0)` diabaikan.
- File cache lokal: seek tetap aktif.
- ISAPI XML memakai stamp extended `yyyy-MM-dd'T'HH:mm:ss'Z'`; query RTSP memakai stamp basic `yyyyMMdd'T'HHmmss'Z'`.
- `trackID` NVR yang terbukti jalan: `101` (main stream).
- Activity baru tetap `android:exported="false"`.
- `searchID` XML wajib GUID.

## Approaches

| | Bentuk | Trade-off |
|---|---|---|
| **A** | Timeline overlay ditambahkan ke player sekarang | Paling murah, tapi tetap list-first untuk memilih klip. Timeline jadi aksesoris, bukan model utama. |
| **B** | `PlaybackActivity` timeline-first; player tetap satu | Paling mirip Hik-Connect untuk satu kamera. Tap jam → buka ulang RTSP di jam itu (bukan seek decoder). Cocok dengan aturan proyek. **Dipilih.** |
| **C** | Paritas penuh 4 kamera + scrub asli + speed/snapshot/download | Butuh decoder banyak dan seek RTSP; melanggar aturan lama. Tidak dikerjakan. |

Pilihanku: **B**. Aku serahkan keputusan ke pengguna, jadi ini catatan keputusanku, bukan pertanyaan baru.

## Komponen

### 1. `PlaybackTimeline.kt` (murni, diuji)

Object Kotlin tanpa Android:

- `ratio(millis, dayStart, dayEnd): Float` → `0f..1f`.
- `clipAt(clips, ratio, dayStart, dayEnd): RecordingClip?` → klip yang memuat jam itu; kalau jam masuk celah, klip terdekat.
- `index(clips, ratio, dayStart, dayEnd): Int` → indeks klip terpilih untuk tombol `« »`.
- `filter(clips, filter): List<RecordingClip>` → `ALL` return semua; lainnya cocokkan `eventKind`.
- `step(clips, index, delta): Int` → indeks sebelumnya/berikutnya, di-clamp.

Aturan yang mengikat (jawaban reviewer):

- **Prioritas saat tumpang tindih:** `PERSON > VEHICLE > EVENT > CONTINUOUS`; bila sama, klip lebih pendek menang. Jadi tap pada event tidak pernah "tertelan" continuous.
- **`« »` waktu:** pakai `startMillis` klip tujuan. Tidak mempertahankan posisi dalam klip sebelumnya, supaya tombol selalu deterministik.
- **`PlaybackFilter`** enum: `ALL, EVENT, PERSON, VEHICLE`.
`PlaybackEventKind` enum: `CONTINUOUS, EVENT, PERSON, VEHICLE`.

### 2. `PlaybackTimelineView.kt`

Custom `View`framework (Canvas, tanpa library):

- Terima `dayStart`, `dayEnd`, `clips` hasil filter, `selectedIndex`.
- Gambar latar `stroke`, segmen continuous `accent`, segmen event `warning`, penanda selected `text`.
- Gestur: `ACTION_DOWN` mulai melacak penanda, `ACTION_MOVE` hanya menggeser penanda (update label jam), `ACTION_UP` baru kirim `onPick(clip, finalMillis)`. Tap = drag tanpa pergerakan. Ini meniru Hik-Connect tanpa decoder kedua atau scrub decoder.
- `contentDescription` dan `AccessibilityNodeInfo` menyebut jam terpilih.

### 3. `RecordingClip.kt` + `PlaybackSearchXml.kt`

Tambah field dengan **default** supaya konstruksi lama tetap kompil:

```kotlin
data class RecordingClip(
    val trackId: Int,
    val startMillis: Long,
    val endMillis: Long,
    val playbackUri: String = "",
    val eventKind: PlaybackEventKind = PlaybackEventKind.CONTINUOUS
)
```

Parser mengambil `<playbackURI>` dan `<eventType>`/`event` bila ada. Mapping: teks mengandung `person`/`face` → `PERSON`, `vehicle`/`car` → `VEHICLE`, teks lain non-kosong → `EVENT`, kosong → `CONTINUOUS`. Person/Vehicle tanpa metadata menampilkan pesan kosong yang jujur, bukan hasil palsu.

### 4. `PlaybackDownloader.kt` (perbaikan bug cloud)

Ganti body download lama (`trackID` + jam) dengan:

```xml
<downloadRequest version="1.0" xmlns="http://www.isapi.org/ver20/XMLSchema">
  <playbackURI>…</playbackURI>
</downloadRequest>
```

Kalau `clip.playbackUri` kosong → `Result.failure` dengan pesan "Perangkat tidak mengembalikan playbackURI rekaman." Satu percobaan, tanpa retry storm. Test server lokal diperbarui untuk memeriksa body baru.

Rute download tetap **direct HTTP** ke `endpoint.httpBase` dengan kredensial perangkat, karena binary GET (`downloadId` → MP4) hanya tersedia di `IsapiClient`. Cloud passthrough dipakai untuk **search metadata** saja; `CloudIsapiClient` tidak punya transport biner. Bila direct HTTP tidak terjangkau, tampilkan pesan jelas "NVR tidak terjangkau untuk unduhan. Hubungkan HP ke LAN/VPN." — jangan mengklaim cloud-only berhasil. Ini batas jujur proyek tanpa SDK/relay cloud.

### 5. `PlaybackActivity.kt` + `activity_playback.xml`

- Hapus `btnPlaybackSearch` dan `ScrollView` daftar klip.
- Header: spinner kanal, `‹` / label tanggal / `›` / `btnPlaybackDate`. Tombol tanggal memakai `DatePickerDialog` yang sudah ada.
- Auto-search saat Activity dibuka, saat tanggal berubah, dan saat kanal berubah. Filter hanya filter lokal, tidak request ulang.
- `MaterialButtonToggleGroup` (sudah tersedia di Material yang dipakai proyek) untuk 4 filter. Fallback bila tidak tersedia: 4 `MaterialButton` checkable dalam `LinearLayout`.
- `PlaybackTimelineView` + label jam + `« »`. Label tanggal memakai "Today"/"Hari ini" bila sama dengan hari sistem.
- Tap timeline / lepas drag → `openAt(clip, finalMillis)`. `« »` memakai `startMillis` klip tetangga, bukan posisi dalam klip sekarang.
- Tetap memakai `EndpointResolver`, `DirectPlaybackIsapi` dulu, `CloudPlaybackIsapi` fallback, `PlaybackTransportResolver`.

### 6. `RecordingPlayerActivity.kt`

Tambah extra:

- `EXTRA_PLAY_MILLIS` — jam yang dipilih di timeline. Dipakai untuk `starttime` RTSP dan judul.
- `EXTRA_SEEK_OFFSET_MILLIS` — offset di dalam klip cache; diterapkan sekali saat event `Playing` pertama bila seek aktif.

Perilaku lama (`EXTRA_FILE_PATH` tanpa offset) tidak berubah. `EXTRA_START_MILLIS`/`EXTRA_END_MILLIS` tetap dipakai untuk batas durasi, tetapi nilai start kini boleh berasal dari jam yang dipilih.

## Alur

1. Tap **Playback** di navigasi.
2. Timeline langsung terisi untuk kanal + hari ini.
3. Geser tanggal / pilih kanal → search ulang otomatis. Pilih filter → timeline berganti warna tanpa request.
4. Tap bilah (atau `« »`) → jam terpilih.
5. LAN/VPN: RTSP URL dibuka dengan `starttime = jam terpilih`, `endtime = akhir klip`.
6. Cloud-only: klip diunduh memakai `playbackURI` lewat HTTP langsung; bila HTTP tidak terjangkau, tampil pesan jelas. Player lokal dibuka, di-seek ke offset, cache dihapus saat Activity mati.

## RAM & OTA

Satu decoder, satu file cache maksimal, tanpa dependency baru. Version/code, keystore, dan sertifikat tidak disentuh. Tidak ada release/tag/push.

## Pengujian

- Unit: `PlaybackTimelineTest` (ratio, celah, clamp, filter, step), `PlaybackSearchXmlTest` (playbackURI + eventType), `PlaybackDownloaderTest` (body `playbackURI` + gagal bila kosong), test lama tetap hijau.
- Build: `assembleDebug assembleRelease testDebugUnitTest`.
- Device: buka Playback, tanggal `23 Nov 2026`, kanal CAM-09; timeline harus menampilkan klip; tap segmen pertama → player dengan jam terpilih berjalan; kembali → timeline masih ada; `logcat` tanpa `FATAL EXCEPTION`.
- Sertifikat: `29b29d0ace7d7b782bbb3c25e4c5afb4b0804191fadbb8f18f902aacecc4339d`.
- Cloud-only: search bisa lewat cloud, unduhan tetap butuh HTTP langsung ke NVR. Tanpa rute HTTP, laporkan jujur; jangan klaim sukses dan jangan retry storm.

## Risiko & keputusan

- NVR mungkin tidak mengirim `eventType` → Person/Vehicle kosong. Itu disengaja daripada mengarang klasifikasi.
- Cloud response mungkin tanpa `playbackURI` → error jelas, tidak jatuh ke body lama yang sudah terbukti `400`.
- Tap di celah rekaman → klip terdekat, sama seperti lompat ke jam terdekat di Hik-Connect.
- `ui-ux-pro-max` CLI tidak dipakai karena Python tidak terpasang; keputusan visual memakai token warna existing (`accent`, `warning`, `stroke`, `surface`).

## Builder check

Reviewer buta sudah memeriksa dan mengembalikan 4 pertanyaan. Semua kuselesaikan sendiri karena keputusan sudah didelegasikan:

1. Rute download → direct HTTP, cloud hanya untuk metadata; bila HTTP mati, pesan jelas.
2. `« »` → pakai `startMillis` klip tujuan.
3. Overlap → `PERSON > VEHICLE > EVENT > CONTINUOUS`, tie by lebih pendek.
4. Drag → didukung, tapi playback baru dibuka saat `ACTION_UP`.

Tidak ada pertanyaan yang perlu kembali ke pengguna.
