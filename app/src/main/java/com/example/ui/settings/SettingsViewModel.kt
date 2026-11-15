package com.example.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.android.overlay.FloatingIndicatorController
import com.example.android.runtime.AndroidPermissionManager
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TaskRepository
import com.example.domain.agent.DiagnosticsExportBundle
import com.example.domain.agent.DiagnosticsExporter
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.AppearancePosture
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.DeviceCapability
import com.example.domain.model.FloatingIndicatorMode
import com.example.domain.model.MemoryEntry
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ResponseVerbosity
import com.example.domain.model.TelegramConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val defaultProvider: ProviderConfig? = null,
    val telegramConfig: TelegramConfig = TelegramConfig(),
    val conversationCount: Int = 0,
    val capabilityCount: Int = 14,
    val availableCapabilitiesCount: Int = 0,
    val canDrawOverlays: Boolean = false,
    val historyClearedMessage: String? = null,
    val memoryEntries: List<MemoryEntry> = emptyList(),
    val diagnosticsExport: DiagnosticsExportBundle? = null
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val connectionsRepository: ConnectionsRepository,
    private val chatRepository: ChatRepository,
    private val permissionManager: AndroidPermissionManager? = null,
    private val floatingIndicatorController: FloatingIndicatorController? = null,
    private val memoryRepository: MemoryRepository? = null,
    private val taskRepository: TaskRepository? = null
) : ViewModel() {

    private val clearedMessage = MutableStateFlow<String?>(null)
    private val permissionRefreshTick = MutableStateFlow(0)
    private val diagnosticsExportState = MutableStateFlow<DiagnosticsExportBundle?>(null)

    private val memoryFlow = memoryRepository?.memoryEntries ?: flowOf(emptyList())

    private val metaExtrasFlow = combine(
        clearedMessage,
        permissionRefreshTick,
        memoryFlow,
        diagnosticsExportState
    ) { cleared, _, memories, export ->
        Triple(cleared, memories, export)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        connectionsRepository.defaultProvider,
        connectionsRepository.telegramConfig,
        chatRepository.conversations,
        connectionsRepository.capabilities,
        metaExtrasFlow
    ) { values ->
        val settings = values[0] as AppSettings
        val defaultProv = values[1] as ProviderConfig?
        val telegram = values[2] as TelegramConfig
        @Suppress("UNCHECKED_CAST")
        val conversations = values[3] as List<*>
        @Suppress("UNCHECKED_CAST")
        val capabilities = values[4] as List<DeviceCapability>
        @Suppress("UNCHECKED_CAST")
        val extras = values[5] as Triple<String?, List<MemoryEntry>, DiagnosticsExportBundle?>

        SettingsUiState(
            settings = settings,
            defaultProvider = defaultProv,
            telegramConfig = telegram,
            conversationCount = conversations.size,
            capabilityCount = capabilities.size,
            availableCapabilitiesCount = capabilities.count { it.status == CapabilityStatus.AVAILABLE },
            canDrawOverlays = permissionManager?.canDrawOverlays() ?: false,
            historyClearedMessage = extras.first,
            memoryEntries = extras.second,
            diagnosticsExport = extras.third
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun refreshOverlayPermission() {
        permissionRefreshTick.value += 1
        floatingIndicatorController?.notifyOverlayPermissionChanged()
    }

    fun buildOpenOverlaySettingsIntent(): Intent? =
        permissionManager?.buildOpenOverlaySettingsIntent()

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(transform)
        }
    }

    fun selectAppearance(posture: AppearancePosture) {
        update { it.copy(appearancePosture = posture) }
    }

    fun cycleAppearance() {
        update {
            val entries = AppearancePosture.entries
            val next = entries[(entries.indexOf(it.appearancePosture) + 1) % entries.size]
            it.copy(appearancePosture = next)
        }
    }

    fun cycleVerbosity() {
        update {
            val entries = ResponseVerbosity.entries
            val next = entries[(entries.indexOf(it.responseVerbosity) + 1) % entries.size]
            it.copy(responseVerbosity = next)
        }
    }

    fun cycleFloatingIndicatorMode() {
        update {
            val entries = FloatingIndicatorMode.entries
            val next = entries[(entries.indexOf(it.floatingIndicatorMode) + 1) % entries.size]
            it.copy(floatingIndicatorMode = next)
        }
    }

    fun selectConfirmationMode(mode: ConfirmationMode) {
        update { it.copy(confirmationMode = mode) }
    }

    fun updateHistoryRetentionDays(days: Int) {
        val boundedDays = days.coerceIn(1, 365)
        viewModelScope.launch {
            settingsRepository.updateSettings { it.copy(historyRetentionDays = boundedDays) }
            val activeConversationIds = taskRepository?.getTasksSnapshot(limit = 20)
                ?.filter {
                    it.status in setOf(
                        AgentTaskStatus.RUNNING,
                        AgentTaskStatus.AWAITING_APPROVAL,
                        AgentTaskStatus.WAITING_FOR_USER_UNLOCK,
                        AgentTaskStatus.WAITING_FOR_PERMISSION,
                        AgentTaskStatus.WAITING_FOR_EXTERNAL_APP,
                        AgentTaskStatus.VERIFYING,
                        AgentTaskStatus.PAUSED
                    )
                }
                ?.mapNotNull { it.resumableSnapshot?.conversationId }
                ?.toSet()
                .orEmpty()
            val pruned = chatRepository.pruneExpiredConversations(
                retentionDays = boundedDays,
                activeConversationIds = activeConversationIds
            )
            clearedMessage.value = "Retention updated to $boundedDays days. Pruned $pruned expired user thread(s)."
        }
    }

    fun addMemory(text: String, category: String = "Preference") {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            memoryRepository?.addMemory(clean, category.trim().ifBlank { "Preference" })
        }
    }

    fun updateMemory(id: String, text: String, category: String = "Preference") {
        val clean = text.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            memoryRepository?.updateMemory(id, clean, category.trim().ifBlank { "Preference" })
        }
    }

    fun removeMemory(id: String) {
        viewModelScope.launch {
            memoryRepository?.removeMemory(id)
        }
    }

    fun clearMemories() {
        viewModelScope.launch {
            memoryRepository?.clearAll()
            clearedMessage.value = "Cleared saved on-device memory notes."
        }
    }

    fun prepareDiagnosticsExport() {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val providers = connectionsRepository.providers.first()
            val capabilities = connectionsRepository.capabilities.first()
            val tasks = taskRepository?.getTasksSnapshot(limit = 20).orEmpty()
            val conversations = chatRepository.getConversationsSnapshot()
            val messagesByConv = buildMap {
                conversations.forEach { conv ->
                    put(conv.id, chatRepository.getMessagesSnapshot(conv.id))
                }
            }
            val memories = memoryRepository?.getSnapshot().orEmpty()
            diagnosticsExportState.value = DiagnosticsExporter.buildReport(
                settings = settings,
                providers = providers,
                capabilities = capabilities,
                tasks = tasks,
                conversations = conversations,
                messagesByConversation = messagesByConv,
                memories = memories
            )
        }
    }

    fun dismissDiagnosticsExport() {
        diagnosticsExportState.value = null
    }

    fun clearAllConversations() {
        viewModelScope.launch {
            chatRepository.clearAllConversations()
            clearedMessage.value = "All local conversation history cleared."
        }
    }

    fun dismissClearedMessage() {
        clearedMessage.value = null
    }

    class Factory(
        private val settingsRepository: SettingsRepository,
        private val connectionsRepository: ConnectionsRepository,
        private val chatRepository: ChatRepository,
        private val permissionManager: AndroidPermissionManager? = null,
        private val floatingIndicatorController: FloatingIndicatorController? = null,
        private val memoryRepository: MemoryRepository? = null,
        private val taskRepository: TaskRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(
                settingsRepository = settingsRepository,
                connectionsRepository = connectionsRepository,
                chatRepository = chatRepository,
                permissionManager = permissionManager,
                floatingIndicatorController = floatingIndicatorController,
                memoryRepository = memoryRepository,
                taskRepository = taskRepository
            ) as T
        }
    }
}

