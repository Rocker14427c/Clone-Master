# DexProtector Decryption Guide for Next-Cloner

**Date:** 2026-08-23  
**Status:** Research Complete — Tools Identified

## Overview

The Next-Cloner repository contains a decompiled App Cloner v3.6.8 protected by **DexProtector**. While the static decompilation (jadx/apktool) only reveals encrypted stubs, the actual DEX code **can be extracted at runtime** using Frida-based tools.

## How DexProtector Works

1. **Encrypts** the original `classes.dex` and stores it in `assets/`
2. At runtime, `libdexprotector.so` **decrypts** the DEX into `/data/data/[package]/.cache/`
3. The decrypted DEX is loaded into memory
4. After loading, the decrypted file is **deleted** from disk
5. The app runs with the decrypted DEX in memory

**Key Insight:** The decrypted DEX exists in memory (and briefly on disk) before deletion. We can intercept it.

---

## Method 1: clsdumper (Recommended — Latest Tool)

**Repository:** https://github.com/TheQmaks/clsdumper  
**Features:** 9 extraction strategies, anti-Frida bypass, specifically tested against DexProtector

### Requirements
- Rooted Android device (physical or emulator)
- Python 3.10+
- Frida server running on device
- USB debugging enabled

### Installation
```bash
pip install clsdumper
```

### Usage
```bash
# Install the Next-Cloner APK on your rooted device
adb install original/AppCloner.apk

# Dump DEX from the running app
clsdumper com.applisto.appcloner --spawn --extract-classes

# Or attach to running app
clsdumper com.applisto.appcloner --strategies fart_dump,memory_scan
```

### Output
```
dump_com.applisto.appcloner/
  dex/
    classes_001.dex    # Dumped DEX files (9+ files expected)
    classes_002.dex
    ...
  classes/             # Extracted individual classes
    com/applisto/appcloner/...
  metadata.json
```

### Strategies (9 total)
1. **art_walk** — Walks ART Runtime → ClassLinker → DexFile structs
2. **open_common_hook** — Hooks `DexFile::OpenCommon` in libdexfile.so
3. **memory_scan** — Scans readable memory for DEX magic bytes
4. **cookie** — Reads mCookie field from ClassLoaders
5. **classloader_hook** — Monitors `loadClass` / `DexClassLoader`
6. **mmap_hook** — Intercepts mmap/mmap64 calls
7. **oat_extract** — Parses .vdex/.oat files
8. **fart_dump** — Hooks DefineClass + walks class_table (best coverage)
9. **dexfile_constructor** — Hooks OatDexFile C++ constructors

**Recommendation:** Start with `fart_dump` + `memory_scan` for best coverage.

---

## Method 2: Android_Dump_Dex (DexProtector-Specific)

**Repository:** https://github.com/Alexjr2/Android_Dump_Dex  
**Features:** Specifically detects DexProtector and dumps DEX

### Installation
```bash
git clone https://github.com/Alexjr2/Android_Dump_Dex
cd Android_Dump_Dex
```

### Usage
```bash
# Edit DumpDex.js to set the target package
# Line 10: let Pro = "com.applisto.appcloner";

# Spawn and dump
frida -U -f com.applisto.appcloner -l DumpDex.js --no-pause
```

### Output
```
[*] DexProtector Found : https://dexprotector.com/
[*] Dumped classes1.dex to /storage/emulated/0/Android/data/com.applisto.appcloner/classes1.dex
[*] Dumped classes2.dex to /storage/emulated/0/Android/data/com.applisto.appcloner/classes2.dex
...
```

---

## Method 3: frida-dexdump (Classic Approach)

**Repository:** https://github.com/hluwa/frida-dexdump  
**Features:** Scans process memory for DEX magic bytes

### Usage
```bash
# Install frida-dexdump
pip install frida-dexdump

# Dump from foreground app (USB-connected device)
frida-dexdump -FU

# Or specify package
frida-dexdump -U -f com.applisto.appcloner
```

---

## Method 4: DexIntercept (File-Based)

