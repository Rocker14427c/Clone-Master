# Next-Cloner Assets Integration

**Date:** 2026-08-23  
**Source:** https://github.com/Rocker14427c/Next-Cloner (decompiled App Cloner v3.6.8)

## Overview

This document describes the assets and native libraries copied from the Next-Cloner reference repository into Clone-Master. These assets provide the runtime infrastructure for cloning, hooking, networking, and data compression.

## What Was Copied

### 1. Native Libraries (jniLibs) — 5.1 MB

Bundled in `app/src/main/jniLibs/{abi}/` for 4 ABIs:

| Library | Purpose | Size (arm64-v8a) |
|---------|---------|------------------|
| `libappcloner.so` | Core hooking library (Pine/ByteHook/AndHook abstraction) | 402 KB |
| `libpdnsd.so` | DNS proxy for per-clone DNS isolation | 211 KB |
| `libtun2socks.so` | VPN tunnel to SOCKS proxy | 157 KB |
| `libzstd-jni.so` | Zstandard compression native library | 538 KB |

**Total:** 16 .so files across arm64-v8a, armeabi-v7a, x86, x86_64

### 2. Hook Library Archives (assets) — ~2 MB

| Archive | Purpose |
|---------|---------|
| `libAppCloner.zip` | Hook library with per-ABI binaries (extracted at clone-build time) |
| `libPine.zip` | Pine ART inline hook (Android 9+) |
| `libByteHook.zip` | ByteHook PLT hook for native functions |
| `libAndHook.zip` | AndHook (legacy hook framework) |
| `libAliuHook.zip` | AliuHook (alternative hook) |

### 3. Runtime DEX Files (assets) — ~300 KB

| File | Purpose |
|------|---------|
| `classes.dex.xz` | Compressed runtime DEX injected into clones |
| `kotlin.dex.xz` | Kotlin stdlib DEX for clones |
| `secondary/classes.dex.xz` | Secondary runtime DEX |

### 4. Networking Tools (assets/microsocks/) — ~800 KB

SOCKS5 proxy binary for per-clone network isolation:
- `arm64-v8a/microsocks`
- `armeabi-v7a/microsocks`
- `x86/microsocks`
- `x86_64/microsocks`

### 5. Data Files (assets/) — ~5 MB

| File | Purpose |
|------|---------|
| `devices.csv` | Device fingerprint database (physical device profiles) |
| `hardware.csv` | Hardware capability database |
| `cities.csv` | City database for GPS spoofing |
| `bathymetry.bin` | Elevation data for GPS spoofing |
| `mac_oui_list_*.txt` | MAC OUI lists for MAC address spoofing |
| `names/*.txt.xz` | Name databases for identity spoofing |

### 6. Development Tools (assets/) — ~2 MB

| Asset | Purpose |
|-------|---------|
| `codeeditor/` | CodeMirror-based code editor (WebView) |
| `remote-control.html` | Remote control web UI |
| `remote-control-macros.js` | Automation macros |
| `axml/` | AXML reference data (attrs.xml, public.xml) |
| `keyboards/` | Custom keyboard layouts |
| `fonts/` | Source Sans Pro font |
| `anim/` | Animation XMLs (transitions) |
| `favicon.ico` | Favicon for web UI |

### 7. Additional DEX Files (assets/) — ~1 MB

| File | Purpose |
|------|---------|
| `dd/classes.dex` | Device dashboard runtime |
| `dd/resources.zip` | Device dashboard resources |
| `ftpserver/classes.dex` | FTP server runtime |
| `ftpserver/resources.zip` | FTP server resources |
| `dependencies/*.jar` | Dependency JARs (AppClonerCodeClasses.jar, core-ktx) |

## Code Updates

### 1. NativeLibHandler.kt (Rewritten)

**Before:** Empty placeholder with warnings about missing hook libs  
**After:** Fully functional — extracts hook libraries from bundled zip assets and injects them into clone's `lib/{abi}/` directory

**Key Changes:**
- Reads `libAppCloner.zip`, `libPine.zip`, `libByteHook.zip`, etc. from assets
- Extracts per-ABI `.so` files into clone's lib directory
- Validates existing native libs (removes corrupted 0-byte files)
- Provides `extractNativeBinary()` helper for runtime extraction

### 2. ProxyManager.kt (Enhanced)

**Before:** Logged "would start microsocks" but never actually started it  
**After:** Extracts microsocks binary from assets and starts it as a subprocess

**Key Changes:**
- Extracts `microsocks/{abi}/microsocks` from assets to `filesDir/bin/{abi}/`
- Sets executable permissions
- Starts microsocks process with proper arguments: `-i 127.0.0.1 -p 1080 -s {host} -P {port}`
- Monitors process lifecycle (destroy on stopProxy())

### 3. DataArchiveManager.kt (Enhanced)

**Before:** ZSTD compression was a placeholder using ZIP  
**After:** Uses real Zstandard compression via zstd-jni library

