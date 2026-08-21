package com.clonemaster.identity

import android.content.Context
import com.clonemaster.cloning.models.CloneConfig
import com.clonemaster.cloning.models.IdentityConfig
import java.io.File

/**
 * Identity & Device Fingerprint Controls – per-clone configurable.
 * Each method documents Android restriction and graceful degradation.
 */
class IdentityManager(private val context: Context) {

    fun loadConfig(): IdentityConfig {
        return try {
            val file = File(context.filesDir, "assets/clone_config.json")
            if (!file.exists()) return IdentityConfig()
            val json = file.readText()
            com.google.gson.Gson().fromJson(json, IdentityConfig::class.java) ?: IdentityConfig()
        } catch (e: Exception) {
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
     * Runtime hooks – these are installed inside cloned app via HookFramework.
     * Documentation of restrictions:
     * - IMEI/IMSI: Android 10+ requires READ_PRIVILEGED_PHONE_STATE, not granted to normal apps. We hook TelephonyManager and return spoofed value, but some system APIs bypass hook.
     * - WiFi MAC: Android 6+ returns 02:00:00:00:00:00 for getMacAddress(). Hook returns spoofed but explain.
     * - BT MAC: Android 6+ similar.
     * - Android ID: Can be spoofed via Settings.Secure hook.
     * - GSF ID, Advertising ID: Hook GMS client.
     * - Build props: Hook android.os.Build fields + __system_property_get via native hook (ByteHook).
     */
    object Hooks {
        fun install(config: IdentityConfig) {
            // These would use Pine to hook methods. Stub implementation logs.
            // Real implementation:
            // Pine.hook(Settings.Secure::class.java.getMethod("getStringForUser", ContentResolver::class.java, String::class.java, Int::class.java)) { ... }
            // For brevity, pseudocode.
        }
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

// Additional spoofers per requirement
class AndroidIdSpoofer(val fakeId: String) {
    fun getFake(): String = fakeId
}
class ImeiSpoofer(val fakeImei: String) {
    // Android 10+ limitation: returns null unless privileged. We document and return fake via hook.
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
