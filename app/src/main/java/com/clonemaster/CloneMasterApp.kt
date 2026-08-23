package com.clonemaster

import android.app.Application
import androidx.multidex.MultiDexApplication

class CloneMasterApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        // Diagnostics FIRST: persistent on-device log + crash handler so every
        // later failure is captured into the shareable report.
        com.clonemaster.diagnostics.DiagLog.init(this)
        // Load native libs if present
        try {
            System.loadLibrary("appcloner")
        } catch (ignored: Throwable) {
            // lib not present yet – will be injected into clones, not host
        }
    }
}
