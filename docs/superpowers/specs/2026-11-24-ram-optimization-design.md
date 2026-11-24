# RAM Optimization Design

**Goal:** turunkan konsumsi RAM CCTV Monitor di semua perangkat tanpa mengurangi fitur atau jumlah kamera yang bisa dipantau.

## Intent

Aplikasi saat ini sudah membatasi pemutaran ke halaman yang terlihat, tetapi masih menyimpan beban memori yang tidak perlu: model TensorFlow Lite 4,6 MB yang tidak pernah dipanggil, bitmap JPEG besar untuk klasifikasi, semua halaman grid di-inflate di awal, bitmap notifikasi resolusi penuh, dan decoder VLC dengan jumlah thread auto.

## Goals

- Hapus alokasi mati: model TFLite yang tidak dipakai, bitmap `ARGB_8888` besar, `IntArray` penuh.
- Batasi decoding JPEG ke ukuran yang benar-benar dibutuhkan.
- Tunda inflasi halaman grid sampai halaman dibutuhkan RecyclerView.
- Batasi thread decoder VLC supaya 4 stream tidak membuat banyak frame buffer.
- Tetap kompatibel API 24–34.

## Non-goals

- Tidak mengurangi jumlah kamera, tile, atau frekuensi snapshot.
- Tidak menurunkan kualitas/fps stream secara eksplisit.
- Tidak membuat cabang perilaku per device atau per RAM.
- Tidak mengubah alur cloud recording, update, atau izin.

## Constraints

- `minSdk = 24`, `targetSdk = 34`, VLC `3.7.7`, WorkManager `2.9.1`.
- Unit test hanya JVM (`JUnit4`); tidak ada Robolectric, jadi logika Android dipisah ke fungsi murni.
- Perangkat uji tidak sedang terhubung; verifikasi HP dilakukan setelah tersedia.
- Perubahan harus bisa diuji dengan `assembleDebug testDebugUnitTest`.

## Approaches

### Dipilih: lean allocation + lazy UI

Buang dependensi/model yang mati, sampling bitmap, batasi thread decoder, dan inflate grid sesuai kebutuhan adapter. Dampaknya langsung ke heap dan native heap tanpa mengubah fitur.

### Ditolak: release LibVLC saat idle

`LibVLC` singleton memang menyimpan native heap, tetapi melepasnya saat aplikasi background memerlukan refcount lintas `MainActivity`/`FullscreenPlayerActivity` dan bisa menimbulkan delay saat kembali. Kompleksitasnya lebih besar daripada manfaatnya untuk perubahan ini.

### Ditolak: kurangi jumlah kamera aktif / turunkan stream

Ini mengubah fungsi inti aplikasi monitoring multi-kanal, jadi tidak dilakukan.

## Decisions

- `BitmapSampler.sampleSizeFor(sourceW, sourceH, maxDimension)` dipakai bersama.
- Klasifikasi objek: decode maksimal **160 px**, `RGB_565`, tanpa model TFLite; `isReady()` selalu `true`.
- Notifikasi gerak: decode maksimal **256 px**.
- `MotionMonitor.CLASSIFY_WIDTH`: **320** (dari 640).
- VLC: `--avcodec-threads=2` di opsi global `LibVLC`.
- Grid: halaman dibuat di `onCreateViewHolder`/`onBindViewHolder`, bukan semua di `buildCameraPages`.
- Dependensi `org.tensorflow:tensorflow-lite-task-vision:0.4.4` dan aset `efficientdet_lite0.tflite` dihapus.

## Left to builder

Tidak ada keputusan produk yang tersisa. Pilih nama internal dan struktur helper sesuai pola existing.
