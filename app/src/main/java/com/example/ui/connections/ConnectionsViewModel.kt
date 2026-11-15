package com.example.ui.connections

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.media.MediaProjectionSessionManager
import com.example.android.runtime.AndroidPermissionManager
import com.example.data.repository.ConnectionsRepository
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AiProviderType
import com.example.domain.model.CapabilityCategory
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.MediaProjectionConsentState
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ProviderConnectionStatus
import com.example.domain.model.TelegramConfig
import com.example.domain.provider.ProviderException
import com.example.domain.security.ApiKeyMasker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnectionsSubTab(val label: String) {
    PROVIDERS("AI Providers"),
    TELEGRAM("Telegram"),
    CAPABILITIES("Capabilities")
}

data class ConnectionsUiState(
    val selectedTab: ConnectionsSubTab = ConnectionsSubTab.PROVIDERS,
    val providers: List<ProviderConfig> = emptyList(),
    val refreshingProviders: Set<AiProviderType> = emptySet(),
    val refreshErrors: Map<AiProviderType, String> = emptyMap(),
    val telegramConfig: TelegramConfig = TelegramConfig(),
    val telegramFormError: String? = null,
    val capabilities: List<DeviceCapability> = emptyList(),
    val totalCapabilitiesCount: Int = 0,
    val availableCapabilitiesCount: Int = 0,
    val actionNeededCapabilitiesCount: Int = 0,
    val capabilityFilter: CapabilityCategory? = null,
    val accessibilityState: AccessibilityRuntimeState = AccessibilityRuntimeState.SERVICE_DISABLED,
    val accessibilityReason: String = "Enable Nexa Computer-Use Service in Android Accessibility Settings.",
    val canPostNotifications: Boolean = true,
    val mediaProjectionState: MediaProjectionConsentState = MediaProjectionConsentState.NOT_GRANTED,
    val mediaProjectionReason: String = "Screen capture permission not granted. Tap Grant Screen Capture when needed.",
    val mediaProjectionResolution: String? = null,
    val canDrawOverlays: Boolean = false,
    val installedAppsSheetVisible: Boolean = false,
    val installedAppsSearchQuery: String = "",
    val discoveredApps: List<InstalledAppInfo> = emptyList(),
    val appLaunchFeedback: String? = null
)

