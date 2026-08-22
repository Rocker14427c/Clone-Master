# Clone-Master Verification Report

Date: 2026-08-22 · Commit: see git log (`fix/clean-clone-baseline` work on `main`)
Environment: Debian 13 sandbox, JDK 21, Android SDK 34, Gradle 8.6 wrapper,
`apksigner`/`aapt`/`zipalign`/`dexdump` from build-tools 34.0.0.

This report records what is **verified with evidence** versus what is **honestly
unverified** (device-only checks). Per the handover rule: no fabricated results,
"implemented" ≠ "verified".

---

## 1. Bug #1 – optional features default OFF (0/N rule) — FIXED & VERIFIED

**Evidence:**
- `CloneConfig` defaults audited and flipped: 70+ optional toggles now `false`
  (environment master + fine-grained, rootHideLevel/emulatorHideLevel = OFF,
  privacy hideRoot/hideMockLocation, tracking blockers, CPU/GPU hide, hook
  framework options, WebView injection, layout inspector, device filtering, data
  bundle categories, storage isolation/redirect, game OBB, multiWindow,
  showVolumeIndicator, removeBranding, includeObb, developer native hooks...).
- Only mandatory mechanics remain `true`: `transformPaths`, `embedInApk`
  (packaging modes, not features), plus immutable profile data.
- Regression test `CloneConfigDefaultsTest` (3 tests) passes:
  `:app:testDebugUnitTest` → `tests=3 failures=0`.
- `PresetManager`: DEFAULT and CLEAN_CLONE presets no longer enable optional
  toggles (they contradict their own descriptions); presets enable features only
  on explicit user selection.

**How the UI counter behaves now:** `CloneOptionsActivity` derives the enabled
count from the actual config values (`initializeConfigValues()`), so a fresh
application shows **0/N** instead of the previous 11/83.

**Still to verify on device (checklist):**
1. Select new app → Open Clone Configuration → counter shows 0/N.
2. Build Clean Clone → verify optional hooks are not injected/enabled.
3. Enable exactly one option → counter 1/N → build → verify only that option.

---

## 2. Bug #2 – invalid APK generation — ROOT CAUSE FOUND & FIXED

### Root causes (all confirmed in code at commit 4f1bc1b)

The real-device failure ("There's a problem with the app file") was not one bug
but a broken toolchain assumption:

1. **apktool cannot run on Android.** The engine shells out to the `apktool`
   JVM binary; on a device it is never found. The "unzip fallback" then:
2. **read/wrote the binary AndroidManifest.xml as TEXT** (`ManifestTransformer.
   transform` does `readText()`/`writeText()` on the AXML file; `transformBinary`
   explicitly delegates to the text path) → manifest corrupted.
3. **left classes.dex unpatched** (`DexTransformer.transformDexFiles` is a
   documented placeholder; nothing rewrote DEX strings) → manifest package and
   DEX package disagree.
4. **signing also depends on external binaries** (`keytool`, `apksigner`,
   `zipalign`, `which`) → on device the APK was emitted **unsigned** →
   `INSTALL_FAILED_INVALID_APK` (Android 11+).
5. `ApkParser.parseApkFile()` returned `packageName="unknown"` – APK-picker
   clones had a bogus source package.
6. `NativeLibHandler` contained a **committed local machine path**
   (`/home/user/Next-Cloner-ref/...`) — removed.

### The fix: native on-device APK toolchain (pure Kotlin, no external tools)

New module `core/src/main/java/com/clonemaster/core/cloner/` (JVM-clean,
unit-tested, reused by the app):

| Component | File | What it does |
|---|---|---|
| Binary XML editor | `axml/BinaryXml.kt` | Reads/writes Android AXML (UTF-8 & UTF-16 pools), aapt-compatible chunk layout |
| Manifest cloner | `manifest/ManifestCloner.kt` | package rewrite, authority rewrite, sharedUserId removal, package validation |
| DEX string patcher | `dex/DexStringPatcher.kt` | in-place footprint-preserving MUTF-8 rewrites + SHA-1/Adler-32 fixups; reports non-fitting strings as errors (fail clearly, no silent corruption) |
| Authority planner | `AppCloneBuilder.planAuthorities` | short deterministic authorities (`cm` + 10 hex) so DEX patches fit |
| ZIP writer | `apk/ZipIO.kt` | zipalign-compatible repack (stored+aligned arsc/dex/libs, raw-copy compression preservation) |
| v2 signer/verifier | `sign/V2Scheme.kt`, `sign/SigningKey.kt` | APK Signature Scheme v2 (alg 0x0103), deterministic on-device RSA key, hand-rolled X.509 (no keytool) |
| Validator | `apk/ApkValidator.kt` | ZIP structure, AXML manifest, dex magic, authorities gone, v2 verify — gate before "success" |
| Orchestrator | `AppCloneBuilder.kt` | manifest → dex → assets → pack → sign → validate |

