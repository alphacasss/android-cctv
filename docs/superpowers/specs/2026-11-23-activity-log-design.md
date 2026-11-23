# Desain: Aktifitas & Status menjadi nyata

## Intent
Card “Aktifitas & Status” di beranda saat ini hanya teks statis. Card ini harus
menjadi ringkasan status lokal yang hidup dan catatan aksi kontrol yang nyata,
tanpa mengubah perilaku streaming maupun jaringan.

## Goals
- Mencatat setiap aksi kontrol yang lewat `ControlRepository` (tombol massal
  dan kontrol per kamera) dengan waktu, judul, dan detail singkat.
- Menyimpan maksimal 30 entri terbaru di `AppPrefs` (EncryptedSharedPreferences)
  agar tetap ada setelah aplikasi ditutup.
- Menampilkan di beranda: ringkasan status lokal (jumlah kamera dalam status
  mati dan jumlah backup yang belum dipulihkan) serta 4 entri terbaru.
- Menyegarkan tampilan saat beranda dibuka dan setelah aksi massal/sheet selesai.

## Non-goals / anti-goals
- Tidak membuat halaman riwayat penuh sekarang.
- Tidak melakukan polling status ke NVR/cloud; card memakai status lokal yang
  sudah dipakai aplikasi untuk memutar/stop kamera.
- Tidak mengubah paging (`CAMERAS_PER_PAGE = 4`, `GRID_COLUMNS = 2`), rute
  kontrol, atau logika SDK/relay.
- Tidak menyimpan kredensial, kode verifikasi, atau alamat lengkap yang sensitif
  ke dalam log.

## Constraints
- Layout sudah punya `tvActivityInfo` dan `tvActivityLog` di
  `activity_main.xml`; ID dipertahankan.
- `AppPrefs` sudah memakai JSON di `cctv-secure` dengan fallback `cctv-fallback`.
- Semua aksi kontrol sudah melewati `ControlRepository`, jadi itu titik catat
  yang paling lengkap.
- Teks memakai bahasa Indonesia seperti UI lainnya.

## Approaches yang dipertimbangkan
1. **Catat di `ControlRepository` (dipilih)** — satu titik untuk semua aksi,
   termasuk yang dipanggil dari `CameraControlSheet`. Tidak perlu mengubah sheet.
2. Catat dari UI (`MainActivity` + sheet) — perlu mengirim label aksi lewat
   FragmentResult dari banyak tombol dan rawan terlewat.
3. Turunkan dari perubahan flag — pasif, tidak tahu aksi apa yang menyebabkan
   perubahan.

## Desain
- `data class ActivityLogEntry(val at: Long, val title: String, val detail: String)`
  di package `id.cctv.monitor.data`.
- `AppPrefs`:
  - `fun activityLog(): List<ActivityLogEntry>` — baca/parse format teks ringan,
    tangguh terhadap baris rusak, kembalikan list kosong bila gagal.
  - `fun addActivity(title: String, detail: String)` — tambah entri baru dengan
    `System.currentTimeMillis()`, potong ke 30 entri, tulis ulang teks.
  - Format: satu entri per baris `waktu|judul|detail` dengan escaping untuk
    `|`, `\`, dan newline. Ini keputusan builder agar codec diuji di JVM tanpa
    stub Android (`org.json` tidak bisa dipakai di unit test lokal).
- `ControlRepository` memanggil `addActivity` setelah operasi selesai:
  matikan semua, nyalakan semua, RTSP off, restore, dan aksi per kamera
  (privacy mask, NVR, stream, day/night, motion, lampu).
  Judul memakai nama kamera (`cam.displayName`), detail memakai hasil
  berhasil/gagal dan rute bila relevan, tanpa kredensial.
- `MainActivity`:
  - `tvActivityInfo` → ringkasan status lokal, misalnya
    `8 kamera aktif • 2 dalam status mati • 1 backup belum dipulihkan`.
  - `tvActivityLog` → 4 entri terbaru, satu baris per entri:
    `14:22  MATIKAN SEMUA RTSP — Kanal 1–8 diputus`.
  - `renderActivityLog()` dipanggil dari `onResume()`, setelah aksi massal,
    setelah `onCameraChanged`, dan setelah restore.
  - Sub-card “Riwayat Akun” diganti “Riwayat Aktivitas”.

## Yang tersisa untuk builder
- Unit test untuk parsing/penyimpanan log dan deduksi batas 30 entri.
- Verifikasi build, test, dan perilaku di perangkat uji.
- Commit, push, bump versi ke `1.1.3`, dan rilis APK.
