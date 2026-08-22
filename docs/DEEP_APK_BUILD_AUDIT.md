# DEEP AUDIT — How Clone Master Actually Builds APKs

Date: 2026-08-22 · Audit-only task: **no implementation code was changed**.
Evidence tags used throughout:
- **VERIFIED FROM CODE** — traced in the repository at the stated file/line.
- **VERIFIED BY MEASUREMENT/TEST** — ran the pipeline in this environment.
- **INFERRED** — deduced from Android platform behavior; not testable here.
- **UNKNOWN** — no evidence available in this environment.

Repository state at audit: `c5aa199` (main).

---

## A. Current APK pipeline — exact source-level execution flow

```
[UI] CloneOptionsActivity.showCloneSummaryAndBuild()        app/.../ui/CloneOptionsActivity.kt:707
     saves config (ConfigStorageManager), puts configJson into Intent
   → BuildProgressActivity.startBuild()                      app/.../ui/BuildProgressActivity.kt:98
     (Dispatchers.IO) cloneEngine.clone(config) { progress → UI }
   → CloneEngine.clone(config, onProgress)                   app/.../cloning/engine/CloneEngine.kt:40
     - creates workDir/decodedDir/buildDir under cacheDir (NOT used by the native path)
     - getApkPath(originalPackage) → sourceDir of the installed app
     - findApktool()  (CloneEngine.kt:324: $APKTOOL env, then PATH scan)
         ├── found  → apktool decode → ManifestTransformer → DexTransformer →
         │            ResourceTransformer → ... → SigningPipeline   (DESKTOP ONLY —
         │            apktool is a JVM process; this branch never executes on Android)
         └── NULL   → cloneNative(config, onProgress, apkPath)  CloneEngine.kt:331
   → cloneNative():
     1. apkPath.readBytes()                       — whole APK into memory
     2. CloneRequest(originalPackage, clonePackage, authorityMap = EMPTY,
        extraAssets = {"clone_config.json" → config JSON})
     3. AppCloneBuilder().build(apkBytes, request, loadOrCreateSignMaterial())
       (core/.../cloner/AppCloneBuilder.kt:63; see B)
     4. copy builder diagnostics into CloningDiagnostics; if builder threw → failure
     5. finalApk.writeBytes(product.apk) → getExternalFilesDir(null)/clones/<pkg>_<ver>.apk
     6. saveCloneConfig(config); Result.success(finalApk)
   → BuildProgressActivity: success → resultApk + honest "Build complete & validated …
     Installation not yet verified"; Install button → installApk(apk)
     (PackageInstaller session + result broadcast receiver)
```

**VERIFIED FROM CODE.** There is no JNI; the only `System.loadLibrary`
(`CloneMasterApp.kt:12`) is a guarded try/catch for a lib that is **not packaged**
(`app/src/main` contains 0 `.so` files) — clone building is 100% Kotlin/JVM.

---

## B. APK reconstruction mechanism — exactly how the final APK is generated

`AppCloneBuilder.build()` (core/.../cloner/AppCloneBuilder.kt:63) — 8 stages:

| # | Stage | Function | What happens |
|---|-------|----------|--------------|
| 1 | parse | `ZipIO.read(originalApk)` (:65) | locate EOCD + central directory; for each entry keep `name, method, crc, sizes, localHeaderOffset, dataOffset, raw bytes` |
| 2 | sanitize | `isStaleV1SignatureFile` filter (:72) | drop original app's `META-INF/MANIFEST.MF`, `*.SF`, `*.RSA/DSA/EC/SIG` (invalid after content change) |
| 3 | manifest | `BinaryXml.read` → `ManifestCloner().transform` → `BinaryXml.write` (:81-93) | parse binary AXML, rewrite package + authorities + remove sharedUserId, re-serialize (see C) |
| 4 | authorities | `planAuthorities(doc, request)` (:88, :164) | when no explicit map: for each manifest authority create `"cm" + 10 hex(SHA-256(old + clonePackage))` |
| 5 | DEX | `DexStringPatcher().patch(dexData.copyOf(), …)` per `classes*.dex` (:105-129) | in-place string-table patching (see E) |
| 6 | assets | `request.extraAssets` → `assets/<name>` additions (:131-135) | clone_config.json etc. |
| 7 | pack | `ZipIO().write(entries, replacements, additions)` (:138) | new ZIP container (see F) |
| 8 | sign | `V2Scheme.V2Signer(key, cert).sign(unsigned)` (:142) | APK Signature Scheme v2 block (see G) |
| 9 | gate | `ApkValidator().validate(signed, request)` (:146) | 13 checks; any failure → `error(...)` → build fails (see H) |

