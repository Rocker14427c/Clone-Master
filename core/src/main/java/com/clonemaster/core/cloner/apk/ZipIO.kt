package com.clonemaster.core.cloner.apk

import java.io.ByteArrayOutputStream

/**
 * Minimal ZIP reader/writer for APK packaging.
 *
 * Reader: locates EOCD and central directory directly, so compressed entry
 * bytes can be copied RAW (source data preserved byte-for-byte). The actual
 * DATA OFFSET of each entry (localHeaderOffset + 30 + nameLen + extraLen) is
 * captured from the archive at read time, so validators and alignment checks
 * are computed against the REAL byte positions of the file being inspected.
 *
 * Writer: emits a zipalign-compatible APK:
 *   - STORED entries aligned via extra-field padding:
 *       * native libs (entries under lib/) -> 16384 (16 KB page-size devices, Android 15+;
 *         16 KB alignment is also accepted on 4 KB-page devices),
 *       * all other STORED entries (resources.arsc, classes*.dex, ...) -> 4
 *         (zipalign default requirement).
 *   - DEFLATED entries copied raw (no alignment needed).
 *   - deterministic timestamps, no data descriptors, no Zip64 (clear error).
 */
class ZipIO {

    data class Entry(
        val name: String,
        val method: Int,             // 0 STORED, 8 DEFLATED
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val compressedData: ByteArray,      // raw bytes from the archive
        /** Byte offset of the entry DATA in the archive this entry was read from. */
        val dataOffset: Long = -1,
        val extraLen: Int = 0
    )

    companion object {
        const val STORED = 0
        const val DEFLATED = 8

        /** Alignment for compressed=no native libs: covers 16 KB page devices (Android 15+). */
        const val ALIGN_SO = 16384
        /** Alignment for all other stored entries (zipalign default). */
        const val ALIGN_DEFAULT = 4

        fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        /** Signature files from the ORIGINAL signing that are invalid after content changes. */
        fun isStaleV1SignatureFile(name: String): Boolean {
            val upper = name.uppercase()
            return upper == "META-INF/MANIFEST.MF" ||
                    upper == "META-INF/INDEX.LIST" ||
                    upper.startsWith("META-INF/") && upper.substringAfterLast('/').matches(
                        Regex("[A-Z0-9_-]+\\.(SF|RSA|DSA|EC|SIG)")
                    )
        }

        fun read(apkBytes: ByteArray): List<Entry> {
            val eocd = findEocd(apkBytes)
            val cdOff = le32(apkBytes, eocd + 16)
            val cdCount = le16(apkBytes, eocd + 10)
            if (cdCount == 0xFFFF) error("Zip64 archives are not supported: entry count exceeds 65535")
            if (cdOff == 0xFFFFFFFF.toInt()) error("Zip64 archives are not supported: central directory offset exceeds 4GB")

            val entries = mutableListOf<Entry>()
            var p = cdOff
            repeat(cdCount) {
                require(le32(apkBytes, p) == 0x02014b50) { "Bad central directory record at $p" }
                val method = le16(apkBytes, p + 10)
                require(method == STORED || method == DEFLATED) { "Unsupported compression method $method" }
                val crc = le32(apkBytes, p + 16).toLong() and 0xFFFFFFFFL
                val csize = le32(apkBytes, p + 20).toLong() and 0xFFFFFFFFL
                val usize = le32(apkBytes, p + 24).toLong() and 0xFFFFFFFFL
                val nameLen = le16(apkBytes, p + 28)
                val extraLen = le16(apkBytes, p + 30)
                val commentLen = le16(apkBytes, p + 32)
                val lho = le32(apkBytes, p + 42).toLong() and 0xFFFFFFFFL
                val name = String(apkBytes, p + 46, nameLen, Charsets.UTF_8)
                if (csize == 0xFFFFFFFFL || usize == 0xFFFFFFFFL || lho == 0xFFFFFFFFL) {
                    error("Zip64 entry not supported: $name")
                }
                // Local header for exact data offset
                require(le32(apkBytes, lho.toInt()) == 0x04034b50) { "Bad local header for $name" }
                val lNameLen = le16(apkBytes, lho.toInt() + 26)
                val lExtraLen = le16(apkBytes, lho.toInt() + 28)
                val dataStart = lho.toInt() + 30 + lNameLen + lExtraLen
                require(csize <= Int.MAX_VALUE) { "Entry too large: $name" }
                val raw = apkBytes.copyOfRange(dataStart, dataStart + csize.toInt())
                entries.add(Entry(name, method, crc, csize, usize, lho, raw, dataStart.toLong(), lExtraLen))
                p += 46 + nameLen + extraLen + commentLen
            }
            return entries
        }

        private fun findEocd(b: ByteArray): Int {
            val scanStart = (b.size - 65557).coerceAtLeast(0)
            for (i in b.size - 22 downTo scanStart) {
                if (le32(b, i) == 0x06054b50) {
                    val cdCount = le16(b, i + 10)
                    val cdSize = le32(b, i + 12)
                    val commentLen = le16(b, i + 20)
                    if (i + 22 + commentLen == b.size && !(cdCount == 0xFFFF || cdSize == 0xFFFFFFFF.toInt())) {
                        return i
                    }
                }
            }
            error("EOCD not found – file is not a ZIP/APK")
        }
    }

