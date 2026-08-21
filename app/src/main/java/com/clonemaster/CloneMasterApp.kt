package com.clonemaster

import android.app.Application
import androidx.multidex.MultiDexApplication

class CloneMasterApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // Init diagnostics, crash reporting, etc
        // Load native libs if present
        try {
            System.loadLibrary("appcloner")
        } catch (_: Exception) {
            // lib not present yet – will be injected into clones, not host
        }
    }
}
