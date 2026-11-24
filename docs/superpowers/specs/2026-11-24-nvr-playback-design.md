# Playback Rekaman NVR / Hik-Connect — Desain

Status: **DRAFT — menunggu approval**
Tanggal: 2026-11-24

## Keputusan pengguna

- Jalur utama: **Streaming ISAPI** (cari rekaman per tanggal, putar via RTSP).
- Sumber: **NVR Hikvision** dan **Cloud Hik-Connect**.
- Jika HP hanya punya cloud (NVR tak terjangkau langsung): **download fallback** ke cache, putar lokal, hapus setelah selesai.
- Tidak ada TFLite / on-device ML, dependency baru, decoder grid, atau polling.
- OTA, keystore, dan signing cert tidak berubah.

## Tujuan

Halaman **Playback**: pilih kanal NVR + tanggal → daftar rekaman → putar satu video.
Satu decoder aktif. Kode lama "putar file di HP" tidak dijadikan target lagi.

## Non-goal

- Tidak mengubah Riwayat / file lokal.
- Tidak mendukung rekaman microSD kamera di versi ini (hanya kanal NVR).
- Tidak mengubah `versionCode` / `versionName` / release.
- Tidak menambah SDK native.

## Arsitektur

```
PlaybackActivity
  ├─ PlaybackSearchRepository
  │    ├─ IsapiClient.post()        → LAN/VPN/DDNS  (utama)
  │    └─ CloudIsapiClient.post()   → cloud passthrough (fallback metadata)
  ├─ PlaybackTransportResolver (murni)
  │    ├─ STREAM_RTSP   → RecordingPlayerActivity(EXTRA_MEDIA_URI)
  │    └─ DOWNLOAD_CACHE→ RecordingPlayerActivity(EXTRA_FILE_PATH)
  └─ PlaybackDownloader (hanya cloud-only)
```

`RecordingPlayerActivity` diperluas dengan `EXTRA_MEDIA_URI`. Tanpa extra itu,
perilaku lama (file lokal) tetap sama. Satu kelas player, satu `SharedVlc`.

## Komponen

1. **`PlaybackActivity.kt` + layout**
   - Spinner kanal: hanya `Camera.isNvr && enabled`.
   - `DatePickerDialog` framework (tema Material3 sudah ada; tanpa dependency baru).
   - Tombol **Cari**, daftar hasil berupa `LinearLayout` dalam `ScrollView`
     (maks. 100 entri; tanpa RecyclerView baru).
   - Baris: `07:00–07:05 • Nama Kamera`.
   - Kosong: pesan "Tidak ada rekaman pada tanggal ini."

2. **`PlaybackSearchRepository.kt`**
   - Request `CMSearchDescription` XML dengan `trackID = camera.streamId(true)`
     dan rentang `startTime`/`endTime` format `yyyyMMdd'T'HHmmss'Z'` (UTC).
   - `POST /ISAPI/ContentMgmt/search/`; fallback `POST /ISAPI/ContentMgmt/search`
     bila 404.
   - `IsapiClient.post()` dan `CloudIsapiClient.post()` ditambahkan kecil-kecil.
   - Parse XML memakai `XmlPullParser` (framework).
   - Urutan hasil diurutkan berdasarkan `startMillis`.

3. **`PlaybackTransportResolver.kt` (murni, diuji)**
   - Input: `directReachable: Boolean`, `cloudAvailable: Boolean`.
   - `true, *` → `STREAM_RTSP`.
   - `false, true` → `DOWNLOAD_CACHE`.
   - `false, false` → `ERROR_NO_ROUTE` dengan pesan jelas.

4. **`PlaybackRtspUrl.kt` (murni, diuji)**
   - `rtsp://<user>:<pass>@<host>:<port>/Streaming/tracks/<streamId>?starttime=<utc>&endtime=<utc>`
   - Kredensial di-encode. Port memakai `AppPrefs.rtspPort(camera.id)` /
     `remoteRtspPort`, bukan angka hardcode.

5. **`PlaybackDownloader.kt`**
   - Hanya dipakai pada `DOWNLOAD_CACHE`.
   - Satu permintaan download, satu file di `cacheDir/playback/`.
   - Dihapus saat player `onDestroy`. Tidak retry berulang.

6. **Perubahan player**
   - `RecordingPlayerActivity`: terima `EXTRA_MEDIA_URI`.
   - RTSP: play/pause + waktu; **seek dimatikan** (seek RTSP replay tidak reliable).
   - File lokal hasil download: seek tetap aktif seperti sekarang.

## Alur UI

1. Tap **Playback** di bottom navigation.
2. Pilih kanal, pilih tanggal (default hari ini), tekan **Cari**.
3. Daftar jam rekaman muncul.
4. Tap entri → resolver memilih jalur.
5. Player tampil: judul kanal + jam, play/pause, indikator waktu.

## Probe perangkat sebelum final

- Endpoint search: konfirmasi `CMSearchDescription` diterima firmware NVR yang dipakai.
- Endpoint download: konfirmasi `/ISAPI/ContentMgmt/download` tersedia.
  Bila tidak, cloud-only menampilkan pesan:
  "Perangkat tidak menyediakan unduhan rekaman. Hubungkan HP ke LAN/VPN."
- Tidak ada klaim "cloud-only putar" sebelum probe ini lolos.

## RAM & OTA

- Satu `MediaPlayer` VLC aktif. `MainActivity.onStop()` sudah memanggil
  `playerManager.stopAll()` sebelum player playback dibuka.
- Satu file cache maksimal, dihapus saat selesai.
- Tanpa dependency baru. `versionCode`, `versionName`, keystore, dan sertifikat
  signing tidak disentuh.
- Activity baru `android:exported="false"`.

## Pengujian

- Unit: parse XML 0/1/banyak entri + urutan; resolver 3 kasus; URL RTSP dengan
  karakter khusus pada password; konversi tanggal lokal → UTC.
- Build: `assembleDebug assembleRelease testDebugUnitTest`.
- Device: cari tanggal yang memang ada rekaman, tap putar, screenshot + logcat.
- Verifikasi sertifikat signing tetap
  `29b29d0ace7d7b782bbb3c25e4c5afb4b0804191fadbb8f18f902aacecc4339d`.
- Belum release / tag / push tanpa perintah lanjutan.

## Risiko

- Firmware NVR memakai variasi endpoint search. Mitigasi: dua path + error jelas.
- Cloud passthrough bisa memotong deklarasi XML; pola penangan sudah ada di
  `CloudIsapiClient.asXmlDocument()`.
- Download cloud bisa tidak tersedia. Mitigasi: pesan jelas, bukan retry storm.
- Seek RTSP tidak dijanjikan; sengaja dimatikan.

## Approval

Balas **"setuju playback"** bila desain ini disetujui. Setelah itu saya invoke
`writing-plans`, lalu implementasi. Belum ada kode baru untuk arah ini yang saya ubah.