    /**
     * Write an APK from `baseEntries` (raw copies), with `replacements`
     * replacing same-named entries and `additions` added.
     */
    fun write(
        baseEntries: List<Entry>,
        replacements: Map<String, ByteArray>,
        additions: Map<String, ByteArray>
    ): ByteArray {
        // Deterministic order: source order, then replacements, then additions.
        val names = LinkedHashSet<String>()
        baseEntries.forEach { names.add(it.name) }
        replacements.keys.forEach { names.add(it) }
        additions.keys.forEach { names.add(it) }

        val out = ByteArrayOutputStream()
        val cd = ByteArrayOutputStream()

        for (name in names) {
            val rep = replacements[name]
            val add = additions[name]
            val base = baseEntries.firstOrNull { it.name == name }

            val method: Int
            val data: ByteArray
            val crc: Long
            val usize: Long
            when {
                rep != null -> {
                    method = STORED
                    data = rep
                    crc = crc32(rep)
                    usize = rep.size.toLong()
                }
                add != null -> {
                    method = DEFLATED
                    data = deflate(add)
                    crc = crc32(add)
                    usize = add.size.toLong()
                }
                base != null -> {
                    method = base.method
                    data = base.compressedData
                    crc = base.crc
                    usize = base.uncompressedSize
                }
                else -> error("entry $name not found in source and not provided")
            }

            // Alignment for STORED (uncompressed) entries only.
            val align = when {
                method != STORED -> 1
                name.startsWith("lib/") || name.endsWith(".so") -> ALIGN_SO
                else -> ALIGN_DEFAULT
            }
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val baseHdrLen = 30 + nameBytes.size
            var extraLen = 0
            if (align > 1) {
                val off = out.size() + baseHdrLen
                val rem = off % align
                if (rem != 0) extraLen = align - rem
            }
            // extra field = zeros (valid padding entry 0x0000), same trick zipalign uses
            val extra = ByteArray(extraLen)
            val lho = out.size().toLong()
            val crcInt = crc.toInt()

            // local header
            writeLeShort(out, 0x4b50); writeLeShort(out, 0x0403) // local file header sig (LE)
            writeLeShort(out, 20)        // version needed
            writeLeShort(out, 0x0800)    // flags: UTF-8 names
            writeLeShort(out, method)
            writeLeShort(out, 0)         // time
            writeLeShort(out, 0x21)      // date 1980-01-01
            writeLeInt(out, crcInt)
            writeLeInt(out, data.size.toInt())
            writeLeInt(out, usize.toInt())
            writeLeShort(out, nameBytes.size)
            writeLeShort(out, extra.size)
            out.write(nameBytes)
            out.write(extra)
            out.write(data)

            // central directory record
            writeLeShort(cd, 0x4b50); writeLeShort(cd, 0x0201) // central dir sig (LE)
            writeLeShort(cd, 20)      // version made by
            writeLeShort(cd, 20)      // version needed
            writeLeShort(cd, 0x0800)  // flags
            writeLeShort(cd, method)
            writeLeShort(cd, 0)
            writeLeShort(cd, 0x21)
            writeLeInt(cd, crcInt)
            writeLeInt(cd, data.size.toInt())
            writeLeInt(cd, usize.toInt())
            writeLeShort(cd, nameBytes.size)
            writeLeShort(cd, 0)       // extra len
            writeLeShort(cd, 0)       // comment len
            writeLeShort(cd, 0)       // disk number
            writeLeShort(cd, 0)       // internal attrs
            writeLeInt(cd, 0)         // external attrs
            writeLeInt(cd, lho.toInt())
            cd.write(nameBytes)
        }

        val cdBytes = cd.toByteArray()
        val cdOff = out.size().toLong()
        val cdOffInt = cdOff.toInt()
        out.write(cdBytes)
        // EOCD
        writeLeShort(out, 0x4b50); writeLeShort(out, 0x0605) // EOCD sig (LE)
        writeLeShort(out, 0)
        writeLeShort(out, 0)
        writeLeShort(out, names.size)
        writeLeShort(out, names.size)
        writeLeInt(out, cdBytes.size)
        writeLeInt(out, cdOffInt)
        writeLeShort(out, 0)
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        java.util.zip.DeflaterOutputStream(bos, java.util.zip.Deflater(java.util.zip.Deflater.DEFAULT_COMPRESSION, true)).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun crc32(data: ByteArray): Long {
        val c = java.util.zip.CRC32()
        c.update(data)
        return c.value
    }

    private fun writeLeShort(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    private fun writeLeInt(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF)
    }
}
