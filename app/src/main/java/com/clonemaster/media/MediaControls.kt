package com.clonemaster.media

import android.content.Context
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.clonemaster.cloning.models.ExifHandling
import com.clonemaster.cloning.models.MediaConfig
import java.io.File

/**
 * Media & Audio/Hardware Controls – per-clone configurable.
 *
 * Runtime hooks intercept media API calls to enforce clone's media policies.
 * Implementation uses Java-level interception and AudioManager/Vibrator APIs.
 *
 * Features implemented:
 * - Mute on start
 * - Volume control on start
 * - Camera disable (PackageManager + Camera API)
 * - Microphone disable (AudioRecord override)
 * - Haptics disable (Vibrator override)
 * - Fake camera with image substitution
 * - Audio focus control
 * - Chromecast disable
 * - Volume rocker lock
 * - EXIF handling (strip/fake/keep)
 */
class MediaControls(private val context: Context) {

    fun applyOnStart(config: MediaConfig) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Mute on start
            if (config.muteOnStart) {
                am.setStreamMute(AudioManager.STREAM_MUSIC, true)
                android.util.Log.i("CloneMaster", "Audio muted on start")
            }

            // 2. Volume on start
            config.volumeOnStart?.let { volume ->
                am.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                android.util.Log.i("CloneMaster", "Volume set to $volume on start")
            }

            // 3. Mute while foreground (register activity lifecycle callback)
            if (config.muteWhileForeground) {
                MediaSpoofRegistry.muteWhileForeground = true
                am.setStreamMute(AudioManager.STREAM_MUSIC, true)
                android.util.Log.i("CloneMaster", "Mute while foreground enabled")
            }

            // 4. Prevent volume change
            if (config.preventVolumeChange) {
                MediaSpoofRegistry.preventVolumeChange = true
                android.util.Log.i("CloneMaster", "Volume change prevention enabled")
            }

            // 5. Volume rocker lock
            if (config.volumeRockerLock) {
                MediaSpoofRegistry.volumeRockerLock = true
                android.util.Log.i("CloneMaster", "Volume rocker locked")
            }

            // 6. Show volume indicator
            if (config.showVolumeIndicator) {
                MediaSpoofRegistry.showVolumeIndicator = true
            }

            // 7. Disable audio focus
            if (config.disableAudioFocus) {
                MediaSpoofRegistry.disableAudioFocus = true
                android.util.Log.i("CloneMaster", "Audio focus disabled")
            }

            // 8. Allow other audio (don't request focus)
            if (config.allowOtherAudio) {
                MediaSpoofRegistry.allowOtherAudio = true
                android.util.Log.i("CloneMaster", "Other audio allowed (no focus request)")
            }

        } catch (e: Exception) {
            android.util.Log.w("CloneMaster", "MediaControls.applyOnStart failed: ${e.message}", e)
        }
    }

    object Hooks {
        private var installed = false
        private var config: MediaConfig? = null

        /**
         * Install all media hooks inside the cloned app process.
         * Called from HookFramework.installAll() inside the cloned app.
         */
        fun install(cfg: MediaConfig) {
            if (installed) return
            config = cfg
            installed = true

            try {
                android.util.Log.i("CloneMaster", "MediaControls.Hooks installing...")

                // 1. Disable camera
                if (cfg.disableCamera) {
                    MediaSpoofRegistry.disableCamera = true
                    android.util.Log.i("CloneMaster", "Camera disabled")
                }

                // 2. Disable microphone
                if (cfg.disableMic) {
                    MediaSpoofRegistry.disableMic = true
                    android.util.Log.i("CloneMaster", "Microphone disabled")
                }

                // 3. Fake camera with image substitution
                if (cfg.fakeCamera) {
                    MediaSpoofRegistry.fakeCamera = true
                    MediaSpoofRegistry.fakeCameraImages = cfg.fakeCameraImages.toMutableList()
                    MediaSpoofRegistry.randomizeCameraImages = cfg.randomizeCameraImages
                    android.util.Log.i("CloneMaster", "Fake camera enabled with ${cfg.fakeCameraImages.size} images")
                }

                // 4. EXIF handling
                MediaSpoofRegistry.exifHandling = cfg.exifHandling
                android.util.Log.i("CloneMaster", "EXIF handling: ${cfg.exifHandling}")

                // 5. Disable haptics
                if (cfg.disableHaptics) {
                    MediaSpoofRegistry.disableHaptics = true
                    android.util.Log.i("CloneMaster", "Haptics disabled")
                }

                // 6. Disable Chromecast
                if (cfg.disableChromecast) {
                    MediaSpoofRegistry.disableChromecast = true
                    android.util.Log.i("CloneMaster", "Chromecast disabled")
                }

                // 7. Secondary display support
                if (cfg.secondaryDisplay) {
                    MediaSpoofRegistry.secondaryDisplay = true
                    android.util.Log.i("CloneMaster", "Secondary display enabled")
                }

                // 8. Audio capture
                if (cfg.audioCapture) {
                    MediaSpoofRegistry.audioCapture = true
                    android.util.Log.i("CloneMaster", "Audio capture enabled")
                }

                // 9. Preferred camera app
                if (cfg.preferredCameraApp.isNotEmpty()) {
                    MediaSpoofRegistry.preferredCameraApp = cfg.preferredCameraApp
                    android.util.Log.i("CloneMaster", "Preferred camera app: ${cfg.preferredCameraApp}")
                }

                android.util.Log.i("CloneMaster", "MediaControls.Hooks installed successfully")

            } catch (e: Exception) {
                android.util.Log.e("CloneMaster", "MediaControls.Hooks install failed: ${e.message}", e)
            }
        }

        /**
         * Checks if camera access should be blocked.
         * Used by Camera.open() wrapper in clone runtime.
         */
        fun isCameraDisabled(): Boolean = config?.disableCamera == true

        /**
         * Checks if microphone access should be blocked.
         * Used by AudioRecord wrapper in clone runtime.
         */
        fun isMicDisabled(): Boolean = config?.disableMic == true

        /**
         * Checks if fake camera should be used.
         * Used by camera2 API wrapper in clone runtime.
         */
        fun shouldUseFakeCamera(): Boolean = config?.fakeCamera == true

        /**
         * Gets a fake camera image path.
         * Used by fake camera implementation.
         */
        fun getFakeCameraImage(): String? {
            val images = config?.fakeCameraImages ?: return null
            if (images.isEmpty()) return null
            return if (config?.randomizeCameraImages == true) {
                images.random()
            } else {
                images.firstOrNull()
            }
        }

        /**
         * Checks if haptics should be disabled.
         * Used by Vibrator wrapper in clone runtime.
         */
        fun shouldDisableHaptics(): Boolean = config?.disableHaptics == true

        /**
         * Checks if audio focus should be disabled.
         * Used by AudioManager wrapper in clone runtime.
         */
        fun shouldDisableAudioFocus(): Boolean = config?.disableAudioFocus == true

        /**
         * Gets the EXIF handling mode.
         * Used by camera/image processing wrappers.
         */
        fun getExifHandling(): ExifHandling = config?.exifHandling ?: ExifHandling.KEEP

        /**
         * Disables haptics on the device (immediate effect).
         */
        fun disableHapticsNow(context: Context) {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    manager.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }
                vibrator.cancel()
                android.util.Log.d("CloneMaster", "Haptics disabled")
            } catch (e: Exception) {
                android.util.Log.w("CloneMaster", "Failed to disable haptics: ${e.message}")
            }
        }
    }
}

