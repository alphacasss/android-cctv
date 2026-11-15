# CCTV Monitor (Android)

Aplikasi Android native untuk memantau 9 kamera CCTV Hikvision langsung dari
jaringan lokal, dan tetap bisa dipakai dari luar rumah lewat alamat publik yang
dideteksi otomatis, DDNS, atau VPN. Tanpa PC dan tanpa aplikasi perantara.

- Grid 3×3 live memakai **sub-stream 102**.
- Ketuk tile → layar penuh memakai **main-stream 101**.
- Tombol **⋮** pada tile membuka kontrol kamera: **HITAM / NORMAL**,
  **STOP / AKTIF stream**, **RTSP OFF / ON**, dan **KANAL NVR STOP / AKTIF**.
- Kredensial disimpan dengan **EncryptedSharedPreferences** (kunci di Android
  Keystore), bukan plaintext.
- Pemulihan otomatis lewat **WorkManager** bila aplikasi ditinggalkan saat ada
  kamera yang masih dimatikan aplikasi.
- **Akses luar jaringan otomatis** — satu alamat publik untuk semua kamera,
  dideteksi sendiri lewat HTTPS; rute **LOKAL/LUAR** dipilih otomatis sesuai
  jaringan ponsel.

Kontrol firewall tetap hanya ada di aplikasi PC (`cctv/CCTV-Control.ps1`);
aplikasi Android ini tidak menyentuh firewall.

## Kebutuhan

| Komponen | Versi |
|---|---|
| JDK | 17 |
| Android Studio | Narwhal atau lebih baru (AGP 8.10.1) |
| Android SDK Platform | 36 |
| Build Tools | 36.0.0 |
| Gradle | 8.11.1 (wrapper sudah disertakan) |
| Perangkat | Android 7.0+ (minSdk 24), ARM64/ARMv7/x86_64 |

libVLC `org.videolan.android:libvlc-all:3.7.7` diunduh otomatis oleh Gradle.
APK debug berukuran besar (puluhan MB) karena membawa semua ABI libVLC.

## Build

### Android Studio

1. Buka folder `android-cctv` (bukan folder induknya).
2. Tunggu Gradle sync selesai; Android Studio memakai JDK bawaan.
3. Jalankan target `app` ke perangkat/emulator, atau
   **Build → Build Bundle(s)/APK(s) → Build APK(s)**.

### Command line

```bat
cd android-cctv
gradlew.bat assembleDebug
```

Hasil: `app\build\outputs\apk\debug\app-debug.apk`.

Pasang ke perangkat yang terhubung:

```bat
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

Gradle butuh koneksi internet pada build pertama untuk mengunduh AGP,
dependensi, dan libVLC. Jika `JAVA_HOME` belum diatur, arahkan ke JDK 17, atau
buat `local.properties` berisi:

```properties
sdk.dir=C\:\\Users\\<nama>\\AppData\\Local\\Android\\Sdk
```

### Build release ber-signing

Keystore proyek ada di `keystore/cctv-release.jks` dengan kredensial di
`keystore.properties`. Keduanya di-ignore oleh Git — **simpan cadangan**,
karena tanpa file tersebut APK tidak bisa diperbarui di perangkat yang sama.

```bat
cd android-cctv
gradlew.bat assembleRelease
```

Hasil: `app\build\outputs\apk\release\app-release.apk`, sudah ditandatangani
dengan skema v2. Verifikasi:

```bat
%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0\apksigner.bat verify ^
  --print-certs app\build\outputs\apk\release\app-release.apk
```

Membuat keystore baru:

```bat
keytool -genkeypair -v -keystore keystore\cctv-release.jks -alias cctv ^
  -keyalg RSA -keysize 2048 -validity 10000
