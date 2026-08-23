# Clone-Master — Take-Over Status Report

**Date:** 2026-08-22 · **Auditor:** incoming agent (fresh inspection, nothing trusted from summaries)
**Repos (local):** `/home/user/clone-master` · `/home/user/next-cloner` (private, fetched read-only)
**Toolchain used for verification:** JDK 21.0.12, Android SDK 34 / build-tools 34.0.0, Gradle 8.6 wrapper.

Every claim below was verified against source, git, tests, or SDK tools this session.

---

## A. Current Git state

| Item | State |
|---|---|
| Branch | `main`, tracking `origin/main`, **working tree clean** |
| HEAD | `3242ae4` *feat(engine): full DEX rebuild engine (dexlib2) replaces in-place patcher* (2026-08-22 10:56 UTC) |
| Push state | **All previous work committed and pushed** — local HEAD == origin/main == tag `v2.0.0-dexlib2-engine`. The interrupted commit/push/release actually completed; nothing is lost |
| Tags (8) | v1.0.0-master … v2.0.0-dexlib2-engine (= HEAD) |
| Releases | No GitHub Releases objects found — handover mentioned a “release operation”; only tags exist |
| Uncommitted/local-only work | **None** (workspace snapshot also started empty; anything unpushed from prior sessions is unrecoverable, but git shows no gaps) |
| Documents referenced by handover | `docs/ENGINE_REPLACEMENT_REPORT.md` ✅ exists · `docs/NEXT_CLONER_AUDIT.md` ❌ **does not exist** (was never written) |
| Next-Cloner | exists, **private**, ~406 MB, desc: *“Full reverse-engineered workspace of AppCloner 3.6.8 …”*, 1 commit `1cae4c7b`. Fetched read-only with user-provided token (token not stored in any config/remote) |

## B. Current APK engine state — VERIFIED, do not replace

Pipeline (on-device path): `CloneService → CloneEngine.clone() → [apktool not on device] → cloneNative() → core:AppCloneBuilder`
→ ZipIO read → stale-v1-sig strip → **BinaryXml AXML manifest transform (ManifestCloner)** → **DexPackageRewriter (dexlib2 full DEX rebuild)** → extra assets → ZipIO aligned repack → **custom v2 signer** → **ApkValidator (incl. semantic component→class check)**.

Verified this session (clean checkout):
- **37/37 unit tests pass** (core 15: CloningCoreTest 6 + ClonerE2ETest 9; app 22: OptionStateTest 6, CloneConfigDefaultsTest 3, ManifestTransformationTest 6, DataBundleTest 7). The report’s “37 tests” = combined total; core alone is 15.
- **`:app:assembleDebug` succeeds** (8.5 MB APK, minSdk 24 / targetSdk 34).
- **Sandbox self-clone E2E reproduced**: `com.clonemaster → com.clonemaster.clone1.verify` (longer name): apksigner **v2 ✓**, zipalign **✓**, aapt badging shows renamed package ✓, manifest parses ✓.
- Device regression `mark.via.gp → mark.via.gp.clone1` (installed+launched) stands per handover; engine untouched.
- Honest, documented limitations (confirmed in source): resources.arsc byte-copied; v2-only signing; base-APK-only for splits; **native manifest path applies ONLY: package attr, sharedUserId removal, component `android:name`s, `android:process`, provider authorities** — no label/versionCode/versionName/icon changes.

## C. Runtime infrastructure state — THE critical gap

- `HookFramework` + ~20 subsystem `Hooks.install(...)` implementations (Identity, Privacy, Display, Storage, Networking, Media, Navigation, Launching, Notification, Game, TvWear, Automation, Developer, Environment, Tracking, CpuGpu…) **exist and are compiled — but only into the manager app itself**.
- Config plumbing is real: UI → `CloneConfig` (44 data classes, ~250 fields) → `assets/clone_config.json` bundled in the clone ✅.
- **Nothing inside a clone built on-device ever reads that config.** The native pipeline injects no classes, wraps no Application (`CloneRequest.wrapApplication` scaffold exists, never used).
- The old apktool path *did* generate `HookApplication.smali` + 13 subsystem smali — but each was a **logging no-op stub** (“Actual hook logic would be here”), and that path can only run on desktops anyway.
- Reference architecture confirmed in Next-Cloner: AppCloner ships prebuilt runtime DEX archives (`assets/classes.dex.xz`, `assets/kotlin.dex.xz`) + `libappcloner.so` and merges them into clones — i.e., a **self-contained runtime artifact** is the proven delivery mechanism. Clone-Master has no such artifact yet.
- **Conclusion: 0 of the ~60 runtime-hook options currently affect a clone built on-device.** Infrastructure ≈ 40% (config+plumbing+implementations exist; delivery = 0%).

## D. Complete feature inventory (discovered, not assumed)

- **UI options: 83** (`OptionRegistry`, 16/18 categories populated; **MEDIA and NAVIGATION have zero UI options** despite config+hooks existing).
- **Config surface:** 44 data classes / ~250 fields — much larger than UI (hidden capabilities: automation triggers, sequenced actions, device filtering, layout inspector, hook options…).
- **UI defects found by tracing:** 5 broken rows — `diagnostics_compatibilityReport → configFieldPath "isBatch"` (wrong field), `developer_versionName` duplicates `general_versionName`, `diagnostics_logcatViewer` duplicates `developer_logcat`; duplicate pairs writing the same field: hideRoot ×2, hideEmulator ×2, hideMockLocation ×2, and two different fields for WebView UA (`identity.webViewUserAgent` vs `developer.webViewUa`).
- Full per-option matrix: **`docs/FEATURE_IMPLEMENTATION_STATUS.md`** (all 83 rows, status + evidence each).

