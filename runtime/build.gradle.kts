plugins {
    id("com.android.library")
}

/**
 * Self-contained runtime that gets injected into generated clones.
 *
 * HARD CONSTRAINTS — violating any of these breaks clones:
 *  1. PLAIN JAVA ONLY (no Kotlin: a Kotlin runtime would need a second,
 *     kotlin-stdlib dex in every clone).
 *  2. NO libraries — no androidx, no Gson, nothing. android.jar + org.json
 *     (part of the Android framework since API 1) only.
 *  3. FAIL-SOFT EVERYWHERE — the runtime must NEVER crash the clone.
 *
 * The build dexes the compiled classes with d8 and exposes the result at
 * build/runtimeDexAssets/cloner_runtime/classes.dex. The :app module merges
 * that directory into its APK assets; CloneEngine reads it from assets and
 * hands the bytes to the core engine for injection into the clone.
 *
 * Reference (architecture only, clean-room): Next-Cloner shows the commercial
 * reference delivering its runtime exactly this way — prebuilt runtime dex
 * archives (assets/classes.dex.xz, assets/kotlin.dex.xz) + hook-native zips
 * (libPine/libByteHook/libAndHook/libAliuHook zip) merged into the clone.
 */
android {
    namespace = "com.clonemaster.runtime"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        buildConfig = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

/**
 * Dex the runtime (d8 from the Android SDK build-tools). Input is AGP's own
 * bundled classes jar (deterministic, always up to date with sources).
 */
val dexRuntime = tasks.register<Exec>("dexRuntime") {
    dependsOn("bundleLibRuntimeToJarRelease")
    val sdkDir = android.sdkDirectory
    val d8 = File(sdkDir, "build-tools/${android.buildToolsVersion}/d8")
    val androidJar = File(sdkDir, "platforms/android-34/android.jar")
    val inJar = layout.buildDirectory.file("intermediates/runtime_library_classes_jar/release/classes.jar").get().asFile
    val outDir = layout.buildDirectory.dir("runtimeDex")
    inputs.file(inJar)
    inputs.file(androidJar)
    outputs.dir(outDir)
    commandLine(d8.absolutePath, "--min-api", "21", "--lib", androidJar.absolutePath,
        "--output", outDir.get().asFile.absolutePath, inJar.absolutePath)
}

/** Stage the dex under an asset-shaped directory for :app to merge. */
val stageRuntimeAsset = tasks.register<Copy>("stageRuntimeAsset") {
    dependsOn(dexRuntime)
    from(layout.buildDirectory.dir("runtimeDex")) {
        include("classes.dex")
        rename { "classes.dex" }
    }
    into(layout.buildDirectory.dir("runtimeDexAssets/cloner_runtime"))
}

// :app wires preBuild -> stageRuntimeAsset, so the APK always carries a fresh runtime dex.
// (No assembleRelease wiring: AGP registers it lazily and nothing consumes the runtime AAR directly.)
