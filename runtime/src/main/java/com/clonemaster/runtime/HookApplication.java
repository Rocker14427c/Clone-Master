package com.clonemaster.runtime;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;

import java.lang.reflect.Method;

/**
 * Application wrapper injected into clones.
 *
 * The clone's manifest points application android:name at this class. On
 * startup this wrapper:
 *  1. instantiates the ORIGINAL application class (name preserved in
 *     assets/cloner_runtime.json at build time),
 *  2. attaches it to the real base context via the protected
 *     ContextWrapper.attachBaseContext (public API, reflection-safe),
 *  3. initializes the runtime (reads clone_config.json, installs features),
 *  4. delegates the Application lifecycle to the original instance.
 *
 * EVERYTHING is fail-soft: any failure leaves a functioning clean clone.
 */
public class HookApplication extends Application {

    public static final String WRAPPER_CLASS = "com.clonemaster.runtime.HookApplication";
    private static final String RUNTIME_PACKAGE_PREFIX = "com.clonemaster.runtime.";

    private Application original;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        try {
            String origName = RuntimeInit.readOriginalApplication(base);
            if (origName != null
                    && !origName.equals(WRAPPER_CLASS)
                    && !origName.startsWith(RUNTIME_PACKAGE_PREFIX)) {
                Class<?> cls = Class.forName(origName, true, base.getClassLoader());
                Object inst = cls.getDeclaredConstructor().newInstance();
                if (inst instanceof Application) {
                    original = (Application) inst;
                    Method m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                    m.setAccessible(true);
                    m.invoke(original, base);
                    RuntimeLog.i("original application attached: " + origName);
                }
            }
        } catch (Throwable t) {
            RuntimeLog.e("original application wrap failed – clone continues WITHOUT original app class", t);
            original = null;
        }
        RuntimeInit.init(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (original != null) {
            try {
                original.onCreate();
            } catch (Throwable t) {
                RuntimeLog.e("original onCreate failed", t);
            }
        }
    }

    @Override
    public void onTerminate() {
        if (original != null) {
            try { original.onTerminate(); } catch (Throwable ignored) {}
        }
        super.onTerminate();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (original != null) {
            try { original.onConfigurationChanged(newConfig); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        if (original != null) {
            try { original.onLowMemory(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (original != null) {
            try { original.onTrimMemory(level); } catch (Throwable ignored) {}
        }
    }

    /** Test/inspection hook: the wrapped original instance, if any. */
    public Application getOriginalApplication() {
        return original;
    }
}