## E. Next-Cloner reference mapping (actual inspection)

Repo verified as RE workspace: `original/AppCloner-3.6.8-26062918.apk` (64 MB) · `decompiled/apktool/` (**16,481 smali**, 9 dexes, res, assets, **44 native libs**) · `decompiled/jadx/` (**8,150 java**) · `build.sh` (apktool b → keytool → uber-apk-signer; desktop-only). Previous agent’s description: accurate.
Mechanism-level facts usable as reference (clean-room):
| Mechanism | Reference evidence | Clone-Master status |
|---|---|---|
| Runtime delivery | `assets/classes.dex.xz`, `assets/kotlin.dex.xz` merged into clone; `libappcloner.so` | **missing** (P0) |
| On-device rebuild | assets `axml/`, `dexopt/` tools onboard; apktool.yml metadata | core equivalents exist (BinaryXml, dexlib2, ZipIO) ✅ better-suited |
| Networking | `libpdnsd.so`, `libtun2socks.so` per-proxy DNS/tunnel | config+manager-side only |
| Compression | `libzstd-jni.so` (ZSTD for data bundles) | ZSTD declared, falls back to ZIP |
| Protection | `libdexprotector.so`, obfuscated root package — AppCloner self-protects | n/a (we don’t need it) |

**License constraint (unchanged from previous agent, re-affirmed):** Next-Cloner is decompiled proprietary code. It will be used for architecture/behavior reference only; **no code will be copied** into Clone-Master. All gap fixes = clean-room implementations.

## F. Broken / partial / missing capabilities (ranked evidence)

1. **Runtime delivery missing (P0)** — 60+ options dead in on-device clones (config bundled, no consumer).
2. **General build-time options not applied natively (P0)** — app name, versionName/Code, icon, badge, branding removal (`labelOverride` explicitly “not yet supported in native path”).
3. **Desktop-only transforms (P1)** — permission strip, stealth icon removal, allowBackup, hasFragileUserData, largeHeap/category, OBB embed, data bundling: exist only in the apktool path.
4. **UI map defects (P1)** — 5 broken mappings + 6 duplicated concept controls (see D).
5. **Install button (P2, known issue)** — receiver registered in `onResume`/unregistered in `onPause`; while the system install-consent UI is up, our activity is paused → **result broadcast is missed**. Root cause identified.
6. **Export APK (P2, known issue)** — writes to `getExternalFilesDir("exports")` (app-private) instead of public Downloads.
7. Docs drift (P2) — “83 supported”, “37 unit tests” (core-only reads 15), next-cloner audit doc absent (now superseded by this report + the matrix).

## G. Shared infrastructure gaps

- **Runtime artifact + DEX merge step** (missing) — blocks all runtime features; fix once in `AppCloneBuilder` (not per-feature hacks).
- **Hooking substrate** — Kotlin hooks reference androidx in places; runtime artifact must be androidx-free + needs a hook mechanism (instrumentation-proxy first; native PLT hooking optional later — reference ships `.so`, we currently have none).
- **ManifestCloner extensions** — label/versionName/versionCode/icon-ref/category/allowBackup/permissions/remove-launcher/hasFragileUserData/largeHeap (one shared binary-XML facility, already padded-fixed).
- **resources.arsc table writer** — string-pool/table package rename + app_name replace (shared by name/branding features).
- **Image pipeline** — icon decode/resize/encode + badge overlay usable from native path (currently Bitmap-based in apktool path only).
- **Test scaffold** — engine E2E exists; missing golden-APK regression pack + feature on/off diff tests (enabled must transform; disabled must not).

## H. Prioritized implementation roadmap

| Phase | Scope | Exit criteria |
|---|---|---|
| **P0-1** Wire General options into native path | extend `CloneRequest`/`ManifestCloner`/resource handling: label, versionName/Code, branding; icon+badge via pure-JVM image encode | golden diff test per option; Via regression intact |
| **P0-2** Runtime delivery (the big one) | build `runtime/` module (androidx-free): HookApplication wrap via dexlib2 class synthesis + manifest `android:name` swap (original preserved), merge runtime classes.dex into clone (multidex-safe), init reads `clone_config.json` | clone logs init on device (Via), env-config consumed |
| **P1-3** First wave of runtime features, verified on/off | Android ID, clipboard block, screenshot block, recents exclude, sensors/GPS spoof, keep-awake, dark mode, orientation, permissions strip + manifest options from F-3 | each option: enabled-transforms/disabled-clean, Via + self APKs |
| **P1-4** UI integrity fixes | fix 5 broken mappings, dedupe pairs, add MEDIA/NAVIGATION options or hide dead config; Install receiver lifecycle fix; Export → MediaStore Downloads | OptionStateTest extended; manual device pass |
| **P2-5** Data bundling + OBB into native path; resources.arsc writer | bundle archive embed via ZipIO additions; arsc package rename | bundle→first-run restore on device |
| **P2-6** Hook substrate hardening + networking | instrumentation-proxy hook layer; then optional native `.so` (our own NDK code) for PLT hooks; proxy/DoH | on-device verification per RUNTIME_TEST_PLAN |
| **Ongoing** Next-Cloner per-feature reference notes | append mechanism comparisons to FEATURE_IMPLEMENTATION_STATUS.md per feature as implemented (reference-only) | matrix rows updated with evidence |

**Regression guard (every phase):** `:core:test` + `:app:testDebugUnitTest` (37 tests) + self-clone E2E + **mark.via.gp → clone1 must keep installing/launching**; optional features default OFF (0/N rule) — clean clone must stay byte-stable.

---
*Prepared as required by handover §23 before implementation. Next: begin P0-1/P0-2 unless directed otherwise.*
