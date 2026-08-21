package com.clonemaster.ui

import android.content.Context
import com.clonemaster.cloning.models.CloneConfig
import com.google.gson.GsonBuilder
import java.io.File

/**
 * Save / load configuration – independent implementation
 * Stores configuration independently from source application
 */
class ConfigStorageManager(private val context: Context) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val configDir: File by lazy { File(context.filesDir, "clone_configs").apply { mkdirs() } }
    private val exportDir: File by lazy { File(context.getExternalFilesDir(null), "exports").apply { mkdirs() } }
    private val backupDir: File by lazy { File(context.getExternalFilesDir(null), "backups").apply { mkdirs() } }

    fun saveConfiguration(config: CloneConfig): File {
        val file = File(configDir, "${config.clonePackage}.json")
        file.writeText(gson.toJson(config))
        android.util.Log.d("CloneMaster", "Config saved: ${file.absolutePath}")
        return file
    }

    fun loadConfiguration(clonePackage: String): CloneConfig? {
        val file = File(configDir, "$clonePackage.json")
        return if (file.exists() && file.length() > 0) {
            try {
                GsonBuilder().create().fromJson(file.readText(), CloneConfig::class.java)
            } catch (ignored: Exception) {
                android.util.Log.w("CloneMaster", "Failed to load config $clonePackage: ${ignored.message}")
                null
            }
        } else null
    }

    fun loadAllConfigurations(): List<CloneConfig> {
        return configDir.listFiles()?.mapNotNull { file ->
            try {
                if (file.extension == "json" && file.length() > 0) {
                    GsonBuilder().create().fromJson(file.readText(), CloneConfig::class.java)
                } else null
            } catch (ignored: Exception) {
                android.util.Log.w("CloneMaster", "Failed to parse ${file.name}: ${ignored.message}")
                null
            }
        } ?: emptyList()
    }

    fun duplicateConfiguration(original: CloneConfig, newPackageSuffix: String = "copy"): CloneConfig {
        val newPackage = "${original.clonePackage}.$newPackageSuffix"
        val duplicated = original.copy(
            clonePackage = newPackage,
            appName = "${original.appName} Copy",
            cloneIndex = original.cloneIndex + 1
        )
        saveConfiguration(duplicated)
        return duplicated
    }

    fun resetToDefaults(originalPackage: String): CloneConfig {
        // Create default config for original package
        return CloneConfig(
            originalPackage = originalPackage,
            clonePackage = "$originalPackage.clone1",
            cloneIndex = 1,
            appName = "${originalPackage.substringAfterLast('.')} Clone"
        )
    }

    fun exportConfiguration(config: CloneConfig): File {
        exportDir.mkdirs()
        val file = File(exportDir, "${config.clonePackage}_config_${System.currentTimeMillis()}.json")
        file.writeText(gson.toJson(config))
        return file
    }

    fun importConfiguration(uri: android.net.Uri): CloneConfig? {
        return try {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: return null
            val config = GsonBuilder().create().fromJson(text, CloneConfig::class.java)
            saveConfiguration(config)
            config
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "importConfiguration failed: ${ignored.message}", ignored)
            null
        }
    }

    fun importConfigurationFromFile(file: File): CloneConfig? {
        return try {
            if (!file.exists() || file.length() == 0L) return null
            val config = GsonBuilder().create().fromJson(file.readText(), CloneConfig::class.java)
            saveConfiguration(config)
            config
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "importConfigurationFromFile failed: ${ignored.message}", ignored)
            null
        }
    }

    fun deleteConfiguration(clonePackage: String): Boolean {
        val file = File(configDir, "$clonePackage.json")
        return if (file.exists()) file.delete() else false
    }

    fun getExportedConfigs(): List<File> {
        return exportDir.listFiles { f -> f.extension == "json" }?.toList() ?: emptyList()
    }
}
