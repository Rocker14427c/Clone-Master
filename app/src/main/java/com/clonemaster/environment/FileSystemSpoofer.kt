package com.clonemaster.environment

import com.clonemaster.cloning.models.EnvironmentConfig
import java.io.File

/**
 * Filesystem hiding – hides root and emulator artifacts from File.exists(), access(), stat(), fopen()
 */
class FileSystemSpoofer {

    fun getPathsToHide(config: EnvironmentConfig): List<String> {
        val paths = mutableListOf<String>()
        if (config.hideRoot && config.hideRootPaths) {
            paths.addAll(RootHideManager.SU_PATHS)
            paths.addAll(listOf(
                "/system/app/Superuser.apk",
                "/data/adb/magisk",
                "/sbin/.magisk",
                "/system/xbin/busybox",
                "/system/bin/busybox"
            ))
        }
        if (config.hideEmulator && config.hideEmulatorFiles) {
            paths.addAll(EmulatorHideManager.EMULATOR_FILES)
        }
        return paths.distinct()
    }

    object Hooks {
        fun install(pathsToHide: List<String>) {
            // Hook File.exists() – Pine
            // Hook File.canRead(), canExecute()
            // Native hooks via ByteHook:
            // - hook access(const char* pathname, int mode) in libc.so -> if pathname in pathsToHide return -1 with ENOENT
            // - hook stat(), lstat(), __stat(), fopen(), open()
            // - hook opendir(), readdir() to filter out su, magisk, qemu entries when listing /system/bin, /system/xbin, /dev, /proc
            // - hook BufferedReader for /proc/mounts, /proc/self/cgroup, /proc/cpuinfo, /proc/version to filter suspicious lines
            // - hook Runtime.exec() to block "su", "which su", "busybox", "magisk", "qemu-props"

            // Example pseudo:
            // ByteHook.hook("libc.so", "access") { pathname, mode ->
            //   if (pathsToHide.any { pathname.contains(it) }) { set errno=ENOENT; return -1 }
            //   else return original(pathname, mode)
            // }
        }
    }

    fun generateFilteredProcFile(originalPath: String, originalContent: String): String {
        return when {
            originalPath == "/proc/mounts" -> {
                originalContent.lines().filterNot { line ->
                    line.contains("magisk", true) || line.contains("su", true) && line.contains("/data/adb")
                }.joinToString("\n")
            }
            originalPath == "/proc/cpuinfo" -> {
                // Replace goldfish/ranchu/qemu with physical CPU info
                originalContent.replace("Goldfish", "Qualcomm").replace("ranchu", "qcom").replace("QEMU", "Qualcomm")
            }
            originalPath == "/proc/version" -> {
                if (originalContent.contains("goldfish", true) || originalContent.contains("ranchu", true)) {
                    "Linux version 5.15.131-android14-11 (android-build@abfarm-01117) (Android clang version 17.0.2) #1 SMP PREEMPT Thu Sep 5 03:33:15 UTC 2024"
                } else originalContent
            }
            originalPath == "/proc/tty/drivers" -> {
                // Filter goldfish
                originalContent.lines().filterNot { it.contains("goldfish", true) }.joinToString("\n")
            }
            originalPath == "/proc/self/cgroup" -> {
                originalContent.lines().filterNot { it.contains("magisk", true) }.joinToString("\n")
            }
            else -> originalContent
        }
    }
}
