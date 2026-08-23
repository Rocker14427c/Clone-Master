package com.clonemaster.core.cloner.sign

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/**
 * APK Signature Scheme v2 signer + verifier (pure Kotlin, no external tools).
 *
 * Implements the v2 signing block layout: digests over the APK content
 * (1 MiB chunks, each prefixed with 0xA5), RSASSA-PKCS1-v1.5-SHA256 signatures
 * (algorithm ID 0x0103), and the signing block container
 * (ID 0x7109871a, magic "APK Sig Block 42").
 *
 * Content-digest model (identical to apksig/V2): three sections are digested
 * as one stream – [everything before the signing block][central directory]
 * [EOCD with its 4-byte central-dir-offset field replaced by the signing block
 * offset]. Chunk boundaries fall at 1 MiB across section borders.
 *
 * v2-signed APKs are accepted since Android 7.0 (API 24) – matching
 * Clone-Master's minSdk. JAR (v1) signatures are not produced; devices below
 * Android 7.0 are out of scope.
 */
object V2Scheme {

    const val SIGNING_BLOCK_ID = 0x7109871A
    const val MAGIC = "APK Sig Block 42"
    const val ALG_RSA_PKCS1_SHA256 = 0x0103
    const val CHUNK_SIZE = 1 shl 20 // 1 MiB

    // ---- apksig-exact binary helpers -------------------------------------
    // encodeAsSequenceOfLengthPrefixedElements: [u32 len][elem]... (NO total field)
    fun lpElements(vararg elements: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (e in elements) {
            putLeInt(out, e.size)
            out.write(e)
        }
        return out.toByteArray()
    }

    // encodeAsSequenceOfLengthPrefixedPairsOfIntAndLengthPrefixedBytes:
    // per pair: [u32 (8+len)][u32 alg][u32 len][bytes] (NO total field)
    fun lpPairs(pairs: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((alg, value) in pairs) {
            putLeInt(out, 8 + value.size)
            putLeInt(out, alg)
            putLeInt(out, value.size)
            out.write(value)
        }
        return out.toByteArray()
    }

    class V2Signer(val keyPair: KeyPair, val certDer: ByteArray) {

        /** Signs the given unsigned APK; returns final APK bytes (block inserted before CD). */
        fun sign(unsignedApk: ByteArray): ByteArray {
            val eocd = findEocd(unsignedApk)
            val cdU = le32(unsignedApk, eocd + 16) // cd offset in the unsigned apk == block start
            val cdEnd = eocd
            // Memory: digest over SLICES of the unsigned APK instead of copying
            // prefix/cd (a whole extra APK copy in RAM for large apps).
            val eocdBytes = unsignedApk.copyOfRange(cdEnd, unsignedApk.size)

            // Payload size is independent of digest/signature CONTENT (fixed sizes);
            // measure with placeholders to derive the block length.
            val dummyPayload = buildV2Payload(
                buildSignedData(ByteArray(32), certDer),
                ByteArray(256),
                keyPair.public.encoded
            )
            val pairBytes = 8 + 4 + dummyPayload.size        // [u64 len][u32 id][payload]
            val blockL = pairBytes + 8 + 16                  // + [u64 L][magic]
            val blockTotal = 8 + blockL                     // the first [u64 L]

            // Digest: EOCD cd-offset field replaced with the SIGNING BLOCK offset (= cdU).
            val contentDigest = computeChunkedDigest(
                Slice(unsignedApk, 0, cdU),
                Slice(unsignedApk, cdU, cdEnd - cdU),
                run { val p = patchedEocd(eocdBytes, cdU.toLong()); Slice(p, 0, p.size) }
            )
            val signedData = buildSignedData(contentDigest, certDer)
            val sig = Signature.getInstance("SHA256withRSA")
            sig.initSign(keyPair.private)
            sig.update(signedData)
            val signature = sig.sign()
            val payload = buildV2Payload(signedData, signature, keyPair.public.encoded)
            val block = buildBlock(payload, blockL)

            // Memory: assemble into an exact-size array (a growing buffer peaks
            // at ~3x the APK size; this peaks at 1x).
            val finalBytes = ByteArray(unsignedApk.size + block.size)
            System.arraycopy(unsignedApk, 0, finalBytes, 0, cdU)
            System.arraycopy(block, 0, finalBytes, cdU, block.size)
            System.arraycopy(unsignedApk, cdU, finalBytes, cdU + block.size, unsignedApk.size - cdU)
            // Central directory really starts after the block:
            val finalEocd = findEocd(finalBytes)
            val newCdOffset = (cdU.toLong() + blockTotal).toInt()
            putLe32(finalBytes, finalEocd + 16, newCdOffset)
            return finalBytes
        }


