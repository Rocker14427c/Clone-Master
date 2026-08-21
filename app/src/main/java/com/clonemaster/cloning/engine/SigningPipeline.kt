package com.clonemaster.cloning.engine

import java.io.File

/**
 * Signature / re-signing pipeline – zipalign + apksigner
 * QA Hardened:
 * - Validates unsigned APK exists and non-zero before signing
 * - Handles ProcessBuilder output to prevent deadlocks
 * - Logs errors instead of swallowing silently
 * - Uses secure temp files for keystore
 * - Documents debug keystore password as debug-only
 * - Verifies signed APK exists
 */
class SigningPipeline {

    data class SigningResult(val signedApk: File, val verified: Boolean, val warnings: List<String> = emptyList())

    fun sign(unsignedApk: File, outputDir: File, keystore: File?, alias: String = "clone-master", password: String = "clone-master"): SigningResult {
        val warnings = mutableListOf<String>()

        if (!unsignedApk.exists()) {
            throw IllegalArgumentException("Unsigned APK does not exist: ${unsignedApk.absolutePath}")
        }
        if (unsignedApk.length() == 0L) {
            throw IllegalArgumentException("Unsigned APK is 0 bytes – will cause INSTALL_FAILED_INVALID_APK")
        }

        outputDir.mkdirs()
        val alignedApk = File(outputDir, "aligned.apk")
        val signedApk = File(outputDir, "clone-signed.apk")

        // Zipalign
        try {
            val zipalign = findBinary("zipalign")
            if (zipalign != null) {
                val proc = ProcessBuilder(zipalign, "-f", "4", unsignedApk.absolutePath, alignedApk.absolutePath)
                    .redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                val exitCode = proc.waitFor()
                if (exitCode != 0) {
                    warnings.add("zipalign failed with exit $exitCode: $output – using unaligned APK (may cause slower install)")
                    unsignedApk.copyTo(alignedApk, overwrite = true)
                }
            } else {
                warnings.add("zipalign not found – using unaligned APK, functional but not optimal")
                unsignedApk.copyTo(alignedApk, overwrite = true)
            }
        } catch (e: Exception) {
            warnings.add("zipalign exception: ${e.message} – using unaligned APK")
            android.util.Log.w("CloneMaster", "zipalign failed", e)
            try { unsignedApk.copyTo(alignedApk, overwrite = true) } catch (ex: Exception) {
                throw IllegalStateException("Failed to copy unsigned APK to aligned: ${ex.message}", ex)
            }
        }

        // Signing
        try {
            val apksigner = findBinary("apksigner")
            if (apksigner != null && keystore != null && keystore.exists()) {
                if (keystore.length() == 0L) {
                    warnings.add("Keystore is 0 bytes – creating debug keystore")
                    val debugKs = createDebugKeystore()
                    signWithApkSigner(apksigner, debugKs, alias, password, alignedApk, signedApk, warnings)
                } else {
                    signWithApkSigner(apksigner, keystore, alias, password, alignedApk, signedApk, warnings)
                }
            } else {
                val uber = File("/opt/uber-apk-signer.jar")
                if (uber.exists()) {
                    warnings.add("apksigner not found, using uber-apk-signer fallback")
                    val proc = ProcessBuilder("java", "-jar", uber.absolutePath, "--apks", outputDir.absolutePath, "--allowResign",
                        "--ks", keystore?.absolutePath ?: createDebugKeystore().absolutePath,
                        "--ksAlias", alias, "--ksPass", password, "--ksKeyPass", password,
                        "--out", outputDir.absolutePath
                    ).redirectErrorStream(true).start()
                    val output = proc.inputStream.bufferedReader().readText()
                    val exitCode = proc.waitFor()
                    if (exitCode != 0) {
                        warnings.add("uber-apk-signer failed: $output")
                    }
                    outputDir.listFiles { f -> f.name.contains("aligned") && f.name.endsWith(".apk") }?.firstOrNull()?.let {
                        it.copyTo(signedApk, overwrite = true)
                    } ?: alignedApk.copyTo(signedApk, overwrite = true)
                } else {
                    warnings.add("No signer found (apksigner/uber-apk-signer) – APK will be unsigned, INSTALL_FAILED_INVALID_APK on Android 11+")
                    alignedApk.copyTo(signedApk, overwrite = true)
                }
            }
        } catch (e: Exception) {
            warnings.add("Signing failed: ${e.message} – using aligned APK as fallback (may fail to install)")
            android.util.Log.e("CloneMaster", "Signing failed", e)
            try { alignedApk.copyTo(signedApk, overwrite = true) } catch (ex: Exception) {
                throw IllegalStateException("Failed to create signed APK fallback: ${ex.message}", ex)
            }
        }

        if (!signedApk.exists() || signedApk.length() == 0L) {
            throw IllegalStateException("Signed APK creation failed – file missing or 0 bytes")
        }

        // Verify
        var verified = false
        try {
            val apksigner = findBinary("apksigner")
            if (apksigner != null) {
                val proc = ProcessBuilder(apksigner, "verify", "--verbose", signedApk.absolutePath).redirectErrorStream(true).start()
                val output = proc.inputStream.bufferedReader().readText()
                verified = proc.waitFor() == 0
                if (!verified) warnings.add("apksigner verify failed: $output")
            } else {
                warnings.add("apksigner not found – cannot verify signature, assuming unverified")
            }
        } catch (e: Exception) {
            warnings.add("Verify failed: ${e.message}")
            android.util.Log.w("CloneMaster", "Verify failed", e)
        }

        return SigningResult(signedApk, verified, warnings)
    }

    private fun signWithApkSigner(apksigner: String, keystore: File, alias: String, password: String, input: File, output: File, warnings: MutableList<String>) {
        val proc = ProcessBuilder(
            apksigner, "sign",
            "--ks", keystore.absolutePath,
            "--ks-key-alias", alias,
            "--ks-pass", "pass:$password",
            "--key-pass", "pass:$password",
            "--out", output.absolutePath,
            input.absolutePath
        ).redirectErrorStream(true).start()
        val procOutput = proc.inputStream.bufferedReader().readText()
        val exitCode = proc.waitFor()
        if (exitCode != 0) {
            warnings.add("apksigner sign failed exit $exitCode: $procOutput")
            throw IllegalStateException("apksigner failed: $procOutput")
        }
    }

    private fun findBinary(name: String): String? {
        return try {
            val proc = ProcessBuilder("which", name).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            if (out.isNotEmpty() && !out.contains("not found")) out else null
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "findBinary $name failed: ${e.message}")
            null
        }
    }

    fun createDebugKeystore(): File {
        // QA: Debug keystore password "clone-master" is debug-only, not for production release
        // Documented as debug keystore, should be replaced with user-provided keystore for release
        val ksFile = try {
            File.createTempFile("debug", ".keystore").apply {
                deleteOnExit()
            }
        } catch (e: Exception) {
            File("/tmp/debug_${System.currentTimeMillis()}.keystore")
        }

        try {
            val proc = ProcessBuilder(
                "keytool", "-genkeypair", "-v",
                "-keystore", ksFile.absolutePath,
                "-alias", "clone-master",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "clone-master",
                "-keypass", "clone-master",
                "-dname", "CN=Clone-Master Debug, OU=Dev, O=Clone-Master, L=Patna, ST=Bihar, C=IN"
            ).redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            val exitCode = proc.waitFor()
            if (exitCode != 0) {
                android.util.Log.w("CloneMaster", "keytool failed: $output")
            }
        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "createDebugKeystore failed", e)
        }
        return ksFile
    }
}
