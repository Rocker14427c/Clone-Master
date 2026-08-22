package com.clonemaster.core.cloner.axml

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal, dependency-free binary Android XML (AXML) reader/writer.
 *
 * Implements the Android res/xml chunk format (ResXMLTree / ResourceTypes.h):
 *   - document header type 0x0003
 *   - string pool 0x0001 (UTF-8 and UTF-16 source encoding supported; output is UTF-8)
 *   - resource map 0x0180
 *   - namespace start/end 0x0100/0x0101
 *   - start/end element 0x0102/0x0103
 *   - CDATA 0x0104
 *
 * The parsed model keeps string indices stable (string pool order is preserved and
 * only ever appended to), so attribute references remain valid across edits.
 */
object BinaryXml {

    const val TYPE_XML = 0x0003
    const val TYPE_STRING_POOL = 0x0001
    const val TYPE_RESOURCE_MAP = 0x0180
    const val TYPE_NS_START = 0x0100
    const val TYPE_NS_END = 0x0101
    const val TYPE_ELEMENT_START = 0x0102
    const val TYPE_ELEMENT_END = 0x0103
    const val TYPE_CDATA = 0x0104

    const val UTF8_FLAG = 0x00000100
    const val SORTED_FLAG = 0x00000001
    const val NO_INDEX = 0xFFFFFFFF.toInt()

    // ------------------------------------------------------------------ model

    data class Attribute(
        var ns: Int,
        var name: Int,
        var rawValue: Int,
        var dataType: Int,
        var data: Int
    ) {
        companion object {
            const val TYPE_STRING = 3
            const val TYPE_INT = 16
            const val TYPE_REFERENCE = 1
            const val TYPE_BOOLEAN = 18
        }
    }

    /** A start element with editable attributes. */
    class Element(
        var line: Int,
        var ns: Int,
        var name: Int,
        val attributes: MutableList<Attribute>
    )

    /** One parser node in document order. */
    sealed class Node {
        class NamespaceStart(val prefix: Int, val uri: Int, val line: Int) : Node()
        class NamespaceEnd(val prefix: Int, val uri: Int, val line: Int) : Node()
        class Elem(val element: Element) : Node()
        class CData(val data: Int, val line: Int, val typedData: Int? = null) : Node()
        class EndElement(val name: Int, val ns: Int, val line: Int = 0) : Node()
    }

    class Document {
        val strings = mutableListOf<String>()
        val resourceIds = mutableMapOf<Int, Int>() // string index -> framework resource id
        val nodes = mutableListOf<Node>()
        var parsedStringPoolFlag: Int = 0

        fun findString(s: String): Int {
            val i = strings.indexOfFirst { it == s }
            return if (i >= 0) i else addString(s)
        }

        fun addString(s: String): Int {
            strings.add(s)
            return strings.size - 1
        }

        /** First element with the given (namespace-ignored) name, e.g. "manifest". */
        fun findFirstElement(attrName: String? = null): Element? {
            val elemName = attrName?.let { strings.indexOfFirst { s -> s == it }.takeIf { i -> i >= 0 } } ?: -1
            for (n in nodes) {
                if (n is Node.Elem) {
                    val e = n.element
                    if (attrName == null) return e
                    if (e.name == elemName) return e
                }
            }
            return null
        }

        fun elements(): List<Element> =
            nodes.filterIsInstance<Node.Elem>().map { it.element }

        fun elementName(e: Element): String = strings.getOrElse(e.name) { "?" }

        fun attrName(a: Attribute): String = strings.getOrElse(a.name) { "?" }

        fun attrValue(a: Attribute): String =
            if (a.dataType == Attribute.TYPE_STRING && a.data in strings.indices) strings[a.data] else a.data.toString()

        fun setStringValue(a: Attribute, newValue: String) {
            val idx = findString(newValue)
            a.data = idx
            a.rawValue = idx
            a.dataType = Attribute.TYPE_STRING
        }
    }

    // ------------------------------------------------------------------ read

