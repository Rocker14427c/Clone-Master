package com.clonemaster.cloning.engine

import java.io.File

/**
 * Signature / re-signing pipeline – zipalign + apksigner
 */
class SigningPipeline {

    data class SigningResult(val signedApk: File, val verified: Boolean)

    fun sign(unsignedApk: File, outputDir: File, keystore: File?, alias: String = "clone-master", password: String = "clone-master"): SigningResult {
        outputDir.mkdirs()
        val alignedApk = File(outputDir, "aligned.apk")
        val signedApk = File(outputDir, "clone-signed.apk")

        // Try zipalign if available
        try {
            val zipalign = findBinary("zipalign")
            if (zipalign != null) {
                ProcessBuilder(zipalign, "-f", "4", unsignedApk.absolutePath, alignedApk.absolutePath)
                    .redirectErrorStream(true).start().waitFor()
            } else {
                unsignedApk.copyTo(alignedApk, overwrite = true)
            }
        } catch (e: Exception) {
            unsignedApk.copyTo(alignedApk, overwrite = true)
        }

        // Try apksigner
        try {
            val apksigner = findBinary("apksigner")
            if (apksigner != null && keystore != null && keystore.exists()) {
                ProcessBuilder(
                    apksigner, "sign",
                    "--ks", keystore.absolutePath,
                    "--ks-key-alias", alias,
                    "--ks-pass", "pass:$password",
                    "--key-pass", "pass:$password",
                    "--out", signedApk.absolutePath,
                    alignedApk.absolutePath
                ).redirectErrorStream(true).start().waitFor()
            } else {
                // Fallback: use uber-apk-signer if present
                val uber = File("/opt/uber-apk-signer.jar")
                if (uber.exists()) {
                    ProcessBuilder("java", "-jar", uber.absolutePath, "--apks", outputDir.absolutePath, "--allowResign",
                        "--ks", keystore?.absolutePath ?: createDebugKeystore().absolutePath,
                        "--ksAlias", alias, "--ksPass", password, "--ksKeyPass", password,
                        "--out", outputDir.absolutePath
                    ).redirectErrorStream(true).start().waitFor()
                    // Find output
                    outputDir.listFiles { f -> f.name.contains("aligned") && f.name.endsWith(".apk") }?.firstOrNull()?.let {
                        it.copyTo(signedApk, overwrite = true)
                    } ?: alignedApk.copyTo(signedApk, overwrite = true)
                } else {
                    alignedApk.copyTo(signedApk, overwrite = true)
                }
            }
        } catch (e: Exception) {
            alignedApk.copyTo(signedApk, overwrite = true)
        }

        // Verify
        var verified = false
        try {
            val apksigner = findBinary("apksigner")
            if (apksigner != null) {
                val proc = ProcessBuilder(apksigner, "verify", signedApk.absolutePath).start()
                verified = proc.waitFor() == 0
            }
        } catch (_: Exception) {}

        return SigningResult(signedApk, verified)
    }

    private fun findBinary(name: String): String? {
        return try {
            val proc = ProcessBuilder("which", name).start()
            proc.waitFor()
            val out = proc.inputStream.bufferedReader().readText().trim()
            if (out.isNotEmpty()) out else null
        } catch (_: Exception) { null }
    }

    fun createDebugKeystore(): File {
        val ksFile = File.createTempFile("debug", ".keystore")
        try {
            ProcessBuilder(
                "keytool", "-genkeypair", "-v",
                "-keystore", ksFile.absolutePath,
                "-alias", "clone-master",
                "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "10000",
                "-storepass", "clone-master",
                "-keypass", "clone-master",
                "-dname", "CN=Clone-Master, OU=Dev, O=Clone-Master, L=Patna, ST=Bihar, C=IN"
            ).redirectErrorStream(true).start().waitFor()
        } catch (_: Exception) {}
        return ksFile
    }
}
