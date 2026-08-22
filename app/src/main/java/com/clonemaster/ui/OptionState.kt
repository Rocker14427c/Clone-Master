package com.clonemaster.ui

import com.clonemaster.cloning.models.CloneConfig

/**
 * Canonical clone-option enablement resolver.
 *
 * WHY THIS EXISTS (audit finding):
 * The clone-config UI shows values for identity/device fields (Android ID, IMEI,
 * Wi-Fi MAC, GSF ID, GAID, WebView UA, device profile, build props) that are
 * VALUE HOLDERS, not toggles. Displaying "pixel8_pro" or a random Android ID does
 * NOT mean the feature is active – activation is gated by a separate "spoof"
 * enable flag (identity.spoofAndroidId, identity.spoofImei,
 * environment.spoofPhysicalDeviceProfile, ...).
 *
 * That produces two wrong signals:
 *  1. "value visible -> feature active" (WRONG assumption, fields are display-only
 *     until the gate flag is set);
 *  2. the OLD counter only counted Boolean values present in the UI's configValues
 *     map, so flags enabled by PRESETS (e.g. Privacy preset sets
 *     spoofAndroidId=true) were invisible and uncounted -> UI could say "0/83"
 *     while the saved config actually contained active identity spoofing.
 *
 * OptionState computes enablement straight from CloneConfig:
 *  - Boolean-valued options are enabled when their field is true;
 *  - value-typed options (TEXT_FIELD/DROPDOWN/LIST_EDITOR identity & device
 *    options) are enabled only when their enable-gate flag is true;
 *  - options that map to the same config field (duplicate switches such as
 *    "Root Hide" and "Hide Root") count once.
 *
 * RULE (audit requirement): a configured VALUE is never the same thing as an
 * ENABLED FEATURE; a fresh CloneConfig() must resolve to 0 enabled options.
 */
object OptionState {

    /**
     * Value-typed config fields -> the enable gate flag that activates them.
     * Paths absent from this map and non-Boolean are "mode selectors"
     * (darkMode, statusBarColor, locale...) and are never counted as enabled.
     * "identity.deviceProfileName" is an alias of the physical-profile feature
     * (gated by environment.spoofPhysicalDeviceProfile) but joins the SAME
     * feature through environment.physicalDeviceProfileId, so it is deliberately
     * NOT counted separately (avoids double counting one feature).
     */
    private val VALUE_GATES: Map<String, String> = mapOf(
        "identity.androidId" to "identity.spoofAndroidId",
        "identity.imei" to "identity.spoofImei",
        "identity.wifiMac" to "identity.spoofWifiMac",
        "identity.btMac" to "identity.spoofBtMac",
        "identity.gsfId" to "identity.spoofGsfId",
        "identity.advertisingId" to "identity.spoofAdvertisingId",
        "identity.webViewUserAgent" to "identity.customWebViewUaEnabled",
        "identity.buildProps" to "identity.spoofBuildProps",
        "environment.physicalDeviceProfileId" to "environment.spoofPhysicalDeviceProfile"
    )

    /** One enabled option (deduplicated by config field path). */
    data class EnabledOption(val optionId: String, val fieldPath: String, val optionName: String)

    /** True when the configured field actually activates a (non-default) behavior. */
    fun isEnabled(config: CloneConfig, fieldPath: String): Boolean {
        val raw = readField(config, fieldPath) ?: return false
        if (raw is Boolean) return raw
        val gate = VALUE_GATES[fieldPath] ?: return false // mode selectors: never "enabled"
        return readField(config, gate) == true
    }

    /** All enabled options, deduplicated by config field path, in registry order. */
    fun enabledOptions(config: CloneConfig, options: List<OptionItem> = OptionRegistry.getAllOptions()): List<EnabledOption> {
        val seen = HashSet<String>()
        val out = ArrayList<EnabledOption>()
        for (o in options) {
            val path = o.configFieldPath
            if (isEnabled(config, path) && seen.add(path)) {
                out.add(EnabledOption(o.id, path, o.name))
            }
        }
        return out
    }

    fun enabledCount(config: CloneConfig, options: List<OptionItem> = OptionRegistry.getAllOptions()): Int =
        enabledOptions(config, options).size

    /**
     * Reflectively reads a nested config field ("identity.androidId" ->
     * config.identity.androidId) using Java getters (works on Android; no
     * kotlin-reflect dependency needed).
     */
    fun readField(config: CloneConfig, path: String): Any? {
        var current: Any? = config
        for (segment in path.split('.')) {
            current = getter(current, segment) ?: return null
        }
        return current
    }

    private fun getter(obj: Any?, name: String): Any? {
        if (obj == null) return null
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        return try {
            val method = obj.javaClass.methods.firstOrNull {
                it.name == getterName && it.parameterCount == 0
            }
            method?.invoke(obj)
        } catch (e: Exception) {
            null
        }
    }
}
