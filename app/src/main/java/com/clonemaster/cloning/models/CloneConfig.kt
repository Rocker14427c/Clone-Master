package com.clonemaster.cloning.models

import java.io.Serializable

/**
 * Per-clone configuration – covers all 22 feature groups.
 * Persisted as JSON in app's private storage and bundled into clone as assets/clone_config.json
 */
data class CloneConfig(
    // Core
    var originalPackage: String = "",
    var clonePackage: String = "", // e.g. com.example.app.clone1
    var cloneIndex: Int = 1,
    var appName: String = "",
    var versionName: String = "",
    var versionCode: Long = 1,
    var customIconPath: String? = null,
    var iconBadge: IconBadge = IconBadge.NONE,
    var badgeNumber: Int = 1,
    var badgeColor: Int = 0xFF2196F3.toInt(),
    var removeBranding: Boolean = true,
    var bundleOriginalApk: Boolean = false,
    var includeObb: Boolean = true,
    var includeExternalData: Boolean = false,

    // Identity
    var identity: IdentityConfig = IdentityConfig(),

    // Privacy
    var privacy: PrivacyConfig = PrivacyConfig(),

    // Display
    var display: DisplayConfig = DisplayConfig(),

    // View Mod
    var viewMods: MutableList<ViewModRule> = mutableListOf(),

    // Media
    var media: MediaConfig = MediaConfig(),

    // Navigation
    var navigation: NavigationConfig = NavigationConfig(),

    // Storage
    var storage: StorageConfig = StorageConfig(),

    // Launching
    var launching: LaunchingConfig = LaunchingConfig(),

    // Networking
    var networking: NetworkingConfig = NetworkingConfig(),

    // Notification
    var notification: NotificationConfig = NotificationConfig(),

    // Game
    var game: GameConfig = GameConfig(),

    // TV/Wear
    var tvWear: TvWearConfig = TvWearConfig(),

    // Automation
    var automation: AutomationConfig = AutomationConfig(),

    // Developer
    var developer: DeveloperConfig = DeveloperConfig(),

    // Batch
    var isBatch: Boolean = false,
    var batchCount: Int = 1,
    var batchNameTemplate: String = "{appName} {index}"

) : Serializable

enum class IconBadge { NONE, NUMBER, DOT, CUSTOM_TEXT }

data class IdentityConfig(
    var spoofAndroidId: Boolean = false,
    var androidId: String = randomAndroidId(),
    var spoofImei: Boolean = false,
    var imei: String = randomImei(),
    var imsi: String = randomImsi(),
    var spoofWifiMac: Boolean = false,
    var wifiMac: String = randomMac(),
    var spoofBtMac: Boolean = false,
    var btMac: String = randomMac(),
    var spoofGsfId: Boolean = false,
    var gsfId: String = randomGsfId(),
    var spoofAdvertisingId: Boolean = false,
    var advertisingId: String = randomGaId(),
    var amazonAdId: String = randomGaId(),
    var facebookAttributionId: String = "",
    var webViewUserAgent: String = "",
    var customWebViewUaEnabled: Boolean = false,
    var spoofWifiInfo: Boolean = false,
    var wifiSsid: String = "CloneMasterWiFi",
    var wifiBssid: String = randomMac(),
    var spoofGpu: Boolean = false,
    var gpuVendor: String = "Qualcomm",
    var gpuRenderer: String = "Adreno 640",
    var spoofSim: Boolean = false,
    var simOperator: String = "310260",
    var simCountry: String = "us",
    var simOperatorName: String = "CloneCarrier",
    var spoofBuildProps: Boolean = false,
    var buildProps: MutableMap<String, String> = mutableMapOf(),
    var deviceProfileName: String = "default"
) : Serializable