```

lalu isi `keystore.properties`:

```properties
storePassword=...
keyPassword=...
keyAlias=cctv
storeFile=keystore/cctv-release.jks
```

**Penting:** APK release ditandatangani dengan kunci berbeda dari APK debug,
jadi pemasangan di atas debug akan gagal
(`INSTALL_FAILED_UPDATE_INCOMPATIBLE`). Uninstall debug terlebih dahulu — semua
data aplikasi, **termasuk kredensial terenkripsi, akan hilang** dan harus diisi
ulang. Setelah itu update release berikutnya bisa memakai `adb install -r`
selama keystore-nya sama.

Salinan APK yang siap dibagikan ada di `dist/`:

- `CCTV-Monitor-1.1.1-release.apk` — versi ber-signing untuk pemakaian.
- `CCTV-Monitor-1.1.1-debug.apk` — versi debug untuk pengembangan; tidak bisa
  dipasang bersamaan dengan release karena `applicationId`-nya sama.

Update dari 1.0.0/1.1.0 memakai `adb install -r` biasa (kunci dan
`applicationId` sama); data serta kredensial tetap tersimpan.

## Konfigurasi pertama

Buka **Setelan** (ikon gerigi di toolbar):

1. **Kredensial default** — isi user dan password kamera (akun dengan izin
   konfigurasi ISAPI, biasanya `admin`). Kamera yang dikosongkan user/password-nya
   akan memakai nilai default ini.
2. **Kamera** — daftar default sudah terisi:
   - `CAM-01` … `CAM-08` → `192.168.100.20` … `192.168.100.27`, kanal 1
   - `CAM-09` → `192.168.100.34`, kanal 1, ditandai **NVR**
   Ketuk baris untuk mengubah nama, IP, kanal, status NVR, kredensial khusus,
   alamat/port luar khusus kamera, dan tombol **Tes ISAPI** untuk mendiagnosa
   koneksi.
3. **Akses luar jaringan otomatis** — aktif secara default. Aplikasi mendeteksi
   IP publik sendiri dan memakai pola port otomatis, jadi tidak perlu mengisi
   alamat di setiap kamera. Lihat bagian berikutnya.
4. **Pemulihan otomatis** — aktif secara default, jeda 120 detik setelah
   aplikasi ditinggalkan. Bisa dimatikan atau diubah (10–3600 detik).

Status tiap kamera yang diubah aplikasi disimpan sebagai flag, dan XML asli
kamera disimpan di penyimpanan internal (`filesDir/backups`) sebelum diubah.
Selama masih ada flag/backup, banner peringatan muncul di layar utama dengan
tombol **Pulihkan semua**.

## Akses dari luar jaringan

Aplikasi memilih rute untuk setiap kamera secara otomatis:

- **LOKAL** — dipakai bila ponsel punya antarmuka IPv4 yang satu subnet dengan
  IP kamera. Perbandingan memakai netmask sebenarnya, jadi tetap benar untuk
  Wi-Fi, Ethernet, maupun VPN.
- **LUAR** — dipakai bila ponsel tidak satu subnet; lalu lintas memakai alamat
  publik dan port luar. Kamera yang memakai setelan global ditandai chip
  **LUAR AUTO**, dan Setelan menulis "rute LUAR otomatis".

### Cara otomatis (tanpa mengisi IP)

Di **Setelan → Akses luar jaringan otomatis**:

| Field | Arti |
|---|---|
| Aktifkan otomatis saat di luar jaringan | Sumber alamat luar untuk semua kamera. Default aktif. |
| Alamat tetap / DDNS (opsional) | Isi hanya bila punya DDNS. Kosong = pakai IP publik yang dideteksi aplikasi. |
| Port HTTP dasar | Port luar untuk CAM-01; kamera lain memakai dasar + nomor kamera. Default 8080. |
| Port RTSP dasar | Sama, untuk RTSP. Default 8550. |
| Perbarui IP publik sekarang | Deteksi ulang paksa (biasanya otomatis tiap 5 menit). |

IP publik rumah dideteksi lewat HTTPS (`api.ipify.org`, `ifconfig.me`,
`icanhazip.com`), lalu disimpan di perangkat. **Deteksi hanya berjalan saat
ponsel tersambung ke jaringan rumah** (salah satu kamera berada di subnet yang
sama), karena di jaringan lain — misalnya data seluler — yang terbaca adalah IP
operator, bukan IP rumah. Deteksi berjalan saat aplikasi dibuka, saat tile
diputar, dan saat Setelan dibuka; alamat terakhir tetap dipakai bila sedang
offline.

Konsekuensinya: aplikasi perlu **sekali** dijalankan sambil tersambung ke Wi-Fi
rumah (biasanya otomatis saat pertama dipasang di rumah). Setelah itu alamat
tersimpan dan dipakai dari mana saja. Bila ponsel belum pernah berada di rumah,
isi DDNS sebagai gantinya. Satu-satunya pekerjaan manual lain adalah
**membuat aturan port forwarding sekali di router** — daftar port yang
dibutuhkan sudah ditampilkan di kartu tersebut.

Kamera tetap bisa memakai alamat/port khusus: kosongkan **Alamat luar** dan
**Port luar** pada dialog kamera untuk kembali ke mode otomatis. Isi hanya bila
ada kamera yang diteruskan ke port berbeda.

### Port yang perlu diteruskan

Pola `dasar + nomor kamera`:

| Kamera | IP lokal | Port HTTP luar → 80 | Port RTSP luar → 554 |
|---|---|---|---|
| CAM-01 | 192.168.100.20 | 8081 | 8551 |
| CAM-02 | 192.168.100.21 | 8082 | 8552 |
| … | … | … | … |
| CAM-08 | 192.168.100.27 | 8088 | 8558 |
| CAM-09 (NVR) | 192.168.100.34 | 8089 | 8559 |

NVR juga perlu diteruskan pada port HTTP/RTSP-nya sendiri agar kontrol kanal
dan stream CAM-09 tetap bekerja. Cukup TCP; RTSP di sini tidak memakai UDP.

Catatan penting:

- **Hairpin NAT** — menguji dari dalam rumah (chip **LUAR AUTO** atau "Selalu
  pakai alamat luar") bisa gagal walau akses dari luar nanti berhasil, karena
  banyak router tidak mendukung NAT loopback. Uji paling akurat memakai data
  seluler.
- **CGNAT** — bila ISP memberi IP bersama (antarmuka WAN di 100.64.0.0/10),
  port forwarding tidak akan bekerja. Gunakan VPN.
- **IP publik berubah** — tanpa DDNS, aplikasi menyesuaikan sendiri tetapi ada
  jeda hingga 5 menit; DDNS lebih cepat dan stabil.

### Rekomendasi keamanan

Membuka port kamera Hikvision ke internet berisiko: bot memindai port HTTP/RTSP
terus-menerus, dan HTTP/RTSP tidak terenkripsi. Urutan pilihan:

1. **VPN (paling aman)** — WireGuard/Tailscale di router atau mini PC. Ponsel
   masuk VPN dan alamat kamera tetap `192.168.100.x`; rute otomatis memilih
   LOKAL, jadi tidak ada port yang perlu dibuka.
2. **Port forwarding + alamat publik/DDNS** — pilih port luar yang tidak lazim
   (bukan 80/554), gunakan password kuat dan unik, batasi akses bila router
   mendukung, dan perbarui firmware kamera. Kredensial tersimpan terenkripsi di
   ponsel, tetapi perjalanan data tetap tidak terenkripsi.

Aplikasi tidak memakai UPnP dan tidak membuka port sendiri; semua aturan
forwarding tetap di router. Tidak ada server relay — koneksi langsung ke kamera.

## Arti kontrol

| Tombol | Yang dilakukan | Catatan |
|---|---|---|
| **HITAM** | Mengaktifkan privacy mask penuh lewat `/ISAPI/System/Video/inputs/channels/<kanal>/privacyMask` | Stream tetap jalan, gambar menjadi hitam. Region dibuat 4 titik sudut + `regionType=quadrilateral` (wajib untuk firmware DS-2CD… V5.11). Aman untuk kamera dan NVR. |
| **NORMAL** | Mengembalikan privacy mask dari backup XML | Backup dihapus setelah berhasil; bila kamera menolak, backup dipertahankan. |
| **STOP STREAM (sub 102)** | Mematikan kanal streaming `<kanal>02` | Sebagian firmware menolak `enabled=false`; aplikasi memverifikasi 4×, lalu rollback dan memberi pesan jelas. Tidak tersedia untuk NVR. |
| **AKTIFKAN STREAM** | Mengembalikan kanal streaming dari backup | — |
| **RTSP OFF** | Mematikan layanan RTSP lewat `/ISAPI/Security/adminAccesses` | **Semua** stream kamera berhenti, termasuk rekaman NVR, tetapi HTTP/ISAPI tetap hidup sehingga masih bisa dipulihkan. Diblokir untuk NVR karena akan mematikan semua kanal. |
| **RTSP ON** | Mengaktifkan kembali layanan RTSP, memverifikasi 4× | Bila kamera belum membaca status aktif, backup dipertahankan agar bisa dicoba lagi. |
| **KANAL STOP** | Mencoba mematikan kanal pada NVR (`/ISAPI/ContentMgmt/InputProxy/channels/<kanal>`) | Hanya untuk kamera bertanda NVR. Firmware NVR tertentu (mis. seri .34) menerima perintah tetapi mengabaikan `<enabled>false</enabled>`; aplikasi memverifikasi, rollback, dan menampilkan pesan agar rekaman dihentikan lewat menu NVR. |

Urutan pemulihan: RTSP → sub-stream → kanal NVR → privacy mask.

## Pemulihan otomatis

- Dijadwalkan saat aplikasi masuk background **dan** ada flag/backup tertinggal.
- Dibatalkan saat aplikasi kembali terlihat, supaya kamera tidak dipulihkan saat
  sedang dipakai.
- Tetap berjalan lewat WorkManager walau proses aplikasi dimatikan, selama
  perangkat punya jaringan.
- Jeda bawaan 120 detik adalah **minimum**; Android/WorkManager dapat
  menjalankannya beberapa menit lebih lambat (pengujian di Samsung A07 ±3,5
  menit) tergantung penghemat baterai. Status hasil terakhir terlihat di Setelan.
- Menu **⋮ → Pulihkan & keluar** memulihkan semua lalu menutup aplikasi.

## Keamanan

- User/password disimpan di `EncryptedSharedPreferences` dengan kunci
  AES256-GCM di Android Keystore. Jika perangkat bermasalah dengan Keystore,
  aplikasi memakai SharedPreferences biasa dan menampilkan peringatan merah di
  Setelan — pindahkan data ke perangkat lain bila ini terjadi.
- Password kamera juga muncul di URL RTSP yang diberikan ke libVLC di memori;
  tidak ditulis ke log.
- `usesCleartextTraffic=true` diperlukan untuk HTTP ISAPI dan RTSP di LAN.
  Jangan pakai aplikasi ini di jaringan yang tidak dipercaya.
- Hikvision dapat mengunci akun sementara setelah beberapa login gagal.
  Pastikan kredensial benar sebelum menekan **Tes ISAPI** berulang kali.

## Troubleshooting

**Tile hitam / "Gagal — mencoba lagi"**
- Pastikan ponsel berada di jaringan yang sama dengan kamera (192.168.100.0/24),
  atau — bila di luar rumah — akses luar otomatis aktif dan port forwarding
  sudah dibuat.
- Cek RTSP dengan aplikasi lain (mis. VLC) memakai URL
  `rtsp://user:pass@192.168.100.20:554/Streaming/Channels/102`.
