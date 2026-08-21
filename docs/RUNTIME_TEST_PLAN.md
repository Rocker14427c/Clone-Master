# Runtime Test Plan – Clone-Master Real Device / Emulator Validation

**Objective:** Turn Clone-Master from statically verified codebase into project ready for genuine Android runtime validation. This document describes exact steps to test on real device or emulator.

## Required Android Version

- **Minimum:** Android 7.0 (API 24) – per `minSdk=24` in `app/build.gradle.kts`
- **Target:** Android 14 (API 34) – `targetSdk=34`, `compileSdk=34`
- **Recommended for testing:** Android 10 (API 29), Android 13 (API 33), Android 14 (API 34) – covers scoped storage, exported requirement, per-app locale, 64-bit only
- **Future:** Android 15 (API 35) 64-bit only and Android 16 (API 36) – test for 32-bit lib warnings

## Required SDK Components

Install via `sdkmanager` (from `setup_env.sh`):

```bash
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "emulator" "system-images;android-34;google_apis;arm64-v8a"
```

- `platform-tools` (adb)
- `platforms;android-34`
- `build-tools;34.0.0` (zipalign, apksigner)
- `emulator` + system image for AVD (arm64-v8a recommended for M1/M2, x86_64 for Intel)
- JDK 17 (OpenJDK 17)

## Build Command

```bash
cd /home/user/Clone-Master
bash setup_env.sh # installs JDK 17, aapt, apktool, uber-apk-signer, enables 6GB swap

# Ensure local.properties exists
cp local.properties.example local.properties
# Edit sdk.dir to your Android SDK path

# Clean build
./gradlew clean

# Debug APK
./gradlew assembleDebug --stacktrace

# Output: app/build/outputs/apk/debug/app-debug.apk
# Also: core/build/libs/core.jar (pure JVM lib)

# Verify APK
./gradlew :app:lintDebug
apksigner verify --verbose app/build/outputs/apk/debug/app-debug.apk
```

**Deterministic build:** No local machine paths committed, uses `gradle.properties` with caching and parallel, wrapper `gradle-8.6-bin.zip`

## APK Installation

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm list packages | grep clonemaster
adb logcat -s CloneMaster:V -v time > logcat_clonemaster.txt &
```

## Sample Test APK Requirements

Use small harmless test APKs – do NOT use production apps you don't own:

- **Test APK 1: Simple Hello World** – package `com.example.hello`, 1 activity, no native libs, no providers – for basic cloning pipeline smoke test
- **Test APK 2: With Provider** – package `com.example.provider`, has ContentProvider with authority `com.example.provider.fileprovider` – for provider authority collision testing
- **Test APK 3: With Native Lib** – package `com.example.native`, has `lib/arm64-v8a/libnative.so` – for native lib handling and 32/64-bit testing
- **Test APK 4: With Database** – package `com.example.db`, has Room database and SharedPreferences – for data bundling/restoration testing
- **Test APK 5: With Tracking SDK** – package `com.example.tracking`, includes AppsFlyer SDK (public test SDK) – for tracking blocker testing

Build sample APKs via Android Studio or use `adb shell pm path` to get APK path for installed test apps.

## Cloning Workflow – Basic

1. Open Clone-Master app on device
2. Grant permissions: `QUERY_ALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `REQUEST_INSTALL_PACKAGES`, `POST_NOTIFICATIONS`, `SYSTEM_ALERT_WINDOW`
3. In MainActivity, search for test APK (e.g., `com.example.hello`)
4. Tap app → AppAnalyzerActivity shows:
   - Package, VersionName/Code, Target/Min SDK, Activities/Services/Receivers/Providers, Permissions, Libraries, Large Heap, Biometric, Firebase Auth, Size
   - Compatibility report: OK/WARNING/BLOCKER with recommendations
5. Tap "Clone this app"
6. In CloneConfigActivity:
   - Set clonePackage `com.example.hello.clone1`, appName `Hello Clone 1`, versionName `1.0_clone`, custom icon, badge NUMBER 1
   - Enable functional parity features as needed
   - Search across all options: type "GPS", "proxy", "clipboard", "dark mode", "WebView", "notification" – verify search filters correctly
