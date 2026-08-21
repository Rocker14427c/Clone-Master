package com.clonemaster.environment

import android.content.Context
import com.clonemaster.cloning.models.DeviceProfile
import com.clonemaster.cloning.models.EnvironmentConfig

/**
 * Central manager for Environment Spoofing / Detection Mitigation subsystem
 * Modular and compatible with Identity/Profile system
 * Single Device Profile provides coherent physical-device-like environment across all dimensions
 */
class EnvironmentManager(private val context: Context) {

    private val deviceProfileManager = DeviceProfileManager(context)
    private val rootManager = RootHideManager(context)
    private val emulatorManager = EmulatorHideManager(context)
    private val sysPropSpoofer = SystemPropertySpoofer()
    private val fsSpoofer = FileSystemSpoofer()
    private val diagnostics = DetectionDiagnostics(context)

    fun getDeviceProfile(profileId: String): DeviceProfile {
        return deviceProfileManager.loadProfile(profileId) ?: deviceProfileManager.pixel8Pro()
    }

    fun getAllProfiles(): List<DeviceProfile> = deviceProfileManager.listProfiles()

    fun getCoherentEnvironment(config: EnvironmentConfig): DeviceProfileManager.CoherentEnvironment {
        val profile = getDeviceProfile(config.physicalDeviceProfileId)
        return deviceProfileManager.getCoherentEnvironment(profile)
    }

    fun runDiagnostics(config: EnvironmentConfig): Pair<List<DetectionDiagnostics.DiagnosticCategory>, String> {
        val profile = getDeviceProfile(config.physicalDeviceProfileId)
        val categories = diagnostics.runFullScan(config, profile)
        val report = diagnostics.generateOverallReport(categories)
        return categories to report
    }

    fun getCompatibilityReport(config: EnvironmentConfig): String {
        val profile = getDeviceProfile(config.physicalDeviceProfileId)
        val sb = StringBuilder()
        sb.appendLine(rootManager.getCompatibilityReport(config))
        sb.appendLine(emulatorManager.getConsistencyReport(profile))
        sb.appendLine()
        sb.appendLine("=== Environment Config ===")
        sb.appendLine("Hide Root: ${config.hideRoot} (Level: ${config.rootHideLevel})")
        sb.appendLine("Hide Emulator: ${config.hideEmulator} (Level: ${config.emulatorHideLevel})")
        sb.appendLine("Hide Developer Options: ${config.hideDeveloperOptions}")
        sb.appendLine("Hide USB/ADB: ${config.hideUsbAdb}")
        sb.appendLine("Hide Mock Location: ${config.hideMockLocation}")
        sb.appendLine("Spoof Physical Profile: ${config.spoofPhysicalDeviceProfile} (${config.physicalDeviceProfileId})")
        sb.appendLine("Enforce Consistency: ${config.enforceConsistency}")
        return sb.toString()
    }

    /**
     * Generates the hooks configuration to be bundled into clone's assets/environment_config.json
     */
    fun generateHooksConfig(config: EnvironmentConfig): EnvironmentHooksConfig {
        val profile = getDeviceProfile(config.physicalDeviceProfileId)
        val coherent = deviceProfileManager.getCoherentEnvironment(profile)
        val sysProps = sysPropSpoofer.getSpoofedProps(config, profile)
        val hidePaths = fsSpoofer.getPathsToHide(config)

        return EnvironmentHooksConfig(
            config = config,
            profile = profile,
            coherentEnvironment = coherent,
            systemProps = sysProps,
            hidePaths = hidePaths
        )
    }

    data class EnvironmentHooksConfig(
        val config: EnvironmentConfig,
        val profile: DeviceProfile,
        val coherentEnvironment: DeviceProfileManager.CoherentEnvironment,
        val systemProps: Map<String, String>,
        val hidePaths: List<String>
    )

    /**
     * Runtime hooks – installed inside clone
     */
    object Hooks {
        fun install(context: Context, config: EnvironmentConfig, profile: DeviceProfile) {
            // Install in order: system props -> filesystem -> root -> emulator -> sensors -> etc
            // Ensures consistency: all spoofed values come from same profile

            val sysPropSpoofer = SystemPropertySpoofer()
            val props = sysPropSpoofer.getSpoofedProps(config, profile)
            SystemPropertySpoofer.Hooks.install(props)

            val fsSpoofer = FileSystemSpoofer()
            val hidePaths = fsSpoofer.getPathsToHide(config)
            FileSystemSpoofer.Hooks.install(hidePaths)

            RootHideManager.Hooks.install(config)

            EmulatorHideManager.Hooks.install(config, profile)

            // Additional hooks for telephony, sensors, camera, battery, etc are inside EmulatorHideManager.Hooks
            // Identity hooks also need to be consistent – Android ID, GSF ID, Advertising ID should match profile if spoofPhysicalDeviceProfile true

            // Verify bypass after install – do not claim bypass unless verified
            // Example: after installing, run quick self-check: File("/dev/qemu_pipe").exists() should be false if hideEmulatorFiles true
        }
    }
}
