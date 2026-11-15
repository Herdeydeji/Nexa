package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import com.example.domain.security.ApiKeyMasker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class OpenRouterProviderAdapter(
    private val httpClient: NexaHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.OPENROUTER

    override fun defaultModels(): List<AiModelInfo> = listOf(
        AiModelInfo("anthropic/claude-sonnet-4", "Claude Sonnet 4 (OR)", "200K", 200_000, supportsVision = true, supportsTools = true),
        AiModelInfo("google/gemini-2.5-pro", "Gemini 2.5 Pro (OR)", "1M", 1_000_000, supportsVision = true, supportsTools = true),
        AiModelInfo("openai/gpt-4.1", "GPT-4.1 (OR)", "1M", 1_000_000, supportsVision = true, supportsTools = true),
        AiModelInfo("deepseek/deepseek-r1", "DeepSeek R1 (OR)", "128K", 128_000, supportsVision = false, supportsTools = false)
    )

    override suspend fun testConfiguration(
        config: ProviderConfig,
        rawApiKey: String
    ): ConnectionTestResult {
        if (rawApiKey.isBlank()) {
            return ConnectionTestResult.Failure(
                ProviderError.MissingApiKey("Enter and save an OpenRouter API key before testing.")
            )
        }
        val baseUrl = normalizedBaseUrl(config)
            ?: return ConnectionTestResult.Failure(
                ProviderError.InvalidEndpoint("OpenRouter base URL must use HTTPS.")
            )

        val headers = authHeaders(rawApiKey)
        val keyResult = httpClient.get("$baseUrl/key", headers, secretToSanitize = rawApiKey)
        keyResult.onFailure { throwable ->
            val err = (throwable as? ProviderException)?.error
                ?: NexaHttpClient.mapThrowableToError(throwable, rawApiKey)
            return ConnectionTestResult.Failure(err)
        }

        val modelsResult = fetchModels(config, rawApiKey)
        val models = modelsResult.getOrNull().orEmpty()
        return ConnectionTestResult.Success(
            message = if (models.isNotEmpty()) {
                "Connected to OpenRouter (${models.size} models verified)."
            } else {
                "OpenRouter API key verified."
            },
            fetchedModels = models
        )
    }

    override suspend fun fetchModels(
        config: ProviderConfig,
        rawApiKey: String
    ): Result<List<AiModelInfo>> {
        if (rawApiKey.isBlank()) {
            return Result.failure(ProviderException(ProviderError.MissingApiKey("OpenRouter API key missing.")))
        }
        val baseUrl = normalizedBaseUrl(config)
            ?: return Result.failure(ProviderException(ProviderError.InvalidEndpoint("Invalid OpenRouter base URL.")))
        return httpClient.get("$baseUrl/models", authHeaders(rawApiKey), secretToSanitize = rawApiKey)
            .map { resp -> OpenAiCompatibleJson.parseModelsArray(resp.body) }
    }

    override fun streamChat(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent> = flow {
        if (rawApiKey.isBlank()) {
            emit(ProviderStreamEvent.Failed(ProviderError.MissingApiKey("Configure an OpenRouter API key in Connections.")))
            return@flow
        }
        val baseUrl = normalizedBaseUrl(config)
        if (baseUrl == null) {
            emit(ProviderStreamEvent.Failed(ProviderError.InvalidEndpoint("OpenRouter base URL must be valid HTTPS.")))
            return@flow
        }
        if (request.modelId.isBlank()) {
            emit(ProviderStreamEvent.Failed(ProviderError.UnsupportedModel("Select an OpenRouter model first.")))
            return@flow
        }

        val supportsVision = request.modelInfo?.supportsVision ?: false
        val body = OpenAiCompatibleJson.buildChatRequestBody(
            request = request,
            stream = true,
            supportsVision = supportsVision,
            includeStreamUsageOption = true
        )

        var latestUsage: TokenUsage? = null
        var emittedTerminalError: ProviderError? = null

        val result = try {
            httpClient.streamSsePost(
                url = "$baseUrl/chat/completions",
                headers = authHeaders(rawApiKey),
                jsonBody = body,
                secretToSanitize = rawApiKey,
                modelId = request.modelId,
                onFrame = { frame ->
                    when (val parsed = OpenAiCompatibleJson.parseStreamChunk(frame.data, rawApiKey, request.modelId)) {
                        is OpenAiCompatibleJson.ParsedSseChunk.Delta -> {
                            if (parsed.usage != null) {
                                latestUsage = parsed.usage
                                emit(ProviderStreamEvent.Usage(parsed.usage))
                            }
                            emit(ProviderStreamEvent.Delta(parsed.text))
                            true
                        }
                        is OpenAiCompatibleJson.ParsedSseChunk.UsageOnly -> {
                            latestUsage = parsed.usage
                            emit(ProviderStreamEvent.Usage(parsed.usage))
                            true
                        }
                        is OpenAiCompatibleJson.ParsedSseChunk.Error -> {
                            emittedTerminalError = parsed.error
                            false
                        }
                        OpenAiCompatibleJson.ParsedSseChunk.Ignore -> true
                    }
                },
                onPlainJsonFallback = { jsonText ->
                    val (content, usage) = OpenAiCompatibleJson.parsePlainJsonCompletion(
                        jsonText,
                        rawApiKey,
                        request.modelId
                    )
                    if (usage != null) {
                        latestUsage = usage
                        emit(ProviderStreamEvent.Usage(usage))
                    }
                    if (content.isNotEmpty()) emit(ProviderStreamEvent.Delta(content))
                }
            )
        } catch (ce: CancellationException) {
            throw ce
        }

        val terminalErr = emittedTerminalError ?: result.exceptionOrNull()?.let {
            (it as? ProviderException)?.error ?: NexaHttpClient.mapThrowableToError(it, rawApiKey)
        }
        if (terminalErr != null) {
            emit(ProviderStreamEvent.Failed(terminalErr))
        } else {
            emit(ProviderStreamEvent.Completed(latestUsage))
        }
    }

    private fun authHeaders(rawApiKey: String): Map<String, String> = mapOf(
        "Authorization" to "Bearer ${rawApiKey.trim()}",
        "HTTP-Referer" to "https://nexa.android.app",
        "X-Title" to "Nexa Android"
    )

    private fun normalizedBaseUrl(config: ProviderConfig): String? {
        val candidate = config.baseUrl.ifBlank { providerType.defaultBaseUrl }.trim().trimEnd('/')
        return if (ApiKeyMasker.validateEndpointUrl(candidate, required = true) is ApiKeyMasker.ValidationResult.Valid) {
            candidate
        } else {
            null
        }
    }
}