7. Tap "Save & Clone" – triggers CloneService foreground notification with progress
8. Monitor Logcat: `adb logcat -s CloneMaster:D`
9. Expected output: `Signed APK: .../clone-signed.apk, verified=true`
10. Final APK in `/sdcard/Android/data/com.clonemaster/files/clones/com.example.hello.clone1_1.0_clone.apk`

## First Launch – Hook Initialization

1. Install cloned APK: `adb install -r /sdcard/.../clone1.apk`
2. Launch clone from launcher or via `adb shell monkey -p com.example.hello.clone1 -c android.intent.category.LAUNCHER 1`
3. On first launch, HookFramework.init() should run in `attachBaseContext`:
   - Check logcat for "HookFramework.init started" and "completed" – no crashes
   - If data bundling enabled, FirstRunImportActivity should appear automatically with progress bar
4. Verify clone launches without crash, ANR, or immediate exit
5. Test second launch – should not re-trigger import if migration completed
6. Test after reboot – if persistent mode enabled, BootReceiver should show notification, not crash

## Data Migration – Bundle & Restore App Data

1. In CloneConfigActivity, enable "Bundle App Data", select categories: SharedPrefs, Databases, Files, External dirs
2. Set compression ZSTD, encryption AES256 with password "test123", embedInApk=true
3. Build clone – logs: "Analyzing app data...", "Packaging X files (Y MB)...", "Embedding data archive..."
4. Install clone
5. FirstRunImportActivity shows:
   - "Importing application data..." 0%
   - "Verifying archive checksum..." 5%
   - "Creating storage structure..." 10%
   - "Extracting archive..." 20%
   - "Restoring files... filename" 30-70%
   - "Restoring database..." 70%
   - "Restoring WebView data..." 75%
   - "Applying transformations..." 80%
   - "Validating..." 85%
   - "Finalizing..." 90%
   - "Data import complete" 100%
6. Verify restored files in `/data/data/com.example.hello.clone1/` via `adb shell run-as com.example.hello.clone1 ls -R`
7. Check import log for warnings about Keystore – should show "Some account/session data could not be restored..." if applicable, not claim guaranteed restoration
8. Test rollback: corrupt archive (truncate file), install, expect "Import failed" with Retry button, verify original data not deleted, retry allowed
9. Test incompatible archive: bundle from Android 14, restore on Android 10 device – should warn about version incompatibility but not crash

## Multiple Clones

1. Batch clone: select app, set count 3, template "{appName} {index}" – should create `com.example.hello.clone1`, `clone2`, `clone3` with unique authorities
2. Install all 3 – verify no `INSTALL_FAILED_CONFLICTING_PROVIDER` (authority collision fix)
3. Launch each – verify isolated storage (different data dirs)
4. Verify they coexist with original and each other

## Environment Spoofing Diagnostics

1. In CloneConfigActivity, enable Hide Root (AGGRESSIVE), Hide Emulator (FULL), Hide Developer Options, Hide USB/ADB, Hide Mock Location, Spoof Physical Device Profile = Pixel 8 Pro
2. Build and install clone
3. Inside clone, open EnvironmentDiagnosticsActivity via secret dialer code `*#*#7777#*#*` or menu
4. Check diagnostics screen shows:
   - Root detected/not detected – should be NOT DETECTED if hideRoot active and verified
   - Emulator detected/not detected – should be NOT DETECTED if hideEmulator active
   - QEMU indicators – should be hidden
   - Virtual-device, Debug/ADB, Mock-location, Build properties, Filesystem artifacts, Hardware, Sensor, Telephony, Network, Camera, Battery
   - Each check shows detected/mitigated/verifiedBypass, mitigationMethod, severity
   - Overall report: total checks, detected, mitigated, verified, unmitigated
5. Verify internal consistency: fingerprint `google/husky/husky:14/...`, manufacturer Google, model Pixel 8 Pro, hardware husky, board husky, CPU arm64-v8a, GPU Adreno 750, sensors Bosch/STM/AKM (not Goldfish), camera count 3, battery 5050mAh – all from same profile
6. Run `DetectionDiagnostics.generateOverallReport()` before and after mitigation – verify no false "verified" claims

## Root Detection Testing

