# Clone-Master Architecture

## Goal
Produce genuinely separate APK with isolated identity – not wrapper.

## Pipeline (core module)

### 1. APK Parsing
- Unzip, detect split APKs (base + config + density)
- Parse binary AndroidManifest.xml via apktool / axml parser
- Extract resources.arsc, dex list, lib/, assets/, OBB references
- Detect: multi-dex, native libs, kotlin, jetpack, Firebase, Play Services, Billing, SafetyNet

### 2. Compatibility Analysis (before cloning)
Detects likely failures:
- certificate pinning (network_security_config, TrustManager checks, string "X509TrustManager", "CertificatePinner")
- Google Play Services cert dependency (com.google.android.gms.auth, com.google.android.gms.common)
- Google login / Games / Drive backup
- In-app billing (com.android.billingclient, com.android.vending.BILLING)
- reCAPTCHA, anti-tamper (SafetyNet, Play Integrity API, root detection libs)
- package-name integrity (getPackageName() comparisons, hard-coded authority strings)
- signature verification (PackageManager.GET_SIGNATURES, PackageInfo signatures)
- native-code assumptions (System.loadLibrary with absolute paths)
- split APK dependencies
- OBB required

Generates `CompatibilityReport` with levels: OK, WARNING, BLOCKER and user-facing explanation.

### 3. Manifest Transformation
- New package ID: `original.pkg + ".clone" + index` or custom user string, validated as Java package
- `android:sharedUserId` removed (incompatible with new signature)
- Rewrite `providers`: `android:authorities` => `${newPackage}.provider.<originalAuthorityHash>`
- Update all `<activity>`, `<service>`, `<receiver>`, `<provider>` names if relative
- Update `<meta-data>`, `<intent-filter>` data authorities
- Remove `android:allowBackup` override if user wants prevent backup
- Inject `<uses-feature>` for TV, etc per clone settings
- Preserve `android:extractNativeLibs`, `android:largeHeap`, etc

### 4. Resource Transformation
- Change `app_name` string resource
- Replace icons: user custom icon + badge generation (overlay number/badge via Canvas)
- Re-write resource references if package name appears in strings (optional heuristic)
- Keep resource ID stability – do not reassign IDs, only replace files

### 5. DEX Transformation
This is critical for hard-coded references.
- Scan all dex files for const-string containing original package / authority
- Replace with new package / authority where safe (heuristic: only if string equals or prefix)
- Inject hook framework: merge `libAppCloner` classes.dex into clone's dex (secondary dex)
- Rewrite `Application` class: wrap original Application with `com.clonemaster.hooks.HookApplication` which calls original after installing hooks
- If original has no custom Application, use HookApplication directly
- Multidex support: ensure hook dex is in primary or add to multidex list

### 6. Native Library Handling
- Preserve ABIs: arm64-v8a, armeabi-v7a, x86_64, x86
- Inject `libappcloner.so` (built from NDK) for native hooks
- Hook table: PLT hook for `getDeviceId`, `getSubscriberId`, location, etc where possible

### 7. Identity & Privacy Runtime Hooks (inside clone)
Each hook is per-clone configurable via `clone_config.json` stored in clone's private files dir.

**Identity:**
- Android ID: hook `Settings.Secure.getStringForUser(ANDROID_ID)` + `Settings.Secure.ANDROID_ID`
- IMEI/IMSI: hook `TelephonyManager.getDeviceId()`, `getSubscriberId()` – note Android 10+ restricts to privileged apps; we hook and return spoofed but explain limitation
- WiFi MAC: `WifiInfo.getMacAddress()` – returns randomized locally-administered MAC
- Bluetooth MAC: `BluetoothAdapter.getAddress()`
- GSF ID, Advertising ID: hook GMS `AdvertisingIdClient.getAdvertisingIdInfo()`, `Settings.Secure.getString("advertising_id")`
- WebView UA: hook `WebSettings.getUserAgentString()`, `setUserAgentString()`
- GPU: hook `GLES20.glGetString(GL_RENDERER/VENDOR)`
- Build props: hook `android.os.Build` fields via reflection + native __system_property_get override
- Fingerprint profiles: JSON profiles storing all above

