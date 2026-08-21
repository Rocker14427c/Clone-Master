package com.clonemaster.navigation

import android.app.Activity
import android.view.View
import com.clonemaster.cloning.models.NavigationConfig

class NavigationControls {

    fun addFloatingBack(activity: Activity) {
        val wm = activity.getSystemService(android.content.Context.WINDOW_SERVICE) as android.view.WindowManager
        val btn = android.widget.Button(activity).apply { text = "←" }
        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            android.graphics.PixelFormat.TRANSLUCENT
        )
        btn.setOnClickListener { activity.onBackPressed() }
        try { wm.addView(btn, params) } catch (ignored: Exception) {}
    }

    object Hooks {
        fun install(config: NavigationConfig) {
            if (config.popupBlocker) {
                // Hook AlertDialog.show to block certain popups
            }
            if (config.blockedActivities.isNotEmpty()) {
                // Hook Activity.startActivity to block
            }
            if (config.kioskMode) {
                // Hook to prevent leaving activity
            }
        }
    }
}
