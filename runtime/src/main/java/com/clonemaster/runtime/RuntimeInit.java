package com.clonemaster.runtime;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

/**
 * Runtime bootstrap executed inside the clone. Installs the configured
 * per-activity appliers. NEVER throws: a failing runtime must degrade to a
 * clean clone, never crash the user's app.
 *
 * Load verification anchor (device): logcat line
 *   CloneMasterRT: runtime v1 loaded: pkg=... original=... screens=... awake=... orient=...
 */
public final class RuntimeInit {

    public static final String TAG = "CloneMasterRT";

    private static volatile boolean inited = false;

    private RuntimeInit() {}

    public static synchronized void init(Context appContext) {
        if (inited) return;
        try {
            String runtimeJson = readAsset(appContext, "cloner_runtime.json");
            String cloneConfigJson = readAsset(appContext, "clone_config.json");
            RuntimeConfig cfg = RuntimeConfig.parse(runtimeJson, cloneConfigJson);
            inited = true;
            Log.i(TAG, "runtime v" + RuntimeConfig.RUNTIME_VERSION + " loaded: pkg="
                    + appContext.getPackageName()
                    + " original=" + cfg.originalApplication
                    + " screens=" + cfg.disableScreenshots
                    + " awake=" + cfg.keepScreenAwake
                    + " orient=" + cfg.orientationLock);
            if (cfg.hasActiveFeatures() && appContext instanceof Application) {
                ((Application) appContext).registerActivityLifecycleCallbacks(new WindowFlagsApplier(cfg));
            }
        } catch (Throwable t) {
            Log.e(TAG, "runtime init failed – clone continues UNMODIFIED", t);
        }
    }

    /** Reads only the runtime meta (original application name) — used by HookApplication's wrap step. */
    public static String readOriginalApplication(Context ctx) {
        try {
            return RuntimeConfig.parse(readAsset(ctx, "cloner_runtime.json"), null).originalApplication;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readAsset(Context ctx, String name) {
        InputStream in = null;
        try {
            in = ctx.getAssets().open(name);
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, Charset.forName("UTF-8")));
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        } catch (Throwable missing) {
            return null; // asset absent -> defaults
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignored) {}
        }
    }
}
