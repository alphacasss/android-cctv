# In-app auto-update design

## Intent
Pemakai tidak perlu membuka GitHub untuk tahu ada rilis baru. Aplikasi mengecek rilis
publik, mengunduh APK saat aman (Wi-Fi), memverifikasi, lalu mengajak pemasangan.

## Goals
- Cek otomatis 1× per 24 jam saat aplikasi dibuka, plus tombol manual di Setelan.
- Hanya rilis stabil (`releases/latest`, bukan draft/prerelease) dari
  `alphacasss/android-cctv`.
- Unduh otomatis hanya saat Wi-Fi / jaringan tidak terukur; manual boleh pakai
  jaringan apa pun setelah dikonfirmasi dialog.
- Verifikasi SHA-256 memakai kolom `digest` aset GitHub sebelum instal.
- Notifikasi "Update siap dipasang"; pemasangan tetap butuh satu ketukan pengguna.

## Non-goals
- Silent install / root / Device Owner.
- Update delta, background self-update tanpa notifikasi, rollback otomatis.
- Prerelease channel, beta, atau multi-repo.

## Constraints
- minSdk 24, targetSdk 34, Kotlin 17, WorkManager 2.9.1 sudah ada.
- `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`,
  `FOREGROUND_SERVICE_DATA_SYNC` sudah ada di manifest.
- `FileProvider` authority `${applicationId}.files`, path dari `@xml/file_paths`.
- Versi saat ini `versionCode = 6`, `versionName = "1.1.4"`.
- Aset rilis bernama `cctv-monitor-<versi>-release.apk`, ukuran ~110 MB.

## Decisions (builder's calls)
- Perbandingan versi memakai `versionName` (`1.1.4` → 10104), bukan `versionCode`,
  karena tag GitHub memakai pola `vX.Y.Z`.
- `UpdateManager` memakai `AppPrefs.autoUpdate` (default true) dan
  `AppPrefs.lastUpdateCheckAt`.
- Unduh memakai `WorkManager` + `Constraints(UNMETERED)` untuk jalur otomatis;
  jalur manual memakai jaringan apa pun.
- APK disimpan di `getExternalFilesDir("Updates")`, diekspos lewat FileProvider.
- Notifikasi progres memakai `NotificationChannel` baru `cctv_update`.

## UX
Setelan → kartu "Pembaruan Aplikasi":
- sakelar "Cek pembaruan otomatis" (default on)
- teks status: versi terpasang / terakhir cek
- tombol "Cek pembaruan sekarang"

Tombol manual → dialog: versi baru, catatan rilis, ukuran, tombol "Unduh & pasang".
Saat unduh: notifikasi progres. Selesai: notifikasi "Update siap dipasang" → ketuk
→ dialog konfirmasi sistem pemasang (atau diarahkan ke izin install unknown apps).

## Risks / review focus
- Respons API tanpa aset `.apk` → jangan crash, laporkan "rilis tidak punya APK".
- `digest` bukan sha256 → tetap unduh, tapi jangan klaim terverifikasi.
- Izin `REQUEST_INSTALL_PACKAGES` tidak didapat → buka halaman pengaturan izin.
- Unduh terputus → WorkManager retry; file parsial ditimpa, tidak dipasang.
- Check otomatis saat cellular → hanya metadata kecil, tidak unduh APK.
