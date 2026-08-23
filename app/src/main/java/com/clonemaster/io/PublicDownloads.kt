package com.clonemaster.io

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Saves build artifacts (cloned APKs, exports) into the PUBLIC Downloads
 * collection so the user finds them in any file manager. The previous flow
 * wrote to getExternalFilesDir("exports"), which is buried under
 * Android/data/com.clonemaster/ and confused on-device testers.
 */
object PublicDownloads {

    sealed class Result {
        /** Saved; [display] is what to show the user (uri on Q+, path on older). */
        data class Saved(val display: String) : Result()
        /** API 24–28 and WRITE_EXTERNAL_STORAGE not granted: caller should request it and retry. */
        object NeedsPermission : Result()
        data class Failed(val reason: String) : Result()
    }

    fun save(
        context: Context,
        src: File,
        displayName: String,
        mime: String = "application/vnd.android.package-archive"
    ): Result {
        if (!src.exists() || src.length() == 0L) return Result.Failed("source missing/empty: ${src.absolutePath}")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, src, displayName, mime)
        } else {
            saveLegacy(context, src, displayName)
        }
    }

    @android.annotation.TargetApi(29)
    private fun saveViaMediaStore(context: Context, src: File, displayName: String, mime: String): Result {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri == null) return Result.Failed("MediaStore insert returned null")
            val ok = try {
                resolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
            } catch (t: Throwable) {
                try { resolver.delete(uri, null, null) } catch (ignored: Throwable) {}
                return Result.Failed("write failed: ${t.message}")
            }
            if (!ok) {
                try { resolver.delete(uri, null, null) } catch (ignored: Throwable) {}
                return Result.Failed("output stream unavailable")
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            Result.Saved("Download/$displayName")
        } catch (t: Throwable) {
            Result.Failed("MediaStore error: ${t.message}")
        }
    }

    @Suppress("DEPRECATION")
    private fun saveLegacy(context: Context, src: File, displayName: String): Result {
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) return Result.NeedsPermission
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            dir.mkdirs()
            val dst = File(dir, displayName)
            src.inputStream().use { input -> FileOutputStream(dst).use { input.copyTo(it) } }
            Result.Saved(dst.absolutePath)
        } catch (t: Throwable) {
            Result.Failed("legacy write failed: ${t.message}")
        }
    }
}
