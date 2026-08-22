package com.clonemaster.cloning.engine

import com.clonemaster.cloning.models.CloneConfig

/**
 * Single source of truth for "does this clone request enable any OPTIONAL
 * feature?" (per the handover CRITICAL DEFAULTS RULE: supported != enabled).
 *
 * Used to decide whether:
 *  - hook framework / HookApplication must be injected (manifest wrap + dex),
 *  - the native clone pipeline must warn that features are not yet applied.
 *
 * Mandatory cloning mechanics (package rename, authority rewrite, signing,
 * config bundling) are NOT considered optional features.
 */
object OptionalFeatures {

    fun anyEnabled(config: CloneConfig): Boolean {
        val env = config.environment
        if (env.hideRoot || env.hideEmulator || env.hideDeveloperOptions || env.hideUsbAdb ||
            env.hideMockLocation || env.spoofPhysicalDeviceProfile || env.rootHideLevel != com.clonemaster.cloning.models.RootHideLevel.OFF ||
            env.emulatorHideLevel != com.clonemaster.cloning.models.EmulatorHideLevel.OFF
        ) return true

        val id = config.identity
        if (id.spoofAndroidId || id.spoofImei || id.spoofWifiMac || id.spoofBtMac || id.spoofGsfId ||
            id.spoofAdvertisingId || id.customWebViewUaEnabled || id.spoofWifiInfo || id.spoofGpu ||
            id.spoofSim || id.spoofBuildProps
        ) return true

        val pr = config.privacy
        if (pr.passwordProtection || pr.stealthMode || pr.decoyCalculator || pr.excludeFromRecents ||
            pr.incognitoMode || pr.incognitoKeyboard || pr.clearOnExit || pr.disableAccounts ||
            pr.disableContacts || pr.disableCalendar || pr.disableCallLog || pr.disableClipboard ||
            pr.disabledPermissions.isNotEmpty() || pr.disablePermissionPrompts || pr.gpsSpoof ||
            pr.hideMockLocation || pr.fakeSensors || pr.disableSensors || pr.disableAccessibility ||
            pr.disableScreenshots || pr.disableScreenRecord || pr.floatingKeyboard || pr.disableAutofill ||
            pr.hideRoot || pr.hideOtherApps || pr.disableLogcat || pr.disableShare ||
            pr.disableDeviceAdmin || pr.disableAccessibilityServices || pr.knoxDisable ||
            pr.autoExitOnScreenOff || pr.shakeToExit
        ) return true

        val dp = config.display
        if (dp.forceDarkMode || dp.colorInversion || dp.orientationLock != -1 || dp.immersiveFullscreen ||
            dp.keepScreenAwake || dp.floatingWindow || dp.freeformWindow || dp.pipSupport ||
            dp.hudMode || dp.largeAspectRatio || dp.zoomableImages || dp.blurImages ||
            dp.revealPasswords || dp.skipDialogs.isNotEmpty() || dp.welcomeMessage.isNotEmpty()
        ) return true

        if (config.viewMods.any { it.enabled }) return true
        if (config.media.muteOnStart || config.media.disableCamera || config.media.disableMic ||
            config.media.fakeCamera || config.media.disableHaptics || config.media.audioCapture
        ) return true
        if (config.navigation.floatingBack || config.navigation.confirmExit || config.navigation.kioskMode ||
            config.navigation.popupBlocker || config.navigation.activityMonitor ||
            config.navigation.blockedActivities.isNotEmpty()
        ) return true
        if (config.storage.redirectExternalStorage || config.storage.isolateStorage ||
            config.storage.preserveDataOnUninstall || config.storage.clearCacheOnExit ||
            config.storage.secureDeletePaths.isNotEmpty()
        ) return true
        if (config.launching.removeLauncherIcon || config.launching.persistentMode ||
            config.launching.quickTile || config.launching.secretDialerCode.isNotEmpty() ||
            config.launching.fakeBatteryLevel != null || config.launching.setAsHome ||
            config.launching.startOnEvents.isNotEmpty()
        ) return true
        if (config.networking.disableNetworking || config.networking.notificationToggle ||
            config.networking.socksProxy.isNotEmpty() || config.networking.httpProxy.isNotEmpty() ||
            config.networking.httpProxyList.isNotEmpty() || config.networking.dnsOverHttps.isNotEmpty() ||
            config.networking.vpnOnly || config.networking.disableCleartext ||
            config.networking.webrtcLeakProtection
        ) return true
        if (config.notification.silence || config.notification.quietHours != null ||
            config.notification.replaceIcons || config.notification.replaceActions ||
            config.notification.toastFilter.isNotEmpty() || config.notification.filterPatterns.isNotEmpty()
        ) return true
        if (config.game.keyMapperEnabled || config.game.fpsMonitor || config.game.copyObb ||
            config.game.bundleObb || config.game.supportObb
        ) return true
        if (config.tvWear.tvLauncher || config.tvWear.removeWearComponents || config.tvWear.joystickPointer ||
            config.tvWear.pip || config.tvWear.watchVariant
        ) return true
        if (config.automation.brightnessOnStart != null || config.automation.dndToggle != null ||
            config.automation.wifiToggle != null || config.automation.btToggle != null ||
            config.automation.autoRotateToggle != null || config.automation.clipboardOnStart.isNotEmpty() ||
            config.automation.taskerTasks.isNotEmpty() || config.automation.apiAutomation ||
            config.automation.autoScroll || config.automation.flashlightWhileOpen ||
            config.automation.startHooks.isNotEmpty() || config.automation.exitHooks.isNotEmpty() ||
            config.automation.shellHooks.isNotEmpty() || config.automation.eventTriggers.isNotEmpty() ||
            config.automation.sequencedActions.isNotEmpty()
        ) return true
        if (config.developer.logcatViewer || config.developer.hideDevMode ||
            config.developer.fileMonitoring || config.developer.urlMonitoring ||
            config.developer.httpHeaderMonitoring || config.developer.webViewInspection ||
            config.developer.webViewJsInjection.isNotEmpty() || config.developer.nativeHooksEnabled ||
            config.developer.safeMode
        ) return true
        if (config.dataBundle.enabled) return true
        if (config.parityFeatures.trackingBlocker.disableAppsFlyer ||
            config.parityFeatures.trackingBlocker.disableFirebaseAnalytics ||
            config.parityFeatures.trackingBlocker.disableFacebook ||
            config.parityFeatures.trackingBlocker.disableAllTracking ||
            config.parityFeatures.trackingBlocker.customBlockedPackages.isNotEmpty()
        ) return true
        if (config.parityFeatures.cpuGpu.hideCpuInfo || config.parityFeatures.cpuGpu.hideGpuInfo) return true
        if (config.parityFeatures.sneezeToExit.enabled || config.parityFeatures.knoxWarranty.spoofWarrantyBit ||
            config.parityFeatures.screensaver.preventDream || config.parityFeatures.supportChat.enabled ||
            config.parityFeatures.textMute.enabled || config.parityFeatures.screenEvents.disableScreenOnOffEvents ||
            config.parityFeatures.notificationNetworkingToggle.enabled ||
            config.parityFeatures.tunnelManager.enabled || config.parityFeatures.proxyList.autoRotate ||
            config.parityFeatures.notificationDots.showDots != null || config.parityFeatures.locale.usePerAppLocale ||
            config.parityFeatures.webViewScript.enabled || config.parityFeatures.deviceFiltering.enabled ||
            config.parityFeatures.layoutInspector.enabled
        ) return true

        return false
    }
}
