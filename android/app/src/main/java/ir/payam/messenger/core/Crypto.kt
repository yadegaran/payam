package ir.payam.messenger.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** vodozemac (Olm) via JNI — see crypto-rs/src/jni_bridge.rs. Stateless: pickles in, pickles out. */
object Olm {
    init { System.loadLibrary("payamcrypto") }
    @JvmStatic external fun newAccount(key: ByteArray): String
    /** [ed25519, curve25519] */
    @JvmStatic external fun identity(acc: String, key: ByteArray): Array<String>
    @JvmStatic external fun sign(acc: String, key: ByteArray, msg: ByteArray): String
    @JvmStatic external fun verify(ed: String, msg: ByteArray, sig: String): Boolean
    /** [account, id1, key1, id2, key2, …] */
    @JvmStatic external fun genOneTimeKeys(acc: String, key: ByteArray, n: Int): Array<String>
    /** [account, id, key] */
    @JvmStatic external fun genFallbackKey(acc: String, key: ByteArray): Array<String>
    @JvmStatic external fun outbound(acc: String, key: ByteArray, curve: String, otk: String): String
    /** [account, session, plaintextBase64] */
    @JvmStatic external fun inbound(acc: String, key: ByteArray, curve: String, ct: String): Array<String>
    @JvmStatic external fun sessionMatches(sess: String, key: ByteArray, ct: String): Boolean
    /** [session, type, ciphertext] */
    @JvmStatic external fun encrypt(sess: String, key: ByteArray, pt: ByteArray): Array<String>
    /** [session, plaintextBase64] */
    @JvmStatic external fun decrypt(sess: String, key: ByteArray, type: Int, ct: String): Array<String>
}

private val rng = SecureRandom()

fun randomBytes(n: Int) = ByteArray(n).also { rng.nextBytes(it) }
fun b64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
fun sha256(vararg parts: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").run { parts.forEach { update(it) }; digest() }

object Ids {
    private val MOD = BigInteger.valueOf(36).pow(8)

    /** Must match server.js usernameOf(): first 8 base36 chars of SHA-256(ed ‖ curve). */
    fun usernameOf(ed: ByteArray, curve: ByteArray): String =
        BigInteger(1, sha256(ed, curve).copyOfRange(0, 8)).mod(MOD).toString(36).padStart(8, '0')

    fun usernameOf(ed: String, curve: String) = usernameOf(unb64(ed), unb64(curve))

    /** Full identity fingerprint, carried in invite links/QR after '#'. */
    fun fingerprint(ed: String, curve: String): String = hex(sha256(unb64(ed), unb64(curve)))

    /** 60-digit number both people see identically — compare it in person. */
    fun safetyNumber(fpA: String, fpB: String): String {
        val (a, b) = listOf(fpA, fpB).sorted()
        val h = sha256((a + b).toByteArray())
        return (0 until 12).joinToString(" ") { i ->
            val v = ((h[2 * i].toInt() and 0xff) shl 16) or ((h[2 * i + 1].toInt() and 0xff) shl 8) or (h[24 + i % 8].toInt() and 0xff)
            (v % 100000).toString().padStart(5, '0')
        }
    }

    fun newId(): String = hex(randomBytes(16))
}

/** AES key inside Android Keystore (never leaves secure hardware) wraps our app secrets. */
object Vault {
    private const val ALIAS = "payam_master"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as SecretKey?)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }.generateKey()
    }

    fun seal(plain: ByteArray): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return b64(c.iv + c.doFinal(plain))
    }

    fun open(sealed: String): ByteArray {
        val d = unb64(sealed)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, d, 0, 12))
        return c.doFinal(d, 12, d.size - 12)
    }
}

/** Password-based encryption for backups and the app PIN (pure JVM, unit-tested). */
object Pw {
    private val MAGIC = "PYB1".toByteArray()

    fun derive(pass: CharArray, salt: ByteArray, iters: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass, salt, iters, 256)).encoded

    fun encrypt(pass: CharArray, data: ByteArray, iters: Int = 600_000): ByteArray {
        val salt = randomBytes(16)
        val iv = randomBytes(12)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive(pass, salt, iters), "AES"), GCMParameterSpec(128, iv))
        return MAGIC + intBytes(iters) + salt + iv + c.doFinal(data)
    }

    /** Throws on wrong password (GCM tag mismatch) or a foreign file. */
    fun decrypt(pass: CharArray, blob: ByteArray): ByteArray {
        require(blob.size > 36 && blob.copyOfRange(0, 4).contentEquals(MAGIC)) { "not a backup file" }
        val iters = ((blob[4].toInt() and 0xff) shl 24) or ((blob[5].toInt() and 0xff) shl 16) or
            ((blob[6].toInt() and 0xff) shl 8) or (blob[7].toInt() and 0xff)
        val salt = blob.copyOfRange(8, 24)
        val iv = blob.copyOfRange(24, 36)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(derive(pass, salt, iters), "AES"), GCMParameterSpec(128, iv))
        return c.doFinal(blob, 36, blob.size - 36)
    }

    private fun intBytes(v: Int) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}

/**
 * Attachment encryption: AES-256-CTR + HMAC-SHA256 (encrypt-then-MAC), streamed so
 * 100 MB videos never sit in memory. Wire format: iv(16) ‖ ciphertext ‖ mac(32).
 * The 64-byte key (32 AES ‖ 32 HMAC) travels inside the Olm-encrypted message.
 */
object Attach {
    private const val BUF = 64 * 1024

    fun encrypt(src: InputStream, dst: File): ByteArray {
        val key = randomBytes(64)
        val iv = randomBytes(16)
        val c = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, 0, 32, "AES"), IvParameterSpec(iv))
        }
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, 32, 32, "HmacSHA256")) }
        src.use { input ->
            dst.outputStream().buffered().use { out ->
                out.write(iv); mac.update(iv)
                val buf = ByteArray(BUF)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    c.update(buf, 0, n)?.let { out.write(it); mac.update(it) }
                }
                c.doFinal()?.let { out.write(it); mac.update(it) }
                out.write(mac.doFinal())
            }
        }
        return key
    }

    /** Verifies the MAC over the whole file first; only then decrypts. Throws SecurityException. */
    fun decrypt(src: File, key: ByteArray, dst: File) {
        require(key.size == 64)
        val len = src.length()
        if (len < 48) throw SecurityException("short attachment")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, 32, 32, "HmacSHA256")) }
        val tag = ByteArray(32)
        src.inputStream().buffered().use { input ->
            var left = len - 32
            val buf = ByteArray(BUF)
            while (left > 0) {
                val n = input.read(buf, 0, minOf(BUF.toLong(), left).toInt())
                if (n < 0) throw SecurityException("truncated")
                mac.update(buf, 0, n); left -= n
            }
            var off = 0
            while (off < 32) { val n = input.read(tag, off, 32 - off); if (n < 0) break; off += n }
        }
        if (!MessageDigest.isEqual(mac.doFinal(), tag)) throw SecurityException("attachment MAC mismatch")

        src.inputStream().buffered().use { input ->
            val iv = ByteArray(16).also { var o = 0; while (o < 16) o += input.read(it, o, 16 - o) }
            val c = Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, 0, 32, "AES"), IvParameterSpec(iv))
            }
            dst.outputStream().buffered().use { out ->
                var left = len - 48
                val buf = ByteArray(BUF)
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(BUF.toLong(), left).toInt())
                    if (n < 0) break
                    c.update(buf, 0, n)?.let { out.write(it) }
                    left -= n
                }
                c.doFinal()?.let { out.write(it) }
            }
        }
    }
}
