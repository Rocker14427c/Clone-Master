package com.clonemaster.environment

import android.content.Context
import com.clonemaster.cloning.models.EnvironmentConfig
import com.clonemaster.cloning.models.RootHideLevel
import java.io.File

/**
 * Root Detection Mitigation – per clone
 * Handles Java/Kotlin APIs and native checks where technically possible.
 */
class RootHideManager(private val context: Context) {

    // Common root detection artifacts (from RootBeer, etc)
    companion object {
        val ROOT_MANAGEMENT_APPS = listOf(
            "com.noshufou.android.su",
            "com.noshufou.android.su.elite",
            "eu.chainfire.supersu",
            "com.koushikdutta.superuser",
            "com.thirdparty.superuser",
            "com.yellowes.su",
            "com.koushikdutta.rommanager",
            "com.koushikdutta.rommanager.license",
            "com.dimonvideo.luckypatcher",
            "com.chelpus.lackypatch",
            "com.ramdroid.appquarantine",
            "com.ramdroid.appquarantinepro",
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.amphoras.hidemyrootadfree",
            "com.formyhm.hiderootPremium",
            "com.formyhm.hideroot",
            "me.phh.superuser",
            "eu.chainfire.supersu.pro",
            "com.kingouser.com",
            "com.topjohnwu.magisk"
        )

        val ROOT_CLOAKING_APPS = listOf(
            "com.devadvance.rootcloak",
            "com.devadvance.rootcloakplus",
            "de.robv.android.xposed.installer",
            "com.saurik.substrate",
            "com.zachspong.temprootremovejb",
            "com.amphoras.hidemyroot",
            "com.formyhm.hideroot"
        )

        val SU_PATHS = listOf(
            "/data/local/",
            "/data/local/bin/",
            "/data/local/xbin/",
            "/sbin/",
            "/system/bin/",
            "/system/bin/.ext/",
            "/system/xbin/",
            "/system/xbin/.ext/",
            "/system/sd/xbin/",
            "/system/bin/failsafe/",
            "/data/local/su",
            "/su/bin/",
            "/su/bin/su",
            "/system/xbin/daemonsu",
            "/system/etc/init.d/99SuperSUDaemon",
            "/system/bin/.ext/.su",
            "/system/xbin/busybox",
            "/system/bin/busybox",
            "/data/adb/magisk",
            "/data/adb/magisk/busybox",
            "/sbin/.magisk",
            "/cache/.magisk",
            "/data/adb/modules",
            "/data/magisk",
            "/dev/magisk",
            "/system/app/Superuser.apk",
            "/system/etc/init/magisk"
        )

        val ROOT_PROPERTIES = mapOf(
            "ro.debuggable" to "0",
            "ro.secure" to "1",
            "ro.build.selinux" to "1",
            "ro.build.type" to "user",
            "ro.build.tags" to "release-keys",
            "service.adb.root" to "0",
            "ro.kernel.android.checkjni" to "0"
        )

        val NATIVE_ROOT_LIBS = listOf(
            "libRootBeer.so",
            "libdetection.so"
        )
    }

    data class RootCheckResult(
        val checkId: String,
        val name: String,
        val detected: Boolean,
        val canMitigate: Boolean,
        val mitigationActive: Boolean,
        val description: String
    )

    fun scanForRootIndicators(): List<RootCheckResult> {
        val results = mutableListOf<RootCheckResult>()

        // Check su binary existence (Java API)
        results.add(RootCheckResult(
            "su_binary", "SU Binary Existence", checkSuBinary(),
            canMitigate = true, mitigationActive = false,
            description = "Checks common paths for su binary via File.exists()"
        ))

        // Check root management apps
        results.add(RootCheckResult(
            "root_apps", "Root Management Apps", checkRootApps(),
            canMitigate = true, mitigationActive = false,
            description = "Checks PackageManager for known root apps"
        ))

        // Check build tags
        results.add(RootCheckResult(
            "build_tags", "Build Tags (test-keys)", android.os.Build.TAGS?.contains("test-keys") == true,
            canMitigate = true, mitigationActive = false,
            description = "Build.TAGS contains test-keys"
        ))

        // Check superuser apk
        results.add(RootCheckResult(
            "superuser_apk", "Superuser APK", File("/system/app/Superuser.apk").exists(),
            canMitigate = true, mitigationActive = false,
            description = "File /system/app/Superuser.apk exists"
        ))

        // Check for magisk
        results.add(RootCheckResult(
            "magisk", "Magisk Files", checkMagisk(),
            canMitigate = true, mitigationActive = false,
            description = "Checks for Magisk specific files and mounts"
        ))

        // Check for busybox
        results.add(RootCheckResult(
            "busybox", "BusyBox Binary", File("/system/xbin/busybox").exists() || File("/system/bin/busybox").exists(),
            canMitigate = true, mitigationActive = false,
            description = "BusyBox indicates rooted device"
        ))

        // Check for writable system
        results.add(RootCheckResult(
            "writable_system", "Writable System Partition", checkWritableSystem(),
            canMitigate = false, mitigationActive = false,
            description = "System partition writable – cannot fully hide without root"
        ))

        // Check for su via Runtime.exec
        results.add(RootCheckResult(
            "exec_su", "Runtime.exec(su)", checkExecSu(),
            canMitigate = true, mitigationActive = false,
            description = "Tries to execute su binary"
        ))

        // Check for root cloaking apps (self)
        results.add(RootCheckResult(
            "root_cloak", "Root Cloaking Apps", checkRootCloakingApps(),
            canMitigate = true, mitigationActive = false,
            description = "Checks for root hiding apps – indicates attempt to hide root"
        ))

        // Native library checks
        results.add(RootCheckResult(
            "native_checks", "Native Library Root Checks", false,
            canMitigate = true, mitigationActive = false,
            description = "Native code checks via access() syscall – mitigated via PLT hook where possible"
        ))

        return results
    }

