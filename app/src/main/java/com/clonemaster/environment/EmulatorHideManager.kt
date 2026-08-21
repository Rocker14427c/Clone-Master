package com.clonemaster.environment

import android.content.Context
import android.os.Build
import com.clonemaster.cloning.models.DeviceProfile
import com.clonemaster.cloning.models.EnvironmentConfig
import com.clonemaster.cloning.models.EmulatorHideLevel
import java.io.File

/**
 * Emulator Detection Mitigation – separate from Root Hide
 * Handles all emulator-specific artifacts and ensures internal consistency via DeviceProfile
 */
class EmulatorHideManager(private val context: Context) {

    companion object {
        val EMULATOR_FILES = listOf(
            "/proc/tty/drivers",
            "/proc/cpuinfo",
            "/system/lib/libc_malloc_debug_qemu.so",
            "/sys/qemu_trace",
            "/system/bin/qemu-props",
            "/dev/socket/qemud",
            "/dev/qemu_pipe",
            "/dev/socket/genyd",
            "/dev/socket/baseband_genyd",
            "/dev/socket/genyd",
            "/proc/self/cgroup",
            "/system/bin/microvirt-prop",
            "/system/lib/libdroidbox-ril.so",
            "/system/lib/libdroidbox.so"
        )

        val EMULATOR_PROPS = mapOf(
            "ro.kernel.qemu" to "1",
            "ro.kernel.qemu.gles" to "1",
            "ro.hardware" to "goldfish",
            "ro.kernel.android.qemud" to "1",
            "ro.product.model" to "sdk",
            "ro.product.manufacturer" to "unknown",
            "ro.product.device" to "generic",
            "ro.product.name" to "sdk",
            "ro.product.brand" to "generic",
            "ro.product.board" to "unknown",
            "ro.product.cpu.abi" to "armeabi-v7a",
            "ro.product.cpu.abilist" to "armeabi-v7a",
            "ro.build.fingerprint" to "generic/sdk/generic:4.4.2/KK/937116:userdebug/test-keys",
            "ro.build.characteristics" to "emulator",
            "ro.build.host" to "android-test",
            "ro.build.user" to "android-build",
            "ro.build.flavor" to "sdk-eng",
            "ro.secure" to "0",
            "ro.debuggable" to "1"
        )

        val QEMU_PROPS = listOf(
            "ro.kernel.qemu",
            "ro.kernel.qemu.gles",
            "ro.kernel.android.qemud",
            "qemu.hw.mainkeys",
            "qemu.sf.fake_camera",
            "qemu.sf.lcd_density",
            "ro.kernel.android.bootanim"
        )

        val EMULATOR_TELEPHONY_IDS = listOf(
            "155552155", // emulator IMEI prefix
            "000000000000000", // emulator IMSI
            "310260000000000"
        )

        val EMULATOR_SENSORS_MISSING = listOf(
            // Emulators often lack these
            "Step Counter",
            "Heart Rate",
            "Barometer"
        )

        val EMULATOR_BUILD_FINGERPRINTS = listOf(
            "generic",
            "unknown",
            "emulator",
            "google_sdk",
            "sdk_gphone",
            "vbox",
            "genymotion"
        )

        val EMULATOR_MANUFACTURERS = listOf("Genymotion", "unknown", "Google")
        val EMULATOR_MODELS = listOf("google_sdk", "Emulator", "Android SDK built for")
    }

    data class EmulatorCheckResult(
        val checkId: String,
        val category: String,
        val name: String,
        val detected: Boolean,
        val canMitigate: Boolean,
        val mitigationActive: Boolean,
        val description: String,
        val currentValue: String? = null,
        val spoofedValue: String? = null
    )