data class PrivacyConfig(
    var passwordProtection: Boolean = false,
    var password: String = "",
    var stealthMode: Boolean = false,
    var decoyCalculator: Boolean = false,
    var excludeFromRecents: Boolean = false,
    var incognitoMode: Boolean = false,
    var incognitoKeyboard: Boolean = false,
    var clearOnExit: Boolean = false,
    var disableAccounts: Boolean = false,
    var disableContacts: Boolean = false,
    var disableCalendar: Boolean = false,
    var disableCallLog: Boolean = false,
    var disableClipboard: Boolean = false,
    var disabledPermissions: MutableList<String> = mutableListOf(),
    var disablePermissionPrompts: Boolean = false,
    var gpsSpoof: Boolean = false,
    var fakeLat: Double = 37.4220,
    var fakeLng: Double = -122.0841,
    var fakeAltitude: Double = 0.0,
    var hideMockLocation: Boolean = true,
    var fakeTimezone: String = "",
    var fakeSensors: Boolean = false,
    var disableSensors: Boolean = false,
    var disableAccessibility: Boolean = false,
    var disableScreenshots: Boolean = false,
    var disableScreenRecord: Boolean = false,
    var floatingKeyboard: Boolean = false,
    var disableAutofill: Boolean = false,
    var hideRoot: Boolean = true,
    var hideOtherApps: Boolean = false,
    var disableLogcat: Boolean = false,
    var disableShare: Boolean = false,
    var disableDeviceAdmin: Boolean = false,
    var disableAccessibilityServices: Boolean = false,
    var knoxDisable: Boolean = false,
    var autoExitOnScreenOff: Boolean = false,
    var shakeToExit: Boolean = false
) : Serializable

data class DisplayConfig(
    var statusBarColor: Int? = null,
    var navBarColor: Int? = null,
    var toolbarColor: Int? = null,
    var darkMode: DarkMode = DarkMode.SYSTEM,
    var forceDarkMode: Boolean = false,
    var colorInversion: Boolean = false,
    var orientationLock: Int = -1, // ActivityInfo.SCREEN_ORIENTATION_*
    var customDisplaySize: Float? = null,
    var customLanguage: String = "", // e.g. "fr", "de"
    var customFontPath: String? = null,
    var immersiveFullscreen: Boolean = false,
    var keepScreenAwake: Boolean = false,
    var floatingWindow: Boolean = false,
    var freeformWindow: Boolean = false,
    var multiWindow: Boolean = true,
    var pipSupport: Boolean = false,
    var flipScreen: Boolean = false,
    var hudMode: Boolean = false,
    var notchHandling: NotchHandling = NotchHandling.DEFAULT,
    var largeAspectRatio: Boolean = false,
    var webViewTextZoom: Int = 100,
    var zoomableImages: Boolean = false,
    var blurImages: Boolean = false,
    var allowTextSelection: Boolean = false,
    var copyShareImages: Boolean = false,
    var longPressCopy: Boolean = false,
    var revealPasswords: Boolean = false,
    var skipDialogs: MutableList<String> = mutableListOf(),
    var customSplashPath: String? = null,
    var welcomeMessage: String = "",
    var allowCopyPaste: Boolean = false,
    var rtlSupport: Boolean? = null,
    var colorFilter: Int? = null
) : Serializable

enum class DarkMode { LIGHT, DARK, SYSTEM, FORCE_DARK }
enum class NotchHandling { DEFAULT, HIDE, FULLSCREEN }

data class ViewModRule(
    var id: String = java.util.UUID.randomUUID().toString(),
    var activityPattern: String = "*", // regex or *
    var viewIdName: String = "",
    var xpath: String = "",
    var searchText: String = "",
    var action: ViewModAction = ViewModAction.HIDE,
    var replacementText: String = "",
    var styleJson: String = "", // {"background":"#FF0000","textColor":"#FFFFFF"}
    var enabled: Boolean = true
) : Serializable

enum class ViewModAction { HIDE, SHOW, REPLACE_TEXT, RESTYLE, REMOVE, DISABLE_CLICK }

data class MediaConfig(
    var muteOnStart: Boolean = false,
    var volumeOnStart: Int? = null,
    var muteWhileForeground: Boolean = false,
    var preventVolumeChange: Boolean = false,
    var startupSoundPath: String? = null,
    var disableCamera: Boolean = false,
    var disableMic: Boolean = false,
    var disableAudioFocus: Boolean = false,
    var allowOtherAudio: Boolean = false,
    var disableChromecast: Boolean = false,
    var secondaryDisplay: Boolean = false,
    var volumeRockerLock: Boolean = false,
    var showVolumeIndicator: Boolean = true,
    var disableHaptics: Boolean = false,
    var audioCapture: Boolean = false,
    var preferredCameraApp: String = "",
    var fakeCamera: Boolean = false,
    var fakeCameraImages: MutableList<String> = mutableListOf(),
    var exifHandling: ExifHandling = ExifHandling.KEEP,
    var randomizeCameraImages: Boolean = false
) : Serializable

