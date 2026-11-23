# Desain: UI Polish CCTV Monitor — 2026-11-23

## Tujuan
1. Perbaiki tombol CLOUD di sheet kontrol kamera yang tulisannya tak terlihat.
2. Seragamkan kontras teks/tombol, tipografi, dan spacing di seluruh layar.
3. Rapikan beranda: kartu status header, kartu grid, chip status, aksi massal.
4. Rapikan sheet kontrol: grouping tombol, ikon, dan warna status yang konsisten.

## Akar masalah (terverifikasi)
`app/src/main/res/layout/sheet_camera_control.xml` → `btnBindCloud`:
- `android:textColor="@color/accent"` (#34D399)
- `app:backgroundTint="@color/accent"` (#34D399)

Warna teks = warna background. Saat kamera terikat, `CameraControlSheet.refreshUi()` mengganti teks menjadi
`CLOUD: <nama device> — ubah`, sehingga persis keluhan "tulisan tombol cloud : camera warnanya sama".

Pola yang benar di aplikasi ini: tombol filled `@color/accent` memakai `android:textColor="@color/black"`
(`btnSaveCred`, `btnSaveRemote`, `btnAway`, `btnTurnOnAll`, dll.).

## Lingkup perubahan

### 1. Fix kontras (wajib)
- `btnBindCloud`: `textColor="@color/black"`, tambah `app:icon="@drawable/ic_wifi"` + `iconTint="@color/black"`.
- Audit semua `MaterialButton`:
  - Filled (accent/danger/warning/info) → teks & ikon hitam.
  - Outlined → teks mengikuti warna status (accent / warning / danger), stroke senada.
  - Text button → teks accent.

### 2. Token UI baru
- Tambah `res/values/dimens.xml`: spacing `4/8/12/16dp`, radius kartu `16dp`, radius chip `10dp`.
- Tambah warna `on_accent = @color/black` bila diperlukan (tetap pakai `@color/black` untuk konsistensi dengan kode yang ada).

### 3. Beranda (`activity_main.xml`)
- Kartu status: ikon kamera + judul + subtitle + indikator dot, `tvTotalCameras` sebagai chip ringan.
- Tombol `Pulihkan semua` jadi outlined warning (tidak mendominasi), tetap pakai id yang sama.
- Kartu grid: judul dan tombol "Muat ulang" sejajar, indikator halaman lebih jelas, petunjuk dipadatkan.
- Kartu Aksi Bulk + Mode Away: ikon di tiap tombol, warna status konsisten, spacing memakai dimens.

### 4. Sheet kontrol (`sheet_camera_control.xml`)
- Tambah pemisah grup (TextView tanpa id, aman untuk binding):
  - **Tampilan**: HITAM, MODE MALAM, NIGHT BOOST, LAMPU PUTIH
  - **Stream & Rekam**: STOP STREAM, RTSP, KANAL STOP, FOTO, REKAM
  - **Sensor & Sistem**: GERAK DETECT, TES SENSOR, CEK KEMAMPUAN, muat ulang
  - **Cloud**: BIND KE CLOUD
- Ikon `app:icon` pada tombol utama (`ic_eye`, `ic_video`, `ic_power`, `ic_camera`, `ic_refresh`, `ic_wifi`).
- Warna: danger untuk off/stop, warning untuk RTSP, accent untuk aksi aktif.

### 5. Yang TIDAK diubah
- Logika streaming, rute kontrol, cloud passthrough, dan workaround InputProxy.
- `CAMERAS_PER_PAGE = 4`, `GRID_COLUMNS = 2`.
- Semua id view yang dipakai Kotlin (perubahan hanya atribut tampilan + TextView tanpa id).

## Verifikasi
- `.\gradlew.bat assembleDebug --console=plain` → BUILD SUCCESSFUL (3m 42s).
- Install ke `R9RYB03HG7E` → `Success`.
- `uiautomator dump` beranda → `8 kamera Hikvision di jaringan rumah` dan `kamera terhubung` hadir.
- `uiautomator dump` sheet kontrol setelah scroll → `CLOUD: Camera 01 — ubah` muncul; grup `TAMPILAN & SENSOR`, `STREAM & REKAM`, `SISTEM` juga muncul.
- Pengecekan sumber: `btnBindCloud` = `textColor="@color/black"` + `backgroundTint="@color/accent"` + `icon="@drawable/ic_wifi"`; tidak ada lagi `textColor="@color/accent"` pada tombol tersebut.
- Scan seluruh layout: 0 `MaterialButton` dengan `textColor` == `backgroundTint`.
- Logcat: 0 `FATAL` / `Fatal signal` / `UnsatisfiedLinkError`.
- Release APK / GitHub push **tidak** dilakukan (opsi yang dipilih hanya debug + verifikasi perangkat).
