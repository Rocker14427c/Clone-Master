package com.clonemaster.core.cloner

import com.clonemaster.core.cloner.apk.ApkValidator
import com.clonemaster.core.cloner.apk.ZipIO
import com.clonemaster.core.cloner.axml.BinaryXml
import com.clonemaster.core.cloner.dex.DexPackageRewriter
import com.clonemaster.core.cloner.dex.DexStringPatcher
import com.clonemaster.core.cloner.manifest.findAttr
import com.clonemaster.core.cloner.sign.SigningKey
import com.clonemaster.core.cloner.sign.V2Scheme
import org.jf.dexlib2.Opcode
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.Annotation
import org.jf.dexlib2.iface.MethodParameter
import org.jf.dexlib2.iface.instruction.Instruction
import org.jf.dexlib2.immutable.ImmutableClassDef
import org.jf.dexlib2.immutable.ImmutableMethod
import org.jf.dexlib2.immutable.ImmutableMethodImplementation
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction21c
import org.jf.dexlib2.immutable.reference.ImmutableStringReference
import org.jf.dexlib2.writer.io.MemoryDataStore
import org.jf.dexlib2.writer.pool.DexPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32

/**
 * End-to-end tests for the clone engine: full DEX rebuild (dexlib2), binary
 * manifest transform, APK repack + v2 signing + validation.
 *
 * The fixture APKs carry REAL dex classes (built with dexlib2) and REAL
 * manifest components, so the semantic validation (component classes must exist
 * in the DEX after the package rename) is exercised, not just ZIP checks.
 */
class ClonerE2ETest {

    companion object {
        const val ORIG = "com.example.test"
        const val CLONE = "com.example.test.clone1"
        const val CLONE_LONG = "com.example.test.clone12345.longer.name"
        const val AUTH = "com.example.test.provider"
        const val NEW_AUTH = "cm.a1b2c3d4.provider"
    }

    // ------------------------------------------------------------- builders

