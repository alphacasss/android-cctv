# Recording playback design — 2026-11-24

## Tujuan
Ketuk video di **Riwayat** memutar rekaman di dalam aplikasi. Tanpa app luar.

## Bukan bagian ini
- Tidak ada dependency baru.
- Tidak ada TFLite / analisis video.
- Tidak menyalakan grid kamera saat playback.
- Tidak mengubah OTA, signing, atau rute cloud.

## Pendekatan
1. `HistoryPlaybackRouter` (murni, bisa di-test) memutuskan:
   - video + file ada → `PLAY_IN_APP`
   - foto → `SHOW_PHOTO`
   - video + file hilang → `FILE_MISSING`
2. `RecordingPlayerActivity` baru, non-exported, menerima path absolut lewat extra.
   - Memakai `SharedVlc` yang sudah ada. Satu `MediaPlayer`, satu `VLCVideoLayout`.
   - Kontrol minimum: putar/jeda, seek, waktu berjalan/durasi, tutup.
   - `onStop` menjeda; `onDestroy` melepas view + media player.
3. `HistoryActivity.playVideo` menggantikan `ACTION_VIEW` eksternal dengan membuka `RecordingPlayerActivity`.
4. Manifest mendaftarkan activity baru sebagai `exported="false"`.

## RAM & baterai
- Hanya satu decoder aktif saat layar playback terbuka.
- `MainActivity.onStop` sudah memanggil `playerManager.stopAll()`, jadi grid tidak ikut berjalan.
- Tidak membuat `LibVLC` baru; Singleton existing dipakai ulang.
- Tidak ada polling, SDK native, atau pekerjaan latar belakang baru.

## Risiko
- `.ts` bisa punya indeks seek terbatas; VLC tetap memutar dari awal dengan benar.
- Opsi global `--no-audio` tetap berlaku; rekaman CCTV tidak mengandalkan audio.

## Verifikasi
- Unit test routing (3 kasus).
- `assembleDebug assembleRelease testDebugUnitTest`.
- Cek manual di perangkat: Riwayat → video → playback in-app → rotasi → tutup.
