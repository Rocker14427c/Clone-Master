package com.clonemaster.environment

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import com.clonemaster.R
import com.clonemaster.cloning.models.EnvironmentConfig
import com.clonemaster.cloning.models.DeviceProfile
import com.google.gson.GsonBuilder

/**
 * Detection Diagnostics Screen – shows root/emulator/QEMU/virtual/debug/mock/build/filesystem/hardware/sensor/telephony/network indicators
 */
class EnvironmentDiagnosticsActivity : AppCompatActivity() {

    private lateinit var diagnostics: DetectionDiagnostics
    private lateinit var envManager: EnvironmentManager
    private lateinit var config: EnvironmentConfig
    private lateinit var profile: DeviceProfile

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_environment_diagnostics)

        envManager = EnvironmentManager(this)
        diagnostics = DetectionDiagnostics(this)

        // Load config from intent or default
        val profileId = intent.getStringExtra("profileId") ?: "pixel8_pro"
        profile = envManager.getDeviceProfile(profileId)
        config = EnvironmentConfig(
            hideRoot = true,
            hideEmulator = true,
            hideDeveloperOptions = true,
            hideUsbAdb = true,
            hideMockLocation = true,
            spoofPhysicalDeviceProfile = true,
            physicalDeviceProfileId = profileId
        )

        // Allow toggling via UI
        val recycler = findViewById<RecyclerView>(R.id.recyclerDiagnostics)
        recycler.layoutManager = LinearLayoutManager(this)

        val (categories, report) = envManager.runDiagnostics(config)

        findViewById<TextView>(R.id.textReport).text = report
        findViewById<TextView>(R.id.textProfile).text = "Profile: ${profile.displayName}\nFingerprint: ${profile.fingerprint}\nManufacturer: ${profile.manufacturer} Model: ${profile.model}\nConsistency: ${envManager.getDeviceProfile(profileId).let { DeviceProfileManager(this).getCoherentEnvironment(it) }.buildFingerprint}"

        recycler.adapter = DiagnosticsAdapter(categories)
    }

    class DiagnosticsAdapter(private val categories: List<DetectionDiagnostics.DiagnosticCategory>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemCount(): Int = categories.sumOf { it.checks.size + 1 } // +1 for header

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_diagnostic, parent, false)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            // Simplified binding – in real app would have header + check items
            var pos = position
            for (cat in categories) {
                if (pos == 0) {
                    // header
                    holder.itemView.findViewById<TextView>(R.id.textName).text = "## ${cat.name} (${cat.checks.count { it.detected }} detected)"
                    return
                }
                pos--
                if (pos < cat.checks.size) {
                    val check = cat.checks[pos]
                    val status = when {
                        !check.detected -> "✅ NOT DETECTED"
                        check.verifiedBypass -> "✅ MITIGATED & VERIFIED"
                        check.mitigated -> "⚠️ MITIGATED (not yet verified)"
                        else -> "❌ DETECTED (unmitigated)"
                    }
                    holder.itemView.findViewById<TextView>(R.id.textName).text = "${check.name}: $status"
                    holder.itemView.findViewById<TextView>(R.id.textDesc).text = "${check.description}\nCurrent: ${check.currentValue}\nExpected: ${check.expectedPhysicalValue}\nMethod: ${check.mitigationMethod}"
                    return
                }
                pos -= cat.checks.size
            }
        }
    }
}
