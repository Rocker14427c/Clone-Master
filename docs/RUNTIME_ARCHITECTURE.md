# Clone-Master — Runtime Delivery Architecture (P0-2)

**Date:** 2026-08-23 · **Status:** implemented, unit-tested, real-APK sandbox-verified; final on-device confirmation pending (see checklist)

## 1. Reference audit (Next-Cloner) — delivery mechanism, structural facts only

Verified by inspecting the actual RE workspace (no code copied, clean-room):

| Mechanism | Reference (AppCloner 3.6.8) evidence | Clone-Master equivalent |
|---|---|---|
| Runtime delivered as **prebuilt dex archives shipped as assets**, merged into the clone | `assets/classes.dex.xz` (200 KB) + `assets/kotlin.dex.xz` (their runtime is Kotlin → two dexes) | `:runtime` module → d8 → `assets/cloner_runtime/classes.dex` (7.9 KB, plain Java → ONE dex, zero deps) |
| Optional hook substrates bundled as zips | `libPine.zip`, `libByteHook.zip`, `libAndHook.zip`, `libAliuHook.zip`, `libAppCloner.zip` (+sha256) | none yet (hook substrate = P1; V1 runtime needs no method hooking) |
| Native components | `libappcloner.so` etc. per ABI | none yet (native hooks = P2) |
| Application wrapping | their runtime wraps the clone's Application (original preserved) | `HookApplication` via manifest swap; original class name preserved in `assets/cloner_runtime.json` |

Takeaway confirmed by evidence: **the clone's runtime must be a self-contained prebuilt artifact; the builder merges it.** Anything referencing androidx/Gson cannot be injected.

## 2. Implementation (where everything lives)

- `runtime/` — new Gradle module (`com.android.library`, plain Java, min-api 21). Sources:
  - `HookApplication.java` — Application wrapper: instantiates the original app class (reflection + protected `ContextWrapper.attachBaseContext`, reflection-safe public API), initializes the runtime, delegates lifecycle. Fail-soft everywhere.
  - `RuntimeInit.java` — loads `assets/cloner_runtime.json` + `assets/clone_config.json`, logs the verification anchor, installs appliers.
  - `RuntimeConfig.java` — pure org.json parse + decisions (JVM-tested).
  - `WindowFlagsApplier.java` — ActivityLifecycleCallbacks applying FLAG_SECURE / FLAG_KEEP_SCREEN_ON / orientationLock.
  - Gradle: `bundleLibRuntimeToJarRelease` → **d8** (`--min-api 21 --lib android.jar`) → `build/runtimeDexAssets/cloner_runtime/classes.dex`; `:app` merges that dir as an assets srcDir (`preBuild` depends on staging). Reliable-task rule: AGP's bundled classes jar is the d8 input (no hand-rolled class dirs).
- `core/` engine:
  - `CloneRequest` gains `runtimeDex: ByteArray?`.
  - `ManifestCloner` gains the **guarded application wrap** (step 4b): relative names resolve against the clone package; missing `android:name` gets a careful attribute add (only when the `name` string + android namespace already exist in the pool); **double-wrap is refused with a clear error**; fail-closed when wrapping is impossible.
  - `AppCloneBuilder` appends the runtime as `classes(N+1).dex` (original dex set/order untouched) + writes `assets/cloner_runtime.json`; wrap requested but not applied → **build fails** (never a silently featureless clone).
  - `ApkValidator` already scans ALL `classes*.dex`, so the wrapper class is existence-checked in every build.
- `app/CloneEngine.cloneNative`: when `OptionalFeatures.anyEnabled(config)` → reads `assets/cloner_runtime/classes.dex` (missing → build fails with a clear message) → `wrapApplication=true`. OFF default path unchanged (byte-stable → Via regression safe).

## 3. JSON contract

`assets/clone_config.json` = full Gson-serialized `CloneConfig` (already the case). The runtime navigates exact key paths (`privacy.disableScreenshots`, `display.keepScreenAwake`, `display.orientationLock`, …). Two tests pin the contract:
- `app/src/test/.../CloneConfigJsonContractTest` — real CloneConfig → Gson → exact paths exist (catches key drift at build time).
- `runtime/src/test/.../RuntimeConfigTest` — parse + decisions (incl. malformed/missing input → safe defaults).

## 4. Verification ladder (what proves what)

| Tier | Evidence | State |
|---|---|---|
| Core unit tests (5 new) | wrap+meta+dex append; fail-closed without dex; guarded attr-add; double-wrap refused; OFF injects nothing | 23/23 core tests pass |
| Runtime JVM tests (6) | parse/decisions, flag masks (0x2000/0x80), fail-soft | pass |
| Contract tests (3) | Gson key paths from the REAL CloneConfig class | pass |
| Real-APK sandbox E2E | app-debug.apk → clone: runtime dex appended, meta `originalApplication=com.clonemaster.clone1.verify.CloneMasterApp`, aapt/apksigner v2/zipalign all clean | pass (this session) |
| **On-device** | install clone, watch the logcat anchor (below) | **needs device — checklist below; do not mark VERIFIED before this** |

## 5. On-device verification checklist (Via regression APK)

Clone `mark.via.gp → mark.via.gp.clone1` twice:
1. **Clean clone (defaults OFF)** — must install/launch exactly as the existing regression case; behavior byte-path unchanged (regression guard).
2. **Runtime clone** with: Disable Screenshots ON, Keep Screen Awake ON, Orientation Lock = portrait. Then:
   - `adb logcat -s CloneMasterRT` must show, at clone start:
     `runtime v1 loaded: pkg=mark.via.gp.clone1 original=mark.via.gp.clone1.<AppClass> screens=true awake=true orient=1`
   - Screenshot attempt inside the clone → blocked (FLAG_SECURE); recents thumbnail blank.
   - Screen stays on while the clone is foreground (FLAG_KEEP_SCREEN_ON).
   - Rotation locked to portrait.
   - `unzip -p clone.apk assets/clone_config.json` shows the enabled values (config actually bundled).
   - Via browsing still works (runtime didn't break the app).

## 6. Honest limitations (documented, not hidden)

- **V1 runtime delivers exactly 3 feature behaviors**: disableScreenshots, keepScreenAwake, orientationLock. The other runtime options (identity/privacy/networking hooks…) require the hook substrate (P1) — their config is already bundled + contract-pinned; they are NOT yet delivered.
- Deep ContextWrapper delegation (forwarding all calls to the original) is minimal in V1 — apps that introspect `this` as their own class may misbehave; extend if compatibility reports demand it (reference does heavier proxying).
- The injected dex is DEFLATED in the APK (valid on API 21+ targets; PAGE_SIZE/alignment rules honored by ZipIO).
- Pre-API-21 devices unsupported by the runtime path (multidex pre-lollipop) — clone minSdk effectively 21+ when runtime enabled.
