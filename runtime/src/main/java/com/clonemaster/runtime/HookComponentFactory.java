package com.clonemaster.runtime;

import android.app.Activity;
import android.app.AppComponentFactory;
import android.app.Application;
import android.os.Bundle;

/**
 * Component-factory injection hook (preferred runtime delivery mode).
 *
 * Why this instead of wrapping the Application class (the old mode): when the
 * manifest application android:name is replaced by a wrapper, the PROCESS
 * Application instance becomes the wrapper. Apps that call their own
 * application singleton pattern — e.g. (MyApp) getApplication() — then crash
 * with ClassCastException right at startup (observed on-device: clone stuck
 * on splash + "Exception Happened Thread[main,5,main]"). With the factory
 * approach the ORIGINAL Application class stays in the manifest and stays the
 * real process application; we only get called while it is being created.
 *
 * Contract with the engine (core ManifestCloner): factory mode is only used
 * when the source manifest has NO appComponentFactory of its own (else the
 * wrap fallback is used, since we cannot chain cleanly without reflection
 * into hidden APIs).
 *
 * Lifecycle note: instantiateApplication() runs BEFORE the Application is
 * attached to a base context, so its assets are not readable here. We
 * register a lifecycle callback on the instance (safe pre-attach — the
 * callback list lives on the instance) and RuntimeInit runs on the first
 * activity creation, where a fully-attached Context exists.
 *
 * Only ever loaded on API 28+ (when the manifest attribute is honored); older
 * devices ignore the attribute, so the class is never touched there.
 */
public final class HookComponentFactory extends AppComponentFactory {

    public static final String FACTORY_CLASS = "com.clonemaster.runtime.HookComponentFactory";

    @Override
    public Application instantiateApplication(ClassLoader cl, String className) {
        Application app;
        try {
            app = super.instantiateApplication(cl, className);
        } catch (Throwable t) {
            // Fail-soft: never break process creation.
            RuntimeLog.e("factory: super.instantiateApplication failed for " + className, t);
            throwIfPossible(t);
            return null; // unreachable; throwIfPossible always throws on error
        }
        try {
            app.registerActivityLifecycleCallbacks(new BootCallback());
            // Crash capture as early as possible: covers Application.onCreate
            // crashes (factory-mode runtime boot waits for the first activity).
            RuntimeCrashHook.installEarly(app);
            RuntimeLog.i("factory: application created (" + className + "), runtime boot + crash hook registered");
        } catch (Throwable t) {
            RuntimeLog.e("factory: failed to register runtime boot – clone continues UNMODIFIED", t);
        }
        return app;
    }

    /** Boots the runtime on the first activity creation. Named static class
     *  (an anonymous inner class crashed d8/R8 at dex time — keeps the runtime
     *  asset buildable). */
    static final class BootCallback implements Application.ActivityLifecycleCallbacks {
        private boolean booted = false;

        @Override
        public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
            if (booted) return;
            booted = true;
            RuntimeInit.init(activity, RuntimeInit.MODE_FACTORY);
            // The WindowFlagsApplier was registered DURING this same callback
            // dispatch (snapshot semantics) -> apply flags to this first
            // activity explicitly.
            RuntimeConfig cfg = RuntimeInit.currentConfig();
            if (cfg != null) WindowFlagsApplier.apply(activity, cfg);
        }

        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivityResumed(Activity activity) {}
        @Override public void onActivityPaused(Activity activity) {}
        @Override public void onActivityStopped(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
        @Override public void onActivityDestroyed(Activity activity) {}
    }

    private static void throwIfPossible(Throwable t) {
        if (t instanceof RuntimeException) throw (RuntimeException) t;
        if (t instanceof Error) throw (Error) t;
        // checked creation failure: wrap so the framework still sees a failure,
        // matching what default instantiation would do without us
        throw new RuntimeException(t);
    }
}
