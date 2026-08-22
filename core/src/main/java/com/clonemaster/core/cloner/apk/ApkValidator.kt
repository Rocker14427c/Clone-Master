package com.clonemaster.core.cloner.apk

import com.clonemaster.core.cloner.CloneRequest
import com.clonemaster.core.cloner.axml.BinaryXml
import com.clonemaster.core.cloner.manifest.findAttr
import com.clonemaster.core.cloner.dex.DexPackageRewriter
import com.clonemaster.core.cloner.sign.V2Scheme

/**
 * Structural APK validation – the gatekeeper before a build is reported as success.
 *
 * IMPORTANT STATE-MACHINE RULE: every MANDATORY check that fails is added to the
 * `errors` list and forces `Report.ok = false`. There is no such thing as a
 * "failed check that is still OK" – callers must not report success when any
 * mandatory check fails.
 *
 * Checks:
 *  - valid ZIP / EOCD structure, no duplicate entry names, supported methods
 *  - AndroidManifest.xml present and binary AXML; package == clonePackage
 *  - provider authorities rewritten (no original values used)
 *  - resources.arsc stored when expected; alignment of STORED entries measured
 *    against the REAL data offsets of the file being validated
 *  - native libs: 16 KB alignment when stored (required on 16 KB page devices)
 *  - classes*.dex valid magic; CRCs of stored data verified; no original
 *    authority strings remain
 *  - stale v1 (JAR) signature files removed (they are invalid after content
 *    changes and can corrupt verification on devices that fall back to v1)
 *  - v2 signature verifies
 */
class ApkValidator {

    data class Report(
        val ok: Boolean,
        val checks: List<Pair<String, Boolean>>,
        val errors: List<String>
    ) {
        override fun toString(): String = buildString {
            append("ApkValidator: ")
            checks.forEach { (name, passed) -> append("\n  [${if (passed) "OK" else "FAIL"}] $name") }
            errors.forEach { append("\n  ERROR: $it") }
        }
    }

