# RAM optimization brainstorm — progress

- [x] Recon: pahami alokasi memori saat ini (VLC players, bitmap, TFLite, adapter, service).
  - Temuan: hanya halaman aktif yang diputar (4 tile), VLC singleton native heap tetap hidup, grid halaman lain tetap di-inflate.
  - TFLite model 4,6 MB + 2 thread dimuat sekali; `ObjectFilter.detect` belum memanggil detector, tapi alokasi bitmap ARGB_8888 + IntArray penuh per classify.
  - `AwayService` decode bitmap notifikasi tanpa sampling; `MotionMonitor` simpan frame grayscale 64x36 per kamera.
- [x] First message: pantulkan permintaan + temuan, tanya satu pertanyaan terbuka soal "kenapa".
- [ ] Lay out approaches: 2–3 opsi nyata + rekomendasi, minta pilihan.
- [ ] Tulis design doc (Approaches + intent + goals/non-goals + constraints + keputusan).
- [ ] Approval user terhadap design doc.
- [ ] Invoke `writing-plans`, lalu implementasi lewat workflow normal.