- Buka **Setelan → kamera → Tes ISAPI**; bila 401, perbaiki kredensial; bila
  403, akun tidak punya izin konfigurasi.
- Aplikasi mencoba ulang otomatis setiap 5 detik selama tile ingin bermain.

**Di luar rumah kamera tetap "Gagal"**
- Setelan menampilkan **IP publik rumah** dan daftar port yang harus diteruskan.
  Bila "belum terdeteksi", jalankan aplikasi sekali saat tersambung ke Wi-Fi
  rumah, atau isi DDNS. Tombol **Perbarui IP publik sekarang** hanya bekerja di
  Wi-Fi rumah; di jaringan lain aplikasi menolak karena yang terbaca adalah IP
  operator.
- Buka **Setelan → kamera → Tes ISAPI**; baris pertama menunjukkan rute yang
  dipakai (LOKAL/LUAR) dan alamat yang dihubungi.
- Periksa aturan port forwarding: HTTP 8081–8089 → 80 dan RTSP 8551–8559 → 554.
- Dari dalam rumah, cara menguji port forwarding adalah menyalakan **Selalu
  pakai alamat luar** pada kamera. Bila gagal padahal aturan sudah benar,
  kemungkinan router tidak mendukung hairpin NAT — uji lewat data seluler.
- Bila ISP memakai CGNAT, port forwarding tidak akan bekerja; gunakan VPN.