### Evidence (official Google tools, real APK input)

Input: real APK built with `aapt2`/`d8` (`com.example.hello`, provider
`com.example.hello.provider`, hard-coded package strings). Clone built by
`AppCloneBuilder` (pure Kotlin):

```
apksigner verify --verbose clone.apk
  → Verifies
  → Verified using v2 scheme (APK Signature Scheme v2): true   [OFFICIAL TOOL ACCEPTS]

aapt dump badging clone.apk
  → package: name='com.example.hello.clone1' …
aapt dump xmltree → android:authorities="cm.<hash>.provider"   (planned short authority)

zipalign -c 4 clone.apk → Verification succesful

DEX: com.example.hello.provider count = 0;  cm.<hash>.provider count = 1
     SHA-1 + Adler-32 recomputed by patcher and independently re-verified
apksigner verify --print-certs → CN=Clone-Master Clone Signer (self-signed)
```

Also: post-build validation **fails closed** — a non-fitting authority
replacement aborts the build with a clear error instead of producing a broken APK.

### Wiring
- `CloneEngine.clone()` now uses the native pipeline whenever apktool is absent
  (which is always on-device) and never silently falls back to the broken unzip
  path. Post-build validation must pass before success is reported.
- `ApkParser.parseApkFile` now parses real metadata via `net.dongliu:apk-parser`.
- `findApktool` scans `PATH`/`APKTOOL` (no `which` dependency).
- `ManifestTransformer`/`DexTransformer` (apktool dev path) now wrap the
  application class and inject the hook framework **only** when optional
  features are enabled (`OptionalFeatures.anyEnabled`).
- Environment spoofing assets are bundled only when environment features are on.

### Unit tests (all passing, no external tools)
`ClonerE2ETest` (6 tests): AXML round trip, manifest transform, DEX patch +
checksum, v2 sign/verify, full clean-clone build + validation, fail-clear on
non-fitting authority. `:core:test` + `:app:testDebugUnitTest` → 30 tests,
0 failures.

---

## 3. Baseline status vs. handover target

Handover: `SIMPLE TEST APK → CLEAN CLONE → VALID APK → SIGNATURE VERIFIED →
INSTALLS → LAUNCHES`.

| Step | Status | Evidence |
|---|---|---|
| Simple test APK → clean clone | ✅ | real aapt2 APK through `AppCloneBuilder` |
| Valid APK | ✅ | `aapt` parses; ZIP/alignment OK |
| Signature verified | ✅ | **official `apksigner` v2 verify = true** |
| INSTALLS | ⛔ device-only | needs a real device (`adb install clone.apk`) |
| LAUNCHES | ⛔ device-only | needs a real device |

**Remaining (device) test plan:**
1. Install Clone-Master debug APK (this build).
2. Clone `com.example.hello`-style simple app (or any small installed app).
3. `adb install <clones dir>/<clone>.apk` → expect success (was
   "There's a problem with the app file" before this fix).
4. Launch the clone; original app must remain intact.
5. Second clone of the same app must coexist (authority uniqueness covered by
   planned short authorities).

## 4. Honest limitations (not hidden)

- Native path currently applies **clean-clone mechanics**; when optional
  features are enabled it reports a warning and still builds a clean clone —
  feature injection into clones (hook framework, spoofing subsystems inside the
  generated APK) is a later phase and is NOT claimed.
- Hard-coded original package strings that are LONGER than the clone package
  name cannot be rewritten in-place; these are reported as warnings ("may run
  package-integrity checks"), never silently skipped.
- v2-only signature: Android 7.0+ (API 24+) — matches Clone-Master minSdk; v1
  (JAR) signatures not produced.
- `resources.arsc` keeps the original package table entry (industry-standard
  renaming approach); validator does not require it to match the new package.
- dex string table sort-order caveat of in-place patching is accepted
  (industry-standard technique; all offsets/checksums stay valid; no bytes
  shift).
