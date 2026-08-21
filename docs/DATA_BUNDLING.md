# Bundle & Restore App Data – Dedicated Feature

## Overview
Creates self-contained clone package that can optionally contain source app's exportable user data alongside cloned APK. UX: Build clone → Install → Import data automatically → Progress bar → Open restored clone

## Build-Time Process

### 1. Analyze Source App's Accessible Data
`DataBundleAnalyzer.analyze(packageName)` inspects:
- `shared_prefs/` – SharedPreferences XML
- `databases/` – SQLite, Room (detected via journal files, naming)
- `files/` – persistent files, game progress, offline downloads
- `no_backup/` – cache-independent persistent files
- `app_webview/` – WebView data/cookies (with warning about encryption)
- `/sdcard/Android/data/<pkg>` – external-storage app dirs
- `/sdcard/Android/obb/<pkg>` – OBB dirs
- Custom dirs – user explicitly selected

Each category reports: path, fileCount, sizeBytes, accessible, description, examples

Warnings:
- WebView cookies often encrypted with device key – may not be restorable
- Keystore/hardware-backed/session data cannot be copied – login restoration not guaranteed
- Data dir not readable without root – only external dirs accessible

### 2. User Chooses Categories/Directories
In CloneConfigActivity, new section "Bundle & Restore App Data":
- Checkboxes for each DataCategory
- List of detected dirs with size
- Custom dirs picker (SAF)
- Exclude dirs
- Options: compression (NONE/ZIP/GZIP/ZSTD), encryption (NONE/AES256/CHACHA20), password, embedInApk vs separate .data file, maxBundleSizeMb

Config stored in `CloneConfig.dataBundle: DataBundleConfig`

### 3. Package into Encrypted/Compressed Archive
`DataArchiveManager.createArchive()`:
- Collects all files recursively from selected dirs
- Creates `DataBundleManifest` with `DataBundleMetadata`:
  - sourcePackage, clonePackage, sourceVersionName/Code, cloneVersionName/Code, androidVersion, androidRelease, dataFormatVersion=2, createdAt, includedCategories, includedDirs, excludedDirs, archiveName, archiveSize, fileCount, totalBytes, compression, encryption, hasKeystoreData flag, notes
- For each file: `DataBundleFileEntry` with originalPath, relativePath (`data/...`), type, size, checksum SHA256, requiresTransformation, transformedPath
- Compresses via ZIP (BEST_COMPRESSION) or GZIP or ZSTD (placeholder uses ZIP – real would use zstd-jni)
- Calculates SHA256 for each file and archive
- Creates outer zip containing: `manifest.json`, `checksums.sha256`, `data/archive.zip` (inner)
- Encrypts if enabled: AES/GCM with key derived from password SHA256 (production should use PBKDF2), IV prepended
- Output: `com.example.clone_data_v2.cmb` (Clone-Master Bundle)

### 4. Metadata
`DataBundleMetadata` includes source/clone package, Android version, data format/version, included dirs, archive checksum SHA256, fileCount, totalBytes, encryption, compression

### 5. Embed or Separate Payload
- If `embedInApk=true`: copies archive into `assets/data/archive.zip` + `assets/data_manifest.json` + `assets/data_bundle_metadata.json` inside decoded APK before building
- If `createSeparateDataFile=true`: keeps archive as separate file `CloneName.data` alongside `CloneName.apk`
- Also creates combined backup package: `/clone.apk` + `/data/archive` + `/manifest.json` + `/checksums` via `BackupManager.exportCloneAndData()`
- Never overwrites original app's data – all reads read-only

## Installation Process (First-Run Import)

When bundled clone installed first time:

