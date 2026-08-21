package com.clonemaster.media

import android.content.Context
import android.media.AudioManager
import com.clonemaster.cloning.models.MediaConfig

class MediaControls(private val context: Context) {

    fun applyOnStart(config: MediaConfig) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (config.muteOnStart) {
            am.setStreamMute(AudioManager.STREAM_MUSIC, true)
        }
        config.volumeOnStart?.let {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, it, 0)
        }
        // Other controls via hooks
    }

    object Hooks {
        fun install(config: MediaConfig) {
            if (config.disableCamera) {
                // Hook Camera.open() to throw or return fake
            }
            if (config.disableMic) {
                // Hook AudioRecord
            }
            if (config.fakeCamera) {
                // Hook camera2 API to return FakeCamera implementation
            }
            if (config.disableHaptics) {
                // Hook Vibrator.vibrate -> no-op
            }
        }
    }
}

class FakeCamera(private val imagePaths: List<String>, private val randomize: Boolean) {
    fun getFakeImage(): String {
        if (imagePaths.isEmpty()) return ""
        return if (randomize) imagePaths.random() else imagePaths.first()
    }

    // EXIF handling
    fun handleExif(path: String, strip: Boolean, fake: Boolean): String {
        // If strip, remove EXIF via ExifInterface
        // If fake, generate fake EXIF with random GPS
        return path
    }
}
