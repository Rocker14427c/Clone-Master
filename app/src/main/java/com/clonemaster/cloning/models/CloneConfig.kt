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

    // Environment Spoofing / Detection Mitigation
    var environment: EnvironmentConfig = EnvironmentConfig(),

    // Data Bundling / Migration
    var dataBundle: DataBundleConfig = DataBundleConfig(),

    // Functional Parity – Independent Implementation for Public Feature Reference (App Cloner)
    var parityFeatures: ParityFeaturesConfig = ParityFeaturesConfig(),

    // Batch
    var isBatch: Boolean = false,
    var batchCount: Int = 1,
    var batchNameTemplate: String = "{appName} {index}"

) : Serializable

// --- Functional Parity Features – Independent Implementation ---
/**
 * Independent implementation for public feature reference https://appcloner.app/
 * Provides equivalent functionality using Clone-Master's own architecture
 * Terms: functional parity, equivalent functionality, independent implementation, public feature reference, compatibility with Android limitations
 */
data class ParityFeaturesConfig(
    var trackingBlocker: TrackingBlockerConfig = TrackingBlockerConfig(),
    var cpuGpu: CpuGpuConfig = CpuGpuConfig(),
    var hookOptions: HookOptionsConfig = HookOptionsConfig(),
    var manifestOptions: ManifestOptionsConfig = ManifestOptionsConfig(),
    var sneezeToExit: SneezeConfig = SneezeConfig(),
    var knoxWarranty: KnoxConfig = KnoxConfig(),
    var screensaver: ScreensaverConfig = ScreensaverConfig(),
    var supportChat: SupportChatConfig = SupportChatConfig(),
    var textMute: TextMuteConfig = TextMuteConfig(),
    var uninstallData: UninstallDataConfig = UninstallDataConfig(),
    var screenEvents: ScreenEventConfig = ScreenEventConfig(),
    var notificationNetworkingToggle: NotificationToggleConfig = NotificationToggleConfig(),
    var tunnelManager: TunnelManagerConfig = TunnelManagerConfig(),
    var proxyList: ProxyListConfig = ProxyListConfig(),
    var notificationDots: DotsConfig = DotsConfig(),
    var locale: LocaleConfig = LocaleConfig(),
    var webViewScript: WebViewScriptConfig = WebViewScriptConfig(),
    var deviceFiltering: DeviceFilteringConfig = DeviceFilteringConfig(),
    var layoutInspector: LayoutInspectorConfig = LayoutInspectorConfig()
) : Serializable

data class TrackingBlockerConfig(
    var disableAppsFlyer: Boolean = true,
    var disableFirebaseAnalytics: Boolean = false,
    var disableFacebook: Boolean = false,
    var disableAllTracking: Boolean = false,
    var customBlockedPackages: MutableList<String> = mutableListOf()
) : Serializable

data class CpuGpuConfig(
    var hideCpuInfo: Boolean = true,
    var hideGpuInfo: Boolean = true,
    var spoofCpuModel: String = "Qualcomm Kryo 385",
    var spoofCpuCores: Int = 8,
    var spoofCpuFreq: String = "2.84 GHz",
    var spoofGpuVendor: String = "Qualcomm",
    var spoofGpuRenderer: String = "Adreno 750"
) : Serializable

data class HookOptionsConfig(
    var nativeHooksEnabled: Boolean = true,
    var disableHooks: Boolean = false,
    var safeMode: Boolean = false,
    var hookPine: Boolean = true,
    var hookByteHook: Boolean = true,
    var hookAndHook: Boolean = false
) : Serializable

data class ManifestOptionsConfig(
    var appCategory: String = "undefined",
    var largeHeap: Boolean? = null
) : Serializable

data class SneezeConfig(
    var enabled: Boolean = false,
    var sensitivity: Float = 0.8f,
    var useProximity: Boolean = true,
    var useSound: Boolean = true,
    var soundThresholdDb: Int = 70
) : Serializable

data class KnoxConfig(
    var spoofWarrantyBit: Boolean = false,
    var warrantyBitValue: Int = 0
) : Serializable

data class ScreensaverConfig(
    var mode: String = "DEFAULT",
    var customMessage: String = "",
    var preventDream: Boolean = false
) : Serializable

data class SupportChatConfig(
    var enabled: Boolean = false,
    var supportEmail: String = "support@clonemaster.app",
    var telegramLink: String = "https://t.me/CloneMasterSupport"
) : Serializable

data class TextMuteConfig(
    var enabled: Boolean = false,
    var muteTriggers: MutableList<String> = mutableListOf("Ad", "Advertisement"),
    var muteDurationMs: Long = 5000
) : Serializable

