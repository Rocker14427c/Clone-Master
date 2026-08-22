package com.clonemaster.core.cloner.apk

import com.clonemaster.core.cloner.CloneRequest
import com.clonemaster.core.cloner.manifest.findAttr
import com.clonemaster.core.cloner.axml.BinaryXml
import com.clonemaster.core.cloner.sign.V2Scheme

/**
 * Structural APK validation – the gatekeeper before a build is reported as success.
 *
 * Checks (mirrors the handover "final APK should be structurally validated" list):
 *  - valid ZIP / EOCD structure
 *  - AndroidManifest.xml present and binary AXML
 *  - resources.arsc present when expected, stored + aligned
 *  - classes.dex present with valid magic (and multidex files)
 *  - transformed package name matches clonePackage
 *  - no original authority strings remain in manifest or DEX
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
        return try {
            val entries = ZipIO.read(apk)
            val names = entries.map { it.name }.toSet()
            checks.add("zip structure (${entries.size} entries)" to (entries.isNotEmpty()))

            val manifest = entries.firstOrNull { it.name == "AndroidManifest.xml" }
            checks.add("AndroidManifest.xml present" to (manifest != null))
            if (manifest == null) return Report(false, checks, listOf("AndroidManifest.xml missing"))
            val manifestBytes = ByteArray(manifest.compressedSize.toInt())
            // stored manifests are read raw; deflated manifests must be inflated
            val manifestData = if (manifest.method == ZipIO.STORED)
                manifest.compressedData
            else
                inflate(manifest.compressedData, manifest.uncompressedSize.toInt())
            val isAxml = manifestData.size >= 8 &&
                    manifestData[0] == 0x03.toByte() && manifestData[1] == 0x00.toByte() &&
                    manifestData[2] == 0x08.toByte() && manifestData[3] == 0x00.toByte()
            checks.add("AndroidManifest.xml is binary AXML" to isAxml)
            if (!isAxml) return Report(false, checks, listOf("manifest is not binary AXML"))

            val doc = BinaryXml.read(manifestData)
            val pkg = doc.findFirstElement()?.let { el ->
                doc.findAttr(el, "package")?.let { doc.attrValue(it) }
            }
            checks.add("transformed package = ${request.clonePackage}" to (pkg == request.clonePackage))

            val originalAuthorities = request.authorityMap.keys
            val manifestHasOldAuth = doc.elements().any { el ->
                val a = doc.findAttr(el, "authorities") ?: return@any false
                val v = doc.attrValue(a)
                originalAuthorities.any { old -> v.split(";").any { it.trim() == old } }
            }
            checks.add("no original authority strings in manifest" to !manifestHasOldAuth)

            val dexEntries = entries.filter { it.name.matches(Regex("classes(\\d*)\\.dex")) }
            checks.add("classes.dex present (${dexEntries.size} dex files)" to (dexEntries.isNotEmpty()))
            var dexOk = dexEntries.isNotEmpty()
            for (de in dexEntries) {
                val dexData = if (de.method == ZipIO.STORED) de.compressedData
                else inflate(de.compressedData, de.uncompressedSize.toInt())
                val magic = dexData.size >= 4 && dexData[0] == 'd'.code.toByte() && dexData[1] == 'e'.code.toByte() &&
                        dexData[2] == 'x'.code.toByte() && dexData[3] == '\n'.code.toByte()
                dexOk = dexOk && magic
                if (!magic) checks.add("dex magic for ${de.name}" to false)
            }
            checks.add("all dex files have valid magic" to dexOk)

            val arsc = entries.firstOrNull { it.name == "resources.arsc" }
            if (expectArsc) {
                checks.add("resources.arsc present" to (arsc != null))
                if (arsc != null) {
                    checks.add("resources.arsc stored" to (arsc.method == ZipIO.STORED))
                    checks.add("resources.arsc aligned to 4" to ((arsc.localHeaderOffset + 30 + nameLenOf(apk, arsc)) % 4 == 0L))
                }
            }

            // DEX must not contain original authority strings
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
            checks.add("no original authority strings in DEX" to !oldAuthInDex)

            val v2 = V2Scheme.verify(apk)
            checks.add("v2 signature verifies" to v2.verified)

            val errors = mutableListOf<String>()
            if (pkg != request.clonePackage) errors.add("package mismatch")
            if (manifestHasOldAuth) errors.add("original authorities left in manifest")
            if (!dexOk) errors.add("invalid dex")
            if (oldAuthInDex) errors.add("original authorities left in DEX")
            if (!v2.verified) errors.add("v2 signature did not verify: ${v2.message}")
            Report(errors.isEmpty(), checks, errors)
        } catch (e: Exception) {
            Report(false, checks, listOf("validation failed: ${e.message}"))
        }
    }

    private fun nameLenOf(apk: ByteArray, e: ZipIO.Entry): Int = e.name.toByteArray(Charsets.UTF_8).size

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
        val out = java.io.ByteArrayOutputStream(expectedSize)
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
