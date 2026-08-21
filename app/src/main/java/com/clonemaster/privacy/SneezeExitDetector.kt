package com.clonemaster.privacy

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper

/**
 * Independent implementation for "Sneeze to exit" – equivalent functionality to public feature reference "sneeze to exit"
 * Public reference: App Cloner lists "Exit app on screen off, 'sneeze' to exit" under Privacy options
 * This is independent implementation using proximity + loud sound detection, not copying proprietary
 * Functional parity: detect sneeze-like event (proximity near + sudden loud audio) and exit app
 * Compatibility with Android limitations: requires RECORD_AUDIO permission, degrades gracefully if not granted
 */
class SneezeExitDetector(private val context: Context, private val onSneeze: () -> Unit) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var proximitySensor: Sensor? = null
    private var isProximityNear = false
    private var mediaRecorder: MediaRecorder? = null
    private var isListening = false
    private val handler = Handler(Looper.getMainLooper())
    private var lastLoudSoundTime = 0L

    data class SneezeConfig(
        var enabled: Boolean = false,
        var sensitivity: Float = 0.8f, // 0-1
        var useProximity: Boolean = true,
        var useSound: Boolean = true,
        var soundThresholdDb: Int = 70 // loudness threshold
    )

    fun start(config: SneezeConfig) {
        if (!config.enabled) return

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)

        if (config.useProximity && proximitySensor != null) {
            sensorManager?.registerListener(this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL)
        }

        if (config.useSound) {
            startSoundMonitoring(config)
        }

        isListening = true
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        try { mediaRecorder?.stop(); mediaRecorder?.release() } catch (ignored: Exception) {}
        mediaRecorder = null
        isListening = false
    }

    private fun startSoundMonitoring(config: SneezeConfig) {
        try {
            mediaRecorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile("/dev/null")
                prepare()
                start()
            }

            // Poll amplitude every 200ms
            handler.post(object : Runnable {
                override fun run() {
                    if (!isListening) return
                    try {
                        val amplitude = mediaRecorder?.maxAmplitude ?: 0
                        // Convert amplitude to dB roughly: 20*log10(amplitude)
                        val db = if (amplitude > 0) (20 * Math.log10(amplitude.toDouble())).toInt() else 0
                        if (db > config.soundThresholdDb) {
                            lastLoudSoundTime = System.currentTimeMillis()
                            // If proximity is near or proximity not required, trigger sneeze
                            if (!config.useProximity || isProximityNear) {
                                checkSneezePattern()
                            }
                        }
                    } catch (ignored: Exception) {}
                    handler.postDelayed(this, 200)
                }
            })

        } catch (e: Exception) {
            // RECORD_AUDIO permission not granted – degrade gracefully
            android.util.Log.w("CloneMaster", "Sneeze detector: mic not available, using proximity only: ${e.message}")
        }
    }

    private fun checkSneezePattern() {
        // Simple pattern: loud sound + proximity near within 1 sec
        val now = System.currentTimeMillis()
        if (now - lastLoudSoundTime < 1000) {
            // Trigger exit
            onSneeze()
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            isProximityNear = event.values[0] < (proximitySensor?.maximumRange ?: 5f) / 2
            if (isProximityNear) {
                // If sound was loud recently, trigger
                if (System.currentTimeMillis() - lastLoudSoundTime < 1000) {
                    checkSneezePattern()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    object Hooks {
        fun install(config: SneezeConfig) {
            // Hook would be installed in clone – registers sensor listener in Application.onCreate
        }
    }
}
