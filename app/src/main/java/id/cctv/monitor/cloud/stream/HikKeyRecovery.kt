package id.cctv.monitor.cloud.stream

import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Pemulihan kode verifikasi perangkat dari VPS H.265 yang terenkripsi.
 *
 * Sebagian DVR lama menjawab `statusCode 4 (Invalid Operation)` untuk
 * `/ISAPI/System/Network/EZVIZ`, sehingga cloud tidak bisa memberikan kode
 * verifikasi. Kode perangkat selalu 6 huruf besar dan kunci AES-128 adalah
 * kode tersebut di-pad nol, jadi kode dapat dipulihkan dari satu blok VPS:
 * plaintext VPS yang sah selalu diawali `0c 01` dan diikuti
 * `vps_reserved_0xffff_16bits` (`ff ff`).
 *
 * Pemulihan berjalan sekali per serial (single-flight), lalu hasilnya
 * disimpan ke preferensi oleh pemanggil. Tidak ada kode yang ditulis ke
 * source maupun log.
 */
internal object HikKeyRecovery {

    private const val TAG = "HikKeyRecovery"
    private const val CODE_LENGTH = 6
    private const val ALPHABET = 26
    private const val TOTAL = 308_915_776L // 26^6
    private const val MAX_THREADS = 8

    private val sbox = IntArray(256)
    private val invSbox = IntArray(256)
    private val mul9 = IntArray(256)
    private val mul11 = IntArray(256)
    private val mul13 = IntArray(256)
    private val mul14 = IntArray(256)
    private val rcon = intArrayOf(
        0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1B, 0x36
    )

    private val cache = HashMap<String, String>()
    private val flights = HashMap<String, CompletableFuture<String?>>()

    /**
     * Kode yang sudah terbukti untuk satu serial. Semua kanal NVR biasanya
     * memakai kode yang sama, jadi hasil pencarian pertama dipakai ulang untuk
     * blok VPS kanal lain tanpa mengulang brute force 26^6.
     */
    private val bySerial = HashMap<String, String>()

