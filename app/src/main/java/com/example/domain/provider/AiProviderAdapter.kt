package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ChatMessage
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import kotlinx.coroutines.flow.Flow

data class ProviderRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val systemPrompt: String,
    val attachments: List<AttachmentPayload> = emptyList(),
    val modelInfo: AiModelInfo? = null,
    val temperature: Double = 0.2,
    val maxTokens: Int? = null
)

sealed interface ProviderResponse {
    data class Text(
        val content: String,
        val usage: TokenUsage? = null
    ) : ProviderResponse

    data class Error(val error: ProviderError) : ProviderResponse
}

sealed interface ConnectionTestResult {
    data class Success(
        val message: String,
        val fetchedModels: List<AiModelInfo> = emptyList()
    ) : ConnectionTestResult

    data class Failure(val error: ProviderError) : ConnectionTestResult
}

sealed interface ProviderStreamEvent {
    data class Delta(val text: String) : ProviderStreamEvent
    data class Usage(val usage: TokenUsage) : ProviderStreamEvent
    data class Completed(val usage: TokenUsage? = null) : ProviderStreamEvent
    data class Failed(val error: ProviderError) : ProviderStreamEvent
}

interface AiProviderAdapter {
    val providerType: AiProviderType

    fun defaultModels(): List<AiModelInfo>

    suspend fun testConfiguration(
        config: ProviderConfig,
        rawApiKey: String
    ): ConnectionTestResult

    suspend fun fetchModels(
        config: ProviderConfig,
        rawApiKey: String
    ): Result<List<AiModelInfo>>

    fun streamChat(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent>

    suspend fun generate(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): ProviderResponse {
        val builder = StringBuilder()
        var latestUsage: TokenUsage? = null
        var failure: ProviderError? = null

        streamChat(config, rawApiKey, request).collect { event ->
            when (event) {
                is ProviderStreamEvent.Delta -> builder.append(event.text)
                is ProviderStreamEvent.Usage -> latestUsage = event.usage
                is ProviderStreamEvent.Completed -> if (event.usage != null) latestUsage = event.usage
                is ProviderStreamEvent.Failed -> failure = event.error
            }
        }

        val err = failure
        return if (err != null) {
            ProviderResponse.Error(err)
        } else {
            ProviderResponse.Text(builder.toString(), latestUsage)
        }
    }
}

class AiProviderRegistry(
    httpClient: NexaHttpClient = NexaHttpClient(),
    adapters: List<AiProviderAdapter> = defaultAdapters(httpClient)
) {
    private val adapterMap: Map<AiProviderType, AiProviderAdapter> =
        adapters.associateBy { it.providerType }

    fun getAdapter(type: AiProviderType): AiProviderAdapter =
        adapterMap[type] ?: error("No adapter registered for $type")

    fun allAdapters(): List<AiProviderAdapter> =
        AiProviderType.entries.mapNotNull { adapterMap[it] }

    companion object {
        fun defaultAdapters(httpClient: NexaHttpClient = NexaHttpClient()): List<AiProviderAdapter> = listOf(
            OpenRouterProviderAdapter(httpClient),
            GeminiProviderAdapter(httpClient),
            OpenAiProviderAdapter(httpClient),
            AnthropicProviderAdapter(httpClient),
            CustomOpenAiProviderAdapter(httpClient)
        )
    }
}