**Privacy:**
- Password: clone launcher activity wrapped with `PasswordActivity`
- Stealth: icon removed, launched via dialer code / tile
- Calculator decoy: separate `CalculatorDecoyActivity` that looks like calculator, unlocks real app on secret code
- Exclude from recents: `FLAG_EXCLUDE_FROM_RECENTS` per settings
- Incognito keyboard: custom InputMethodService inside clone
- Clear on exit: `onTrimMemory` + `Application.onTerminate` hook deletes files in configured list
- Permission stripping: manifest remove `<uses-permission>` per user selection + runtime `checkPermission` hook returns DENIED
- GPS spoof: hook `LocationManager.getLastKnownLocation()`, `requestLocationUpdates()` returns fake Location
- Sensors: hook `SensorManager.getSensorList()` returns empty or fake
- Root hide: hook `File.exists("/system/xbin/su")`, `Runtime.exec("su")` throws
- Logcat disable: hook `Log.d/i/...` no-op if enabled

**Display:**
- Status/nav bar color: hook `Window.setStatusBarColor()`
- Dark mode: inject `AppCompatDelegate.setDefaultNightMode()`
- Rotation lock: hook `Activity.setRequestedOrientation()`
- Language: hook `Resources.getConfiguration()` locale override via `Locale.setDefault()` + `createConfigurationContext()`
- Font: hook `Typeface.createFromAsset()`
- PiP, freeform: manifest `android:supportsPictureInPicture`, `resizeableActivity=true` + runtime hooks

**View Mod:**
- Runtime inspector uses `WindowManager.getGlobalWindowManager()` reflection to list views
- View hierarchy walker dumps `ViewGroup` tree
- Modification rules: JSON `{activity, viewId, xpath, action: hide/show/replaceText/restyle}`
- Apply via `ViewTreeObserver.OnGlobalLayoutListener` + `RecyclerView.OnScrollListener`

**Media:**
- Mute on start: `AudioManager.setStreamMute()`
- Fake camera: hook `Camera.open()`, `camera2` API, return `FakeCamera` that supplies bitmap from user selected images (with EXIF handling)

**Networking:**
- Per-clone proxy: `libtun2socks` + `pdnsd` + `microsocks` binaries (already in reference assets) started inside clone's process, VpnService? Actually without VPN permission we use Proxy class hook: `System.setProperty("http.proxyHost")`, `ProxySelector` hook, and OkHttp interceptor injection via ByteHook
- Disable networking: hook `ConnectivityManager.getActiveNetworkInfo()` returns null, and socket `connect()` throws if blocked

**Storage:**
- External storage redirect: hook `Environment.getExternalStorageDirectory()` returns `context.getExternalFilesDir() + "/redirect/"`
- Isolate: each clone gets its own `files/` already due to different package, but we also redirect shared prefs path

### 8. Signing Pipeline
- zipalign (if build-tools available)
- apksigner with generated debug keystore or user provided keystore
- Verify `apksigner verify`

### 9. Rollback & Diagnostics
- All steps transactional: if any fails, cleanup temp dir and report
- Detailed logs: `CloningDiagnostics` collects warnings, rewritten counts, blocked features
- User sees report before install

## Module Independence

Each feature group is independent Kotlin object with interface `CloneFeature { fun apply(config, context) }`. Engine composes them.

## AI Controller

`AiController` is NOT replacement for engine. It:
- Parses view hierarchy dump (XML)
- Uses on-device LLM? In this implementation, heuristic + optional remote LLM API (user-provided key) to suggest rules
- Example: user says "hide ads" -> AI finds views with id `adView`, `ads_container` and generates view-mod rule
- Generates automation sequences from natural language: "scroll down every 2 seconds" -> `auto-scroll` rule

## Security & Graceful Degradation

For each restricted API, code checks `Build.VERSION.SDK_INT` and permission. If not possible (e.g., IMEI on Android 10+ without READ_PRIVILEGED_PHONE_STATE), it:
- Logs warning in compatibility report
- Disables toggle in UI with explanation tooltip
- Does not crash clone

## References

- AppCloner smali analysis from Next-Cloner decompiled assets shows use of Pine, ByteHook, AndHook, microsocks, pdnsd, tun2socks, libappcloner.so – we preserve same architecture but re-implement in Kotlin with clean code.
