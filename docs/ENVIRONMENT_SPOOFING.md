# Environment Spoofing / Detection Mitigation Subsystem

## Overview
Dedicated modular subsystem that provides per-clone root and emulator hiding, plus physical device profile spoofing with internal consistency. Compatible with Identity/Profile system.

## Per-Clone Toggles (Independently Configurable)
- **Hide Root** – `RootHideLevel`: OFF/BASIC/STANDARD/AGGRESSIVE
- **Hide Emulator** – `EmulatorHideLevel`: OFF/BASIC/STANDARD/FULL
- **Hide Developer Options**
- **Hide USB/ADB**
- **Hide Mock Location**
- **Spoof Physical Device Profile** – selects coherent profile (Pixel 8 Pro, S24 Ultra, etc)

## Root Detection Mitigation

### Detection
`RootHideManager.scanForRootIndicators()` detects:
- SU binary in 25+ paths: `/system/bin/su`, `/system/xbin/su`, `/sbin/su`, `/data/adb/magisk`, `/system/xbin/busybox`, etc.
- Root management apps: SuperSU, Magisk, KingUser, etc. (24 packages)
- Root cloaking apps (RootCloak, Xposed, etc.)
- Build tags `test-keys`
- Superuser.apk
- Magisk files: `/sbin/.magisk`, `/data/adb/magisk`, `/cache/.magisk`
- BusyBox binaries
- Writable system partition
- `Runtime.exec("su")` success
- Native checks via `access()`, `stat()`, `fopen()`

### Hiding
- `File.exists()` hook → false for SU_PATHS
- `PackageManager.getPackageInfo()` hook → `NameNotFoundException` for root apps
- `Runtime.exec()` hook → block `su`, `which su`, `busybox`, `magisk`
- `SystemProperties` hook: `ro.debuggable=0`, `ro.secure=1`, `ro.build.selinux=1`, `ro.build.type=user`, `ro.build.tags=release-keys`, `service.adb.root=0`
- `__system_property_get` PLT hook via ByteHook (libc.so)
- BufferedReader hook for `/proc/mounts`, `/proc/self/cgroup` filtering magisk
- `Build.TAGS` hook → `release-keys`

Compatibility reporting when cannot intercept (e.g., custom native lib doing direct syscall).

## Emulator Detection Mitigation – Separate from Root

`EmulatorHideManager.scanForEmulatorIndicators()` covers all requested controls:

- **Emulator detection APIs**
- **QEMU detection**: `ro.kernel.qemu=1`, `qemu.hw.mainkeys`, pipes `/dev/qemu_pipe`, `/dev/socket/qemud`
- **System properties**: 15+ props (`ro.hardware=goldfish/ranchu`, `ro.product.model=sdk`, `ro.build.fingerprint=generic/sdk`, etc.)
- **Build fingerprints**: generic, unknown, emulator, google_sdk, sdk_gphone, vbox, genymotion
- **Manufacturer/model/device**: Genymotion, google_sdk, Emulator, Android SDK built for
- **Hardware identifiers**: goldfish, ranchu, vbox
- **CPU/ABI**: x86 without arm translation
- **Filesystem artifacts**: `/proc/tty/drivers`, `/proc/cpuinfo`, `libc_malloc_debug_qemu.so`, `/sys/qemu_trace`, `qemu-props`, `microvirt-prop`, `libdroidbox-ril.so` (14 files)
- **Device nodes**: `/dev/socket/qemud`, `/dev/qemu_pipe`, `/dev/socket/genyd`
- **Kernel info**: `/proc/version` contains goldfish/ranchu/qemu
- **Telephony**: IMEI prefix `155552155`, IMSI `000000000000000`
- **SIM/operator**: `310260000`
- **Network interfaces**: eth0 with 10.0.2.15 without wlan0
- **Sensors**: missing physical sensors, Goldfish vendor
- **Camera**: 0 cameras
- **Battery**: no battery / goldfish battery
- **Bluetooth**: no BT
- **WiFi**: MAC `02:00:00:00:00:00` or no wifi
- **USB/ADB**: `ADB_ENABLED=1`, `ro.debuggable=1`, `ro.secure=0`
- **Developer options**: `DEVELOPMENT_SETTINGS_ENABLED=1`
- **Mock location**: `ALLOW_MOCK_LOCATION`, `isFromMockProvider`
- **Virtual device APIs**: `isUserAGoat`, IsolatedProcess
- **Consistency**: enforced via single DeviceProfile

