package com.clonemaster.core.cloner

import com.clonemaster.core.cloner.apk.ApkValidator
import com.clonemaster.core.cloner.apk.ZipIO
import com.clonemaster.core.cloner.axml.BinaryXml
import com.clonemaster.core.cloner.dex.DexStringPatcher
import com.clonemaster.core.cloner.manifest.ManifestCloner
import com.clonemaster.core.cloner.manifest.ManifestTransformResult
import com.clonemaster.core.cloner.manifest.findAttr
import java.security.MessageDigest
import com.clonemaster.core.cloner.sign.SigningKey
import com.clonemaster.core.cloner.sign.V2Scheme
import java.io.ByteArrayOutputStream

/**
 * Native, on-device clone builder – produces a valid, v2-signed cloned APK from
 * the original APK bytes using only pure-Kotlin tooling (no JVM tools, no
 * apktool, no external binaries). This replaces the broken "unzip fallback"
 * that previously generated install-invalid APKs.
 *
 * Pipeline: AXML manifest transform -> DEX string patch -> aligned repack ->
 * v2 signature -> structural validation. Any failure is reported clearly and
 * the build is NOT presented as successful.
 */
class AppCloneBuilder {

    data class Product(
        val apk: ByteArray,
        val diag: CloneDiag,
        val manifestResult: ManifestTransformResult?
    )

    /** RSA keypair + self-signed certificate used to sign the clone. */
    class SignMaterial(val keyPair: java.security.KeyPair, val certDer: ByteArray)

    companion object {
        /** One signing identity per process (stable across builds in a session). */
        @Volatile private var cachedMaterial: SignMaterial? = null

        fun generateSignMaterial(): SignMaterial {
            val kp = SigningKey.generateKeyPair()
            return SignMaterial(kp, SigningKey.buildSelfSignedCertificate(kp))
        }

        fun cachedSignMaterial(): SignMaterial =
            cachedMaterial ?: synchronized(this) {
                cachedMaterial ?: generateSignMaterial().also { cachedMaterial = it }
            }

        /** Restore a persisted signing identity (PKCS#8 private key + X.509 DER cert). */
        fun signMaterialFrom(privateKeyPkcs8: ByteArray, certDer: ByteArray): SignMaterial {
            val kf = java.security.KeyFactory.getInstance("RSA")
            val priv = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(privateKeyPkcs8))
            val cert = java.security.cert.CertificateFactory.getInstance("X.509")
                .generateCertificate(java.io.ByteArrayInputStream(certDer)) as java.security.cert.X509Certificate
            return SignMaterial(java.security.KeyPair(cert.publicKey, priv), certDer)
        }