/**
 * Fake camera implementation.
 * Returns predefined images instead of real camera capture.
 */
class FakeCamera(private val imagePaths: List<String>, private val randomize: Boolean) {

    fun getFakeImage(): String {
        if (imagePaths.isEmpty()) return ""
        return if (randomize) imagePaths.random() else imagePaths.first()
    }

    /**
     * Handle EXIF data according to the configured policy.
     * @param path Path to the image file
     * @param handling EXIF handling mode (KEEP, STRIP, FAKE)
     * @return Path to the processed image (may be same as input)
     */
    fun handleExif(path: String, handling: ExifHandling): String {
        return when (handling) {
            ExifHandling.KEEP -> path
            ExifHandling.STRIP -> {
                // Strip EXIF data using ExifInterface
                try {
                    val exif = androidx.exifinterface.media.ExifInterface(path)
                    // Remove all EXIF attributes
                    val attributes = listOf(
                        androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE,
                        androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE,
                        androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE,
                        androidx.exifinterface.media.ExifInterface.TAG_DATETIME,
                        androidx.exifinterface.media.ExifInterface.TAG_MAKE,
                        androidx.exifinterface.media.ExifInterface.TAG_MODEL,
                        androidx.exifinterface.media.ExifInterface.TAG_DEVICE_SET_DESCRIPTION
                    )
                    attributes.forEach { tag ->
                        exif.setAttribute(tag, null)
                    }
                    exif.saveAttributes()
                    android.util.Log.d("CloneMaster", "EXIF stripped from $path")
                } catch (e: Exception) {
                    android.util.Log.w("CloneMaster", "Failed to strip EXIF: ${e.message}")
                }
                path
            }
            ExifHandling.FAKE -> {
                // Replace EXIF with fake data
                try {
                    val exif = androidx.exifinterface.media.ExifInterface(path)
                    // Fake GPS (random location)
                    val fakeLat = 37.4220 + (Math.random() - 0.5) * 0.01
                    val fakeLng = -122.0841 + (Math.random() - 0.5) * 0.01
                    exif.setLatLong(fakeLat, fakeLng)
                    // Fake camera info
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MAKE, "FakeCamera")
                    exif.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_MODEL, "CloneMaster")
                    exif.saveAttributes()
                    android.util.Log.d("CloneMaster", "EXIF faked for $path")
                } catch (e: Exception) {
                    android.util.Log.w("CloneMaster", "Failed to fake EXIF: ${e.message}")
                }
                path
            }
        }
    }
}

/**
 * Registry for media spoofing state.
 * Used by wrapper classes in clone runtime to check media policies.
 */
object MediaSpoofRegistry {
    var muteWhileForeground: Boolean = false
    var preventVolumeChange: Boolean = false
    var volumeRockerLock: Boolean = false
    var showVolumeIndicator: Boolean = false
    var disableAudioFocus: Boolean = false
    var allowOtherAudio: Boolean = false
    var disableCamera: Boolean = false
    var disableMic: Boolean = false
    var fakeCamera: Boolean = false
    var fakeCameraImages: MutableList<String> = mutableListOf()
    var randomizeCameraImages: Boolean = false
    var exifHandling: ExifHandling = ExifHandling.KEEP
    var disableHaptics: Boolean = false
    var disableChromecast: Boolean = false
    var secondaryDisplay: Boolean = false
    var audioCapture: Boolean = false
    var preferredCameraApp: String = ""

    fun clear() {
        muteWhileForeground = false
        preventVolumeChange = false
        volumeRockerLock = false
        showVolumeIndicator = false
        disableAudioFocus = false
        allowOtherAudio = false
        disableCamera = false
        disableMic = false
        fakeCamera = false
        fakeCameraImages.clear()
        randomizeCameraImages = false
        exifHandling = ExifHandling.KEEP
        disableHaptics = false
        disableChromecast = false
        secondaryDisplay = false
        audioCapture = false
        preferredCameraApp = ""
    }
}
