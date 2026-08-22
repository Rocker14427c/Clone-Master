# Clone-Master – APK Build Pipeline Audit & Fix Report

Date: 2026-08-22 · Scope: clone pipeline only (no new features)
Audit result follows the requested structure: current pipeline → root cause of
install failure → alignment issue → build authenticity → installation failure →
required changes → implemented fixes → evidence.

---

## 1. Current pipeline (traced class by class)

```
UI: CloneOptionsActivity.showCloneSummaryAndBuild()
  → BuildProgressActivity.startBuild()              (UI progress, Install/Export buttons)
    → CloneEngine.clone(config, onProgress)         (app/.../engine/CloneEngine.kt)
      → diagnostics.clear(); workDir under cacheDir
      → apktool? (findApktool(): PATH scan) 
         NULL on Android → cloneNative()  ← the only path that runs on a device
      → cloneNative():
         1. read source APK bytes
         2. ZipIO.read()                     → parse ZIP (EOCD+central dir), keep raw entry bytes
         3. AppCloneBuilder.build():
            a. BinaryXml.read()              → parse AndroidManifest.xml (binary AXML)
            b. ManifestCloner.transform()    → rewrite package, authorities, remove sharedUserId
            c. planAuthorities()             → deterministic short authorities (cm + 10 hex)
            d. BinaryXml.write()             → re-emit manifest (UTF-8 pool, real end-elements)
            e. DexStringPatcher.patch()      → in-place MUTF-8 string rewrites, SHA-1+Adler-32 fixup
            f. ZipIO.write()                 → repack: STORED entries aligned (lib/ = 16384,
                                               others = 4), DEFLATED copied raw, stale
                                               META-INF v1 signatures dropped, deterministic
            g. V2Scheme.V2Signer.sign()      → APK Signature Scheme v2 (RSA-2048/SHA-256)
            h. ApkValidator.validate()       → post-build gate (see §6)
         4. write final APK to external files clones/
         5. Result.success / Result.failure
```

Dev-only nuance: if `APKTOOL` env/PATH contains a JVM apktool, the old
decode→smali→build path runs (desktop only). On Android it never exists.

## 2. Build authenticity — genuine rebuild or lightweight patch? (ANSWER: B+)

**It is a genuine *transformation + packaging* pipeline, not a full
decode/rebuild.** Exactly what is reconstructed vs preserved:

| APK component | Status | How |
|---|---|---|
| AndroidManifest.xml | **REBUILT** | parses binary XML, rewrites package/authorities, re-serializes |
| classes*.dex | **PATCHED in place** | string-table items rewritten inside existing data section; SHA-1 signature + Adler-32 recomputed; all offsets preserved |
| ZIP container (local headers, central dir, EOCD) | **REBUILT** | new deterministic container, aligned STORED entries |
| APK signature | **REBUILT** | v2 signature block generated fresh (old v1/v2 signatures stripped) |
| resources.arsc | preserved (byte-identical) | rename does not require resource re-compilation |
| remaining entries (assets, libs, etc.) | preserved (raw copied) | compression preserved |
| res/ files | preserved | package rename only |
| Native code | preserved | re-signing only |

Deliberately NOT done (documented, not hidden): resource re-compilation,
smali/dex re-compilation, app name / label change, feature injection.
These are unnecessary for a clean package-name clone and are later phases.

## 3. Root causes found (each one real, verified in code)

1. **`resources.arsc aligned to 4` check was mathematically wrong.**
   Used `localHeaderOffset + 30 + nameLen` — that is the offset of the *extra
   field*, not the data (it omitted the alignment padding length). Whenever the
   writer's padding was not a multiple of 4, the check FAILED even though the
   real data offset was correctly aligned. (Reproduced in the regression test
   `alignment validated against real data offsets`.)
2. **Validation state machine: failed checks were not errors.** Alignment
   (and other non-listed) checks were appended to a `checks` list only; the
   `errors` list drove `ok`. So `[FAIL] resources.arsc aligned to 4` appeared
   in the log while `ok=true`, `HasError=false`, UI showed "Complete". Fixed:
   every failed mandatory check now adds an error; `ok=false`; build fails.
3. **Stale v1 (JAR) signature files were copied into the output.** The source
   APK's `META-INF/MANIFEST.MF`, `CERT.SF`, `CERT.RSA` (invalid after content
   changes) were preserved; any installer that falls back to v1 verification
   rejects the APK. Fixed: dropped during repack; validator asserts absence.
