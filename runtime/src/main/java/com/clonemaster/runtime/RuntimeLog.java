package com.clonemaster.runtime;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logger for the injected clone runtime. Always mirrors to logcat under the
 * CloneMasterRT anchor tag; optionally (and only when the clone was BUILT with
 * {@code fileLog:true}) also appends to {@code files/cloner/rt.log} inside the
 * clone's private storage so the user can pull runtime events without logcat.
 *
 * Zero dependencies, fail-off: any error disables the file sink and file
 * logging never throws into the cloned app. Clean clones (built without
 * optional features) carry NO runtime at all, so this cannot affect the 0/N
 * byte-stability rule.
 *
 * Retrieval on a stock device: the clone's private dir needs root or a
 * debuggable clone; the logcat anchor therefore remains the primary channel.
 */
public final class RuntimeLog {

    private static final long MAX_BYTES = 128L * 1024L;
    private static volatile boolean fileEnabled = false;
    private static volatile File file = null;

    private RuntimeLog() {}

    /** Enables the file sink. Called once by RuntimeInit after config parse. Never throws. */
    public static synchronized void enableFile(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), "cloner");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File f = new File(dir, "rt.log");
            if (f.exists() && f.length() > MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                f.delete(); // simple cap: restart the file each launch once full
            }
            file = f;
            fileEnabled = true;
            append("I", "rt.log file sink enabled: " + f.getAbsolutePath(), null);
        } catch (Throwable t) {
            fileEnabled = false;
            file = null;
            appendNote("file sink disabled (" + t.getMessage() + ")");
        }
    }

    public static void i(String msg) {
        try { Log.i(RuntimeInit.TAG, msg); } catch (Throwable ignored) {}
        if (fileEnabled) append("I", msg, null);
    }

    public static void e(String msg, Throwable t) {
        try { Log.e(RuntimeInit.TAG, msg, t); } catch (Throwable ignored) {}
        if (fileEnabled) append("E", msg, t);
    }

    public static void w(String msg, Throwable t) {
        try { Log.w(RuntimeInit.TAG, msg, t); } catch (Throwable ignored) {}
        if (fileEnabled) append("W", msg, t);
    }

    private static void appendNote(String note) {
        try { Log.w(RuntimeInit.TAG, note); } catch (Throwable ignored) {}
    }

    private static synchronized void append(String level, String msg, Throwable t) {
        File f = file;
        if (f == null) return;
        BufferedWriter w = null;
        try {
            if (f.exists() && f.length() > MAX_BYTES) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            w = new BufferedWriter(new FileWriter(f, true), 4096);
            w.write(ts() + " " + level + "/" + RuntimeInit.TAG + ": " + msg);
            w.newLine();
            if (t != null) {
                w.write(ts() + " " + level + "/" + RuntimeInit.TAG + ":   " + t.getClass().getName() + ": " + t.getMessage());
                w.newLine();
            }
            w.flush();
        } catch (Throwable io) {
            fileEnabled = false; // fail-off; keep logcat
        } finally {
            if (w != null) try { w.close(); } catch (Throwable ignored) {}
        }
    }

    private static String ts() {
        try {
            return new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        } catch (Throwable t) {
            return "?";
        }
    }
}