enum class ExifHandling { KEEP, STRIP, FAKE }

data class NavigationConfig(
    var floatingBack: Boolean = false,
    var confirmExit: Boolean = false,
    var minimizeOnBack: Boolean = false,
    var shakeToExit: Boolean = false,
    var swipeToBack: Boolean = false,
    var longPressBackMenu: Boolean = false,
    var fingerprintActions: String = "",
    var volumeKeyAction: VolumeKeyAction = VolumeKeyAction.DEFAULT,
    var customVolumeMapping: MutableMap<String, String> = mutableMapOf(),
    var kioskMode: Boolean = false,
    var popupBlocker: Boolean = false,
    var activityMonitor: Boolean = false,
    var blockedActivities: MutableList<String> = mutableListOf()
) : Serializable

enum class VolumeKeyAction { DEFAULT, MEDIA, NAVIGATION, CUSTOM }

data class StorageConfig(
    var installToSd: Boolean = false,
    var disableMediaAccess: Boolean = false,
    var redirectExternalStorage: Boolean = true,
    var preventBackup: Boolean = false,
    var preserveDataOnUninstall: Boolean = false,
    var clearCacheOnExit: Boolean = false,
    var secureDeletePaths: MutableList<String> = mutableListOf(),
    var bundleSdDirs: MutableList<String> = mutableListOf(),
    var bundleExportedData: Boolean = false,
    var isolateStorage: Boolean = true
) : Serializable

data class LaunchingConfig(
    var removeLauncherIcon: Boolean = false,
    var removeWidgets: Boolean = false,
    var internalActivitiesAsIcons: MutableList<String> = mutableListOf(),
    var disableAutoStart: Boolean = false,
    var persistentMode: Boolean = false,
    var disableBackgroundServices: Boolean = false,
    var disableAppDefaults: Boolean = false,
    var secretDialerCode: String = "",
    var outgoingCallCode: String = "",
    var quickTile: Boolean = false,
    var disableWakeLocks: Boolean = false,
    var modifyJobScheduler: Boolean = false,
    var fakeBatteryLevel: Int? = null,
    var requestBatteryOptimizationExempt: Boolean = false,
    var setAsHome: Boolean = false,
    var setAsCamera: Boolean = false,
    var setAsAssistant: Boolean = false,
    var launchOtherAppOnStart: String = "",
    var startOnEvents: MutableList<StartEvent> = mutableListOf(),
    var handleScreenOnOff: Boolean = false
) : Serializable

enum class StartEvent { SPEN, HEADPHONES, POWER_CONNECTED, POWER_DISCONNECTED, SD_MOUNTED, NFC }

data class NetworkingConfig(
    var disableNetworking: Boolean = false,
    var manualToggle: Boolean = false,
    var notificationToggle: Boolean = false,
    var disableMobileData: Boolean = false,
    var disableBackgroundNet: Boolean = false,
    var disableNetScreenOff: Boolean = false,
    var vpnOnly: Boolean = false,
    var mockWifiInfo: Boolean = false,
    var mockMobileInfo: Boolean = false,
    var mockEthernetInfo: Boolean = false,
    var socksProxy: String = "", // host:port
    var httpProxy: String = "",
    var httpProxyList: MutableList<String> = mutableListOf(),
    var dnsOverHttps: String = "", // https://dns.google/dns-query
    var showIpInfo: Boolean = false,
    var disableCleartext: Boolean = false,
    var webrtcLeakProtection: Boolean = false
) : Serializable

