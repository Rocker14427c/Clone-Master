package com.clonemaster.core.cloner.sign

import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.SecretKeySpec

/**
 * Clone signing key: a self-contained, on-device RSA key + self-signed X.509
 * certificate. No keytool/JVM or network needed – works on Android.
 *
 * The key is DERIVED deterministically from a fixed application passphrase
 * (PBKDF2-HMAC-SHA256, 100k iterations) so re-generation on the same device
 * yields the same key; callers should additionally cache the PKCS#8 bytes
 * after the first generation (app module caches in private storage).
 *
 * IMPORTANT SECURITY NOTE (handover rule): this key signs CLONED apps only
 * (like a debug/signing key for derived apps). It is NOT a credential, is
 * never committed, and is not used to sign Clone-Master itself.
 */
object SigningKey {

    const val PASSPHRASE = "CloneMaster.Clone.Signing.v1"
    private const val SALT = "com.clonemaster.clone-signing."
    private const val ITERATIONS = 100_000

    fun deriveSeed(): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(PASSPHRASE.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        var block = byteArrayOf(0, 0, 0, 1)
        var state = mac.doFinal((SALT + "seed").toByteArray(Charsets.UTF_8))
        repeat(ITERATIONS) {
            state = mac.doFinal(state)
            if (it == ITERATIONS - 1) block = state
        }
        return block
    }

    /** Generates the clone signing keypair (deterministic via PBKDF2-derived seed). */
    fun generateKeyPair(): KeyPair {
        val seed = deriveSeed()
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048, SecureRandom(seed))
        return kpg.generateKeyPair()
    }

    // ------------------------------------------------------------- X.509 DER

    /** Builds a self-signed X.509 v3 certificate (DER) for an RSA keypair. */
    fun buildSelfSignedCertificate(kp: KeyPair, subjectCn: String = "Clone-Master Clone Signer"): ByteArray {
        val notBefore = Instant.parse("2026-01-01T00:00:00Z")
        val notAfter = Instant.parse("2036-01-01T00:00:00Z")
        val utc = DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC)

        val serial = ByteArray(20).also { SecureRandom().nextBytes(it) }
        serial[0] = (serial[0].toInt() and 0x7F).toByte()

        val sigAlg = derSequence(
            derOid("1.2.840.113549.1.1.11") + // sha256WithRSAEncryption
                    derNull()
        )
        val rsaAlg = derSequence(
            derOid("1.2.840.113549.1.1.1") + // rsaEncryption
                    derNull()
        )
        val name = derName(
            "2.5.4.6" to "IN",
            "2.5.4.10" to "Clone-Master",
            "2.5.4.3" to subjectCn
        )
        val validity = derSequence(
            derUtcTime(utc.format(notBefore)) +
                    derUtcTime(utc.format(notAfter))
        )
        val spki = kp.public.encoded // SubjectPublicKeyInfo DER (X.509)

        val tbs = derSequence(
            derContextExplicit(0, derInteger(ByteArray(1) { 2 })) + // version v3
                    derInteger(serial) +
                    sigAlg +
                    name +
                    validity +
                    name + // subject == issuer (self-signed)
                    derRaw(spki)
        )

        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(kp.private)
        sig.update(tbs)
        val signature = sig.sign()

        return derSequence(
            tbs +
                    sigAlg +
                    derBitString(signature)
        )
    }

    // ASN.1 DER primitives ---------------------------------------------------

    private fun derRaw(b: ByteArray): ByteArray = b

    private fun derSequence(content: ByteArray): ByteArray =
        byteArrayOf(0x30) + length(content.size) + content

    private fun derSet(content: ByteArray): ByteArray =
        byteArrayOf(0x31) + length(content.size) + content

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derOid(oid: String): ByteArray {
        val parts = oid.split(".").map { it.toInt() }
        val body = ByteArrayOutputStream()
        body.write((parts[0] * 40 + parts[1]))
        for (i in 2 until parts.size) encodeBase128(body, parts[i])
        val b = body.toByteArray()
        return byteArrayOf(0x06) + length(b.size) + b
    }

    private fun encodeBase128(out: ByteArrayOutputStream, v: Int) {
        val tmp = ByteArrayOutputStream()
        var value = v
        tmp.write(value and 0x7F)
        value = value shr 7
        while (value > 0) {
            tmp.write((value and 0x7F) or 0x80)
            value = value shr 7
        }
        val reversed = tmp.toByteArray().reversedArray()
        reversed.forEach { out.write(it.toInt()) }
    }

    private fun derInteger(v: ByteArray): ByteArray =
        byteArrayOf(0x02) + length(v.size) + v

    private fun derBitString(bytes: ByteArray): ByteArray =
        byteArrayOf(0x03) + length(bytes.size + 1) + 0x00.toByte() + bytes

    private fun derUtcTime(s: String): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x17) + length(b.size) + b
    }

    private fun derContextExplicit(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf((0xA0 or tag).toByte()) + length(content.size) + content

    private fun derName(vararg rdns: Pair<String, String>): ByteArray {
        val content = rdns.map { (oid, value) ->
            derSet(
                derSequence(
                    derOid(oid) +
                            derPrintableString(value)
                )
            )
        }.reduce { a, b -> a + b }
        return derSequence(content)
    }

    private fun derPrintableString(s: String): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII)
        return byteArrayOf(0x13) + length(b.size) + b
    }

    private fun length(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), (n and 0xFF).toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), ((n shr 8) and 0xFF).toByte(), (n and 0xFF).toByte())
    }
}
