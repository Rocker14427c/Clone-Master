# Clone-Master – Clone Option State Audit & Receiver Crash Fix

Date: 2026-08-22 — scope: (1) BuildProgressActivity receiver crash, (2) clone
option state model. No new features added.

---

## Part 1 — BuildProgressActivity crash (device: SecurityException)

### Exact crash (from device log)
```
FATAL EXCEPTION: main
java.lang.RuntimeException: Unable to resume activity
  {com.clonemaster/com.clonemaster.ui.BuildProgressActivity}
Caused by: java.lang.SecurityException: com.clonemaster: One of
  RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED should be specified when a
  receiver isn't being registered exclusively for system broadcasts
  at ContextImpl.registerReceiverInternal
  at BuildProgressActivity.onResume(BuildProgressActivity.kt:204)
```

### Root cause
`BuildProgressActivity.onResume()` registered a dynamic receiver for
`"com.clonemaster.INSTALL_RESULT"` with the deprecated no-flag
`registerReceiver(...)`. Because the app ships `targetSdkVersion 34`, Android 14+
requires an explicit `RECEIVER_EXPORTED`/`RECEIVER_NOT_EXPORTED` flag for any
receiver that is not a system broadcast. The activity therefore crashed on
resume, i.e. the moment the clone/build workflow opened BuildProgressActivity —
matching the reported "crashes when I press the clone/build operation".

### Receiver purpose & the correct export behavior
The receiver exists solely to receive OUR OWN PackageInstaller result broadcast
(`com.clonemaster.INSTALL_RESULT`), delivered through a `PendingIntent` that this
app itself created for `PackageInstaller.Session.commit()`. No external app ever
legitimately sends it; external senders must be rejected. Therefore:
**`RECEIVER_NOT_EXPORTED` is the correct (and secure) behavior** — internal
intent, same-app sender.

### Fix (applies across the supported range)
`ContextCompat.registerReceiver(context, receiver, filter,
ContextCompat.RECEIVER_NOT_EXPORTED)`:
- **API 33+** → framework call with `RECEIVER_NOT_EXPORTED` (satisfies the
  Android 14+ requirement),
- **API < 33** → plain `registerReceiver` (flags not defined there),
- keeps working on minSdk 24 → targetSdk 34 without deprecated/version-broken
  patterns. AndroidX Core is already a project dependency.

Lifecycle discipline (verified):
- registration happens only when `installReceiver == null` → no double
  registration on repeated `onResume`;
- `onPause` unregisters and nulls the reference → no leak, second `onPause`
  cannot throw;
- registration is wrapped in try/catch: worst case the install-result feedback
  is unavailable (message shown), the activity never crashes, the clone/build
  workflow proceeds regardless;
- `onReceive` still delivers the real `PackageInstaller.EXTRA_STATUS` /
  `EXTRA_STATUS_MESSAGE` to the UI.

---

## Part 2 — Clone option state ("0/83 options" audit)

### The complete state flow (traced)
```
UI field (text/dropdown/switch)
  → OptionConfigurators / OptionsAdapter
  → updateConfigFromOption(option, value)      config field write (CloneConfig)
  → configStorage.saveConfiguration(config)    persisted JSON per clonePackage
  → ConfigStorageManager.loadConfiguration()   restored when reopening
  → clone request: config serialized → CloneEngine.clone(config)
  → transformation: apktool path OR native AppCloneBuilder
  → generated clone APK + bundled assets/clone_config.json
```

### Answers (each verified in code)

**Q: Do displayed values mean the feature is enabled?**
**NO. Values are value-holders; features are gated by enable flags.**
- `Android ID` (TEXT_FIELD) writes `identity.androidId` — activation is
  `identity.spoofAndroidId`. Default `false`.
- `IMEI/IMSI` writes `identity.imei` — activation `identity.spoofImei` (false).
- `Wi-Fi/BT MAC`, `GSF ID`, `Advertising ID` — same pattern, all gate flags
  default false.
- `WebView User-Agent` value is inert until `customWebViewUaEnabled=true`.
- `Device Profile "pixel8_pro"` = the DEFAULT profile id shown in the UI
  (case 1 of the four offered). It does nothing until
  `environment.spoofPhysicalDeviceProfile=true`, which is **not toggleable in
  the UI at all** (only presets/imports can set it).

**Q: What does "0/83" mean after the fix?**
It is computed by the new `OptionState.enabledCount(config)` straight from the
SAVED config:
- Boolean options: active when their field is `true`.
- Value-typed identity/device options: active only when their gate flag is true.
- Duplicate switches sharing one field (e.g. "Root Hide" and "Hide Root" both
  map to `environment.hideRoot`) count once.
- Fresh `CloneConfig()` ⇒ **0**. (Old counter counted only UI-map booleans and
  MISSED preset-activated flags.)

**Q: Are any identity/device options enabled by default?**
**No.** All `spoof*`/`customWebViewUaEnabled`/`spoofPhysicalDeviceProfile` and
every optional toggle default to `false` (0/N defaults rule, enforced by
`CloneConfigDefaultsTest`). Random values exist by default, but they never
activate anything.

**Q: Where do the generated-looking values come from?**
`CloneConfig` property initializers: `randomAndroidId()/randomImei()/randomImsi()/
randomMac()/randomGsfId()/randomGaId()`. Generated once per config object
creation (new clone / new config), persisted with the saved config (so they stay
stable across reopens), re-randomized for each new clone, and consumed ONLY
when the matching spoof flag is set (runtime hooks) or when OS hooks are
injected (optional path). They are never applied for a disabled option.

**Q: Are clone modifications applied only when enabled?**
- Native/on-device path: builds clean-clone mechanics; feature injection is a
  later phase and reports a warning when options are on.
- Descriptor path (apktool on desktop): HookApplication wrap + hook framework
  injection happen only when `OptionalFeatures.anyEnabled(config)` is true.
- `assets/clone_config.json` is passive metadata; it carries values, it does
  not activate them.

### Defects found & fixed
1. **Hidden preset-activated options** — PRIVACY/MAXIMUM_PRIVACY presets set
   `spoofAndroidId`/`spoofGsfId`/`spoofPhysicalDeviceProfile`… but the old
   counter (UI-map booleans) displayed them as 0. Fixed: `OptionState` counts
   from `CloneConfig`, so preset-enabled options are visible and counted; the
   detailed summary now lists them by name.
2. **Misleading "IMEI BLOCKED" warning** — fired whenever an IMEI *value*
   existed (always, it's random by default). Now gated on
   `identity.spoofImei` (the actual enable state).
3. **Device profile label lied** — "Profile: pixel8_pro" now annotates
   `(SPOOFED)` vs `(showing default – spoofing OFF)`.
4. **Duplicate switches, one field** — "Root Hide"/"Hide Root" etc. share the
   same config field; the counter dedupes by field path so enabling one counts
   once.

### Honest remaining gap (documented, not hidden)
The UI has **no enable switch for identity/device spoofing** — those gate flags
can only be set through presets or imported configs. The state model is now
consistent (values never imply enabled; enabled is always visible/counted), but
per-option enable toggles for identity fields are a UI follow-up, not implemented
in this pass (no new features per instruction).

### Test coverage (all passing)
`OptionStateTest` (6): Case A fresh=0 & nothing applied · Case B one option ⇒ 1 ·
Case C several + dedupe · Case D value without flag = 0 / with flag = 1 / after
disable = 0 (value retained) · preset-activated options visible · profile default
value-only. Total suite: 38 tests, 0 failures. Build-pipeline regression tests
(alignment, 16 KB libs, stale signatures, determinism, fail-clear) untouched and
passing.
