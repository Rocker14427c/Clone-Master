# Clone-Master

**A complete Android application-cloning / modification platform** – built as a successor to App Cloner with modular, per-clone isolated subsystems.

> ⚠️ Educational / owner-rights purpose only. Only clone apps you own or have explicit rights to modify. Some features are limited by Android security model – this project degrades gracefully and reports limitations.

## What it does

Clone-Master generates a **genuinely separate installable APK** with isolated identity, storage and runtime config – not a launcher wrapper or simple package rename.

### Feature Groups (22 requirements implemented)

1. **Core Cloning** – APK parsing, manifest/resource/DEX transformation, package & provider authority rewriting, multi-clone coexistence, custom icons/badges, batch cloning, OBB support, backup/restore
2. **Identity & Fingerprint** – Android ID, GSF ID, Advertising IDs (Google/Amazon/Facebook), Wi-Fi/BT MAC, IMEI/IMSI (where permitted), WebView UA, GPU, SIM, build props, fingerprint profiles – all per-clone
3. **Privacy & Isolation** – password, stealth, calculator decoy, incognito keyboard, clear-on-exit, permission stripping, GPS spoof, sensors, root-hide, logcat disable, etc
4. **Display & UI** – status/nav bar colors, dark mode, rotation lock, custom locale/font, PiP, freeform, notch handling, etc
5. **View & Layout Mod** – runtime inspector, view search/hide/replace/restyle, persistent rules
6. **Media/Audio/HW** – mute/volume on start, disable camera/mic, fake camera with EXIF, audio focus, secondary display
7. **Navigation** – floating back, shake to exit, volume-key mapping, kiosk, activity blocking
8. **Storage** – SD install, external-storage redirect, isolate storage, bundle OBB/data, secure delete on exit
9. **Launching** – remove icon, secret dialer code, Quick Tile, battery spoof, home/camera/assistant roles, event triggers (S-Pen, headphones, NFC...)
10. **Networking** – disable mobile/background/off-screen, SOCKS/HTTP proxy per-clone, DNS-over-HTTPS, WebRTC leak protection
11. **Notification** – filtering, quiet time, custom vibration/LED/color, toast -> notification, opacity/position
12. **Game** – OBB bundling, key mapper, FPS monitor
13. **TV / Wear** – TV banner, joystick pointer, PiP, remove Wear components
14. **Automation** – brightness/DND/WiFi/BT toggles, clipboard on start, Tasker, shell hooks (where permitted), sequenced/conditional actions
15. **Developer** – Logcat viewer, Target SDK spoof, build-prop overrides, file/URL/header monitoring, WebView inspection/JS injection, native hooks (Pine/ByteHook/AndHook abstraction)
16. **WebView Toolkit** – inspect, source view, persistent rules, navigation override
17. **App Analysis** – package, versions, SDKs, components, permissions, libs, large-heap, biometrics, Firebase, cert checks
18. **AI-Assisted** – controller on top of engine: understands UI via view hierarchy + accessibility dump, suggests view-mod rules, privacy presets, compatibility fixes
19. **Clone Management UI** – installed apps, clones list, search across all options ("GPS", "proxy"...), favorites, profiles, import/export, batch ops
20. **Architecture** – APK parsing, manifest/resource/DEX/native handling, provider transformation, signing pipeline, diagnostics
21. **Reliability** – compatibility analyzer for cert validation, Play Services, login, billing, SafetyNet/Play Integrity, anti-tamper, hard-coded package names
22. **Engineering Principle** – never pretend; if Android restriction blocks a feature, report and degrade gracefully

## New: Environment Spoofing / Detection Mitigation (Added)
- Dedicated subsystem `com.clonemaster.environment` with RootHideManager, EmulatorHideManager, DeviceProfileManager, SystemPropertySpoofer, FileSystemSpoofer, DetectionDiagnostics, EnvironmentManager
- Per-clone toggles: Hide Root, Hide Emulator, Hide Developer Options, Hide USB/ADB, Hide Mock Location, Spoof Physical Device Profile
- 8 built-in physical device profiles (Pixel 8 Pro, Pixel 7a, S24 Ultra, A54, OnePlus 12, Xiaomi 14 Pro, Nothing Phone 2, Fold5) with coherent Build, Telephony, Sensors, Camera, GPU, Battery, Network
- Detection diagnostics screen showing root/emulator/QEMU/virtual/debug/mock/build/filesystem/hardware/sensor/telephony/network indicators with verified bypass status
- See docs/ENVIRONMENT_SPOOFING.md for full details

## Project Structure

```
Clone-Master/
├── app/
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/clonemaster/
│       │   ├── CloneMasterApp.kt
│       │   ├── cloning/engine/          # Core cloning pipeline
│       │   ├── identity/                # Per-clone identity spoofing
│       │   ├── privacy/                 # Privacy controls
│       │   ├── display/                 # UI customization
│       │   ├── viewmod/                 # View inspector & modifier
│       │   ├── media/                   # Camera/audio
│       │   ├── navigation/              # Nav controls
│       │   ├── storage/                 # Storage isolation
│       │   ├── launching/               # Launch modes
│       │   ├── networking/              # Proxy & net controls
│       │   ├── notification/            # Notification controls
│       │   ├── game/                    # OBB, keymapper, FPS
│       │   ├── tvwear/                  # TV/Wear
│       │   ├── automation/              # Automation framework
│       │   ├── developer/               # Hooks, logcat, WebView toolkit
│       │   ├── analysis/                # App analysis
│       │   ├── ai/                      # AI controller layer
│       │   └── ui/                      # Management UI
│       └── res/
├── core/                                # Pure JVM cloning library (testable without Android)
├── build.gradle.kts
├── settings.gradle.kts
└── docs/ARCHITECTURE.md
```

## Cloning Engine Pipeline

```
Source APK -> CompatibilityAnalyzer -> ApkParser (apktool + zip + binary xml)
-> ManifestTransformer (package rename, provider authorities, deep links)
-> ResourceTransformer (icon badge, string/app name, resource ID stability)
-> DexTransformer (package reference rewriting, provider authority strings, hard-coded pkg detection)
-> NativeLibHandler (keep, ABI filter)
-> ObbHandler (bundle/copy)
-> SigningPipeline (zipalign + apksigner)
-> Diagnostics + Install
```

Hooking runtime (inside generated clone) uses **libAppCloner.so** abstraction with pluggable backends:
- **Pine** (ART inline hook, Android 9+)
- **ByteHook** (PLT hook for native)
- **AndHook** (legacy)

All identity/privacy/display features are injected via **AppCloner Hook Framework** (`com.clonemaster.hooks`) which is merged into clone's dex at build time.

## Quick Start

```bash
bash setup_env.sh   # installs JDK 17, Android SDK, apktool, uber-apk-signer, enables 6GB swap
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

To build clone of an app from CLI (for testing core lib):

```bash
./gradlew :core:run --args="--input /path/to/source.apk --package com.example.clone1 --name 'Example Clone' --output /tmp/clone.apk --profile privacy"
```

## Swap (6GB) – already enabled in this environment

```bash
sudo fallocate -l 6G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
free -h
```

## Security Note on GitHub Token

If you provided a token for pushing, it is stored only in local git credential helper / env and **never committed**. See `.gitignore` includes `.env` and `keystore/`. After push, revoke or delete token from shell history.

## License

MIT – for your own apps. Respect original app licenses.