**Source:** https://www.worldofiptv.com/threads/dexintercept-dexprotector-bypass.9179/  
**Features:** Uses inotify to catch decrypted DEX files before deletion

### How It Works
1. DexProtector decrypts DEX to `/data/data/[package]/.cache/`
2. DexIntercept monitors this directory with inotify
3. When the decrypted file appears, it's copied before deletion
4. You get the decrypted DEX file

### Requirements
- Rooted device
- Custom inotify-await binary for Android
- Manual setup (script not publicly available)

---

## Method 5: DexPwn (File Stealing)

**Repository:** https://github.com/Shabbypenguin/DexPwn  
**Features:** Uses inotify to steal protected files from DexProtector

### Usage
```bash
git clone https://github.com/Shabbypenguin/DexPwn
cd DexPwn

# Build and push to rooted device
make
adb push dexPwn /data/local/tmp/

# Run
adb shell "su -c '/data/local/tmp/dexPwn com.applisto.appcloner /sdcard/dumped/'"
```

---

## Step-by-Step Workflow (Recommended)

### Prerequisites
1. **Rooted Android device** (physical or emulator like Genymotion)
2. **Frida server** installed and running:
   ```bash
   # Download frida-server for your device architecture
   wget https://github.com/frida/frida/releases/download/16.1.5/frida-server-16.1.5-android-arm64.xz
   xz -d frida-server-16.1.5-android-arm64.xz
   adb push frida-server-16.1.5-android-arm64 /data/local/tmp/frida-server
   adb shell "chmod 755 /data/local/tmp/frida-server"
   adb shell "su -c '/data/local/tmp/frida-server &'"
   ```

3. **Install Next-Cloner APK:**
   ```bash
   adb install Next-Cloner-ref/original/AppCloner.apk
   ```

### Extraction
```bash
# Method 1: clsdumper (best coverage)
pip install clsdumper
clsdumper com.applisto.appcloner --spawn --strategies fart_dump,memory_scan,art_walk --extract-classes

# Method 2: Android_Dump_Dex (DexProtector-specific)
git clone https://github.com/Alexjr2/Android_Dump_Dex
cd Android_Dump_Dex
# Edit DumpDex.js: let Pro = "com.applisto.appcloner";
frida -U -f com.applisto.appcloner -l DumpDex.js --no-pause
```

### Post-Processing
```bash
# You'll get multiple DEX files (classes_001.dex, classes_002.dex, etc.)
# Merge them or analyze individually

# Decompile with jadx
jadx dumped_dex/classes_001.dex -o decompiled_source/

# Or convert to JAR
d2j-dex2jar classes_001.dex -o output.jar

# Open in JADX-GUI
jadx-gui classes_001.dex
```

---

## What You'll Get

After successful decryption, you'll have access to:

### ✅ Cloning Engine Code
- APK parsing and transformation logic
- Manifest rewriting implementation
- DEX string pool manipulation
- Resource transformation
- Signing pipeline

### ✅ Feature Hook Implementations
- `IdentityManager.Hooks.install()` — Android ID, IMEI, MAC spoofing
- `PrivacyManager.Hooks.install()` — Screenshot prevention, stealth mode
- `DisplayCustomizer.Hooks.install()` — Dark mode, orientation lock
- `StorageIsolation.Hooks.install()` — External storage redirection
- `ProxyManager.Hooks.install()` — Per-clone SOCKS proxy
- `MediaControls.Hooks.install()` — Camera/mic disable, fake camera
- `NavigationControls.Hooks.install()` — Floating back, kiosk mode
- `LaunchManager.Hooks.install()` — Secret dialer code, quick tile
- `NotificationManager.Hooks.install()` — Filtering, quiet hours
- `GameFeatures.Hooks.install()` — OBB handling, key mapper
- `TvWearManager.Hooks.install()` — TV banner, joystick pointer
- `AutomationEngine.Hooks.install()` — Brightness, DND, WiFi toggles
- `DeveloperTools.Hooks.install()` — Logcat viewer, WebView inspection

