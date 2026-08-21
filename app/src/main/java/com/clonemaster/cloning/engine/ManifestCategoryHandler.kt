package com.clonemaster.cloning.engine

import com.clonemaster.cloning.models.CloneConfig
import java.io.File

/**
 * Independent implementation for App category and Large heap manifest options
 * Public feature reference: WhatsNew 3.6.8 says "Options 'App category' and 'Large heap' moved to Manifest & resource options"
 * Equivalent functionality implemented independently
 */
class ManifestCategoryHandler {

    enum class AppCategory {
        UNDEFINED,
        GAME,
        AUDIO,
        VIDEO,
        IMAGE,
        SOCIAL,
        NEWS,
        MAPS,
        PRODUCTIVITY
    }

    data class ManifestOptions(
        var appCategory: AppCategory = AppCategory.UNDEFINED,
        var largeHeap: Boolean? = null, // null = keep original, true/false = override
        var targetSdk: Int? = null,
        var compileSdk: Int? = null
    )

    fun apply(manifestFile: File, options: ManifestOptions, config: CloneConfig, diagnostics: CloningDiagnostics) {
        var content = manifestFile.readText()

        // App category – android:appCategory attribute (API 26+)
        if (options.appCategory != AppCategory.UNDEFINED) {
            val categoryValue = when (options.appCategory) {
                AppCategory.GAME -> "game"
                AppCategory.AUDIO -> "audio"
                AppCategory.VIDEO -> "video"
                AppCategory.IMAGE -> "image"
                AppCategory.SOCIAL -> "social"
                AppCategory.NEWS -> "news"
                AppCategory.MAPS -> "maps"
                AppCategory.PRODUCTIVITY -> "productivity"
                else -> null
            }

            if (categoryValue != null) {
                if (content.contains("android:appCategory")) {
                    content = content.replace(Regex("""android:appCategory="[^"]*""""), """android:appCategory="$categoryValue"""")
                } else {
                    content = content.replace("<application", """<application android:appCategory="$categoryValue"""")
                }
                diagnostics.log("Set appCategory to $categoryValue")
            }
        }

        // Large heap
        if (options.largeHeap != null) {
            if (content.contains("android:largeHeap")) {
                content = content.replace(Regex("""android:largeHeap="[^"]*""""), """android:largeHeap="${options.largeHeap}"""")
            } else {
                content = content.replace("<application", """<application android:largeHeap="${options.largeHeap}"""")
            }
            diagnostics.log("Set largeHeap to ${options.largeHeap}")
        }

        manifestFile.writeText(content)
    }
}
