package com.clonemaster.display

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Independent implementation for in-app support / live chat reference
 * Public feature reference: App Cloner lists "In-app live chat" under Display options
 * This is equivalent functionality implemented independently – not copying proprietary chat implementation
 * Functional parity: provide help/support overlay linking to official support channels (Telegram, email) with independent UI
 * Compatibility with Android limitations: uses SYSTEM_ALERT_WINDOW permission, degrades gracefully if not granted
 */
class SupportChatOverlay(private val context: Context) {

    data class SupportConfig(
        var enabled: Boolean = false,
        var showHelpButton: Boolean = true,
        var supportEmail: String = "support@clonemaster.app",
        var telegramLink: String = "https://t.me/CloneMasterSupport",
        var customMessage: String = "Need help with Clone-Master?"
    )

    fun showHelpOverlay(activity: Activity, config: SupportConfig) {
        if (!config.enabled) return

        val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC2196F3.toInt())
            setPadding(24, 24, 24, 24)
        }

        val title = TextView(activity).apply {
            text = config.customMessage
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 14f
        }

        val emailBtn = Button(activity).apply {
            text = "Email Support"
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:${config.supportEmail}")
                    }
                    activity.startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }

        val telegramBtn = Button(activity).apply {
            text = "Telegram"
            setOnClickListener {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(config.telegramLink))
                    activity.startActivity(intent)
                } catch (ignored: Exception) {}
            }
        }

        layout.addView(title)
        layout.addView(emailBtn)
        layout.addView(telegramBtn)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = 20
            y = 100
        }

        try {
            wm.addView(layout, params)
            // Auto-remove after 10 sec
            layout.postDelayed({ try { wm.removeView(layout) } catch (ignored: Exception) {} }, 10000)
        } catch (e: Exception) {
            // No overlay permission – degrade gracefully, show Toast with support info
            android.widget.Toast.makeText(activity, "Support: ${config.supportEmail}", android.widget.Toast.LENGTH_LONG).show()
        }
    }

    object Hooks {
        fun install(config: SupportConfig) {
            // Could hook to show help button in clone's UI if needed
        }
    }
}