        private fun buildSignedData(contentDigest: ByteArray, cert: ByteArray): ByteArray {
            // apksig layout: lp of [digestsSeq][certSeq][attrs(empty)][reserved(empty)]
            val digestsSeq = lpPairs(listOf(ALG_RSA_PKCS1_SHA256 to contentDigest))
            val certSeq = lpElements(cert)
            return lpElements(digestsSeq, certSeq, ByteArray(0), ByteArray(0))
        }

        private fun buildV2Payload(signedData: ByteArray, signature: ByteArray, publicKeySpki: ByteArray): ByteArray {
            val signatures = lpPairs(listOf(ALG_RSA_PKCS1_SHA256 to signature))
            val signer = lpElements(signedData, signatures, publicKeySpki)
            // payload: lp of a sequence of signers (single signer -> two nested length prefixes)
            return lpElements(lpElements(signer))
        }

        private fun buildBlock(payload: ByteArray, blockL: Int): ByteArray {
            val out = ByteArrayOutputStream()
            putLe64(out, blockL.toLong())
            putLe64(out, (payload.size + 4).toLong())
            putLe32(out, SIGNING_BLOCK_ID)
            out.write(payload)
            putLe64(out, blockL.toLong())
            out.write(MAGIC.toByteArray(Charsets.US_ASCII))
            val b = out.toByteArray()
            return b
        }
    }

    // ---------------------------------------------------------------- verify

    data class VerifyResult(val verified: Boolean, val signerCert: X509Certificate?, val message: String)

    fun verify(apk: ByteArray): VerifyResult {
        return try {
            val eocd = findEocd(apk)
            val cdOffset = le32(apk, eocd + 16)
            val magicOff = cdOffset - 16
            require(magicOff >= 0) { "no signing block" }
            val magic = String(apk, magicOff, 16, Charsets.US_ASCII)
            require(magic == MAGIC) { "v2 signing block not found" }
            val blockL = le64(apk, magicOff - 8)
            // layout: [u64 L][pairs][u64 L][magic] -> blockStart + 8 + L == cdOffset
            val blockStart = magicOff - blockL.toInt() + 8
            require(blockStart >= 0) { "bad signing block length" }
            // sanity: first length field equals last
            require(le64(apk, blockStart) == blockL) { "signing block length mismatch" }

            val pairEnd = blockStart + 8 + blockL.toInt() - 24
            var p = blockStart + 8
            var v2Payload: ByteArray? = null
            while (p < pairEnd) {
                val pairSize = le64(apk, p).toInt()
                val id = le32(apk, p + 8)
                if (id == SIGNING_BLOCK_ID) v2Payload = apk.copyOfRange(p + 12, p + 12 + (pairSize - 4))
                p += 8 + pairSize
            }
            requireNotNull(v2Payload) { "v2 signature block not found" }

            // payload = lp( signers-seq ); signers-seq = lp(signer) xN
            val signerSeq = sliceAt(v2Payload, 0)
            val signer = sliceAt(signerSeq, 0)

            // signer = lp(signedData) || lp(signatures) || lp(publicKey)
            val signedData = sliceAt(signer, 0)
            val signatures = sliceAt(signer, 1)

            // signatures = pairs: [u32 (8+len)][alg][u32 len][bytes]..., iterated to end
            var contentDigest: ByteArray? = null
            var sigBytes: ByteArray? = null
            var sigPos = 0
            while (sigPos < signatures.size) {
                val pLen = le32(signatures, sigPos)
                val alg = le32(signatures, sigPos + 4)
                val dl = le32(signatures, sigPos + 8)
                val dd = signatures.copyOfRange(sigPos + 12, sigPos + 12 + dl)
                sigPos += 4 + pLen
                if (alg == ALG_RSA_PKCS1_SHA256) sigBytes = dd
            }
            requireNotNull(sigBytes) { "no RSA-PKCS1-SHA256 signature found" }

            // signedData = lp(digestsSeq || certSeq || attrs || reserved)
            val digestsSeq = sliceAt(signedData, 0)
            val certSeq = sliceAt(signedData, 1)
            var digestPos = 0
            while (digestPos < digestsSeq.size) {
                val pLen = le32(digestsSeq, digestPos)
                val alg = le32(digestsSeq, digestPos + 4)
                val dl = le32(digestsSeq, digestPos + 8)
                val dd = digestsSeq.copyOfRange(digestPos + 12, digestPos + 12 + dl)
                digestPos += 4 + pLen
                if (alg == ALG_RSA_PKCS1_SHA256) contentDigest = dd
            }
            requireNotNull(contentDigest) { "no RSA-PKCS1-SHA256 content digest found" }

            var certPos = 0
            val certLen = le32(certSeq, certPos)
            val certDer = certSeq.copyOfRange(certPos + 4, certPos + 4 + certLen)
            val cf = CertificateFactory.getInstance("X.509")
            val cert = cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate

            // recompute content digest with the EOCD field patched to blockStart
            val eocdBytes = apk.copyOfRange(eocd, apk.size)
            val expected = computeChunkedDigest(
                Slice(apk, 0, blockStart),
                Slice(apk, cdOffset, eocd - cdOffset),
                run { val p = patchedEocd(eocdBytes, blockStart.toLong()); Slice(p, 0, p.size) }
            )
            val digestOk = MessageDigest.isEqual(expected, contentDigest)

            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(cert.publicKey)
            verifier.update(signedData)
            val sigOk = verifier.verify(sigBytes)

            when {
                !digestOk -> VerifyResult(false, cert, "content digest mismatch")
                !sigOk -> VerifyResult(false, cert, "signature verification failed")
                else -> VerifyResult(true, cert, "v2 signature verified")
            }
        } catch (e: Exception) {
            VerifyResult(false, null, "verify error: ${e.message}")
        }
    }