1. Install RootBeer sample app or similar root detector inside clone (or use RootHideManager.scanForRootIndicators() directly)
2. With hideRoot OFF – should detect SU binary, root apps, test-keys, etc.
3. With hideRoot ON AGGRESSIVE – should show NOT DETECTED and MITIGATED & VERIFIED for mitigatable checks, UNMITIGATED for writable system (requires system-level)
4. Test on rooted device vs non-rooted – diagnostics should differ, but clone should not crash

## Emulator Detection Testing

1. Run clone on emulator (AVD) with hideEmulator OFF – should detect emulator files (`/dev/qemu_pipe`, `/dev/socket/qemud`), QEMU props (`ro.kernel.qemu=1`), build fingerprint generic/sdk, hardware goldfish/ranchu, etc.
2. With hideEmulator ON FULL and profile Pixel 8 Pro – should show NOT DETECTED, MITIGATED & VERIFIED
3. Verify consistency: no Samsung fingerprint with Pixel hardware, no Goldfish sensors
4. Test on physical device – should already be NOT DETECTED for most, but still test mitigation active

## Networking/Proxy Testing

1. In clone config, set SOCKS proxy `127.0.0.1:1080` (invalid) – ProxyManager should validate format and not crash, log warning
2. Set valid proxy (e.g., local microsocks) – startProxy should check binary exists, set executable, log "Would start microsocks..." – IMPLEMENTED BUT NOT RUNTIME VERIFIED without binary
3. Test proxy speed test: add proxy to HttpProxyListManager, tap test – should measure latency via socket connect with 3s timeout, return latencyMs, not fake 0
4. Test notification toggle: enable notificationNetworkingToggle, show notification, tap Disable – should save state `networking_enabled=false`, hooks should block networking via ConnectivityManager hook
5. Test Tunnel Manager: add 2 tunnels, set active, test speed, auto-switch on failure – verify no process leak (destroy + destroyForcibly)
6. Test DoH: set DNS over HTTPS `https://dns.google/dns-query` – should validate https:// prefix
7. Test WebRTC leak protection – enable, check WebView injects JS `RTCPeerConnection=undefined`

## WebView Testing

1. Clone app that uses WebView (e.g., simple browser test APK)
2. Enable WebView UA spoofing with custom UA
3. Enable script injection with inject mode DOCUMENT_START vs DOCUMENT_END – verify script runs at correct time via WebViewScriptManager
4. Enable navigation override – block specific URL pattern
5. Open WebView, inspect via WebViewToolkit – view source, page info
6. Verify WebView lifecycle – no crash on rotation

## Backup/Restore

1. Export clone + data: select clone, tap Export – should create `*.cmb_backup` zip containing `clone.apk`, `data/archive.zip`, `manifest.json`, `checksums.sha256`, `clone_config.json`
2. Verify integrity: `BackupManager.verifyBackupIntegrity()` checks manifest + checksums exist
3. Test encrypted backup: enable encryption with password, export → `.enc` file, import with wrong password → should fail, with correct password → success
4. Test data-only backup: backupDataOnly() – analyzes clonePackage, creates archive from accessible dirs
5. Test settings backup/restore: backupSettings() → JSON, restoreSettings() → CloneConfig
6. Test migration: old clone v1 → new clone v2 via `migrateData()` – copies files with package transformation, warns if original packages differ
7. Test corrupted backup: truncate backup file, import → should detect checksum mismatch and fail gracefully with message, not crash
8. Test versioned format: backup version 2 should be readable, older version should warn

## Failure/Rollback Tests

1. **Corrupted archive:** Truncate data archive, restore – should detect checksum mismatch, show warning, allow retry, rollback to previous data via rollbackBackupDir
2. **Incompatible DB schema:** Create clone with old DB, update original app with new schema, bundle old data, restore to new clone – should detect schema incompatibility via SQLite header check and skip file with warning, not corrupt
3. **Path traversal attack:** Craft malicious zip with `../../etc/passwd` entry – DataRestoreEngine should detect via canonical path check and throw SecurityException, not write outside dataDir
4. **ZIP bomb:** Craft zip with 1GB file – should be rejected via MAX_FILE_SIZE 100MB and MAX_TOTAL_SIZE 500MB limits
5. **Original data protection:** Ensure restore only writes to `dataDir` containing `packageName` – try to restore to original package path – should throw SecurityException
6. **Process death during restore:** Kill app during restore (simulate via `adb shell am kill`) – on next launch, should allow retry via `allowRetry()` clearing migration_completed flag
7. **Multiple clones authority collision:** Batch clone 3 times same app – second and third should have unique authorities via collision resolution, install should succeed

