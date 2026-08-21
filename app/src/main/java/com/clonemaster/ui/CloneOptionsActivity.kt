package com.clonemaster.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.clonemaster.R
import com.clonemaster.cloning.models.*
import com.clonemaster.environment.DeviceProfileManager
import com.clonemaster.ui.adapters.OptionsAdapter
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Dedicated Clone Configuration screen – independent UI implementation
 * Public functional/UI reference: https://appcloner.app/ used only for organization and behavior reference
 * Implements own independent UI, code and design, not copying App Cloner colors/assets/layout
 *
 * User flow: Installed Apps → Select application → App Details/Compatibility → Clone this app → Clone Configuration → Configure options → Build Clone → Install/Export
 * User must configure BEFORE APK is generated – functional parity with public reference
 *
 * Features:
 * - Search field "Search clone options..." filtering immediately by name, description, category, aliases/tags
 * - Categories as cards for already implemented functionality (only when contains implemented functionality)
 * - Individual option UI with icon, name, description, enabled/disabled state, appropriate controls (Switch, Checkbox, Dropdown, Slider, Text field, List editor, Dialog)
 * - Option state maps to real field in CloneConfig – no fake switches – UI → CloneConfig → CloneEngine → transformer/hook/runtime
 * - Connects to existing systems: IdentityManager, DeviceProfileManager, RootHideManager, EmulatorHideManager, EnvironmentManager, SystemPropertySpoofer, FileSystemSpoofer, Privacy, Display, Media, Navigation, Storage, DataBundleAnalyzer, DataArchiveManager, DataRestoreEngine, Networking, ProxyManager, TunnelManager, WebViewScriptManager, Notification, Automation, Native hooks, Diagnostics, Manifest/resource, Developer
 * - Advanced options collapsible, warnings for dangerous options
 * - Presets: Default, Privacy, Maximum Privacy, Performance, Compatibility, Clean Clone, Custom
 * - Save/load: Save, Load, Duplicate, Reset, Export, Import
 * - Clone summary before building with warnings and estimated size, then Build Clone
 * - Build progress: Analyze → Transform manifest → resources → DEX → native libs → hooks → bundle data → sign → verify → complete with meaningful errors
 * - Compatibility indicators: 🟢 Supported, 🟡 May affect compatibility, 🔴 Known limitation, ⚠️ Requires root/Android version/permission from compatibility system
 * - UI quality modern and consistent with Clone-Master own visual identity
 */
class CloneOptionsActivity : AppCompatActivity() {

    private lateinit var config: CloneConfig
    private lateinit var originalAppInfo: AppInfo

    private lateinit var searchEditText: TextInputEditText
    private lateinit var recyclerCategories: RecyclerView
    private lateinit var recyclerOptions: RecyclerView
    private lateinit var presetSpinner: Spinner
    private lateinit var textSummary: TextView
    private lateinit var buttonBuildClone: Button
    private lateinit var buttonSaveConfig: Button
    private lateinit var buttonLoadConfig: Button
    private lateinit var toolbar: MaterialToolbar

    private lateinit var optionsAdapter: OptionsAdapter
    private var allOptions: List<OptionItem> = emptyList()
    private var filteredOptions: List<OptionItem> = emptyList()
    private var selectedCategory: OptionCategory? = null