    /** Memory-friendly view over a byte source: data + offset + length (no copy). */
    data class Slice(val data: ByteArray, val off: Int, val len: Int)

    /** apksig CHUNKED_SHA256: per-segment 1 MiB chunks, each hashed over 0xA5 + u32LE(len) + bytes;
     *  final digest = SHA-256 over 0x5A + u32LE(chunkCount) + concat(chunkDigests). */
    fun computeChunkedDigest(vararg segments: ByteArray): ByteArray =
        computeChunkedDigest(*segments.map { Slice(it, 0, it.size) }.toTypedArray())

    /** Slice-based overload: identical digest, but no segment copies (large-APK memory). */
    fun computeChunkedDigest(vararg segments: Slice): ByteArray {
        var chunkCount = 0
        // chunkDigests: 32 bytes per chunk; even a 1 GiB APK is just ~32 KiB here.
        val chunkDigests = ByteArrayOutputStream()
        for (segment in segments) {
            var pos = segment.off
            val end = segment.off + segment.len
            while (pos < end) {
                val n = minOf(CHUNK_SIZE, end - pos)
                val d = MessageDigest.getInstance("SHA-256")
                d.update(0xA5.toByte())
                putLe32(d, n)
                d.update(segment.data, pos, n)
                val cdg = d.digest()
                chunkDigests.write(cdg, 0, cdg.size)
                chunkCount++
                pos += n
            }
        }
        val out = MessageDigest.getInstance("SHA-256")
        out.update(0x5A.toByte())
        putLe32(out, chunkCount)
        out.update(chunkDigests.toByteArray())
        return out.digest()
    }

    fun patchedEocd(eocd: ByteArray, cdOffset: Long): ByteArray {
        val eocdPatched = eocd.copyOf()
        putLe32(eocdPatched, 16, cdOffset.toInt()) // 4-byte field only
        return eocdPatched
    }

    /** MessageDigest extension: little-endian u32. */
    private fun putLe32(d: MessageDigest, v: Int) {
        d.update((v and 0xFF).toByte()); d.update(((v shr 8) and 0xFF).toByte())
        d.update(((v shr 16) and 0xFF).toByte()); d.update(((v shr 24) and 0xFF).toByte())
    }

    // ---------------------------------------------------------------- utils

    /** Returns the [u32 len][bytes] slice at position `index` within `container`. */
    fun sliceAt(container: ByteArray, index: Int): ByteArray {
        var pos = 0
        var current = 0
        while (current <= index) {
            if (pos + 4 > container.size) error("slice index $index out of bounds")
            val len = le32(container, pos)
            if (current == index) {
                if (pos + 4 + len > container.size) error("slice length out of bounds")
                return container.copyOfRange(pos + 4, pos + 4 + len)
            }
            pos += 4 + len
            current++
        }
        error("slice index $index out of bounds")
    }

    fun findEocd(b: ByteArray): Int {
        val scanStart = (b.size - 65557).coerceAtLeast(0)
        for (i in b.size - 22 downTo scanStart) {
            if (le32(b, i) == 0x06054b50) return i
        }
        error("EOCD not found")
    }

    fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte(); b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    fun putLe64(b: ByteArray, o: Int, v: Long) {
        for (i in 0 until 8) b[o + i] = ((v shr (8 * i)) and 0xFF).toByte()
    }

    fun putLeInt(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF)
    }

    fun putLe32(o: ByteArrayOutputStream, v: Int) = putLeInt(o, v)

    fun putLe64(o: ByteArrayOutputStream, v: Long) {
        for (i in 0 until 8) o.write(((v shr (8 * i)) and 0xFF).toInt())
    }

    fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun le64(b: ByteArray, o: Int): Long {
        var v = 0L
        for (i in 7 downTo 0) v = (v shl 8) or (b[o + i].toLong() and 0xFF)
        return v
    }
}
