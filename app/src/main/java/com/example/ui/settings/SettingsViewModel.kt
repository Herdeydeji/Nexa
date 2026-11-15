package com.example.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.SettingsRepository
import com.example.domain.model.AppSettings
import com.example.domain.model.AppearancePosture
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ResponseVerbosity
import com.example.domain.model.TelegramConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val defaultProvider: ProviderConfig? = null,
    val telegramConfig: TelegramConfig = TelegramConfig(),
    val conversationCount: Int = 0,
    val capabilityCount: Int = 14,
    val availableCapabilitiesCount: Int = 0,
    val historyClearedMessage: String? = null
)

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val connectionsRepository: ConnectionsRepository,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val clearedMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings,
        connectionsRepository.defaultProvider,
        connectionsRepository.telegramConfig,
        chatRepository.conversations,
        connectionsRepository.capabilities,
        clearedMessage
    ) { values ->
        val settings = values[0] as AppSettings
        val defaultProv = values[1] as ProviderConfig?
        val telegram = values[2] as TelegramConfig
        @Suppress("UNCHECKED_CAST")
        val conversations = values[3] as List<*>
        @Suppress("UNCHECKED_CAST")
        val capabilities = values[4] as List<com.example.domain.model.DeviceCapability>
        val cleared = values[5] as String?

        SettingsUiState(
            settings = settings,
            defaultProvider = defaultProv,
            telegramConfig = telegram,
            conversationCount = conversations.size,
            capabilityCount = capabilities.size,
            availableCapabilitiesCount = capabilities.count {
                it.status == com.example.domain.model.CapabilityStatus.AVAILABLE
            },
            historyClearedMessage = cleared
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SettingsUiState()
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            settingsRepository.updateSettings(transform)
        }
    }

    fun cycleAppearance() {
        update {
            val next = if (it.appearancePosture == AppearancePosture.OBSIDIAN_DARK) {
                AppearancePosture.HIGH_CONTRAST_DARK
            } else {
                AppearancePosture.OBSIDIAN_DARK
            }
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

    fun selectConfirmationMode(mode: ConfirmationMode) {
        update { it.copy(confirmationMode = mode) }
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
        private val chatRepository: ChatRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SettingsViewModel(settingsRepository, connectionsRepository, chatRepository) as T
        }
    }
}
