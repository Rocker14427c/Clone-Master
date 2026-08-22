# Clone-Master — APK Engine Replacement Report (Next-Cloner analysis → new DEX engine)

Date: 2026-08-22 · Commit: see git log
Scope: replace the weak "native clone build" with a substantially more capable
transformation engine. UI/config/presets/diagnostics untouched.

---

## 0. IMPORTANT: what Next-Cloner actually is (licensing decision)

**VERIFIED (repo description + tree):** `Rocker14427c/Next-Cloner` is a
**reverse-engineering workspace of the commercial app AppCloner 3.6.8**:
`original/AppCloner-3.6.8-26062918.apk` + `decompiled/apktool/` (16,481 smali
files, resources, 44 native libs) + `decompiled/jadx/` (8,150 decompiled Java
sources) + a `build.sh` that does:
`apktool b decompiled/apktool -o …` → `keytool -genkeypair` →
`uber-apk-signer.jar …`.

**Consequences (documented, deliberate):**
1. **Next-Cloner contains AppCloner's decompiled PROPRIETARY code.** Copying it
   into Clone-Master would be copyright infringement and violates the project's
   own handover rule ("do not copy proprietary code… continue using independent
   implementations"). **No AppCloner code was copied.** It was used only as
   *architectural reference*.
2. **Next-Cloner's "engine" is a desktop toolchain** (`apktool` + `keytool` +
   `uber-apk-signer`, all JVM/desktop binaries). It cannot run on an Android
   device — which is precisely why Clone-Master's earlier apktool path never
   worked on-device. There is no reusable library inside Next-Cloner to port.
3. The **equivalent capability**, implemented clean-room with the same
   industry-standard open library this class of engine is built on
   (**org.smali:dexlib2, Apache-2.0**) is what was built here. dexlib2 is the
   same technology family (smali/baksmali/dex tools) Next-Cloner's apktool
   pipeline uses under the hood, but it is pure-JVM and runs on Android.

## 1. What changed

### New: `core/…/cloner/dex/DexPackageRewriter.kt` (dexlib2-based full DEX rebuild)
Replaces the in-place `DexStringPatcher` in the engine (the patcher remains as a
tested utility, no longer used by the build):

| Old (in-place patcher) | New (DexPackageRewriter) |
|---|---|
| string replaced only if it FIT byte-wise | **string pool fully rebuilt** → any length |
| "NOT FITTED" → original string left | **no NOT FITTED exists** |
| types/classes NEVER moved | **type descriptors renamed** (`Lpkg/X;` → `LclonePkg/X;`) with all field/method/type/annotation references updated |
| const-string (21c/31c) operands not rebuilt | **rewritten** + encoded string values |
| checksums hand-fixed (offsets unchanged) | **whole dex re-serialized** by DexPool: header/map/ids/offsets/checksum regenerated |
| `HIDDEN: off-is-ok` | fails clearly if a dex cannot be parsed |

Rewrite rules (evidence-based, minimal): exact original package; exact
authorities; prefix rule (`"pkg."`, `"pkg/"`, `"pkg:"`, `"pkg_"` → new package +
remainder); type descriptors only in the original package path (third-party
types untouched).

### Manifest: component awareness (`ManifestCloner`)
Because classes now really MOVE to the clone package, the manifest is updated
coherently: package attribute, **absolute component names**
(application/activity/activity-alias/service/receiver/provider/instrumentation
`android:name`), `android:process` package prefixes, provider authorities
(`;`-lists), sharedUserId removal. Relative names (`.Foo`) resolve against the
new package automatically — no change needed.

### New semantic validation (`ApkValidator`)
Every manifest component (absolute or relative-resolved) **must resolve to a
class present in the DEX** after the rename — catches the
"ClassNotFoundException at launch / invalid component" class of failures that
pure ZIP/CRC/signature checks cannot.

