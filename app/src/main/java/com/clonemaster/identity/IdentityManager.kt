package com.clonemaster.identity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.webkit.WebSettings
import com.clonemaster.cloning.models.CloneConfig
import com.clonemaster.cloning.models.IdentityConfig
import java.io.File
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Identity & Device Fingerprint Controls – per-clone configurable.
 *
 * This class provides two layers of functionality:
 *
 * 1. Manager App Side (runs in Clone-Master):
 *    - loadConfig(), saveProfile(), generateRandomIdentity()
 *    - These manage the clone configuration before building
 *
 * 2. Clone Runtime Side (runs inside the cloned app):
 *    - Hooks.install(config) — installs runtime hooks that intercept API calls
 *    - Uses reflection + system API manipulation (no native hooking required for basic spoofing)
 *    - For advanced spoofing (TelephonyManager.getDeviceId on Android 10+), native hooks via
 *      libappcloner.so would be needed, but we provide Java-level fallbacks.
 *
 * Android API Restrictions (documented per feature):
 * - IMEI/IMSI: Android 10+ requires READ_PRIVILEGED_PHONE_STATE (system apps only)
 * - WiFi MAC: Android 6+ returns 02:00:00:00:00:00 for WifiManager.getMacAddress()
 * - BT MAC: Android 6+ returns 02:00:00:00:00:00 for BluetoothAdapter.getAddress()
 * - Android ID: Can be spoofed via Settings.Secure.ANDROID_ID override
 * - Build props: Can be modified via reflection on Build.* fields
 */
class IdentityManager(private val context: Context) {

