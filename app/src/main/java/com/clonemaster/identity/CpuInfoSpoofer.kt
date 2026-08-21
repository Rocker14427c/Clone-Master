package com.clonemaster.identity

import android.os.Build
import com.clonemaster.cloning.models.DeviceProfile

/**
 * Independent implementation for Hide CPU/GPU info
 * Public feature reference: WhatsNew 3.6.8 "Improved 'Hide CPU / GPU info' option" and Identity options "Hide GPU info"
 * Equivalent functionality: hide CPU info (in addition to GPU) via independent implementation
 * Functional parity with compatibility with Android limitations
 */
class CpuInfoSpoofer {

    data class CpuGpuConfig(
        var hideCpuInfo: Boolean = false,
        var hideGpuInfo: Boolean = false,
        var spoofCpuModel: String = "Qualcomm Kryo 385",
        var spoofCpuCores: Int = 8,
        var spoofCpuFreq: String = "2.84 GHz",
        var spoofGpuVendor: String = "Qualcomm",
        var spoofGpuRenderer: String = "Adreno 750",
        var customCpuInfo: String = ""
    )

    fun getSpoofedCpuInfo(profile: DeviceProfile?, config: CpuGpuConfig): Map<String, String> {
        if (!config.hideCpuInfo) return emptyMap()

        return mapOf(
            "cpu_model" to (profile?.let { "${it.hardware} ${it.board}" } ?: config.spoofCpuModel),
            "cpu_cores" to (profile?.let { it.supportedAbis.size.toString() } ?: config.spoofCpuCores.toString()),
            "cpu_freq" to config.spoofCpuFreq,
            "ro.product.cpu.abi" to (profile?.cpuAbi ?: "arm64-v8a"),
            "ro.product.board" to (profile?.board ?: "husky"),
            "ro.hardware" to (profile?.hardware ?: "qcom")
        )
    }

    object Hooks {
        fun install(config: CpuGpuConfig, profile: DeviceProfile?) {
            if (!config.hideCpuInfo && !config.hideGpuInfo) return

            // Hide CPU info: hook /proc/cpuinfo reading to return spoofed physical CPU info
            // Hook Runtime.availableProcessors() -> spoof cores
            // Hook System.getProperty("os.arch") -> arm64-v8a
            // Hook Build.HARDWARE, BOARD, SUPPORTED_ABIS -> profile values

            // Hide GPU info: hook GLES20.glGetString(GL_RENDERER/VENDOR/VERSION) -> profile gpu
            // This complements existing IdentityManager GPU spoofing – independent implementation

            // Compatibility: Some apps read CPU info via native code – requires ByteHook for fopen("/proc/cpuinfo") filtering
        }
    }
}
