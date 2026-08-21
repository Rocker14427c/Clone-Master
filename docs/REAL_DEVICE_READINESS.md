# Real-Device Readiness Report – 2026-08-21

**Objective:** Turn Clone-Master from statically verified codebase into project ready for genuine Android runtime validation.

## Remaining Compile/Build Blockers

**None critical for debug build – project is buildable outside sandbox after QA fixes:**

- **Gradle Wrapper:** Added – `gradle/wrapper/gradle-wrapper.properties` (gradle-8.6-bin), `gradlew`, `gradlew.bat`, `gradle-wrapper.jar` 47K – **FIXED**
- **Wrapper Properties:** Added with `distributionUrl=https://services.gradle.org/distributions/gradle-8.6-bin.zip`, `networkTimeout=10000`, `validateDistributionUrl=true` – **FIXED**
- **Gradle Configuration:** `build.gradle.kts` with deterministic config, no local paths, `printEnvironment` task, `gradle.properties` with parallel, caching, configuration-cache – **FIXED**
- **AGP Configuration:** `com.android.application` 8.2.2, `compileSdk=34`, `targetSdk=34`, `minSdk=24`, `namespace="com.clonemaster"` – **FIXED**
- **Repositories:** `google()`, `mavenCentral()`, `jitpack.io` in `settings.gradle.kts` with `FAIL_ON_PROJECT_REPOS` – **FIXED**
- **Dependencies:** All required dependencies listed in `app/build.gradle.kts` with versions, including new `lifecycle-runtime-ktx:2.7.0` for lifecycleScope fix – **FIXED**, no unnecessary dependencies added
- **Deterministic Build:** No local machine paths committed, `local.properties` in `.gitignore`, `local.properties.example` provided – **FIXED**

**Minor build warnings (not blockers):**
- `apk-parser:2.6.10` may have vulnerabilities – consider updating to 2.6.11 or using `com.jaredrummler:apkparser` only
- `jaredrummler:apkparser:3.0.0-alpha2` is alpha – stability risk, but functional
- `multidex-config.pro` not present – hooks may be removed if `isMinifyEnabled=true` (currently false, so safe)
- `compileSdk=34` with `suppressUnsupportedCompileSdk=34` – OK, but should update to 35 when AGP supports

**Build command that should work on dev machine:**
```bash
cp local.properties.example local.properties # edit sdk.dir
./gradlew clean assembleDebug --stacktrace
```

## Remaining Code-Quality Issues

**Fixed in this revision:**
- MainActivity CoroutineScope leak → lifecycleScope – **FIXED**
- FirstRunImportActivity non-atomic flag → AtomicBoolean + lifecycleScope + onSaveInstanceState – **FIXED**
- DataBundleAnalyzer repeated walkTopDown → single walk caching – **FIXED**
- Persistent mode missing BootReceiver → BootReceiver + PersistentCloneService with Android 10+ background start handling via notification – **FIXED**

**Remaining (minor, documented, not crashing):**
- `DataBundleAnalyzer` still walks external dirs separately (limited to 1000 files) – could be further optimized with single walk – **LOW PRIORITY**
- `CoroutineScope` in some other files (e.g., `CloneService` uses `CoroutineScope(Dispatchers.IO)` not lifecycle-aware) – service lifecycle, not activity, so less critical, but could use `Service.lifecycleScope` or WorkManager – **LOW PRIORITY**
- `randomMac()` duplicated in `CloneConfig.kt` and `IdentityManager.kt` – intentional for modularity (core vs app), but could be consolidated into `utils/RandomUtil.kt` – **LOW PRIORITY**
- `ViewModificationEngine` scans on every layout change without throttling – could cause CPU use on scroll – **LOW PRIORITY**, add debounce
- `SupportChatOverlay` uses `TYPE_PHONE` fallback for pre-O – deprecated, should use `TYPE_APPLICATION_OVERLAY` only – **LOW PRIORITY**

**No major code-quality blockers remaining.**

## Remaining Unverified Runtime Features

All Android runtime features are **IMPLEMENTED BUT NOT RUNTIME VERIFIED** because sandbox lacks device/emulator – explicitly kept as such per instruction not to fabricate:

- Core cloning end-to-end (apktool decode/build, signing, installation, first launch)
- Multiple clones authority collision (batch cloning 3x)
- Identity spoofing (Android ID, GSF, GAID, IMEI – BLOCKED BY ANDROID LIMITATION for IMEI on 10+)
- Root hiding actual bypass (needs rooted device)
- Emulator hiding actual bypass (needs emulator AVD)
- Environment diagnostics before/after mitigation verification
- Privacy controls (password, stealth, calculator decoy, incognito keyboard, permission stripping, GPS spoof, etc.)
- Display/UI modification (status/nav colors, dark mode, rotation, language, PiP, view hide/show/replace)
- Storage isolation (redirect, secure delete, hasFragileUserData)
- Data bundling first-run import with progress bar, rollback, retry, Keystore warning
- Networking proxy validation, Tunnel Manager actual tunnel start, notification toggle, DoH, WebRTC leak
- WebView UA spoof, script inject mode DOCUMENT_START/END, navigation override
- Notifications filter, dots, toasts
- Game OBB, key mapper, FPS monitor
- TV/Wear launcher, banner, joystick pointer
- Automation brightness/DND/WiFi/BT, Tasker, auto-press, auto-scroll
- Native hooks Pine/ByteHook/AndHook (needs NDK build)
- Layout Inspector live hierarchy
- Backup/restore encrypted, integrity verification, migration
- Clone management UI search, favorites, batch
- AI-assisted controls with LLM

**Total: 35+ runtime features – IMPLEMENTED BUT NOT RUNTIME VERIFIED – ready for testing per RUNTIME_TEST_PLAN.md**

## Exact Steps Required to Perform First Real-Device Test

**Prerequisites:**
- Developer machine with JDK 17, Android SDK 34, adb, emulator or physical device Android 10+
- Clone-Master repo cloned

**Steps:**

1. **Setup Environment:**
   ```bash
   cd /home/user/Clone-Master
   bash setup_env.sh # installs JDK 17, aapt, apktool, uber-apk-signer, enables 6GB swap
   cp local.properties.example local.properties
   # Edit local.properties: sdk.dir=/path/to/android-sdk
   ```

2. **Build:**
   ```bash
   ./gradlew clean assembleDebug --stacktrace
   # Expected: app/build/outputs/apk/debug/app-debug.apk (no compilation errors)
   ```

3. **Prepare Device:**
   ```bash
   adb devices # ensure device/emulator connected
   adb shell getprop ro.build.version.sdk # check API level
   ```

4. **Install Clone-Master:**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   adb shell pm list packages | grep clonemaster
   ```

5. **Prepare Sample Test APK:**
   - Build simple Hello World APK with package `com.example.hello` (1 activity, no native libs) via Android Studio
   - Install: `adb install -r hello.apk`
   - Or use existing installed app that you own and have rights to clone

6. **Grant Permissions:**
   - Open Clone-Master app, grant: QUERY_ALL_PACKAGES, MANAGE_EXTERNAL_STORAGE, REQUEST_INSTALL_PACKAGES, POST_NOTIFICATIONS, SYSTEM_ALERT_WINDOW

7. **First Clone – Smoke Test:**
   - In MainActivity, search for `com.example.hello`
   - Tap → AppAnalyzerActivity shows package, version, SDKs, components, compatibility report
   - Tap "Clone this app" → set clonePackage `com.example.hello.clone1`, appName `Hello Clone 1`
   - Tap "Save & Clone" → CloneService notification shows progress, check logcat: `adb logcat -s CloneMaster:V`
   - Expected: `Signed APK: .../clone-signed.apk, verified=true`
   - Final APK in `/sdcard/Android/data/com.clonemaster/files/clones/`

8. **Install and Launch Clone:**
   ```bash
   adb install -r /sdcard/Android/data/com.clonemaster/files/clones/com.example.hello.clone1_1.0.apk
   adb shell monkey -p com.example.hello.clone1 -c android.intent.category.LAUNCHER 1
   adb logcat -s CloneMaster:V | grep "HookFramework.init"
   ```
   - Verify no crash, HookFramework.init completed logged

9. **Verify Isolation:**
   ```bash
   adb shell run-as com.example.hello ls /data/data/com.example.hello/files
   adb shell run-as com.example.hello.clone1 ls /data/data/com.example.hello.clone1/files
   # Should be different dirs, isolated
   ```

10. **Collect Logs:**
    ```bash
    adb logcat -d -s CloneMaster:V > logcat_first_test.txt
    ```

**If build fails:** Check `docs/DAILY_QA_REPORT_2026-08-21.md` Build section for warnings, ensure JDK 17, SDK 34, no local paths in local.properties.

## Recommended First 5 Test Scenarios

**Priority order: Build failures > Crashes > Core cloning > Data corruption > Hook failures > Compatibility**

1. **Smoke Test – Hello World Clone (Build + Core Cloning + Runtime Stability):**
   - **Objective:** Verify project builds, clones, installs, launches without crash
   - **Steps:** As in Exact Steps above, clone `com.example.hello` with default config (no data bundling, no environment spoofing)
   - **Expected:** `./gradlew assembleDebug` succeeds, APK installs, clone launches, logcat shows HookFramework.init completed, no crash on first/second launch
   - **Verification:** VERIFIED if all pass, else FAILED – fix build/crash first before other tests
   - **Status after fix:** PARTIALLY VERIFIED (static) → should become VERIFIED after real-device test

2. **Provider Authority Collision – Multiple Clones (Core Cloning + Compatibility):**
   - **Objective:** Verify authority collision fix prevents INSTALL_FAILED_CONFLICTING_PROVIDER
   - **Steps:** Clone `com.example.provider` (has ContentProvider authority `com.example.provider.fileprovider`) 3 times via batch with template "{appName} {index}" → creates clone1, clone2, clone3 with unique authorities via hash + cloneIndex + random suffix
   - **Expected:** All 3 APKs install alongside original, no collision, launch each, verify isolated storage via `run-as`
   - **Verification:** VERIFIED if all 3 install and launch, else FAILED – indicates authority transformation bug
   - **Status after fix:** PARTIALLY VERIFIED (unit tests for collision handling PASS) → should become VERIFIED

3. **Environment Spoofing Diagnostics – Pixel 8 Pro Profile (Environment Spoofing + Consistency):**
   - **Objective:** Verify coherent physical device profile and detection diagnostics before/after mitigation
   - **Steps:** Clone `com.example.hello` with Hide Root AGGRESSIVE, Hide Emulator FULL, Spoof Physical Profile Pixel 8 Pro (fingerprint google/husky/husky:14/..., manufacturer Google, model Pixel 8 Pro, hardware husky, GPU Adreno 750, sensors Bosch/STM/AKM)
   - **Expected:** Install clone, open EnvironmentDiagnosticsActivity via secret dialer `*#*#7777#*#*`, overall report shows 0 detected or all mitigated & verified, consistency check shows ✅ Consistent, no Samsung fingerprint with Pixel hardware
   - **Verification:** VERIFIED if report shows coherent profile and no false verified claims, else PARTIALLY VERIFIED
   - **Status after fix:** PARTIALLY VERIFIED (consistency report unit test PASS) → should become VERIFIED