    fun read(bytes: ByteArray): Document {
        require(bytes.size >= 8) { "AXML too short" }
        val doc = Document()
        var off = 8 // skip document header (type/hdrSize/size)
        while (off < bytes.size) {
            val type = u16(bytes, off)
            val size = u32(bytes, off + 4)
            if (size < 8 || off + size > bytes.size) break // recovered corruption guard
            when (type) {
                TYPE_STRING_POOL -> readStringPool(bytes, off, size, doc)
                TYPE_RESOURCE_MAP -> readResourceMap(bytes, off, size, doc)
                TYPE_NS_START -> {
                    val prefix = u32(bytes, off + 16)
                    val uri = u32(bytes, off + 20)
                    doc.nodes.add(Node.NamespaceStart(prefix, uri, u32(bytes, off + 8)))
                }
                TYPE_NS_END -> {
                    val prefix = u32(bytes, off + 16)
                    val uri = u32(bytes, off + 20)
                    doc.nodes.add(Node.NamespaceEnd(prefix, uri, u32(bytes, off + 8)))
                }
                TYPE_ELEMENT_START -> {
                    val line = u32(bytes, off + 8)
                    val ns = u32(bytes, off + 16)
                    val name = u32(bytes, off + 20)
                    val attrStart = u16(bytes, off + 24)
                    val attrCount = u16(bytes, off + 28)
                    val el = Element(line, ns, name, mutableListOf())
                    // attributes begin at 16 (attributeExt) + attributeStart
                    var aOff = off + 16 + attrStart
                    repeat(attrCount) {
                        val ans = u32(bytes, aOff)
                        val anm = u32(bytes, aOff + 4)
                        val ravv = u32(bytes, aOff + 8)
                        val tsize = u16(bytes, aOff + 12)
                        val dtype = bytes[aOff + 15].toInt() and 0xFF // res0 at +14, dataType at +15
                        val dval = if (tsize >= 8) u32(bytes, aOff + 16) else 0
                        el.attributes.add(Attribute(ans, anm, ravv, dtype, dval))
                        aOff += 20
                    }
                    doc.nodes.add(Node.Elem(el))
                }
                TYPE_ELEMENT_END -> {
                    val ns = u32(bytes, off + 16)
                    val name = u32(bytes, off + 20)
                    doc.nodes.add(Node.EndElement(name, ns))
                }
                TYPE_CDATA -> {
                    val data = u32(bytes, off + 16)
                    val typedData = u32(bytes, off + 20)
                    doc.nodes.add(Node.CData(data, u32(bytes, off + 8), typedData))
                }
                else -> { /* unknown chunk: skip (kept out of re-serialization) */ }
            }
            off += size
        }
        return doc
    }

    private fun readStringPool(b: ByteArray, off: Int, size: Int, doc: Document) {
        val count = u32(b, off + 8)
        val flags = u32(b, off + 16)
        val stringsStart = u32(b, off + 20)
        doc.parsedStringPoolFlag = flags
        val utf8 = flags and UTF8_FLAG != 0
        for (i in 0 until count) {
            val rel = u32(b, off + 28 + i * 4)
            doc.strings.add(readPoolString(b, off + stringsStart + rel, utf8))
        }
    }

    private fun readPoolString(b: ByteArray, o: Int, utf8: Boolean): String {
        if (utf8) {
            var p = o
            var l = b[p].toInt() and 0xFF; p++
            if (l and 0x80 != 0) { l = ((l and 0x7F) shl 8) or (b[p].toInt() and 0xFF); p++ }
            var hl = b[p].toInt() and 0xFF; p++
            if (hl and 0x80 != 0) { hl = ((hl and 0x7F) shl 8) or (b[p].toInt() and 0xFF); p++ }
            return String(b, p, l, StandardCharsets.UTF_8)
        } else {
            // UTF-16: u16 (or u32 if high bit set) length in CODE UNITS, then data
            var l = u16(b, o)
            var p = o + 2
            if (l and 0x8000 != 0) {
                l = ((l and 0x7FFF) shl 16) or u16(b, p); p += 2
            }
            val sb = StringBuilder(l)
            for (i in 0 until l) sb.append(u16(b, p + i * 2).toChar())
            return sb.toString()
        }
    }