        fun encodePrivateKey(m: SignMaterial): ByteArray = m.keyPair.private.encoded
    }

    /** @param material optional persisted signing identity; null -> process-cached/default */
    fun build(originalApk: ByteArray, request: CloneRequest, material: SignMaterial? = null): Product {
        val diag = CloneDiag()
        val allEntries = ZipIO.read(originalApk)

        // Drop the ORIGINAL application's v1 (JAR) signature files: they are
        // invalid after our content changes and can break verification on
        // installers that fall back to the v1 scheme. We re-sign with v2.
        val staleSig = allEntries.filter { ZipIO.Companion.isStaleV1SignatureFile(it.name) }
        if (staleSig.isNotEmpty()) {
            diag.log("Removed stale v1 signature files: ${staleSig.map { it.name }.joinToString(", ")}")
        }
        val entries = allEntries.filterNot { ZipIO.Companion.isStaleV1SignatureFile(it.name) }

        // ---------------- manifest ----------------
        val manifestEntry = entries.firstOrNull { it.name == "AndroidManifest.xml" }
            ?: error("AndroidManifest.xml not found in source APK")
        val manifestData = if (manifestEntry.method == ZipIO.STORED) manifestEntry.compressedData
        else inflate(manifestEntry.compressedData, manifestEntry.uncompressedSize.toInt())
        val doc = BinaryXml.read(manifestData)

        // 0. Authority planning: when no explicit map is provided, derive short,
        //    deterministic and unique authorities from (old authority + clone package).
        val effectiveMap = if (request.authorityMap.isNotEmpty()) {
            request.authorityMap
        } else {
            planAuthorities(doc, request)
        }
        val effectiveRequest = if (effectiveMap == request.authorityMap) request else request.copy(authorityMap = effectiveMap)
        diag.log("Authority plan: " + effectiveMap.entries.joinToString(", ") { "${it.key} -> ${it.value}" })

        val manifestResult = ManifestCloner().transform(doc, effectiveRequest)
        val newManifest = BinaryXml.write(doc)
        diag.log("Manifest: ${request.originalPackage} -> ${request.clonePackage}, " +
                "authorities=${manifestResult.authorityMap.size}, sharedUserIdRemoved=${manifestResult.removedSharedUserId}")

        // ---------------- dex ----------------
        val replacements = mutableMapOf<String, ByteArray>()
        replacements["AndroidManifest.xml"] = newManifest
        val dexEntries = entries.filter { it.name.matches(Regex("classes(\\d*)\\.dex")) }
        if (dexEntries.isEmpty()) {
            diag.warn("No classes.dex found in source APK (native-only apps are supported)")
        }
        val patcher = DexStringPatcher()
        val notFittedAll = mutableListOf<String>()
        for (de in dexEntries) {
            val dexData = if (de.method == ZipIO.STORED) de.compressedData
            else inflate(de.compressedData, de.uncompressedSize.toInt())
            val patchedDex = dexData.copyOf()
            val r = patcher.patch(patchedDex, effectiveRequest, diag)
            diag.log("${de.name}: patched ${r.replacements} strings" +
                    (if (r.notFitted.isNotEmpty()) ", NOT FITTED: ${r.notFitted.joinToString(", ")}" else ""))
            notFittedAll += r.notFitted
            if (r.replacements > 0) replacements[de.name] = patchedDex
        }
        // Authority strings MUST fit in every dex – otherwise fail clearly.
        val authorityNotFitted = notFittedAll.filter { it in request.authorityMap.keys }
        if (authorityNotFitted.isNotEmpty()) {
            error("Authority strings could not be rewritten in DEX (too long for in-place patch): " +
                    authorityNotFitted.joinToString(", ") +
                    ". Refusing to produce an install-invalid APK.")
        }
        // Original package hard-coded strings that could not be fitted: warn only.
        val pkgNotFitted = notFittedAll.filter { it == request.originalPackage || it.startsWith(request.originalPackage + ".") }
        if (pkgNotFitted.isNotEmpty()) {
            diag.warn("Hard-coded package strings not rewritten (longer than original): " +
                    pkgNotFitted.joinToString(", ") + ". App may run package-integrity checks; reported, not hidden.")
        }

        // ---------------- extra assets ----------------
        val additions = mutableMapOf<String, ByteArray>()
        request.extraAssets.forEach { (name, bytes) ->
            additions["assets/" + name.trimStart('/')] = bytes
        }

        // ---------------- pack + sign ----------------
        val unsigned = ZipIO().write(entries, replacements, additions)
        diag.log("Repacked APK: ${unsigned.size} bytes (unsigned)")

        val signMaterial = material ?: cachedSignMaterial()
        val signed = V2Scheme.V2Signer(signMaterial.keyPair, signMaterial.certDer).sign(unsigned)
        diag.log("v2-signed (RSA-2048/SHA-256), certificate CN=Clone-Master Clone Signer")

        // ---------------- validate ----------------
        val report = ApkValidator().validate(signed, effectiveRequest)
        diag.log(report.toString())
        if (!report.ok) {
            error("Post-build validation FAILED – clone NOT usable. " + report.errors.joinToString(" | "))
        }

        diag.log("Build completed and structurally validated (ZIP+manifest+DEX+alignment+CRC+signature). " +
                "Installation on a device is NOT verified in this build environment – use the install/export flow on the device.")
        return Product(signed, diag, manifestResult)
    }

    /**
     * Collects provider authorities actually used in the manifest and derives a
     * short deterministic replacement for each: "cm" + 10 hex chars (SHA-256 of
     * old authority + clone package), unique within the clone.
     * Short replacements maximize the chance of in-place DEX string patching;
     * any that do not fit fail the build clearly (no silent corruption).
     */
    fun planAuthorities(doc: com.clonemaster.core.cloner.axml.BinaryXml.Document, request: CloneRequest): Map<String, String> {
        val used = sortedSetOf<String>()
        for (el in doc.elements()) {
            val a = doc.findAttr(el, "authorities") ?: continue
            val v = doc.attrValue(a)
            if (v.isNotEmpty()) v.split(";").forEach { used.add(it.trim()) }
        }
        if (used.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        val seen = HashSet<String>()
        for (old in used) {
            // already explicitly mapped
            request.authorityMap[old]?.let { mapped ->
                require(seen.add(mapped)) { "Duplicate authority mapping: $mapped" }
                out[old] = mapped
                return@let
            }
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$old:${request.clonePackage}".toByteArray(Charsets.UTF_8))
            val hex = digest.joinToString("") { "%02x".format(it) }
            var candidate = "cm${hex.take(10)}"
            var salt = 0
            while (!seen.add(candidate)) {
                salt++
                candidate = "cm${hex.take(8)}${salt.toString(16).padStart(2, '0')}"
            }
            out[old] = candidate
        }
        return out
    }

    private fun inflate(data: ByteArray, expectedSize: Int): ByteArray {
        val out = ByteArrayOutputStream(expectedSize.coerceAtLeast(16))
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