1. **Detect bundled data**: `DataRestoreEngine.detectBundledData()` checks `assets/data/archive.zip`, `assets/data_manifest.json`, `filesDir/*.cmb`
2. **Create clone's private data structure**: ensures `shared_prefs/`, `databases/`, `files/`, `app_webview/`, `no_backup/` exist
3. **First-run import screen**: `FirstRunImportActivity` with progress bar, status messages, log view, Retry/Continue buttons
4. **Progress bar**: 0-100% with stages
5. **Extract/import**: `extractArchive()` handles outer zip + inner `data/archive.zip`
6. **Restore files/databases**: copies files to appropriate locations, with package-name/path transformation if `transformPaths=true` (e.g., `shared_prefs/com.example.app_preferences.xml` → replace source pkg with clone pkg in file name and content)
7. **Transformations**: `transformPathForRestore()` replaces source package with clone package in paths; `applyTransformations()` replaces source package in SharedPreferences XML content
8. **Validate**: `validateRestoredData()` checks file count vs manifest (80% threshold), `verifyFinalDataDir()` ensures not empty
9. **Checksum verification**: `DataArchiveManager.verifyChecksum()`
10. **Mark migration completed**: `SharedPreferences clone_migration migration_completed=true`
11. **Delete temp files**: `extractDir.deleteRecursively()`, archive file delete
12. **Launch cloned app**: auto-launch after 2 sec if no warnings, else Continue button

### Example UI Messages
- "Importing application data..."
- "Restoring files..."
- "Restoring database..."
- "Restoring WebView data..."
- "Finalizing..."
- "Data import complete"

Progress mapping:
- DETECTING 0% → "Importing application data..."
- PREPARING 5-10% → "Preparing...", "Verifying archive checksum..."
- EXTRACTING 20% → "Extracting data..."
- RESTORING_FILES 30-70% → "Restoring files... <filename>"
- RESTORING_DATABASES 70% → "Restoring database..."
- RESTORING_WEBVIEW 75% → "Restoring WebView data..."
- TRANSFORMING 80% → "Applying transformations..."
- VALIDATING 85% → "Validating..."
- FINALIZING 90% → "Finalizing..."
- COMPLETE 100% → "Data import complete"

## Data Migration Safety

- **Never modify original**: all reads read-only, `dataDir.canRead()` check, never write to source package path
- **Never blindly overwrite incompatible**: `isDatabaseCompatible()` checks SQLite header "SQLite format 3", skips if schema incompatible, logs warning
- **Android-version incompatibilities**: warns if source Android version > current +5 or < current -10
- **App-version incompatibilities**: warns if sourceVersionCode != cloneVersionCode – DB schema may have changed
- **Database-schema incompatibilities**: checks header, could be extended to compare table schemas via SQLite
- **Rollback**: `rollback()` placeholder – in production keeps backup of original data dir before restore and restores on failure
- **Import log**: `StringBuilder importLog` with RESTORED/SKIPPED/FAILED entries, shown in UI
- **Retry**: `allowRetry()` clears migration_completed flag, Retry button enabled on failure
- **Verify final data dir**: `verifyFinalDataDir()` checks exists and not empty

## Session/State Restoration

Attempts to preserve where technically possible:
- Login/session state (SharedPreferences tokens, non-Keystore)
- Preferences, app settings (SharedPreferences)
- Offline downloads (files/, external dirs)
- Game progress (files/, databases/)
- Databases (SQLite, Room)
- WebView state (app_webview/ – with encryption warning)
- Persistent app files

**However, does NOT claim login restoration guaranteed.** For protected data, shows:

> "Some account/session data could not be restored because it is protected by Android or the application."

Cases:
- Android Keystore: keys stored in `AndroidKeyStore`, hardware-backed, cannot be copied
- Hardware-backed security: StrongBox, TEE
- Certificate-bound credentials: SSL client certs bound to package signature
- Server-side sessions: session tokens validated server-side with device fingerprint, may be invalidated on new package

Detection: if manifest has `hasKeystoreData=true` or warnings contain "Keystore", shows warning in UI.

## Export/Import Format

Dedicated archive format, not raw data in APK:

**Option 1: Separate files**
- `CloneName.apk`
- `CloneName.data` (encrypted/compressed bundle)

**Option 2: Single package (combined backup)**
- `/clone.apk`
- `/data/archive.zip` (inner data)
- `/manifest.json` (backup manifest + DataBundleMetadata + CloneConfig)
- `/checksums.sha256`

Implemented in `BackupManager.exportCloneAndData()`:
- Creates zip `backupId.cmb_backup` containing `manifest.json`, `clone.apk`, `data/archive.zip`, `clone_config.json`, `checksums.sha256`
- Optional encryption: AES/GCM, IV prepended, `.enc` extension

