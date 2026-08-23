# Clone-Master — Feature Implementation & Verification Status

**Generated:** 2026-08-22 · **Auditor:** take-over session · **HEAD:** 3242ae4 (v2.0.0-dexlib2-engine)
**Method:** source-traced (UI OptionRegistry -> CloneConfig -> CloneEngine -> core AppCloneBuilder), NOT label-trusted.
**Test evidence:** 37/37 unit tests pass (clean checkout, JDK21, SDK34); sandbox E2E self-clone passes aapt+zipalign+apksigner v2.

Statuses: VERIFIED WORKING (end-to-end evidence) / VERIFIED (manager app) (works, but runs in the manager, not the clone) /
PARTIAL (desktop-only path) (implemented only in the apktool pipeline that cannot run on Android) /
BROKEN (never reaches the clone) / BROKEN (UI) (mapping/duplication defect) / BLOCKED (Android platform limit).

**Headline:** 82 of 83 UI options depend on transforms/hooks that the on-device (native) pipeline does not deliver.
Only package/authority renaming is VERIFIED WORKING end-to-end on-device.

| # | UI Option id | Name | Category | Config field | UI flag | Status | Evidence / gap |
|---|---|---|---|---|---|---|---|
| 1 | `general_appName` | Clone App Name | GENERAL | `appName` | SUPPORTED | VERIFIED WORKING | Native path (P0-1): application + launcher labels rewritten as literal in binary manifest; unit test + aapt-verified self-clone (label=\"Clone Verify\"). resources.arsc app_name string still original (P2) |
| 2 | `general_clonePackage` | Clone Package / Application ID | GENERAL | `clonePackage` | SUPPORTED | VERIFIED WORKING | Engine: ManifestCloner+DexPackageRewriter; device-verified mark.via.gp->clone1; sandbox self-clone aapt/apksigner/zipalign pass |
| 3 | `general_versionName` | Version Name | GENERAL | `versionName` | SUPPORTED | VERIFIED WORKING | Native path: android:versionName rewritten (modify-only); skipped when equal to source; aapt-verified (9.9.9) |
| 4 | `general_versionCode` | Version Code | GENERAL | `versionCode` | SUPPORTED | VERIFIED WORKING | Native path: android:versionCode rewritten as TYPE_INT (modify-only, guarded vs same-as-source); aapt-verified (4242, type 0x10) |
| 5 | `general_customIcon` | Custom Icon | GENERAL | `customIconPath` | SUPPORTED | PARTIAL (desktop-only path) | ResourceTransformer (apktool path) only; native path byte-copies res/ |
| 6 | `general_iconBadge` | Icon Badge | GENERAL | `iconBadge` | SUPPORTED | PARTIAL (desktop-only path) | apktool path only |
| 7 | `general_removeBranding` | Remove Branding | GENERAL | `removeBranding` | SUPPORTED | VERIFIED WORKING | Native path: branding/app_cloner asset entries dropped at pack time; unit-tested ON+OFF states |
| 8 | `identity_androidId` | Android ID | IDENTITY | `identity.androidId` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 9 | `identity_imei` | IMEI / IMSI | IDENTITY | `identity.imei` | KNOWN_LIMITATION | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 10 | `identity_wifiMac` | Wi-Fi MAC | IDENTITY | `identity.wifiMac` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 11 | `identity_btMac` | Bluetooth MAC | IDENTITY | `identity.btMac` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 12 | `identity_gsfId` | Google Services Framework ID | IDENTITY | `identity.gsfId` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 13 | `identity_gaid` | Google Advertising ID | IDENTITY | `identity.advertisingId` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 14 | `identity_webViewUa` | WebView User-Agent | IDENTITY | `identity.webViewUserAgent` | SUPPORTED | BROKEN | identity.webViewUserAgent; no runtime delivery |
| 15 | `identity_deviceProfile` | Device Profile | IDENTITY | `identity.deviceProfileName` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 16 | `identity_buildProps` | Build Properties | IDENTITY | `identity.buildProps` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 17 | `parity_cpuGpu` | Hide CPU / GPU Info | IDENTITY | `parityFeatures.cpuGpu.hideCpuInfo` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 18 | `privacy_hideRoot` | Hide Root | PRIVACY | `environment.hideRoot` | MAY_AFFECT_COMPATIBILITY | BROKEN | maps environment.hideRoot; clone_config.json bundled natively but NO code inside clone consumes it |
| 19 | `privacy_hideEmulator` | Hide Emulator | PRIVACY | `environment.hideEmulator` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 20 | `privacy_clipboard` | Disable Clipboard Access | PRIVACY | `privacy.disableClipboard` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 21 | `privacy_sensors` | Disable Sensors / Fake Sensors | PRIVACY | `privacy.disableSensors` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 22 | `privacy_gps` | GPS / Location Spoofing | PRIVACY | `privacy.gpsSpoof` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 23 | `privacy_hideMockLocation` | Hide Mock Location | PRIVACY | `environment.hideMockLocation` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 24 | `privacy_screenshots` | Disable Screenshots | PRIVACY | `privacy.disableScreenshots` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 25 | `privacy_recents` | Exclude from Recents | PRIVACY | `privacy.excludeFromRecents` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 26 | `privacy_accounts` | Disable Account Access | PRIVACY | `privacy.disableAccounts` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 27 | `privacy_contacts` | Disable Contacts Access | PRIVACY | `privacy.disableContacts` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 28 | `privacy_incognito` | Incognito Mode | PRIVACY | `privacy.incognitoMode` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 29 | `privacy_password` | Password Protection | PRIVACY | `privacy.passwordProtection` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 30 | `privacy_stealth` | Stealth Mode | PRIVACY | `privacy.stealthMode` | SUPPORTED | PARTIAL (desktop-only path) | manifest icon removal in apktool path; native path lacks removal |
| 31 | `privacy_permissions` | Disable/Strip Permissions | PRIVACY | `privacy.disabledPermissions` | MAY_AFFECT_COMPATIBILITY | PARTIAL (desktop-only path) | permission strip only in apktool path |
| 32 | `parity_appsFlyer` | Disable AppsFlyer Tracking | PRIVACY | `parityFeatures.trackingBlocker.disableAppsFlyer` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 33 | `display_darkMode` | Dark Mode | DISPLAY | `display.darkMode` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 34 | `display_rotation` | Rotation / Orientation Lock | DISPLAY | `display.orientationLock` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 35 | `display_fullscreen` | Immersive Fullscreen Mode | DISPLAY | `display.immersiveFullscreen` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 36 | `display_keepAwake` | Keep Screen Awake | DISPLAY | `display.keepScreenAwake` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 37 | `display_colors` | Status/Navigation/Toolbar Colors | DISPLAY | `display.statusBarColor` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 38 | `display_displaySize` | Custom Display Size | DISPLAY | `display.customDisplaySize` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 39 | `display_locale` | Custom Language / Locale | DISPLAY | `display.customLanguage` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 40 | `display_font` | Custom Font | DISPLAY | `display.customFontPath` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 41 | `display_viewMods` | View Modifications | DISPLAY | `viewMods` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 42 | `storage_externalStorage` | Redirect External Storage | STORAGE | `storage.redirectExternalStorage` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 43 | `storage_preventBackup` | Prevent Backup | STORAGE | `storage.preventBackup` | SUPPORTED | PARTIAL (desktop-only path) | allowBackup=false applied only in apktool path |
| 44 | `storage_keepDataOnUninstall` | Prompt to Keep Data on Uninstall | STORAGE | `parityFeatures.uninstallData.hasFragileUserData` | SUPPORTED | PARTIAL (desktop-only path) | hasFragileUserData only in apktool path |
| 45 | `data_bundleData` | Bundle App Data | DATA_BUNDLING | `dataBundle.enabled` | MAY_AFFECT_COMPATIBILITY | PARTIAL (desktop-only path) | archival in apktool path only; native path never calls data bundle |
| 46 | `data_compression` | Data Compression | DATA_BUNDLING | `dataBundle.compression` | SUPPORTED | PARTIAL (desktop-only path) | only via data_bundleData |
| 47 | `data_encryption` | Data Encryption | DATA_BUNDLING | `dataBundle.encryption` | SUPPORTED | PARTIAL (desktop-only path) | only via data_bundleData |
| 48 | `launching_removeIcon` | Remove Launcher Icon | LAUNCHING | `launching.removeLauncherIcon` | SUPPORTED | PARTIAL (desktop-only path) | apktool path only |
| 49 | `launching_dialerCode` | Secret Dialer Code | LAUNCHING | `launching.secretDialerCode` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 50 | `launching_persistent` | Persistent App Mode | LAUNCHING | `launching.persistentMode` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 51 | `launching_fakeBattery` | Fake Battery Level | LAUNCHING | `launching.fakeBatteryLevel` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 52 | `networking_disableNetworking` | Disable All Networking | NETWORKING | `networking.disableNetworking` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 53 | `networking_mobileData` | Disable Mobile Data / Background Networking | NETWORKING | `networking.disableMobileData` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 54 | `networking_httpProxy` | HTTP Proxy | NETWORKING | `networking.httpProxy` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 55 | `networking_socksProxy` | SOCKS Proxy | NETWORKING | `networking.socksProxy` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 56 | `networking_proxyList` | HTTP Proxy List + Speed Test | NETWORKING | `networking.httpProxyList` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 57 | `networking_doh` | DNS over HTTPS | NETWORKING | `networking.dnsOverHttps` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 58 | `networking_tunnelManager` | Tunnel Manager | NETWORKING | `parityFeatures.tunnelManager.enabled` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 59 | `networking_vpnOnly` | Disable Networking Unless VPN | NETWORKING | `networking.vpnOnly` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 60 | `networking_notificationToggle` | Networking Toggle via Notification | NETWORKING | `networking.notificationToggle` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 61 | `notifications_filter` | Notification Filtering | NOTIFICATIONS | `notification.filterPatterns` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 62 | `notifications_dots` | Notification Dots | NOTIFICATIONS | `notification.showDots` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 63 | `games_obb` | OBB / Expansion Files | GAMES | `game.bundleObb` | SUPPORTED | PARTIAL (desktop-only path) | ObbHandler only in apktool path |
| 64 | `tv_banner` | Custom Android TV Banner | TV_WEAR | `tvWear.customTvBannerPath` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 65 | `automation_brightness` | Set Brightness on Startup | AUTOMATION | `automation.brightnessOnStart` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 66 | `automation_tasker` | Execute Tasker Tasks | AUTOMATION | `automation.taskerTasks` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 67 | `developer_versionName` | Change Version Name/Code (Developer) | DEVELOPER | `versionName` | SUPPORTED | BROKEN (UI) | duplicate control writing same field as general_versionName |
| 68 | `developer_targetSdk` | Change Target SDK | DEVELOPER | `developer.changeTargetSdk` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 69 | `developer_logcat` | Logcat Viewer | DEVELOPER | `developer.logcatViewer` | SUPPORTED | VERIFIED (manager app) | runs in manager app (LogcatViewerActivity); not a clone feature |
| 70 | `developer_nativeHooks` | Native Hooks | DEVELOPER | `developer.nativeHooksEnabled` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 71 | `developer_disableHooks` | Disable Hooks / Safe Mode | DEVELOPER | `developer.safeMode` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 72 | `environment_rootHide` | Root Hide | ENVIRONMENT | `environment.hideRoot` | MAY_AFFECT_COMPATIBILITY | BROKEN (UI) | duplicate of privacy_hideRoot (same field) |
| 73 | `environment_emulatorHide` | Emulator Hide | ENVIRONMENT | `environment.hideEmulator` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 74 | `environment_devOptions` | Hide Developer Options | ENVIRONMENT | `environment.hideDeveloperOptions` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 75 | `environment_usbAdb` | Hide USB / ADB | ENVIRONMENT | `environment.hideUsbAdb` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 76 | `environment_mockLocation` | Hide Mock Location | ENVIRONMENT | `environment.hideMockLocation` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 77 | `environment_physicalProfile` | Spoof Physical Device Profile | ENVIRONMENT | `environment.physicalDeviceProfileId` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 78 | `environment_diagnostics` | Environment Diagnostics | ENVIRONMENT | `environment.enableDetectionDiagnostics` | SUPPORTED | VERIFIED (manager app) | EnvironmentDiagnosticsActivity runs in manager app |
| 79 | `webview_userAgent` | WebView User-Agent | WEBVIEW | `developer.webViewUa` | SUPPORTED | BROKEN (UI) | second option for same concept, different field developer.webViewUa; no runtime delivery either way |
| 80 | `webview_customScript` | WebView Custom Script / JS Injection | WEBVIEW | `developer.webViewJsInjection` | MAY_AFFECT_COMPATIBILITY | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 81 | `webview_navOverride` | WebView Navigation Override | WEBVIEW | `developer.webViewNavOverrides` | SUPPORTED | BROKEN | value plumbed UI->CloneConfig->assets/clone_config.json; no consumer inside clone built on-device |
| 82 | `diagnostics_logcatViewer` | Logcat Viewer | DIAGNOSTICS | `developer.logcatViewer` | SUPPORTED | BROKEN (UI) | duplicate mapping of developer.logcatViewer |
| 83 | `diagnostics_compatibilityReport` | Compatibility Report | DIAGNOSTICS | `isBatch` | SUPPORTED | BROKEN (UI) | configFieldPath=isBatch – wrong field (batch flag); compat analysis itself works in manager |

## Hidden / internal capabilities not in the UI (found by source scan)

| Capability | Location | State |
|---|---|---|
| `CloneRequest.wrapApplication` flag (Phase-2 HookApplication wrap scaffold) | core CloneRequest | Scaffold only – never set true |
| `CloneRequest.labelOverride` | core CloneRequest | Declared, explicitly not supported in native path |
| `DexStringPatcher` (legacy in-place patcher) | core dex/ | Retained utility, unused by pipeline (tested) |
| Entire apktool decode/build path w/ feature transforms | app CloneEngine.clone() | Dead on-device (apktool is a desktop JVM tool) |
| MediaConfig + NavigationConfig groups | models/CloneConfig.kt | Config + hooks exist; ZERO UI options registered |
| AutomationEngine capabilities beyond brightness/tasker | automation/ | 15+ triggers only reachable via config JSON, no UI |
| Runtime `HookFramework` + 20 subsystem `Hooks.install` implementations | app hooks/, identity/, privacy/, ... | Compiled into manager app only; never merged into clones (native path). Old path injected logging no-op stubs |

## Totals (this audit)

- VERIFIED WORKING (clone-affecting, end-to-end): **5** (package/authority transform incl. component names, multidex DEX rebuild, signing, validation; P0-1: app label, versionName, versionCode, removeBranding)
- VERIFIED (manager app) behaviors: presets, search, option state, save/load/export config, compatibility analysis, env diagnostics screen, logcat viewer (unit-tested)
- PARTIAL (desktop-only path): ~10 build-time transforms (custom icon, icon badge, permissions, stealth, backup, fragile-data, OBB, data-bundle)
- BROKEN (never delivered to clone): ~60 runtime-hook options
- BROKEN (UI defects): 5 rows (see table: duplicates, wrong field `isBatch`, duplicate version control)
- Duplicated UI options (same field twice): hideRoot x2, hideEmulator x2, hideMockLocation x2, logcatViewer x2, versionName x2, WebView UA x2-fields

## Verification roadmap evidence pointers
- Engine regression (device): mark.via.gp -> mark.via.gp.clone1 (installed+launched, per handover)
- Engine regression (sandbox, this session): com.clonemaster -> com.clonemaster.clone1.verify, longer package, v2-signed, aapt/zipalign clean
- Unit tests: 40/40 (CloningCoreTest 6, ClonerE2ETest 9, OptionStateTest 6, CloneConfigDefaultsTest 3, ManifestTransformationTest 6, DataBundleTest 7)
