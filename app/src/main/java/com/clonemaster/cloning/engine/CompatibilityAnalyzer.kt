package com.clonemaster.cloning.engine

import com.clonemaster.cloning.models.*

/**
 * Compatibility analysis stage before cloning.
 * Detects situations likely to fail and explains to user.
 */
class CompatibilityAnalyzer {

    fun analyze(appInfo: AppInfo): CompatibilityReport {
        val checks = mutableListOf<CompatibilityCheck>()

        // Certificate validation
        checks.add(
            CompatibilityCheck(
                id = "cert_pinning",
                name = "Certificate Pinning / Network Security Config",
                status = if (appInfo.usesPlayServices) CompatibilityStatus.WARNING else CompatibilityStatus.OK,
                description = "App uses Play Services which may validate certificates. Cloned APK will have different signature.",
                recommendation = "If login fails, try disabling Play Services hooks or use identity spoofing only."
            )
        )

        // Play Services
        checks.add(
            CompatibilityCheck(
                id = "gms",
                name = "Google Play Services Dependency",
                status = when {
                    appInfo.usesPlayServices && appInfo.usesSafetyNet -> CompatibilityStatus.BLOCKER
                    appInfo.usesPlayServices -> CompatibilityStatus.WARNING
                    else -> CompatibilityStatus.OK
                },
                description = "GMS apps often check package name + signature. SafetyNet/Play Integrity will fail on cloned app.",
                recommendation = "Enable 'Hide mock location', spoof GSF ID, and consider disabling SafetyNet checks via hook."
            )
        )

        // Billing
        checks.add(
            CompatibilityCheck(
                id = "billing",
                name = "In-App Purchases / Billing",
                status = if (appInfo.usesBilling) CompatibilityStatus.WARNING else CompatibilityStatus.OK,
                description = "BillingClient validates package name with Play Store. Purchases may not work in clone.",
                recommendation = "Cloning will succeed but purchases will fail. This is expected."
            )
        )

        // Firebase Auth
        checks.add(
            CompatibilityCheck(
                id = "firebase_auth",
                name = "Firebase Authentication",
                status = if (appInfo.usesFirebaseAuth) CompatibilityStatus.WARNING else CompatibilityStatus.OK,
                description = "Firebase Auth often restricts to SHA-1 + package name. Clone will need new Firebase project.",
                recommendation = "If auth fails, you may need to add clone's SHA-1 to Firebase console (not possible for third-party apps)."
            )
        )

        // Biometric
        checks.add(
            CompatibilityCheck(
                id = "biometric",
                name = "Biometric Authentication",
                status = if (appInfo.usesBiometric) CompatibilityStatus.WARNING else CompatibilityStatus.OK,
                description = "Biometric prompts may be tied to keystore which is package-specific.",
                recommendation = "Should work as new biometric enrollment per clone."
            )
        )

        // Signature verification
        checks.add(
            CompatibilityCheck(
                id = "sig_verify",
                name = "Signature Verification / Anti-Tamper",
                status = CompatibilityStatus.WARNING,
                description = "Many apps verify their own signature via PackageManager. Clone will have different signature.",
                recommendation = "Hook engine attempts to spoof PackageInfo.signatures – enable 'Hide root' and 'Disable Logcat'."
            )
        )

        // Hard-coded package
        checks.add(
            CompatibilityCheck(
                id = "hardcoded_pkg",
                name = "Hard-coded Package Name",
                status = if (appInfo.providers.isNotEmpty()) CompatibilityStatus.WARNING else CompatibilityStatus.OK,
                description = "Found ${appInfo.providers.size} ContentProviders. Authorities will be rewritten but hard-coded strings in native code may remain.",
                recommendation = "DexTransformer will replace provider authorities. Native code assumptions cannot be fully fixed."
            )
        )

        // OBB
        checks.add(
            CompatibilityCheck(
                id = "obb",
                name = "OBB / Game Expansion",
                status = if (appInfo.hasObb) CompatibilityStatus.WARNING else CompatibilityStatus.OK,
                description = if (appInfo.hasObb) "OBB detected at /Android/obb/${appInfo.packageName}. Will be copied if enabled." else "No OBB detected.",
                recommendation = if (appInfo.hasObb) "Enable 'Bundle OBB' or 'Copy OBB' in Game settings." else null
            )
        )

        // Split APK
        checks.add(
            CompatibilityCheck(
                id = "split",
                name = "Split APK / App Bundle",
                status = if (appInfo.isSplit) CompatibilityStatus.WARNING else CompatibilityStatus.OK,
                description = if (appInfo.isSplit) "App uses split APKs (base + config). Cloner will merge or include splits." else "Single APK.",
                recommendation = if (appInfo.isSplit) "Ensure all splits are included; some dynamic features may fail." else null
            )
        )

        // Root / custom ROM
        checks.add(
            CompatibilityCheck(
                id = "root",
                name = "Root / Custom ROM Compatibility",
                status = CompatibilityStatus.OK,
                description = "Clone-Master itself does not require root. Some hooks work better with root but degrade gracefully.",
                recommendation = "No action needed."
            )
        )

        // Determine overall
        val overall = when {
            checks.any { it.status == CompatibilityStatus.BLOCKER } -> CompatibilityStatus.WARNING // we never block completely, but warn
            checks.any { it.status == CompatibilityStatus.WARNING } -> CompatibilityStatus.WARNING
            else -> CompatibilityStatus.OK
        }

        val summary = buildString {
            append("Analyzed ${appInfo.packageName} v${appInfo.versionName} (${appInfo.versionCode}). ")
            append("Found ${checks.count { it.status == CompatibilityStatus.WARNING }} warnings, ${checks.count { it.status == CompatibilityStatus.BLOCKER }} blockers. ")
            if (overall == CompatibilityStatus.OK) append("Cloning should succeed.")
            else append("Cloning possible but some features may not work – see details.")
        }

        return CompatibilityReport(appInfo, checks, overall, summary)
    }
}
