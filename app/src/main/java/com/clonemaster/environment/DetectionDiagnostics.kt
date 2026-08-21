package com.clonemaster.environment

import android.content.Context
import android.os.Build
import com.clonemaster.cloning.models.DeviceProfile
import com.clonemaster.cloning.models.EnvironmentConfig
import java.io.File

/**
 * Environment-detection diagnostic screen – shows all indicators and whether mitigation is active
 * Must not claim bypass unless mitigation actually active and verified.
 */
class DetectionDiagnostics(private val context: Context) {

    data class DiagnosticCategory(
        val name: String,
        val checks: List<DiagnosticCheck>
    )

    data class DiagnosticCheck(
        val id: String,
        val name: String,
        val category: String,
        val detected: Boolean,
        val mitigated: Boolean,
        val verifiedBypass: Boolean, // only true if mitigation active AND verified to hide
        val description: String,
        val currentValue: String,
        val expectedPhysicalValue: String,
        val canMitigate: Boolean,
        val mitigationMethod: String,
        val severity: Severity
    )

    enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

    fun runFullScan(config: EnvironmentConfig, profile: DeviceProfile): List<DiagnosticCategory> {
        val rootManager = RootHideManager(context)
        val emulatorManager = EmulatorHideManager(context)

        val rootChecks = rootManager.scanForRootIndicators()
        val emulatorChecks = emulatorManager.scanForEmulatorIndicators(profile)

        val categories = mutableListOf<DiagnosticCategory>()

        // Root
        categories.add(DiagnosticCategory(
            "Root Detection",
            rootChecks.map { rc ->
                DiagnosticCheck(
                    id = rc.checkId,
                    name = rc.name,
                    category = "Root",
                    detected = rc.detected,
                    mitigated = config.hideRoot && rc.canMitigate,
                    verifiedBypass = config.hideRoot && rc.canMitigate && !rc.detected, // if mitigation active, detected should be false
                    description = rc.description,
                    currentValue = if (rc.detected) "DETECTED" else "NOT DETECTED",
                    expectedPhysicalValue = "NOT DETECTED",
                    canMitigate = rc.canMitigate,
                    mitigationMethod = if (rc.canMitigate) "File.exists hook + access() PLT hook + PackageManager hook" else "Requires system-level (unmitigatable)",
                    severity = if (rc.detected) Severity.CRITICAL else Severity.LOW
                )
            }
        ))

        // Emulator
        categories.add(DiagnosticCategory(
            "Emulator Detection",
            emulatorChecks.filter { it.category == "Build" || it.category == "QEMU" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = ec.category,
                    detected = ec.detected,
                    mitigated = config.hideEmulator && ec.canMitigate,
                    verifiedBypass = config.hideEmulator && ec.canMitigate && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "unknown",
                    expectedPhysicalValue = ec.spoofedValue ?: "physical",
                    canMitigate = ec.canMitigate,
                    mitigationMethod = "Build props spoof + SystemProperties hook + __system_property_get hook",
                    severity = if (ec.detected) Severity.HIGH else Severity.LOW
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "QEMU Indicators",
            emulatorChecks.filter { it.category == "QEMU" || it.checkId.contains("qemu") }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "QEMU",
                    detected = ec.detected,
                    mitigated = config.hideEmulator && config.hideQemuProps,
                    verifiedBypass = config.hideEmulator && config.hideQemuProps && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "0 or empty",
                    canMitigate = true,
                    mitigationMethod = "SystemProperties hook + libc __system_property_get",
                    severity = Severity.CRITICAL
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Virtual Device",
            emulatorChecks.filter { it.category == "Virtual" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Virtual",
                    detected = ec.detected,
                    mitigated = config.hideEmulator,
                    verifiedBypass = config.hideEmulator && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "physical",
                    canMitigate = true,
                    mitigationMethod = "Virtual device API hooks",
                    severity = Severity.MEDIUM
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Debug/ADB",
            emulatorChecks.filter { it.category == "USB/ADB" || it.category == "Developer" }.map { ec ->
                val isAdb = ec.checkId == "usb_adb"
                val isDev = ec.checkId == "dev_options"
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = if (isAdb) "ADB" else "Developer Options",
                    detected = ec.detected,
                    mitigated = when {
                        isAdb -> config.hideUsbAdb
                        isDev -> config.hideDeveloperOptions
                        else -> false
                    },
                    verifiedBypass = when {
                        isAdb -> config.hideUsbAdb && !ec.detected
                        isDev -> config.hideDeveloperOptions && !ec.detected
                        else -> false
                    },
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "hidden / 0",
                    canMitigate = true,
                    mitigationMethod = if (isAdb) "Settings.Global.ADB_ENABLED hook + ro.debuggable spoof" else "Settings.Global.DEVELOPMENT_SETTINGS_ENABLED hook",
                    severity = Severity.MEDIUM
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Mock Location",
            emulatorChecks.filter { it.category == "Location" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Location",
                    detected = ec.detected,
                    mitigated = config.hideMockLocation,
                    verifiedBypass = config.hideMockLocation && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "not mocked",
                    canMitigate = true,
                    mitigationMethod = "Location.isFromMockProvider hook + Settings.Secure.ALLOW_MOCK_LOCATION hook + hideMockLocation",
                    severity = Severity.MEDIUM
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Build Properties",
            emulatorChecks.filter { it.category == "Build" }.map { ec ->
                DiagnosticCheck(
                    id = "build_${ec.checkId}",
                    name = "Build ${ec.name}",
                    category = "Build",
                    detected = ec.detected,
                    mitigated = config.spoofBuildFingerprint || config.spoofManufacturerModel,
                    verifiedBypass = (config.spoofBuildFingerprint || config.spoofManufacturerModel) && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = ec.spoofedValue ?: profile.fingerprint,
                    canMitigate = true,
                    mitigationMethod = "Build.* field hooks + system props",
                    severity = Severity.HIGH
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Filesystem Artifacts",
            emulatorChecks.filter { it.category == "Filesystem" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Filesystem",
                    detected = ec.detected,
                    mitigated = config.hideEmulatorFiles || config.hideRootPaths,
                    verifiedBypass = (config.hideEmulatorFiles || config.hideRootPaths) && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "hidden",
                    canMitigate = true,
                    mitigationMethod = "File.exists hook + access()/stat() PLT hook + /proc/* filtering",
                    severity = Severity.HIGH
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Hardware Characteristics",
            emulatorChecks.filter { it.category == "CPU" || it.category == "Kernel" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Hardware",
                    detected = ec.detected,
                    mitigated = config.spoofCpuAbi || config.hideEmulatorKernelInfo,
                    verifiedBypass = (config.spoofCpuAbi || config.hideEmulatorKernelInfo) && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = ec.spoofedValue ?: profile.cpuAbi,
                    canMitigate = true,
                    mitigationMethod = "Build.SUPPORTED_ABIS hook + /proc/version filtering",
                    severity = Severity.MEDIUM
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Sensor Consistency",
            emulatorChecks.filter { it.category == "Sensors" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Sensors",
                    detected = ec.detected,
                    mitigated = config.spoofSensors,
                    verifiedBypass = config.spoofSensors && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "${profile.sensors.size} physical sensors from ${profile.manufacturer}",
                    canMitigate = true,
                    mitigationMethod = "SensorManager.getSensorList hook returning profile.sensors",
                    severity = Severity.MEDIUM
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Telephony & SIM",
            emulatorChecks.filter { it.category == "Telephony" || it.category == "SIM" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Telephony",
                    detected = ec.detected,
                    mitigated = config.spoofTelephony || config.spoofSimOperator,
                    verifiedBypass = (config.spoofTelephony || config.spoofSimOperator) && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "${profile.simOperatorName} ${profile.simOperator}",
                    canMitigate = true,
                    mitigationMethod = "TelephonyManager hooks (getDeviceId, getSubscriberId, getNetworkOperator, etc)",
                    severity = Severity.MEDIUM
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Network",
            emulatorChecks.filter { it.category == "Network" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Network",
                    detected = ec.detected,
                    mitigated = config.spoofNetworkInterfaces,
                    verifiedBypass = config.spoofNetworkInterfaces && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = profile.networkInterfaces.joinToString(","),
                    canMitigate = true,
                    mitigationMethod = "NetworkInterface.getNetworkInterfaces hook + WifiInfo hook",
                    severity = Severity.LOW
                )
            }
        ))

        // Additional categories for Camera, Battery, BT, WiFi
        categories.add(DiagnosticCategory(
            "Camera",
            emulatorChecks.filter { it.category == "Camera" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Camera",
                    detected = ec.detected,
                    mitigated = config.spoofCamera,
                    verifiedBypass = config.spoofCamera && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "${profile.camera.cameraCount} cameras",
                    canMitigate = true,
                    mitigationMethod = "CameraManager.getCameraIdList hook",
                    severity = Severity.LOW
                )
            }
        ))

        categories.add(DiagnosticCategory(
            "Battery",
            emulatorChecks.filter { it.category == "Battery" }.map { ec ->
                DiagnosticCheck(
                    id = ec.checkId,
                    name = ec.name,
                    category = "Battery",
                    detected = ec.detected,
                    mitigated = config.spoofBattery,
                    verifiedBypass = config.spoofBattery && !ec.detected,
                    description = ec.description,
                    currentValue = ec.currentValue ?: "",
                    expectedPhysicalValue = "${profile.battery.capacityMah}mAh",
                    canMitigate = true,
                    mitigationMethod = "BatteryManager hook + ACTION_BATTERY_CHANGED spoof",
                    severity = Severity.LOW
                )
            }
        ))

        return categories
    }

    fun generateOverallReport(categories: List<DiagnosticCategory>): String {
        val totalChecks = categories.sumOf { it.checks.size }
        val detected = categories.sumOf { cat -> cat.checks.count { it.detected } }
        val mitigated = categories.sumOf { cat -> cat.checks.count { it.detected && it.mitigated } }
        val verified = categories.sumOf { cat -> cat.checks.count { it.verifiedBypass } }
        val unmitigated = detected - mitigated

        return buildString {
            appendLine("=== Environment Detection Diagnostics ===")
            appendLine("Total checks: $totalChecks")
            appendLine("Detected indicators: $detected")
            appendLine("Mitigated (active): $mitigated")
            appendLine("Verified bypass: $verified")
            appendLine("Unmitigated: $unmitigated")
            appendLine()
            categories.forEach { cat ->
                appendLine("## ${cat.name}: ${cat.checks.count { it.detected }} detected / ${cat.checks.size} total")
                cat.checks.filter { it.detected }.forEach { check ->
                    appendLine("- [${if (check.mitigated) "MITIGATED" else "UNMITIGATED"}] ${check.name}: ${check.description}")
                    appendLine("  Current: ${check.currentValue} | Expected: ${check.expectedPhysicalValue}")
                    appendLine("  Method: ${check.mitigationMethod} | Can mitigate: ${check.canMitigate} | Verified: ${check.verifiedBypass}")
                }
            }
            if (unmitigated > 0) {
                appendLine()
                appendLine("⚠️ Some indicators cannot be fully hidden due to Android restrictions. See per-check mitigationMethod.")
            } else if (detected == 0) {
                appendLine()
                appendLine("✅ No emulator/root indicators detected – appears as physical device")
            } else if (verified == detected) {
                appendLine()
                appendLine("✅ All detected indicators are mitigated and verified bypassed")
            }
        }
    }
}