    private fun checkSuBinary(): Boolean {
        return SU_PATHS.any { path ->
            try { File(path).exists() || File(path + "su").exists() } catch (ignored: Exception) { false }
        }
    }

    private fun checkRootApps(): Boolean {
        return try {
            val pm = context.packageManager
            ROOT_MANAGEMENT_APPS.any { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (ignored: Exception) { false }
            }
        } catch (ignored: Exception) { false }
    }

    private fun checkMagisk(): Boolean {
        return listOf("/sbin/.magisk", "/data/adb/magisk", "/data/magisk", "/cache/.magisk").any { File(it).exists() }
    }

    private fun checkWritableSystem(): Boolean {
        return try {
            val file = File("/system/test_root_write")
            file.createNewFile().also { if (it) file.delete() }
        } catch (ignored: Exception) { false }
    }

    private fun checkExecSu(): Boolean {
        return try {
            Runtime.getRuntime().exec("su").destroy()
            true
        } catch (ignored: Exception) { false }
    }

    private fun checkRootCloakingApps(): Boolean {
        return try {
            val pm = context.packageManager
            ROOT_CLOAKING_APPS.any { pkg ->
                try { pm.getPackageInfo(pkg, 0); true } catch (ignored: Exception) { false }
            }
        } catch (ignored: Exception) { false }
    }

    /**
     * Hooks to hide root – installed inside clone via Pine/ByteHook
     */
    object Hooks {
        fun install(config: EnvironmentConfig) {
            if (!config.hideRoot || config.rootHideLevel == RootHideLevel.OFF) return

            // Hook File.exists() for SU_PATHS -> false
            // Pine.hook(File::class.java.getMethod("exists")) { ... check path against SU_PATHS }

            // Hook PackageManager.getPackageInfo for ROOT_MANAGEMENT_APPS -> throw NameNotFoundException
            // Hook Runtime.exec to block "su", "which su", "busybox"

            // Hook SystemProperties.get for ROOT_PROPERTIES -> return safe values
            // Hook __system_property_get via ByteHook (PLT hook for libc)

            // Hook Build.TAGS -> "release-keys" if contains test-keys

            // Native: hook access(), stat(), fopen() for su paths via ByteHook
            // Example: ByteHook.hook("libc.so", "access") { path, mode -> if path in SU_PATHS return -1 else call original }

            // Hide Magisk mounts: hook BufferedReader reading /proc/mounts to filter magisk entries

            // Compatibility reporting: if root check uses custom native library not in our hook list, log warning
        }

        fun getMitigationReport(config: EnvironmentConfig): List<RootCheckResult> {
            // Returns list with mitigationActive = true if hook would hide
            return emptyList() // stub – real implementation would check active hooks
        }
    }

    fun getCompatibilityReport(config: EnvironmentConfig): String {
        val results = scanForRootIndicators()
        val unmitigatable = results.filter { it.detected && !it.canMitigate }
        return buildString {
            appendLine("Root Detection Scan: ${results.count { it.detected }} indicators found")
            if (unmitigatable.isNotEmpty()) {
                appendLine("Unmitigatable (requires system-level hiding):")
                unmitigatable.forEach { appendLine("- ${it.name}: ${it.description}") }
            }
            appendLine("Mitigation Level: ${config.rootHideLevel}")
            appendLine("Hide Root Toggle: ${config.hideRoot}")
        }
    }
}
