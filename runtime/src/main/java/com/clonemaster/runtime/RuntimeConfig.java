package com.clonemaster.runtime;

import android.view.WindowManager;

import org.json.JSONObject;

/**
 * Parsed runtime configuration for one clone.
 *
 * Two asset sources (both written by the core engine at clone-build time):
 *  - assets/cloner_runtime.json  {\"originalApplication\": \"...\", \"runtimeVersion\": N}
 *  - assets/clone_config.json    full serialized CloneConfig (Gson output of the
 *                                manager app's data class; navigated here by key path)
 *
 * This class is PURE: no Android calls beyond framework classes whose constants
 * are compile-time inlined, so it is unit-testable on the JVM with org.json.
 *
 * JSON CONTRACT (must match CloneConfig field names — see
 * app/src/test CloneConfigJsonContractTest, which pins these paths):
 *  privacy.disableScreenshots : boolean
 *  display.keepScreenAwake    : boolean
 *  display.orientationLock    : int (-1 = no override; ActivityInfo constants)
 *  clonePackage               : string
 *  appName                    : string
 */
public final class RuntimeConfig {

    /** v3: meta JSON may carry "hookMode" ("wrap"|"factory"); v2 carried "fileLog". */
    public static final int RUNTIME_VERSION = 3;

    /** Never null; null means "no original Application android:name". */
    public final String originalApplication;
    public final String clonePackage;
    public final String appName;

    public final boolean disableScreenshots;
    public final boolean keepScreenAwake;
    /** -1 when no orientation override is configured. */
    public final int orientationLock;
    /** When true, the runtime mirrors its log lines into files/cloner/rt.log. */
    public final boolean fileLog;
    /** How the runtime was injected: "wrap" (application wrapper) or "factory"
     *  (appComponentFactory). Meta v1/v2 had no key -> defaults to "wrap". */
    public final String hookMode;

    private RuntimeConfig(String originalApplication, String clonePackage, String appName,
                          boolean disableScreenshots, boolean keepScreenAwake, int orientationLock,
                          boolean fileLog, String hookMode) {
        this.originalApplication = originalApplication;
        this.clonePackage = clonePackage;
        this.appName = appName;
        this.disableScreenshots = disableScreenshots;
        this.keepScreenAwake = keepScreenAwake;
        this.orientationLock = orientationLock;
        this.fileLog = fileLog;
        this.hookMode = hookMode;
    }

    /** Null-safe parse; any malformed/missing input yields a config with all features OFF. */
    public static RuntimeConfig parse(String runtimeJson, String cloneConfigJson) {
        String original = null;
        String clonePackage = "";
        String appName = "";
        boolean disableScreenshots = false;
        boolean keepScreenAwake = false;
        int orientationLock = -1;
        boolean fileLog = false;
        String hookMode = "wrap";
        try {
            if (runtimeJson != null) {
                JSONObject meta = new JSONObject(runtimeJson);
                original = meta.optString("originalApplication", null);
                if (original != null && original.isEmpty()) original = null;
                fileLog = meta.optBoolean("fileLog", false);
                hookMode = meta.optString("hookMode", "wrap");
            }
        } catch (Throwable ignored) { /* fail-soft: defaults */ }
        try {
            if (cloneConfigJson != null) {
                JSONObject cfg = new JSONObject(cloneConfigJson);
                clonePackage = cfg.optString("clonePackage", "");
                appName = cfg.optString("appName", "");
                JSONObject privacy = cfg.optJSONObject("privacy");
                if (privacy != null) {
                    disableScreenshots = privacy.optBoolean("disableScreenshots", false);
                }
                JSONObject display = cfg.optJSONObject("display");
                if (display != null) {
                    keepScreenAwake = display.optBoolean("keepScreenAwake", false);
                    orientationLock = display.optInt("orientationLock", -1);
                }
            }
        } catch (Throwable ignored) { /* fail-soft: defaults */ }
        return new RuntimeConfig(original, clonePackage, appName,
                disableScreenshots, keepScreenAwake, orientationLock, fileLog, hookMode);
    }

    /**
     * Window flags to set on every activity (pure decision).
     * Bit constants are inlined at compile time: FLAG_SECURE = 0x2000,
     * FLAG_KEEP_SCREEN_ON = 0x80. Test asserts these exact values.
     */
    public int windowFlagsToSet() {
        int f = 0;
        if (disableScreenshots) f |= WindowManager.LayoutParams.FLAG_SECURE;
        if (keepScreenAwake) f |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;
        return f;
    }

    /** True when any delivered V1 feature is active. */
    public boolean hasActiveFeatures() {
        return disableScreenshots || keepScreenAwake || orientationLock != -1;
    }
}