### Hiding Implementation
- `Build.*` field hooks: `FINGERPRINT`, `MANUFACTURER`, `MODEL`, `DEVICE`, `HARDWARE`, `BOARD`, `BRAND`, `PRODUCT`, `SUPPORTED_ABIS`, `CPU_ABI`, etc. → values from `DeviceProfile`
- `SystemProperties.get()` + `__system_property_get` hooks → physical props from `profile.systemProps`
- `File.exists()`, `access()`, `stat()`, `fopen()` hooks → hide emulator files
- `/proc/*` content filtering: `cpuinfo` (Goldfish→Qualcomm), `version` (replace qemu kernel with `5.15.131-android14-11`), `tty/drivers`, `mounts`, `cgroup`
- `TelephonyManager` hooks: `getDeviceId`, `getSubscriberId`, `getNetworkOperator`, `getSimOperator`, `getSimCountryIso` → profile values
- `NetworkInterface.getNetworkInterfaces()` hook → only `profile.networkInterfaces`
- `WifiInfo.getMacAddress()` → randomized from `profile.wifiMacPrefix` + suffix, locally-administered bit set
- `SensorManager.getSensorList()` → `profile.sensors` (Bosch, STM, AKM vendors, not Goldfish)
- `CameraManager.getCameraIdList()` → `profile.camera.cameraCount`
- `BatteryManager` + `ACTION_BATTERY_CHANGED` spoof → `profile.battery`
- `BluetoothAdapter` → `profile.btMacPrefix`
- `Settings.Global.ADB_ENABLED` → 0, `DEVELOPMENT_SETTINGS_ENABLED` → 0 if toggles active
- `Location.isFromMockProvider()` → false, `ALLOW_MOCK_LOCATION` → 0

## Internal Consistency – Single Device Profile

`DeviceProfileManager` provides 8 built-in physical profiles:

- **Pixel 8 Pro** (husky, Android 14, Adreno 750, 5050mAh, T-Mobile 310260)
- **Pixel 7a** (lynx, Adreno 730, 4385mAh)
- **Galaxy S24 Ultra** (SM-S928B, e3q, kalama, Adreno 750, 4 cameras)
- **Galaxy A54** (a54x, Mali-G68)
- **OnePlus 12** (CPH2573, pineapple, Adreno 750, 5400mAh)
- **Xiaomi 14 Pro** (shennong, Adreno 750)
- **Nothing Phone 2** (Pong, Adreno 730)
- **Fold5** (q5q, Adreno 740)

Each profile contains coherent:
- Build: manufacturer, brand, model, device, product, hardware, fingerprint, board, bootloader, version
- CPU/ABI: `arm64-v8a`, supported ABIs
- GPU: vendor/renderer/version
- Telephony/SIM: operator, country, operator name
- WiFi/BT MAC prefix
- Sensors: 9-11 physical sensors (BMI3XX, AK0991X, STK, BMP380, etc.)
- Camera: count, flash, focal lengths
- Battery: capacity, technology, voltage
- Network interfaces: `wlan0`, `rmnet_data0`
- Filesystem hidePaths
- System props: `ro.kernel.qemu=0`, `ro.hardware=husky`, `ro.secure=1`, etc.

`getCoherentEnvironment()` returns single `CoherentEnvironment` used by all hooks – prevents Samsung fingerprint with Pixel hardware, Goldfish sensors in physical profile, etc.

`getConsistencyReport()` verifies: Google manufacturer but fingerprint not google, ARM ABI but x86 in list, Goldfish vendor, emulator camera/battery flags.

## Detection Diagnostics Screen

`EnvironmentDiagnosticsActivity` shows 12+ categories:

- Root detected/not detected
- Emulator detected/not detected
- QEMU indicators
- Virtual-device indicators
- Debug/ADB indicators
- Mock-location indicators
- Suspicious build properties
- Suspicious filesystem artifacts
- Suspicious hardware characteristics
- Sensor inconsistencies
- Telephony inconsistencies
- Network inconsistencies
- Camera, Battery, Bluetooth, WiFi, etc.