data class UninstallDataConfig(
    var promptToKeepData: Boolean = false,
    var hasFragileUserData: Boolean = false
) : Serializable

data class ScreenEventConfig(
    var disableScreenOnOffEvents: Boolean = false
) : Serializable

data class NotificationToggleConfig(
    var enabled: Boolean = false,
    var showToggle: Boolean = false
) : Serializable

data class TunnelManagerConfig(
    var enabled: Boolean = false,
    var activeTunnelId: String? = null,
    var autoSwitchOnFailure: Boolean = true
) : Serializable

data class ProxyListConfig(
    var autoRotate: Boolean = false,
    var rotateIntervalMinutes: Int = 30,
    var testOnAdd: Boolean = true,
    var useBestLatency: Boolean = true
) : Serializable

data class DotsConfig(
    var showDots: Boolean? = null
) : Serializable

data class LocaleConfig(
    var customLocale: String = "",
    var usePerAppLocale: Boolean = true
) : Serializable

data class WebViewScriptConfig(
    var injectMode: String = "DOCUMENT_END",
    var enabled: Boolean = true
) : Serializable

data class DeviceFilteringConfig(
    var filterQuery: String = "",
    var filterByTag: Boolean = true,
    var enabled: Boolean = true
) : Serializable

data class LayoutInspectorConfig(
    var enabled: Boolean = true,
    var liveHierarchy: Boolean = true,
    var showProperties: Boolean = true
) : Serializable

// --- Data Bundling / Migration Config ---
data class DataBundleConfig(
    var enabled: Boolean = false,
    var bundleSharedPrefs: Boolean = true,
    var bundleDatabases: Boolean = true,
    var bundleRoomDatabases: Boolean = true,
    var bundleFiles: Boolean = true,
    var bundleCacheIndependentFiles: Boolean = true,
    var bundleWebViewData: Boolean = true,
    var bundleExternalAppDirs: Boolean = true,
    var bundleObbDirs: Boolean = true,
    var customDirs: MutableList<String> = mutableListOf(), // user explicitly selected directories
    var excludeDirs: MutableList<String> = mutableListOf(),
    var selectedCategories: MutableList<DataCategory> = mutableListOf(DataCategory.SHARED_PREFS, DataCategory.DATABASES, DataCategory.FILES),
    var compression: CompressionType = CompressionType.ZSTD,
    var encryption: EncryptionType = EncryptionType.AES256,
    var encryptionPassword: String = "", // optional, if empty no encryption or use device-derived key
    var createSeparateDataFile: Boolean = false, // if true: Clone.apk + Clone.data, else embed in apk/assets
    var embedInApk: Boolean = true,
    var maxBundleSizeMb: Int = 500,
    var includeNoBackupFiles: Boolean = false,
    var transformPaths: Boolean = true, // apply package-name/path/provider transformations
    var version: Int = 1
) : Serializable

enum class DataCategory {
    SHARED_PREFS,
    DATABASES,
    ROOM_DATABASES,
    FILES,
    CACHE_INDEPENDENT,
    WEBVIEW_DATA,
    EXTERNAL_APP_DIRS,
    OBB_DIRS,
    CUSTOM_DIRS
}

enum class CompressionType { NONE, ZIP, GZIP, ZSTD }
enum class EncryptionType { NONE, AES256, CHACHA20 }

data class DataBundleMetadata(
    var sourcePackage: String = "",
    var clonePackage: String = "",
    var sourceVersionName: String = "",
    var sourceVersionCode: Long = 0,
    var cloneVersionName: String = "",
    var cloneVersionCode: Long = 0,
    var androidVersion: Int = 0,
    var androidRelease: String = "",
    var dataFormatVersion: Int = 2,
    var createdAt: Long = System.currentTimeMillis(),
    var includedCategories: List<DataCategory> = emptyList(),
    var includedDirs: List<String> = emptyList(),
    var excludedDirs: List<String> = emptyList(),
    var archiveName: String = "",
    var archiveSize: Long = 0,
    var archiveChecksumSha256: String = "",
    var fileCount: Int = 0,
    var totalBytes: Long = 0,
    var encryption: EncryptionType = EncryptionType.NONE,
    var compression: CompressionType = CompressionType.ZSTD,
    var hasKeystoreData: Boolean = false, // indicates some data could not be restored
    var notes: String = ""
) : Serializable

data class DataBundleManifest(
    var metadata: DataBundleMetadata = DataBundleMetadata(),
    var files: List<DataBundleFileEntry> = emptyList(),
    var checksums: Map<String, String> = emptyMap(), // path -> sha256
    var version: Int = 2
) : Serializable

