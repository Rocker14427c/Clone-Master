package com.clonemaster.storage

import android.content.Context
import android.os.Environment
import com.clonemaster.cloning.models.StorageConfig
import java.io.File

/**
 * Storage Isolation & Controls – per-clone configurable.
 *
 * Each clone already gets isolated storage via its unique package name.
 * Additional controls redirect external storage, block media access,
 * and implement secure deletion.
 */
class StorageIsolation(private val context: Context) {

    fun apply(config: StorageConfig) {
        // Each clone already isolated by package name (different data dir)
        // Additional redirect handled via hooks
    }

    fun getRedirectedExternalDir(): File {
        return File(context.getExternalFilesDir(null), "redirected_external").apply { mkdirs() }
    }

    fun clearCacheOnExit() {
        try {
            context.cacheDir.deleteRecursively()
            context.externalCacheDir?.deleteRecursively()
            android.util.Log.i("CloneMaster", "Cache cleared on exit")
        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "Cache clear failed: ${e.message}")
        }
    }

    fun secureDelete(paths: List<String>) {
        paths.forEach { path ->
            try {
                val f = File(path)
                if (f.exists()) {
                    if (f.isFile) {
                        // Overwrite with zeros 3x, then random data, then delete
                        val length = f.length()
                        for (pass in 1..3) {
                            f.outputStream().use { out ->
                                val zeros = ByteArray(4096)
                                var remaining = length
                                while (remaining > 0) {
                                    val chunk = minOf(remaining, 4096).toInt()
                                    out.write(zeros, 0, chunk)
                                    remaining -= chunk
                                }
                                out.fd.sync()
                            }
                        }
                        // Final pass with random data
                        f.outputStream().use { out ->
                            val random = java.security.SecureRandom()
                            val buf = ByteArray(4096)
                            var remaining = length
                            while (remaining > 0) {
                                val chunk = minOf(remaining, 4096).toInt()
                                random.nextBytes(buf)
                                out.write(buf, 0, chunk)
                                remaining -= chunk
                            }
                            out.fd.sync()
                        }
                    }
                    f.deleteRecursively()
                    android.util.Log.d("CloneMaster", "Secure deleted: $path")
                }
            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Secure delete failed for $path: ${e.message}")
            }
        }
    }

    object Hooks {
        private var installed = false
        private var config: StorageConfig? = null

        fun install(cfg: StorageConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "StorageIsolation.Hooks installing...")

                if (cfg.redirectExternalStorage) {
                    StorageSpoofRegistry.redirectExternalStorage = true
                    android.util.Log.i("CloneMaster", "External storage redirection enabled")
                }

                if (cfg.disableMediaAccess) {
                    StorageSpoofRegistry.disableMediaAccess = true
                    android.util.Log.i("CloneMaster", "Media access disabled")
                }

                if (cfg.installToSd) {
                    StorageSpoofRegistry.installToSd = true
                    android.util.Log.i("CloneMaster", "Install to SD enabled (manifest: installLocation=preferExternal)")
                }

                if (cfg.preventBackup) {
                    StorageSpoofRegistry.preventBackup = true
                    android.util.Log.i("CloneMaster", "Backup prevention enabled (android:allowBackup=false)")
                }

                if (cfg.preserveDataOnUninstall) {
                    StorageSpoofRegistry.preserveDataOnUninstall = true
                    android.util.Log.i("CloneMaster", "Preserve data on uninstall (manifest: hasFragileUserData)")
                }

                if (cfg.clearCacheOnExit) {
                    StorageSpoofRegistry.clearCacheOnExit = true
                    android.util.Log.i("CloneMaster", "Clear cache on exit enabled")
                }

                if (cfg.isolateStorage) {
                    StorageSpoofRegistry.isolateStorage = true
                    android.util.Log.i("CloneMaster", "Storage isolation enabled")
                }

                if (cfg.secureDeletePaths.isNotEmpty()) {
                    StorageSpoofRegistry.secureDeletePaths = cfg.secureDeletePaths.toList()
                    android.util.Log.i("CloneMaster", "Secure delete paths: ${cfg.secureDeletePaths.size} paths")
                }

                if (cfg.bundleSdDirs.isNotEmpty()) {
                    StorageSpoofRegistry.bundleSdDirs = cfg.bundleSdDirs.toList()
                }

                android.util.Log.i("CloneMaster", "StorageIsolation.Hooks installed successfully")
            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "StorageIsolation.Hooks install failed: ${e.message}", e)
            }
        }

        /**
         * Check if a path should be redirected to clone's private storage.
         * Called by Environment.getExternalStorageDirectory() wrapper.
         */
        fun getRedirectedPath(originalPath: String): String? {
            if (config?.redirectExternalStorage != true) return null
            // Redirect to clone's private external dir
            return originalPath // Wrapper will replace with clone's external files dir
        }

        /**
         * Check if media store queries should return empty results.
         * Called by MediaStore query wrapper.
         */
        fun shouldBlockMediaAccess(): Boolean = config?.disableMediaAccess == true
    }
}

/**
 * Registry for storage spoofing state.
 */
object StorageSpoofRegistry {
    var redirectExternalStorage: Boolean = false
    var disableMediaAccess: Boolean = false
    var installToSd: Boolean = false
    var preventBackup: Boolean = false
    var preserveDataOnUninstall: Boolean = false
    var clearCacheOnExit: Boolean = false
    var isolateStorage: Boolean = false
    var secureDeletePaths: List<String> = emptyList()
    var bundleSdDirs: List<String> = emptyList()

    fun clear() {
        redirectExternalStorage = false
        disableMediaAccess = false
        installToSd = false
        preventBackup = false
        preserveDataOnUninstall = false
        clearCacheOnExit = false
        isolateStorage = false
        secureDeletePaths = emptyList()
        bundleSdDirs = emptyList()
    }
}