### ✅ Business Logic
- Clone configuration handling
- Option registry and state management
- Preset management
- Batch cloning logic
- Compatibility analysis

### ✅ UI Code
- Activities, fragments, view models
- RecyclerView adapters
- Option configuration UI
- Clone management screens

---

## Integration Into Clone-Master

Once you have the decrypted DEX files:

1. **Decompile with jadx:**
   ```bash
   jadx classes_001.dex -o next_cloner_decrypted/
   ```

2. **Extract relevant packages:**
   ```bash
   # Clone engine
   cp -r next_cloner_decrypted/sources/com/applisto/appcloner/cloning/ /tmp/
   
   # Feature hooks
   cp -r next_cloner_decrypted/sources/com/applisto/appcloner/hooks/ /tmp/
   
   # UI
   cp -r next_cloner_decrypted/sources/com/applisto/appcloner/activity/ /tmp/
   ```

3. **Port to Kotlin** (or keep as Java):
   - Clone-Master uses Kotlin, so you may want to convert
   - Or keep the Java code and integrate as-is (Android supports both)

4. **Replace empty implementations:**
   - Copy the decrypted hook implementations
   - Replace the empty `Hooks.install()` methods in Clone-Master
   - Adapt to Clone-Master's architecture (config models, etc.)

---

## Legal & Ethical Considerations

⚠️ **Important:**
- App Cloner is a **commercial product** with a license
- Decrypting protected code may violate:
  - App Cloner's Terms of Service
  - DMCA (Digital Millennium Copyright Act)
  - Local copyright laws
- This guide is for **educational and research purposes only**
- You should only decrypt apps you own or have explicit rights to modify
- Clone-Master is an **independent implementation** — using decrypted code from App Cloner may create licensing issues

### Recommended Approach
Instead of copying decrypted code, use it as **reference** to understand:
- How features are implemented
- What Android APIs are used
- What the hooking patterns look like

Then **reimplement** the features in Clone-Master using your own code, inspired by the reference but not copied verbatim.

---

## Tools Summary

| Tool | Approach | DexProtector Support | Ease of Use | Coverage |
|------|----------|---------------------|-------------|----------|
| **clsdumper** | Frida + 9 strategies | ✅ Explicit | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Android_Dump_Dex** | Frida + DexProtector detection | ✅ Explicit | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **frida-dexdump** | Frida + memory scan | ✅ Generic | ⭐⭐⭐⭐ | ⭐⭐⭐⭐ |
| **DexIntercept** | inotify file capture | ✅ Explicit | ⭐⭐ | ⭐⭐⭐ |
| **DexPwn** | inotify file stealing | ✅ Explicit | ⭐⭐⭐ | ⭐⭐⭐ |

**Recommendation:** Start with **clsdumper** — it's the most modern, comprehensive, and explicitly tested against DexProtector.

---

## Next Steps

1. **Set up rooted Android environment** (physical device or emulator)
2. **Install Frida server** on the device
3. **Install Next-Cloner APK** on the device
4. **Run clsdumper** to extract decrypted DEX files
5. **Decompile with jadx** to get Java source
6. **Review the decrypted code** to understand implementations
7. **Port relevant features** to Clone-Master (as reference, not direct copy)
8. **Test thoroughly** on multiple devices and Android versions

---

## References

- **clsdumper:** https://github.com/TheQmaks/clsdumper
- **Android_Dump_Dex:** https://github.com/Alexjr2/Android_Dump_Dex
- **frida-dexdump:** https://github.com/hluwa/frida-dexdump
- **DexPwn:** https://github.com/Shabbypenguin/DexPwn
- **DexProtector Documentation:** https://licelus.com/products/dexprotector/docs/android/introduction-to-dexprotector
- **AWAKE Packers Guide:** https://zahidaz.github.io/awake/packers/

---

**Note:** This guide is for educational purposes. Always respect software licenses and copyright laws. Clone-Master should be an independent implementation, not a copy of proprietary code.