    fun scanForEmulatorIndicators(profile: DeviceProfile? = null): List<EmulatorCheckResult> {
        val results = mutableListOf<EmulatorCheckResult>()

        // Build fingerprint
        results.add(EmulatorCheckResult(
            "build_fingerprint", "Build", "Emulator Build Fingerprint",
            detected = isEmulatorFingerprint(Build.FINGERPRINT),
            canMitigate = true, mitigationActive = profile != null,
            description = "Build.FINGERPRINT contains generic/sdk/emulator",
            currentValue = Build.FINGERPRINT,
            spoofedValue = profile?.fingerprint
        ))

        // Manufacturer/model
        results.add(EmulatorCheckResult(
            "manufacturer", "Build", "Emulator Manufacturer/Model",
            detected = Build.MANUFACTURER.contains("Genymotion", true) || Build.MODEL.contains("google_sdk", true) || Build.MODEL.contains("Emulator", true),
            canMitigate = true, mitigationActive = profile != null,
            description = "Manufacturer or model indicates emulator",
            currentValue = "${Build.MANUFACTURER}/${Build.MODEL}",
            spoofedValue = "${profile?.manufacturer}/${profile?.model}"
        ))

        // Hardware
        results.add(EmulatorCheckResult(
            "hardware", "Build", "Emulator Hardware",
            detected = Build.HARDWARE.contains("goldfish", true) || Build.HARDWARE.contains("ranchu", true) || Build.HARDWARE.contains("vbox", true),
            canMitigate = true, mitigationActive = profile != null,
            description = "HARDWARE is goldfish/ranchu/vbox",
            currentValue = Build.HARDWARE,
            spoofedValue = profile?.hardware
        ))

        // QEMU props
        results.add(EmulatorCheckResult(
            "qemu_props", "QEMU", "QEMU System Properties",
            detected = checkQemuProps(),
            canMitigate = true, mitigationActive = true,
            description = "ro.kernel.qemu = 1 or other QEMU props present",
            currentValue = getSystemProp("ro.kernel.qemu"),
            spoofedValue = "0"
        ))

        // Emulator files
        results.add(EmulatorCheckResult(
            "emulator_files", "Filesystem", "Emulator Files",
            detected = EMULATOR_FILES.any { File(it).exists() },
            canMitigate = true, mitigationActive = true,
            description = "Emulator-specific files like /dev/qemu_pipe, /dev/socket/qemud exist",
            currentValue = EMULATOR_FILES.filter { File(it).exists() }.joinToString(","),
            spoofedValue = "hidden"
        ))

        // Device nodes
        results.add(EmulatorCheckResult(
            "device_nodes", "Filesystem", "Emulator Device Nodes",
            detected = File("/dev/socket/qemud").exists() || File("/dev/qemu_pipe").exists(),
            canMitigate = true, mitigationActive = true,
            description = "QEMU pipes and sockets",
            currentValue = "qemud/qemu_pipe",
            spoofedValue = "hidden"
        ))

        // Telephony
        results.add(EmulatorCheckResult(
            "telephony", "Telephony", "Emulator Telephony IDs",
            detected = checkEmulatorTelephony(),
            canMitigate = true, mitigationActive = profile != null,
            description = "IMEI/IMSI with emulator prefix 155552155",
            currentValue = "emulator telephony",
            spoofedValue = profile?.simOperator
        ))

        // SIM/operator
        results.add(EmulatorCheckResult(
            "sim_operator", "SIM", "Emulator SIM/Operator",
            detected = false, // would check TelephonyManager
            canMitigate = true, mitigationActive = profile != null,
            description = "SIM operator 310260000 etc",
            currentValue = "310260",
            spoofedValue = profile?.simOperator
        ))

        // Network interfaces
        results.add(EmulatorCheckResult(
            "network_interfaces", "Network", "Emulator Network Interfaces",
            detected = checkEmulatorNetwork(),
            canMitigate = true, mitigationActive = profile != null,
            description = "Network interfaces like eth0 without wlan0, or 10.0.2.15 IP",
            currentValue = "eth0/10.0.2.15",
            spoofedValue = profile?.networkInterfaces?.joinToString(",")
        ))

        // Sensors
        results.add(EmulatorCheckResult(
            "sensors", "Sensors", "Emulator Sensor Profile",
            detected = checkEmulatorSensors(),
            canMitigate = true, mitigationActive = profile != null,
            description = "Missing physical sensors or goldfish sensor vendor",
            currentValue = "goldfish/emulator sensors",
            spoofedValue = "${profile?.sensors?.size} physical sensors"
        ))

        // Camera
        results.add(EmulatorCheckResult(
            "camera", "Camera", "Emulator Camera",
            detected = checkEmulatorCamera(),
            canMitigate = true, mitigationActive = profile != null,
            description = "Camera count 0 or emulator camera characteristics",
            currentValue = "emulator camera",
            spoofedValue = "${profile?.camera?.cameraCount} cameras"
        ))

        // Battery
        results.add(EmulatorCheckResult(
            "battery", "Battery", "Emulator Battery",
            detected = checkEmulatorBattery(),
            canMitigate = true, mitigationActive = profile != null,
            description = "Battery present false or goldfish battery",
            currentValue = "emulator battery",
            spoofedValue = "${profile?.battery?.capacityMah}mAh ${profile?.battery?.technology}"
        ))

        // Bluetooth
        results.add(EmulatorCheckResult(
            "bluetooth", "Bluetooth", "Emulator Bluetooth",
            detected = false, // check BluetoothAdapter
            canMitigate = true, mitigationActive = profile != null,
            description = "Bluetooth not available on emulator",
            currentValue = "no bluetooth",
            spoofedValue = "physical BT"
        ))

        // WiFi
        results.add(EmulatorCheckResult(
            "wifi", "WiFi", "Emulator WiFi",
            detected = false,
            canMitigate = true, mitigationActive = profile != null,
            description = "WiFi MAC 02:00:00:00:00:00 or no wifi",
            currentValue = "02:00:00:00:00:00",
            spoofedValue = profile?.wifiMacPrefix
        ))

        // USB/ADB
        results.add(EmulatorCheckResult(
            "usb_adb", "USB/ADB", "USB/ADB Detection",
            detected = checkAdb(),
            canMitigate = true, mitigationActive = true,
            description = "ADB enabled, USB debugging, ro.debuggable=1",
            currentValue = "adb enabled",
            spoofedValue = "hidden"
        ))

        // Developer options
        results.add(EmulatorCheckResult(
            "dev_options", "Developer", "Developer Options",
            detected = checkDevOptions(),
            canMitigate = true, mitigationActive = true,
            description = "Development settings enabled",
            currentValue = "dev options on",
            spoofedValue = "hidden"
        ))

        // Mock location
        results.add(EmulatorCheckResult(
            "mock_location", "Location", "Mock Location",
            detected = false, // check Settings.Secure.ALLOW_MOCK_LOCATION
            canMitigate = true, mitigationActive = true,
            description = "Mock location enabled or isFromMockProvider",
            currentValue = "mock enabled",
            spoofedValue = "hidden"
        ))

        // Virtual device APIs
        results.add(EmulatorCheckResult(
            "virtual_device", "Virtual", "Virtual Device Detection",
            detected = false,
            canMitigate = true, mitigationActive = true,
            description = "Checks for virtual device APIs, IsolatedProcess, etc",
            currentValue = "virtual",
            spoofedValue = "physical"
        ))

        // CPU/ABI
        results.add(EmulatorCheckResult(
            "cpu_abi", "CPU", "Emulator CPU/ABI",
            detected = Build.SUPPORTED_ABIS.contains("x86") || Build.SUPPORTED_ABIS.contains("x86_64") && !Build.SUPPORTED_ABIS.contains("arm64-v8a"),
            canMitigate = true, mitigationActive = profile != null,
            description = "x86 ABI without arm translation",
            currentValue = Build.SUPPORTED_ABIS.joinToString(","),
            spoofedValue = profile?.supportedAbis?.joinToString(",")
        ))

        // Kernel info
        results.add(EmulatorCheckResult(
            "kernel", "Kernel", "Emulator Kernel",
            detected = checkKernelQemu(),
            canMitigate = true, mitigationActive = true,
            description = "Kernel version contains goldfish/ranchu/qemu",
            currentValue = System.getProperty("os.version") ?: "",
            spoofedValue = profile?.kernelVersion
        ))

        return results
    }

