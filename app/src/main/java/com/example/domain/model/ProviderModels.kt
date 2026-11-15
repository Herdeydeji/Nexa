package com.example.domain.model

import com.squareup.moshi.JsonClass

enum class AiProviderType(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val defaultBaseUrl: String,
    val keyHint: String,
    val requiresBaseUrl: Boolean = false,
    val supportsModelListing: Boolean = true
) {
    OPENROUTER(
        id = "openrouter",
        displayName = "OpenRouter",
        subtitle = "Unified multi-model router",
        defaultBaseUrl = "https://openrouter.ai/api/v1",
        keyHint = "sk-or-v1-..."
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        subtitle = "Gemini multimodal models",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        keyHint = "AIzaSy..."
    ),
    OPENAI(
        id = "openai",
        displayName = "OpenAI",
        subtitle = "GPT & reasoning models",
        defaultBaseUrl = "https://api.openai.com/v1",
        keyHint = "sk-proj-..."
    ),
    ANTHROPIC(
        id = "anthropic",
        displayName = "Anthropic",
        subtitle = "Claude reasoning & tool-use models",
        defaultBaseUrl = "https://api.anthropic.com/v1",
        keyHint = "sk-ant-..."
    ),
    CUSTOM(
        id = "custom",
        displayName = "Other Compatible Provider",
        subtitle = "OpenAI-compatible endpoint (Ollama, vLLM, Groq)",
        defaultBaseUrl = "",
        keyHint = "Bearer token / API key",
        requiresBaseUrl = true,
        supportsModelListing = true
    )
}

@JsonClass(generateAdapter = true)
data class AiModelInfo(
    val id: String,
    val displayName: String,
    val contextWindowLabel: String = "Unknown",
    val contextLengthTokens: Int? = null,
    val supportsVision: Boolean = false,
    val supportsTools: Boolean = false,
    val isManualEntry: Boolean = false,
    val isCatalogVerified: Boolean = false
)

sealed interface ProviderConnectionStatus {
    data object NotConfigured : ProviderConnectionStatus
    data object ConfiguredUnverified : ProviderConnectionStatus
    data object Testing : ProviderConnectionStatus
    data class Connected(val message: String, val modelCount: Int? = null) : ProviderConnectionStatus
    data class AuthFailed(val message: String) : ProviderConnectionStatus
    data class RateLimited(val message: String) : ProviderConnectionStatus
    data class EndpointError(val message: String) : ProviderConnectionStatus
    data class NetworkError(val message: String) : ProviderConnectionStatus
    data class ValidationError(val message: String) : ProviderConnectionStatus
    data class KeyInvalidated(val message: String) : ProviderConnectionStatus
}

data class ProviderConfig(
    val providerType: AiProviderType,
    val hasApiKey: Boolean,
    val maskedApiKey: String,
    val baseUrl: String,
    val selectedModelId: String?,
    val manualModelId: String = "",
    val isDefault: Boolean,
    val availableModels: List<AiModelInfo>,
    val isConnected: Boolean = false,
    val credentialInvalidated: Boolean = false,
    val allowManualToolsOverride: Boolean = false,
    val connectionStatus: ProviderConnectionStatus = when {
        credentialInvalidated -> ProviderConnectionStatus.KeyInvalidated(
            "Stored key could not be decrypted. Re-enter your API key."
        )
        hasApiKey -> ProviderConnectionStatus.ConfiguredUnverified
        else -> ProviderConnectionStatus.NotConfigured
    }
) {
    val selectedModelInfo: AiModelInfo?
        get() = availableModels.firstOrNull { it.id == selectedModelId }
            ?: selectedModelId?.let { id ->
                AiModelInfo(
                    id = id,
                    displayName = id,
                    contextWindowLabel = "Unknown",
                    contextLengthTokens = null,
                    supportsVision = false,
                    supportsTools = false,
                    isManualEntry = true,
                    isCatalogVerified = false
                )
            }

    val effectiveSupportsTools: Boolean
        get() = selectedModelInfo?.supportsTools == true || allowManualToolsOverride
}

enum class TelegramConnectionStatus(val label: String) {
    DISCONNECTED("Not Connected"),
    PAIRING_PENDING("Pairing Pending"),
    VALIDATION_ERROR("Validation Required")
}

data class TelegramConfig(
    val status: TelegramConnectionStatus = TelegramConnectionStatus.DISCONNECTED,
    val maskedBotToken: String = "",
    val pairingHandle: String = "",
    val allowRemotePrompts: Boolean = false,
    val requireDeviceApproval: Boolean = true,
    val shareTaskSummaries: Boolean = false,
    val statusNote: String = "No active Telegram bridge. Scheduled for Phase 3."
)
