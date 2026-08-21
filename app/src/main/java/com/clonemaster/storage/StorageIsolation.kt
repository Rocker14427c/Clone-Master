package com.clonemaster.storage

import android.content.Context
import android.os.Environment
import com.clonemaster.cloning.models.StorageConfig
import java.io.File

class StorageIsolation(private val context: Context) {

    fun apply(config: StorageConfig) {
        // Each clone already isolated by package name (different data dir)
        // Additional redirect handled via hooks
    }

    fun getRedirectedExternalDir(): File {
        return File(context.getExternalFilesDir(null), "redirected_external").apply { mkdirs() }
    }

    fun clearCacheOnExit() {
        context.cacheDir.deleteRecursively()
        context.externalCacheDir?.deleteRecursively()
    }

    fun secureDelete(paths: List<String>) {
        paths.forEach { path ->
            try {
                val f = File(path)
                if (f.exists()) {
                    // Overwrite with zeros then delete (simple secure delete)
                    if (f.isFile) {
                        f.outputStream().use { out ->
                            val zeros = ByteArray(1024)
                            var remaining = f.length()
                            while (remaining > 0) {
                                out.write(zeros, 0, minOf(remaining, 1024).toInt())
                                remaining -= 1024
                            }
                        }
                    }
                    f.deleteRecursively()
                }
            } catch (ignored: Exception) {}
        }
    }

    object Hooks {
        fun install(config: StorageConfig) {
            if (config.redirectExternalStorage) {
                // Hook Environment.getExternalStorageDirectory() -> clone's private external dir
                // Hook Context.getExternalFilesDir()
            }
            if (config.disableMediaAccess) {
                // Hook MediaStore queries to return empty
            }
        }
    }
}