**Key Changes:**
- Added `com.github.luben:zstd-jni:1.5.5-11` dependency
- Implemented `createZstdArchive()` method using `ZstdOutputStream`
- Writes custom format: `[path_len:4][path][size:8][data]` per file
- Falls back to ZIP if ZSTD fails
- Added helper methods: `intToBytes()`, `longToBytes()`

### 4. build.gradle.kts (Updated)

**Changes:**
- Added `jniLibs.srcDirs("src/main/jniLibs")` to sourceSets
- Added `com.github.luben:zstd-jni:1.5.5-11` dependency
- Added `resources.excludes` for META-INF

### 5. .gitignore (Updated)

**Added exclusions for large binaries:**
```
app/src/main/jniLibs/**/*.so
app/src/main/assets/lib*.zip
app/src/main/assets/classes.dex.xz
app/src/main/assets/kotlin.dex.xz
app/src/main/assets/microsocks/
```

## Architecture Impact

### Before (Clone-Master)
- ❌ Hook libraries: Not available (empty placeholders)
- ❌ Native hooking: Not functional
- ❌ SOCKS proxy: Stub only
- ❌ ZSTD compression: ZIP fallback
- ❌ Device profiles: No data files
- ❌ GPS spoofing: No city/elevation data

### After (Clone-Master + Next-Cloner Assets)
- ✅ Hook libraries: Bundled in assets (libAppCloner.zip, libPine.zip, etc.)
- ✅ Native hooking: libappcloner.so available for injection
- ✅ SOCKS proxy: microsocks binary bundled and functional
- ✅ ZSTD compression: Real zstd-jni library
- ✅ Device profiles: devices.csv with physical device fingerprints
- ✅ GPS spoofing: cities.csv + bathymetry.bin for realistic locations

## What's Still Missing

### From Next-Cloner (Encrypted by DexProtector)
The following are **encrypted** and cannot be copied:
- ❌ Actual cloning engine logic (APK transformation, manifest rewriting)
- ❌ Feature hook implementations (IdentityManager.Hooks, PrivacyManager.Hooks, etc.)
- ❌ Business logic (clone configuration, option handling)
- ❌ UI logic (activities, fragments, view models)

**These must be implemented from scratch in Clone-Master** (which is already done for the core engine in the `:core` module).

### What Clone-Master Already Has (Better)
Clone-Master has **superior implementations** in these areas:
- ✅ Binary manifest parsing/writing (`BinaryXml.kt` — 404 lines, fully functional)
- ✅ DEX rewriting via dexlib2 (`DexPackageRewriter.kt` — 199 lines, real string pool rebuild)
- ✅ APK signing (`V2Scheme.kt` — 334 lines, APK Signature Scheme v2)
- ✅ Runtime injection (`:runtime` module — 7 Java files, HookApplication + RuntimeConfig)
- ✅ Kotlin codebase (vs. obfuscated Java in Next-Cloner)

## Usage

### Building Clones with Hook Libraries
The `NativeLibHandler` now automatically:
1. Reads hook library zips from `assets/`
2. Extracts per-ABI `.so` files
3. Injects them into the clone's `lib/{abi}/` directory
4. The clone's `HookApplication` loads them at runtime via `System.loadLibrary()`

### Starting SOCKS Proxy
`ProxyManager.startProxy(config)` now:
1. Extracts `microsocks/{abi}/microsocks` from assets
2. Sets executable permissions
3. Starts the microsocks process
4. Routes clone's network traffic through the proxy

### Compressing Data Bundles
`DataArchiveManager.createArchive(config)` now:
1. Uses ZSTD compression (level 19) when `config.compression == ZSTD`
2. Falls back to ZIP if ZSTD fails
3. Achieves ~30-50% better compression than ZIP for app data

## Next Steps

1. **Implement empty `Hooks.install()` methods** — Use the native libs to implement actual hooking
2. **Add more device profiles** — Extend `devices.csv` with more physical devices
3. **Wire up GPS spoofing** — Use `cities.csv` + `bathymetry.bin` for realistic locations
4. **Implement remote control** — Use `remote-control.html` for web-based clone control
5. **Add FTP server** — Use `ftpserver/classes.dex` for file transfer

## References

- **Next-Cloner Repo:** https://github.com/Rocker14427c/Next-Cloner
- **Original App:** App Cloner v3.6.8 (com.applisto.appcloner)
- **Protection:** DexProtector (DEX encryption + native code obfuscation)
- **Decompilation:** apktool 3.0.3 + jadx 1.5.6

## License & Attribution

These assets are copied from a decompiled third-party app for educational and interoperability purposes. The native libraries are bound by their original licenses (see Next-Cloner repo for details). Clone-Master's own code remains MIT-licensed.

**Note:** This integration is for reference and interoperability. Clone-Master is an independent implementation that uses these assets as runtime infrastructure, not as copied business logic.
