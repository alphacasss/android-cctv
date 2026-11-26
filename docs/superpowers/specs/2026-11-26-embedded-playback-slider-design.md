# Embedded playback + slider (26 Nov 2026)

Status: **usul, menunggu approve**.

## Masalah

`PlaybackActivity` sekarang cuma timeline. Tap timeline → buka `RecordingPlayerActivity` terpisah. User mau: video di atas, slider di bawah, dan video ikut pindah ke jam yang dipilih.

## Keputusan

- **B — cache + seek** dipilih. Satu klip diunduh sekali lewat `playbackURI`, lalu slider jadi seek file lokal.
- Tetap **satu `MediaPlayer` VLC** via `SharedVlc`. Tanpa decoder kedua, tanpa grid.
- RTSP playback tetap dipakai `HistoryActivity`. `PlaybackActivity` tidak membuka `RecordingPlayerActivity` lagi.

## Layout

```text
[ toolbar + kanal + tanggal ]
[ video playback ]      <- VLCVideoLayout, overlay judul/loading, play/pause
[ All ][ Event ][ Person ][ Vehicle ]
[ 00:00 ▓▓░░▓▓░░ 24:00 ]  <- slider 24 jam, draggable
[ « ]    07:12    [ » ]
[ hint / status ]
```

`PlaybackTimelineView` tetap dipakai. Tidak bikin view baru.

## Perilaku slider

1. `ACTION_DOWN` / `ACTION_MOVE`: ubah penanda + label jam. Belum putar.
2. `ACTION_UP`: pilih klip pakai aturan lama (`PERSON > VEHICLE > EVENT > CONTINUOUS`, interval `[start, end)`).
3. Klip yang sama sudah di-cache & player aktif: `MediaPlayer.time = offset` langsung.
4. Klip beda: unduh klip itu sekali, ganti media, lalu seek ke `selectedMillis - clip.startMillis`.
5. `« »` pakai `PlaybackTimeline.stepState`, lalu jalur yang sama seperti no. 4.

`ACTION_CANCEL`: batal, tanpa seek. RTSP tidak di-seek.

## Cache & lifecycle

- Satu file cache aktif. Ganti klip/tanggal/kanal/filter atau keluar layar → stop player, hapus cache lama.
- `PlaybackDownloader` dipakai apa adanya. Cloud tetap metadata-only; binary tetap HTTP langsung ke NVR.
- Busy ditampilkan dengan overlay/progress. Tanpa polling, tanpa coroutine looping.

## Non-goal

- Live + playback dua video bareng, PiP, grid 4 kamera, decoder kedua.
- Seek RTSP mulus, speed control, snap/audio/download di player.
- Dependency baru, SDK native, ML/on-device.

## Terima jika

- Satu layar menampilkan video + slider, tanpa pindah activity saat scrub.
- Geser slider mengubah jam; lepas jari memindahkan video ke jam itu.
- Ganti klip tetap satu decoder, tidak ada crash/black frame ganda.
- Build `assembleDebug assembleRelease testDebugUnitTest` hijau dan verifikasi di `R9RYB03HG7E`.

Estimasi: 1,5–2 jam implementasi + test, ~20 menit verifikasi perangkat.