    private fun readResourceMap(b: ByteArray, off: Int, size: Int, doc: Document) {
        val count = (size - 8) / 4
        for (i in 0 until count) {
            val id = u32(b, off + 8 + i * 4)
            if (id != 0) doc.resourceIds[i] = id
        }
    }

    // ------------------------------------------------------------------ write

    fun write(doc: Document): ByteArray {
        val pool = writeStringPool(doc.strings)
        val map = writeResourceMap(doc)
        val body = ByteArrayOutputStream()
        for (n in doc.nodes) {
            when (n) {
                is Node.NamespaceStart -> {
                    // aapt layout: hdr 16, size 24: line@8 comment@12 prefix@16 uri@20
                    val c = chunk(TYPE_NS_START, 16, 24)
                    c.putInt(n.line); c.putInt(NO_INDEX)
                    c.putInt(n.prefix); c.putInt(n.uri)
                    body.write(c.toByteArray())
                }
                is Node.NamespaceEnd -> {
                    val c = chunk(TYPE_NS_END, 16, 24)
                    c.putInt(n.line); c.putInt(NO_INDEX)
                    c.putInt(n.prefix); c.putInt(n.uri)
                    body.write(c.toByteArray())
                }
                is Node.Elem -> {
                    val el = n.element
                    val attrBytes = ByteArray(20 * el.attributes.size)
                    var p = 0
                    for (a in el.attributes) {
                        putInt(attrBytes, p, a.ns)
                        putInt(attrBytes, p + 4, a.name)
                        putInt(attrBytes, p + 8, a.rawValue)
                        putShort(attrBytes, p + 12, 8) // typed value unit size
                        attrBytes[p + 14] = 0
                        attrBytes[p + 15] = a.dataType.toByte()
                        putInt(attrBytes, p + 16, a.data)
                        p += 20
                    }
                    // aapt layout: hdr 16, size 36+attrs, attrStart 20 (relative to offset 16)
                    val c = chunk(TYPE_ELEMENT_START, 16, 36 + attrBytes.size)
                    c.putInt(el.line); c.putInt(NO_INDEX)
                    c.putInt(el.ns); c.putInt(el.name)
                    c.putShort(20); c.putShort(20)
                    c.putShort(el.attributes.size)
                    c.putShort(0); c.putShort(0); c.putShort(0) // idIndex, classIndex, styleIndex
                    body.write(c.toByteArray())
                    body.write(attrBytes)
                }
                is Node.EndElement -> {
                    // aapt layout: hdr 16, size 24: line@8 comment@12 ns@16 name@20
                    // README: apksig resolves the closing tag's ns/name through the pool,
                    // so the real indices must be written here (aapt2 does the same).
                    val c = chunk(TYPE_ELEMENT_END, 16, 24)
                    c.putInt(n.line); c.putInt(NO_INDEX)
                    c.putInt(n.ns); c.putInt(n.name)
                    body.write(c.toByteArray())
                }
                is Node.CData -> {
                    val c = chunk(TYPE_CDATA, 16, 24)
                    c.putInt(n.line); c.putInt(NO_INDEX)
                    c.putInt(n.data); c.putInt(n.typedData ?: 0)
                    body.write(c.toByteArray())
                }
            }
        }

        val out = ByteArrayOutputStream()
        out.write(littleEndianTypeHdr(TYPE_XML, 8, 8 + pool.size + map.size + body.size()))
        out.write(pool)
        out.write(map)
        body.writeTo(out)
        return out.toByteArray()
    }