**Tile menampilkan "Luar jaringan — IP publik rumah belum terdeteksi"**
Ponsel berada di luar subnet kamera, tetapi IP publik rumah belum pernah
terdeteksi. Jalankan aplikasi sekali saat tersambung ke Wi-Fi rumah, atau isi
DDNS di Setelan. Selama alamat belum ada, kamera hanya bisa diakses dari LAN.

**"Firmware kamera … tidak mengizinkan sub-stream 102 dimatikan"**
Firmware tersebut menerima PUT tetapi tetap menyalakan stream. Aplikasi sudah
rollback otomatis. Gunakan **HITAM** atau **RTSP OFF**.

**"Firmware NVR … tidak menyediakan tombol aktif/nonaktif kanal"**
Sebagian NVR mengabaikan `<enabled>false</enabled>` pada kanal IP. Aplikasi
memverifikasi dan rollback otomatis. Hentikan rekaman kanal lewat menu NVR
(Manajemen Kamera), lalu gunakan **HITAM** bila hanya ingin membutakan gambar.

**RTSP OFF tapi gambar masih jalan**
RTSP butuh beberapa detik untuk benar-benar berhenti, dan klien yang sudah
terhubung bisa bertahan sampai timeout. Tutup pemutar lain, tunggu, lalu cek
ulang. Pemulihan lewat **RTSP ON** atau **Pulihkan semua**.