Each `DiagnosticCheck` displays:
- `detected`: boolean
- `mitigated`: toggle active && canMitigate
- `verifiedBypass`: mitigation active AND re-scan shows not detected – **only then claim bypass**
- `description`, `currentValue`, `expectedPhysicalValue`, `mitigationMethod`, `canMitigate`, `severity` (LOW/MEDIUM/HIGH/CRITICAL)

Overall report: total checks, detected, mitigated, verified, unmitigated. If unmitigated >0, explains Android restrictions.

**Important:** Do not claim detection bypassed unless mitigation actually active and verified.

## Modularity & Identity Integration

- `EnvironmentManager` is separate module but compatible with Identity/Profile system
- `IdentityConfig.deviceProfileName` links to `DeviceProfileManager`
- `EnvironmentConfig.physicalDeviceProfileId` selects profile
- `HookFramework` installs environment hooks **first**, then identity hooks – ensures Android ID, GSF ID, Advertising ID consistent with physical profile if `spoofPhysicalDeviceProfile=true`
- Original app unaffected – all hooks scoped to clone via `assets/device_profile.json` + `environment_hooks.json` bundled at build time by `CloneEngine`

## Files

- `environment/DeviceProfileManager.kt` – 8 built-in profiles + custom save/load + CoherentEnvironment
- `environment/RootHideManager.kt` – root artifacts + scanning + Hooks
- `environment/EmulatorHideManager.kt` – emulator artifacts + scanning + Hooks + consistency
- `environment/SystemPropertySpoofer.kt` – prop mapping + Hooks (Pine + ByteHook)
- `environment/FileSystemSpoofer.kt` – path hiding + /proc filtering + Hooks
- `environment/DetectionDiagnostics.kt` – diagnostic categories + overall report
- `environment/EnvironmentManager.kt` – central manager + Hooks + generateHooksConfig
- `environment/EnvironmentDiagnosticsActivity.kt` – UI with RecyclerView
- `cloning/models/CloneConfig.kt` – `EnvironmentConfig`, `DeviceProfile`, `SensorProfile`, `CameraProfile`, `BatteryProfile`
- `hooks/HookFramework.kt` – installs environment first
- `cloning/engine/CloneEngine.kt` – bundles `environment_config.json`, `device_profile.json`, `environment_hooks.json`

## Limitations & Graceful Degradation

- Some root checks via direct syscall or custom native lib may bypass PLT hook – reported as unmitigatable with explanation
- Android 10+ restricts IMEI – hook returns spoofed but some system APIs bypass – documented
- If `hideRootLevel=OFF`, diagnostics show detected but not mitigated, does not claim bypass
- Verified bypass only when re-scan after hooks shows not detected
- Emulator hiding cannot hide host GPU passthrough if app checks Vulkan directly – reported as warning

## Usage

```kotlin
val envManager = EnvironmentManager(context)
val config = EnvironmentConfig(
    hideRoot = true,
    hideEmulator = true,
    hideDeveloperOptions = true,
    hideUsbAdb = true,
    hideMockLocation = true,
    spoofPhysicalDeviceProfile = true,
    physicalDeviceProfileId = "pixel8_pro",
    rootHideLevel = RootHideLevel.AGGRESSIVE,
    emulatorHideLevel = EmulatorHideLevel.FULL,
    enforceConsistency = true
)
val (categories, report) = envManager.runDiagnostics(config)
// Show in EnvironmentDiagnosticsActivity
```

Inside clone, diagnostics can be triggered via secret dialer code or menu.

## Testing

1. Install Clone-Master, select app, enable Hide Root + Hide Emulator + Spoof Physical Profile = Pixel 8 Pro
2. Clone, install clone
3. Inside clone, open EnvironmentDiagnosticsActivity – should show ✅ NOT DETECTED or ✅ MITIGATED & VERIFIED for most checks
4. Run RootBeer, Emulator Detector libraries inside clone – should show not rooted, not emulator if mitigations active
5. Check consistency: fingerprint google/husky, manufacturer Google, hardware husky, sensors Bosch, GPU Adreno 750 – all from same profile