data class NotificationConfig(
    var filterPatterns: MutableList<String> = mutableListOf(),
    var quietHours: Pair<Int, Int>? = null, // startHour, endHour
    var silence: Boolean = false,
    var customVibration: LongArray? = null,
    var color: Int? = null,
    var ledColor: Int? = null,
    var snooze: Long? = null,
    var timeout: Long? = null,
    var visibility: Int? = null,
    var priority: Int? = null,
    var replaceIcons: Boolean = false,
    var customIconPath: String? = null,
    var replaceActions: Boolean = false,
    var singleGroup: Boolean = false,
    var modifyText: Boolean = false,
    var textReplacementMap: MutableMap<String, String> = mutableMapOf(),
    var modifyCategories: Boolean = false,
    var showDots: Boolean? = null,
    var toastFilter: MutableList<String> = mutableListOf(),
    var toastPosition: String = "bottom",
    var toastDuration: Int = 0,
    var toastOpacity: Float = 1f,
    var toastToNotification: Boolean = false,
    var invertToast: Boolean = false
) : Serializable

data class GameConfig(
    var supportObb: Boolean = true,
    var copyObb: Boolean = true,
    var bundleObb: Boolean = false,
    var keyMapperEnabled: Boolean = false,
    var keyMappings: MutableMap<String, String> = mutableMapOf(),
    var fpsMonitor: Boolean = false
) : Serializable

data class TvWearConfig(
    var tvLauncher: Boolean = false,
    var customTvBannerPath: String? = null,
    var joystickPointer: Boolean = false,
    var pip: Boolean = false,
    var useTvVariantOnMobile: Boolean = false,
    var removeWearComponents: Boolean = false,
    var watchVariant: Boolean = false
) : Serializable

data class AutomationConfig(
    var brightnessOnStart: Int? = null,
    var dndToggle: Boolean? = null,
    var wifiToggle: Boolean? = null,
    var btToggle: Boolean? = null,
    var autoRotateToggle: Boolean? = null,
    var clipboardOnStart: String = "",
    var taskerTasks: MutableList<String> = mutableListOf(),
    var apiAutomation: Boolean = false,
    var autoPressButtons: MutableList<AutoPressRule> = mutableListOf(),
    var autoScroll: Boolean = false,
    var autoScrollInterval: Long = 2000,
    var flashlightWhileOpen: Boolean = false,
    var startHooks: MutableList<String> = mutableListOf(), // shell/cmd descriptions
    var exitHooks: MutableList<String> = mutableListOf(),
    var shellHooks: MutableList<String> = mutableListOf(),
    var eventTriggers: MutableList<EventTrigger> = mutableListOf(),
    var sequencedActions: MutableList<SequencedAction> = mutableListOf()
) : Serializable

data class AutoPressRule(val viewId: String, val delayMs: Long)
data class EventTrigger(val event: String, val action: String)
data class SequencedAction(val order: Int, val action: String, val condition: String? = null)

data class DeveloperConfig(
    var logcatViewer: Boolean = false,
    var hideDevMode: Boolean = false,
    var changeTargetSdk: Int? = null,
    var changeReportedAndroidVersion: String = "",
    var customBuildProps: MutableMap<String, String> = mutableMapOf(),
    var customPermissions: MutableList<String> = mutableListOf(),
    var activityMonitoring: Boolean = false,
    var fileMonitoring: Boolean = false,
    var urlMonitoring: Boolean = false,
    var httpHeaderMonitoring: Boolean = false,
    var webViewInspection: Boolean = false,
    var webViewSourceInspection: Boolean = false,
    var webViewJsInjection: MutableList<String> = mutableListOf(),
    var webViewNavOverrides: MutableMap<String, String> = mutableMapOf(),
    var webViewUa: String = "",
    var nativeHooksEnabled: Boolean = true,
    var hookConfig: MutableMap<String, String> = mutableMapOf(),
    var safeMode: Boolean = false
) : Serializable

// Helpers
fun randomAndroidId(): String = (1..16).map { "0123456789abcdef".random() }.joinToString("")
fun randomImei(): String = "35" + (1..13).map { (0..9).random() }.joinToString("")
fun randomImsi(): String = "310260" + (1..9).map { (0..9).random() }.joinToString("")
fun randomMac(): String = (1..6).map { "%02X".format((0..255).random()) }.joinToString(":").let {
    // set locally administered bit
    val first = it.substring(0,2).toInt(16) or 0x02
    "%02X".format(first) + it.substring(2)
}
fun randomGsfId(): String = (1..16).map { "0123456789abcdef".random() }.joinToString("")
fun randomGaId(): String = java.util.UUID.randomUUID().toString()
