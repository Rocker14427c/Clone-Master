# Clone-Master Diagnostics (in-app logging & crash capture)

> Added in v2.2.0-diagnostics. Purpose: a tester with **no adb, no logcat,
> no root** can capture *what they did* and *what the app/clone did* — and hand
> the complete picture to a developer as one shareable text file.

## How to use (tester)

1. Open Clone-Master → tap the **FAB (+)** → **"Diagnostics Log (view/share)"**.
2. Reproduce your issue in the app (change options, build a clone, install…).
3. Come back to **Diagnostics Log** → tap **"Share full report"** and send the
   generated `clone-master-diagnostics.txt` (Telegram/Gmail/Drive — anything).

That's it. No permissions needed; everything lives in app-private storage.

## What the report contains

- **Device header**: app version+code, device model, Android release/SDK,
  build fingerprint, ABIs, free storage.
- **Current session** (`session.log`, ≤512 KB + one rotation slot):
  - every screen opened (`open CloneOptionsActivity`),
  - every option change (`set privacy.disableScreenshots = true   [privacy_screenshots]`),
  - every preset applied, every Build-Clone tap,
  - the full clone build transcript (start, each step, success/failure),
  - engine diagnostics (native builder lines, validator output),
  - install results (PackageInstaller status + real device message),
  - warnings/errors with stacktraces.
- **Previous session** (`previous.log`, archived automatically at app start).
- **Last crash** (`crash-last.txt`): full uncaught-exception stacktrace
  captured by the crash hook. If the app died, the *next* launch shows a red
  banner in the Diagnostics screen and the report includes the crash.

## Switches (in the Diagnostics screen)

| Switch | Default | Effect |
|---|---|---|
| Record diagnostics | **ON** | Master switch. Turns file recording on/off (logcat mirroring of W/E always stays on). |
| Verbose mode | OFF | Also records full config JSONs and full engine diag lines. Turn on only when a developer asks. |
| Clone runtime file log | OFF | **New clones** get `"fileLog":true` in their runtime meta → the injected runtime mirrors its events to `files/cloner/rt.log` *inside the clone*. |

Sensitive values (passwords, tokens, keys, secrets…) are **masked** before
writing, so the report is safe to share.

## Clone-side runtime logging (audience: developer)

- The injected runtime always logs to logcat under the anchor tag
  **`CloneMasterRT`**: `adb logcat | grep CloneMasterRT` shows
  `runtime v3 loaded: pkg=… mode=wrap|factory original=… screens=… awake=… orient=… fileLog=…`.
- **Crash capture (v2.3.0+, always on when a runtime is injected):** an
  uncaught exception in the clone writes the FULL stacktrace to
  `Download/CloneMasterRT-<pkg>-crash.txt` (MediaStore, API 29+; no permission
  needed), then the app's own crash UI runs as before. Share that file — no adb.
- **"Clone runtime file log" ON at build time:** additionally mirrors runtime
  lines to `Download/CloneMasterRT-<pkg>.log` (public) and
  `files/cloner/rt.log` (private, 128 KB cap).

## Files & storage budget

```
files/diag/session.log       # current (rotates to session.1.log at 512 KB)
files/diag/session.1.log     # previous rotation slot
files/diag/previous.log      # archived last session (≤ ~768 KB archived)
files/diag/crash-last.txt    # last crash detail
files/diag/crash.flag        # set while crashed, consumed at next init
```

Total worst case ≈ 1.5 MB in app-private storage. **Clear** button wipes all.

## Implementation map

| Piece | File |
|---|---|
| Pure-JVM core (rotation, sanitize, tail) | `app/.../diagnostics/DiagLogCore.kt` |
| Android facade (session, crash hook, report) | `app/.../diagnostics/DiagLog.kt` |
| Viewer / share screen | `app/.../diagnostics/DiagnosticsActivity.kt` |
| App init | `CloneMasterApp.kt` |
| Engine/clone logging | `CloneEngine.kt`, `CloneService.kt`, `BuildProgressActivity.kt` |
| Option/preset/build-action logging | `CloneOptionsActivity.kt` |
| Runtime logger (clone-side) | `runtime/.../RuntimeLog.java` (+ `fileLog` in `RuntimeConfig`) |
| Meta plumbing (`fileLog`, `runtimeVersion:2`) | `core/.../AppCloneBuilder.kt`, `CloneRequest.kt` |
| Tests | `DiagLogCoreTest.kt`, `RuntimeConfigTest`, `ClonerE2ETest` (meta flags) |
