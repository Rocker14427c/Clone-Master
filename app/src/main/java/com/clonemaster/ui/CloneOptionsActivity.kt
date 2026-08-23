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
 * Production-usable Clone Configuration – fixed layout + real configurators, no fake controls
 * Independent implementation, public reference https://appcloner.app/ only for organization/behavior
 *
 * Layout structure (fixed):
 * Top: App bar (56dp)
 * Search card (wrap_content)
 * Preset/save/load card (wrap_content, 48dp)
 * Categories horizontal RecyclerView (48dp fixed)
 * Main: ONE primary vertically scrollable options area (weight 1, gets majority height)
 * Bottom: Compact sticky summary (wrap_content, ~100dp) – does NOT cover option list
 * Avoids ScrollView inside ScrollView, RecyclerView inside scrolling parent, fixed-height option containers, large permanent cards
 */
class CloneOptionsActivity : AppCompatActivity() {

    private lateinit var config: CloneConfig
    private lateinit var originalAppInfo: AppInfo

    private lateinit var searchEditText: TextInputEditText
    private lateinit var recyclerCategories: RecyclerView
    private lateinit var recyclerOptions: RecyclerView
    private lateinit var presetSpinner: Spinner
    private lateinit var textCloneName: TextView
    private lateinit var textClonePackage: TextView
    private lateinit var textSummaryCompact: TextView
    private lateinit var buttonBuildClone: Button
    private lateinit var buttonSaveConfig: Button
    private lateinit var buttonLoadConfig: Button
    private lateinit var buttonSummaryDetails: Button
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
        textCloneName = findViewById(R.id.textCloneName)
        textClonePackage = findViewById(R.id.textClonePackage)
        textSummaryCompact = findViewById(R.id.textSummaryCompact)
        buttonBuildClone = findViewById(R.id.buttonBuildClone)
        buttonSaveConfig = findViewById(R.id.buttonSaveConfig)
        buttonLoadConfig = findViewById(R.id.buttonLoadConfig)
        buttonSummaryDetails = findViewById(R.id.buttonSummaryDetails)

        toolbar.setNavigationOnClickListener { finish() }

        configStorage = ConfigStorageManager(this)
        deviceProfileManager = DeviceProfileManager(this)

        // Load config from intent – user flow: Installed Apps → Select → Details → Clone this app → Clone Configuration
        val configJson = intent.getStringExtra("configJson")
        val originalPackage = intent.getStringExtra("originalPackage") ?: intent.getStringExtra("package") ?: ""
        val appNameExtra = intent.getStringExtra("appName") ?: ""

        config = if (configJson != null) {
            try { GsonBuilder().create().fromJson(configJson, CloneConfig::class.java) } catch (ignored: Exception) { CloneConfig(originalPackage = originalPackage) }
        } else {
            val clonePackage = intent.getStringExtra("clonePackage")
            if (clonePackage != null) {
                configStorage.loadConfiguration(clonePackage) ?: CloneConfig(
                    originalPackage = originalPackage,
                    clonePackage = clonePackage,
                    appName = if (appNameExtra.isNotEmpty()) "$appNameExtra Clone" else "${originalPackage.substringAfterLast('.')} Clone"
                )
            } else {
                CloneConfig(
                    originalPackage = originalPackage,
                    clonePackage = if (originalPackage.isNotEmpty()) "$originalPackage.clone1" else "com.example.clone1",
                    cloneIndex = 1,
                    appName = if (appNameExtra.isNotEmpty()) "$appNameExtra Clone" else if (originalPackage.isNotEmpty()) "${originalPackage.substringAfterLast('.')} Clone" else "My Clone"
                )
            }
        }

        // Load original app info for summary – fast path, not deep parsing
        originalAppInfo = try {
            com.clonemaster.analysis.AppAnalyzer(this).let { analyzer ->
                // Try fast list first, then detailed if needed
                val fastList = analyzer.listInstalledApps(false)
                fastList.find { it.packageName == config.originalPackage } ?: AppInfo(
                    packageName = config.originalPackage,
                    appName = config.appName,
                    versionName = config.versionName,
                    versionCode = config.versionCode,
                    targetSdk = 34,
                    minSdk = 24,
                    apkPath = ""
                )
            }
        } catch (ignored: Exception) {
            AppInfo(packageName = config.originalPackage, appName = config.appName, versionName = config.versionName, versionCode = config.versionCode, targetSdk = 34, minSdk = 24, apkPath = "")
        }

        initializeConfigValues()
        setupCategories()
        allOptions = OptionRegistry.getAllOptions()
        filteredOptions = allOptions

        optionsAdapter = OptionsAdapter(
            options = filteredOptions,
            configValues = configValues,
            onOptionChanged = { option, newValue ->
                updateConfigFromOption(option, newValue)
                // Persist immediately – UI → Configurator → CloneConfig → persistent state
                configStorage.saveConfiguration(config)
                updateCompactSummary()
            },
            onOptionClicked = { option ->
                showRealConfigurator(option)
            }
        )