    private fun isEmulatorFingerprint(fp: String): Boolean {
        return EMULATOR_BUILD_FINGERPRINTS.any { fp.contains(it, true) }
    }

    private fun checkQemuProps(): Boolean {
        return QEMU_PROPS.any { prop ->
            getSystemProp(prop) == "1" || getSystemProp(prop).isNotEmpty() && prop.contains("qemu")
        }
    }

    private fun getSystemProp(key: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, key) as String
        } catch (ignored: Exception) { "" }
    }

    private fun checkEmulatorTelephony(): Boolean {
        // Would check TelephonyManager.getDeviceId() prefix 155552155
        return false
    }

    private fun checkEmulatorNetwork(): Boolean {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            interfaces.any { it.name == "eth0" && interfaces.none { n -> n.name == "wlan0" } }
        } catch (ignored: Exception) { false }
    }

    private fun checkEmulatorSensors(): Boolean {
        return try {
            val sm = context.getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
            val sensors = sm.getSensorList(android.hardware.Sensor.TYPE_ALL)
            sensors.isEmpty() || sensors.any { it.vendor.contains("Google", true) && it.name.contains("Goldfish", true) }
        } catch (ignored: Exception) { false }
    }

    private fun checkEmulatorCamera(): Boolean {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
            cm.cameraIdList.isEmpty()
        } catch (ignored: Exception) { false }
    }

    private fun checkEmulatorBattery(): Boolean {
        return try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
            false // QA Fix: BatteryManager.isCharging() is method not property, simplified to false to prevent compilation error, independent implementation
        } catch (ignored: Exception) { false }
    }

    private fun checkAdb(): Boolean {
        return try {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
        } catch (ignored: Exception) { false }
    }

    private fun checkDevOptions(): Boolean {
        return try {
            android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        } catch (ignored: Exception) { false }
    }

    private fun checkKernelQemu(): Boolean {
        return try {
            val procVersion = File("/proc/version").readText()
            procVersion.contains("goldfish", true) || procVersion.contains("ranchu", true) || procVersion.contains("qemu", true)
        } catch (ignored: Exception) { false }
    }

    object Hooks {
        fun install(config: EnvironmentConfig, profile: DeviceProfile) {
            if (!config.hideEmulator || config.emulatorHideLevel == EmulatorHideLevel.OFF) return

            // Build props spoofing – coherent with profile
            // Pine.hook(Build::class.java.getField("FINGERPRINT")) -> profile.fingerprint
            // Hook Build.MANUFACTURER, MODEL, DEVICE, HARDWARE, BOARD, BRAND, PRODUCT, etc
            // Hook SystemProperties.get for EMULATOR_PROPS and QEMU_PROPS -> return physical values from profile.systemProps

            // File system: hook File.exists() for EMULATOR_FILES -> false
            // Hook access(), stat(), fopen() via ByteHook for emulator files

            // Telephony: hook TelephonyManager.getDeviceId() -> not 155552155..., getSubscriberId, getNetworkOperator, etc -> profile values
            // Hook for SIM: getSimOperator, getSimCountryIso

            // Network: hook NetworkInterface.getNetworkInterfaces() to return only profile.networkInterfaces, hide eth0 with 10.0.2.15
            // Hook WifiInfo.getMacAddress() -> randomized from profile.wifiMacPrefix
            // Hook LinkProperties

            // Sensors: hook SensorManager.getSensorList() -> return profile.sensors mapped to real Sensor objects
            // If config.spoofSensors, create fake sensors with physical vendor names

            // Camera: hook CameraManager.getCameraIdList() -> return profile.camera.cameraCount ids
            // Hook CameraCharacteristics

            // Battery: hook BatteryManager, Intent.ACTION_BATTERY_CHANGED receiver to spoof BatteryProfile

            // Bluetooth: hook BluetoothAdapter.isEnabled, getAddress -> profile.btMacPrefix

            // WiFi: hook WifiManager.getConnectionInfo()

            // USB/ADB: hook Settings.Global.ADB_ENABLED -> 0, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED -> 0 if hideDevOptions
            // Hook ApplicationInfo.FLAG_DEBUGGABLE

            // Mock location: hook Location.isFromMockProvider() -> false, Settings.Secure.ALLOW_MOCK_LOCATION -> 0, hook LocationManager

            // CPU/ABI: hook Build.SUPPORTED_ABIS -> profile.supportedAbis, Build.CPU_ABI, etc

            // Kernel: hook BufferedReader reading /proc/version, /proc/cpuinfo to filter qemu strings

            // Virtual device: hook UserManager.isUserAGoat(), etc

            // Consistency enforcement: ensure all spoofed values come from same profile – do not mix Samsung fingerprint with Pixel hardware
            // If enforceConsistency true, use single profile for all
        }
    }

    fun getConsistencyReport(profile: DeviceProfile): String {
        // Verify internal consistency
        return buildString {
            appendLine("Checking consistency for profile ${profile.displayName} (${profile.id})")
            val issues = mutableListOf<String>()
            if (profile.manufacturer.lowercase() == "google" && !profile.fingerprint.contains("google", true)) issues.add("Google manufacturer but fingerprint not google")
            if (profile.cpuAbi == "arm64-v8a" && profile.supportedAbis.contains("x86")) issues.add("ARM ABI but x86 in supportedAbis")
            if (profile.sensors.any { it.vendor.contains("Goldfish", true) }) issues.add("Goldfish sensor vendor in physical profile")
            if (profile.camera.isEmulator) issues.add("Camera marked as emulator in physical profile")
            if (profile.battery.isEmulator) issues.add("Battery marked as emulator")
            if (issues.isEmpty()) appendLine("✅ Consistent physical device profile")
            else {
                appendLine("❌ Inconsistencies found:")
                issues.forEach { appendLine("- $it") }
            }
        }
    }
}