User experience remains: Build clone → Install → Import automatically → Progress bar → Open restored clone

## Backup and Restoration Features

`BackupManager` provides:

- **Export clone + data**: `exportCloneAndData()` – APK + data archive + manifest + checksums, optional encrypted
- **Import clone + data**: `importBackup()` – decrypt if needed, extract to temp dir, verify checksums, return apkFile, dataArchive, config, tempDir
- **Backup clone settings**: `backupSettings()` – saves CloneConfig as JSON
- **Restore clone settings**: `restoreSettings()` – parses JSON
- **Data-only backup**: `backupDataOnly()` – analyzes clonePackage, creates archive from accessible dirs
- **Data-only restore**: uses `DataRestoreEngine.restore()`
- **Optional encrypted backups**: AES256/GCM with password, key derived SHA256 (should use PBKDF2 in production)
- **Backup integrity verification**: `verifyBackupIntegrity()` checks manifest + checksums exist, `verifyChecksum()` SHA256
- **Versioned backup format**: `BACKUP_VERSION=2`, `dataFormatVersion=2`, manifest includes version
- **Migration between compatible clone versions**: `migrateData(oldConfig, newConfig, oldDataDir, newDataDir)` – copies files with package-name transformation, warns if original packages differ

Additional:
- `listBackups(backupDir)` – lists `*.cmb_backup` and `*.enc` with BackupInfo (backupId, clonePackage, backupType, createdAt, version, size, checksum, encrypted, includesData/Apk)
- Import log kept, retry allowed

## Files

- `databundle/DataBundleAnalyzer.kt` – analyze exportable data
- `databundle/DataArchiveManager.kt` – create/encrypt/verify archive
- `databundle/DataRestoreEngine.kt` – first-run restore with safety, rollback, validation
- `databundle/FirstRunImportActivity.kt` – UI with progress bar
- `databundle/BackupManager.kt` – export/import, settings, data-only, encrypted, versioned, migration
- `cloning/models/CloneConfig.kt` – `DataBundleConfig`, `DataCategory`, `CompressionType`, `EncryptionType`, `DataBundleMetadata`, `DataBundleManifest`, `DataBundleFileEntry`
- `cloning/engine/CloneEngine.kt` – bundling logic, embed vs separate, inject import activity
- `hooks/HookFramework.kt` – detects bundled data on first run, launches import activity
- `res/layout/activity_first_run_import.xml` – progress UI
- `AndroidManifest.xml` – adds FirstRunImportActivity

## Security

- Never modifies original app's data
- Read-only analysis
- Encrypted backups optional with password
- Checksums for integrity
- Rollback on failure
- No hard-coded secrets

## Limitations (Graceful Degradation)

- Without root, `/data/data/<pkg>` may not be readable – only external dirs and accessible files can be bundled (warning added)
- WebView cookies encrypted with device key – may not restore (warning)
- Keystore data cannot be restored – shows message, does not claim success
- Database schema changes between versions may cause incompatibility – skips file with warning instead of corrupting
- Large bundles >500MB may be truncated per `maxBundleSizeMb` config

## Testing Flow

1. In Clone-Master, select app, enable "Bundle App Data", choose categories (SharedPrefs, Databases, Files, External), set compression ZSTD, encryption AES256 with password, embedInApk=true
2. Build clone – logs show "Analyzing app data...", "Packaging X files (Y MB)...", "Embedding data archive..."
3. Install clone APK
4. On first launch, `FirstRunImportActivity` appears: "Importing application data..." → progress bar → "Restoring files... filename" → "Restoring database..." → "Restoring WebView data..." → "Finalizing..." → "Data import complete"
5. If Keystore data present, shows "Some account/session data could not be restored..."
6. Click Continue → cloned app launches with restored data
7. Test backup: Export clone+data → creates `clone.apk` + `data/archive` + `manifest.json` + `checksums` in single `.cmb_backup` file
8. Test import: Import backup → verifies checksum → extracts
9. Test data-only backup/restore
10. Test migration: old clone v1 → new clone v2 with `migrateData()`
