package com.clonemaster.core.cloner.dex

import com.clonemaster.core.cloner.CloneDiag
import com.clonemaster.core.cloner.CloneRequest
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * DEX string-table patcher.
 *
 * Rewrites exact string literals inside classes.dex (and multidex files) that the
 * manifest transformation changed elsewhere: provider authorities, and where the
 * length permits, hard-coded original package names / "pkg."-prefixed strings.
 *
 * Technique: in-place, footprint-preserving string rewrite inside the existing
 * string data section (the same technique established APK patching tooling uses):
 *   - the replacement must fit inside the original string_data_item footprint
 *     (new uleb128 length prefix + new MUTF-8 bytes <= original item size);
 *   - the remainder of the item is NUL-padded so every string_ids offset stays valid
 *     and nothing else in the file shifts;
 *   - SHA-1 signature and Adler-32 checksum are recomputed.
 *
 * If a required replacement (e.g. an authority) does not fit, it is reported as an
 * error so the build FAILS CLEARLY instead of shipping an APK with inconsistent
 * package/authority strings – the root cause of the device
 * "There's a problem with the app file" failure.
 */
class DexStringPatcher {

    data class Result(
        val replacements: Int,
        val notFitted: List<String>,
        val skippedInvalidUtf8: Int
    )

    fun patch(dex: ByteArray, request: CloneRequest, diag: CloneDiag): Result {
        require(dex.size >= 112) { "DEX file too small (${dex.size} bytes)" }
        val magic = String(dex, 0, 4, Charsets.ISO_8859_1)
        require(magic == "dex\n") { "Not a DEX file (magic=$magic)" }

        val stringIdsSize = leU32(dex, 56)
        val stringIdsOff = leU32(dex, 60)
        require(stringIdsOff + stringIdsSize * 4 <= dex.size) { "string_ids outside file" }

        val replacements = mutableMapOf<String, String>()
        // 1. authorities (must fit – otherwise fail clearly)
        request.authorityMap.forEach { (old, new) -> replacements[old] = new }
        // 2. exact original package string (best effort)
        if (request.originalPackage.isNotEmpty() && request.clonePackage.isNotEmpty() &&
            request.originalPackage != request.clonePackage
        ) {
            replacements[request.originalPackage] = request.clonePackage
        }
        if (replacements.isEmpty()) return Result(0, emptyList(), 0)

        var patched = 0
        val notFitted = mutableListOf<String>()
        var skippedUtf8 = 0
        data class Item(val start: Int, val item: ByteArray, val footprint: Int)
        val pending = mutableListOf<Item>()

        for (i in 0 until stringIdsSize) {
            val idOff = stringIdsOff + i * 4
            val itemOff = leU32(dex, idOff)
            if (itemOff < 0 || itemOff + 1 > dex.size) continue
            val (utf16Size, prefixLen) = readUlebWithLen(dex, itemOff)
            val (text, bytesUsed) = readMutf8(dex, itemOff + prefixLen, utf16Size)
            if (text == null) { skippedUtf8++; continue }
            val originalFootprint = prefixLen + bytesUsed

            val mapped = replacements.entries.firstOrNull { it.key == text }?.value
                ?: run {
                    // prefix rule: "origPkg." -> "clonePkg." (file paths, provider refs, pref keys)
                    if (request.originalPackage.isNotEmpty() &&
                        text.length > request.originalPackage.length &&
                        text.startsWith(request.originalPackage + ".")
                    ) {
                        request.clonePackage + text.substring(request.originalPackage.length)
                    } else null
                } ?: continue

            if (mapped == text) continue
            val newItem = buildItem(mapped)
            if (newItem.size <= originalFootprint) {
                pending.add(Item(itemOff, newItem, originalFootprint))
                patched++
            } else {
                notFitted.add(text)
            }
        }

        for (p in pending) {
            System.arraycopy(p.item, 0, dex, p.start, p.item.size)
            // NUL-pad the remaining footprint (string length prefix now says a shorter string,
            // so the trailing bytes are unreachable; zero them for determinism)
            for (j in p.item.size until p.footprint) dex[p.start + j] = 0
        }

        fixChecksums(dex)
        return Result(patched, notFitted, skippedUtf8)
    }

    private fun fixChecksums(dex: ByteArray) {
        val sha = MessageDigest.getInstance("SHA-1")
        val sig = sha.digest(dex.copyOfRange(32, dex.size))
        System.arraycopy(sig, 0, dex, 12, 20)
        val adler = Adler32()
        adler.update(dex, 12, dex.size - 12)
        val cs = adler.value
        dex[8] = (cs and 0xFF).toByte(); dex[9] = ((cs shr 8) and 0xFF).toByte()
        dex[10] = ((cs shr 16) and 0xFF).toByte(); dex[11] = ((cs shr 24) and 0xFF).toByte()
    }

    // ------------------------------------------------------------ codec

    private fun buildItem(text: String): ByteArray =
        encodeUleb(text.length) + encodeMutf8(text)

    private fun readUlebWithLen(b: ByteArray, off: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var i = 0
        while (true) {
            val v = b[off + i].toInt() and 0xFF
            result = result or ((v and 0x7F) shl shift)
            if (v and 0x80 == 0) break
            shift += 7
            i++
            require(shift < 35) { "uleb too long" }
        }
        return result to (i + 1)
    }

    private fun encodeUleb(v: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var value = v
        do {
            var b = value and 0x7F
            value = value ushr 7
            if (value != 0) b = b or 0x80
            out.write(b)
        } while (value != 0)
        return out.toByteArray()
    }

    /** Decode modified UTF-8 (3-byte surrogate encoding) of exactly utf16Len units. */
    private fun readMutf8(b: ByteArray, off: Int, utf16Len: Int): Pair<String?, Int> {
        val sb = StringBuilder(utf16Len)
        var p = off
        for (i in 0 until utf16Len) {
            if (p >= b.size) return null to (p - off)
            val c0 = b[p].toInt() and 0xFF
            val cp = when {
                c0 < 0x80 -> { p++; c0 }
                c0 and 0xE0 == 0xC0 -> {
                    if (p + 1 >= b.size) return null to (p - off)
                    val c1 = b[p + 1].toInt() and 0xFF
                    if (c1 and 0xC0 != 0x80) return null to (p - off)
                    p += 2; ((c0 and 0x1F) shl 6) or (c1 and 0x3F)
                }
                c0 and 0xF0 == 0xE0 -> {
                    if (p + 2 >= b.size) return null to (p - off)
                    val c1 = b[p + 1].toInt() and 0xFF
                    val c2 = b[p + 2].toInt() and 0xFF
                    if (c1 and 0xC0 != 0x80 || c2 and 0xC0 != 0x80) return null to (p - off)
                    p += 3; ((c0 and 0x0F) shl 12) or ((c1 and 0x3F) shl 6) or (c2 and 0x3F)
                }
                else -> return null to (p - off)
            }
            sb.append(cp.toChar())
        }
        return sb.toString() to (p - off)
    }

    private fun encodeMutf8(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (ch in s) {
            val c = ch.code
            when {
                c in 0x0001..0x007F -> out.write(c)
                c <= 0x07FF -> {
                    out.write(0xC0 or (c shr 6)); out.write(0x80 or (c and 0x3F))
                }
                else -> {
                    out.write(0xE0 or (c shr 12)); out.write(0x80 or ((c shr 6) and 0x3F)); out.write(0x80 or (c and 0x3F))
                }
            }
        }
        return out.toByteArray()
    }

    private fun leU32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}