**Architecture verdict: `patch → repack` (in memory).** It is NOT
"extract → modify → rebuild" in the apktool/aapt2 sense:
- not EXTRACTED to disk (no file staging), all modifications happen on byte arrays;
- the ZIP **container** is rebuilt; `resources.arsc`, `res/*`, `assets/*`,
  `lib/*` remain **byte-identical** raw copies (verified: stored CRCs match);
- the manifest is genuinely re-serialized; DEX is patched in place (never rebuilt);
- no `aapt2`, `aapt`, `apktool`, `baksmali`, `smali`, `dexlib2` library or tool
  is invoked or linked (verified: none in `app/build.gradle.kts`, `core/build.gradle.kts`,
  or `core` imports — the DEX module imports only `java.util.zip`/`java.security`).

---

## C. Manifest transformation mechanism (AndroidManifest.xml)

Component: `core/.../cloner/axml/BinaryXml.kt` (399 lines) + `manifest/ManifestCloner.kt` (90 lines).

**Custom AXML parser + serializer** (no library):
- `BinaryXml.read()` (:118) parses the binary Android XML chunks: header `0x0003`,
  string pool `0x0001` (UTF-8 **and** UTF-16 source pools), resource map `0x0180`,
  namespace start/end `0x0100/0x0101`, start element `0x0102`
  (line, ns, name; 20-byte attribute units: ns@0, name@4, raw@8, size@12,
  res0@14, **dataType@15**, data@16), end element `0x0103` (ns/name captured),
  CDATA `0x0104`.
- `BinaryXml.write()` (:220) **reconstructs the whole XML structure**: string pool
  re-emitted as UTF-8 with length prefixes **and NUL terminators** (:372, required
  by apksig), resource map re-emitted covering the full pool, element chunks with
  real end-element ns/name (required by apksig — a bug fixed earlier).
  **Indices are preserved**: pool order is kept, new strings appended
  (`findString` :75), so attribute references stay valid (verified: Google `aapt`
  fully parses the output; `android:label=@0x7f010000` intact).
- `ManifestCloner.transform()` (:26): rewrites `package` via `setStringValue`
  (string-pool entry replaced), removes `sharedUserId`, rewrites each
  `android:authorities` value (supports `;`-separated lists); validates the
  package against `[a-zA-Z][a-zA-Z0-9_]*(…)+`, ≤ 100 chars → throws otherwise.

**Longer/shorter package name:** the string pool can grow freely (it is rebuilt,
not patched in place), so manifest package renames of any length are safe —
but only the manifest, see E for DEX.

---

## D. Resource transformation mechanism (resources.arsc)

- **VERIFIED FROM CODE: `resources.arsc` is NEVER modified.** It is copied
  byte-identically from the source APK (`ZipIO` raw-copy), and the validator only
  checks its presence, storageness, alignment and CRC (ApkValidator.kt:112-127).
- No `ResTable` parser/serializer exists anywhere in the project.
- Why: a package-ID/name clone arguably doesn't require resource recompilation —
  the manifest's resource references are numeric IDs (`@0x7f010000`) and are
  resolved by package-ID in `AssetManager`.
