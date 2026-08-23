package com.clonemaster.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.FileProvider
import com.clonemaster.R
import java.io.File

/**
 * In-app viewer for the diagnostics log with a one-tap shareable report.
 * The whole point: a tester with no adb and no logcat knowledge can still
 * hand the developer a complete picture (device header, what they did, every
 * build step, every error, the last crash stacktrace).
 */
class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var text: TextView
    private lateinit var crashBanner: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)

        text = findViewById(R.id.diagText)
        crashBanner = findViewById(R.id.diagCrashBanner)

        val switchEnabled = findViewById<SwitchCompat>(R.id.diagSwitchEnabled)
        val switchVerbose = findViewById<SwitchCompat>(R.id.diagSwitchVerbose)
        val switchRuntime = findViewById<SwitchCompat>(R.id.diagSwitchRuntimeFile)

        switchEnabled.isChecked = DiagLog.isEnabled()
        switchVerbose.isChecked = DiagLog.isVerbose()
        switchRuntime.isChecked = DiagLog.isRuntimeFileLog()

        switchEnabled.setOnCheckedChangeListener { _, checked -> DiagLog.setEnabled(checked) }
        switchVerbose.setOnCheckedChangeListener { _, checked -> DiagLog.setVerbose(checked) }
        switchRuntime.setOnCheckedChangeListener { _, checked -> DiagLog.setRuntimeFileLog(checked) }

        findViewById<Button>(R.id.diagBtnRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.diagBtnClear).setOnClickListener {
            DiagLog.clearLogs()
            Toast.makeText(this, "Diagnostics cleared", Toast.LENGTH_SHORT).show()
            refresh()
        }
        findViewById<Button>(R.id.diagBtnCopy).setOnClickListener { copyReport() }
        findViewById<Button>(R.id.diagBtnShare).setOnClickListener { shareReport() }

        refresh()
    }

    private fun refresh() {
        crashBanner.visibility = if (DiagLog.hasCrashReport()) View.VISIBLE else View.GONE
        val body = DiagLog.currentSessionText()
        text.text = if (body.isBlank()) "(no diagnostics recorded yet — use the app, then come back)" else body
    }

    private fun copyReport() {
        val file = DiagLog.buildReport()
        if (file == null) {
            Toast.makeText(this, "Could not build report", Toast.LENGTH_LONG).show()
            return
        }
        val content = DiagLogCore.readTail(file, 256L * 1024L)
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Clone-Master diagnostics", content))
        Toast.makeText(this, "Diagnostics copied (last 256 KB)", Toast.LENGTH_SHORT).show()
    }

    private fun shareReport() {
        val file: File? = DiagLog.buildReport()
        if (file == null) {
            Toast.makeText(this, "Could not build report", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Clone-Master diagnostics report")
                putExtra(Intent.EXTRA_TEXT, "Clone-Master diagnostics report attached (includes device info, actions, errors and last crash if any).")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            DiagLog.i("Diag", "report shared (${file.length()} bytes)")
            startActivity(Intent.createChooser(intent, "Send diagnostics to developer"))
        } catch (t: Throwable) {
            Toast.makeText(this, "Share failed: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }
}
