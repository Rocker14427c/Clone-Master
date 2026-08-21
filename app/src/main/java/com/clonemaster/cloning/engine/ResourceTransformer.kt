package com.clonemaster.cloning.engine

import android.graphics.*
import android.graphics.drawable.BitmapDrawable
import com.clonemaster.cloning.models.CloneConfig
import com.clonemaster.cloning.models.IconBadge
import java.io.File

/**
 * Resource handling – icon replacement, badge overlay, string/app name changes.
 */
class ResourceTransformer {

    fun transform(resDir: File, config: CloneConfig, diagnostics: CloningDiagnostics) {
        // Change app name
        val valuesDir = File(resDir, "values")
        if (valuesDir.exists()) {
            valuesDir.listFiles { f -> f.name.startsWith("strings") }?.forEach { stringsFile ->
                try {
                    var content = stringsFile.readText()
                    // Replace app_name
                    if (config.appName.isNotEmpty()) {
                        content = content.replace(
                            Regex("""<string name="app_name">[^<]*</string>"""),
                            """<string name="app_name">${escapeXml(config.appName)}</string>"""
                        )
                    }
                    stringsFile.writeText(content)
                    diagnostics.log("Updated app_name in ${stringsFile.name}")
                } catch (e: Exception) {
                    diagnostics.warn("Failed to update ${stringsFile.name}: ${e.message}")
                }
            }
        }

        // Icon handling
        val mipmapDirs = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("mipmap") } ?: emptyArray()
        val drawableDirs = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("drawable") } ?: emptyArray()
        val iconDirs = mipmapDirs + drawableDirs

        // Custom icon
        if (config.customIconPath != null) {
            val customIconFile = File(config.customIconPath)
            if (customIconFile.exists()) {
                iconDirs.forEach { dir ->
                    dir.listFiles { f -> f.name.contains("ic_launcher") }?.forEach { iconFile ->
                        try {
                            customIconFile.copyTo(iconFile, overwrite = true)
                            diagnostics.log("Replaced icon ${iconFile.path}")
                        } catch (e: Exception) {
                            diagnostics.warn("Icon replace failed: ${e.message}")
                        }
                    }
                }
            }
        }

        // Badge overlay
        if (config.iconBadge != IconBadge.NONE) {
            iconDirs.forEach { dir ->
                dir.listFiles { f -> f.name.contains("ic_launcher") && (f.extension == "png" || f.extension == "webp") }?.forEach { iconFile ->
                    try {
                        applyBadge(iconFile, config)
                        diagnostics.log("Applied badge to ${iconFile.name}")
                    } catch (e: Exception) {
                        diagnostics.warn("Badge failed for ${iconFile.name}: ${e.message}")
                    }
                }
            }
        }

        // Remove branding – reference assets/app_cloner_branding.png from original AppCloner
        // In Clone-Master we ensure no branding remains
        File(resDir.parentFile, "assets").listFiles()?.find { it.name.contains("branding") }?.delete()
    }

    private fun applyBadge(iconFile: File, config: CloneConfig) {
        val bitmap = BitmapFactory.decodeFile(iconFile.absolutePath) ?: return
        val mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutable)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        val size = mutable.width.coerceAtMost(mutable.height)
        val badgeSize = size * 0.35f
        val badgeX = mutable.width - badgeSize
        val badgeY = 0f

        when (config.iconBadge) {
            IconBadge.NUMBER -> {
                paint.color = config.badgeColor
                canvas.drawCircle(badgeX + badgeSize/2, badgeY + badgeSize/2, badgeSize/2, paint)
                paint.color = Color.WHITE
                paint.textSize = badgeSize * 0.6f
                paint.textAlign = Paint.Align.CENTER
                val text = config.badgeNumber.toString()
                val fm = paint.fontMetrics
                canvas.drawText(text, badgeX + badgeSize/2, badgeY + badgeSize/2 - (fm.ascent + fm.descent)/2, paint)
            }
            IconBadge.DOT -> {
                paint.color = config.badgeColor
                canvas.drawCircle(badgeX + badgeSize/2, badgeY + badgeSize/2, badgeSize/3, paint)
            }
            IconBadge.CUSTOM_TEXT -> {
                // placeholder
                paint.color = config.badgeColor
                canvas.drawRoundRect(badgeX, badgeY, badgeX+badgeSize, badgeY+badgeSize*0.6f, 8f, 8f, paint)
            }
            else -> {}
        }

        iconFile.outputStream().use { out ->
            mutable.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private fun escapeXml(s: String): String = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}