    /** Real dex with classes in the ORIG package carrying package strings. */
    private fun buildRealDex(vararg typeDescs: String, strings: List<String>): ByteArray {
        val opcodes = Opcodes.getDefault()
        val pool = DexPool(opcodes)
        for (t in typeDescs) {
            val instrs: List<Instruction> = strings.map { s ->
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(s))
            } + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)
            val impl = ImmutableMethodImplementation(2, instrs, emptyList(), emptyList())
            val method = ImmutableMethod(
                t, "getVal", emptyList<MethodParameter>(), "Ljava/lang/String;",
                0x1, emptySet<Annotation>(), emptySet(), impl
            )
            val cls = ImmutableClassDef(
                t,
                0x1,
                "Ljava/lang/Object;",
                emptyList<String>(),
                null as String?,
                emptySet<Annotation>(),
                emptyList<org.jf.dexlib2.iface.Field>(),
                emptyList<org.jf.dexlib2.iface.Field>(),
                listOf<org.jf.dexlib2.iface.Method>(method),
                emptyList<org.jf.dexlib2.iface.Method>()
            )
            pool.internClass(cls)
        }
        val store = MemoryDataStore()
        pool.writeTo(store)
        return store.data
    }

    private fun crc(data: ByteArray): Long = java.util.zip.CRC32().apply { update(data) }.value

    private fun storedEntry(name: String, data: ByteArray): ZipIO.Entry =
        ZipIO.Entry(name, ZipIO.STORED, crc(data), data.size.toLong(), data.size.toLong(), 0, data)

    /** A manifest whose components live in the ORIG package (absolute names). */
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
        val activity = BinaryXml.Element(3, BinaryXml.NO_INDEX, doc.addString("activity"), mutableListOf())
        fnAttr(doc, activity, "name", androidNs, "$pkg.HelloActivity")
        fnAttr(doc, activity, "exported", androidNs, "true")
        doc.nodes.add(BinaryXml.Node.Elem(manifest))
        doc.nodes.add(BinaryXml.Node.Elem(app))
        doc.nodes.add(BinaryXml.Node.Elem(activity))
        doc.nodes.add(BinaryXml.Node.EndElement(activity.name, BinaryXml.NO_INDEX))
        if (authority != null) {
            val provider = BinaryXml.Element(4, BinaryXml.NO_INDEX, doc.addString("provider"), mutableListOf())
            fnAttr(doc, provider, "name", androidNs, "$pkg.FileProvider")
            fnAttr(doc, provider, "authorities", androidNs, authority)
            fnAttr(doc, provider, "exported", androidNs, "false")
            doc.nodes.add(BinaryXml.Node.Elem(provider))
            doc.nodes.add(BinaryXml.Node.EndElement(provider.name, BinaryXml.NO_INDEX))
        }
        doc.nodes.add(BinaryXml.Node.EndElement(app.name, BinaryXml.NO_INDEX))
        doc.nodes.add(BinaryXml.Node.EndElement(manifest.name, BinaryXml.NO_INDEX))
        return BinaryXml.write(doc)
    }

    private fun fnAttr(doc: BinaryXml.Document, el: BinaryXml.Element, name: String, ns: Int?, value: String) {
        val vIdx = doc.addString(value)
        el.attributes.add(BinaryXml.Attribute(ns ?: BinaryXml.NO_INDEX, doc.addString(name), vIdx, BinaryXml.Attribute.TYPE_STRING, vIdx))
    }

    private fun buildTestApk(pkg: String, authority: String?, clonePkg: String, extraEntries: List<ZipIO.Entry> = emptyList()): ByteArray {
        val manifest = buildManifest(pkg, authority)
        val dex = buildRealDex(
            "L$pkg/HelloActivity;".replace('.', '/'),
            "L$pkg/FileProvider;".replace('.', '/'),
            strings = listOf(pkg, authority ?: pkg, "data/${pkg}/prefs")
        )
        val arsc = ByteArray(64) { 0 }
        val base = mutableListOf<ZipIO.Entry>()
        base.add(ZipIO.Entry("AndroidManifest.xml", ZipIO.STORED, crc(manifest), manifest.size.toLong(), manifest.size.toLong(), 0, manifest))
        base.add(ZipIO.Entry("classes.dex", ZipIO.STORED, crc(dex), dex.size.toLong(), dex.size.toLong(), 0, dex))
        base.add(ZipIO.Entry("resources.arsc", ZipIO.STORED, crc(arsc), arsc.size.toLong(), arsc.size.toLong(), 0, arsc))
        base.addAll(extraEntries)
        if (clonePkg != pkg) {
            // simulate multidex with a second dex (initially identical content)
            val dex2 = buildRealDex("L$pkg/Extra;".replace('.', '/'), strings = listOf("$pkg.extra"))
            base.add(ZipIO.Entry("classes2.dex", ZipIO.STORED, crc(dex2), dex2.size.toLong(), dex2.size.toLong(), 0, dex2))
        }
        return ZipIO().write(base, emptyMap(), emptyMap())
    }

    private fun req(clonePkg: String = CLONE): CloneRequest = CloneRequest(
        originalPackage = ORIG,
        clonePackage = clonePkg,
        authorityMap = mapOf(AUTH to NEW_AUTH)
    )

    // ---------------------------------------------------------------- tests

    @Test
    fun `DEX rebuild handles LONGER package names - classes move, no NOT FITTED`() {
        val dex = buildRealDex("Lcom/example/test/Hello;", strings = listOf(ORIG, AUTH, "data/$ORIG/prefs"))
        val r = DexPackageRewriter(ORIG, CLONE_LONG, mapOf(AUTH to NEW_AUTH)).rewrite(dex)

        assertTrue("types must be rewritten", r.rewrittenTypes >= 1)
        assertTrue("strings must be rewritten", r.rewrittenStrings >= 2)
        // classes really moved to the new package
        val classes = DexPackageRewriter.listClasses(r.dex)
        assertTrue("class must be under new package: $classes", classes.any { it == "Lcom/example/test/clone12345/longer/name/Hello;" })
        // dex must be valid & contain no residual original package descriptors
        val body = r.dex.toString(Charsets.ISO_8859_1)
        assertFalse("no residual Lcom/example/test/ class descriptor", body.contains("Lcom/example/test/Hello;"))
        // string pool rebuilt: LONGER constants present
        assertTrue(body.contains("com.example.test.clone12345.longer.name"))
    }

    @Test
    fun `manifest transform rewrites package, authorities AND absolute component names`() {
        val doc = BinaryXml.read(buildManifest(ORIG, AUTH))
        val result = com.clonemaster.core.cloner.manifest.ManifestCloner().transform(doc, req())
        assertEquals(CLONE, result.newPackage)

        val bytes = BinaryXml.write(doc)
        val doc2 = BinaryXml.read(bytes)
        val pkg = doc2.findFirstElement()?.let { doc2.findAttr(it, "package")?.let { a -> doc2.attrValue(a) } }
        assertEquals(CLONE, pkg)
        // authority rewritten
        val auth = doc2.elements().firstOrNull { doc2.elementName(it) == "provider" }
            ?.let { doc2.findAttr(it, "authorities")?.let { a -> doc2.attrValue(a) } }
        assertEquals(NEW_AUTH, auth)
        // ABSOLUTE component names rewritten to the new package (classes moved there)
        val act = doc2.elements().firstOrNull { doc2.elementName(it) == "activity" }
            ?.let { doc2.findAttr(it, "name")?.let { a -> doc2.attrValue(a) } }
        assertEquals("$CLONE.HelloActivity", act)
        val prov = doc2.elements().firstOrNull { doc2.elementName(it) == "provider" }
            ?.let { doc2.findAttr(it, "name")?.let { a -> doc2.attrValue(a) } }
        assertEquals("$CLONE.FileProvider", prov)
        // rewritten names reported for diagnostics
        assertTrue(result.rewrittenComponentNames.isNotEmpty())
    }

    @Test
    fun `full clean clone - longer clone package, multidex, all checks pass`() {
        val src = buildTestApk(ORIG, AUTH, CLONE_LONG)
        val product = AppCloneBuilder().build(src, req(CLONE_LONG))
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)
        assertTrue(
            "engine must report real transformations: ${product.diag.logs.filter { it.contains("REWROTE") }}",
            product.diag.logs.any { it.contains("REWROTE") }
        )
        val report = ApkValidator().validate(product.apk, req(CLONE_LONG))
        assertTrue("validator must pass: ${report.errors}", report.ok)
        val v2 = V2Scheme.verify(product.apk)
        assertTrue("v2 must verify: ${v2.message}", v2.verified)

        // output checks: manifest package + absolute component, dex classes moved
        val names = ZipIO.read(product.apk).map { it.name }
        assertTrue(names.contains("classes.dex") && names.contains("classes2.dex"))
        val v = ZipIO.read(product.apk).first { it.name == "AndroidManifest.xml" }
        val doc = BinaryXml.read(v.compressedData)
        val finalActivity = doc.elements().firstOrNull { doc.elementName(it) == "activity" }
            ?.let { doc.findAttr(it, "name")?.let { a -> doc.attrValue(a) } }
        assertEquals("$CLONE_LONG.HelloActivity", finalActivity)
    }

    @Test
    fun `alignment validated against real data offsets - regression for user FAIL row`() {
        val oddData = byteArrayOf(1, 2, 3)
        val src = buildTestApk(ORIG, AUTH, CLONE, extraEntries = listOf(storedEntry("odd.bin", oddData)))
        val product = AppCloneBuilder().build(src, req())
        assertFalse("build must not report errors: ${product.diag.errors}", product.diag.hasErrors)
        val arsc = ZipIO.read(product.apk).first { it.name == "resources.arsc" }
        assertEquals("resources.arsc data offset must be 4-aligned", 0L, arsc.dataOffset % 4)
        val report = ApkValidator().validate(product.apk, req())
        assertTrue("validator must pass: ${report.errors}", report.ok)
    }

    @Test
    fun `stored native libs 16KB aligned and stale v1 signatures dropped`() {
        val libData = ByteArray(2048) { it.toByte() }
        val stale = "META-INF/CERT.RSA".toByteArray()
        val src = buildTestApk(ORIG, AUTH, CLONE, extraEntries = listOf(
            storedEntry("lib/arm64-v8a/libtest.so", libData),
            storedEntry("META-INF/CERT.RSA", stale),
            storedEntry("META-INF/MANIFEST.MF", stale)
        ))
        val product = AppCloneBuilder().build(src, req())
        assertFalse(product.diag.hasErrors)
        val outNames = ZipIO.read(product.apk).map { it.name }
        assertFalse(outNames.contains("META-INF/CERT.RSA"))
        assertFalse(outNames.contains("META-INF/MANIFEST.MF"))
        val so = ZipIO.read(product.apk).first { it.name == "lib/arm64-v8a/libtest.so" }
        assertEquals("stored .so must be 16384-aligned in output", 0L, so.dataOffset % 16384)
        assertTrue(ApkValidator().validate(product.apk, req()).ok)
    }

    @Test
    fun `build output is deterministic - same input twice gives identical bytes`() {
        val src = buildTestApk(ORIG, AUTH, CLONE)
        val a = AppCloneBuilder().build(src, req()).apk
        val b = AppCloneBuilder().build(src, req()).apk
        assertTrue("build must be deterministic (identical bytes)", a.contentEquals(b))
    }

    @Test
    fun `missing component class is caught by semantic validation`() {
        // Manifest declares a component that the DEX does NOT contain: the new
        // semantic check must fail the build instead of shipping a broken clone.
        val doc = BinaryXml.Document()
        val androidNs = doc.addString("http://schemas.android.com/apk/res/android")
        doc.nodes.add(BinaryXml.Node.NamespaceStart(doc.addString("android"), androidNs, 1))
        val manifest = BinaryXml.Element(1, BinaryXml.NO_INDEX, doc.addString("manifest"), mutableListOf())
        fnAttr(doc, manifest, "package", null, ORIG)
        val app = BinaryXml.Element(2, BinaryXml.NO_INDEX, doc.addString("application"), mutableListOf())
        val activity = BinaryXml.Element(3, BinaryXml.NO_INDEX, doc.addString("activity"), mutableListOf())
        fnAttr(doc, activity, "name", androidNs, "$ORIG.DoesNotExist")
        doc.nodes.add(BinaryXml.Node.Elem(manifest))
        doc.nodes.add(BinaryXml.Node.Elem(app))
        doc.nodes.add(BinaryXml.Node.Elem(activity))
        doc.nodes.add(BinaryXml.Node.EndElement(activity.name, BinaryXml.NO_INDEX))
        doc.nodes.add(BinaryXml.Node.EndElement(app.name, BinaryXml.NO_INDEX))
        doc.nodes.add(BinaryXml.Node.EndElement(manifest.name, BinaryXml.NO_INDEX))
        val manifestBytes = BinaryXml.write(doc)
        val dex = buildRealDex("Lcom/example/test/Hello;", strings = listOf(ORIG))
        val entries = listOf(
            ZipIO.Entry("AndroidManifest.xml", ZipIO.STORED, crc(manifestBytes), manifestBytes.size.toLong(), manifestBytes.size.toLong(), 0, manifestBytes),
            ZipIO.Entry("classes.dex", ZipIO.STORED, crc(dex), dex.size.toLong(), dex.size.toLong(), 0, dex),
            ZipIO.Entry("resources.arsc", ZipIO.STORED, crc(ByteArray(64)), 64L, 64L, 0, ByteArray(64))
        )
        val src = ZipIO().write(entries, emptyMap(), emptyMap())
        try {
            AppCloneBuilder().build(src, req())
            throw AssertionError("expected failure: component class missing after rename")
        } catch (e: Exception) {
            assertTrue("error must mention components: ${e.message}", e.message!!.contains("components"))
        }
    }

    @Test
    fun `v2 signer signs and verify accepts its own output`() {
        val apk = buildTestApk(ORIG, AUTH, CLONE)
        val kp = SigningKey.generateKeyPair()
        val cert = SigningKey.buildSelfSignedCertificate(kp)
        val signed = V2Scheme.V2Signer(kp, cert).sign(apk)
        assertTrue(ZipIO.read(signed).any { it.name == "AndroidManifest.xml" })
        val v = V2Scheme.verify(signed)
        assertTrue("verify failed: ${v.message}", v.verified)
    }

    // --------- utility tests for the legacy in-place patcher (kept, unused by engine)

    @Test
    fun `legacy in-place patcher checksum fix and not-fitted reporting`() {
        val dex = legacyDex("com.example.test.provider", "welcome", "com.example.test")
        val req0 = CloneRequest(ORIG, CLONE, mapOf(AUTH to NEW_AUTH))
        val diag = CloneDiag()
        val patched = dex.copyOf()
        val r = DexStringPatcher().patch(patched, req0, diag)
        assertEquals(1, r.replacements)
        assertTrue(r.notFitted.contains(ORIG))
        val a = Adler32(); a.update(patched, 12, patched.size - 12)
        assertEquals(a.value, le32(patched, 8).toLong() and 0xFFFFFFFFL)
    }

    private fun legacyDex(vararg strings: String): ByteArray {
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
        putLe32(dex, 32, fileSize); putLe32(dex, 36, 0x70); putLe32(dex, 40, 0x12345678)
        putLe32(dex, 56, strings.size); putLe32(dex, 60, dataOff)
        putLe32(dex, 104, dataSize); putLe32(dex, 108, dataOff + 4 * strings.size)
        var itemOff = dataOff + 4 * strings.size
        strings.forEachIndexed { i, _ -> putLe32(dex, dataOff + i * 4, itemOff); itemOff += items[i].size }
        var w = dataOff + 4 * strings.size
        items.forEach { it.copyInto(dex, w); w += it.size }
        val sha = MessageDigest.getInstance("SHA-1").digest(dex.copyOfRange(32, dex.size))
        sha.copyInto(dex, 12)
        Adler32().let { a -> a.update(dex, 12, dex.size - 12); putLe32(dex, 8, a.value.toInt()) }
        return dex
    }

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

    private fun le32(b: ByteArray, o: Int): Long =
        (b[o].toLong() and 0xFF) or ((b[o + 1].toLong() and 0xFF) shl 8) or
                ((b[o + 2].toLong() and 0xFF) shl 16) or ((b[o + 3].toLong() and 0xFF) shl 24)

    // ------------------------------------------------ P0-1 general-options fixture

    /** Manifest with versionName/versionCode, an app label, one launcher and one plain activity. */
    private fun buildGeneralManifest(pkg: String): ByteArray {
        val doc = BinaryXml.Document()
        val androidNs = doc.addString("http://schemas.android.com/apk/res/android")
        doc.nodes.add(BinaryXml.Node.NamespaceStart(doc.addString("android"), androidNs, 1))
        val manifest = BinaryXml.Element(1, BinaryXml.NO_INDEX, doc.addString("manifest"), mutableListOf())
        fnAttr(doc, manifest, "package", null, pkg)
        fnAttr(doc, manifest, "versionName", androidNs, "1.0")
        fnAttr(doc, manifest, "versionCode", androidNs, "1") // string-typed on purpose (fixture)
        val app = BinaryXml.Element(2, BinaryXml.NO_INDEX, doc.addString("application"), mutableListOf())
        fnAttr(doc, app, "label", androidNs, "TestApp")
        val home = BinaryXml.Element(3, BinaryXml.NO_INDEX, doc.addString("activity"), mutableListOf())
        fnAttr(doc, home, "name", androidNs, "$pkg.HomeActivity")
        fnAttr(doc, home, "label", androidNs, "Home")
        val filter = BinaryXml.Element(4, BinaryXml.NO_INDEX, doc.addString("intent-filter"), mutableListOf())
        val action = BinaryXml.Element(4, BinaryXml.NO_INDEX, doc.addString("action"), mutableListOf())
        fnAttr(doc, action, "name", androidNs, "android.intent.action.MAIN")
        val category = BinaryXml.Element(4, BinaryXml.NO_INDEX, doc.addString("category"), mutableListOf())
        fnAttr(doc, category, "name", androidNs, "android.intent.category.LAUNCHER")
        val settings = BinaryXml.Element(5, BinaryXml.NO_INDEX, doc.addString("activity"), mutableListOf())
        fnAttr(doc, settings, "name", androidNs, "$pkg.SettingsActivity")
        fnAttr(doc, settings, "label", androidNs, "Settings")
        fun end(e: BinaryXml.Element) = BinaryXml.Node.EndElement(e.name, BinaryXml.NO_INDEX)
        doc.nodes.add(BinaryXml.Node.Elem(manifest))
        doc.nodes.add(BinaryXml.Node.Elem(app))
        doc.nodes.add(BinaryXml.Node.Elem(home))
        doc.nodes.add(BinaryXml.Node.Elem(filter))
        doc.nodes.add(BinaryXml.Node.Elem(action)); doc.nodes.add(end(action))
        doc.nodes.add(BinaryXml.Node.Elem(category)); doc.nodes.add(end(category))
        doc.nodes.add(end(filter))
        doc.nodes.add(end(home))
        doc.nodes.add(BinaryXml.Node.Elem(settings)); doc.nodes.add(end(settings))
        doc.nodes.add(end(app))
        doc.nodes.add(end(manifest))
        return BinaryXml.write(doc)
    }

    private fun buildGeneralApk(extraEntries: List<ZipIO.Entry> = emptyList()): ByteArray {
        val manifest = buildGeneralManifest(ORIG)
        val dex = buildRealDex(
            "Lcom/example/test/HomeActivity;",
            "Lcom/example/test/SettingsActivity;",
            strings = listOf(ORIG)
        )
        val arsc = ByteArray(64) { 0 }
        val base = mutableListOf<ZipIO.Entry>()
        base.add(ZipIO.Entry("AndroidManifest.xml", ZipIO.STORED, crc(manifest), manifest.size.toLong(), manifest.size.toLong(), 0, manifest))
        base.add(ZipIO.Entry("classes.dex", ZipIO.STORED, crc(dex), dex.size.toLong(), dex.size.toLong(), 0, dex))
        base.add(ZipIO.Entry("resources.arsc", ZipIO.STORED, crc(arsc), arsc.size.toLong(), arsc.size.toLong(), 0, arsc))
        base.addAll(extraEntries)
        return ZipIO().write(base, emptyMap(), emptyMap())
    }

    // ------------------------------------------------ P0-1 general-options tests

    @Test
    fun `general options ON - label, versionName, versionCode reach the native clone`() {
        val src = buildGeneralApk()
        val request = CloneRequest(
            originalPackage = ORIG,
            clonePackage = CLONE,
            labelOverride = "Cloned App",
            versionNameOverride = "2.3.4",
            versionCodeOverride = 77L
        )
        val product = AppCloneBuilder().build(src, request)
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)
        val doc = BinaryXml.read(ZipIO.read(product.apk).first { it.name == "AndroidManifest.xml" }.compressedData)
        val manifest = doc.findFirstElement()!!
        var vName: String? = null
        var vCode: BinaryXml.Attribute? = null
        for (a in manifest.attributes) when (doc.attrName(a)) {
            "versionName" -> vName = doc.attrValue(a)
            "versionCode" -> vCode = a
        }
        assertEquals("2.3.4", vName)
        requireNotNull(vCode)
        assertEquals(BinaryXml.Attribute.TYPE_INT, vCode!!.dataType)
        assertEquals(77, vCode!!.data)
        val appLabel = doc.findFirstElement("application")
            ?.let { doc.findAttr(it, "label")?.let { a -> doc.attrValue(a) } }
        assertEquals("Cloned App", appLabel)
        val labels = doc.elements().filter { doc.elementName(it) == "activity" }.associate {
            doc.attrValue(doc.findAttr(it, "name")!!) to doc.findAttr(it, "label")?.let { a -> doc.attrValue(a) }
        }
        assertEquals("Cloned App", labels["$CLONE.HomeActivity"])
        assertEquals("Settings", labels["$CLONE.SettingsActivity"])
        assertTrue(product.diag.logs.any { it.contains("General options applied") })
    }

    @Test
    fun `general options OFF - versions and labels are untouched`() {
        val src = buildGeneralApk()
        val product = AppCloneBuilder().build(src, CloneRequest(originalPackage = ORIG, clonePackage = CLONE))
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)
        val doc = BinaryXml.read(ZipIO.read(product.apk).first { it.name == "AndroidManifest.xml" }.compressedData)
        val manifest = doc.findFirstElement()!!
        val vName = manifest.attributes.first { doc.attrName(it) == "versionName" }
        assertEquals("1.0", doc.attrValue(vName))
        val appLabel = doc.findFirstElement("application")
            ?.let { doc.findAttr(it, "label")?.let { a -> doc.attrValue(a) } }
        assertEquals("TestApp", appLabel)
        assertTrue(
            "no general options may be reported for a clean clone: ${product.manifestResult?.appliedOptions}",
            product.manifestResult?.appliedOptions?.isEmpty() != false
        )
    }

    @Test
    fun `removeBranding drops only branding assets - ON and OFF`() {
        val branding = storedEntry("assets/app_cloner_branding.png", byteArrayOf(9, 9))
        val keep = storedEntry("assets/keep.txt", byteArrayOf(1, 2, 3))
        val src = buildGeneralApk(extraEntries = listOf(branding, keep))
        val on = AppCloneBuilder().build(
            src, CloneRequest(originalPackage = ORIG, clonePackage = CLONE, removeBranding = true)
        )
        assertFalse("build must succeed: ${on.diag.errors}", on.diag.hasErrors)
        val onNames = ZipIO.read(on.apk).map { it.name }
        assertFalse("branding asset must be dropped", onNames.contains("assets/app_cloner_branding.png"))
        assertTrue("unrelated assets must be kept", onNames.contains("assets/keep.txt"))
        val off = AppCloneBuilder().build(
            src, CloneRequest(originalPackage = ORIG, clonePackage = CLONE, removeBranding = false)
        )
        assertTrue("OFF state must not modify assets", ZipIO.read(off.apk).map { it.name }.contains("assets/app_cloner_branding.png"))
    }

    // ------------------------------------------------ P0-2 runtime-injection fixtures

    /** Manifest whose <application> optionally carries android:name=$pkg.MyApp, plus one activity. */
    private fun buildAppManifest(pkg: String, withAppName: Boolean = true, appName: String? = null,
                                 appComponentFactory: String? = null): ByteArray {
        val doc = BinaryXml.Document()
        val androidNs = doc.addString("http://schemas.android.com/apk/res/android")
        doc.nodes.add(BinaryXml.Node.NamespaceStart(doc.addString("android"), androidNs, 1))
        val manifest = BinaryXml.Element(1, BinaryXml.NO_INDEX, doc.addString("manifest"), mutableListOf())
        fnAttr(doc, manifest, "package", null, pkg)
        val app = BinaryXml.Element(2, BinaryXml.NO_INDEX, doc.addString("application"), mutableListOf())
        if (withAppName) fnAttr(doc, app, "name", androidNs, appName ?: "$pkg.MyApp")
        if (appComponentFactory != null) fnAttr(doc, app, "appComponentFactory", androidNs, appComponentFactory)
        val activity = BinaryXml.Element(3, BinaryXml.NO_INDEX, doc.addString("activity"), mutableListOf())
        fnAttr(doc, activity, "name", androidNs, "$pkg.MainActivity")
        fun end(e: BinaryXml.Element) = BinaryXml.Node.EndElement(e.name, BinaryXml.NO_INDEX)
        doc.nodes.add(BinaryXml.Node.Elem(manifest))
        doc.nodes.add(BinaryXml.Node.Elem(app))
        doc.nodes.add(BinaryXml.Node.Elem(activity)); doc.nodes.add(end(activity))
        doc.nodes.add(end(app))
        doc.nodes.add(end(manifest))
        return BinaryXml.write(doc)
    }

    private fun buildRuntimeApk(withAppName: Boolean = true, appName: String? = null,
                                appComponentFactory: String? = null): ByteArray {
        val manifest = buildAppManifest(ORIG, withAppName, appName, appComponentFactory)
        val dex = buildRealDex(
            "Lcom/example/test/MyApp;",
            "Lcom/example/test/MainActivity;",
            strings = listOf(ORIG)
        )
        val arsc = ByteArray(64) { 0 }
        val base = mutableListOf<ZipIO.Entry>()
        base.add(ZipIO.Entry("AndroidManifest.xml", ZipIO.STORED, crc(manifest), manifest.size.toLong(), manifest.size.toLong(), 0, manifest))
        base.add(ZipIO.Entry("classes.dex", ZipIO.STORED, crc(dex), dex.size.toLong(), dex.size.toLong(), 0, dex))
        base.add(ZipIO.Entry("resources.arsc", ZipIO.STORED, crc(arsc), arsc.size.toLong(), arsc.size.toLong(), 0, arsc))
        return ZipIO().write(base, emptyMap(), emptyMap())
    }

    private fun fakeRuntimeDex(): ByteArray = buildRealDex(
        "Lcom/clonemaster/runtime/HookApplication;",
        "Lcom/clonemaster/runtime/HookComponentFactory;",
        "Lcom/clonemaster/runtime/RuntimeInit;",
        strings = listOf("cloner_runtime.json")
    )

    private fun readManifest(product: AppCloneBuilder.Product): BinaryXml.Document =
        BinaryXml.read(entryData(ZipIO.read(product.apk).first { it.name == "AndroidManifest.xml" }))

    /** Entry payload honoring the compression method (additions are DEFLATED, manifest STORED). */
    private fun entryData(e: ZipIO.Entry): ByteArray =
        if (e.method == ZipIO.STORED) e.compressedData else {
            val inf = java.util.zip.Inflater(true)
            try {
                inf.setInput(e.compressedData)
                val out = ByteArrayOutputStream(e.uncompressedSize.toInt().coerceAtLeast(16))
                val buf = ByteArray(8192)
                while (!inf.finished()) {
                    val n = inf.inflate(buf)
                    if (n == 0 && inf.needsInput()) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            } finally {
                inf.end()
            }
        }

    // ------------------------------------------------ P0-2 runtime-injection tests

    @Test
    fun `runtime injection prefers factory hook, application stays original, meta written`() {
        val src = buildRuntimeApk(withAppName = true)
        val request = CloneRequest(ORIG, CLONE, wrapApplication = true, runtimeDex = fakeRuntimeDex())
        val product = AppCloneBuilder().build(src, request)
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)

        val entries = ZipIO.read(product.apk)
        val names = entries.map { it.name }
        assertTrue("runtime dex appended as classes2.dex", names.contains("classes2.dex"))
        assertTrue("runtime meta written", names.contains("assets/cloner_runtime.json"))

        val meta = String(entryData(entries.first { it.name == "assets/cloner_runtime.json" }))
        assertTrue("original class recorded in meta: $meta",
            meta.contains("\"originalApplication\":\"$CLONE.MyApp\""))
        assertTrue("factory hook mode in meta: $meta", meta.contains("\"hookMode\":\"factory\""))

        val doc = readManifest(product)
        val appEl = doc.findFirstElement("application")!!
        val appName = doc.findAttr(appEl, "name")?.let { a -> doc.attrValue(a) }
        assertEquals("original application class PRESERVED (factory mode, renamed only)", "$CLONE.MyApp", appName)
        val factory = doc.findAttr(appEl, "appComponentFactory")?.let { a -> doc.attrValue(a) }
        assertEquals("com.clonemaster.runtime.HookComponentFactory", factory)
        assertEquals("factory mode reported", "factory", product.manifestResult!!.hookMode)

        val rtClasses = DexPackageRewriter.listClasses(entryData(entries.first { it.name == "classes2.dex" }))
        assertTrue(rtClasses.contains("Lcom/clonemaster/runtime/HookComponentFactory;"))

        assertTrue("validator must pass: ${ApkValidator().validate(product.apk, request).errors}",
            ApkValidator().validate(product.apk, request).ok)
        val v2 = V2Scheme.verify(product.apk)
        assertTrue("v2 must verify: ${v2.message}", v2.verified)
    }

    @Test
    fun `runtime injection falls back to application wrap when source declares its own factory`() {
        val src = buildRuntimeApk(withAppName = true, appComponentFactory = "com.example.SomeStartupFactory")
        val request = CloneRequest(ORIG, CLONE, wrapApplication = true, runtimeDex = fakeRuntimeDex())
        val product = AppCloneBuilder().build(src, request)
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)

        val doc = readManifest(product)
        val appEl = doc.findFirstElement("application")!!
        val appName = doc.findAttr(appEl, "name")?.let { a -> doc.attrValue(a) }
        assertEquals("wrap fallback replaces application class", "com.clonemaster.runtime.HookApplication", appName)
        val factory = doc.findAttr(appEl, "appComponentFactory")?.let { a -> doc.attrValue(a) }
        assertEquals("source factory attr untouched in wrap mode", "com.example.SomeStartupFactory", factory)
        assertEquals("wrap fallback reported", "wrap", product.manifestResult!!.hookMode)
        val entries = ZipIO.read(product.apk)
        val meta = String(entryData(entries.first { it.name == "assets/cloner_runtime.json" }))
        assertTrue("wrap mode in meta: $meta", meta.contains("\"hookMode\":\"wrap\""))
        assertTrue(ApkValidator().validate(product.apk, request).ok)
    }

    @Test
    fun `runtimeFileLog adds fileLog flag and v2 version to runtime meta`() {
        val src = buildRuntimeApk(withAppName = true)
        val request = CloneRequest(ORIG, CLONE, wrapApplication = true, runtimeDex = fakeRuntimeDex(), runtimeFileLog = true)
        val product = AppCloneBuilder().build(src, request)
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)
        val meta = String(entryData(ZipIO.read(product.apk).first { it.name == "assets/cloner_runtime.json" }))
        assertTrue("fileLog flag expected: $meta", meta.contains("\"fileLog\":true"))
        assertTrue("runtimeVersion 3 expected: $meta", meta.contains("\"runtimeVersion\":3"))
    }

    @Test
    fun `runtimeFileLog OFF keeps meta minimal`() {
        val src = buildRuntimeApk(withAppName = true)
        val request = CloneRequest(ORIG, CLONE, wrapApplication = true, runtimeDex = fakeRuntimeDex()) // runtimeFileLog=false
        val product = AppCloneBuilder().build(src, request)
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)
        val meta = String(entryData(ZipIO.read(product.apk).first { it.name == "assets/cloner_runtime.json" }))
        assertFalse("no fileLog key by default: $meta", meta.contains("fileLog"))
    }

    @Test
    fun `wrap without runtime dex fails closed`() {
        val src = buildRuntimeApk(withAppName = true)
        try {
            AppCloneBuilder().build(src, CloneRequest(ORIG, CLONE, wrapApplication = true, runtimeDex = null))
            throw AssertionError("expected fail-closed build error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("runtimeDex"))
        }
    }

    @Test
    fun `application without name gets factory hook - attribute added, meta has null original`() {
        val src = buildRuntimeApk(withAppName = false)
        val request = CloneRequest(ORIG, CLONE, wrapApplication = true, runtimeDex = fakeRuntimeDex())
        val product = AppCloneBuilder().build(src, request)
        assertFalse("build must succeed: ${product.diag.errors}", product.diag.hasErrors)
        val entries = ZipIO.read(product.apk)
        val meta = String(entryData(entries.first { it.name == "assets/cloner_runtime.json" }))
        assertTrue("null original expected: $meta", meta.contains("\"originalApplication\":null"))
        assertTrue("factory mode in meta: $meta", meta.contains("\"hookMode\":\"factory\""))
        val doc = readManifest(product)
        val appEl = doc.findFirstElement("application")!!
        assertNull("no application name may be added in factory mode",
            doc.findAttr(appEl, "name")?.let { a -> doc.attrValue(a) }?.takeIf { it.isNotEmpty() })
        val factory = doc.findAttr(appEl, "appComponentFactory")?.let { a -> doc.attrValue(a) }
        assertEquals("com.clonemaster.runtime.HookComponentFactory", factory)
        assertTrue(ApkValidator().validate(product.apk, request).ok)
    }

    @Test
    fun `double wrap is refused with a clear error`() {
        val src = buildRuntimeApk(withAppName = true, appName = "com.clonemaster.runtime.HookApplication")
        try {
            AppCloneBuilder().build(src, CloneRequest(ORIG, CLONE, wrapApplication = true, runtimeDex = fakeRuntimeDex()))
            throw AssertionError("expected double-wrap refusal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("already runtime-wrapped"))
        }
    }

    @Test
    fun `OFF state injects no runtime and keeps original application class`() {
        val src = buildRuntimeApk(withAppName = true)
        val request = CloneRequest(ORIG, CLONE) // wrapApplication=false, runtimeDex=null
        val product = AppCloneBuilder().build(src, request)
        assertFalse(product.diag.hasErrors)
        val names = ZipIO.read(product.apk).map { it.name }
        assertFalse(names.contains("classes2.dex"))
        assertFalse(names.contains("assets/cloner_runtime.json"))
        val doc = readManifest(product)
        val appName = doc.findFirstElement("application")
            ?.let { doc.findAttr(it, "name")?.let { a -> doc.attrValue(a) } }
        assertEquals("$CLONE.MyApp", appName) // renamed original, NOT the wrapper
        assertTrue(ApkValidator().validate(product.apk, request).ok)
    }

    // ------------------------------------------------ P0-2.1 signature-string regression (device crash)

    @Test
    fun `embedded descriptors inside generic Signature strings are rewritten`() {
        val rw = DexPackageRewriter(ORIG, CLONE, emptyMap())
        // gson/jackson-style generic of a field: List<models.Foo> from the original package
        val sigIn = "Ljava/util/List<Lcom/example/test/models/AutoPressRule;>;"
        val sigOut = rw.rewriteString(sigIn)
        assertEquals("Ljava/util/List<L${
            CLONE.replace('.', '/')}/models/AutoPressRule;>;", sigOut)
        // pure descriptor string (Class.forName-style)
        assertEquals("L${CLONE.replace('.', '/')}/Foo;", rw.rewriteString("Lcom/example/test/Foo;"))
        // method generic with two descriptors
        val mIn = "(Lcom/example/test/A;Ljava/lang/String;Lcom/example/test/B;)V"
        assertEquals("(L${CLONE.replace('.', '/')}/A;Ljava/lang/String;L${CLONE.replace('.', '/')}/B;)V",
            rw.rewriteString(mIn))
        // untouched: third-party + plain text
        assertEquals(null, rw.rewriteString("Lcom/other/lib/Foo;"))
        assertEquals(null, rw.rewriteString("some user text about com/example paths"))
        assertEquals(null, rw.rewriteString("java.util.List<kotlin.String>"))
    }

    @Test
    fun `round trip - dex carrying a Signature-like const string no longer references old package`() {
        val sig = "Ljava/util/List<Lcom/example/test/models/AutoPressRule;>;"
        val dex = buildRealDex("Lcom/example/test/models/AutoPressRule;", strings = listOf(sig))
        val r = DexPackageRewriter(ORIG, CLONE, emptyMap()).rewrite(dex)
        val body = r.dex.toString(Charsets.ISO_8859_1)
        assertTrue("new package must be present in signature", body.contains("Lcom/example/test/clone1/models/AutoPressRule;"))
        assertFalse("no residual old package anywhere", body.contains("Lcom/example/test/models"))
    }
}
