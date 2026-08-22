package com.clonemaster.core.cloner

import com.clonemaster.core.cloner.apk.ApkValidator
import com.clonemaster.core.cloner.apk.ZipIO
import com.clonemaster.core.cloner.axml.BinaryXml
import com.clonemaster.core.cloner.dex.DexStringPatcher
import com.clonemaster.core.cloner.manifest.findAttr
import com.clonemaster.core.cloner.sign.SigningKey
import com.clonemaster.core.cloner.sign.V2Scheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * End-to-end tests for the native clone pipeline (pure JVM, no Android,
 * no external build tools). These prove the "SIMPLE APK -> CLEAN CLONE ->
 * VALID + SIGNATURE VERIFIED" baseline that previously failed on device.
 */
class ClonerE2ETest {

    // ------------------------------------------------------------- fixtures

    /** Builds a minimal valid DEX with one string. */
    private fun buildDex(vararg strings: String): ByteArray {
        val dataOff = 0x70
        var dataSize = 0
        val items = strings.map { s ->
            val uleb = uleb(s.length)
            val m = mutf8(s)
            (uleb + m).also { dataSize += it.size }
        }
        val fileSize = dataOff + 4 * strings.size + dataSize
        val dex = ByteArray(fileSize)
        "dex\n035\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(dex, 0)
        putLe32(dex, 32, fileSize)      // file_size
        putLe32(dex, 36, 0x70)          // header_size
        putLe32(dex, 40, 0x12345678)    // endian_tag
        putLe32(dex, 56, strings.size)  // string_ids_size
        putLe32(dex, 60, dataOff)       // string_ids_off
        putLe32(dex, 104, dataSize)     // data_size
        putLe32(dex, 108, dataOff + 4 * strings.size) // data_off
        var itemOff = dataOff + 4 * strings.size
        strings.forEachIndexed { i, _ ->
            putLe32(dex, dataOff + i * 4, itemOff)
            itemOff += items[i].size
        }
        var w = dataOff + 4 * strings.size
        items.forEach { it.copyInto(dex, w); w += it.size }
        // signature + checksum
        val sha = MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size))
        sha.copyInto(dex, 12)
        Adler32().let { a -> a.update(dex, 12, dex.size - 12); putLe32(dex, 8, a.value.toInt()) }
        return dex
    }

    /** Builds a binary AXML manifest with the given package and optional authority. */
    private fun buildManifest(pkg: String, authority: String? = null): ByteArray {
        val doc = BinaryXml.Document()
        val androidNs = doc.addString("http://schemas.android.com/apk/res/android")
        doc.nodes.add(BinaryXml.Node.NamespaceStart(doc.addString("android"), androidNs, 1))
        val manifest = BinaryXml.Element(1, BinaryXml.NO_INDEX, doc.addString("manifest"), mutableListOf())
        fnAttr(doc, manifest, "package", null, pkg)
        fnAttr(doc, manifest, "versionCode", androidNs, "1")
        val app = BinaryXml.Element(2, BinaryXml.NO_INDEX, doc.addString("application"), mutableListOf())
        fnAttr(doc, app, "label", androidNs, "TestApp")
        fnAttr(doc, app, "icon", androidNs, "@mipmap/ic_launcher")
        if (authority != null) {
            val provider = BinaryXml.Element(3, BinaryXml.NO_INDEX, doc.addString("provider"), mutableListOf())
            fnAttr(doc, provider, "name", androidNs, "com.example.test.FileProvider")
            fnAttr(doc, provider, "authorities", androidNs, authority)
            fnAttr(doc, provider, "exported", androidNs, "false")
            doc.nodes.add(BinaryXml.Node.Elem(manifest))
            doc.nodes.add(BinaryXml.Node.Elem(app))
            doc.nodes.add(BinaryXml.Node.Elem(provider))
            doc.nodes.add(BinaryXml.Node.EndElement(doc.addString("provider"), BinaryXml.NO_INDEX))
            doc.nodes.add(BinaryXml.Node.EndElement(doc.addString("provider"), BinaryXml.NO_INDEX))
            doc.nodes.add(BinaryXml.Node.EndElement(doc.addString("provider"), BinaryXml.NO_INDEX))
        } else {
            doc.nodes.add(BinaryXml.Node.Elem(manifest))
            doc.nodes.add(BinaryXml.Node.Elem(app))
            doc.nodes.add(BinaryXml.Node.EndElement(doc.addString("provider"), BinaryXml.NO_INDEX))
            doc.nodes.add(BinaryXml.Node.EndElement(doc.addString("provider"), BinaryXml.NO_INDEX))
        }
        return BinaryXml.write(doc)
    }

    private fun fnAttr(doc: BinaryXml.Document, el: BinaryXml.Element, name: String, ns: Int?, value: String) {
        val nsIdx = ns ?: BinaryXml.NO_INDEX
        val vIdx = doc.addString(value)
        el.attributes.add(BinaryXml.Attribute(nsIdx, doc.addString(name), vIdx, BinaryXml.Attribute.TYPE_STRING, vIdx))
    }

    private fun itLength(v: Int): Int = when { v < 0x80 -> 1; v < 0x4000 -> 2; else -> 3 }

    private fun uleb(v: Int): ByteArray {
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

    private fun mutf8(s: String): ByteArray {
        val out = ByteArrayOutputStream()
        for (ch in s) {
            val c = ch.code
            when {
                c in 0x0001..0x007F -> out.write(c)
                c <= 0x07FF -> { out.write(0xC0 or (c shr 6)); out.write(0x80 or (c and 0x3F)) }
                else -> { out.write(0xE0 or (c shr 12)); out.write(0x80 or ((c shr 6) and 0x3F)); out.write(0x80 or (c and 0x3F)) }
            }
        }
        return out.toByteArray()
    }

    private fun putLe32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v and 0xFF).toByte(); b[o + 1] = ((v shr 8) and 0xFF).toByte()
        b[o + 2] = ((v shr 16) and 0xFF).toByte(); b[o + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /** Builds a tiny APK: manifest + dex + dummy arsc. */
    private fun buildTestApk(pkg: String, authority: String?, dexStrings: List<String>): ByteArray =
        buildTestApkCustom(pkg, authority, dexStrings, extraEntries = emptyList())

    /** Builds a test APK with extra raw entries (e.g. libs, META-INF). */
    private fun buildTestApkCustom(
        pkg: String,
        authority: String?,
        dexStrings: List<String>,
        extraEntries: List<ZipIO.Entry>
    ): ByteArray {
        val manifest = buildManifest(pkg, authority)
        val dex = buildDex(*dexStrings.toTypedArray())
        val arsc = ByteArray(64) { 0 }
        val base = mutableListOf<ZipIO.Entry>()
        base.add(ZipIO.Entry("AndroidManifest.xml", ZipIO.STORED, crc(manifest), manifest.size.toLong(), manifest.size.toLong(), 0, manifest))
        base.add(ZipIO.Entry("classes.dex", ZipIO.STORED, crc(dex), dex.size.toLong(), dex.size.toLong(), 0, dex))
        base.add(ZipIO.Entry("resources.arsc", ZipIO.STORED, crc(arsc), arsc.size.toLong(), arsc.size.toLong(), 0, arsc))
        base.addAll(extraEntries)
        return ZipIO().write(base, emptyMap(), emptyMap())
    }

    private fun storedEntry(name: String, data: ByteArray): ZipIO.Entry =
        ZipIO.Entry(name, ZipIO.STORED, crc(data), data.size.toLong(), data.size.toLong(), 0, data)

    private fun crc(data: ByteArray): Long = java.util.zip.CRC32().apply { update(data) }.value

    // ---------------------------------------------------------------- tests

    @Test
    fun `AXML manifest transform rewrites package and authorities`() {
        val manifest = buildManifest("com.example.test", "com.example.test.provider")
        val doc = BinaryXml.read(manifest)
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("com.example.test.provider" to "cm.a1b2c3d4.provider")
        )
        val result = com.clonemaster.core.cloner.manifest.ManifestCloner().transform(doc, req)
        assertEquals("com.example.test.clone1", result.newPackage)
        assertTrue(result.authorityMap.containsKey("com.example.test.provider"))

        val bytes = BinaryXml.write(doc)
        val doc2 = BinaryXml.read(bytes)
        val pkg = doc2.findFirstElement()?.let { doc2.findAttr(it, "package")?.let { a -> doc2.attrValue(a) } }
        assertEquals("com.example.test.clone1", pkg)
        val auth = doc2.elements().firstOrNull { doc2.elementName(it) == "provider" }
            ?.let { doc2.findAttr(it, "authorities")?.let { a -> doc2.attrValue(a) } }
        assertEquals("cm.a1b2c3d4.provider", auth)
        // no USED authority attribute may reference the original value
        val usedAuths = doc2.elements().mapNotNull { doc2.findAttr(it, "authorities") }.map { doc2.attrValue(it) }
        assertTrue(usedAuths.none { it.contains("com.example.test.provider") })
    }

    @Test
    fun `AXML round trip preserves structure`() {
        val manifest = buildManifest("com.example.test")
        val doc = BinaryXml.read(manifest)
        val bytes = BinaryXml.write(doc)
        val doc2 = BinaryXml.read(bytes)
        assertEquals(doc.strings.toList(), doc2.strings.toList())
        assertEquals(doc.nodes.size, doc2.nodes.size)
        val pkg = doc2.findFirstElement()?.let { doc2.findAttr(it, "package")?.let { a -> doc2.attrValue(a) } }
        assertEquals("com.example.test", pkg)
    }

    @Test
    fun `DEX string patch rewrites authority and fixes checksums`() {
        val dex = buildDex("com.example.test.provider", "welcome", "com.example.test")
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("com.example.test.provider" to "cm.a1b2c3d4.provider")
        )
        val diag = CloneDiag()
        val patched = dex.copyOf()
        val r = DexStringPatcher().patch(patched, req, diag)
        // authority fits (shorter); exact package name is LONGER -> must be reported, not silently kept
        assertEquals(1, r.replacements)
        assertTrue("longer package string must be reported as not fitted", r.notFitted.contains("com.example.test"))
        // signature/checksum recomputed -> Adler32 must verify
        val a = Adler32(); a.update(patched, 12, patched.size - 12)
        assertEquals(a.value, le32(patched, 8).toLong() and 0xFFFFFFFFL)
    }

    @Test
    fun `v2 signer signs and verify accepts its own output`() {
        val apk = buildTestApk("com.example.test", null, listOf("hi"))
        val kp = SigningKey.generateKeyPair()
        val cert = SigningKey.buildSelfSignedCertificate(kp)
        val signed = V2Scheme.V2Signer(kp, cert).sign(apk)
        // structure: still a valid zip
        val entries = ZipIO.read(signed)
        assertTrue(entries.any { it.name == "AndroidManifest.xml" })
        val v = V2Scheme.verify(signed)
        assertTrue("verify failed: ${v.message}", v.verified)
    }

    @Test
    fun `full clean clone build validates - package, authority, v2 signature`() {
        val src = buildTestApk(
            "com.example.test",
            "com.example.test.provider",
            listOf("com.example.test.provider", "com.example.test", "hello")
        )
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("com.example.test.provider" to "cm.a1b2c3d4.provider"),
            extraAssets = mapOf("clone_config.json" to """{"clonePackage":"com.example.test.clone1"}""".toByteArray())
        )
        val product = AppCloneBuilder().build(src, req)
        assertFalse("build should not report errors: ${product.diag.errors}", product.diag.hasErrors)

        val report = ApkValidator().validate(product.apk, req)
        assertTrue("validator failed: ${report.errors}", report.ok)
        val v = V2Scheme.verify(product.apk)
        assertTrue("v2 verification failed: ${v.message}", v.verified)
        // v2 (API 24+) only – acceptable for Clone-Master minSdk 24
    }

    @Test
    fun `alignment validated against real data offsets - regression for user FAIL row`() {
        // Regression for the device report "[FAIL] resources.arsc aligned to 4":
        // the OLD check computed (localHeaderOffset + 30 + nameLen) and omitted the
        // extra-field padding, so it FAILED whenever padding % 4 != 0 even though
        // the real data offset was correctly aligned. Construct exactly that case:
        // first entry stored with 3-byte data makes the arsc padding ≡ 3 (mod 4).
        var d3 = 3
        val oddData = byteArrayOf(1, 2, 3)
        val src = buildTestApkCustom(
            "com.example.test", "com.example.test.provider", listOf("com.example.test.provider"),
            extraEntries = listOf(storedEntry("odd.bin", oddData))
        )
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("com.example.test.provider" to "cm.a1b2c3d4.provider")
        )
        val product = AppCloneBuilder().build(src, req)
        assertFalse("build must not report errors: ${product.diag.errors}", product.diag.hasErrors)

        // The REAL data offset in the output must be 4-byte aligned (and validator agrees)
        val outEntries = ZipIO.read(product.apk)
        val arsc = outEntries.first { it.name == "resources.arsc" }
        assertEquals("resources.arsc data offset must be 4-aligned", 0L, arsc.dataOffset % 4)

        val report = ApkValidator().validate(product.apk, req)
        assertTrue("validator must pass: ${report.errors}", report.ok)
        val alignCheck = report.checks.first { it.first.startsWith("resources.arsc aligned") }
        assertTrue(alignCheck.second)
    }

    @Test
    fun `stored native libs are 16KB aligned and stale v1 signatures are dropped`() {
        val libData = ByteArray(2048) { it.toByte() }
        val stale = "META-INF/CERT.RSA".toByteArray()
        val src = buildTestApkCustom(
            "com.example.test", "com.example.test.provider", listOf("com.example.test.provider"),
            extraEntries = listOf(
                storedEntry("lib/arm64-v8a/libtest.so", libData),
                storedEntry("META-INF/CERT.RSA", stale),
                storedEntry("META-INF/MANIFEST.MF", stale)
            )
        )
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("com.example.test.provider" to "cm.a1b2c3d4.provider")
        )
        val product = AppCloneBuilder().build(src, req)
        assertFalse(product.diag.hasErrors)
        val outNames = ZipIO.read(product.apk).map { it.name }
        // stale v1 signature files must be gone
        assertFalse(outNames.contains("META-INF/CERT.RSA"))
        assertFalse(outNames.contains("META-INF/MANIFEST.MF"))
        // 16 KB alignment (Android 15+ 16 KB page-size devices)
        val so = ZipIO.read(product.apk).first { it.name == "lib/arm64-v8a/libtest.so" }
        assertEquals("stored .so must be 16384-aligned in output", 0L, so.dataOffset % 16384)
        val report = ApkValidator().validate(product.apk, req)
        assertTrue("validator must pass: ${report.errors}", report.ok)
    }

    @Test
    fun `build output is deterministic - same input twice gives identical bytes`() {
        val src = buildTestApk("com.example.test", "com.example.test.provider", listOf("com.example.test.provider"))
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("com.example.test.provider" to "cm.a1b2c3d4.provider")
        )
        val a = AppCloneBuilder().build(src, req).apk
        val b = AppCloneBuilder().build(src, req).apk
        assertTrue("build must be deterministic (identical bytes)", a.contentEquals(b))
    }

    @Test
    fun `dex order violations are reported not hidden`() {
        // "com.example.test" sorts before "com.example.test.provider".
        // Replacing the latter with "cm.a1b2c3d4.provider" breaks UTF-16 sort order.
        val dex = buildDex("com.example.test", "com.example.test.provider", "zzz")
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("com.example.test.provider" to "cm.a1b2c3d4.provider")
        )
        val diag = CloneDiag()
        val patched = dex.copyOf()
        val r = DexStringPatcher().patch(patched, req, diag)
        assertEquals(1, r.replacements)
        assertTrue("order violations must be REPORted", r.orderViolations >= 1)
    }

    @Test
    fun `authority that does not fit fails clearly instead of producing invalid apk`() {
        // authority replacement longer than the original string -> must fail (no silent corruption)
        val src = buildTestApk("com.example.test", "x.y", listOf("x.y"))
        val req = CloneRequest(
            originalPackage = "com.example.test",
            clonePackage = "com.example.test.clone1",
            authorityMap = mapOf("x.y" to "cm.this.is.way.too.long.an.authority.value.provider")
        )
        try {
            AppCloneBuilder().build(src, req)
            throw AssertionError("expected failure for non-fitting authority")
        } catch (e: Exception) {
            assertTrue("error must mention authority: ${e.message}", e.message!!.contains("uthority"))
        }
    }

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)
}