- **INFERRED risk (not validated):** the resource table's *package-name field*
  inside `resources.arsc` still holds the ORIGINAL package name. Name-based
  resource lookup (`Resources.getIdentifier(name, type, pkg)` — used by many
  apps) fails in the clone. **UNKNOWN:** whether any device rejects install for
  this (AAPT has `--rename-manifest-package` for exactly this purpose, and the
  standard expectation is that the table package is renamed together with the
  manifest; install-time impact is not verifiable in this environment).
- Consequence: icon/label/app-name changes, resource replacement, and any
  resource-table edit are **not implemented** on the native path.

---

## E. DEX transformation mechanism (classes.dex) — the heart of the matter

Component: `core/.../cloner/dex/DexStringPatcher.kt` (222 lines).

**Answer to #6: it is (B) in-place byte/string patching, not (A) true DEX
reconstruction.** There is no dexlib2/smali in the project (verified).

What `patch()` does, per DEX file:
1. read `string_ids_size/off` (offsets 56/60);
2. for each string_id: decode the `string_data_item`
   (`uleb128 utf16Len` + MUTF-8 bytes) → text;
3. find replacement: (a) exact authority, (b) exact original package,
   (c) prefix rule: text starting with `"origPkg."` → `"clonePkg." + suffix`
   (file paths, pref keys, provider names);
4. if `newItem.size (uleb + MUTF-8) <= original footprint` → **overwrite in place**
   + NUL-pad the remainder (all offsets remain valid);
   else → **NOT FITTED**, string left as the ORIGINAL (reported);
5. after all replacements: recompute **SHA-1** (bytes 32..EOF → 12..31) and
   **Adler-32** (bytes 12..EOF → 8..11) — this is the *only* structural fixup,
   because all offsets are preserved by design;
6. `countOrderViolations()`: counts `string_ids` whose strings are no longer in
   ascending UTF-16 order (DEX spec requirement) — ART tolerates this; reported
   honestly, not fixed.

**What is NEVER touched:** `type_ids`, `proto_ids`, `field_ids`, `method_ids`,
`class_defs`, `call_site_ids`, `method_handles`, code items, debug info,
annotations, the map/header — only string DATA bytes change, within existing
footprints.

**Multidex:** each `classesN.dex` is patched independently
(21 files in the 7.4 MB self-APK — VERIFIED BY MEASUREMENT).

**Why "NOT FITTED" happens:** a longer replacement (e.g.
`com.foo` → `com.foo.clone1`, or class names
`com.foo.ui.MainActivity$1`) cannot grow: `string_ids` offsets and the data
section positions of subsequent items would have to be rebuilt — exactly what an
in-place engine cannot do. Behavior afterwards: the original string remains in
that location; the clone keeps the ORIGINAL package string there.

**Fail-closed rule (already implemented):** if an **authority** replacement does
not fit in ANY dex → the build **throws** (`AppCloneBuilder.kt:120`) — the APK is
not produced (no silent corruption). If only hard-coded **package** strings do
not fit → warning only (`AppCloneBuilder.kt:124-126`), because the clone can
still function for apps without package-integrity checks.

**Measured on the real self-APK (Clone-Master 7.4 MB, 21 dex):**
`DEX_FILES=21 PATCHED=0 NOT_FITTED_LINES=4` — every `com.clonemaster.…` string is
longer than `com.clonemaster.clone1`'s prefix, so **zero** DEX strings changed;
on the small `com.example.hello` test APK the authority WAS patched
(`com.example.hello.provider` → `cm.…`, verified byte-count 0 old / 1 new).

---

## F. Packaging / alignment mechanism

Component: `core/.../cloner/apk/ZipIO.kt` (252 lines). Custom ZIP writer
(java.util.zip only for `Deflater` and `CRC32`).

- `read()` (:62): manual EOCD + central-directory walk; validates signatures,
  methods (STORED/DEFLATED only), Zip64 rejection; captures `dataOffset`
  (= localHeaderOffset + 30 + nameLen + extraLen) — the REAL data position used
  by the validator.
