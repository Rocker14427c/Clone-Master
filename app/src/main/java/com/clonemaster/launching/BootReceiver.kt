package com.clonemaster.launching

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.clonemaster.cloning.models.CloneConfig
import com.google.gson.Gson
import java.io.File

/**
 * QA Fix: Persistent mode missing BootReceiver – if persistent mode is intended to be supported, need BootReceiver to restart clone after reboot
 * Independent implementation, handles Android 10+ background start restrictions with graceful degradation
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return

        try {
            // Check if persistent mode is enabled for any clone
            val configDir = File(context.filesDir, "clone_configs")
            if (!configDir.exists()) return

            val persistentClones = configDir.listFiles()?.mapNotNull { file ->
                try {
                    val config = Gson().fromJson(file.readText(), CloneConfig::class.java)
                    if (config.launching.persistentMode) config else null
                } catch (e: Exception) {
                    android.util.Log.w("CloneMaster", "Failed to parse config ${file.name}: ${e.message}")
                    null
                }
            } ?: emptyList()

            if (persistentClones.isEmpty()) {
                android.util.Log.d("CloneMaster", "No persistent clones found, skipping boot handling")
                return
            }

            // For each persistent clone, try to start it
            // Android 10+ restricts background activity starts – need to use notification or foreground service
            persistentClones.forEach { config ->
                try {
                    android.util.Log.d("CloneMaster", "Handling boot for persistent clone: ${config.clonePackage}")

                    // Check if app can start activities from background (Android 10+ limitation)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        // On Android 10+, cannot start activity from background directly – use notification to prompt user
                        // Or start foreground service that then shows notification
                        val serviceIntent = Intent(context, PersistentCloneService::class.java).apply {
                            putExtra("clonePackage", config.clonePackage)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } else {
                        // Pre-Android 10, can start activity
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(config.clonePackage)
                        launchIntent?.let {
                            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(it)
                        }
                    }

                } catch (e: Exception) {
                    android.util.Log.e("CloneMaster", "Failed to handle boot for ${config.clonePackage}: ${e.message}", e)
                }
            }

        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "BootReceiver failed: ${e.message}", e)
        }
    }
}

class PersistentCloneService : android.app.Service() {

    override fun onBind(intent: android.content.Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val clonePackage = intent?.getStringExtra("clonePackage") ?: return START_NOT_STICKY

        try {
            // Create notification channel
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    "persistent_clone",
                    "Persistent Clones",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
                (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).createNotificationChannel(channel)
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(clonePackage)
            val pendingIntent = if (launchIntent != null) {
                android.app.PendingIntent.getActivity(
                    this, 0, launchIntent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val notification = androidx.core.app.NotificationCompat.Builder(this, "persistent_clone")
                .setContentTitle("Persistent Clone")
                .setContentText("Tap to open ${clonePackage}")
                .setSmallIcon(com.clonemaster.R.mipmap.ic_launcher)
                .setOngoing(false)
                .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
                .build()

            startForeground(2001, notification)

            // Stop after showing notification – user can tap to open
            stopSelf()

        } catch (e: Exception) {
            android.util.Log.e("CloneMaster", "PersistentCloneService failed for $clonePackage: ${e.message}", e)
            stopSelf()
        }

        return START_NOT_STICKY
    }
}