4. **Data Bundling – First-Run Import with Rollback (Data Bundling + Safety):**
   - **Objective:** Verify self-contained clone with exportable data, first-run import UI, safety (never modify original, rollback, retry, Keystore warning)
   - **Steps:** Clone `com.example.db` (has SharedPrefs + Room DB) with Bundle App Data enabled, categories SharedPrefs + Databases + Files, compression ZIP, embedInApk=true, encryption NONE
   - **Expected:** Build logs show analyzing, packaging, embedding; install clone; FirstRunImportActivity shows progress bar with stages (Importing... Restoring files... Restoring database... Restoring WebView... Finalizing... Complete) with progress 0-100%, import log, restored files count, warning about Keystore if applicable; verify restored files via `run-as`; test corrupted archive (truncate) → should show Import failed with Retry button, rollback to previous data, allow retry; test path traversal malicious zip → should throw SecurityException and not write outside dataDir
   - **Verification:** VERIFIED if import completes with progress bar and rollback works, else FAILED – indicates data corruption risk
   - **Status after fix:** PARTIALLY VERIFIED (path traversal and checksum unit tests PASS, Zip Slip fixed, rollback backup added) → should become VERIFIED

5. **Networking – Proxy Validation and Notification Toggle (Networking + Resource Leaks):**
   - **Objective:** Verify proxy format validation prevents crash, process lifecycle no leaks, notification toggle works
   - **Steps:** Clone `com.example.hello` with SOCKS proxy invalid format `badformat` → should validate and log warning, not crash; clone with valid proxy `127.0.0.1:1080` and notification toggle enabled → notification appears, tap Disable → networking blocked, tap Enable → restored; check no process leak via `adb shell ps | grep microsocks`; test HttpProxyListManager speed test → should measure real latency via socket connect with 3s timeout, not fake 0; test Tunnel Manager add 2 tunnels, set active, test speed
   - **Expected:** No crash on invalid format, no stuck tunnels, no process leak, speed test returns actual latency
   - **Verification:** VERIFIED if validation prevents crash and no leaks, else FAILED – indicates stability bug
   - **Status after fix:** PARTIALLY VERIFIED (proxy format validation unit test PASS, process lifecycle fixed with destroyForcibly) → should become VERIFIED

## Conclusion

Clone-Master is now **ready for genuine Android runtime validation** – buildable outside sandbox via Gradle wrapper, with fixed lifecycle leaks, atomic flags, single walk optimization, BootReceiver for persistent mode, and comprehensive test plan.

**Do not fabricate results:** All runtime features remain marked as IMPLEMENTED BUT NOT RUNTIME VERIFIED until tested per RUNTIME_TEST_PLAN.md on real device/emulator.

**Next:** Execute 5 recommended scenarios on dev machine with Android SDK, update VERIFICATION_MATRIX.md with actual VERIFIED/PARTIALLY VERIFIED/FAILED results.