4. **Native lib alignment was 4096-only.** Devices with 16 KB pages
   (Android 15+, most 2024+ devices) require STORED `.so` entries aligned to
   16384; 4096-aligned output can be rejected at install. Fixed: `lib/*`
   STORED entries aligned to 16384 (also accepted on 4 KB-page devices).
5. **Install button gave no real error.** It only fired `ACTION_VIEW` and got
   the generic system message. Fixed: PackageInstaller session + status
   broadcast — the underlying Android failure (status + statusMessage) is now
   captured and displayed; falls back to FileProvider ACTION_VIEW.
6. **Signing identity was random per build** (JDK 21 NativePRNG). Fixed:
   process-cached signer, persisted on-device in private storage → stable
   clone signature across rebuilds/app restarts.
7. **Success wording over-claimed.** "Complete" implied installable. Now:
   "Build complete & validated (…checks passed). Installation not yet verified
   — tap Install." Build-vs-install are explicitly distinct.

## 4. Alignment: what is required, and the correct order

Requirements (verified against zipalign/apksigner semantics):
- STORED (uncompressed) entries: `dataOffset % 4 == 0`
  (`resources.arsc`, `classes*.dex`, stored assets).
- STORED native libs: `dataOffset % 16384 == 0` (16 KB page support; superset
  of the 4 KB requirement).
- DEFLATED entries need no alignment.

Correct order (implemented, and the only order that works for v2 signing):
```
extract (parse, no file writes) → analyze → modify (manifest, DEX)
→ PACKAGE with alignment (pad via ZIP extra field, during write)
→ SIGN v2 (block inserted between last entry and central directory —
   alignment of entry data is untouched by signing)
→ VERIFY (structure/alignment/CRC/DEX/manifest/authority/signature)
→ INSTALL (user action; real status captured)
```
Signing after alignment; no repack after signing (the v2 block does not move
entry data, so alignment survives).

## 5. Why the earlier build "completed in ~1 second"

The native pipeline is genuinely fast for small/medium APKs:
- manifest: single parse+serialize (~ms), DEX: one pass over string_ids
  (in-memory), repack: memory copy. No artificial delays exist, and none were
  added. For large apps (tens of MB) it is still sub-second to a few seconds
  because it never recompiles dex/resources — that is the design, not a
  shortcut. Everything it claims to do, it verifiably does.

## 6. Post-build validation (all mandatory; failures = build failure)

- APK exists & non-empty; valid ZIP/EOCD; CD count == entry count; no duplicate
  entry names; supported compression methods.
- AndroidManifest.xml present + binary AXML; package == clonePackage;
  no original authority values used.
- resources.arsc present (expected) + STORED + aligned from real data offset.
- all STORED entries aligned (4; 16384 for lib/); stored CRCs recomputed.
- classes*.dex valid magic, all dex files; no original authority strings.
- no stale META-INF signature files.
- v2 signature verifies (both our verifier and Google's apksigner tested).

## 7. State machine (now strict)

```
any mandatory validation failure → errors non-empty → ok=false
  → AppCloneBuilder throws → CloneEngine Result.failure(message)
  → BuildProgressActivity "Build failed: <stage message>" + full diagnostics
  → Install button stays disabled
success ONLY when every check passes; UI text spells out what was verified
and that installation was NOT yet verified.
```

## 8. Regression tests added (lock the failures in)

| Test (core ClonerE2ETest) | Protects against |
|---|---|
| `alignment validated against real data offsets` | the user's exact `[FAIL] resources.arsc aligned to 4` + hidden-success bug |
| `stored native libs are 16KB aligned and stale v1 signatures are dropped` | 16 KB-page install rejection; stale META-INF rejection |
| `build output is deterministic` | non-reproducible builds |
| `dex order violations are reported not hidden` | silent DEX sort-order breakage |
| `authority that does not fit fails clearly` | silent corruption instead of fail-closed |

`:core:test` + `:app:testDebugUnitTest` = 30+ tests, 0 failures.

## 9. Honest limitations

- The failing APK itself was on the test device, not in this environment; the
  regression suite reproduces its *failure classes* (validator false-fail,
  state machine, v1 leftovers, 16 KB alignment). Same-APK device test is
  requested for the next device run.
- Hard-coded strings LONGER than the clone package cannot be in-place patched
  (reported, not hidden).
- v2-only signature: installable on Android 7.0+.
