package com.clonemaster.environment

import android.content.Context
import com.clonemaster.cloning.models.DeviceProfile
import com.clonemaster.cloning.models.SensorProfile
import com.clonemaster.cloning.models.CameraProfile
import com.clonemaster.cloning.models.BatteryProfile
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Manages coherent physical device profiles.
 * A single DeviceProfile provides consistent environment across Build, Telephony, Sensors, Camera, GPU, Battery, Network, Filesystem.
 * Compatible with Identity/Profile system – IdentityConfig.deviceProfileName links to this.
 */
class DeviceProfileManager(private val context: Context) {

    private val profilesDir: File by lazy { File(context.filesDir, "device_profiles").apply { mkdirs() } }
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun getBuiltInProfiles(): List<DeviceProfile> = listOf(
        pixel8Pro(),
        pixel7a(),
        samsungS24Ultra(),
        samsungA54(),
        onePlus12(),
        xiaomi14Pro(),
        nothingPhone2(),
        samsungFold5()
    )

    fun pixel8Pro(): DeviceProfile = DeviceProfile(
        id = "pixel8_pro",
        displayName = "Pixel 8 Pro (Physical)",
        manufacturer = "Google",
        brand = "google",
        model = "Pixel 8 Pro",
        device = "husky",
        product = "husky",
        hardware = "husky",
        fingerprint = "google/husky/husky:14/AP2A.240905.003/12231197:user/release-keys",
        board = "husky",
        bootloader = "cloudripper-1.0-13138964",
        cpuAbi = "arm64-v8a",
        supportedAbis = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno 750",
        sensors = defaultSensorsPixel(),
        camera = CameraProfile(cameraCount = 3, hasFlash = true),
        battery = BatteryProfile(capacityMah = 5050, technology = "Li-ion"),
        simOperator = "310260",
        simOperatorName = "T-Mobile",
        networkOperator = "310260"
    )

    fun pixel7a(): DeviceProfile = DeviceProfile(
        id = "pixel7a",
        displayName = "Pixel 7a",
        manufacturer = "Google",
        brand = "google",
        model = "Pixel 7a",
        device = "lynx",
        product = "lynx",
        hardware = "lynx",
        fingerprint = "google/lynx/lynx:14/AP2A.240905.003/12231197:user/release-keys",
        board = "lynx",
        cpuAbi = "arm64-v8a",
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno 730",
        sensors = defaultSensorsPixel(),
        battery = BatteryProfile(capacityMah = 4385),
        simOperator = "310260",
        simOperatorName = "T-Mobile"
    )

    fun samsungS24Ultra(): DeviceProfile = DeviceProfile(
        id = "s24_ultra",
        displayName = "Samsung Galaxy S24 Ultra (SM-S928B)",
        manufacturer = "samsung",
        brand = "samsung",
        model = "SM-S928B",
        device = "e3q",
        product = "e3qxxx",
        hardware = "qcom",
        fingerprint = "samsung/e3qxxx/e3q:14/UP1A.231005.007/S928BXXU1AXCB:user/release-keys",
        board = "kalama",
        bootloader = "S928BXXU1AXCB",
        cpuAbi = "arm64-v8a",
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno 750",
        sensors = listOf(
            SensorProfile("LSM6DSO Accelerometer", "STM", 1),
            SensorProfile("LSM6DSO Gyroscope", "STM", 4),
            SensorProfile("AK09918 Magnetometer", "AKM", 2),
            SensorProfile("SAMSUNG Proximity", "Samsung", 8),
            SensorProfile("SAMSUNG Light", "Samsung", 5)
        ),
        camera = CameraProfile(cameraCount = 4, hasFlash = true),
        battery = BatteryProfile(capacityMah = 5000),
        simOperator = "310260",
        simOperatorName = "T-Mobile"
    )

    fun samsungA54(): DeviceProfile = DeviceProfile(
        id = "a54",
        displayName = "Samsung Galaxy A54",
        manufacturer = "samsung",
        brand = "samsung",
        model = "SM-A546B",
        device = "a54x",
        product = "a54xnsxx",
        fingerprint = "samsung/a54xnsxx/a54x:14/UP1A.231005.007/A546BXXU6CXI1:user/release-keys",
        board = "s5e8835",
        cpuAbi = "arm64-v8a",
        gpuVendor = "ARM",
        gpuRenderer = "Mali-G68",
        battery = BatteryProfile(capacityMah = 5000)
    )

    fun onePlus12(): DeviceProfile = DeviceProfile(
        id = "oneplus12",
        displayName = "OnePlus 12 (CPH2573)",
        manufacturer = "OnePlus",
        brand = "OnePlus",
        model = "CPH2573",
        device = "pineapple",
        product = "pineapple",
        hardware = "qcom",
        fingerprint = "OnePlus/CPH2573EEA/OP5929L1:14/UKQ1.230924.001/1708476155123:user/release-keys",
        board = "pineapple",
        cpuAbi = "arm64-v8a",
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno 750",
        battery = BatteryProfile(capacityMah = 5400)
    )