### Binary XML bug fixed during this work (found via aapt, real)
The AXML string-pool chunk was **not padded to a multiple of 4 bytes**; aapt
reported `AndroidManifest.xml is corrupt` ("XML size … not on an integer
boundary") whenever the pool data length was odd — a genuine
install-rejection condition on strict parsers. The writer now pads the pool
(data offsets unchanged) → `aapt dump badging/xmltree` parse cleanly.

### Housekeeping
Removed the leftover `PAIR-DBG` println in `V2Scheme` (flagged in the audit).

## 2. Capability comparison (implementation evidence)

| Capability | Next-Cloner (RE workspace) | Clone-Master OLD engine | Clone-Master NEW engine |
|---|---|---|---|
| APK extraction | apktool decode (desktop) | in-memory parse (ZipIO) | in-memory parse (ZipIO) |
| Manifest transformation | apktool (text) → smali-era | custom AXML parse/rewrite | custom AXML parse/rewrite + component names/processes |
| DEX transformation | smali edit + `apktool b` (desktop only) | in-place string patch (**NOT FITTED** on growth) | **full dexlib2 rebuild** (any length, types move) |
| Multidex | yes (desktop) | per-file patching | per-file full rebuild (verified: 19 files on real APK) |
| Resource handling | apktool/aapt res decode+rebuild | resources.arsc untouched | unchanged (documented limitation, see §4) |
| APK reconstruction | apktool build | custom ZIP writer | custom ZIP writer (same, verified) |
| Alignment | zipalign (after build) | embedded in writer (4 / 16384 libs) | same (unchanged) |
| Signing | keytool + uber-apk-signer | custom v2 signer | same (unchanged, Google-apksigner-verified) |
| Validation | none | structure/CRC/sig | **+ semantic component-class check** |
| Installation verification | manual | NOT verified (honest UI) | still device-only (honest UI; PackageInstaller status surfaced) |
| Error handling | script fails | fail-closed | fail-closed |
| **Runs on Android device** | ❌ (JVM tools) | ✅ | ✅ |

## 3. Verification performed (this environment)

**Unit (37 tests, 0 failures)** — incl. new:
- `DEX rebuild handles LONGER package names — classes move, no NOT FITTED`
- `full clean clone — longer clone package, multidex, all checks pass`
- `manifest transform rewrites package, authorities AND absolute component names`
- `missing component class is caught by semantic validation`
- all previous alignment / 16 KB libs / stale-signature / determinism / v2 tests.

**Real APK (Clone-Master's own 21-dex, 7.4 MB → longer clone**
`com.clonemaster.clone12345.longer`):
```
aapt dump badging      -> package: name='com.clonemaster.clone12345.longer' ✓
aapt dump xmltree     -> all activities/services/providers parse ✓
apksigner verify      -> v2 scheme: true ✓
zipalign -c 4         -> OK ✓
DEX rewrite           -> 326 strings + 328,300 type references, 9,224 classes,
                         19 dex files rewritten, 0 errors
residual old strings  -> 0 occurrences of Lcom/clonemaster/ in rewritten dex
determinism           -> byte-identical across two processes (persisted key)
build time            -> ~9.4 s for 8.8 MB output (real work now: 21 dex
                         parsed+rewritten; no artificial delays)
```

## 4. Honest remaining limitations (not hidden)

1. **resources.arsc is still byte-copied** — its table package-name (a UTF-16
   string inside the header) keeps the ORIGINAL package. This is what
   mainstream `aapt2 --rename-manifest-package` workflows produce too, so it is
   install-compatible; package-based `Resources.getIdentifier(name,type,origPkg)`
   lookups may fail. A ResTable reader/writer is the next upgrade if required.
2. **v2-only signature** (Android 7.0+; matches minSdk 24).
3. **Split APK / App Bundle apps** are cloned from the base APK only.
4. **Device installation is not verifiable from this sandbox** — the test APK is
   published for the on-device run (mark.via.gp → mark.via.gp.clone1 case).
5. The RE workspace used only as reference; the new engine is independent
   implementation (clean-room, Apache-2.0 dexlib2).

## 5. Acceptance-criteria status (as far as provable here)

- [x] Source APK analyzed (real 21-dex APK)
- [x] Package identifier transformed (longer name verified via aapt)
- [x] Package-dependent references transformed (326 strings + 328K types; 0 residual)
- [x] DEX changes with LONGER strings (full pool rebuild; no NOT FITTED)
- [x] DEX structure valid (dexlib2 re-serialized; 9,224 classes)
- [x] Manifest processed (package + absolute component names + authorities)
- [x] Resources preserved (byte-copy, documented)
- [x] APK reconstructed (custom ZIP writer, verified)
- [x] Alignment (zipalign OK; 4 / 16384 libs)
- [x] Signing (apksigner v2 verified)
- [x] Post-build validation (structure + semantic components)
- [ ] Install on real Android device  ← **needs device** (test APK published)
- [ ] Launch on real Android device   ← **needs device**
- [ ] Clone functionality retained     (clean-clone mechanics; optional-feature injection is a later phase — reported honestly)
- [x] Useful diagnostics when transformation cannot proceed (fail-closed)
