package com.clonemaster.cloning.engine

import com.clonemaster.cloning.models.CloneConfig
import java.io.File

/**
 * DEX handling – rewrites package references, provider authorities, injects hook framework.
 * Real implementation would use ASM / dexlib2 to parse dex. Here we implement heuristic string replacement + dex merging.
 */
class DexTransformer {

    fun transform(smaliRoot: File, config: CloneConfig, authorityMap: Map<String, String>, diagnostics: CloningDiagnostics) {
        val originalPkg = config.originalPackage
        val newPkg = config.clonePackage
        if (originalPkg.isEmpty() || newPkg.isEmpty()) {
            diagnostics.warn("Package empty, skipping dex transform")
            return
        }

        // Walk all smali files
        val smaliFiles = smaliRoot.walkTopDown().filter { it.extension == "smali" }.toList()
        var replacedCount = 0

        smaliFiles.forEach { file ->
            try {
                var content = file.readText()
                var modified = false

                // Replace provider authorities const-string
                authorityMap.forEach { (oldAuth, newAuth) ->
                    if (content.contains(oldAuth)) {
                        content = content.replace(oldAuth, newAuth)
                        modified = true
                        replacedCount++
                    }
                }

                // Replace package name where it appears as const-string for provider / authority checks
                // Heuristic: only replace if whole string equals originalPkg or starts with originalPkg + "."
                // Avoid replacing too aggressively to not break other logic
                if (content.contains(originalPkg)) {
                    // Replace only specific patterns
                    val patterns = listOf(
                        "\"$originalPkg\"" to "\"$newPkg\"",
                        "\"$originalPkg." to "\"$newPkg.",
                        "L${originalPkg.replace('.', '/')}/" to "L${newPkg.replace('.', '/')}/" // This is aggressive – we log but only do for provider classes?
                    )
                    // For safety, we only auto-replace authorities, not class descriptors unless it's provider
                    // So we skip class descriptor replacement here and handle via separate mapping
                    // Keep count
                    if (content.contains("\"$originalPkg\"")) {
                        // Only if file is related to provider or BuildConfig
                        if (file.name.contains("Provider") || file.name.contains("BuildConfig") || file.path.contains("provider")) {
                            content = content.replace("\"$originalPkg\"", "\"$newPkg\"")
                            modified = true
                            replacedCount++
                        }
                    }
                }

                // Inject hook reference if this is Application class
                if (content.contains("super Landroid/app/Application;") && file.name.contains("Application")) {
                    // Mark for wrapping – actual wrapping done in Application class handling step
                    diagnostics.log("Found Application class: ${file.path}")
                }

                if (modified) file.writeText(content)
            } catch (e: Exception) {
                diagnostics.warn("Dex transform failed for ${file.name}: ${e.message}")
            }
        }

        diagnostics.log("DexTransformer: replaced $replacedCount authority/package references in ${smaliFiles.size} smali files")

        // Inject hook framework dex (secondary dex)
        injectHookFramework(smaliRoot, config, diagnostics)
    }

    private fun injectHookFramework(smaliRoot: File, config: CloneConfig, diagnostics: CloningDiagnostics) {
        // Create directory for hook smali
        val hookDir = File(smaliRoot, "com/clonemaster/hooks")
        hookDir.mkdirs()

        // Generate HookApplication smali that wraps original
        val hookAppSmali = """
            .class public Lcom/clonemaster/hooks/HookApplication;
            .super Landroid/app/Application;
            
            .field private static originalApp:Landroid/app/Application;
            
            .method public constructor <init>()V
                .locals 0
                invoke-direct {p0}, Landroid/app/Application;-><init>()V
                return-void
            .end method
            
            .method protected attachBaseContext(Landroid/content/Context;)V
                .locals 2
                invoke-super {p0, p1}, Landroid/app/Application;->attachBaseContext(Landroid/content/Context;)V
                # Initialize hook framework
                const-string v0, "CloneMaster"
                const-string v1, "Initializing hooks"
                invoke-static {v0, v1}, Landroid/util/Log;->d(Ljava/lang/String;Ljava/lang/String;)I
                invoke-static {p1}, Lcom/clonemaster/hooks/HookFramework;->init(Landroid/content/Context;)V
                return-void
            .end method
            
            .method public onCreate()V
                .locals 1
                invoke-super {p0}, Landroid/app/Application;->onCreate()V
                iget-object v0, p0, Lcom/clonemaster/hooks/HookApplication;->originalApp:Landroid/app/Application;
                if-eqz v0, :cond_0
                invoke-virtual {v0}, Landroid/app/Application;->onCreate()V
                :cond_0
                return-void
            .end method
        """.trimIndent()

        File(hookDir, "HookApplication.smali").writeText(hookAppSmali)

        // HookFramework smali
        val hookFrameworkSmali = """
            .class public Lcom/clonemaster/hooks/HookFramework;
            .super Ljava/lang/Object;
            
            .method public static init(Landroid/content/Context;)V
                .locals 3
                .param p0, "context"
                # Load clone_config.json
                # Init all subsystems per config
                invoke-static {}, Lcom/clonemaster/hooks/IdentityHooks;->install()V
                invoke-static {}, Lcom/clonemaster/hooks/PrivacyHooks;->install()V
                invoke-static {}, Lcom/clonemaster/hooks/DisplayHooks;->install()V
                invoke-static {}, Lcom/clonemaster/hooks/StorageHooks;->install()V
                invoke-static {}, Lcom/clonemaster/hooks/NetworkingHooks;->install()V
                invoke-static {}, Lcom/clonemaster/hooks/MediaHooks;->install()V
                return-void
            .end method
        """.trimIndent()
        File(hookDir, "HookFramework.smali").writeText(hookFrameworkSmali)

        // Create stub hooks for each subsystem
        listOf("IdentityHooks", "PrivacyHooks", "DisplayHooks", "StorageHooks", "NetworkingHooks", "MediaHooks", "ViewModEngine", "AutomationEngine").forEach { name ->
            File(hookDir, "$name.smali").writeText("""
                .class public Lcom/clonemaster/hooks/$name;
                .super Ljava/lang/Object;
                .method public static install()V
                    .locals 0
                    return-void
                .end method
            """.trimIndent())
        }

        diagnostics.log("Injected hook framework into smali")
    }

    /**
     * For binary dex transformation (without smali), we would use dexlib2 to rewrite string pool.
     * Stub for future.
     */
    fun transformDexFiles(dexFiles: List<File>, config: CloneConfig, authorityMap: Map<String, String>, diagnostics: CloningDiagnostics) {
        diagnostics.log("Binary dex transform: ${dexFiles.size} files, authority map size ${authorityMap.size}")
        // Real implementation: use org.jf.dexlib2 to iterate string references
    }
}