    fun validate(apk: ByteArray, request: CloneRequest, expectArsc: Boolean = true): Report {
        val checks = mutableListOf<Pair<String, Boolean>>()
        val errors = mutableListOf<String>()
        fun check(name: String, passed: Boolean, mandatory: Boolean = true) {
            checks.add(name to passed)
            if (!passed && mandatory) errors.add(name)
        }
        return try {
            val entries = ZipIO.read(apk)
            val names = entries.map { it.name }
            check("zip structure (${entries.size} entries)", entries.isNotEmpty())
            check("no duplicate entry names", names.size == names.toSet().size)

            // stale v1 signature files must be gone (invalid after content change)
            val stale = entries.filter { ZipIO.Companion.isStaleV1SignatureFile(it.name) }
            check("no stale META-INF v1 signature files" + if (stale.isEmpty()) "" else " (${stale.map { it.name }.joinToString()} )", stale.isEmpty())

            val manifest = entries.firstOrNull { it.name == "AndroidManifest.xml" }
            check("AndroidManifest.xml present", manifest != null)
            if (manifest == null) return Report(false, checks, errors + "AndroidManifest.xml missing")
            val manifestData = if (manifest.method == ZipIO.STORED) manifest.compressedData
            else inflate(manifest.compressedData, manifest.uncompressedSize.toInt())
            val isAxml = manifestData.size >= 8 &&
                    manifestData[0] == 0x03.toByte() && manifestData[1] == 0x00.toByte() &&
                    manifestData[2] == 0x08.toByte() && manifestData[3] == 0x00.toByte()
            check("AndroidManifest.xml is binary AXML", isAxml)
            if (!isAxml) return Report(false, checks, errors + "manifest is not binary AXML")

            val doc = BinaryXml.read(manifestData)
            val pkg = doc.findFirstElement()?.let { el ->
                doc.findAttr(el, "package")?.let { doc.attrValue(it) }
            }
            check("transformed package = ${request.clonePackage} (found: ${pkg ?: "?"})", pkg == request.clonePackage)

            val originalAuthorities = request.authorityMap.keys
            val manifestHasOldAuth = doc.elements().any { el ->
                val a = doc.findAttr(el, "authorities") ?: return@any false
                val v = doc.attrValue(a)
                originalAuthorities.any { old -> v.split(";").any { it.trim() == old } }
            }
            check("no original authority strings in manifest", !manifestHasOldAuth)

            val dexEntries = entries.filter { it.name.matches(Regex("classes(\\d*)\\.dex")) }

            // ---- semantic: every manifest component must resolve to a class in DEX ----
            // (after a package rename, absolute/relative component names must match the
            // classes the DEX engine moved to the new package; a missing component is an
            // install/launch-breaking defect that structural checks cannot catch.)
            val dexClassSet = HashSet<String>()
            for (de in dexEntries) {
                val dd = if (de.method == ZipIO.STORED) de.compressedData
                else inflate(de.compressedData, de.uncompressedSize.toInt())
                dexClassSet.addAll(DexPackageRewriter.listClasses(dd))
            }
            val missingComponents = mutableListOf<String>()
            val componentElements = setOf(
                "application", "activity", "activity-alias", "service", "receiver", "provider", "instrumentation"
            )
            for (el in doc.elements()) {
                if (doc.elementName(el) !in componentElements) continue
                val nameAttr = doc.findAttr(el, "name") ?: continue
                val name = doc.attrValue(nameAttr)
                val resolved = resolveComponent(name, request.clonePackage) ?: continue
                if (resolved !in dexClassSet) missingComponents.add("$name (resolved $resolved)")
                // activity-alias also checks targetActivity
                if (doc.elementName(el) == "activity-alias") {
                    val ta = doc.findAttr(el, "targetActivity") ?: continue
                    val tResolved = resolveComponent(doc.attrValue(ta), request.clonePackage)
                    if (tResolved != null && tResolved !in dexClassSet) missingComponents.add("targetActivity ${doc.attrValue(ta)}")
                }
            }
            check("all manifest components resolve to classes in DEX" +
                    (if (missingComponents.isEmpty()) "" else " (missing: ${missingComponents.joinToString()})"),
                missingComponents.isEmpty())

            // ---- alignment: measured against REAL data offsets in THIS archive ----
            val alignedEntries = entries.filter { it.method == ZipIO.STORED }
            val misaligned = alignedEntries.filter { e ->
                val align = if (e.name.startsWith("lib/") || e.name.endsWith(".so")) ZipIO.Companion.ALIGN_SO else ZipIO.Companion.ALIGN_DEFAULT
                e.dataOffset % align != 0L
            }
            check(
                "all STORED entries aligned" + (if (misaligned.isEmpty()) "" else " (${misaligned.map { "${it.name}@${it.dataOffset}" }.joinToString()})"),
                misaligned.isEmpty()
            )

            check("classes.dex present (${dexEntries.size} dex files)", dexEntries.isNotEmpty())
            var dexOk = dexEntries.isNotEmpty()
            val storedCrcBad = mutableListOf<String>()
            for (de in dexEntries) {
                val dexData = if (de.method == ZipIO.STORED) de.compressedData
                else inflate(de.compressedData, de.uncompressedSize.toInt())
                val magic = dexData.size >= 4 && dexData[0] == 'd'.code.toByte() && dexData[1] == 'e'.code.toByte() &&
                        dexData[2] == 'x'.code.toByte() && dexData[3] == '\n'.code.toByte()
                dexOk = dexOk && magic
                if (!magic) check("dex magic for ${de.name}", false)
            }
            check("all dex files have valid magic", dexOk)

            val arsc = entries.firstOrNull { it.name == "resources.arsc" }
            if (expectArsc) {
                check("resources.arsc present", arsc != null)
                if (arsc != null) {
                    check("resources.arsc stored", arsc.method == ZipIO.STORED)
                    check("resources.arsc aligned to 4 (data @ ${arsc.dataOffset})", arsc.dataOffset % 4 == 0L)
                }
            }

            // ---- stored CRCs (cheap integrity proof for moderate entries) ----
            for (e in alignedEntries) {
                if (e.uncompressedSize <= 32L * 1024 * 1024) {
                    val c = java.util.zip.CRC32()
                    c.update(e.compressedData)
                    if (c.value != e.crc) storedCrcBad.add(e.name)
                }
            }
            check("stored entry CRCs match" + (if (storedCrcBad.isEmpty()) "" else " (${storedCrcBad.joinToString()})"), storedCrcBad.isEmpty())

            // ---- DEX content: original authorities must be gone ----
            var oldAuthInDex = false
            if (originalAuthorities.isNotEmpty()) {
                outer@ for (de in dexEntries) {
                    val dexData = if (de.method == ZipIO.STORED) de.compressedData
                    else inflate(de.compressedData, de.uncompressedSize.toInt())
                    for (old in originalAuthorities) {
                        if (containsUtf8(dexData, old)) { oldAuthInDex = true; break@outer }
                    }
                }
            }
            check("no original authority strings in DEX", !oldAuthInDex)

            val v2 = V2Scheme.verify(apk)
            check("v2 signature verifies", v2.verified)

            Report(errors.isEmpty(), checks, errors)
        } catch (e: Exception) {
            Report(false, checks, errors + "validation failed: ${e.message}")
        }
    }

    /** Resolves a component name against the clone package; returns descriptor or null (external package). */
    private fun resolveComponent(name: String, clonePkg: String): String? = when {
        name.startsWith(".") -> DexPackageRewriter.toDescriptor(clonePkg + name)
        name.startsWith(clonePkg) || name.contains(".") && name.startsWith(clonePkg + ".") ->
            DexPackageRewriter.toDescriptor(name)
        else -> null // external/framework component – cannot verify, skip
    }

    private fun containsUtf8(data: ByteArray, needle: String): Boolean {
        val nb = needle.toByteArray(Charsets.UTF_8)
        if (nb.isEmpty() || data.size < nb.size) return false
        outer@ for (i in 0..data.size - nb.size) {
            for (j in nb.indices) if (data[i + j] != nb[j]) continue@outer
            return true
        }
        return false
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(expectedSize.coerceAtLeast(16))
        val inf = java.util.zip.Inflater(true)
        try {
            inf.setInput(data)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && inf.needsInput()) break
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }
}