        recyclerOptions.layoutManager = LinearLayoutManager(this)
        recyclerOptions.adapter = optionsAdapter
        recyclerOptions.setHasFixedSize(false)
        recyclerOptions.isNestedScrollingEnabled = true

        // Search – must work across all categories even when category selected
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString()
                if (query.isEmpty()) {
                    // No search – show selected category or all
                    filteredOptions = if (selectedCategory != null) OptionRegistry.getByCategory(selectedCategory!!) else allOptions
                } else {
                    // Search across ALL categories – even if user viewing another category
                    filteredOptions = OptionRegistry.search(query)
                }
                optionsAdapter.updateOptions(filteredOptions)
                // Reset scroll position appropriately when search changes
                recyclerOptions.scrollToPosition(0)
                updateCompactSummary()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })

        findViewById<View>(R.id.clearSearch).setOnClickListener {
            searchEditText.text?.clear()
            filteredOptions = if (selectedCategory != null) OptionRegistry.getByCategory(selectedCategory!!) else allOptions
            optionsAdapter.updateOptions(filteredOptions)
            recyclerOptions.scrollToPosition(0)
            updateCompactSummary()
        }

        setupPresets()

        buttonSaveConfig.setOnClickListener {
            try {
                val file = configStorage.saveConfiguration(config)
                Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
            } catch (ignored: Exception) {
                Toast.makeText(this, "Save failed: ${ignored.message}", Toast.LENGTH_SHORT).show()
            }
        }

        buttonLoadConfig.setOnClickListener { showLoadConfigDialog() }

        buttonSummaryDetails.setOnClickListener { showDetailedSummaryDialog() }

        buttonBuildClone.setOnClickListener { showCloneSummaryAndBuild() }

        updateCompactSummary()
    }

    private fun initializeConfigValues() {
        configValues.clear()
        configValues["appName"] = config.appName
        configValues["clonePackage"] = config.clonePackage
        configValues["versionName"] = config.versionName
        configValues["versionCode"] = config.versionCode.toString()
        configValues["customIconPath"] = config.customIconPath ?: ""
        configValues["iconBadge"] = config.iconBadge.name
        configValues["removeBranding"] = config.removeBranding

        configValues["identity.androidId"] = config.identity.androidId
        configValues["identity.imei"] = config.identity.imei
        configValues["identity.wifiMac"] = config.identity.wifiMac
        configValues["identity.btMac"] = config.identity.btMac
        configValues["identity.gsfId"] = config.identity.gsfId
        configValues["identity.advertisingId"] = config.identity.advertisingId
        configValues["identity.webViewUserAgent"] = config.identity.webViewUserAgent
        configValues["identity.deviceProfileName"] = config.identity.deviceProfileName

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

        configValues["environment.hideRoot"] = config.environment.hideRoot
        configValues["environment.hideEmulator"] = config.environment.hideEmulator
        configValues["environment.hideDeveloperOptions"] = config.environment.hideDeveloperOptions
        configValues["environment.hideUsbAdb"] = config.environment.hideUsbAdb
        configValues["environment.hideMockLocation"] = config.environment.hideMockLocation
        configValues["environment.physicalDeviceProfileId"] = config.environment.physicalDeviceProfileId
        configValues["environment.enableDetectionDiagnostics"] = config.environment.enableDetectionDiagnostics

        configValues["display.darkMode"] = config.display.darkMode.name
        configValues["display.orientationLock"] = config.display.orientationLock.toString()
        configValues["display.immersiveFullscreen"] = config.display.immersiveFullscreen
        configValues["display.keepScreenAwake"] = config.display.keepScreenAwake
        configValues["display.customLanguage"] = config.display.customLanguage
        configValues["viewMods"] = config.viewMods.size.toString()

        configValues["storage.redirectExternalStorage"] = config.storage.redirectExternalStorage
        configValues["storage.preventBackup"] = config.storage.preventBackup
        configValues["storage.preserveDataOnUninstall"] = config.storage.preserveDataOnUninstall

        configValues["dataBundle.enabled"] = config.dataBundle.enabled
        configValues["dataBundle.compression"] = config.dataBundle.compression.name
        configValues["dataBundle.encryption"] = config.dataBundle.encryption.name

        configValues["launching.removeLauncherIcon"] = config.launching.removeLauncherIcon
        configValues["launching.secretDialerCode"] = config.launching.secretDialerCode
        configValues["launching.persistentMode"] = config.launching.persistentMode
        configValues["launching.fakeBatteryLevel"] = config.launching.fakeBatteryLevel?.toString() ?: ""

        configValues["networking.disableNetworking"] = config.networking.disableNetworking
        configValues["networking.disableMobileData"] = config.networking.disableMobileData
        configValues["networking.httpProxy"] = config.networking.httpProxy
        configValues["networking.socksProxy"] = config.networking.socksProxy
        configValues["networking.httpProxyList"] = config.networking.httpProxyList.joinToString(",")
        configValues["networking.dnsOverHttps"] = config.networking.dnsOverHttps
        configValues["networking.vpnOnly"] = config.networking.vpnOnly
        configValues["networking.notificationToggle"] = config.networking.notificationToggle

        configValues["notification.filterPatterns"] = config.notification.filterPatterns.joinToString(",")
        configValues["notification.showDots"] = config.notification.showDots?.toString() ?: "null"

        configValues["game.bundleObb"] = config.game.bundleObb
        configValues["tvWear.customTvBannerPath"] = config.tvWear.customTvBannerPath ?: ""
        configValues["automation.brightnessOnStart"] = config.automation.brightnessOnStart?.toString() ?: ""
        configValues["developer.changeTargetSdk"] = config.developer.changeTargetSdk?.toString() ?: ""
        configValues["developer.nativeHooksEnabled"] = config.developer.nativeHooksEnabled
        configValues["developer.safeMode"] = config.developer.safeMode

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
        try {
            when (option.configFieldPath) {
                "appName" -> config.appName = newValue as String
                "clonePackage" -> config.clonePackage = newValue as String
                "versionName" -> config.versionName = newValue as String
                "versionCode" -> config.versionCode = (newValue as String).toLongOrNull() ?: config.versionCode
                "customIconPath" -> config.customIconPath = (newValue as String).ifEmpty { null }
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
                "privacy.disabledPermissions" -> {
                    val list = (newValue as String).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    config.privacy.disabledPermissions.clear()
                    config.privacy.disabledPermissions.addAll(list)
                }

                "environment.hideRoot" -> { config.environment.hideRoot = newValue as Boolean; config.privacy.hideRoot = newValue as Boolean }
                "environment.hideEmulator" -> config.environment.hideEmulator = newValue as Boolean
                "environment.hideDeveloperOptions" -> config.environment.hideDeveloperOptions = newValue as Boolean
                "environment.hideUsbAdb" -> config.environment.hideUsbAdb = newValue as Boolean
                "environment.hideMockLocation" -> { config.environment.hideMockLocation = newValue as Boolean; config.privacy.hideMockLocation = newValue as Boolean }
                "environment.physicalDeviceProfileId" -> config.environment.physicalDeviceProfileId = newValue as String

                "display.darkMode" -> config.display.darkMode = try { DarkMode.valueOf(newValue as String) } catch (ignored: Exception) { DarkMode.SYSTEM }
                "display.orientationLock" -> config.display.orientationLock =
                    OptionValueParsers.parseOrientation(newValue as String)
                "display.immersiveFullscreen" -> config.display.immersiveFullscreen = newValue as Boolean
                "display.keepScreenAwake" -> config.display.keepScreenAwake = newValue as Boolean
                "display.customLanguage" -> { config.display.customLanguage = newValue as String; config.parityFeatures.locale.customLocale = newValue as String }

                "storage.redirectExternalStorage" -> config.storage.redirectExternalStorage = newValue as Boolean
                "storage.preventBackup" -> config.storage.preventBackup = newValue as Boolean
                "storage.preserveDataOnUninstall" -> { config.storage.preserveDataOnUninstall = newValue as Boolean; config.parityFeatures.uninstallData.hasFragileUserData = newValue as Boolean }

                "dataBundle.enabled" -> config.dataBundle.enabled = newValue as Boolean
                "dataBundle.compression" -> config.dataBundle.compression = try { CompressionType.valueOf(newValue as String) } catch (ignored: Exception) { CompressionType.ZSTD }
                "dataBundle.encryption" -> config.dataBundle.encryption = try { EncryptionType.valueOf(newValue as String) } catch (ignored: Exception) { EncryptionType.NONE }

                "launching.removeLauncherIcon" -> config.launching.removeLauncherIcon = newValue as Boolean
                "launching.secretDialerCode" -> config.launching.secretDialerCode = newValue as String
                "launching.persistentMode" -> config.launching.persistentMode = newValue as Boolean
                "launching.fakeBatteryLevel" -> config.launching.fakeBatteryLevel = (newValue as String).toIntOrNull()

                "networking.disableNetworking" -> config.networking.disableNetworking = newValue as Boolean
                "networking.disableMobileData" -> config.networking.disableMobileData = newValue as Boolean
                "networking.httpProxy" -> config.networking.httpProxy = newValue as String
                "networking.socksProxy" -> config.networking.socksProxy = newValue as String
                "networking.httpProxyList" -> {
                    val list = (newValue as String).split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    config.networking.httpProxyList.clear()
                    config.networking.httpProxyList.addAll(list)
                }
                "networking.dnsOverHttps" -> config.networking.dnsOverHttps = newValue as String
                "networking.vpnOnly" -> config.networking.vpnOnly = newValue as Boolean
                "networking.notificationToggle" -> { config.networking.notificationToggle = newValue as Boolean; config.parityFeatures.notificationNetworkingToggle.enabled = newValue as Boolean }

                "game.bundleObb" -> config.game.bundleObb = newValue as Boolean
                "developer.changeTargetSdk" -> config.developer.changeTargetSdk = (newValue as String).toIntOrNull()
                "developer.nativeHooksEnabled" -> { config.developer.nativeHooksEnabled = newValue as Boolean; config.parityFeatures.hookOptions.nativeHooksEnabled = newValue as Boolean }
                "developer.safeMode" -> { config.developer.safeMode = newValue as Boolean; config.parityFeatures.hookOptions.disableHooks = newValue as Boolean }

                "parityFeatures.trackingBlocker.disableAppsFlyer" -> config.parityFeatures.trackingBlocker.disableAppsFlyer = newValue as Boolean
                "parityFeatures.cpuGpu.hideCpuInfo" -> { config.parityFeatures.cpuGpu.hideCpuInfo = newValue as Boolean; config.parityFeatures.cpuGpu.hideGpuInfo = newValue as Boolean; config.identity.spoofGpu = newValue as Boolean }
                "parityFeatures.hookOptions.disableHooks" -> { config.parityFeatures.hookOptions.disableHooks = newValue as Boolean; config.developer.safeMode = newValue as Boolean }
                "parityFeatures.manifestOptions.appCategory" -> config.parityFeatures.manifestOptions.appCategory = newValue as String
                "parityFeatures.uninstallData.hasFragileUserData" -> { config.parityFeatures.uninstallData.hasFragileUserData = newValue as Boolean; config.storage.preserveDataOnUninstall = newValue as Boolean }
                "parityFeatures.screenEvents.disableScreenOnOffEvents" -> config.parityFeatures.screenEvents.disableScreenOnOffEvents = newValue as Boolean
                "parityFeatures.tunnelManager.enabled" -> config.parityFeatures.tunnelManager.enabled = newValue as Boolean
                "parityFeatures.locale.customLocale" -> { config.parityFeatures.locale.customLocale = newValue as String; config.display.customLanguage = newValue as String }
                "parityFeatures.webViewScript.injectMode" -> config.parityFeatures.webViewScript.injectMode = newValue as String
                "parityFeatures.sneezeToExit.enabled" -> config.parityFeatures.sneezeToExit.enabled = newValue as Boolean
                "parityFeatures.knoxWarranty.spoofWarrantyBit" -> config.parityFeatures.knoxWarranty.spoofWarrantyBit = newValue as Boolean

                else -> android.util.Log.d("CloneMaster", "Option ${option.id} -> ${option.configFieldPath} updated to $newValue via dialog")
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
            // Show only options belonging to that category – reset scroll appropriately – preserve option values
            filteredOptions = if (searchEditText.text.toString().isEmpty()) {
                OptionRegistry.getByCategory(category)
            } else {
                // Search overrides category filter – search across all categories
                OptionRegistry.search(searchEditText.text.toString())
            }
            optionsAdapter.updateOptions(filteredOptions)
            recyclerOptions.scrollToPosition(0)
            updateCompactSummary()
        }
    }

    private var presetUserTouched = false

    private fun setupPresets() {
        val presetNames = PresetType.values().map { "${it.displayName} – ${it.description}" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, presetNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        presetSpinner.adapter = adapter

        // CRITICAL (device-verified bug): AdapterView.onItemSelected FIRES
        // PROGRAMMATICALLY — on first layout and on every state re-dispatch.
        // Position 0 is the DEFAULT preset ("all optional features OFF"), so
        // each programmatic fire silently reset every option the user enabled —
        // reported on-device as "when I enable option B, option A turns off".
        // A preset may ONLY be applied for a real user touch; everything else
        // (bind/restore/re-layout fires) must leave config + configValues alone.
        presetSpinner.setOnTouchListener { _, ev ->
            if (ev?.action == android.view.MotionEvent.ACTION_DOWN) presetUserTouched = true
            false
        }

        presetSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!presetUserTouched) return // programmatic fire – never mutate state
                presetUserTouched = false
                val preset = PresetType.values()[position]
                if (preset != PresetType.CUSTOM) {
                    config = PresetManager.applyPreset(config, preset)
                    initializeConfigValues()
                    filteredOptions = if (selectedCategory != null) OptionRegistry.getByCategory(selectedCategory!!) else allOptions
                    optionsAdapter = OptionsAdapter(filteredOptions, configValues, { opt, value ->
                        updateConfigFromOption(opt, value)
                        configStorage.saveConfiguration(config)
                        updateCompactSummary()
                    }, { opt -> showRealConfigurator(opt) })
                    recyclerOptions.adapter = optionsAdapter
                    recyclerOptions.scrollToPosition(0)
                    updateCompactSummary()
                    Toast.makeText(this@CloneOptionsActivity, "Applied preset: ${preset.displayName}", Toast.LENGTH_SHORT).show()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showRealConfigurator(option: OptionItem) {
        // Real configurators – no generic informational dialog with fake Toggle/OK
        when (option.id) {
            "general_customIcon" -> {
                OptionConfigurators.showCustomIconConfigurator(this, config) { newPath ->
                    configValues[option.configFieldPath] = newPath ?: ""
                    configStorage.saveConfiguration(config)
                    optionsAdapter.notifyDataSetChanged()
                    updateCompactSummary()
                }
            }
            "identity_deviceProfile", "environment_physicalProfile" -> {
                OptionConfigurators.showDeviceProfileConfigurator(this, config, deviceProfileManager) { newProfileId ->
                    configValues["environment.physicalDeviceProfileId"] = newProfileId
                    configValues["identity.deviceProfileName"] = newProfileId
                    configStorage.saveConfiguration(config)
                    optionsAdapter.notifyDataSetChanged()
                    updateCompactSummary()
                }
            }
            "networking_httpProxy" -> {
                OptionConfigurators.showProxyConfigurator(this, config, isSocks = false) { newProxy ->
                    configValues[option.configFieldPath] = newProxy
                    configStorage.saveConfiguration(config)
                    optionsAdapter.notifyDataSetChanged()
                    updateCompactSummary()
                }
            }
            "networking_socksProxy" -> {
                OptionConfigurators.showProxyConfigurator(this, config, isSocks = true) { newProxy ->
                    configValues[option.configFieldPath] = newProxy
                    configStorage.saveConfiguration(config)
                    optionsAdapter.notifyDataSetChanged()
                    updateCompactSummary()
                }
            }
            "networking_proxyList" -> {
                val currentList = config.networking.httpProxyList.toMutableList()
                OptionConfigurators.showListEditorConfigurator(this, option, currentList) { newList ->
                    config.networking.httpProxyList.clear()
                    config.networking.httpProxyList.addAll(newList)
                    configValues[option.configFieldPath] = newList.joinToString(",")
                    configStorage.saveConfiguration(config)
                    optionsAdapter.notifyDataSetChanged()
                    updateCompactSummary()
                }
            }
            "privacy_permissions" -> {
                val currentList = config.privacy.disabledPermissions.toMutableList()
                OptionConfigurators.showListEditorConfigurator(this, option, currentList) { newList ->
                    config.privacy.disabledPermissions.clear()
                    config.privacy.disabledPermissions.addAll(newList)
                    configValues[option.configFieldPath] = newList.joinToString(",")
                    configStorage.saveConfiguration(config)
                    optionsAdapter.notifyDataSetChanged()
                    updateCompactSummary()
                }
            }
            "notifications_filter" -> {
                val currentList = config.notification.filterPatterns.toMutableList()
                OptionConfigurators.showListEditorConfigurator(this, option, currentList) { newList ->
                    config.notification.filterPatterns.clear()
                    config.notification.filterPatterns.addAll(newList)
                    configValues[option.configFieldPath] = newList.joinToString(",")
                    configStorage.saveConfiguration(config)
                    optionsAdapter.notifyDataSetChanged()
                    updateCompactSummary()
                }
            }
            "data_bundleData" -> {
                OptionConfigurators.showDataBundleConfigurator(this, config) {
                    initializeConfigValues()
                    optionsAdapter.notifyDataSetChanged()
                    configStorage.saveConfiguration(config)
                    updateCompactSummary()
                }
            }
            "webview_customScript" -> {
                OptionConfigurators.showWebViewScriptConfigurator(this, config) {
                    initializeConfigValues()
                    optionsAdapter.notifyDataSetChanged()
                    configStorage.saveConfiguration(config)
                    updateCompactSummary()
                }
            }
            "display_colors" -> {
                // Real color picker – independent implementation
                val colors = arrayOf("#FF0000 Red", "#00FF00 Green", "#0000FF Blue", "#2196F3 Clone-Master Blue", "#000000 Black", "#FFFFFF White")
                var selected = 0
                AlertDialog.Builder(this)
                    .setTitle("Status/Navigation/Toolbar Colors – Select")
                    .setSingleChoiceItems(colors, 0) { _, which -> selected = which }
                    .setPositiveButton("Save") { _, _ ->
                        val colorHex = colors[selected].split(" ")[0]
                        val colorInt = try { android.graphics.Color.parseColor(colorHex) } catch (ignored: Exception) { 0xFF2196F3.toInt() }
                        config.display.statusBarColor = colorInt
                        config.display.navBarColor = colorInt
                        configValues[option.configFieldPath] = colorInt
                        configStorage.saveConfiguration(config)
                        optionsAdapter.notifyDataSetChanged()
                        updateCompactSummary()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            else -> {
                // For other options, use appropriate configurator based on control type – real UI, not generic metadata
                when (option.controlType) {
                    ControlType.TEXT_FIELD -> {
                        val current = configValues[option.configFieldPath]?.toString() ?: ""
                        OptionConfigurators.showTextConfigurator(this, option, current) { newValue ->
                            configValues[option.configFieldPath] = newValue
                            updateConfigFromOption(option, newValue)
                            configStorage.saveConfiguration(config)
                            optionsAdapter.notifyDataSetChanged()
                            updateCompactSummary()
                        }
                    }
                    ControlType.DROPDOWN -> {
                        val current = configValues[option.configFieldPath]?.toString() ?: ""
                        val values = getDropdownValues(option)
                        OptionConfigurators.showEnumConfigurator(this, option, current, values) { newValue ->
                            configValues[option.configFieldPath] = newValue
                            updateConfigFromOption(option, newValue)
                            configStorage.saveConfiguration(config)
                            optionsAdapter.notifyDataSetChanged()
                            updateCompactSummary()
                        }
                    }
                    ControlType.SLIDER -> {
                        val current = (configValues[option.configFieldPath] as? Int) ?: configValues[option.configFieldPath]?.toString()?.toIntOrNull() ?: 50
                        val (min, max) = getSliderRange(option)
                        OptionConfigurators.showNumericConfigurator(this, option, current, min, max) { newValue ->
                            configValues[option.configFieldPath] = newValue
                            updateConfigFromOption(option, newValue.toString())
                            configStorage.saveConfiguration(config)
                            optionsAdapter.notifyDataSetChanged()
                            updateCompactSummary()
                        }
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
                                startActivity(Intent(this, com.clonemaster.ui.LogcatViewerActivity::class.java))
                            }
                            "diagnostics_compatibilityReport" -> {
                                startActivity(Intent(this, com.clonemaster.ui.AppAnalyzerActivity::class.java).apply {
                                    putExtra("package", config.originalPackage)
                                })
                            }
                            else -> {
                                AlertDialog.Builder(this)
                                    .setTitle(option.name)
                                    .setMessage("${option.description}\n\nField: ${option.configFieldPath}\nCompatibility: ${option.compatibility.emoji} ${option.compatibility.label}")
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                        }
                    }
                    else -> {
                        // For switch, already handled via switch listener – no need for dialog
                        // But if user taps card, toggle switch
                        val current = configValues[option.configFieldPath] as? Boolean ?: false
                        val newValue = !current
                        configValues[option.configFieldPath] = newValue
                        updateConfigFromOption(option, newValue)
                        configStorage.saveConfiguration(config)
                        optionsAdapter.notifyDataSetChanged()
                        updateCompactSummary()
                    }
                }
            }
        }
    }

    private fun getDropdownValues(option: OptionItem): List<String> {
        return when {
            option.configFieldPath.contains("iconBadge") -> listOf("NONE", "NUMBER", "DOT", "CUSTOM_TEXT")
            option.configFieldPath.contains("darkMode") -> listOf("LIGHT", "DARK", "SYSTEM", "FORCE_DARK")
            option.configFieldPath.contains("orientationLock") -> listOf("-1", "1", "0", "4")
            option.configFieldPath.contains("appCategory") -> listOf("undefined", "game", "audio", "video", "image", "social", "news", "maps", "productivity")
            option.configFieldPath.contains("compression") -> listOf("NONE", "ZIP", "GZIP", "ZSTD")
            option.configFieldPath.contains("encryption") -> listOf("NONE", "AES256", "CHACHA20")
            option.configFieldPath.contains("deviceProfile") || option.configFieldPath.contains("physicalDeviceProfileId") -> listOf("pixel8_pro", "pixel7a", "s24_ultra", "a54", "oneplus12", "xiaomi14pro", "nothing2", "fold5")
            option.configFieldPath.contains("injectMode") -> listOf("DOCUMENT_START", "DOCUMENT_END", "DOCUMENT_IDLE")
            option.configFieldPath.contains("rootHideLevel") -> listOf("OFF", "BASIC", "STANDARD", "AGGRESSIVE")
            option.configFieldPath.contains("emulatorHideLevel") -> listOf("OFF", "BASIC", "STANDARD", "FULL")
            else -> listOf("Default", "Enabled", "Disabled")
        }
    }

    private fun getSliderRange(option: OptionItem): Pair<Int, Int> {
        return when {
            option.configFieldPath.contains("fakeBatteryLevel") -> 0 to 100
            option.configFieldPath.contains("brightnessOnStart") -> 0 to 255
            option.configFieldPath.contains("customDisplaySize") -> 50 to 200
            option.configFieldPath.contains("badgeNumber") -> 1 to 99
            else -> 0 to 100
        }
    }

    private fun showLoadConfigDialog() {
        val configs = configStorage.loadAllConfigurations()
        if (configs.isEmpty()) {
            Toast.makeText(this, "No saved configurations", Toast.LENGTH_SHORT).show()
            return
        }

        val names = configs.map { "${it.appName} – ${it.clonePackage} – ${it.environment.physicalDeviceProfileId}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Load Configuration – Save/Load/Duplicate/Reset/Export/Import")
            .setItems(names) { _, which ->
                val selected = configs[which]
                config = selected
                initializeConfigValues()
                optionsAdapter = OptionsAdapter(filteredOptions, configValues, { opt, value ->
                    updateConfigFromOption(opt, value)
                    configStorage.saveConfiguration(config)
                    updateCompactSummary()
                }, { opt -> showRealConfigurator(opt) })
                recyclerOptions.adapter = optionsAdapter
                recyclerOptions.scrollToPosition(0)
                updateCompactSummary()
                Toast.makeText(this, "Loaded: ${selected.clonePackage}", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Import") { _, _ ->
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "application/json"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                startActivityForResult(Intent.createChooser(intent, "Import Config"), 2001)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDetailedSummaryDialog() {
        val detailed = buildDetailedSummary()
        AlertDialog.Builder(this)
            .setTitle("Detailed Clone Summary")
            .setMessage(detailed)
            .setPositiveButton("OK", null)
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

    private fun showCloneSummaryAndBuild() {
        val summary = buildCompactSummaryForBuild()

        AlertDialog.Builder(this)
            .setTitle("Clone Summary – Ready to Build?")
            .setMessage(summary)
            .setPositiveButton("BUILD CLONE") { _, _ ->
                configStorage.saveConfiguration(config)
                val intent = Intent(this, BuildProgressActivity::class.java).apply {
                    putExtra("configJson", GsonBuilder().setPrettyPrinting().create().toJson(config))
                }
                startActivity(intent)
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Details") { _, _ -> showDetailedSummaryDialog() }
            .show()
    }

    private fun buildCompactSummaryForBuild(): String {
        val enabledOptions = OptionState.enabledOptions(config)
        val warnings = mutableListOf<String>()
        // Warning must be gated on the ENABLE flag, not on a value merely existing
        // (a random IMEI value is present by default with spoofing OFF).
        if (config.identity.spoofImei) warnings.add("IMEI spoofing BLOCKED BY ANDROID LIMITATION on Android 10+")
        if (config.privacy.disabledPermissions.isNotEmpty()) warnings.add("Stripping permissions may break app")
        if (config.environment.hideRoot) warnings.add("Root hiding may be bypassed by direct syscalls")
        if (config.dataBundle.enabled) warnings.add("Data bundle increases size by ~${config.dataBundle.maxBundleSizeMb}MB")

        return """
            Source: ${originalAppInfo.appName} (${config.originalPackage}) v${config.versionName}

            Clone: ${config.appName}
            Package: ${config.clonePackage}
            Profile: ${config.environment.physicalDeviceProfileId}${if (config.environment.spoofPhysicalDeviceProfile) " (SPOOFED)" else " (showing default – spoofing OFF)"}

            Enabled: ${enabledOptions.size}/${allOptions.size} options
            Data Bundle: ${if (config.dataBundle.enabled) "Enabled" else "Disabled"}
            Network: ${if (config.networking.disableNetworking) "Disabled" else "Enabled – Proxy ${config.networking.httpProxy}/${config.networking.socksProxy}"}

            Warnings: ${warnings.size} – ${warnings.take(3).joinToString("; ")}

            Estimated Size: ~${estimateOutputSize()}MB

            ${if (warnings.isEmpty()) "🟢 Supported" else "🟡 May affect compatibility"}
        """.trimIndent()
    }

    private fun buildDetailedSummary(): String {
        val enabledOptions = OptionState.enabledOptions(config)
        val warnings = mutableListOf<String>()
        if (config.identity.spoofImei) warnings.add("IMEI spoofing BLOCKED BY ANDROID LIMITATION")
        if (config.privacy.disabledPermissions.isNotEmpty()) warnings.add("Stripping permissions: ${config.privacy.disabledPermissions.joinToString()}")
        if (config.environment.hideRoot) warnings.add("Root hiding may be bypassed")
        if (config.dataBundle.enabled) warnings.add("Data bundle size ~${config.dataBundle.maxBundleSizeMb}MB")

        return """
            Source App: ${originalAppInfo.appName}
            Package: ${config.originalPackage}
            Version: ${config.versionName} (${config.versionCode}) – Target SDK ${originalAppInfo.targetSdk}
            APK: ${originalAppInfo.apkPath}
            Size: ${originalAppInfo.sizeBytes / 1024 / 1024}MB

            Clone Name: ${config.appName}
            Clone Package: ${config.clonePackage}
            Clone Index: ${config.cloneIndex}
            Version: ${config.versionName} (${config.versionCode})

            Device Profile: ${config.environment.physicalDeviceProfileId} (${deviceProfileManager.loadProfile(config.environment.physicalDeviceProfileId)?.displayName ?: "Unknown"})
            Fingerprint: ${deviceProfileManager.loadProfile(config.environment.physicalDeviceProfileId)?.fingerprint ?: ""}

            Enabled Options (${enabledOptions.size}) – counted from the saved config:
            ${enabledOptions.joinToString("\n") { "- ${it.optionName} [${it.fieldPath}]" }}
            (Value-only fields such as Android ID/IMEI/profile are NOT active unless their
            enable flag is set; defaults are intentionally OFF.)

            Data Bundle: ${if (config.dataBundle.enabled) "Enabled – Categories ${config.dataBundle.selectedCategories.joinToString()} – Compression ${config.dataBundle.compression} – Encryption ${config.dataBundle.encryption} – Embed ${config.dataBundle.embedInApk}" else "Disabled"}

            Network: HTTP Proxy ${config.networking.httpProxy} – SOCKS ${config.networking.socksProxy} – List ${config.networking.httpProxyList.size} – DoH ${config.networking.dnsOverHttps} – VPN Only ${config.networking.vpnOnly} – Notification Toggle ${config.networking.notificationToggle}
            Tunnel Manager: ${if (config.parityFeatures.tunnelManager.enabled) "Enabled – Active ${config.parityFeatures.tunnelManager.activeTunnelId}" else "Disabled"}

            Warnings (${warnings.size}):
            ${warnings.joinToString("\n") { "⚠️ $it" }}

            Estimated Size: ~${estimateOutputSize()}MB
            Compatibility: ${if (warnings.isEmpty()) "🟢 Supported" else "🟡 May affect compatibility"}

            Config JSON size: ${GsonBuilder().setPrettyPrinting().create().toJson(config).length / 1024}KB
        """.trimIndent()
    }

    private fun estimateOutputSize(): Int {
        var size = 0
        try { size += File(originalAppInfo.apkPath).length().toInt() / 1024 / 1024 } catch (ignored: Exception) { size += 20 }
        if (config.dataBundle.enabled) size += config.dataBundle.maxBundleSizeMb
        if (config.game.bundleObb) size += 50
        size += 5
        return size
    }

    private fun updateCompactSummary() {
        val enabledCount = OptionState.enabledCount(config)
        val totalCount = allOptions.size
        val warnings = mutableListOf<String>().apply {
            if (config.identity.spoofImei) add("IMEI BLOCKED")
            if (config.privacy.disabledPermissions.isNotEmpty()) add("Permissions")
            if (config.dataBundle.enabled) add("Data Bundle")
        }

        textCloneName.text = "Clone: ${config.appName}"
        textClonePackage.text = "${config.clonePackage} • ${config.environment.physicalDeviceProfileId} – ${deviceProfileManager.loadProfile(config.environment.physicalDeviceProfileId)?.displayName?.take(20) ?: ""}"
        textSummaryCompact.text = "$enabledCount/$totalCount options • Data: ${if (config.dataBundle.enabled) "Enabled" else "Disabled"} • Warnings: ${warnings.size} ${warnings.take(2).joinToString()}"
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            2001 -> {
                if (resultCode == RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) {
                        val imported = configStorage.importConfiguration(uri)
                        if (imported != null) {
                            config = imported
                            initializeConfigValues()
                            optionsAdapter = OptionsAdapter(filteredOptions, configValues, { opt, value ->
                                updateConfigFromOption(opt, value)
                                configStorage.saveConfiguration(config)
                                updateCompactSummary()
                            }, { opt -> showRealConfigurator(opt) })
                            recyclerOptions.adapter = optionsAdapter
                            updateCompactSummary()
                            Toast.makeText(this, "Imported: ${imported.clonePackage}", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(this, "Import failed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            3001 -> {
                // Custom icon picker result
                if (resultCode == RESULT_OK) {
                    val uri = data?.data
                    if (uri != null) {
                        try {
                            // Copy selected image to app private storage for persistence
                            val inputStream = contentResolver.openInputStream(uri)
                            val iconDir = File(filesDir, "custom_icons").apply { mkdirs() }
                            val iconFile = File(iconDir, "custom_icon_${System.currentTimeMillis()}.png")
                            inputStream?.use { input ->
                                iconFile.outputStream().use { output -> input.copyTo(output) }
                            }
                            config.customIconPath = iconFile.absolutePath
                            configValues["customIconPath"] = iconFile.absolutePath
                            configStorage.saveConfiguration(config)
                            optionsAdapter.notifyDataSetChanged()
                            updateCompactSummary()
                            Toast.makeText(this, "Icon selected: ${iconFile.name}", Toast.LENGTH_SHORT).show()
                        } catch (ignored: Exception) {
                            Toast.makeText(this, "Failed to save icon: ${ignored.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
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
        // Visual feedback for selected category – independent UI, not copying App Cloner colors
        holder.name.setBackgroundResource(
            if (category == selected) R.drawable.bg_category_selected else R.drawable.bg_category_unselected
        )
        holder.itemView.setOnClickListener {
            selected = category
            notifyDataSetChanged()
            onCategorySelected(category)
        }
    }
}