**Kamera tidak bisa dipulihkan setelah RTSP OFF**
HTTP/ISAPI tetap hidup, jadi pemulihan seharusnya bisa. Bila gagal, reboot
kamera secara fisik, lalu jalankan **Pulihkan semua**. Backup XML tetap
tersimpan sampai pemulihan berhasil.

**Build gagal: `SDK location not found`**
Buat `local.properties` dengan `sdk.dir=...`, atau buka proyek di Android Studio
agar file itu dibuat otomatis.

**APK terpasang tetapi video tidak muncul**
Perangkat/emulator harus mendukung ABI yang disertakan (`arm64-v8a`,
`armeabi-v7a`, `x86_64`). Emulator x86 32-bit tidak didukung. Coba juga mematikan
HW decoder di kode (`media.setHWDecoderEnabled(false, false)`) bila ada kamera
dengan profil H.264 yang tidak umum.

**Kredensial hilang setelah update**
Jangan membersihkan data aplikasi atau mengubah keystore perangkat; data
terenkripsi tidak bisa dibuka tanpa kunci tersebut.

## Struktur proyek

```
android-cctv/
├─ gradle/wrapper/            Gradle 8.11.1 (jar + properties)
├─ gradlew, gradlew.bat
├─ keystore/cctv-release.jks  Kunci signing release (jangan dibagikan)
├─ keystore.properties        Kredensial keystore (jangan dibagikan)
├─ dist/                      APK siap pasang (debug & release)
├─ app/
│  ├─ build.gradle.kts        compileSdk 36, minSdk 24, libVLC 3.7.7
│  ├─ proguard-rules.pro      keep org.videolan.**
│  └─ src/main/
│     ├─ AndroidManifest.xml
│     ├─ java/id/cctv/monitor/
│     │  ├─ CctvApp.kt              lifecycle background → WorkManager
│     │  ├─ MainActivity.kt         grid 3×3, banner, menu
│     │  ├─ FullscreenPlayerActivity.kt
│     │  ├─ SettingsActivity.kt
│     │  ├─ control/ControlRepository.kt   ISAPI + backup/verify/rollback
│     │  ├─ data/                   AppPrefs, Camera, BackupStore, RemoteSettings
│     │  ├─ net/                    IsapiClient, DigestAuth, CameraXml, Endpoint, PublicIp
│     │  ├─ player/PlayerManager.kt LibVLC + MediaPlayer per tile
│     │  ├─ ui/                     bottom sheet kontrol, aspect ratio
│     │  └─ work/                   RestoreScheduler, RestoreAllWorker
│     └─ res/                       layout, drawable, menu, values
└─ README.md
```

## Batasan

- Hanya untuk kamera Hikvision yang mendukung ISAPI dan RTSP.
- Kontrol firewall tidak tersedia di Android.
- RTSP OFF pada NVR sengaja diblokir.
- Akses dari luar jaringan memerlukan port forwarding (alamat publik/DDNS
  otomatis) atau VPN; aplikasi tidak membuka port sendiri dan tidak menyediakan
  server relay. HTTP/RTSP tetap tidak terenkripsi.
- Belum ada penyimpanan rekaman, PTZ, atau audio; fokus aplikasi adalah
  monitoring live dan kontrol status kamera.