- `write()` (:119): new container; **entry order = source order** (deterministic);
  replacement entries → STORED; additions → DEFLATED; unchanged entries → **raw
  copy with original compression method** (no re-compression, no re-encoding);
  local headers: version 20, flags `0x0800` (UTF-8), mtime 0, mdate `0x21`
  (1980-01-01 → deterministic); CRC recomputed only for new entries; central
  directory re-emitted with recomputed offsets; EOCD with counts + CD offset.
- Alignment (:165-169): STORED entries padded via zero extra field —
  `lib/*` → **16384** (16 KB-page devices, Android 15+), other STORED → **4**,
  DEFLATED → none. **Correct order:** alignment is applied DURING packaging;
  signing inserts the v2 block BEFORE the central directory and cannot move
  entry data, so alignment survives signing. Verified with Google tools:
  `zipalign -c 4` PASS on the 942-entry clone; `apksigner verify` v2:true;
  the unit test asserts arsc `dataOffset % 4 == 0` for the exact case that used
  to false-fail.
- Limitations: no Zip64 (clear error), duplicates impossible (LinkedHashSet),
  timestamps fixed (determinism), alignment never re-runs after signing
  (not needed).

---

## G. Signing mechanism

Component: `core/.../cloner/sign/V2Scheme.kt` (321 lines) + `SigningKey.kt` (181 lines).

- Key: RSA-2048 generated from an HMAC-SHA256-derived seed
  (`deriveSeed`, 100k iterations); **X.509 certificate hand-rolled in DER**
  (no keytool): serial = SHA-256(public key) → deterministic; validity
  2026-01-01 → 2036-01-01; CN "Clone-Master Clone Signer".
- Signing: `V2Scheme.V2Signer.sign()` implements the real **APK Signature
  Scheme v2** block (algorithm 0x0103, RSA-PKCS1-SHA256):
  chunked content digest (1 MiB chunks: `0xA5 + u32(len) + data`; final:
  `0x5A + u32(chunkCount) + concat`) over `[prefix][centralDir][EOCD with
  cd-offset → block start]`; signed-data/cert/public-key/signatures encoded with
  the apksig-exact length-prefixed format (`lpElements`/`lpPairs`,
  no leading totals); block inserted before the central directory; EOCD cd-offset
  patched.