data class DataBundleFileEntry(
    var originalPath: String = "",
    var relativePath: String = "", // inside archive
    var type: DataCategory = DataCategory.FILES,
    var size: Long = 0,
    var checksum: String = "",
    var requiresTransformation: Boolean = false,
    var transformedPath: String = "" // after package-name/path transformation
) : Serializable

// --- Environment Spoofing Config (dedicated subsystem) ---
data class EnvironmentConfig(
    // Master toggles – independently configurable per-clone
    var hideRoot: Boolean = true,
    var hideEmulator: Boolean = true,
    var hideDeveloperOptions: Boolean = true,
    var hideUsbAdb: Boolean = true,
    var hideMockLocation: Boolean = true,
    var spoofPhysicalDeviceProfile: Boolean = true,

    // Root mitigation fine-grained
    var rootHideLevel: RootHideLevel = RootHideLevel.AGGRESSIVE,
    var hideRootArtifacts: Boolean = true,
    var hideRootPaths: Boolean = true,
    var hideRootProperties: Boolean = true,
    var hideRootNativeChecks: Boolean = true,
    var hideRootJavaChecks: Boolean = true,

    // Emulator mitigation fine-grained
    var emulatorHideLevel: EmulatorHideLevel = EmulatorHideLevel.FULL,
    var spoofBuildFingerprint: Boolean = true,
    var spoofManufacturerModel: Boolean = true,
    var spoofHardwareIds: Boolean = true,
    var spoofCpuAbi: Boolean = true,
    var hideEmulatorFiles: Boolean = true,
    var hideEmulatorNodes: Boolean = true,
    var hideQemuProps: Boolean = true,
    var hideEmulatorKernelInfo: Boolean = true,
    var spoofTelephony: Boolean = true,
    var spoofSimOperator: Boolean = true,
    var spoofNetworkInterfaces: Boolean = true,
    var spoofSensors: Boolean = true,
    var spoofCamera: Boolean = true,
    var spoofBattery: Boolean = true,
    var spoofBluetooth: Boolean = true,
    var spoofWifi: Boolean = true,
    var spoofUsbAdbProps: Boolean = true,
    var enforceConsistency: Boolean = true,

    // Physical device profile to use for coherent spoofing
    var physicalDeviceProfileId: String = "pixel8_pro", // default
    var customDeviceProfile: DeviceProfile? = null,

    // Diagnostics
    var enableDetectionDiagnostics: Boolean = true,
    var reportUnmitigatableChecks: Boolean = true

) : Serializable

enum class RootHideLevel { OFF, BASIC, STANDARD, AGGRESSIVE }
enum class EmulatorHideLevel { OFF, BASIC, STANDARD, FULL }

data class DeviceProfile(
    var id: String = "pixel8_pro",
    var displayName: String = "Pixel 8 Pro (Physical)",
    var manufacturer: String = "Google",
    var brand: String = "google",
    var model: String = "Pixel 8 Pro",
    var device: String = "husky",
    var product: String = "husky",
    var hardware: String = "husky",
    var fingerprint: String = "google/husky/husky:14/AP2A.240905.003/12231197:user/release-keys",
    var buildTags: String = "release-keys",
    var buildType: String = "user",
    var buildVersionRelease: String = "14",
    var buildVersionSdk: String = "34",
    var buildVersionIncremental: String = "12231197",
    var buildHost: String = "abfarm-01117",
    var buildUser: String = "android-build",
    var board: String = "husky",
    var bootloader: String = "cloudripper-1.0-13138964",
    var cpuAbi: String = "arm64-v8a",
    var cpuAbi2: String = "",
    var supportedAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a", "armeabi"),
    var supported32BitAbis: List<String> = listOf("armeabi-v7a", "armeabi"),
    var supported64BitAbis: List<String> = listOf("arm64-v8a"),
    var hardwareFeatures: String = "qcom",
    var radioVersion: String = "g5300g-240805-240805-B-12291344",
    var kernelVersion: String = "5.15.131-android14-11",
    var baseband: String = "g5300g",
    // Telephony
    var simOperator: String = "310260",
    var simOperatorName: String = "T-Mobile",
    var simCountryIso: String = "us",
    var networkOperator: String = "310260",
    var networkOperatorName: String = "T-Mobile",
    var networkCountryIso: String = "us",
    var phoneType: Int = 1, // GSM
    // WiFi/BT
    var wifiMacPrefix: String = "A4:CF:12", // locally administered randomized suffix
    var btMacPrefix: String = "A4:CF:12",
    // Sensors – physical device sensor list
    var sensors: List<SensorProfile> = defaultPhysicalSensors(),
    // Camera
    var camera: CameraProfile = CameraProfile(),
    // Battery
    var battery: BatteryProfile = BatteryProfile(),
    // GPU
    var gpuVendor: String = "Qualcomm",
    var gpuRenderer: String = "Adreno 750",
    var gpuVersion: String = "OpenGL ES 3.2 V@0600.0",
    // Network
    var networkInterfaces: List<String> = listOf("wlan0", "rmnet_data0"),
    // Filesystem – hide emulator artifacts
    var hidePaths: List<String> = defaultEmulatorPathsToHide(),
    // System props to spoof
    var systemProps: Map<String, String> = defaultPhysicalSystemProps(),
    // Consistency hash – ensures all above match
    var isPhysical: Boolean = true
) : Serializable

