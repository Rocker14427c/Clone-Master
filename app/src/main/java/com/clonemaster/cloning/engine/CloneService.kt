package com.clonemaster.cloning.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.clonemaster.R
import com.clonemaster.cloning.models.CloneConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CloneService : Service() {

    private val engine by lazy { CloneEngine(this) }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configJson = intent?.getStringExtra("config") ?: return START_NOT_STICKY
        val config = com.google.gson.Gson().fromJson(configJson, CloneConfig::class.java)

        val notification = NotificationCompat.Builder(this, "clone_channel")
            .setContentTitle("Cloning ${config.appName}")
            .setContentText("Starting...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()

        startForeground(1, notification)

        CoroutineScope(Dispatchers.IO).launch {
            engine.clone(config) { progress ->
                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                val updated = NotificationCompat.Builder(this@CloneService, "clone_channel")
                    .setContentTitle("Cloning ${config.appName}")
                    .setContentText(progress)
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setOngoing(true)
                    .build()
                nm.notify(1, updated)
            }.onSuccess { apk ->
                stopForeground(true)
                stopSelf()
                // Broadcast success
                sendBroadcast(Intent("com.clonemaster.CLONE_COMPLETE").apply {
                    putExtra("apk", apk.absolutePath)
                })
            }.onFailure { e ->
                stopForeground(true)
                stopSelf()
                sendBroadcast(Intent("com.clonemaster.CLONE_FAILED").apply {
                    putExtra("error", e.message)
                })
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("clone_channel", "Cloning", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }
}