    /**
     * Satu thread driver supaya pencarian berat tidak menahan loop baca
     * stream; pencarian itu sendiri memakai beberapa thread pekerja.
     */
    private val runner = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hik-key-recovery").apply { isDaemon = true }
    }

    init {
        for (x in 0 until 256) {
            var inverse = 0
            for (y in 1 until 256) {
                if (gmul(x, y) == 1) {
                    inverse = y
                    break
                }
            }
            var value = inverse
            for (i in 1..4) {
                value = value xor (((inverse shl i) or (inverse ushr (8 - i))) and 0xFF)
            }
            sbox[x] = (value xor 0x63) and 0xFF
        }
        for (x in 0 until 256) invSbox[sbox[x]] = x
        for (x in 0 until 256) {
            mul9[x] = gmul(x, 9)
            mul11[x] = gmul(x, 11)
            mul13[x] = gmul(x, 13)
            mul14[x] = gmul(x, 14)
        }
    }

    /**
     * Pulihkan kode dari NAL VPS terenkripsi. Mengembalikan null bila NAL
     * bukan VPS atau kode tidak ditemukan. Pemanggilan paralel untuk serial
     * yang sama berbagi satu proses pencarian.
     */
    fun recover(serial: String, vps: ByteArray): String? {
        if (!isVps(vps)) return null
        // Kode yang sudah terbukti untuk serial ini dicoba lebih dulu; kanal
        // lain pada NVR yang sama memakai kode yang sama.
        knownCode(serial, vps)?.let { return it }
        // Cache per blok cipher, bukan per serial: tiap kanal bisa memakai
        // kode berbeda walau berada di NVR yang sama.
        val cacheKey = vps.copyOfRange(2, 18).joinToString("") { "%02x".format(it) }
        val future: CompletableFuture<String?>
        val owner: Boolean
        synchronized(this) {
            cache[cacheKey]?.let { return it }
            val running = flights[cacheKey]
            if (running == null) {
                future = CompletableFuture()
                flights[cacheKey] = future
                owner = true
            } else {
                future = running
                owner = false
            }
        }
        if (!owner) return runCatching { future.get() }.getOrNull()

        val code = try {
            bruteForce(serial, vps)
        } catch (t: Throwable) {
            Log.w(TAG, "Pemulihan kode gagal: ${t.message}")
            null
        }
        synchronized(this) {
            if (code != null) {
                cache[cacheKey] = code
                bySerial[serial] = code
            }
            flights.remove(cacheKey)
        }
        future.complete(code)
        return code
    }

    /** Kode yang sudah diketahui untuk [serial], bila cocok dengan [vps] ini. */
    private fun knownCode(serial: String, vps: ByteArray): String? {
        if (serial.isBlank()) return null
        val code = synchronized(this) { bySerial[serial] } ?: return null
        return code.takeIf { validate(it, vps) }
    }

    /**
     * Versi non-blokir: [onCode] dipanggil di thread latar setelah kode
     * pulih. Permintaan berulang untuk serial yang sama berbagi hasil cache.
     */
    fun recoverAsync(serial: String, vps: ByteArray, onCode: (String) -> Unit) {
        if (!isVps(vps)) return
        runner.execute {
            val code = recover(serial, vps)
            if (!code.isNullOrBlank()) onCode(code)
        }
    }

    /** Kunci AES-128 dari kode verifikasi (kode ASCII di-pad nol). */
    fun keyOf(code: String): ByteArray {
        val key = ByteArray(16)
        // Jangan ubah huruf besar/kecil; kunci memakai kode apa adanya.
        val raw = code.trim().toByteArray(Charsets.US_ASCII)
        raw.copyInto(key, endIndex = minOf(raw.size, key.size))
        return key
    }

    fun isVps(nal: ByteArray): Boolean =
        nal.size >= 18 &&
            (nal[0].toInt() and 0x7E) == 0x40 &&
            (nal[1].toInt() and 0x07) == 1

    private fun bruteForce(serial: String, vps: ByteArray): String? {
        val cipher = vps.copyOfRange(2, 18)
        val threads = Runtime.getRuntime().availableProcessors()
            .coerceIn(1, MAX_THREADS)
        Log.d(TAG, "Memulihkan kode verifikasi $serial ($threads thread)")
        val found = AtomicReference<String?>(null)
        val stop = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(threads)
        val chunk = (TOTAL / (threads * 8L)).coerceAtLeast(1L)
        var from = 0L
        val started = System.currentTimeMillis()
        while (from < TOTAL) {
            val lo = from
            val hi = minOf(TOTAL, from + chunk)
            pool.execute { search(lo, hi, cipher, vps, found, stop) }
            from = hi
        }
        pool.shutdown()
        while (!pool.awaitTermination(250, TimeUnit.MILLISECONDS)) {
            if (found.get() != null) break
        }
        pool.shutdownNow()
        val code = found.get()
        if (code != null) {
            Log.d(TAG, "Kode verifikasi dipulihkan dalam ${System.currentTimeMillis() - started} ms")
        } else {
            Log.w(TAG, "Kode verifikasi tidak ditemukan")
        }
        return code
    }

    private fun search(
        lo: Long,
        hi: Long,
        cipher: ByteArray,
        vps: ByteArray,
        found: AtomicReference<String?>,
        stop: AtomicBoolean
    ) {
        val key = ByteArray(16)
        val schedule = IntArray(44)
        val first = IntArray(16)
        val second = IntArray(16)
        var n = lo
        while (n < hi) {
            if (stop.get()) return
            var value = n
            for (i in CODE_LENGTH - 1 downTo 0) {
                key[i] = ('A'.code + (value % ALPHABET)).toByte()
                value /= ALPHABET
            }
            expandKey(key, schedule)
            decryptBlock(schedule, cipher, first, second)
            // Awalan VPS yang sah: 0c 01 ff ff. Byte kedua (0x01) penting —
            // tanpa itu tebakan acak yang menghasilkan 0c ?? ff ff ikut lolos.
            if (first[0] == 0x0C && first[1] == 0x01 &&
                first[2] == 0xFF && first[3] == 0xFF
            ) {
                val code = decode(n)
                if (validate(code, vps)) {
                    found.compareAndSet(null, code)
                    stop.set(true)
                    return
                }
            }
            n++
        }
    }

    private fun decode(n: Long): String {
        val chars = CharArray(CODE_LENGTH)
        var value = n
        for (i in CODE_LENGTH - 1 downTo 0) {
            chars[i] = ('A'.code + (value % ALPHABET)).toChar()
            value /= ALPHABET
        }
        return String(chars)
    }

    private fun validate(code: String, vps: ByteArray): Boolean {
        val plain = HikEncryption.decryptNal(vps, true, 2, keyOf(code))
        return AnnexB.isPlainVps(plain)
    }

    private fun expandKey(key: ByteArray, w: IntArray) {
        for (i in 0 until 4) {
            w[i] = ((key[4 * i].toInt() and 0xFF) shl 24) or
                ((key[4 * i + 1].toInt() and 0xFF) shl 16) or
                ((key[4 * i + 2].toInt() and 0xFF) shl 8) or
                (key[4 * i + 3].toInt() and 0xFF)
        }
        for (i in 4 until 44) {
            var temp = w[i - 1]
            if (i % 4 == 0) {
                temp = (temp shl 8) or (temp ushr 24)
                temp = (sbox[(temp ushr 24) and 0xFF] shl 24) or
                    (sbox[(temp ushr 16) and 0xFF] shl 16) or
                    (sbox[(temp ushr 8) and 0xFF] shl 8) or
                    sbox[temp and 0xFF]
                temp = temp xor (rcon[i / 4 - 1] shl 24)
            }
            w[i] = w[i - 4] xor temp
        }
    }

    /** Dekripsi satu blok; hasil akhir ditulis ke [s]. */
    private fun decryptBlock(w: IntArray, input: ByteArray, s: IntArray, t: IntArray) {
        for (c in 0 until 4) {
            val word = w[40 + c]
            for (r in 0 until 4) {
                s[4 * c + r] = (input[4 * c + r].toInt() and 0xFF) xor
                    ((word ushr (24 - 8 * r)) and 0xFF)
            }
        }
        for (round in 9 downTo 1) {
            for (r in 0 until 4) {
                for (c in 0 until 4) {
                    t[4 * c + r] = invSbox[s[4 * ((c - r + 4) % 4) + r]]
                }
            }
            for (c in 0 until 4) {
                val word = w[4 * round + c]
                for (r in 0 until 4) {
                    t[4 * c + r] = t[4 * c + r] xor
                        ((word ushr (24 - 8 * r)) and 0xFF)
                }
            }
            for (c in 0 until 4) {
                val a0 = t[4 * c]
                val a1 = t[4 * c + 1]
                val a2 = t[4 * c + 2]
                val a3 = t[4 * c + 3]
                s[4 * c] = mul14[a0] xor mul11[a1] xor mul13[a2] xor mul9[a3]
                s[4 * c + 1] = mul9[a0] xor mul14[a1] xor mul11[a2] xor mul13[a3]
                s[4 * c + 2] = mul13[a0] xor mul9[a1] xor mul14[a2] xor mul11[a3]
                s[4 * c + 3] = mul11[a0] xor mul13[a1] xor mul9[a2] xor mul14[a3]
            }
        }
        for (r in 0 until 4) {
            for (c in 0 until 4) {
                t[4 * c + r] = invSbox[s[4 * ((c - r + 4) % 4) + r]]
            }
        }
        for (c in 0 until 4) {
            val word = w[c]
            for (r in 0 until 4) {
                s[4 * c + r] = t[4 * c + r] xor
                    ((word ushr (24 - 8 * r)) and 0xFF)
            }
        }
    }

    private fun gmul(a: Int, b: Int): Int {
        var x = a
        var y = b
        var result = 0
        while (y != 0) {
            if (y and 1 != 0) result = result xor x
            x = ((x shl 1) xor (if (x and 0x80 != 0) 0x1B else 0)) and 0xFF
            y = y ushr 1
        }
        return result and 0xFF
    }
}