## Logcat Collection

```bash
adb logcat -c
adb logcat -s CloneMaster:V -v time > logcat.txt &
# Perform tests
adb logcat -d -s CloneMaster:V > logcat_after_test.txt
# Check for crashes, ANRs, excessive allocations, sensitive data
grep -i "exception\|crash\|anr\|oom\|password\|token" logcat.txt
```

**Expected:** No crashes, ANRs, OOM, sensitive data in logcat. All hooks log with `Log.d/e/w` safely.

## Recommended First 5 Test Scenarios for Real-Device Validation

1. **Smoke Test – Hello World Clone:**
   - Build and install Clone-Master debug APK
   - Clone `com.example.hello` (simple 1-activity app) with default config (no data bundling, no environment spoofing)
   - Install clone, launch, verify no crash on first and second launch, check logcat for HookFramework.init completed

2. **Provider Authority Collision – Multiple Clones:**
   - Clone `com.example.provider` (has ContentProvider) 3 times via batch with template "{appName} {index}"
   - Install all 3 + original, verify no INSTALL_FAILED_CONFLICTING_PROVIDER, launch each, verify isolated storage via `run-as`

3. **Environment Spoofing Diagnostics – Pixel 8 Pro Profile:**
   - Clone `com.example.hello` with Hide Root AGGRESSIVE, Hide Emulator FULL, Spoof Physical Profile Pixel 8 Pro
   - Install, open EnvironmentDiagnosticsActivity, verify overall report shows 0 detected or all mitigated & verified, check consistency (fingerprint, manufacturer, hardware, sensors, GPU all from same profile)

4. **Data Bundling – First-Run Import with Rollback:**
   - Clone `com.example.db` (has SharedPrefs + Room DB) with Bundle App Data enabled, categories SharedPrefs + Databases + Files, compression ZIP, embedInApk=true
   - Install clone, verify FirstRunImportActivity shows progress bar with stages (Importing... Restoring files... Restoring database... Finalizing... Complete), verify restored files via `run-as`, test corrupted archive rollback and retry

5. **Networking – Proxy Validation and Notification Toggle:**
   - Clone `com.example.hello` with SOCKS proxy invalid format `badformat` – verify validation prevents crash and logs warning
   - Clone with valid proxy `127.0.0.1:1080` and notification toggle enabled – verify notification appears, tap Disable → networking blocked via hooks, tap Enable → networking restored, check no process leak via `adb shell ps | grep microsocks`

## Test Execution Checklist

- [ ] Required SDK components installed
- [ ] `./gradlew assembleDebug` succeeds (no compilation errors)
- [ ] APK installs via `adb install`
- [ ] Sample test APKs built and installed
- [ ] Cloning workflow completes with signed APK verified
- [ ] First launch no crash, HookFramework.init logged
- [ ] Data migration progress bar and log shown, rollback tested
- [ ] Multiple clones coexist, no authority collision
- [ ] Environment diagnostics shows coherent physical profile, no false verified claims
- [ ] Root/emulator detection tested with RootBeer sample
- [ ] Networking proxy validation and notification toggle tested, no leaks
- [ ] WebView UA spoof and script inject mode tested
- [ ] Backup/restore integrity verification, encrypted backup, corrupted backup handling tested
- [ ] Failure/rollback tests (path traversal, ZIP bomb, original data protection) passed
- [ ] Logcat collected, no crashes/ANRs/OOM/sensitive data

## Reporting

After runtime tests, update `docs/VERIFICATION_MATRIX.md` with actual results using:

- VERIFIED – tested on real device/emulator and passed
- PARTIALLY VERIFIED – tested but some sub-features failed or degraded
- IMPLEMENTED BUT NOT RUNTIME VERIFIED – implemented but not tested (keep for features not covered in this run)
- FAILED – tested and failed
- BLOCKED BY ANDROID LIMITATION – cannot work due to Android restriction (e.g., IMEI on Android 10+)

Do NOT fabricate results – only mark VERIFIED if actual device/emulator test passed.