data class SensorProfile(
    var name: String,
    var vendor: String,
    var type: Int,
    var version: Int = 1,
    var resolution: Float = 0.01f,
    var power: Float = 0.5f,
    var isEmulatorFake: Boolean = false
) : Serializable

data class CameraProfile(
    var hasCamera: Boolean = true,
    var cameraCount: Int = 3, // back, front, ultrawide
    var hasFlash: Boolean = true,
    var focalLengths: List<Float> = listOf(6.5f, 2.2f),
    var supportsHdr: Boolean = true,
    var isEmulator: Boolean = false
) : Serializable

data class BatteryProfile(
    var technology: String = "Li-ion",
    var health: Int = 2, // GOOD
    var present: Boolean = true,
    var hasBattery: Boolean = true,
    var capacityMah: Int = 5050,
    var voltageMv: Int = 4300,
    var temperatureDeciC: Int = 300,
    var isEmulator: Boolean = false
) : Serializable

fun defaultPhysicalSensors(): List<SensorProfile> = listOf(
    SensorProfile("BMI3XX Accelerometer", "Bosch", 1),
    SensorProfile("BMI3XX Gyroscope", "Bosch", 4),
    SensorProfile("AK0991X Magnetometer", "AKM", 2),
    SensorProfile("STK_STK3XXX Proximity", "Sensortek", 8),
    SensorProfile("STK_STK3XXX Light", "Sensortek", 5),
    SensorProfile("BMP380 Barometer", "Bosch", 6),
    SensorProfile("Gravity", "Google", 9),
    SensorProfile("Linear Acceleration", "Google", 10),
    SensorProfile("Rotation Vector", "Google", 11)
)

fun defaultEmulatorPathsToHide(): List<String> = listOf(
    "/system/bin/su",
    "/system/xbin/su",
    "/system/app/Superuser.apk",
    "/sbin/su",
    "/system/bin/.ext/.su",
    "/system/xbin/daemonsu",
    "/system/etc/init.d/99SuperSUDaemon",
    "/system/bin/.ext",
    "/system/xbin/.ext",
    "/data/local/xbin/su",
    "/data/local/bin/su",
    "/system/sd/xbin/su",
    "/system/bin/failsafe/su",
    "/data/local/su",
    "/su/bin/su",
    "/su/bin",
    "/system/xbin/busybox",
    "/system/bin/busybox",
    "/data/local/tmp/qemu-props",
    "/proc/tty/drivers",
    "/proc/cpuinfo",
    "/system/lib/libc_malloc_debug_qemu.so",
    "/sys/qemu_trace",
    "/system/bin/qemu-props",
    "/dev/socket/qemud",
    "/dev/qemu_pipe",
    "/dev/socket/genyd",
    "/dev/socket/baseband_genyd"
)

fun defaultPhysicalSystemProps(): Map<String, String> = mapOf(
    "ro.kernel.qemu" to "0",
    "ro.hardware" to "husky",
    "ro.revision" to "0",
    "ro.kernel.android.qemud" to "",
    "ro.kernel.android.bootanim" to "0",
    "ro.kernel.android.checkjni" to "0",
    "ro.build.fingerprint" to "google/husky/husky:14/AP2A.240905.003/12231197:user/release-keys",
    "ro.build.characteristics" to "nosdcard",
    "ro.product.model" to "Pixel 8 Pro",
    "ro.product.manufacturer" to "Google",
    "ro.product.brand" to "google",
    "ro.product.device" to "husky",
    "ro.product.board" to "husky",
    "ro.product.cpu.abi" to "arm64-v8a",
    "ro.product.cpu.abilist" to "arm64-v8a,armeabi-v7a,armeabi",
    "ro.product.cpu.abilist32" to "armeabi-v7a,armeabi",
    "ro.product.cpu.abilist64" to "arm64-v8a",
    "ro.debuggable" to "0",
    "ro.secure" to "1",
    "ro.build.type" to "user",
    "ro.build.tags" to "release-keys",
    "ro.build.flavor" to "husky-user",
    "ro.build.host" to "abfarm-01117",
    "ro.build.user" to "android-build",
    "ro.build.selinux" to "1"
)

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