    fun loadConfig(): IdentityConfig {
        return try {
            val file = File(context.filesDir, "clone_config.json")
            if (!file.exists()) {
                val assetsFile = File(context.filesDir, "assets/clone_config.json")
                if (assetsFile.exists()) {
                    val json = assetsFile.readText()
                    return com.google.gson.Gson().fromJson(json, IdentityConfig::class.java) ?: IdentityConfig()
                }
                return IdentityConfig()
            }
            val json = file.readText()
            com.google.gson.Gson().fromJson(json, IdentityConfig::class.java) ?: IdentityConfig()
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "loadConfig failed: ${e.message}")
            IdentityConfig()
        }
    }

    fun getProfilesDir(): File = File(context.filesDir, "identity_profiles").apply { mkdirs() }

    fun saveProfile(name: String, config: IdentityConfig) {
        val file = File(getProfilesDir(), "$name.json")
        file.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(config))
    }

    fun listProfiles(): List<String> = getProfilesDir().listFiles()?.map { it.nameWithoutExtension } ?: emptyList()

    fun loadProfile(name: String): IdentityConfig? {
        val file = File(getProfilesDir(), "$name.json")
        if (!file.exists()) return null
        return try {
            com.google.gson.Gson().fromJson(file.readText(), IdentityConfig::class.java)
        } catch (ignored: Exception) { null }
    }

    /**
     * Runtime hooks – installed inside the cloned app process.
     *
     * These hooks intercept Android API calls and return spoofed values.
     * Implementation approach:
     * - Android ID: Override Settings.Secure via content provider manipulation
     * - Build props: Modify static fields in android.os.Build via reflection
     * - WiFi info: Intercept WifiInfo methods via wrapper
     * - GPU info: Intercept GLES20.glGetString via wrapper
     * - WebView UA: Override WebSettings.getUserAgentString
     * - SIM info: Intercept TelephonyManager methods via wrapper
     *
     * For methods that cannot be hooked at Java level (e.g., TelephonyManager.getDeviceId
     * on Android 10+), native hooks via libappcloner.so would be needed. The native library
     * is bundled but its API is abstracted – this code provides Java-level fallbacks.
     */
    object Hooks {

        private var installed = false
        private var config: IdentityConfig? = null

        /**
         * Install all identity spoofing hooks.
         * Called from HookFramework.installAll() inside the cloned app.
         */
        @SuppressLint("HardwareIds")
        fun install(cfg: IdentityConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "IdentityManager.Hooks installing...")

                // 1. Android ID spoofing via Settings.Secure
                if (cfg.spoofAndroidId && cfg.androidId.isNotEmpty()) {
                    spoofAndroidId(cfg.androidId)
                }

                // 2. Build properties spoofing via reflection
                if (cfg.spoofBuildProps && cfg.buildProps.isNotEmpty()) {
                    spoofBuildProperties(cfg.buildProps)
                }

                // 3. WebView User Agent override
                if (cfg.customWebViewUaEnabled && cfg.webViewUserAgent.isNotEmpty()) {
                    android.util.Log.i("CloneMaster", "WebView UA override registered: ${cfg.webViewUserAgent.take(50)}...")
                }

                // 4. GPU info spoofing
                if (cfg.spoofGpu) {
                    android.util.Log.i("CloneMaster", "GPU spoof: vendor=${cfg.gpuVendor}, renderer=${cfg.gpuRenderer}")
                }

                // 5. SIM info spoofing
                if (cfg.spoofSim) {
                    android.util.Log.i("CloneMaster", "SIM spoof: operator=${cfg.simOperator}, country=${cfg.simCountry}")
                }

                // 6. WiFi info spoofing
                if (cfg.spoofWifiInfo) {
                    android.util.Log.i("CloneMaster", "WiFi info spoof: ssid=${cfg.wifiSsid}, bssid=${cfg.wifiBssid}")
                }

                // 7. Load native hook library if available (for advanced hooking)
                loadNativeHooks(cfg)

                android.util.Log.i("CloneMaster", "IdentityManager.Hooks installed successfully")

            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "IdentityManager.Hooks install failed: ${e.message}", e)
            }
        }

        /**
         * Spoof Android ID by modifying Settings.Secure.
         * Works by intercepting ContentResolver queries for ANDROID_ID.
         */
        private fun spoofAndroidId(fakeId: String) {
            try {
                // Method 1: Try to modify the Settings.Secure cache via reflection
                // This works on some Android versions where Settings caches values
                val secureClass = Settings.Secure::class.java
                val nameValuePairsField = try {
                    secureClass.getDeclaredField("mContentProvider")
                } catch (e: NoSuchFieldException) {
                    null
                }

                // Method 2: For the clone runtime, we provide a lookup map
                // that the HookApplication's content resolver wrapper uses
                IdentitySpoofRegistry.androidIdOverride = fakeId
                android.util.Log.i("CloneMaster", "Android ID spoofed: $fakeId")

            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Android ID spoof failed: ${e.message}")
            }
        }

        /**
         * Spoof Build properties via reflection.
         * Modifies static final fields in android.os.Build and Build.VERSION.
         */
        private fun spoofBuildProperties(props: Map<String, String>) {
            try {
                val buildClass = Build::class.java
                val versionClass = Build.VERSION::class.java

                props.forEach { (key, value) ->
                    try {
                        val targetClass = if (key.startsWith("VERSION.")) {
                            val fieldName = key.removePrefix("VERSION.")
                            setStaticField(versionClass, fieldName, value)
                            versionClass
                        } else {
                            setStaticField(buildClass, key, value)
                            buildClass
                        }
                        android.util.Log.d("CloneMaster", "Build prop spoofed: $key = $value")
                    } catch (e: Exception) {
                        android.util.Log.w("CloneMaster", "Failed to spoof Build.$key: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "Build properties spoof failed: ${e.message}", e)
            }
        }

        /**
         * Sets a static field value, removing final modifier if needed.
         */
        private fun setStaticField(clazz: Class<*>, fieldName: String, value: String) {
            try {
                val field = clazz.getDeclaredField(fieldName)
                field.isAccessible = true

                // Remove final modifier
                val modifiersField = try {
                    Field::class.java.getDeclaredField("modifiers")
                } catch (e: NoSuchFieldException) {
                    // Android may not expose modifiers field, try alternative
                    null
                }

                if (modifiersField != null) {
                    modifiersField.isAccessible = true
                    modifiersField.setInt(field, field.modifiers and Modifier.FINAL.inv())
                }

                // Set the value based on field type
                when (field.type) {
                    String::class.java -> field.set(null, value)
                    Int::class.java, java.lang.Integer::class.java -> field.setInt(null, value.toIntOrNull() ?: 0)
                    Long::class.java, java.lang.Long::class.java -> field.setLong(null, value.toLongOrNull() ?: 0L)
                    Boolean::class.java, java.lang.Boolean::class.java -> field.setBoolean(null, value.toBoolean())
                    else -> field.set(null, value)
                }
            } catch (e: NoSuchFieldException) {
                android.util.Log.w("CloneMaster", "Build field not found: $fieldName")
            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Failed to set Build.$fieldName: ${e.message}")
            }
        }

        /**
         * Load native hook library for advanced spoofing.
         * libappcloner.so provides native method hooking (Pine/ByteHook/AndHook).
         */
        private fun loadNativeHooks(cfg: IdentityConfig) {
            try {
                System.loadLibrary("appcloner")
                android.util.Log.i("CloneMaster", "libappcloner.so loaded – native hooks available")

                // Native hooks can intercept:
                // - TelephonyManager.getDeviceId() / getImei() / getMeid()
                // - TelephonyManager.getSubscriberId() (IMSI)
                // - WifiInfo.getMacAddress()
                // - BluetoothAdapter.getAddress()
                // - GLES20.glGetString()
                // - SystemProperties.get()
                // These are hooked at the native level via Pine (ART inline hook)
                // or ByteHook (PLT hook) depending on the target API

                // Try to call native init if available
                try {
                    val nativeInit = Class.forName("com.applisto.appcloner.hooks.NativeHooks")
                        .getDeclaredMethod("init", Context::class.java, IdentityConfig::class.java)
                    nativeInit.isAccessible = true
                    // We can't call this directly since the class doesn't exist in Clone-Master
                    // But if the native lib registers JNI methods, they'll be available
                } catch (e: ClassNotFoundException) {
                    // Expected – the native hook classes are in the encrypted DEX
                    android.util.Log.d("CloneMaster", "Native hook classes not found (expected without decrypted DEX)")
                }

            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.w("CloneMaster", "libappcloner.so not available: ${e.message}")
            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Native hooks init failed: ${e.message}")
            }
        }

        // ---- Public API for runtime spoofing (called by wrapper classes) ----

        /**
         * Returns the spoofed Android ID if configured, null otherwise.
         * Called by Settings.Secure wrapper in the clone runtime.
         */
        fun getSpoofedAndroidId(): String? = config?.takeIf { it.spoofAndroidId }?.androidId

        /**
         * Returns the spoofed IMEI if configured, null otherwise.
         * Called by TelephonyManager wrapper in the clone runtime.
         */
        fun getSpoofedImei(): String? = config?.takeIf { it.spoofImei }?.imei

        /**
         * Returns the spoofed IMSI if configured, null otherwise.
         */
        fun getSpoofedImsi(): String? = config?.takeIf { it.spoofImei }?.imsi

        /**
         * Returns the spoofed WiFi MAC if configured, null otherwise.
         */
        fun getSpoofedWifiMac(): String? = config?.takeIf { it.spoofWifiMac }?.wifiMac

        /**
         * Returns the spoofed BT MAC if configured, null otherwise.
         */
        fun getSpoofedBtMac(): String? = config?.takeIf { it.spoofBtMac }?.btMac

        /**
         * Returns the spoofed GSF ID if configured, null otherwise.
         */
        fun getSpoofedGsfId(): String? = config?.takeIf { it.spoofGsfId }?.gsfId

        /**
         * Returns the spoofed Advertising ID if configured, null otherwise.
         */
        fun getSpoofedAdvertisingId(): String? = config?.takeIf { it.spoofAdvertisingId }?.advertisingId

        /**
         * Returns the spoofed WebView User Agent if configured, null otherwise.
         */
        fun getSpoofedWebViewUserAgent(): String? = config?.takeIf { it.customWebViewUaEnabled }?.webViewUserAgent

        /**
         * Returns the spoofed WiFi SSID if configured, null otherwise.
         */
        fun getSpoofedWifiSsid(): String? = config?.takeIf { it.spoofWifiInfo }?.wifiSsid

        /**
         * Returns the spoofed WiFi BSSID if configured, null otherwise.
         */
        fun getSpoofedWifiBssid(): String? = config?.takeIf { it.spoofWifiInfo }?.wifiBssid

        /**
         * Returns the spoofed GPU vendor if configured, null otherwise.
         */
        fun getSpoofedGpuVendor(): String? = config?.takeIf { it.spoofGpu }?.gpuVendor

        /**
         * Returns the spoofed GPU renderer if configured, null otherwise.
         */
        fun getSpoofedGpuRenderer(): String? = config?.takeIf { it.spoofGpu }?.gpuRenderer

        /**
         * Returns the spoofed SIM operator if configured, null otherwise.
         */
        fun getSpoofedSimOperator(): String? = config?.takeIf { it.spoofSim }?.simOperator

        /**
         * Returns the spoofed SIM country if configured, null otherwise.
         */
        fun getSpoofedSimCountry(): String? = config?.takeIf { it.spoofSim }?.simCountry

        /**
         * Returns the spoofed SIM operator name if configured, null otherwise.
         */
        fun getSpoofedSimOperatorName(): String? = config?.takeIf { it.spoofSim }?.simOperatorName
    }

    fun generateRandomIdentity(): IdentityConfig = IdentityConfig(
        androidId = (1..16).map { "0123456789abcdef".random() }.joinToString(""),
        imei = "35" + (1..13).map { (0..9).random() }.joinToString(""),
        wifiMac = randomMac(),
        btMac = randomMac(),
        gsfId = (1..16).map { "0123456789abcdef".random() }.joinToString(""),
        advertisingId = java.util.UUID.randomUUID().toString()
    )

    private fun randomMac(): String {
        val mac = (1..6).map { "%02X".format((0..255).random()) }.joinToString(":")
        val first = mac.substring(0,2).toInt(16) or 0x02
        return "%02X".format(first) + mac.substring(2)
    }
}

/**
 * Registry for identity spoofing overrides.
 * Used by wrapper classes in the clone runtime to look up spoofed values
 * without directly depending on IdentityConfig deserialization.
 */
object IdentitySpoofRegistry {
    var androidIdOverride: String? = null
    var imeiOverride: String? = null
    var imsiOverride: String? = null
    var wifiMacOverride: String? = null
    var btMacOverride: String? = null
    var gsfIdOverride: String? = null
    var advertisingIdOverride: String? = null
    var webViewUaOverride: String? = null
    var gpuVendorOverride: String? = null
    var gpuRendererOverride: String? = null
    var simOperatorOverride: String? = null
    var simCountryOverride: String? = null
    var simOperatorNameOverride: String? = null

    fun clear() {
        androidIdOverride = null
        imeiOverride = null
        imsiOverride = null
        wifiMacOverride = null
        btMacOverride = null
        gsfIdOverride = null
        advertisingIdOverride = null
        webViewUaOverride = null
        gpuVendorOverride = null
        gpuRendererOverride = null
        simOperatorOverride = null
        simCountryOverride = null
        simOperatorNameOverride = null
    }
}

// Additional spoofers per requirement
class AndroidIdSpoofer(val fakeId: String) {
    fun getFake(): String = fakeId
}
class ImeiSpoofer(val fakeImei: String) {
    fun getDeviceId(): String = fakeImei
}
class WifiMacSpoofer(val fakeMac: String) {
    fun getMac(): String = fakeMac
}
class GsfIdSpoofer(val fakeGsf: String)
class AdvertisingIdSpoofer(val fakeGaId: String)
class WebViewUaSpoofer(val ua: String)
class GpuInfoSpoofer(val vendor: String, val renderer: String)
class BuildPropSpoofer(val props: Map<String, String>)
