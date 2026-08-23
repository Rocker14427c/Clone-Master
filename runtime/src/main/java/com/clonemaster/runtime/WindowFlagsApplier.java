package com.clonemaster.runtime;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

/**
 * Applies window/orientation features to every activity of the clone.
 * Delivered V1 features (real, no method-hooking required):
 *  - privacy.disableScreenshots -> FLAG_SECURE (also blocks recents thumbnails)
 *  - display.keepScreenAwake    -> FLAG_KEEP_SCREEN_ON
 *  - display.orientationLock    -> Activity.setRequestedOrientation(...)
 */
public final class WindowFlagsApplier implements Application.ActivityLifecycleCallbacks {

    private final RuntimeConfig cfg;

    public WindowFlagsApplier(RuntimeConfig cfg) {
        this.cfg = cfg;
    }

    /** Static entry point so logic is directly unit-testable per-activity. */
    public static void apply(Activity a, RuntimeConfig cfg) {
        try {
            int flags = cfg.windowFlagsToSet();
            if (flags != 0 && a.getWindow() != null) {
                a.getWindow().setFlags(flags, flags);
            }
        } catch (Throwable t) {
            Log.w(RuntimeInit.TAG, "window flags failed on " + a.getLocalClassName(), t);
        }
        try {
            if (cfg.orientationLock != -1) {
                a.setRequestedOrientation(cfg.orientationLock);
            }
        } catch (Throwable t) {
            Log.w(RuntimeInit.TAG, "orientation lock failed on " + a.getLocalClassName(), t);
        }
        // Reference values kept static for tests:
        // WindowManager.LayoutParams.FLAG_SECURE, FLAG_KEEP_SCREEN_ON are inlined.
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
        apply(activity, cfg);
    }

    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityResumed(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
