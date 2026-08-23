package com.clonemaster.runtime;

import android.content.Context;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
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
    private static volatile boolean publicEnabled = false;
    private static volatile Uri publicUri = null;
    private static volatile Context appCtx = null;

    private RuntimeLog() {}

    /** Enables the file sinks. Called once by RuntimeInit after config parse. Never throws. */
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
        // Public sink: Download/CloneMasterRT-<pkg>.log — reachable without adb.
        publicUri = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                android.content.ContentValues v = new android.content.ContentValues();
                v.put(android.provider.MediaStore.Downloads.DISPLAY_NAME,
                        "CloneMasterRT-" + ctx.getPackageName() + ".log");
                v.put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain");
                    Uri existing = findExisting(ctx);
                if (existing != null) {
                    // fresh log per install session: replace the old one
                    try { ctx.getContentResolver().delete(existing, null, null); } catch (Throwable ignored) {}
                }
                Uri uri = ctx.getContentResolver()
                        .insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                publicUri = uri;
                publicEnabled = uri != null;
                appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
                append("I", "public log sink: Download/CloneMasterRT-" + ctx.getPackageName() + ".log", null);
            } catch (Throwable t) {
                publicEnabled = false;
                publicUri = null;
                appendNote("public log sink unavailable: " + t.getMessage());
            }
        }
    }

    private static Uri findExisting(Context ctx) {
        try {
            android.database.Cursor c = ctx.getContentResolver().query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    new String[]{android.provider.MediaStore.Downloads._ID},
                    android.provider.MediaStore.Downloads.DISPLAY_NAME + "=?",
                    new String[]{"CloneMasterRT-" + ctx.getPackageName() + ".log"}, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        long id = c.getLong(0);
                        return android.content.ContentUris.withAppendedId(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                    }
                } finally { c.close(); }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    public static void i(String msg) {
        try { Log.i(RuntimeInit.TAG, msg); } catch (Throwable ignored) {}
        if (fileEnabled) append("I", msg, null);
        if (publicEnabled) appendPublic("I", msg, null);
    }

    public static void e(String msg, Throwable t) {
        try { Log.e(RuntimeInit.TAG, msg, t); } catch (Throwable ignored) {}
        if (fileEnabled) append("E", msg, t);
        if (publicEnabled) appendPublic("E", msg, t);
    }

    public static void w(String msg, Throwable t) {
        try { Log.w(RuntimeInit.TAG, msg, t); } catch (Throwable ignored) {}
        if (fileEnabled) append("W", msg, t);
        if (publicEnabled) appendPublic("W", msg, t);
    }

    private static void appendNote(String note) {
        try { Log.w(RuntimeInit.TAG, note); } catch (Throwable ignored) {}
    }

    private static synchronized void appendPublic(String level, String msg, Throwable t) {
        Context ctx = appCtx;
        Uri uri = publicUri;
        if (ctx == null || uri == null) return;
        java.io.OutputStream out = null;
        try {
            ContentResolver cr = ctx.getContentResolver();
            out = cr.openOutputStream(uri, "wa");
            if (out == null) { publicEnabled = false; return; }
            String line = ts() + " " + level + "/" + RuntimeInit.TAG + ": " + msg + "\n";
            out.write(line.getBytes("UTF-8"));
            if (t != null) {
                String el = ts() + " " + level + "/" + RuntimeInit.TAG + ":   "
                        + t.getClass().getName() + ": " + t.getMessage() + "\n";
                out.write(el.getBytes("UTF-8"));
            }
            out.flush();
        } catch (Throwable io) {
            publicEnabled = false; // fail-off; logcat + private sink remain
        } finally {
            if (out != null) try { out.close(); } catch (Throwable ignored) {}
        }
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
