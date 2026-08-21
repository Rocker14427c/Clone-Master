# Environment Spoofing / Detection Mitigation

Dedicated subsystem - see previous commit message for details.

- RootHideManager
- EmulatorHideManager
- DeviceProfileManager
- SystemPropertySpoofer
- FileSystemSpoofer
- DetectionDiagnostics
- EnvironmentManager
- EnvironmentDiagnosticsActivity

Per-clone toggles: Hide Root, Hide Emulator, Hide Developer Options, Hide USB/ADB, Hide Mock Location, Spoof Physical Device Profile

Coherent physical profiles across Build, Android ID, GSF ID, Advertising ID, Telephony, SIM, WiFi, BT, Sensors, Camera, GPU, CPU/ABI, Battery, Network, Filesystem.
