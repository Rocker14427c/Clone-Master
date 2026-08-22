package com.clonemaster.cloning.models

import com.clonemaster.ui.PresetManager
import com.clonemaster.ui.PresetType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CRITICAL DEFAULTS RULE regression test (handover requirement):
 * - A new app must start at 0/N optional options enabled.
 * - Supported must NOT mean enabled.
 * - Optional root/emulator/device spoofing/privacy/networking/WebView/identity/etc must be OFF by default.
 * - Clean Clone should only perform necessary transformations for producing a separate package.
 * - Automatic/default values are allowed ONLY for mandatory cloning mechanics
 *   (generated package ID, clone name, required manifest/provider changes, signing/build config).
 */
class CloneConfigDefaultsTest {

    private val config = CloneConfig()

    @Test
    fun `all optional feature toggle defaults are OFF`() {
        // Core optional behaviors
        assertFalse("removeBranding must default OFF (optional)", config.removeBranding)
        assertFalse("includeObb must default OFF (optional)", config.includeObb)
        assertFalse("bundleOriginalApk must default OFF", config.bundleOriginalApk)
        assertFalse("includeExternalData must default OFF", config.includeExternalData)

        // Identity – optional spoofing all OFF
        val id = config.identity
        assertFalse(id.spoofAndroidId); assertFalse(id.spoofImei); assertFalse(id.spoofWifiMac)
        assertFalse(id.spoofBtMac); assertFalse(id.spoofGsfId); assertFalse(id.spoofAdvertisingId)
        assertFalse(id.customWebViewUaEnabled); assertFalse(id.spoofWifiInfo); assertFalse(id.spoofGpu)
        assertFalse(id.spoofSim); assertFalse(id.spoofBuildProps)

        // Privacy – optional controls all OFF
        val pr = config.privacy
        assertFalse(pr.passwordProtection); assertFalse(pr.stealthMode); assertFalse(pr.decoyCalculator)
        assertFalse(pr.hideMockLocation); assertFalse(pr.hideRoot); assertFalse(pr.gpsSpoof)
        assertFalse(pr.disableScreenshots); assertFalse(pr.floatingKeyboard); assertFalse(pr.shakeToExit)

        // Display – optional all OFF
        val dp = config.display
        assertFalse(dp.forceDarkMode); assertFalse(dp.immersiveFullscreen); assertFalse(dp.keepScreenAwake)
        assertFalse(dp.pipSupport); assertFalse(dp.multiWindow); assertFalse(dp.blurImages)
        assertFalse(dp.zoomableImages); assertFalse(dp.revealPasswords)

        // Media
        assertFalse(config.media.muteOnStart); assertFalse(config.media.disableCamera)
        assertFalse(config.media.fakeCamera); assertFalse(config.media.showVolumeIndicator)

        // Navigation
        val nav = config.navigation
        assertFalse(nav.floatingBack); assertFalse(nav.confirmExit); assertFalse(nav.kioskMode)

        // Storage – optional isolation/redirect all OFF
        val st = config.storage
        assertFalse(st.redirectExternalStorage); assertFalse(st.isolateStorage)
        assertFalse(st.installToSd); assertFalse(st.preventBackup)

        // Launching
        val la = config.launching
        assertFalse(la.removeLauncherIcon); assertFalse(la.persistentMode); assertFalse(la.quickTile)

        // Networking
        val nt = config.networking
        assertFalse(nt.disableNetworking); assertFalse(nt.notificationToggle); assertFalse(nt.vpnOnly)
        assertFalse(nt.disableCleartext); assertFalse(nt.webrtcLeakProtection)

        // Notification
        val nf = config.notification
        assertFalse(nf.silence); assertFalse(nf.replaceIcons); assertFalse(nf.replaceActions)

        // Game
        assertFalse("supportObb must default OFF", config.game.supportObb)
        assertFalse("copyObb must default OFF", config.game.copyObb)
        assertFalse(config.game.keyMapperEnabled); assertFalse(config.game.fpsMonitor)

        // TV/Wear
        assertFalse(config.tvWear.tvLauncher); assertFalse(config.tvWear.removeWearComponents)

        // Automation
        assertFalse(config.automation.apiAutomation); assertFalse(config.automation.autoScroll)

        // Developer
        assertFalse("Developer nativeHooksEnabled must default OFF", config.developer.nativeHooksEnabled)
        assertFalse(config.developer.webViewInspection)

        // Data bundling – master OFF and every bundle category OFF
        val db = config.dataBundle
        assertFalse("dataBundle.enabled must default OFF", db.enabled)
        assertFalse(db.bundleSharedPrefs); assertFalse(db.bundleDatabases); assertFalse(db.bundleRoomDatabases)
        assertFalse(db.bundleFiles); assertFalse(db.bundleCacheIndependentFiles); assertFalse(db.bundleWebViewData)
        assertFalse(db.bundleExternalAppDirs); assertFalse(db.bundleObbDirs); assertFalse(db.includeNoBackupFiles)

        // Environment spoofing – master toggles OFF, levels OFF, fine-grained OFF
        val env = config.environment
        assertFalse(env.hideRoot); assertFalse(env.hideEmulator); assertFalse(env.hideDeveloperOptions)
        assertFalse(env.hideUsbAdb); assertFalse(env.hideMockLocation); assertFalse(env.spoofPhysicalDeviceProfile)
        assertEquals("rootHideLevel must default OFF", RootHideLevel.OFF, env.rootHideLevel)
        assertEquals("emulatorHideLevel must default OFF", EmulatorHideLevel.OFF, env.emulatorHideLevel)
        assertFalse(env.hideRootArtifacts); assertFalse(env.hideRootPaths); assertFalse(env.hideRootProperties)
        assertFalse(env.hideRootNativeChecks); assertFalse(env.hideRootJavaChecks)
        assertFalse(env.spoofBuildFingerprint); assertFalse(env.spoofManufacturerModel); assertFalse(env.spoofHardwareIds)
        assertFalse(env.spoofCpuAbi); assertFalse(env.hideEmulatorFiles); assertFalse(env.hideEmulatorNodes)
        assertFalse(env.hideQemuProps); assertFalse(env.hideEmulatorKernelInfo); assertFalse(env.spoofTelephony)
        assertFalse(env.spoofSimOperator); assertFalse(env.spoofNetworkInterfaces); assertFalse(env.spoofSensors)
        assertFalse(env.spoofCamera); assertFalse(env.spoofBattery); assertFalse(env.spoofBluetooth)
        assertFalse(env.spoofWifi); assertFalse(env.spoofUsbAdbProps); assertFalse(env.enforceConsistency)
        assertFalse(env.enableDetectionDiagnostics); assertFalse(env.reportUnmitigatableChecks)

        // Parity feature groups
        val pf = config.parityFeatures
        assertFalse("disableAppsFlyer must default OFF", pf.trackingBlocker.disableAppsFlyer)
        assertFalse(pf.trackingBlocker.disableFirebaseAnalytics)
        assertFalse("hideCpuInfo must default OFF", pf.cpuGpu.hideCpuInfo)
        assertFalse("hideGpuInfo must default OFF", pf.cpuGpu.hideGpuInfo)
        assertFalse("HookOptions nativeHooksEnabled must default OFF", pf.hookOptions.nativeHooksEnabled)
        assertFalse(pf.hookOptions.hookPine); assertFalse(pf.hookOptions.hookByteHook)
        assertFalse(pf.sneezeToExit.enabled); assertFalse(pf.sneezeToExit.useProximity); assertFalse(pf.sneezeToExit.useSound)
        assertFalse(pf.screensaver.preventDream); assertFalse(pf.supportChat.enabled); assertFalse(pf.textMute.enabled)
        assertFalse(pf.notificationNetworkingToggle.enabled); assertFalse(pf.tunnelManager.enabled)
        assertFalse("WebView script inject must default OFF", pf.webViewScript.enabled)
        assertFalse("usePerAppLocale must default OFF", pf.locale.usePerAppLocale)
        assertFalse("LayoutInspector must default OFF", pf.layoutInspector.enabled)
        assertFalse("DeviceFiltering must default OFF", pf.deviceFiltering.enabled)
        assertFalse(pf.uninstallData.promptToKeepData)
    }

    @Test
    fun `mandatory cloning mechanics keep sensible defaults`() {
        // These are NOT optional features – they are required for producing a separate package.
        assertTrue("transformPaths is mandatory mechanics and must stay true",
            config.dataBundle.transformPaths)
        assertTrue("embedInApk is a packaging mode, not an enabled feature",
            config.dataBundle.embedInApk)
    }

    @Test
    fun `presets must enable features explicitly - Clean Clone preset enables nothing optional`() {
        // Sanity: applying the Clean Clone preset to a fresh config leaves optional features OFF.
        val presetApplied = PresetManager.applyPreset(config, PresetType.CLEAN_CLONE)
        assertFalse(presetApplied.environment.hideRoot)
        assertFalse(presetApplied.parityFeatures.trackingBlocker.disableAppsFlyer)
        assertFalse(presetApplied.dataBundle.enabled)
        assertFalse(presetApplied.storage.isolateStorage)
        // Default preset = minimal (rename + branding removal only)
        val defApplied = PresetManager.applyPreset(config, PresetType.DEFAULT)
        assertFalse("Default preset must not enable hideRoot", defApplied.privacy.hideRoot)
        assertFalse("Default preset must not enable hideEmulator", defApplied.environment.hideEmulator)
    }
}
