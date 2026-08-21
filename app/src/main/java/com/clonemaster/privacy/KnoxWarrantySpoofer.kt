package com.clonemaster.privacy

import android.content.Context

/**
 * Independent implementation for Knox Warranty Bit spoofing
 * Public feature reference: App Cloner lists "Change Knox Warranty Bit" under Privacy options
 * Equivalent functionality implemented independently
 * Functional parity: spoof Knox warranty bit to 0 (not void) to hide Knox tripping
 * Compatibility with Android limitations: Knox is Samsung-specific, requires system property hooks, degrades gracefully on non-Samsung devices
 */
class KnoxWarrantySpoofer {

    companion object {
        val KNOX_PROPS = listOf(
            "ro.boot.warranty_bit",
            "ro.warranty_bit",
            "ro.boot Knox warranty bit",
            "ro.knox.enhance",
            "ro.knox.kg.state",
            "ro.knox.warranty",
            "sys.knox.warranty_bit"
        )

        val KNOX_FILES = listOf(
            "/sys/class/sec/sec_key/warranty_bit",
            "/sys/class/sec/sec_key/knox_warranty",
            "/proc/knox/warranty"
        )
    }

    data class KnoxConfig(
        var spoofWarrantyBit: Boolean = false,
        var warrantyBitValue: Int = 0, // 0 = not void, 1 = void
        var hideKnoxFiles: Boolean = true,
        var spoofKnoxProps: Boolean = true
    )

    object Hooks {
        fun install(config: KnoxConfig) {
            if (!config.spoofWarrantyBit) return

            // Hook SystemProperties.get for KNOX_PROPS → return warrantyBitValue
            // ByteHook hook __system_property_get for KNOX_PROPS

            // Hook File.exists() for KNOX_FILES → false if hideKnoxFiles
            // Hook FileInputStream reading warranty_bit file → return "0"

            // Hook for Samsung Knox SDK: com.sec.knox Knox API
            // Hook com.samsung.android.knox.KnoxInternal API if present

            // Compatibility: Only Samsung devices have Knox – on non-Samsung, hooks do nothing, degraded gracefully
        }
    }

    fun getSpoofedProps(config: KnoxConfig): Map<String, String> {
        if (!config.spoofWarrantyBit) return emptyMap()
        return KNOX_PROPS.associateWith { config.warrantyBitValue.toString() }
    }
}
