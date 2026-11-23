package id.cctv.monitor.cloud.stream

import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Amplop ECDH relay stream Hik-Connect.
 *
 * Satu sesi memakai kunci ECDH P-256 (tanpa KDF) sebagai master key, satu
 * session key acak 32 byte, payload ChaCha20, dan autentikasi HMAC-SHA256.
 * Paket tipe 1 membawa session key (dibungkus AES-ECB master key), paket
 * tipe 2 hanya membawa data.
 *
 * Kunci privat sengaja dipakai bersama untuk seluruh proses (seperti aplikasi
 * resmi): perangkat membungkus kunci stream untuk publik key yang dikirim saat
 * permintaan VTM, bukan yang dipakai koneksi VTDU.
 */
internal class EcdhEnvelope(
    peerPublicKey: ByteArray? = null,
    private val keyVersion: Int = 1
) {

    private val publicKey: ByteArray = sharedKeyPair.public.encoded
    private val master: ByteArray =
        if (peerPublicKey != null) exchange(peerPublicKey) else ByteArray(0)
    private var session: ByteArray = ByteArray(32).also { secureRandom.nextBytes(it) }
    private var sequence = 1
    private var handshaked = false

    /**
     * Bungkus [data] menjadi paket amplop (tipe 1 saat handshake, lalu tipe 2).
     *
     * Keepalive mengirim dari thread lain sementara thread baca mendekripsi
     * media, jadi [session]/[sequence] harus diakses bergantian.
     */
    @Synchronized
    fun encrypt(data: ByteArray): ByteArray {
        if (!handshaked) {
            val head = ByteArrayOutputStream()
            head.write(0x24)
            head.write(0x01)
            head.write(0x00)
            head.write((data.size ushr 8) and 0xFF)
            head.write(data.size and 0xFF)
            head.write(1)
            head.write(keyVersion and 0xFF)
            head.writeInt(1)
            head.write(aesEcb(master, session, encrypt = true))
            head.write(publicKey)
            val plain = head.toByteArray()
            val ciphertext = ChaCha20.xor(session, 1, data)
            return plain + ciphertext + mac(master, plain, ciphertext)
        }
        sequence++
        val head = ByteArrayOutputStream()
        head.write(0x24)
        head.write(0x02)
        head.write(0x00)
        head.write((data.size ushr 8) and 0xFF)
        head.write(data.size and 0xFF)
        head.write(0)
        head.write(0)
        head.writeInt(sequence)
        val plain = head.toByteArray()
        val ciphertext = ChaCha20.xor(session, sequence, data)
        return plain + ciphertext + mac(session, plain, ciphertext)
    }

    /** Buka paket amplop dari relay. */
    @Synchronized
    fun decrypt(packageBytes: ByteArray): ByteArray {
        require(packageBytes.size >= 11) { "Paket ECDH terpotong." }
        val kind = packageBytes[1].toInt() and 0xFF
        val ext = packageBytes[2].toInt() and 0xFF
        val length = ((packageBytes[ext + 3].toInt() and 0xFF) shl 8) or
            (packageBytes[ext + 4].toInt() and 0xFF)
        val seq = readInt(packageBytes, ext + 7)
        val macKey: ByteArray
        val sessionKey: ByteArray
        val head: Int
        when (kind) {
            1 -> {
                head = ext + 134
                require(packageBytes.size >= head) { "Paket handshake ECDH terpotong." }
                macKey = exchange(packageBytes.copyOfRange(ext + 43, head))
                sessionKey = aesEcb(
                    macKey,
                    packageBytes.copyOfRange(ext + 11, ext + 43),
                    encrypt = false
                )
            }

            2 -> {
                head = ext + 11
                macKey = session
                sessionKey = session
            }

            else -> throw IllegalArgumentException("Tipe paket ECDH tidak dikenal: $kind")
        }
        require(packageBytes.size >= head + length + 32) { "Paket ECDH tidak lengkap." }
        val ciphertext = packageBytes.copyOfRange(head, head + length)
        val received = packageBytes.copyOfRange(head + length, head + length + 32)
        val expected = mac(macKey, packageBytes.copyOfRange(0, head), ciphertext)
        if (!MessageDigest.isEqual(received, expected)) {
            throw IllegalArgumentException("Autentikasi paket ECDH gagal.")
        }
        session = sessionKey
        handshaked = true
        return ChaCha20.xor(sessionKey, seq, ciphertext)
    }

    private fun mac(key: ByteArray, head: ByteArray, ciphertext: ByteArray): ByteArray {
        val message = crc32(head).toString() + crc32(ciphertext).toString()
        val raw = message.toByteArray(Charsets.US_ASCII)
        val padded = ByteArray(8)
        System.arraycopy(raw, 0, padded, 0, minOf(8, raw.size))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(padded)
    }

    private fun crc32(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    private fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES")
        )
        val padded = if (data.size % 16 == 0) data else {
            ByteArray((data.size / 16 + 1) * 16).also {
                System.arraycopy(data, 0, it, 0, data.size)
            }
        }
        val out = cipher.doFinal(padded)
        return if (!encrypt && padded.size != data.size) out.copyOf(data.size) else out
    }

    companion object {

        private val secureRandom = SecureRandom()

        /** Satu pasangan kunci untuk seluruh proses, sama seperti aplikasi resmi. */
        private val sharedKeyPair: KeyPair by lazy {
            KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
        }

        private fun exchange(peerPublicKey: ByteArray): ByteArray {
            val peer: PublicKey = KeyFactory.getInstance("EC")
                .generatePublic(X509EncodedKeySpec(peerPublicKey))
            val agreement = KeyAgreement.getInstance("ECDH")
            agreement.init(sharedKeyPair.private)
            agreement.doPhase(peer, true)
            return agreement.generateSecret()
        }

        private fun ByteArrayOutputStream.writeInt(value: Int) {
            write((value ushr 24) and 0xFF)
            write((value ushr 16) and 0xFF)
            write((value ushr 8) and 0xFF)
            write(value and 0xFF)
        }

        private fun readInt(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 24) or
                ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
    }
}
