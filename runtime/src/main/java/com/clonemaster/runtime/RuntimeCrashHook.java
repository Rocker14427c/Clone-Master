package com.clonemaster.runtime;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Clone-side crash capture. Chains AROUND the app's existing crash handler:
 * writes the full stacktrace where the USER can actually reach it (public
 * Downloads via MediaStore on API 29+, else the clone's private files dir),
 * then delegates to the previously-installed handler so the app's own crash
 * UI keeps working exactly as before.
 *
 * Rationale: most clone-side faults leave our logcat lines unreachable for a
 * tester without adb. The Via splash crash showed only "Exception Happened
 * Thread[main,5,main]" on screen — with this hook the real stack lands in
 * Download/CloneMasterRT-<pkg>-crash.txt and can simply be shared.
 */
public final class RuntimeCrashHook {

    private RuntimeCrashHook() {}

    public static void install(final Context appContext) {
        try {
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            if (previous instanceof RuntimeCrashHandler) return; // already installed
            Thread.setDefaultUncaughtExceptionHandler(new RuntimeCrashHandler(appContext, previous));
            RuntimeLog.i("crash hook installed (chains to: "
                    + (previous == null ? "system" : previous.getClass().getSimpleName()) + ")");
        } catch (Throwable t) {
            RuntimeLog.e("crash hook install failed", t);
        }
    }

    private static final class RuntimeCrashHandler implements Thread.UncaughtExceptionHandler {
        private final Context ctx;
        private final Thread.UncaughtExceptionHandler previous;

        RuntimeCrashHandler(Context ctx, Thread.UncaughtExceptionHandler previous) {
            this.ctx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable throwable) {
            String report = null;
            try {
                report = buildReport(ctx, thread, throwable);
            } catch (Throwable ignored) {}
            try {
                if (report != null) writePublic(ctx, report);
            } catch (Throwable t) {
                try {
                    if (report != null) writePrivate(ctx, report);
                } catch (Throwable ignored) {}
            }
            try {
                RuntimeLog.e("CRASH captured to crash file: " + throwable.getClass().getSimpleName()
                        + ": " + throwable.getMessage(), throwable);
            } catch (Throwable ignored) {}
            try {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                    return;
                }
            } catch (Throwable ignored) {}
            // No previous handler: re-throw on the same thread semantics by
            // delegating to the system default (kills the process as usual).
            try {
                Thread.getDefaultUncaughtExceptionHandler();
            } catch (Throwable ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        }

        private static String buildReport(Context ctx, Thread thread, Throwable t) {
            StringWriter sw = new StringWriter();
            sw.write("Clone-Master runtime crash report\n");
            sw.write("package: " + ctx.getPackageName() + "\n");
            sw.write("time: " + new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date()) + "\n");
            sw.write("device: " + Build.MANUFACTURER + " " + Build.MODEL
                    + ", Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n");
            sw.write("thread: " + thread.getName() + "\n--------------------------------\n");
            t.printStackTrace(new PrintWriter(sw));
            return sw.toString();
        }

        private static void writePublic(Context ctx, String report) {
            if (Build.VERSION.SDK_INT < 29) try {
                writePrivate(ctx, report);
                return;
            } catch (Throwable ignored) { return; }
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME,
                    "CloneMasterRT-" + ctx.getPackageName() + "-crash.txt");
            v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            Uri uri = ctx.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) throw new IllegalStateException("insert null");
            try (OutputStream out = ctx.getContentResolver().openOutputStream(uri, "w")) {
                if (out == null) throw new IllegalStateException("stream null");
                out.write(report.getBytes("UTF-8"));
                out.flush();
            } catch (Throwable t) {
                try { ctx.getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
                throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t);
            }
        }

        private static void writePrivate(Context ctx, String report) throws Exception {
            File dir = new File(ctx.getFilesDir(), "cloner");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            FileWriter w = new FileWriter(new File(dir, "crash.txt"), false);
            try { w.write(report); } finally { w.close(); }
        }
    }
}