    private fun writeStringPool(strings: List<String>): ByteArray {
        // UTF-8 encoded pool; indices preserved in order.
        val encoded = strings.map { utf8Encode(it) }
        var dataSize = 0
        val offsets = IntArray(strings.size)
        for (i in strings.indices) {
            val e = encoded[i]
            offsets[i] = dataSize
            dataSize += e.size
        }
        val headerSize = 28 + 4 * strings.size
        // CRITICAL: the string pool chunk size must be a multiple of 4 (aapt/
        // apksig/Android's parser reject misaligned XML chunks). Padding is
        // appended AFTER the string data, so every offset stays valid.
        val paddedDataSize = (dataSize + 3) and -4
        val total = headerSize + paddedDataSize
        val buf = ByteArray(total)
        var p = 0
        putShort(buf, p, TYPE_STRING_POOL); putShort(buf, p + 2, 28)
        putInt(buf, p + 4, total)
        putInt(buf, p + 8, strings.size)
        putInt(buf, p + 12, 0)          // styleCount
        putInt(buf, p + 16, UTF8_FLAG)  // flags
        putInt(buf, p + 20, headerSize) // stringsStart
        putInt(buf, p + 24, 0)          // stylesStart
        for (i in strings.indices) putInt(buf, p + 28 + i * 4, offsets[i])
        for ((i, e) in encoded.withIndex()) {
            System.arraycopy(e, 0, buf, p + headerSize + offsets[i], e.size)
        }
        // remaining bytes are zero padding (buf is zero-initialized)
        return buf
    }

    private fun writeResourceMap(doc: Document): ByteArray {
        // Full map covering the whole pool: original ids preserved, zeros elsewhere.
        val count = doc.strings.size
        if (count == 0) return ByteArray(0)
        val buf = ByteArray(8 + 4 * count)
        putShort(buf, 0, TYPE_RESOURCE_MAP); putShort(buf, 2, 8)
        putInt(buf, 4, buf.size)
        for (i in 0 until count) putInt(buf, 8 + i * 4, doc.resourceIds[i] ?: 0)
        return buf
    }

    // ------------------------------------------------------------------ utils

    private fun chunk(type: Int, headerSize: Int, totalSize: Int): ByteArrayOutputStream {
        val c = ByteArrayOutputStream(totalSize)
        c.write(littleEndianShort(type))
        c.write(littleEndianShort(headerSize))
        c.write(littleEndianInt(totalSize))
        return c
    }

    private fun ByteArrayOutputStream.putInt(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.putShort(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }

    private fun littleEndianTypeHdr(type: Int, headerSize: Int, totalSize: Int): ByteArray =
        byteArrayOf(
            (type and 0xFF).toByte(), ((type shr 8) and 0xFF).toByte(),
            (headerSize and 0xFF).toByte(), ((headerSize shr 8) and 0xFF).toByte(),
            (totalSize and 0xFF).toByte(), ((totalSize shr 8) and 0xFF).toByte(),
            ((totalSize shr 16) and 0xFF).toByte(), ((totalSize shr 24) and 0xFF).toByte()
        )

    private fun utf8Encode(s: String): ByteArray {
        // DEX/Android-style modified UTF-8 for the pool: encode UTF-16 code units.
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
        val body = out.toByteArray()
        val lenBytes = encodeLength(s.length)
        val hlBytes = encodeLength(body.size)
        // Android's parser (and apksig) require a NUL terminator after each pool string
        return lenBytes + hlBytes + body + byteArrayOf(0)
    }

    private fun encodeLength(v: Int): ByteArray {
        return if (v < 0x80) byteArrayOf(v.toByte())
        else byteArrayOf((0x80 or (v shr 8)).toByte(), (v and 0xFF).toByte())
    }

    fun u16(b: ByteArray, o: Int): Int = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    fun u32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun putShort(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
    }

    fun putInt(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte(); b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun littleEndianShort(v: Int): ByteArray = byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte())
    private fun littleEndianInt(v: Int): ByteArray =
        byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte(), ((v shr 16) and 0xFF).toByte(), ((v shr 24) and 0xFF).toByte())
}
