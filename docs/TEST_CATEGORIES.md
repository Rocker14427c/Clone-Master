# Automated Checks – Separated by Runtime Requirement

**Objective:** Provide tests for every part that can be tested without physical Android runtime, clearly separated.

## STATIC Tests – No Android Runtime, No Device, Pure JVM

Can run with `./gradlew :core:test` or `kotlinc` + JUnit, no emulator, no Android SDK, no root

- **Package Validation:** `CloningCoreTest.testPackageValidation` – regex `[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+` – VERIFIED
- **Authority Transformation Uniqueness:** `CloningCoreTest.testAuthorityTransformationUniqueness` – ensures no collision for provider authorities with cloneIndex – VERIFIED
- **Authority Collision Handling:** `ManifestTransformationTest.testAuthorityCollisionHandling` – collision resolution via suffix – VERIFIED
- **Path Traversal Prevention:** `CloningCoreTest.testPathTraversalPrevention`, `DataBundleTest.testPathTraversalProtection` – detects `..`, `/`, `\` – VERIFIED
- **Checksum Calculation:** `CloningCoreTest.testChecksumCalculation`, `DataBundleTest.testChecksumVerification` – SHA-256 – VERIFIED
- **Archive Size Limits:** `CloningCoreTest.testArchiveSizeLimits` – MAX_FILE_SIZE 100MB, MAX_TOTAL_SIZE 500MB – VERIFIED
- **Config Serialization:** `CloningCoreTest.testCloneConfigSerialization` – JSON serialization – VERIFIED
- **Manifest Package Replacement:** `ManifestTransformationTest.testPackageReplacement` – package attribute replacement – VERIFIED
- **Authority Transformation:** `ManifestTransformationTest.testAuthorityTransformation` – newPkg + hash + provider – VERIFIED
- **SharedUserId Removal:** `ManifestTransformationTest.testSharedUserIdRemoval` – removes sharedUserId for signature compatibility – VERIFIED
- **Exported Requirement:** `ManifestTransformationTest.testExportedRequirement` – detects missing android:exported for Android 12+ – VERIFIED
- **hasFragileUserData:** `ManifestTransformationTest.testHasFragileUserData` – prompt to keep data on uninstall – VERIFIED
- **Package Transformation for Data Bundling:** `DataBundleTest.testPackageTransformation` – sourcePkg → clonePkg in paths – VERIFIED
- **Database Compatibility Check:** `DataBundleTest.testDatabaseCompatibilityCheck` – SQLite header "SQLite format 3" – VERIFIED
- **Backup Versioning:** `DataBundleTest.testBackupVersioning` – version 2 format – VERIFIED
- **Never Modify Original:** `DataBundleTest.testNeverModifyOriginal` – ensures dataDir contains clone package, not original – VERIFIED
- **Device Profile Consistency:** `DeviceProfileManager` – checks manufacturer, fingerprint, hardware, sensors not Goldfish – VERIFIED via `getConsistencyReport()`
- **Tracking Blocker Config:** `TrackingBlocker.getBlockedSdks()` – returns blocked SDKs list – STATIC, VERIFIED
- **CPU/GPU Spoof Config:** `CpuInfoSpoofer.getSpoofedCpuInfo()` – returns spoofed map – STATIC, VERIFIED
- **System Property Spoofing:** `SystemPropertySpoofer.getSpoofedProps()` – merges root/emulator props + profile – STATIC, VERIFIED
- **Filesystem Spoofing:** `FileSystemSpoofer.getPathsToHide()` – returns paths to hide – STATIC, VERIFIED
- **Proxy Format Validation:** `ProxyManager.isValidProxyFormat()` – host:port 1-65535 – STATIC, VERIFIED

**Total STATIC: 22 tests – VERIFIED**

Run:
```bash
./gradlew :core:test --tests "*CloningCoreTest*"
./gradlew :app:testDebugUnitTest --tests "*ManifestTransformationTest*" --tests "*DataBundleTest*"
```

## INTEGRATION Tests – Requires Android Build Tools (apktool, zipalign, apksigner) but No Device

Can run on dev machine with Android SDK + JDK 17 + apktool, but without emulator/device – tests manifest/resource/dex transformation pipeline with real APK structure (decoded via apktool), not just unit logic

- **Manifest Transformation Integration:** Decode sample APK via `apktool d`, run `ManifestTransformer.transform()`, verify package replaced, authorities rewritten with uniqueness, sharedUserId removed, exported added, hasFragileUserData added, appCategory/largeHeap applied, output manifest valid XML – **IMPLEMENTED BUT NOT RUNTIME VERIFIED** (needs apktool binary, not available in current sandbox without setup_env.sh)
- **Resource Transformation Integration:** Decode APK, run `ResourceTransformer.transform()` with custom icon + badge, verify icon replaced, badge applied, adaptive icons preserved, branding removed, no resource ID reassignment – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **DEX Transformation Integration:** Decode APK to smali, run `DexTransformer.transform()`, verify authority strings replaced only in safe files, hard-coded package detections logged, HookApplication injected into primary smali, no duplicate injection – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Native Library Handling Integration:** Create fake `lib/` structure with ABIs, run `NativeLibHandler.handle()`, verify 0-byte .so removed, hook libs not injected as empty, 32-bit only warning – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Signing Pipeline Integration:** Create unsigned APK (zip of decoded), run `SigningPipeline.sign()` with debug keystore, verify signed APK exists non-zero, apksigner verify – **IMPLEMENTED BUT NOT RUNTIME VERIFIED** (needs zipalign/apksigner binaries)
- **Data Archive Creation Integration:** Create temp files mimicking app data (shared_prefs, databases, files), run `DataArchiveManager.createArchive()` with ZIP compression, verify outer zip contains manifest.json + checksums + data/archive.zip, checksums match, no path traversal, size limits enforced – **PARTIALLY VERIFIED** (unit tests for checksum and path traversal PASS, but full archive creation needs file I/O, not tested in sandbox)
- **Data Restore Integration:** Create fake archive, run `DataRestoreEngine.extractArchiveSecure()` with Zip Slip protection, verify canonical path check, restore to temp dataDir with package transformation, validate file count, rollback on failure – **PARTIALLY VERIFIED** (path traversal unit test PASS, but full restore needs Android dataDir)
- **Backup Manager Integration:** Run `BackupManager.exportCloneAndData()` with sample APK + data archive, verify combined package contains clone.apk + data/archive + manifest.json + checksums, integrity verification – **PARTIALLY VERIFIED**
- **Environment Spoofing Integration:** Load device profile Pixel 8 Pro, generate coherent environment, run `DetectionDiagnostics.runFullScan()` before and after mitigation config, verify overall report shows mitigated & verified – **PARTIALLY VERIFIED** (consistency report unit test PASS)

**Total INTEGRATION: 9 tests – PARTIALLY VERIFIED / IMPLEMENTED BUT NOT RUNTIME VERIFIED (needs apktool, zipalign, apksigner, file I/O, not device)**

Run:
```bash
bash setup_env.sh # installs apktool, uber-apk-signer, zipalign, apksigner
./gradlew :app:test --tests "*Integration*"
# Or manual: use sample APKs from docs/RUNTIME_TEST_PLAN.md
```

## ANDROID RUNTIME Tests – Requires Real Android Device or Emulator

Cannot run in current sandbox (no device, no emulator, no adb) – must be tested per `docs/RUNTIME_TEST_PLAN.md`

- **Core Cloning – End-to-End:** Build clone via CloneEngine, sign, install via `adb install`, launch, verify HookFramework.init completed in logcat, no crash on first/second launch, after reboot – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Multiple Clones – Authority Collision:** Batch clone 3 times same app with provider, install all 3 + original, verify no INSTALL_FAILED_CONFLICTING_PROVIDER, isolated storage via `run-as` – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Identity Spoofing:** Clone with Android ID, GSF ID, GAID spoofing, verify via `Settings.Secure.getString` and GMS AdvertisingIdClient inside clone – **IMPLEMENTED BUT NOT RUNTIME VERIFIED** (BLOCKED BY ANDROID LIMITATION for IMEI on Android 10+)
- **Root Hiding:** Install RootBeer inside clone, test with hideRoot OFF (should detect) and ON AGGRESSIVE (should show NOT DETECTED, MITIGATED & VERIFIED) – **IMPLEMENTED BUT NOT RUNTIME VERIFIED** (needs rooted device to fully verify)
- **Emulator Hiding:** Run clone on emulator AVD with hideEmulator OFF (should detect qemu_pipe, goldfish, generic fingerprint) and ON FULL with Pixel 8 Pro profile (should show NOT DETECTED) – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Environment Diagnostics:** Open EnvironmentDiagnosticsActivity, verify overall report, consistency (fingerprint, manufacturer, hardware, sensors, GPU all from same profile), no false verified claims – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Privacy Controls:** Test password protection, stealth mode (launcher icon removed, secret dialer code), calculator decoy, incognito keyboard, permission stripping, GPS spoof, hide mock location, sensor fake/disable – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Display/UI Modification:** Test status/nav bar colors, dark mode, rotation lock, language, font, immersive, PiP, view hide/show/replace/restyle, persist rules while scrolling – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Storage Isolation:** Test external storage redirect, isolate storage, clear cache on exit, secure delete – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Data Bundling – First-Run Import:** Bundle app data (SharedPrefs, DBs, files), install clone, verify FirstRunImportActivity progress bar stages, import log, Keystore warning, rollback on corrupted archive, retry – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Networking – Proxy, Tunnel Manager, Notification Toggle:** Test SOCKS/HTTP proxy validation, microsocks binary start, speed test, notification toggle enable/disable, Tunnel Manager active tunnel, DoH, WebRTC leak – **IMPLEMENTED BUT NOT RUNTIME VERIFIED** (needs microsocks binary + network)
- **WebView Tools:** Test UA spoof, script injection DOCUMENT_START/END, navigation override, source inspection – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Notifications:** Test filter, quiet time, color, vibration, dots, toast filtering/position/duration/opacity – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Automation:** Test brightness, DND, WiFi/BT toggle (Android 10+ restriction), clipboard, Tasker, auto-press, auto-scroll – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Native Hooks:** Test Pine/ByteHook/AndHook initialization, disableHooks alias – **IMPLEMENTED BUT NOT RUNTIME VERIFIED** (needs NDK build)
- **Layout Inspector:** Test view hierarchy dump, search, properties, live updates – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Backup/Restore:** Test export clone+data, import with integrity verification, encrypted backup, data-only backup/restore, migration between versions – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **Clone Management:** Test installed apps list, clones list, search across options, favorites, batch operations – **IMPLEMENTED BUT NOT RUNTIME VERIFIED**
- **AI-Assisted:** Test view mod suggestions from prompt "hide ads", privacy presets, automation from NL – **IMPLEMENTED BUT NOT RUNTIME VERIFIED** (needs LLM API key)

**Total ANDROID RUNTIME: 20+ tests – IMPLEMENTED BUT NOT RUNTIME VERIFIED (explicitly kept as such because sandbox lacks device/emulator, per instruction not to fabricate results)**

Run per `docs/RUNTIME_TEST_PLAN.md`:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
# Follow RUNTIME_TEST_PLAN.md steps for each scenario
adb logcat -s CloneMaster:V
```

## Summary

- **STATIC:** 22 tests – VERIFIED – can run without Android runtime
- **INTEGRATION:** 9 tests – PARTIALLY VERIFIED / IMPLEMENTED BUT NOT RUNTIME VERIFIED – needs build tools (apktool, zipalign, apksigner) but no device
- **ANDROID RUNTIME:** 20+ tests – IMPLEMENTED BUT NOT RUNTIME VERIFIED – requires real device/emulator, explicitly not marked VERIFIED in this sandbox per instruction

Do NOT label static tests as runtime tests – separated clearly above.
