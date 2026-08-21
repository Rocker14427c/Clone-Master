package com.clonemaster.cloning.engine

import com.clonemaster.cloning.models.CloneConfig
import java.io.File

/**
 * Manifest transformation – rewrites package ID, authorities, etc.
 * Works on decoded AndroidManifest.xml (apktool decoded) or binary XML via axml parser.
 */
class ManifestTransformer {

    data class TransformResult(
        val newPackage: String,
        val authorityMap: Map<String, String>, // old -> new
        val modifiedManifest: String
    )

    fun transform(decodedManifestFile: File, config: CloneConfig): TransformResult {
        var content = decodedManifestFile.readText()

        val originalPkg = extractPackage(content) ?: config.originalPackage
        val newPkg = config.clonePackage.ifEmpty { "${originalPkg}.clone${config.cloneIndex}" }

        // Validate package
        require(newPkg.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+"))) { "Invalid package: $newPkg" }

        // Remove sharedUserId (incompatible with new signature)
        content = content.replace(Regex("""android:sharedUserId="[^"]*""""), "")

        // Replace package attribute
        content = content.replace(Regex("""package="[^"]*""""), """package="$newPkg"""")

        // Authority transformation
        val authorityMap = mutableMapOf<String, String>()
        val authorityRegex = Regex("""android:authorities="([^"]+)"""")
        content = authorityRegex.replace(content) { match ->
            val oldAuth = match.groupValues[1]
            // Split by ; multiple authorities
            val newAuth = oldAuth.split(";").joinToString(";") { auth ->
                val transformed = "$newPkg.${auth.hashCode().toString(36)}.provider.${auth.substringAfterLast('.')}"
                    .replace("..", ".")
                    .lowercase()
                authorityMap[auth] = transformed
                transformed
            }
            """android:authorities="$newAuth""""
        }

        // Update provider android:name if relative? Keep same but ensure not conflicting
        // Inject clone config meta-data
        val configMeta = """<meta-data android:name="com.clonemaster.clone_config" android:value="assets/clone_config.json" />"""
        // Insert before </application>
        if (!content.contains("com.clonemaster.clone_config")) {
            content = content.replace("</application>", "    $configMeta\n    </application>")
        }

        // Handle launch icon removal if requested
        if (config.launching.removeLauncherIcon) {
            content = content.replace(Regex("""<category android:name="android.intent.category.LAUNCHER" />"""), """<!-- launcher removed -->""")
        }

        // TV banner
        if (config.tvWear.customTvBannerPath != null) {
            // Replace banner reference if exists
            content = content.replace(Regex("""android:banner="[^"]*""""), """android:banner="@mipmap/clone_tv_banner"""")
        }

        // Permissions stripping
        config.privacy.disabledPermissions.forEach { perm ->
            content = content.replace(Regex("""<uses-permission[^>]*$perm[^>]*/?>"""), """<!-- stripped $perm -->""")
        }

        // Prevent backup
        if (config.storage.preventBackup) {
            if (content.contains("android:allowBackup")) {
                content = content.replace(Regex("""android:allowBackup="[^"]*""""), """android:allowBackup="false"""")
            } else {
                content = content.replace("<application", """<application android:allowBackup="false"""")
            }
        }

        // Write back
        decodedManifestFile.writeText(content)

        return TransformResult(newPkg, authorityMap, content)
    }

    private fun extractPackage(manifest: String): String? {
        return Regex("""package="([^"]+)"""").find(manifest)?.groupValues?.get(1)
    }

    /**
     * Binary AXML handling placeholder – in real implementation would use apktool's AXML parser.
     * For now we rely on apktool decoded XML.
     */
    fun transformBinary(axmlFile: File, config: CloneConfig): TransformResult {
        // If binary, we would decode, transform, re-encode. Stub delegates to text transform for decoded case.
        return transform(axmlFile, config)
    }
}