class ConnectionsViewModel(
    private val connectionsRepository: ConnectionsRepository,
    private val permissionManager: AndroidPermissionManager? = null,
    private val mediaProjectionSessionManager: MediaProjectionSessionManager? = null,
    private val appDiscovery: AndroidAppDiscovery? = null,
    private val appLauncher: AndroidAppLauncher? = null
) : ViewModel() {

    private val selectedTab = MutableStateFlow(ConnectionsSubTab.PROVIDERS)
    private val testingProviders = MutableStateFlow<Set<AiProviderType>>(emptySet())
    private val refreshingProviders = MutableStateFlow<Set<AiProviderType>>(emptySet())
    private val refreshErrors = MutableStateFlow<Map<AiProviderType, String>>(emptyMap())
    private val telegramFormError = MutableStateFlow<String?>(null)
    private val capabilityFilter = MutableStateFlow<CapabilityCategory?>(null)
    private val capabilityRefreshTrigger = MutableStateFlow(0)
    private val installedAppsSheetVisible = MutableStateFlow(false)
    private val installedAppsSearchQuery = MutableStateFlow("")
    private val discoveredApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    private val appLaunchFeedback = MutableStateFlow<String?>(null)

    private val providerAuxFlow = combine(
        testingProviders,
        refreshingProviders,
        refreshErrors
    ) { testing, refreshing, errors ->
        Triple(testing, refreshing, errors)
    }

    private data class AppsBrowserState(
        val visible: Boolean,
        val query: String,
        val apps: List<InstalledAppInfo>,
        val feedback: String?
    )

    private val appsBrowserFlow = combine(
        installedAppsSheetVisible,
        installedAppsSearchQuery,
        discoveredApps,
        appLaunchFeedback
    ) { visible, query, apps, feedback ->
        AppsBrowserState(visible, query, apps, feedback)
    }

    val uiState: StateFlow<ConnectionsUiState> = combine(
        selectedTab,
        connectionsRepository.providers,
        providerAuxFlow,
        connectionsRepository.telegramConfig,
        telegramFormError,
        connectionsRepository.capabilities,
        capabilityFilter,
        capabilityRefreshTrigger,
        appsBrowserFlow
    ) { values ->
        val tab = values[0] as ConnectionsSubTab
        @Suppress("UNCHECKED_CAST")
        val providers = values[1] as List<ProviderConfig>
        @Suppress("UNCHECKED_CAST")
        val aux = values[2] as Triple<Set<AiProviderType>, Set<AiProviderType>, Map<AiProviderType, String>>
        val telegram = values[3] as TelegramConfig
        val tgError = values[4] as String?
        @Suppress("UNCHECKED_CAST")
        val capabilities = values[5] as List<DeviceCapability>
        val capFilter = values[6] as CapabilityCategory?
        val appsState = values[8] as AppsBrowserState

        val (testingSet, refreshingSet, errorsMap) = aux
        val enrichedProviders = providers.map { cfg ->
            if (cfg.providerType in testingSet) {
                cfg.copy(connectionStatus = ProviderConnectionStatus.Testing)
            } else {
                cfg
            }
        }
        val filteredCaps = if (capFilter == null) {
            capabilities
        } else {
            capabilities.filter { it.type.category == capFilter }
        }
        val totalCaps = capabilities.size
        val availableCaps = capabilities.count { it.status == CapabilityStatus.AVAILABLE }
        val actionNeededCaps = capabilities.count { it.status != CapabilityStatus.AVAILABLE }

        val permSnapshot = permissionManager?.inspect()
        val accState = permSnapshot?.accessibilityState ?: if (NexaAccessibilityService.runtimeState.value.isConnected) {
            AccessibilityRuntimeState.SERVICE_ENABLED
        } else {
            AccessibilityRuntimeState.SERVICE_DISABLED
        }
        val accReason = permSnapshot?.accessibilityReason
            ?: "Enable Nexa Computer-Use Service in Android Accessibility Settings."
        val canNotify = permSnapshot?.canPostNotifications ?: true
        val projSnap = mediaProjectionSessionManager?.state?.value
        val projState = projSnap?.consentState ?: permSnapshot?.mediaProjectionState
            ?: MediaProjectionConsentState.NOT_GRANTED
        val projReason = projSnap?.statusReason ?: permSnapshot?.mediaProjectionReason
            ?: "Screen capture permission not granted. Tap Grant Screen Capture when needed."
        val projRes = if (projSnap != null && projSnap.isSessionActive && projSnap.width > 0 && projSnap.height > 0) {
            "${projSnap.width}x${projSnap.height}"
        } else {
            null
        }

        ConnectionsUiState(
            selectedTab = tab,
            providers = enrichedProviders,
            refreshingProviders = refreshingSet,
            refreshErrors = errorsMap,
            telegramConfig = telegram,
            telegramFormError = tgError,
            capabilities = filteredCaps,
            totalCapabilitiesCount = totalCaps,
            availableCapabilitiesCount = availableCaps,
            actionNeededCapabilitiesCount = actionNeededCaps,
            capabilityFilter = capFilter,
            accessibilityState = accState,
            accessibilityReason = accReason,
            canPostNotifications = canNotify,
            mediaProjectionState = projState,
            mediaProjectionReason = projReason,
            mediaProjectionResolution = projRes,
            canDrawOverlays = permSnapshot?.canDrawOverlays ?: false,
            installedAppsSheetVisible = appsState.visible,
            installedAppsSearchQuery = appsState.query,
            discoveredApps = appsState.apps,
            appLaunchFeedback = appsState.feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ConnectionsUiState()
    )

    fun selectTab(tab: ConnectionsSubTab) {
        selectedTab.value = tab
    }

    fun selectCapabilityCategory(category: CapabilityCategory?) {
        capabilityFilter.value = category
    }

    fun openInstalledAppsSheet() {
        installedAppsSheetVisible.value = true
        appLaunchFeedback.value = null
        loadDiscoveredApps(installedAppsSearchQuery.value)
    }

    fun closeInstalledAppsSheet() {
        installedAppsSheetVisible.value = false
    }

    fun updateAppsSearchQuery(query: String) {
        installedAppsSearchQuery.value = query
        loadDiscoveredApps(query)
    }

    private fun loadDiscoveredApps(query: String) {
        val discovery = appDiscovery ?: return
        viewModelScope.launch {
            val batch = withContext(Dispatchers.Default) {
                discovery.listLaunchableApps(
                    query = query.takeIf { it.isNotBlank() },
                    includeSystemApps = true,
                    limit = 80,
                    offset = 0
                )
            }
            discoveredApps.value = batch.apps
        }
    }

    fun testLaunchDiscoveredApp(packageName: String) {
        val launcher = appLauncher ?: return
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.Default) {
                launcher.launch(packageName)
            }
            appLaunchFeedback.value = outcome.message
        }
    }

    fun dismissAppLaunchFeedback() {
        appLaunchFeedback.value = null
    }

    fun refreshCapabilities() {
        connectionsRepository.refreshCapabilities()
        capabilityRefreshTrigger.value += 1
    }

    fun createOverlaySettingsIntent(): Intent? =
        permissionManager?.buildOpenOverlaySettingsIntent()

    fun createScreenCaptureIntent(): Intent? =
        mediaProjectionSessionManager?.createScreenCaptureIntent()

    fun onMediaProjectionConsentResult(resultCode: Int, data: Intent?) {
        mediaProjectionSessionManager?.onConsentResult(resultCode, data)
        refreshCapabilities()
    }

    fun stopMediaProjectionSession() {
        mediaProjectionSessionManager?.stopSession(revokedBySystem = false)
        refreshCapabilities()
    }

    fun saveProviderConfig(
        type: AiProviderType,
        rawApiKey: String,
        baseUrl: String,
        selectedModelId: String?,
        manualModelId: String,
        setAsDefault: Boolean,
        hasExistingKey: Boolean,
        allowManualToolsOverride: Boolean? = null
    ): String? {
        val urlValidation = ApiKeyMasker.validateEndpointUrl(baseUrl, required = type.requiresBaseUrl)
        if (urlValidation is ApiKeyMasker.ValidationResult.Invalid) {
            return urlValidation.reason
        }
        if (rawApiKey.isNotBlank()) {
            val keyValidation = ApiKeyMasker.validateFormat(rawApiKey)
            if (keyValidation is ApiKeyMasker.ValidationResult.Invalid) {
                return keyValidation.reason
            }
        } else if (!hasExistingKey && !type.requiresBaseUrl) {
            return "Enter an API key to configure ${type.displayName}."
        }

        val cleanManual = manualModelId.trim()
        val effectiveModelId = selectedModelId?.takeIf { it.isNotBlank() }
            ?: cleanManual.takeIf { it.isNotBlank() }

        if (effectiveModelId.isNullOrBlank()) {
            return "Select a model or enter a manual model ID."
        }

        viewModelScope.launch {
            connectionsRepository.saveProvider(
                type = type,
                rawApiKey = rawApiKey.takeIf { it.isNotBlank() },
                baseUrl = baseUrl.ifBlank { type.defaultBaseUrl },
                selectedModelId = effectiveModelId,
                manualModelId = cleanManual,
                setAsDefault = setAsDefault,
                allowManualToolsOverride = allowManualToolsOverride
            )
            refreshErrors.value = refreshErrors.value - type
        }
        return null
    }

    fun disconnectProvider(type: AiProviderType) {
        viewModelScope.launch {
            connectionsRepository.clearProviderKey(type)
            refreshErrors.value = refreshErrors.value - type
        }
    }

    fun setDefaultProvider(type: AiProviderType, modelId: String?) {
        viewModelScope.launch {
            connectionsRepository.setDefaultProvider(type, modelId)
        }
    }

    fun testProviderConnection(config: ProviderConfig) {
        if (config.providerType in testingProviders.value) return
        viewModelScope.launch {
            testingProviders.value = testingProviders.value + config.providerType
            refreshErrors.value = refreshErrors.value - config.providerType
            try {
                connectionsRepository.testProviderConfig(config)
            } finally {
                testingProviders.value = testingProviders.value - config.providerType
            }
        }
    }

    fun refreshProviderModels(config: ProviderConfig) {
        if (config.providerType in refreshingProviders.value) return
        viewModelScope.launch {
            refreshingProviders.value = refreshingProviders.value + config.providerType
            refreshErrors.value = refreshErrors.value - config.providerType
            try {
                val result = connectionsRepository.refreshModels(config)
                result.onFailure { throwable ->
                    val message = (throwable as? ProviderException)?.error?.userMessage
                        ?: throwable.message
                        ?: "Failed to fetch models from ${config.providerType.displayName}."
                    refreshErrors.value = refreshErrors.value + (config.providerType to message)
                }
            } finally {
                refreshingProviders.value = refreshingProviders.value - config.providerType
            }
        }
    }

    fun saveTelegramPairingDraft(rawToken: String, handle: String) {
        val trimmedHandle = handle.trim()
        val trimmedToken = rawToken.trim()
        if (trimmedToken.isEmpty() && trimmedHandle.isEmpty()) {
            telegramFormError.value = "Enter a bot token or Telegram pairing handle."
            return
        }
        if (trimmedToken.isNotEmpty() && trimmedToken.length < 12) {
            telegramFormError.value = "Bot token looks too short."
            return
        }
        telegramFormError.value = null
        viewModelScope.launch {
            connectionsRepository.saveTelegramDraft(trimmedToken, trimmedHandle)
        }
    }

    fun updateTelegramPermissions(
        allowRemotePrompts: Boolean? = null,
        requireDeviceApproval: Boolean? = null,
        shareTaskSummaries: Boolean? = null
    ) {
        viewModelScope.launch {
            connectionsRepository.updateTelegramPermissions(
                allowRemotePrompts = allowRemotePrompts,
                requireDeviceApproval = requireDeviceApproval,
                shareTaskSummaries = shareTaskSummaries
            )
        }
    }

    fun disconnectTelegram() {
        telegramFormError.value = null
        viewModelScope.launch {
            connectionsRepository.disconnectTelegram()
        }
    }

    fun toggleCapabilityPreference(id: String, enabled: Boolean) {
        viewModelScope.launch {
            connectionsRepository.setCapabilityToggle(id, enabled)
        }
    }

    class Factory(
        private val connectionsRepository: ConnectionsRepository,
        private val permissionManager: AndroidPermissionManager? = null,
        private val mediaProjectionSessionManager: MediaProjectionSessionManager? = null,
        private val appDiscovery: AndroidAppDiscovery? = null,
        private val appLauncher: AndroidAppLauncher? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ConnectionsViewModel(
                connectionsRepository = connectionsRepository,
                permissionManager = permissionManager,
                mediaProjectionSessionManager = mediaProjectionSessionManager,
                appDiscovery = appDiscovery,
                appLauncher = appLauncher
            ) as T
        }
    }
}
