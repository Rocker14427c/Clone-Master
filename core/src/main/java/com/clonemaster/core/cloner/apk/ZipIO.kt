package com.clonemaster.core.cloner.apk

import java.io.ByteArrayOutputStream

/**
 * Minimal ZIP reader/writer for APK packaging.
 *
 * Reader: locates the end-of-central-directory and central directory records
 * directly, so compressed entry bytes can be copied RAW (source data is
 * preserved byte-for-byte, needed for deterministic output).
 *
 * Writer: emits a zipalign-compatible APK:
 *   - STORED entries are aligned (resources.arsc & classes*.dex to 4 bytes,
 *     native .so to 4096 bytes) via extra-field padding;
 *   - DEFLATED entries are copied raw;
 *   - no data descriptors (sizes known up-front);
 *   - no Zip64 (clear error when required).
 */
class ZipIO {

    data class Entry(
        val name: String,
        val method: Int,             // 0 STORED, 8 DEFLATED
        val crc: Long,
        val compressedSize: Long,
        val uncompressedSize: Long,
        val localHeaderOffset: Long,
        val compressedData: ByteArray, // raw bytes from the source APK
        val isNew: Boolean = false
    )

    data class Apk(val entries: List<Entry>)

    companion object {
        const val STORED = 0
        const val DEFLATED = 8

        fun le16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
        fun le32(b: ByteArray, o: Int): Int =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

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
                entries.add(Entry(name, method, crc, csize, usize, lho, raw))
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
     * Write an APK from `baseEntries` (raw copies) with `replacements` replacing
     * same-named entries and `additions` added. Returns full unsigned APK bytes.
     */
    fun write(
        baseEntries: List<Entry>,
        replacements: Map<String, ByteArray>,
        additions: Map<String, ByteArray>,
        storeNames: Set<String> = emptySet()
    ): ByteArray {
        val names = LinkedHashSet<String>()
        baseEntries.forEach { names.add(it.name) }
        replacements.keys.forEach { names.add(it) }
        additions.keys.forEach { names.add(it) }

        val out = ByteArrayOutputStream()
        val cd = ByteArrayOutputStream()
        data class CdRec(val name: String, val method: Int, val crc: Long, val csize: Long, val usize: Long, val lho: Long, val extra: ByteArray)

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

            // alignment
            val align = when {
                method == STORED && (name.endsWith(".so") || name.startsWith("lib/")) -> 4096
                method == STORED -> 4096 // resources.arsc / dex: page-friendly, satisfies 4-byte rule
                else -> 1
            }
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val baseHdrLen = 30 + nameBytes.size
            var extraLen = 0
            if (align > 1) {
                val off = out.size() + baseHdrLen
                val rem = off % align
                if (rem != 0) extraLen = align - rem
            }
            // extra field = N zero bytes; accepted by Android's zip parser (no EOCD/CRC effect
            // since extras are excluded from entry data), same padding trick zipalign uses.
            val extra = ByteArray(extraLen)
            val lho = out.size().toLong()
            val crcInt = crc.toInt()

            // local header
            writeLeShort(out, 0x4b50); writeLeShort(out, 0x0403) // local file header sig (LE)
            writeLeShort(out, 20)        // version
            writeLeShort(out, 0x0800)    // flags: UTF-8
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
            val cdExtra = ByteArray(0)
            writeLeShort(cd, 0x4b50); writeLeShort(cd, 0x0201) // central dir sig (LE)
            writeLeShort(cd, 20)
            writeLeShort(cd, 20)
            writeLeShort(cd, 0x0800)
            writeLeShort(cd, method)
            writeLeShort(cd, 0)
            writeLeShort(cd, 0x21)
            writeLeInt(cd, crcInt)
            writeLeInt(cd, data.size.toInt())
            writeLeInt(cd, usize.toInt())
            writeLeShort(cd, nameBytes.size)
            writeLeShort(cd, cdExtra.size)
            writeLeShort(cd, 0) // comment len
            writeLeShort(cd, 0) // disk number
            writeLeShort(cd, 0) // internal attrs
            writeLeInt(cd, 0)   // external attrs
            writeLeInt(cd, lho.toInt())
            cd.write(nameBytes)
            cd.write(cdExtra)
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

    // ---- byte helpers ----
    // ---- byte helpers ----
    private fun writeLeShort(o: ByteArrayOutputStream, v: Int) { o.write(v and 0xFF); o.write((v shr 8) and 0xFF) }
    private fun writeLeInt(o: ByteArrayOutputStream, v: Int) {
        o.write(v and 0xFF); o.write((v shr 8) and 0xFF); o.write((v shr 16) and 0xFF); o.write((v shr 24) and 0xFF)
    }
}
