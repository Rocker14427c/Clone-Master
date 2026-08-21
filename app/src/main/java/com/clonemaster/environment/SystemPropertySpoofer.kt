package com.clonemaster.environment

import com.clonemaster.cloning.models.DeviceProfile
import com.clonemaster.cloning.models.EnvironmentConfig

/**
 * System property spoofing – handles ro.* props for both root and emulator hiding
 * Uses ByteHook to hook __system_property_get (libc) and Pine for android.os.SystemProperties
 */
class SystemPropertySpoofer {

    // All props that should be hidden/spoofed for root
    private val rootPropsToSpoof = mapOf(
        "ro.debuggable" to "0",
        "ro.secure" to "1",
        "ro.build.selinux" to "1",
        "ro.build.type" to "user",
        "ro.build.tags" to "release-keys",
        "service.adb.root" to "0",
        "ro.kernel.android.checkjni" to "0",
        "ro.build.flavor" to "user",
        "ro.build.host" to "abfarm",
        "ro.build.user" to "android-build"
    )

    // Emulator props to hide
    private val emulatorPropsToHide = listOf(
        "ro.kernel.qemu",
        "ro.kernel.qemu.gles",
        "ro.kernel.android.qemud",
        "ro.hardware",
        "ro.revision"
    )

    fun getSpoofedProps(config: EnvironmentConfig, profile: DeviceProfile): Map<String, String> {
        val result = mutableMapOf<String, String>()

        if (config.hideRoot && config.hideRootProperties) {
            result.putAll(rootPropsToSpoof)
        }

        if (config.hideEmulator && config.hideQemuProps) {
            // Override emulator props with physical values
            result["ro.kernel.qemu"] = "0"
            result["ro.kernel.qemu.gles"] = "0"
            result["ro.kernel.android.qemud"] = ""
            result["ro.hardware"] = profile.hardware
            result["ro.revision"] = "0"
            result["ro.product.model"] = profile.model
            result["ro.product.manufacturer"] = profile.manufacturer
            result["ro.product.brand"] = profile.brand
            result["ro.product.device"] = profile.device
            result["ro.product.board"] = profile.board
            result["ro.product.cpu.abi"] = profile.cpuAbi
            result["ro.product.cpu.abilist"] = profile.supportedAbis.joinToString(",")
            result["ro.build.fingerprint"] = profile.fingerprint
            result["ro.build.characteristics"] = "nosdcard"
            result["ro.build.host"] = profile.buildHost
            result["ro.build.user"] = profile.buildUser
            result["ro.build.flavor"] = "${profile.device}-user"
            result["ro.secure"] = "1"
            result["ro.debuggable"] = "0"
        }

        if (config.hideUsbAdb && config.spoofUsbAdbProps) {
            result["service.adb.root"] = "0"
            result["sys.usb.state"] = "mtp"
            result["sys.usb.config"] = "mtp"
            result["ro.adb.secure"] = "1"
            result["ro.debuggable"] = "0"
        }

        // Custom overrides from profile
        result.putAll(profile.systemProps)

        return result
    }

    object Hooks {
        fun install(props: Map<String, String>) {
            // Real implementation:
            // 1. Pine hook android.os.SystemProperties.get(String) -> if key in props return spoofed else original
            // 2. Pine hook SystemProperties.get(key, def) similarly
            // 3. ByteHook hook __system_property_get in libc.so:
            //    int __system_property_get(const char* name, char* value) { if name in props { strcpy(value, props[name]); return strlen; } else return original; }
            // 4. Also hook __system_property_find, __system_property_read_callback for completeness

            // For Java Build fields, hook via reflection:
            // Build.FINGERPRINT, MANUFACTURER, MODEL, etc are static final – need to use reflection + Pine to override getters or direct field modification via Unsafe
        }
    }
}