- `verify()` is a full independent re-check (same layout walking).
- **VERIFIED BY MEASUREMENT/TEST:** Google's official `apksigner verify
  --verbose` → "Verified using v2 scheme (APK Signature Scheme v2): true" on
  outputs of this signer (both the small test APK and the 942-entry self-clone).
- Key stability: `SignMaterial` is process-cached AND persisted on device
  (`filesDir/clone_signing/clone-key.p8`, `clone-cert.der`), so all clones from
  one installation share a signer → byte-identical rebuilds
  (verified: two fresh JVMs, persisted material → identical APKs).
- **v1 (JAR) signatures are not produced** — acceptable for minSdk 24
  (Android 7.0 supports v2), documented as a limitation.

---

## H. Validation mechanism — checked vs NOT checked

`core/.../cloner/apk/ApkValidator.kt` (177 lines). Every check below is
MANDATORY: any failure sets `errors` → `ok=false` → the build throws
(state machine fixed in d43e0b5 — no hidden failures).

Checked (VERIFIED FROM CODE, :53-143):
1. ZIP/EOCD structure, ≥1 entry, no duplicate names;
2. no stale META-INF v1 signature files;
3. AndroidManifest.xml present + binary AXML;
4. manifest `package` == `clonePackage`;
5. no original authority values used in the manifest;
6. all STORED entries aligned (4; lib/* → 16384) — against real data offsets;
7. `resources.arsc` present + STORED + 4-aligned;
8. classes*.dex present, valid magic;
9. stored-entry CRCs recomputed & matched;
10. no original authority strings remaining in any DEX;
11. v2 signature verifies.

**NOT checked (this is the core of §10):**
1. `resources.arsc` **contents**: never parsed — no check that the table's
   package-name equals the manifest package, no check that manifest-referenced
   resource IDs exist, no table-integrity validation beyond CRC
   (CRC only proves it is byte-identical to the SOURCE, i.e. source-valid);
2. DEX **semantics**: only magic + string search; no verifier/dex2oat pass, no
   check that class/method references resolve, no multidex class-presence check;
3. No aapt-level manifest validation (no aapt2 on device): exported flags,
   SDK consistency, referenced resources — not re-verified;
4. **No device/PackageManager checks**: authority conflicts with the device's
   installed packages, INSTALL_FAILED_* conditions, version/downgrade rules,
   UID conflicts, `INSTALL_FAILED_TEST_ONLY`, etc.;
5. No v1 signature presence (only relevant for Android <7 — out of scope);
6. No check that DEX string-table sort-order violations are acceptable on the
   target device (reported, tolerated — ART doesn't enforce, but UNVERIFIED
   across OEMs);
7. No check that hard-coded (non-fitted) package strings won't break the app;
8. No check of the final ZIP against a second independent parser beyond the
   standard entry validations.

---

## I. Installation verification gap — why internal validation ≠ installable

Two separate facts (both VERIFIED):

1. **No installation is ever performed by the pipeline.** The validator is
   purely a structural/signature checker running on a byte array. Nothing tests
   against the device PackageManager, dex2oat, or an Android runtime.
2. **The device's install attempt evidence is misread by our own UI.** The
   user's report `[Install] FAILED (UNKNOWN(-1))` comes from
   `BuildProgressActivity` receiver (:187-247): status `-1` falls into the
   `else` → `statusText(-1)` → `"UNKNOWN(-1)"` → "Install FAILED".
   **VERIFIED (AOSP `frameworks/base/core/java/android/content/pm/PackageInstaller.java`,
   line 451): `-1` is `STATUS_PENDING_USER_ACTION`** (added API 22), i.e. the
   *install session is waiting for user action* (typically the OEM install
   confirmation dialog) — **it is not a failure code at all**. The `else` branch
   contains every legitimate pending/streaming state.

   So the honest interpretation: the device said "waiting for your
   confirmation"; the UI printed "FAILED". The real final outcome
   (success/abort/failure) was not reported back to us, or the user acted on the
   wrong message.
   **INFERRED:** on many OEM builds the pending-user-action flow is a confirm
   dialog before install proceeds.
   **UNKNOWN:** whether the underlying APK would pass this device's real
   PackageManager validation (we cannot obtain it without the device).

Also note: the APK remains genuinely rejected in the EARLIER session
("App not installed" pre-v1.3.1, caused by the broken unzip fallback that this
pipeline replaced) — the current code path is different.

---

## J. Root architectural limitations (each supported by code analysis)

The current engine is a **limited custom patch/repack engine** and, concretely:

1. **DEX strings cannot grow** → a longer clone package name leaves original
   references (NOT FITTED) — class names, pref keys, resource names, broadcast
   actions (`com.x.CLONE_COMPLETE`), content URIs, file paths, BuildConfig. Apps
   with package-integrity/anti-cheat checks break. This is the single largest
   functional ceiling. (DexStringPatcher.kt — footprint-preserving by design.)
2. **No code injection** → hooks/features cannot be embedded into the clone on
   device (no smali, no dexlib2). The old apktool path synthesizes smali
   *stub* hooks (DexTransformer.kt:injectHookFramework) and can only run on a
   desktop with apktool installed. The native path injects **nothing**.
3. **resources.arsc is read-only** → no icon/label/app-name change, no resource
   re-mapping, table package name stays original (getIdentifier risk).
4. **Split APK / App Bundle apps are not truly supported**:
   `ApkParser` records `splitSourceDirs` but `CloneEngine.getApkPath()` uses
   `applicationInfo.sourceDir` only — a split app is cloned from its base APK
   alone (functionality loss, no merge).
5. **No Zip64** (>65 535 entries or >4 GB → hard error).
6. **No install-time or device-state validation** (see H/I) — authority
   collisions with already-installed apps, signature-key conflicts with an
   earlier clone of the same app under a different signer, etc., are only
   handled probabilistically by hash-derived authorities.
7. **Obfuscated/native apps**: string patching still works where strings fit,
   but there is no semantic undo of package checks, no reflective rewriting,
   no native-code patching whatsoever.
8. **Desktop apktool path is dead on device** and unsafe if ever reached
   (text-edit manifest transformer on a binary file) — it is correctly
   unreachable on Android (apktool cannot exist there), but it is a maintenance
   hazard.
9. **v2-only signing** (no v1 JAR) — fine for minSdk 24, and our loader is
   v2-capable, but any tooling that looks for `META-INF/*.SF` won't see a
   signature.
10. **Minor defect found (not fixed, per audit-only instruction):** a leftover
    debug `println("PAIR-DBG …")` in `V2Scheme.kt:159` prints on every
    validation — must be removed in the next change pass.

(Things the engine does well — verified: AXML parse/reserialize, short
deterministic authorities, byte-clean repack with correct alignment, apksig-exact
v2 signing, fail-closed validation, deterministic/reproducible output.)

---

## K. Recommended architecture (what a genuinely robust system needs)

Decision point after this report; recommended roadmap, ordered by impact:

1. **DEX: replace the in-place patcher with a real DEX rewriter (dexlib2).**
   dexlib2 (`org.smali:dexlib2`) is pure JVM → runs on Android. With
   `DexBackedDexFile` + `DexPool` + a `RewriterModule` we can rewrite strings
   (and even types/methods) with FULL pool rebuild — growing strings become
   possible, `NOT FITTED` disappears, sort order is restored, checksums/map are
   regenerated by the writer. This alone lifts ceiling #1 and #2 partially.
2. **Feature injection via DEX merge.** Compile Clone-Master's hook/support
   classes to a static `classes.dex` (Gradle), merge with dexlib2 into the
   clone's dex set and wire the Application class — real in-dex features without
   smali, on device.
3. **resources.arsc: implement a binary ResTable reader/writer.**
   Rewrite the table's package-name (UTF-16 string inside the table) together
   with the manifest rename, and — for the icon/label/app-name options —
   replace resource entries (image PNG/XML) or add `clone_*` entries. This is a
   well-bounded, well-documented binary format (ResourceTypes.h), ~1-2k LOC.
4. **Signing: keep the (verified) v2 signer; add v1 JAR signing**
   for completeness and older-device tooling; keep the persisted key.
5. **Validation: strengthen to "semantic":** parse the rebuilt arsc
   (package name matches; referenced IDs exist), run a manifest resource-ref
   check, and — the only real proof — **an install test**: PackageInstaller
   with CORRECT status semantics (treat `-1` = PENDING_USER_ACTION as
   "awaiting confirmation", only `STATUS_FAILURE_*` as failure), plus optional
   `adb install` when a host is attached. Capture `EXTRA_STATUS_MESSAGE`.
6. **Split-APK support:** merge base + splits (`splitSourceDirs`) before
   transformation, or reject split apps loudly up front.
7. **Housekeeping:** remove the standalone `PAIR-DBG` print; retire or fence the
   desktop-only apktool text path so it cannot silently corrupt a binary
   manifest; keep all existing regression tests, add a same-APK device install
   matrix.

---

## Answers to the numbered questions (compact)

1. **Components:** see tables A–G (exact files/functions listed).
2. **Real flow:** `showCloneSummaryAndBuild → startBuild → CloneEngine.clone →
   cloneNative → AppCloneBuilder.build → BinaryXml.read/write →
   ManifestCloner.transform → planAuthorities → DexStringPatcher.patch →
   ZipIO.write → V2Scheme.sign → ApkValidator.validate → writeBytes → Result`.
3. **"Native clone build" means:** *no-external-tools, in-memory, pure-Kotlin
   patch/repack* — custom ZIP reader/writer, custom AXML parser, custom DEX
   string patcher, custom v2 signer; it does NOT extract to disk the way apktool
   does, does not rebuild resources, does not rebuild DEX (only patches bytes).
4. **Manifest:** custom binary-AXML parser + serializer (full structure
   reconstruction, pool re-emitted with NUL terminators); package can grow/shrink
   freely.
5. **resources.arsc:** untouched byte-copy; only presence/stored/align/CRC
   checked; no table edits; package-name field mismatch risk (INFERRED).
6. **dex:** (B) in-place byte/string patching (+ a manifest that is genuinely
   re-serialized, and a ZIP container that is genuinely rebuilt — so overall
   hybrid, but DEX itself is patching, not reconstruction). String tables:
   read via uleb/MUTF-8 decode; lengths handled by footprint-fit rule; offsets
   stable by design; type/method/field IDs & class defs untouched; multidex via
   per-file patching; "NOT FITTED" = would grow → left original → warned.
7. **APK creation:** custom writer — entries raw-copied or replaced, STORED
   aligned (4/16384) via zero extra field, deterministic metadata, CRC
   recomputed for new entries, central directory + EOCD rebuilt, then v2 block
   inserted, then EOCD offset patched. Architecture = **patch → repack**.
8. **Real rebuild engine?** **NO — a limited custom patch/repack engine.**
   Evidence: no aapt2/apktool/smali/dexlib2 on device, DEX strings cannot grow,
   resources.arsc is never touched, no code generation.
9. **~1 second, measured:** 7.4 MB / 942 entries / 21 dex →
   **994–1318 ms** in-process. Why: linear passes only — read bytes (~10 ms),
   ZIP parse (~ms), manifest parse+write (<10 ms), 21× dex inflate+scan
   (~0.2–0.4 s), memcpy repack (~20 ms), SHA-256 (~30–60 ms), RSA sign/verify
   (~5 ms). No resource rebuild, no DEX rebuild, no dexopt, no smali — the speed
   is a direct consequence of shallow transformation, not clever optimization.
10. **Installation failure evidence re-assessed:** internal checks verified by
    Google tools (apksigner v2, zipalign, aapt) yet device said
    "UNKNOWN(-1)" — **-1 is AOSP STATUS_PENDING_USER_ACTION, not a failure**
    (our UI mislabels it). TRUE PackageManager verdict: UNKNOWN here; to obtain
    it: logcat `PackageInstaller`/`PackageManager` on device, or
    `adb install -r <clone.apk>` for the real `Failure [INSTALL_FAILED_*]`.
11. **Fundamental limits:** growing DEX strings, in-dex code injection, arsc
    editing, split-APK merge, Zip64, semantic/device install validation,
    anti-tamper/obfuscated apps, native-code patching (see J).

---

## FINAL QUESTION — plain answer

**What Clone Master currently uses to create a modified APK** is a compact,
custom **patch/repack engine** implemented in pure Kotlin: its own binary-XML
manifest parser/serializer (`BinaryXml` + `ManifestCloner`), an **in-place DEX
string-table patcher** (`DexStringPatcher` — only strings that fit are changed),
its own ZIP writer with alignment (`ZipIO`), its own X.509 + APK Signature
Scheme v2 signer (`SigningKey` + `V2Scheme`), and a structural validator
(`ApkValidator`).

**Is it capable of reliably cloning modern APKs?** For the narrow case of
package-renaming a typical, already-valid APK whose DEX strings fit, it produces
output that passes Google's own tooling (verified here). But it is **not a
general-purpose APK transformation/rebuild engine**: it cannot rebuild DEX
(no string growth), cannot inject code or features into the clone's DEX, cannot
modify resources.arsc (no icon/label/name changes, table package name stays
original), does not handle split APKs or Zip64, and cannot prove installability
(no device-side validation; the last device signal was a misread
pending-user-action status).

**A substantially more complete APK transformation/rebuild engine is required**
for reliable cloning of modern apps — the pragmatic path being:
dexlib2-based full DEX rewriting (and DEX merge for feature injection), a binary
ResTable reader/writer for resources, optional v1+v2 signing, semantic
validation, and a real install-verification step. Open the roadmap in section K
and the acceptance tests before deciding the next implementation step.