    fun xiaomi14Pro(): DeviceProfile = DeviceProfile(
        id = "xiaomi14pro",
        displayName = "Xiaomi 14 Pro",
        manufacturer = "Xiaomi",
        brand = "Xiaomi",
        model = "23116PN5BC",
        device = "shennong",
        product = "shennong",
        hardware = "qcom",
        fingerprint = "Xiaomi/shennong/shennong:14/UKQ1.230804.001/V816.0.5.0.UNBCNXM:user/release-keys",
        board = "pineapple",
        cpuAbi = "arm64-v8a",
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno 750",
        battery = BatteryProfile(capacityMah = 4880)
    )

    fun nothingPhone2(): DeviceProfile = DeviceProfile(
        id = "nothing2",
        displayName = "Nothing Phone (2)",
        manufacturer = "Nothing",
        brand = "Nothing",
        model = "A065",
        device = "Pong",
        product = "Pong",
        hardware = "qcom",
        fingerprint = "Nothing/Pong/Pong:14/UKQ1.230924.001/2404221222:user/release-keys",
        board = "taro",
        cpuAbi = "arm64-v8a",
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno 730",
        battery = BatteryProfile(capacityMah = 4700)
    )

    fun samsungFold5(): DeviceProfile = DeviceProfile(
        id = "fold5",
        displayName = "Samsung Galaxy Z Fold5",
        manufacturer = "samsung",
        brand = "samsung",
        model = "SM-F946B",
        device = "q5q",
        product = "q5qxxx",
        fingerprint = "samsung/q5qxxx/q5q:14/UP1A.231005.007/F946BXXU1CWG9:user/release-keys",
        board = "kalama",
        cpuAbi = "arm64-v8a",
        gpuVendor = "Qualcomm",
        gpuRenderer = "Adreno 740",
        battery = BatteryProfile(capacityMah = 4400)
    )

    private fun defaultSensorsPixel(): List<SensorProfile> = listOf(
        SensorProfile("BMI3XX Accelerometer", "Bosch", 1),
        SensorProfile("BMI3XX Gyroscope", "Bosch", 4),
        SensorProfile("AK0991X Magnetometer", "AKM", 2),
        SensorProfile("STK_STK3XXX Proximity", "Sensortek", 8),
        SensorProfile("STK_STK3XXX Light", "Sensortek", 5),
        SensorProfile("BMP380 Barometer", "Bosch", 6),
        SensorProfile("Gravity", "Google", 9),
        SensorProfile("Linear Acceleration", "Google", 10),
        SensorProfile("Rotation Vector", "Google", 11),
        SensorProfile("Step Counter", "Google", 19),
        SensorProfile("Significant Motion", "Google", 17)
    )

    fun saveProfile(profile: DeviceProfile) {
        val file = File(profilesDir, "${profile.id}.json")
        file.writeText(gson.toJson(profile))
    }

    fun loadProfile(id: String): DeviceProfile? {
        // Try built-in first
        getBuiltInProfiles().find { it.id == id }?.let { return it }
        val file = File(profilesDir, "$id.json")
        if (!file.exists()) return null
        return try { gson.fromJson(file.readText(), DeviceProfile::class.java) } catch (_: Exception) { null }
    }

    fun listProfiles(): List<DeviceProfile> {
        val builtIn = getBuiltInProfiles()
        val custom = profilesDir.listFiles()?.mapNotNull {
            try { gson.fromJson(it.readText(), DeviceProfile::class.java) } catch (_: Exception) { null }
        } ?: emptyList()
        return builtIn + custom
    }

    fun getCoherentEnvironment(profile: DeviceProfile): CoherentEnvironment {
        // Ensures consistency across all subsystems
        return CoherentEnvironment(
            buildFingerprint = profile.fingerprint,
            manufacturer = profile.manufacturer,
            model = profile.model,
            brand = profile.brand,
            device = profile.device,
            hardware = profile.hardware,
            board = profile.board,
            bootloader = profile.bootloader,
            cpuAbi = profile.cpuAbi,
            supportedAbis = profile.supportedAbis,
            gpuVendor = profile.gpuVendor,
            gpuRenderer = profile.gpuRenderer,
            sensors = profile.sensors,
            camera = profile.camera,
            battery = profile.battery,
            simOperator = profile.simOperator,
            simOperatorName = profile.simOperatorName,
            networkOperator = profile.networkOperator,
            networkOperatorName = profile.networkOperatorName,
            systemProps = profile.systemProps,
            hidePaths = profile.hidePaths
        )
    }

    data class CoherentEnvironment(
        val buildFingerprint: String,
        val manufacturer: String,
        val model: String,
        val brand: String,
        val device: String,
        val hardware: String,
        val board: String,
        val bootloader: String,
        val cpuAbi: String,
        val supportedAbis: List<String>,
        val gpuVendor: String,
        val gpuRenderer: String,
        val sensors: List<SensorProfile>,
        val camera: com.clonemaster.cloning.models.CameraProfile,
        val battery: com.clonemaster.cloning.models.BatteryProfile,
        val simOperator: String,
        val simOperatorName: String,
        val networkOperator: String,
        val networkOperatorName: String,
        val systemProps: Map<String, String>,
        val hidePaths: List<String>
    )
}
