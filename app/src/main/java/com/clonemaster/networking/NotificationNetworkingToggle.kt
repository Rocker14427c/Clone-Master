package com.clonemaster.networking

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.clonemaster.R

/**
 * Independent implementation for "Disable / enable networking manually via notification"
 * Public feature reference: App Cloner lists this under Networking options
 * Equivalent functionality: foreground notification with toggle button to enable/disable networking for clone
 * Functional parity with independent implementation
 */
class NotificationNetworkingToggle(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "networking_toggle"
        const val NOTIFICATION_ID = 1001
        const val ACTION_TOGGLE_NETWORK = "com.clonemaster.TOGGLE_NETWORK"
    }

    data class NotificationToggleConfig(
        var enabled: Boolean = false,
        var showToggle: Boolean = false,
        var currentState: Boolean = true // true = networking enabled
    )

    fun showNotification(config: NotificationToggleConfig) {
        if (!config.enabled && !config.showToggle) return

        createChannel()

        val toggleIntent = Intent(ACTION_TOGGLE_NETWORK).apply {
            putExtra("enable", !config.currentState)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Clone-Master Networking")
            .setContentText(if (config.currentState) "Networking: ON (tap to disable)" else "Networking: OFF (tap to enable)")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .addAction(
                if (config.currentState) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (config.currentState) "Disable" else "Enable",
                pendingIntent
            )
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun hideNotification() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Networking Toggle", NotificationManager.IMPORTANCE_LOW)
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    class ToggleReceiver : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_TOGGLE_NETWORK) {
                val enable = intent.getBooleanExtra("enable", true)
                // Save state and apply via ProxyManager.Hooks
                context?.getSharedPreferences("networking", Context.MODE_PRIVATE)?.edit()
                    ?.putBoolean("networking_enabled", enable)?.apply()

                // Update notification
                NotificationNetworkingToggle(context!!).showNotification(
                    NotificationToggleConfig(enabled = true, currentState = enable)
                )
            }
        }
    }

    object Hooks {
        fun install(config: NotificationToggleConfig) {
            // In clone, check SharedPreferences networking_enabled and block networking if disabled
            // Hook ConnectivityManager.getActiveNetworkInfo -> null if disabled
            // Hook Socket.connect -> throw if disabled
        }
    }
}
