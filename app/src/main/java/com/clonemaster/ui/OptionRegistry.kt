package com.clonemaster.ui

import com.clonemaster.R
import com.clonemaster.cloning.models.*

/**
 * Central registry of all clone options – independent implementation
 * Public functional/UI reference: https://appcloner.app/ used only for organization and behavior reference
 * Each option maps to real field in CloneConfig – no fake UI switches
 * Search searches: option name, description, category, aliases/tags
 */
data class OptionItem(
    val id: String, // unique id, maps to config field path
    val name: String,
    val description: String,
    val category: OptionCategory,
    val iconRes: Int = R.mipmap.ic_launcher,
    val controlType: ControlType,
    val configFieldPath: String, // e.g., "identity.androidId", "privacy.hideRoot", "display.darkMode"
    val aliases: List<String> = emptyList(), // for search: GPS, proxy, clipboard, root, dark mode, data, WebView, etc.
    val defaultValue: Any? = null,
    val compatibility: CompatibilityIndicator = CompatibilityIndicator.SUPPORTED,
    val requiresWarning: String? = null, // warning message for dangerous options
    val isAdvanced: Boolean = false,
    val androidVersionRequirement: String? = null, // e.g., "Android 10+", "Requires root"
    val permissionRequirement: String? = null
)

enum class OptionCategory(val displayName: String) {
    GENERAL("General / Premium"),
    IDENTITY("Identity & Tracking"),
    PRIVACY("Privacy"),
    DISPLAY("Display"),
    MEDIA("Media"),
    NAVIGATION("Navigation"),
    STORAGE("Storage"),
    DATA_BUNDLING("Data Bundling & Migration"),
    LAUNCHING("Launching"),
    NETWORKING("Networking"),
    NOTIFICATIONS("Notifications"),
    GAMES("Games"),
    TV_WEAR("Android TV & Wear OS"),
    AUTOMATION("Automation"),
    DEVELOPER("Developer"),
    ENVIRONMENT("Environment / Device"),
    WEBVIEW("WebView"),
    DIAGNOSTICS("Diagnostics")
}

enum class ControlType {
    SWITCH, // boolean
    CHECKBOX, // multi-selection
    DROPDOWN, // predefined values
    SLIDER, // numeric range
    TEXT_FIELD, // custom values
    LIST_EDITOR, // proxy lists, paths, scripts
    DIALOG, // advanced config
    BUTTON // action
}

enum class CompatibilityIndicator(val emoji: String, val label: String) {
    SUPPORTED("🟢", "Supported"),
    MAY_AFFECT_COMPATIBILITY("🟡", "May affect compatibility"),
    KNOWN_LIMITATION("🔴", "Known limitation"),
    REQUIRES_ROOT("⚠️", "Requires root / Android version / permission")
}

object OptionRegistry {