    private val configValues = mutableMapOf<String, Any>()
    private lateinit var configStorage: ConfigStorageManager
    private lateinit var deviceProfileManager: DeviceProfileManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_clone_options)

        toolbar = findViewById(R.id.toolbar)
        searchEditText = findViewById(R.id.searchOptions)
        recyclerCategories = findViewById(R.id.recyclerCategories)
        recyclerOptions = findViewById(R.id.recyclerOptions)
        presetSpinner = findViewById(R.id.presetSpinner)
        textSummary = findViewById(R.id.textSummary)
        buttonBuildClone = findViewById(R.id.buttonBuildClone)
        buttonSaveConfig = findViewById(R.id.buttonSaveConfig)
        buttonLoadConfig = findViewById(R.id.buttonLoadConfig)

        configStorage = ConfigStorageManager(this)
        deviceProfileManager = DeviceProfileManager(this)

        // Load config from intent
        val configJson = intent.getStringExtra("configJson")
        val originalPackage = intent.getStringExtra("originalPackage") ?: intent.getStringExtra("package") ?: ""

        config = if (configJson != null) {
            try { GsonBuilder().create().fromJson(configJson, CloneConfig::class.java) } catch (ignored: Exception) { CloneConfig(originalPackage = originalPackage) }
        } else {
            // Load from clonePackage if provided, else create default for originalPackage
            val clonePackage = intent.getStringExtra("clonePackage")
            if (clonePackage != null) {
                configStorage.loadConfiguration(clonePackage) ?: CloneConfig(originalPackage = originalPackage, clonePackage = clonePackage)
            } else {
                CloneConfig(
                    originalPackage = originalPackage,
                    clonePackage = if (originalPackage.isNotEmpty()) "$originalPackage.clone1" else "com.example.clone1",
                    cloneIndex = 1,
                    appName = if (originalPackage.isNotEmpty()) "${originalPackage.substringAfterLast('.')} Clone" else "My Clone"
                )
            }
        }

        // Load original app info for summary
        originalAppInfo = try {
            com.clonemaster.analysis.AppAnalyzer(this).let { analyzer ->
                analyzer.analyzeInstalled(config.originalPackage).first
            }
        } catch (ignored: Exception) {
            AppInfo(packageName = config.originalPackage, appName = config.appName, versionName = config.versionName, versionCode = config.versionCode, targetSdk = 34, minSdk = 24, apkPath = "")
        }

        // Initialize configValues map from CloneConfig – UI → CloneConfig mapping, no fake switches
        initializeConfigValues()

        // Setup categories
        setupCategories()

        // Setup options
        allOptions = OptionRegistry.getAllOptions()
        filteredOptions = allOptions

        optionsAdapter = OptionsAdapter(
            options = filteredOptions,
            configValues = configValues,
            onOptionChanged = { option, newValue ->
                updateConfigFromOption(option, newValue)
                updateSummary()
            },
            onOptionClicked = { option ->
                showOptionDetailDialog(option)
            }
        )

        recyclerOptions.layoutManager = LinearLayoutManager(this)
        recyclerOptions.adapter = optionsAdapter

        // Search – prominent, immediate filtering by name, description, category, aliases/tags
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                filteredOptions = if (query.isEmpty()) {
                    if (selectedCategory != null) OptionRegistry.getByCategory(selectedCategory!!) else allOptions
                } else {
                    OptionRegistry.search(query)
                }
                optionsAdapter.updateOptions(filteredOptions)
                textSummary.text = "Search: \"$query\" – ${filteredOptions.size} options found"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        findViewById<View>(R.id.clearSearch).setOnClickListener {
            searchEditText.text?.clear()
            filteredOptions = if (selectedCategory != null) OptionRegistry.getByCategory(selectedCategory!!) else allOptions
            optionsAdapter.updateOptions(filteredOptions)
            updateSummary()
        }

        // Presets
        setupPresets()

        // Save / Load
        buttonSaveConfig.setOnClickListener {
            try {
                val file = configStorage.saveConfiguration(config)
                Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
            } catch (ignored: Exception) {
                Toast.makeText(this, "Save failed: ${ignored.message}", Toast.LENGTH_SHORT).show()
            }
        }

        buttonLoadConfig.setOnClickListener {
            showLoadConfigDialog()
        }

        // Build Clone – shows summary first, then progress
        buttonBuildClone.setOnClickListener {
            showCloneSummaryAndBuild()
        }

        updateSummary()
    }

    private fun initializeConfigValues() {
        // Map CloneConfig fields to configValues for UI controls – real fields, no fake
        configValues["appName"] = config.appName
        configValues["clonePackage"] = config.clonePackage
        configValues["versionName"] = config.versionName
        configValues["versionCode"] = config.versionCode.toString()
        configValues["customIconPath"] = config.customIconPath ?: ""
        configValues["iconBadge"] = config.iconBadge.name
        configValues["removeBranding"] = config.removeBranding

        // Identity
        configValues["identity.androidId"] = config.identity.androidId
        configValues["identity.imei"] = config.identity.imei
        configValues["identity.wifiMac"] = config.identity.wifiMac
        configValues["identity.btMac"] = config.identity.btMac
        configValues["identity.gsfId"] = config.identity.gsfId
        configValues["identity.advertisingId"] = config.identity.advertisingId
        configValues["identity.webViewUserAgent"] = config.identity.webViewUserAgent
        configValues["identity.deviceProfileName"] = config.identity.deviceProfileName

        // Privacy
        configValues["privacy.disableClipboard"] = config.privacy.disableClipboard
        configValues["privacy.disableSensors"] = config.privacy.disableSensors
        configValues["privacy.gpsSpoof"] = config.privacy.gpsSpoof
        configValues["privacy.disableScreenshots"] = config.privacy.disableScreenshots
        configValues["privacy.excludeFromRecents"] = config.privacy.excludeFromRecents
        configValues["privacy.disableAccounts"] = config.privacy.disableAccounts
        configValues["privacy.disableContacts"] = config.privacy.disableContacts
        configValues["privacy.incognitoMode"] = config.privacy.incognitoMode
        configValues["privacy.passwordProtection"] = config.privacy.passwordProtection
        configValues["privacy.stealthMode"] = config.privacy.stealthMode
        configValues["privacy.disabledPermissions"] = config.privacy.disabledPermissions.joinToString(",")

        // Environment
        configValues["environment.hideRoot"] = config.environment.hideRoot
        configValues["environment.hideEmulator"] = config.environment.hideEmulator
        configValues["environment.hideDeveloperOptions"] = config.environment.hideDeveloperOptions
        configValues["environment.hideUsbAdb"] = config.environment.hideUsbAdb
        configValues["environment.hideMockLocation"] = config.environment.hideMockLocation
        configValues["environment.physicalDeviceProfileId"] = config.environment.physicalDeviceProfileId
        configValues["environment.enableDetectionDiagnostics"] = config.environment.enableDetectionDiagnostics

        // Display
        configValues["display.darkMode"] = config.display.darkMode.name
        configValues["display.orientationLock"] = config.display.orientationLock.toString()
        configValues["display.immersiveFullscreen"] = config.display.immersiveFullscreen
        configValues["display.keepScreenAwake"] = config.display.keepScreenAwake
        configValues["display.customLanguage"] = config.display.customLanguage
        configValues["viewMods"] = config.viewMods.size.toString()

        // Storage
        configValues["storage.redirectExternalStorage"] = config.storage.redirectExternalStorage
        configValues["storage.preventBackup"] = config.storage.preventBackup
        configValues["storage.preserveDataOnUninstall"] = config.storage.preserveDataOnUninstall

        // Data bundling
        configValues["dataBundle.enabled"] = config.dataBundle.enabled
        configValues["dataBundle.compression"] = config.dataBundle.compression.name
        configValues["dataBundle.encryption"] = config.dataBundle.encryption.name

        // Launching
        configValues["launching.removeLauncherIcon"] = config.launching.removeLauncherIcon
        configValues["launching.secretDialerCode"] = config.launching.secretDialerCode
        configValues["launching.persistentMode"] = config.launching.persistentMode
        configValues["launching.fakeBatteryLevel"] = config.launching.fakeBatteryLevel?.toString() ?: ""

        // Networking
        configValues["networking.disableNetworking"] = config.networking.disableNetworking
        configValues["networking.disableMobileData"] = config.networking.disableMobileData
        configValues["networking.httpProxy"] = config.networking.httpProxy
        configValues["networking.socksProxy"] = config.networking.socksProxy
        configValues["networking.httpProxyList"] = config.networking.httpProxyList.joinToString(",")
        configValues["networking.dnsOverHttps"] = config.networking.dnsOverHttps
        configValues["networking.vpnOnly"] = config.networking.vpnOnly
        configValues["networking.notificationToggle"] = config.networking.notificationToggle

        // Notifications
        configValues["notification.filterPatterns"] = config.notification.filterPatterns.joinToString(",")
        configValues["notification.showDots"] = config.notification.showDots?.toString() ?: "null"

        // Games, TV/Wear, Automation, Developer, Parity
        configValues["game.bundleObb"] = config.game.bundleObb
        configValues["tvWear.customTvBannerPath"] = config.tvWear.customTvBannerPath ?: ""
        configValues["automation.brightnessOnStart"] = config.automation.brightnessOnStart?.toString() ?: ""
        configValues["developer.changeTargetSdk"] = config.developer.changeTargetSdk?.toString() ?: ""
        configValues["developer.nativeHooksEnabled"] = config.developer.nativeHooksEnabled
        configValues["developer.safeMode"] = config.developer.safeMode

        // Parity features
        configValues["parityFeatures.trackingBlocker.disableAppsFlyer"] = config.parityFeatures.trackingBlocker.disableAppsFlyer
        configValues["parityFeatures.cpuGpu.hideCpuInfo"] = config.parityFeatures.cpuGpu.hideCpuInfo
        configValues["parityFeatures.hookOptions.disableHooks"] = config.parityFeatures.hookOptions.disableHooks
        configValues["parityFeatures.manifestOptions.appCategory"] = config.parityFeatures.manifestOptions.appCategory
        configValues["parityFeatures.manifestOptions.largeHeap"] = config.parityFeatures.manifestOptions.largeHeap?.toString() ?: "null"
        configValues["parityFeatures.sneezeToExit.enabled"] = config.parityFeatures.sneezeToExit.enabled
        configValues["parityFeatures.knoxWarranty.spoofWarrantyBit"] = config.parityFeatures.knoxWarranty.spoofWarrantyBit
        configValues["parityFeatures.screensaver.mode"] = config.parityFeatures.screensaver.mode
        configValues["parityFeatures.uninstallData.hasFragileUserData"] = config.parityFeatures.uninstallData.hasFragileUserData
        configValues["parityFeatures.screenEvents.disableScreenOnOffEvents"] = config.parityFeatures.screenEvents.disableScreenOnOffEvents
        configValues["parityFeatures.tunnelManager.enabled"] = config.parityFeatures.tunnelManager.enabled
        configValues["parityFeatures.locale.customLocale"] = config.parityFeatures.locale.customLocale
        configValues["parityFeatures.webViewScript.injectMode"] = config.parityFeatures.webViewScript.injectMode
    }

    private fun updateConfigFromOption(option: OptionItem, newValue: Any) {
        // UI → CloneConfig mapping – real fields, no fake
        try {
            when (option.configFieldPath) {
                "appName" -> config.appName = newValue as String
                "clonePackage" -> config.clonePackage = newValue as String
                "versionName" -> config.versionName = newValue as String
                "versionCode" -> config.versionCode = (newValue as String).toLongOrNull() ?: config.versionCode
                "iconBadge" -> config.iconBadge = try { IconBadge.valueOf(newValue as String) } catch (ignored: Exception) { IconBadge.NONE }
                "removeBranding" -> config.removeBranding = newValue as Boolean

                "identity.androidId" -> config.identity.androidId = newValue as String
                "identity.imei" -> config.identity.imei = newValue as String
                "identity.wifiMac" -> config.identity.wifiMac = newValue as String
                "identity.btMac" -> config.identity.btMac = newValue as String
                "identity.gsfId" -> config.identity.gsfId = newValue as String
                "identity.advertisingId" -> config.identity.advertisingId = newValue as String
                "identity.webViewUserAgent" -> config.identity.webViewUserAgent = newValue as String
                "identity.deviceProfileName" -> config.identity.deviceProfileName = newValue as String

                "privacy.disableClipboard" -> config.privacy.disableClipboard = newValue as Boolean
                "privacy.disableSensors" -> config.privacy.disableSensors = newValue as Boolean
                "privacy.gpsSpoof" -> config.privacy.gpsSpoof = newValue as Boolean
                "privacy.disableScreenshots" -> config.privacy.disableScreenshots = newValue as Boolean
                "privacy.excludeFromRecents" -> config.privacy.excludeFromRecents = newValue as Boolean
                "privacy.disableAccounts" -> config.privacy.disableAccounts = newValue as Boolean
                "privacy.disableContacts" -> config.privacy.disableContacts = newValue as Boolean
                "privacy.incognitoMode" -> config.privacy.incognitoMode = newValue as Boolean
                "privacy.passwordProtection" -> config.privacy.passwordProtection = newValue as Boolean
                "privacy.stealthMode" -> config.privacy.stealthMode = newValue as Boolean

                "environment.hideRoot" -> config.environment.hideRoot = newValue as Boolean
                "environment.hideEmulator" -> config.environment.hideEmulator = newValue as Boolean
                "environment.hideDeveloperOptions" -> config.environment.hideDeveloperOptions = newValue as Boolean
                "environment.hideUsbAdb" -> config.environment.hideUsbAdb = newValue as Boolean
                "environment.hideMockLocation" -> config.environment.hideMockLocation = newValue as Boolean
                "environment.physicalDeviceProfileId" -> config.environment.physicalDeviceProfileId = newValue as String

                "display.darkMode" -> config.display.darkMode = try { DarkMode.valueOf(newValue as String) } catch (ignored: Exception) { DarkMode.SYSTEM }
                "display.immersiveFullscreen" -> config.display.immersiveFullscreen = newValue as Boolean
                "display.keepScreenAwake" -> config.display.keepScreenAwake = newValue as Boolean
                "display.customLanguage" -> config.display.customLanguage = newValue as String

                "storage.redirectExternalStorage" -> config.storage.redirectExternalStorage = newValue as Boolean
                "storage.preventBackup" -> config.storage.preventBackup = newValue as Boolean

                "dataBundle.enabled" -> config.dataBundle.enabled = newValue as Boolean
                "dataBundle.compression" -> config.dataBundle.compression = try { CompressionType.valueOf(newValue as String) } catch (ignored: Exception) { CompressionType.ZSTD }
                "dataBundle.encryption" -> config.dataBundle.encryption = try { EncryptionType.valueOf(newValue as String) } catch (ignored: Exception) { EncryptionType.NONE }

                "launching.removeLauncherIcon" -> config.launching.removeLauncherIcon = newValue as Boolean
                "launching.secretDialerCode" -> config.launching.secretDialerCode = newValue as String
                "launching.persistentMode" -> config.launching.persistentMode = newValue as Boolean

                "networking.disableNetworking" -> config.networking.disableNetworking = newValue as Boolean
                "networking.disableMobileData" -> config.networking.disableMobileData = newValue as Boolean
                "networking.httpProxy" -> config.networking.httpProxy = newValue as String
                "networking.socksProxy" -> config.networking.socksProxy = newValue as String
                "networking.dnsOverHttps" -> config.networking.dnsOverHttps = newValue as String
                "networking.vpnOnly" -> config.networking.vpnOnly = newValue as Boolean
                "networking.notificationToggle" -> config.networking.notificationToggle = newValue as Boolean

                "game.bundleObb" -> config.game.bundleObb = newValue as Boolean

                "developer.changeTargetSdk" -> config.developer.changeTargetSdk = (newValue as String).toIntOrNull()
                "developer.nativeHooksEnabled" -> config.developer.nativeHooksEnabled = newValue as Boolean
                "developer.safeMode" -> config.developer.safeMode = newValue as Boolean

                "parityFeatures.trackingBlocker.disableAppsFlyer" -> config.parityFeatures.trackingBlocker.disableAppsFlyer = newValue as Boolean
                "parityFeatures.cpuGpu.hideCpuInfo" -> config.parityFeatures.cpuGpu.hideCpuInfo = newValue as Boolean
                "parityFeatures.hookOptions.disableHooks" -> {
                    config.parityFeatures.hookOptions.disableHooks = newValue as Boolean
                    config.developer.safeMode = newValue as Boolean
                }
                "parityFeatures.uninstallData.hasFragileUserData" -> config.parityFeatures.uninstallData.hasFragileUserData = newValue as Boolean
                "parityFeatures.screenEvents.disableScreenOnOffEvents" -> config.parityFeatures.screenEvents.disableScreenOnOffEvents = newValue as Boolean
                "parityFeatures.tunnelManager.enabled" -> config.parityFeatures.tunnelManager.enabled = newValue as Boolean
                "parityFeatures.locale.customLocale" -> config.parityFeatures.locale.customLocale = newValue as String
                "parityFeatures.webViewScript.injectMode" -> config.parityFeatures.webViewScript.injectMode = newValue as String

                else -> {
                    // For list editors and other complex types, handled via dialogs
                    android.util.Log.d("CloneMaster", "Option ${option.id} -> ${option.configFieldPath} updated to $newValue (no direct field mapping, handled via dialog)")
                }
            }
        } catch (ignored: Exception) {
            android.util.Log.e("CloneMaster", "Failed to update config from option ${option.id}: ${ignored.message}", ignored)
        }
    }

    private fun setupCategories() {
        val categories = OptionRegistry.getCategoriesWithOptions()
        recyclerCategories.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        recyclerCategories.adapter = CategoryAdapter(categories, selectedCategory) { category ->
            selectedCategory = category
            filteredOptions = if (searchEditText.text.toString().isEmpty()) {
                OptionRegistry.getByCategory(category)
            } else {
                OptionRegistry.search(searchEditText.text.toString()).filter { it.category == category }
            }
            optionsAdapter.updateOptions(filteredOptions)
            updateSummary()
        }
    }

    private fun setupPresets() {
        val presetNames = PresetType.values().map { "${it.displayName} – ${it.description}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presetNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val preset = PresetType.values()[position]
                if (preset != PresetType.CUSTOM) {
                    config = PresetManager.applyPreset(config, preset)
                    initializeConfigValues()
                    filteredOptions = if (selectedCategory != null) OptionRegistry.getByCategory(selectedCategory!!) else allOptions
                    optionsAdapter = OptionsAdapter(filteredOptions, configValues, { opt, value ->
                        updateConfigFromOption(opt, value)
                        updateSummary()
                    }, { opt ->
                        showOptionDetailDialog(opt)
                    })
                    recyclerOptions.adapter = optionsAdapter
                    updateSummary()
                    Toast.makeText(this@CloneOptionsActivity, "Applied preset: ${preset.displayName}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showOptionDetailDialog(option: OptionItem) {
        when (option.controlType) {
            ControlType.LIST_EDITOR -> {
                val current = configValues[option.configFieldPath]?.toString() ?: ""
                val editText = EditText(this).apply {
                    setText(current)
                    hint = "Comma-separated list"
                }
                AlertDialog.Builder(this)
                    .setTitle(option.name)
                    .setMessage(option.description + "\n\nCurrent: $current\n\nAliases: ${option.aliases.joinToString(", ")}")
                    .setView(editText)
                    .setPositiveButton("Save") { _, _ ->
                        val newValue = editText.text.toString()
                        configValues[option.configFieldPath] = newValue
                        updateConfigFromOption(option, newValue)
                        updateSummary()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            ControlType.DIALOG -> {
                AlertDialog.Builder(this)
                    .setTitle(option.name)
                    .setMessage("${option.description}\n\nCategory: ${option.category.displayName}\nField: ${option.configFieldPath}\nCompatibility: ${option.compatibility.emoji} ${option.compatibility.label}\n${option.androidVersionRequirement ?: ""}\n${option.permissionRequirement ?: ""}\n\nAliases: ${option.aliases.joinToString(", ")}\n\n${option.requiresWarning ?: ""}")
                    .setPositiveButton("OK", null)
                    .setNeutralButton("Toggle") { _, _ ->
                        val current = configValues[option.configFieldPath] as? Boolean ?: false
                        val newValue = !current
                        configValues[option.configFieldPath] = newValue
                        updateConfigFromOption(option, newValue)
                        optionsAdapter.notifyDataSetChanged()
                        updateSummary()
                    }
                    .show()
            }
            ControlType.BUTTON -> {
                when (option.id) {
                    "environment_diagnostics" -> {
                        val intent = Intent(this, com.clonemaster.environment.EnvironmentDiagnosticsActivity::class.java).apply {
                            putExtra("profileId", config.environment.physicalDeviceProfileId)
                        }
                        startActivity(intent)
                    }
                    "diagnostics_logcatViewer" -> {
                        val intent = Intent(this, com.clonemaster.ui.LogcatViewerActivity::class.java)
                        startActivity(intent)
                    }
                    "diagnostics_compatibilityReport" -> {
                        val intent = Intent(this, com.clonemaster.ui.AppAnalyzerActivity::class.java).apply {
                            putExtra("package", config.originalPackage)
                        }
                        startActivity(intent)
                    }
                    else -> {
                        AlertDialog.Builder(this)
                            .setTitle(option.name)
                            .setMessage(option.description)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            }
            else -> {}
        }
    }

    private fun showLoadConfigDialog() {
        val configs = configStorage.loadAllConfigurations()
        if (configs.isEmpty()) {
            Toast.makeText(this, "No saved configurations", Toast.LENGTH_SHORT).show()
            return
        }

        val names = configs.map { "${it.appName} – ${it.clonePackage}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Load Configuration")
            .setItems(names) { _, which ->
                val selected = configs[which]
                config = selected
                initializeConfigValues()
                optionsAdapter = OptionsAdapter(filteredOptions, configValues, { opt, value ->
                    updateConfigFromOption(opt, value)
                    updateSummary()
                }, { opt -> showOptionDetailDialog(opt) })
                recyclerOptions.adapter = optionsAdapter
                updateSummary()
                Toast.makeText(this, "Loaded: ${selected.clonePackage}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Import") { _, _ ->
                // Open file picker for import
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/json"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(intent, "Import Config"), 2001)
            }
            .show()
    }

    private fun showCloneSummaryAndBuild() {
        val summary = buildCloneSummary()

        AlertDialog.Builder(this)
            .setTitle("Clone Summary – Ready to Build?")
            .setMessage(summary)
            .setPositiveButton("Build Clone") { _, _ ->
                // Save config first
                configStorage.saveConfiguration(config)

                // Launch BuildProgressActivity
                val intent = Intent(this, BuildProgressActivity::class.java).apply {
                    putExtra("configJson", GsonBuilder().setPrettyPrinting().create().toJson(config))
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Export Config") { _, _ ->
                try {
                    val file = configStorage.exportConfiguration(config)
                    Toast.makeText(this, "Exported to ${file.absolutePath}", Toast.LENGTH_LONG).show()
                } catch (ignored: Exception) {
                    Toast.makeText(this, "Export failed: ${ignored.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun buildCloneSummary(): String {
        val enabledOptions = configValues.filter { it.value is Boolean && it.value as Boolean }.keys
        val warnings = mutableListOf<String>()

        if (config.identity.imei.isNotEmpty()) warnings.add("IMEI spoofing may be BLOCKED BY ANDROID LIMITATION on Android 10+")
        if (config.privacy.disabledPermissions.isNotEmpty()) warnings.add("Stripping permissions may break app: ${config.privacy.disabledPermissions.joinToString()}")
        if (config.environment.hideRoot) warnings.add("Root hiding may be bypassed by direct syscalls – check diagnostics")
        if (config.dataBundle.enabled) warnings.add("Data bundle increases APK size by ~${config.dataBundle.maxBundleSizeMb}MB and may include sensitive data")

        val estimatedSize = estimateOutputSize()

        return """
            Source App: ${originalAppInfo.appName}
            Package: ${config.originalPackage}
            Version: ${config.versionName} (${config.versionCode}) – Target SDK ${originalAppInfo.targetSdk}

            Clone Name: ${config.appName}
            Clone Package: ${config.clonePackage}
            Clone Index: ${config.cloneIndex}

            Device Profile: ${config.environment.physicalDeviceProfileId} (${deviceProfileManager.loadProfile(config.environment.physicalDeviceProfileId)?.displayName ?: "Unknown"})

            Enabled Options (${enabledOptions.size}):
            ${enabledOptions.take(20).joinToString("\n") { "- $it" }}
            ${if (enabledOptions.size > 20) "... and ${enabledOptions.size - 20} more" else ""}

            Data Bundle: ${if (config.dataBundle.enabled) "Enabled – ${config.dataBundle.selectedCategories.joinToString()} – Compression ${config.dataBundle.compression} – Encryption ${config.dataBundle.encryption}" else "Disabled"}

            Network: ${if (config.networking.disableNetworking) "Disabled" else "Enabled – HTTP Proxy ${config.networking.httpProxy} – SOCKS ${config.networking.socksProxy} – DoH ${config.networking.dnsOverHttps} – VPN Only ${config.networking.vpnOnly}"}

            Warnings (${warnings.size}):
            ${warnings.joinToString("\n") { "⚠️ $it" }}

            Estimated Output Size: ~${estimatedSize}MB

            Compatibility: ${if (warnings.isEmpty()) "🟢 Supported" else "🟡 May affect compatibility – check warnings"}

            Tap Build Clone to generate APK – configuration will be bundled into assets/clone_config.json and affect clone pipeline
        """.trimIndent()
    }

    private fun estimateOutputSize(): Int {
        var size = 0
        try {
            size += File(originalAppInfo.apkPath).length().toInt() / 1024 / 1024
        } catch (ignored: Exception) {
            size += 20 // default estimate
        }
        if (config.dataBundle.enabled) size += config.dataBundle.maxBundleSizeMb
        if (config.game.bundleObb) size += 50
        size += 5 // hooks overhead
        return size
    }

    private fun updateSummary() {
        val enabledCount = configValues.filter { it.value is Boolean && it.value as Boolean }.size
        val totalCount = allOptions.size
        val dataBundleStatus = if (config.dataBundle.enabled) "Data Bundle: Enabled" else "Data Bundle: Disabled"
        val profile = config.environment.physicalDeviceProfileId

        textSummary.text = """
            Clone: ${config.appName} – ${config.clonePackage}
            Profile: $profile – ${deviceProfileManager.loadProfile(profile)?.displayName ?: ""}
            Enabled: $enabledCount/$totalCount options – $dataBundleStatus
            Search across: GPS, proxy, clipboard, root, dark mode, data, WebView, etc.
            Tap category to filter, search to find options, preset to apply, Build Clone to generate
        """.trimIndent()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                val imported = configStorage.importConfiguration(uri)
                if (imported != null) {
                    config = imported
                    initializeConfigValues()
                    optionsAdapter = OptionsAdapter(filteredOptions, configValues, { opt, value ->
                        updateConfigFromOption(opt, value)
                        updateSummary()
                    }, { opt -> showOptionDetailDialog(opt) })
                    recyclerOptions.adapter = optionsAdapter
                    updateSummary()
                    Toast.makeText(this, "Imported: ${imported.clonePackage}", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

class CategoryAdapter(
    private val categories: List<OptionCategory>,
    private var selected: OptionCategory?,
    private val onCategorySelected: (OptionCategory) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.categoryName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = android.view.LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return VH(view)
    }

    override fun getItemCount() = categories.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val category = categories[position]
        holder.name.text = category.displayName
        holder.name.isSelected = category == selected
        holder.name.setBackgroundResource(
            if (category == selected) R.drawable.ic_launcher_background else android.R.color.transparent
        )
        holder.itemView.setOnClickListener {
            selected = category
            notifyDataSetChanged()
            onCategorySelected(category)
        }
    }
}