    fun getAllOptions(): List<OptionItem> = listOf(

        // GENERAL / PREMIUM – Core cloning
        OptionItem(
            id = "general_appName",
            name = "Clone App Name",
            description = "Change clone application name",
            category = OptionCategory.GENERAL,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "appName",
            aliases = listOf("name", "label", "app name"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "general_clonePackage",
            name = "Clone Package / Application ID",
            description = "Assign unique package identifier to clone – required for coexistence",
            category = OptionCategory.GENERAL,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "clonePackage",
            aliases = listOf("package", "application id", "bundle id"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "general_versionName",
            name = "Version Name",
            description = "Change clone version name",
            category = OptionCategory.GENERAL,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "versionName",
            aliases = listOf("version", "version name"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "general_versionCode",
            name = "Version Code",
            description = "Change clone version code",
            category = OptionCategory.GENERAL,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "versionCode",
            aliases = listOf("version code", "build number"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "general_customIcon",
            name = "Custom Icon",
            description = "Use custom icon for clone",
            category = OptionCategory.GENERAL,
            controlType = ControlType.DIALOG,
            configFieldPath = "customIconPath",
            aliases = listOf("icon", "custom icon"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "general_iconBadge",
            name = "Icon Badge",
            description = "Add visual identifier / badge to clone icon for different clones",
            category = OptionCategory.GENERAL,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "iconBadge",
            aliases = listOf("badge", "icon badge", "number", "dot"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "general_removeBranding",
            name = "Remove Branding",
            description = "Remove cloning-tool branding from generated clones",
            category = OptionCategory.GENERAL,
            controlType = ControlType.SWITCH,
            configFieldPath = "removeBranding",
            aliases = listOf("branding", "remove branding"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // IDENTITY & TRACKING
        OptionItem(
            id = "identity_androidId",
            name = "Android ID",
            description = "Spoof Android ID per clone – independent configurable identity",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "identity.androidId",
            aliases = listOf("android id", "android_id", "device id", "identity"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "identity_imei",
            name = "IMEI / IMSI",
            description = "Spoof IMEI/IMSI where technically possible within Android restrictions (Android 10+ requires privileged permission)",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "identity.imei",
            aliases = listOf("imei", "imsi", "device id", "tracking"),
            compatibility = CompatibilityIndicator.KNOWN_LIMITATION,
            androidVersionRequirement = "Android 10+ requires READ_PRIVILEGED_PHONE_STATE – BLOCKED BY ANDROID LIMITATION"
        ),
        OptionItem(
            id = "identity_wifiMac",
            name = "Wi-Fi MAC",
            description = "Spoof Wi-Fi MAC with locally-administered randomized address",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "identity.wifiMac",
            aliases = listOf("wifi mac", "mac address", "wifi", "tracking"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY,
            androidVersionRequirement = "Android 6+ returns 02:00:00:00:00:00 for getMacAddress – hook required"
        ),
        OptionItem(
            id = "identity_btMac",
            name = "Bluetooth MAC",
            description = "Spoof Bluetooth MAC",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "identity.btMac",
            aliases = listOf("bluetooth mac", "bt mac", "bluetooth"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),
        OptionItem(
            id = "identity_gsfId",
            name = "Google Services Framework ID",
            description = "Spoof GSF ID per clone",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "identity.gsfId",
            aliases = listOf("gsf id", "gsf", "google services", "tracking"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "identity_gaid",
            name = "Google Advertising ID",
            description = "Spoof Google Advertising ID",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "identity.advertisingId",
            aliases = listOf("advertising id", "gaid", "google advertising", "tracking"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "identity_deviceProfile",
            name = "Device Profile",
            description = "Select coherent physical device profile for consistent spoofing across Build, Telephony, Sensors, etc.",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "identity.deviceProfileName",
            aliases = listOf("device profile", "profile", "fingerprint", "device"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "identity_buildProps",
            name = "Build Properties",
            description = "Custom build-property overrides for fingerprint customization",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.LIST_EDITOR,
            configFieldPath = "identity.buildProps",
            aliases = listOf("build props", "build properties", "fingerprint", "device"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY,
            requiresWarning = "Changing build properties may break apps that validate device fingerprint"
        ),
        OptionItem(
            id = "parity_cpuGpu",
            name = "Hide CPU / GPU Info",
            description = "Hide CPU/GPU info – spoof CPU model, cores, GPU vendor/renderer",
            category = OptionCategory.IDENTITY,
            controlType = ControlType.SWITCH,
            configFieldPath = "parityFeatures.cpuGpu.hideCpuInfo",
            aliases = listOf("cpu", "gpu", "hide cpu", "hide gpu", "cpu info", "gpu info"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // PRIVACY
        OptionItem(
            id = "privacy_clipboard",
            name = "Disable Clipboard Access",
            description = "Disable clipboard access for clone",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.disableClipboard",
            aliases = listOf("clipboard", "clipboard access", "copy paste"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_sensors",
            name = "Disable Sensors / Fake Sensors",
            description = "Disable sensor access or fake environment/device sensors",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.disableSensors",
            aliases = listOf("sensors", "disable sensors", "fake sensors", "sensor"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),
        OptionItem(
            id = "privacy_gps",
            name = "GPS / Location Spoofing",
            description = "Spoof GPS location per clone",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.DIALOG,
            configFieldPath = "privacy.gpsSpoof",
            aliases = listOf("gps", "location", "spoof location", "gps spoof", "fake location"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY,
            requiresWarning = "Location spoofing may be detected by SafetyNet/Play Integrity"
        ),
        OptionItem(
            id = "privacy_screenshots",
            name = "Disable Screenshots",
            description = "Disable screenshots for clone via FLAG_SECURE",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.disableScreenshots",
            aliases = listOf("screenshots", "disable screenshots", "secure", "flag_secure"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_recents",
            name = "Exclude from Recents",
            description = "Exclude clone from recent apps",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.excludeFromRecents",
            aliases = listOf("recents", "recent apps", "exclude recents"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_accounts",
            name = "Disable Account Access",
            description = "Disable account access for clone",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.disableAccounts",
            aliases = listOf("accounts", "disable accounts"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_contacts",
            name = "Disable Contacts Access",
            description = "Disable contacts access",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.disableContacts",
            aliases = listOf("contacts", "disable contacts"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_incognito",
            name = "Incognito Mode",
            description = "Incognito mode – automatically clear data on exit where possible",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.incognitoMode",
            aliases = listOf("incognito", "incognito mode", "clear on exit"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_password",
            name = "Password Protection",
            description = "Password protection for clone",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.DIALOG,
            configFieldPath = "privacy.passwordProtection",
            aliases = listOf("password", "password protection", "lock"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_stealth",
            name = "Stealth Mode",
            description = "Stealth mode – hide clone presence",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "privacy.stealthMode",
            aliases = listOf("stealth", "stealth mode", "hide"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "privacy_permissions",
            name = "Disable/Strip Permissions",
            description = "Disable/strip permissions per clone",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.LIST_EDITOR,
            configFieldPath = "privacy.disabledPermissions",
            aliases = listOf("permissions", "disable permissions", "strip permissions", "remove permissions"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY,
            requiresWarning = "Stripping permissions may break app functionality"
        ),
        OptionItem(
            id = "parity_appsFlyer",
            name = "Disable AppsFlyer Tracking",
            description = "Block AppsFlyer and other tracking SDKs (Firebase Analytics, Facebook, Adjust, etc.)",
            category = OptionCategory.PRIVACY,
            controlType = ControlType.SWITCH,
            configFieldPath = "parityFeatures.trackingBlocker.disableAppsFlyer",
            aliases = listOf("appsflyer", "tracking", "disable tracking", "analytics", "apps flyer"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // DISPLAY
        OptionItem(
            id = "display_darkMode",
            name = "Dark Mode",
            description = "Force dark mode where possible, or set light/dark/system",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "display.darkMode",
            aliases = listOf("dark mode", "dark", "night mode", "force dark"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "display_rotation",
            name = "Rotation / Orientation Lock",
            description = "Lock rotation/orientation per clone",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "display.orientationLock",
            aliases = listOf("rotation", "orientation", "orientation lock", "rotation lock"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "display_fullscreen",
            name = "Immersive Fullscreen Mode",
            description = "Enable immersive fullscreen mode",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.SWITCH,
            configFieldPath = "display.immersiveFullscreen",
            aliases = listOf("fullscreen", "immersive", "fullscreen mode"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "display_keepAwake",
            name = "Keep Screen Awake",
            description = "Keep screen awake while clone is open",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.SWITCH,
            configFieldPath = "display.keepScreenAwake",
            aliases = listOf("keep screen awake", "screen awake", "wakelock"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "display_colors",
            name = "Status/Navigation/Toolbar Colors",
            description = "Customize status-bar, navigation-bar, toolbar colors",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.DIALOG,
            configFieldPath = "display.statusBarColor",
            aliases = listOf("colors", "status bar", "navigation bar", "toolbar", "color"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "display_displaySize",
            name = "Custom Display Size",
            description = "Set custom display size / density",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.SLIDER,
            configFieldPath = "display.customDisplaySize",
            aliases = listOf("display size", "density", "dpi", "display"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),
        OptionItem(
            id = "display_locale",
            name = "Custom Language / Locale",
            description = "Set custom language/locale per clone – improved per-app locale Android 13+",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "display.customLanguage",
            aliases = listOf("language", "locale", "custom language", "change locale", "localization"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "display_font",
            name = "Custom Font",
            description = "Set custom/default font per clone",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.DIALOG,
            configFieldPath = "display.customFontPath",
            aliases = listOf("font", "custom font", "typeface"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "display_viewMods",
            name = "View Modifications",
            description = "Inspect and modify views – hide/show, replace text, restyle widgets, persist rules",
            category = OptionCategory.DISPLAY,
            controlType = ControlType.DIALOG,
            configFieldPath = "viewMods",
            aliases = listOf("view", "layout", "modify views", "replace text", "view hierarchy", "layout inspector"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),

        // STORAGE
        OptionItem(
            id = "storage_externalStorage",
            name = "Redirect External Storage",
            description = "Redirect external storage to isolate clone storage from original",
            category = OptionCategory.STORAGE,
            controlType = ControlType.SWITCH,
            configFieldPath = "storage.redirectExternalStorage",
            aliases = listOf("external storage", "storage", "redirect storage", "isolate storage"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "storage_preventBackup",
            name = "Prevent Backup",
            description = "Prevent app backup via allowBackup=false",
            category = OptionCategory.STORAGE,
            controlType = ControlType.SWITCH,
            configFieldPath = "storage.preventBackup",
            aliases = listOf("backup", "prevent backup", "allowBackup"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "storage_keepDataOnUninstall",
            name = "Prompt to Keep Data on Uninstall",
            description = "Prompt to keep app data on uninstall via hasFragileUserData (Android 10+)",
            category = OptionCategory.STORAGE,
            controlType = ControlType.SWITCH,
            configFieldPath = "parityFeatures.uninstallData.hasFragileUserData",
            aliases = listOf("keep data", "uninstall", "hasFragileUserData", "preserve data"),
            compatibility = CompatibilityIndicator.SUPPORTED,
            androidVersionRequirement = "Android 10+"
        ),

        // DATA BUNDLING
        OptionItem(
            id = "data_bundleData",
            name = "Bundle App Data",
            description = "Create self-contained clone with exportable user data (SharedPrefs, databases, files, external dirs, OBB)",
            category = OptionCategory.DATA_BUNDLING,
            controlType = ControlType.SWITCH,
            configFieldPath = "dataBundle.enabled",
            aliases = listOf("bundle data", "app data", "data", "migration", "bundle app data"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY,
            requiresWarning = "Bundling data increases APK size and may include sensitive data"
        ),
        OptionItem(
            id = "data_compression",
            name = "Data Compression",
            description = "Compression type for data archive: NONE, ZIP, GZIP, ZSTD",
            category = OptionCategory.DATA_BUNDLING,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "dataBundle.compression",
            aliases = listOf("compression", "zip", "gzip", "zstd", "compress"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "data_encryption",
            name = "Data Encryption",
            description = "Encryption for data archive: NONE, AES256, CHACHA20 with optional password",
            category = OptionCategory.DATA_BUNDLING,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "dataBundle.encryption",
            aliases = listOf("encryption", "encrypt", "aes", "chacha", "password", "encrypted backup"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // LAUNCHING
        OptionItem(
            id = "launching_removeIcon",
            name = "Remove Launcher Icon",
            description = "Remove launcher icon for stealth – launch via secret dialer code or Quick Tile",
            category = OptionCategory.LAUNCHING,
            controlType = ControlType.SWITCH,
            configFieldPath = "launching.removeLauncherIcon",
            aliases = listOf("launcher icon", "remove icon", "stealth", "hide icon"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "launching_dialerCode",
            name = "Secret Dialer Code",
            description = "Launch clone via secret dialer code",
            category = OptionCategory.LAUNCHING,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "launching.secretDialerCode",
            aliases = listOf("dialer code", "secret code", "dialer", "launch code"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "launching_persistent",
            name = "Persistent App Mode",
            description = "Make app persistent – restart after reboot via BootReceiver (Android 10+ uses notification)",
            category = OptionCategory.LAUNCHING,
            controlType = ControlType.SWITCH,
            configFieldPath = "launching.persistentMode",
            aliases = listOf("persistent", "persistent mode", "boot", "reboot", "autostart"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY,
            androidVersionRequirement = "Android 10+ restricts background activity starts – uses notification"
        ),
        OptionItem(
            id = "launching_fakeBattery",
            name = "Fake Battery Level",
            description = "Spoof battery level per clone",
            category = OptionCategory.LAUNCHING,
            controlType = ControlType.SLIDER,
            configFieldPath = "launching.fakeBatteryLevel",
            aliases = listOf("battery", "fake battery", "battery level"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // NETWORKING
        OptionItem(
            id = "networking_disableNetworking",
            name = "Disable All Networking",
            description = "Disable all networking for clone",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.SWITCH,
            configFieldPath = "networking.disableNetworking",
            aliases = listOf("networking", "disable networking", "offline", "no internet"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_mobileData",
            name = "Disable Mobile Data / Background Networking",
            description = "Disable mobile data, background networking, or networking when screen off",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.SWITCH,
            configFieldPath = "networking.disableMobileData",
            aliases = listOf("mobile data", "background networking", "disable mobile", "network"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_httpProxy",
            name = "HTTP Proxy",
            description = "Set HTTP proxy per clone",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "networking.httpProxy",
            aliases = listOf("http proxy", "proxy", "http", "proxy list"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_socksProxy",
            name = "SOCKS Proxy",
            description = "Set SOCKS proxy per clone",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "networking.socksProxy",
            aliases = listOf("socks proxy", "socks", "proxy", "socks5"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_proxyList",
            name = "HTTP Proxy List + Speed Test",
            description = "Manage list of proxies with speed test and auto-rotate",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.LIST_EDITOR,
            configFieldPath = "networking.httpProxyList",
            aliases = listOf("proxy list", "http proxy list", "proxy", "speed test", "proxy speed"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_doh",
            name = "DNS over HTTPS",
            description = "Configure DNS-over-HTTPS per clone",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "networking.dnsOverHttps",
            aliases = listOf("doh", "dns over https", "dns", "dns-over-https"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_tunnelManager",
            name = "Tunnel Manager",
            description = "Manage multiple proxy tunnels (SOCKS, HTTP) – independent implementation equivalent to appcloner.me reference",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.DIALOG,
            configFieldPath = "parityFeatures.tunnelManager.enabled",
            aliases = listOf("tunnel", "tunnel manager", "appcloner.me", "proxy tunnel"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_vpnOnly",
            name = "Disable Networking Unless VPN",
            description = "Disable networking unless connected to VPN",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.SWITCH,
            configFieldPath = "networking.vpnOnly",
            aliases = listOf("vpn", "vpn only", "disable networking vpn"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "networking_notificationToggle",
            name = "Networking Toggle via Notification",
            description = "Enable/disable networking manually via notification",
            category = OptionCategory.NETWORKING,
            controlType = ControlType.SWITCH,
            configFieldPath = "networking.notificationToggle",
            aliases = listOf("notification toggle", "networking toggle", "notification", "manual networking"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // NOTIFICATIONS
        OptionItem(
            id = "notifications_filter",
            name = "Notification Filtering",
            description = "Filter notifications by patterns and quiet time",
            category = OptionCategory.NOTIFICATIONS,
            controlType = ControlType.LIST_EDITOR,
            configFieldPath = "notification.filterPatterns",
            aliases = listOf("notification filter", "filter notifications", "quiet time", "notification"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "notifications_dots",
            name = "Notification Dots",
            description = "Control notification dots / badges on launcher icons",
            category = OptionCategory.NOTIFICATIONS,
            controlType = ControlType.SWITCH,
            configFieldPath = "notification.showDots",
            aliases = listOf("dots", "notification dots", "badge", "badges"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // GAMES
        OptionItem(
            id = "games_obb",
            name = "OBB / Expansion Files",
            description = "Copy or bundle OBB expansion files for games",
            category = OptionCategory.GAMES,
            controlType = ControlType.SWITCH,
            configFieldPath = "game.bundleObb",
            aliases = listOf("obb", "expansion files", "game", "obb files"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // TV & WEAR
        OptionItem(
            id = "tv_banner",
            name = "Custom Android TV Banner",
            description = "Set custom TV banner image",
            category = OptionCategory.TV_WEAR,
            controlType = ControlType.DIALOG,
            configFieldPath = "tvWear.customTvBannerPath",
            aliases = listOf("tv banner", "android tv", "banner", "tv"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // AUTOMATION
        OptionItem(
            id = "automation_brightness",
            name = "Set Brightness on Startup",
            description = "Set screen brightness when clone starts",
            category = OptionCategory.AUTOMATION,
            controlType = ControlType.SLIDER,
            configFieldPath = "automation.brightnessOnStart",
            aliases = listOf("brightness", "set brightness", "automation", "brightness on start"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "automation_tasker",
            name = "Execute Tasker Tasks",
            description = "Execute Tasker tasks on start/exit",
            category = OptionCategory.AUTOMATION,
            controlType = ControlType.LIST_EDITOR,
            configFieldPath = "automation.taskerTasks",
            aliases = listOf("tasker", "tasker tasks", "automation", "tasker integration"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // DEVELOPER
        OptionItem(
            id = "developer_targetSdk",
            name = "Change Target SDK",
            description = "Change Target SDK version per clone",
            category = OptionCategory.DEVELOPER,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "developer.changeTargetSdk",
            aliases = listOf("target sdk", "change target sdk", "target", "sdk"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY,
            requiresWarning = "Changing Target SDK may affect compatibility with Android version checks"
        ),
        OptionItem(
            id = "developer_logcat",
            name = "Logcat Viewer",
            description = "View Logcat logs for clone – with filtering",
            category = OptionCategory.DEVELOPER,
            controlType = ControlType.BUTTON,
            configFieldPath = "developer.logcatViewer",
            aliases = listOf("logcat", "logcat viewer", "logs", "logging"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "developer_nativeHooks",
            name = "Native Hooks",
            description = "Enable native hooks via Pine/ByteHook/AndHook – ART inline hook, PLT hook",
            category = OptionCategory.DEVELOPER,
            controlType = ControlType.SWITCH,
            configFieldPath = "developer.nativeHooksEnabled",
            aliases = listOf("native hooks", "pine", "bytehook", "andhook", "hooks", "native"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),
        OptionItem(
            id = "developer_disableHooks",
            name = "Disable Hooks / Safe Mode",
            description = "Disable all hooks for debugging – safe mode, previously called Safe mode, now Disable hooks",
            category = OptionCategory.DEVELOPER,
            controlType = ControlType.SWITCH,
            configFieldPath = "developer.safeMode",
            aliases = listOf("disable hooks", "safe mode", "disable all hooks", "hooks", "safe"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // ENVIRONMENT / DEVICE
        OptionItem(
            id = "environment_rootHide",
            name = "Root Hide",
            description = "Hide root indicators – per-clone toggle with AGGRESSIVE level",
            category = OptionCategory.ENVIRONMENT,
            controlType = ControlType.SWITCH,
            configFieldPath = "environment.hideRoot",
            aliases = listOf("root", "hide root", "root hide", "su", "magisk"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),
        OptionItem(
            id = "environment_emulatorHide",
            name = "Emulator Hide",
            description = "Hide emulator – separate from root, covers QEMU, build fingerprints, sensors, etc.",
            category = OptionCategory.ENVIRONMENT,
            controlType = ControlType.SWITCH,
            configFieldPath = "environment.hideEmulator",
            aliases = listOf("emulator", "hide emulator", "emulator hide", "qemu"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),
        OptionItem(
            id = "environment_devOptions",
            name = "Hide Developer Options",
            description = "Hide developer options detection",
            category = OptionCategory.ENVIRONMENT,
            controlType = ControlType.SWITCH,
            configFieldPath = "environment.hideDeveloperOptions",
            aliases = listOf("developer options", "hide developer", "dev options", "developer"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "environment_usbAdb",
            name = "Hide USB / ADB",
            description = "Hide USB/ADB detection – spoof ro.debuggable=0, ADB_ENABLED=0",
            category = OptionCategory.ENVIRONMENT,
            controlType = ControlType.SWITCH,
            configFieldPath = "environment.hideUsbAdb",
            aliases = listOf("usb", "adb", "hide usb", "hide adb", "usb debugging"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "environment_mockLocation",
            name = "Hide Mock Location",
            description = "Hide mock location indicators",
            category = OptionCategory.ENVIRONMENT,
            controlType = ControlType.SWITCH,
            configFieldPath = "environment.hideMockLocation",
            aliases = listOf("mock location", "hide mock", "mock", "gps mock"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "environment_physicalProfile",
            name = "Spoof Physical Device Profile",
            description = "Spoof coherent physical device profile across Build, Telephony, Sensors, Camera, GPU, etc.",
            category = OptionCategory.ENVIRONMENT,
            controlType = ControlType.DROPDOWN,
            configFieldPath = "environment.physicalDeviceProfileId",
            aliases = listOf("physical device", "device profile", "spoof device", "profile", "pixel", "samsung", "fingerprint"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "environment_diagnostics",
            name = "Environment Diagnostics",
            description = "Show detection diagnostics screen with root/emulator/QEMU/virtual/debug/mock/build/filesystem/hardware/sensor/telephony/network indicators",
            category = OptionCategory.ENVIRONMENT,
            controlType = ControlType.BUTTON,
            configFieldPath = "environment.enableDetectionDiagnostics",
            aliases = listOf("diagnostics", "environment diagnostics", "detection diagnostics", "root detected", "emulator detected"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // WEBVIEW
        OptionItem(
            id = "webview_userAgent",
            name = "WebView User-Agent",
            description = "Modify WebView User-Agent per clone",
            category = OptionCategory.WEBVIEW,
            controlType = ControlType.TEXT_FIELD,
            configFieldPath = "identity.webViewUserAgent",
            aliases = listOf("webview", "user-agent", "ua", "webview ua", "user agent"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),
        OptionItem(
            id = "webview_customScript",
            name = "WebView Custom Script / JS Injection",
            description = "Inject custom JavaScript into WebView with inject mode DOCUMENT_START/END/IDLE",
            category = OptionCategory.WEBVIEW,
            controlType = ControlType.LIST_EDITOR,
            configFieldPath = "developer.webViewJsInjection",
            aliases = listOf("webview", "custom script", "js injection", "javascript", "inject", "webview script"),
            compatibility = CompatibilityIndicator.MAY_AFFECT_COMPATIBILITY
        ),
        OptionItem(
            id = "webview_navOverride",
            name = "WebView Navigation Override",
            description = "Override WebView navigation – block/allow URLs",
            category = OptionCategory.WEBVIEW,
            controlType = ControlType.LIST_EDITOR,
            configFieldPath = "developer.webViewNavOverrides",
            aliases = listOf("webview", "navigation", "override navigation", "block url", "webview navigation"),
            compatibility = CompatibilityIndicator.SUPPORTED
        ),

        // DIAGNOSTICS
        OptionItem(
            id = "diagnostics_compatibilityReport",
            name = "Compatibility Report",
            description = "Show compatibility report before cloning – detects cert validation, Play Services, billing, SafetyNet, etc.",
            category = OptionCategory.DIAGNOSTICS,
            controlType = ControlType.BUTTON,
            configFieldPath = "diagnostics.compatibilityReport",
            aliases = listOf("compatibility", "compatibility report", "report", "analysis"),
            compatibility = CompatibilityIndicator.SUPPORTED
        )
    )

    fun search(query: String): List<OptionItem> {
        if (query.isEmpty()) return getAllOptions()
        val lower = query.lowercase()
        return getAllOptions().filter { option ->
            option.name.lowercase().contains(lower) ||
                    option.description.lowercase().contains(lower) ||
                    option.category.displayName.lowercase().contains(lower) ||
                    option.configFieldPath.lowercase().contains(lower) ||
                    option.aliases.any { it.lowercase().contains(lower) }
        }
    }

    fun getByCategory(category: OptionCategory): List<OptionItem> {
        return getAllOptions().filter { it.category == category }
    }

    fun getCategoriesWithOptions(): List<OptionCategory> {
        return getAllOptions().map { it.category }.distinct().filter { getByCategory(it).isNotEmpty() }
    }
}
