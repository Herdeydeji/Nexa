package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import com.example.domain.model.ToolDispatchDiagnostic
import com.example.domain.security.ApiKeyMasker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow

class OpenRouterProviderAdapter(
    private val httpClient: NexaHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.OPENROUTER

    private val _lastDiagnostic = MutableStateFlow<ToolDispatchDiagnostic?>(null)
    val lastDiagnostic: StateFlow<ToolDispatchDiagnostic?> = _lastDiagnostic.asStateFlow()

    override fun defaultModels(): List<AiModelInfo> = listOf(
        AiModelInfo("inclusionai/ling-3.2-flash:free", "Ling 3.2 Flash (Free)", "128K", 128_000, supportsVision = false, supportsTools = true),
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
            .map { resp -> OpenAiCompatibleJson.parseModelsArray(resp.body, openRouterAutoTools = true) }
    }

    override fun streamChat(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent> = channelFlow {
        if (rawApiKey.isBlank()) {
            send(ProviderStreamEvent.Failed(ProviderError.MissingApiKey("Configure an OpenRouter API key in Connections.")))
            return@channelFlow
        }
        val baseUrl = normalizedBaseUrl(config)
        if (baseUrl == null) {
            send(ProviderStreamEvent.Failed(ProviderError.InvalidEndpoint("OpenRouter base URL must be valid HTTPS.")))
            return@channelFlow
        }
        if (request.modelId.isBlank()) {
            send(ProviderStreamEvent.Failed(ProviderError.UnsupportedModel("Select an OpenRouter model first.")))
            return@channelFlow
        }

        val supportsVision = request.modelInfo?.supportsVision ?: false
        val effectiveRequest = if (!request.allowTools &&
            request.tools.isNotEmpty() &&
            (config.effectiveSupportsTools || ProviderConfig.isAutoToolCapableOpenRouterModel(providerType, request.modelId))
        ) {
            request.copy(allowTools = true)
        } else {
            request
        }

        val body = OpenAiCompatibleJson.buildChatRequestBody(
            request = effectiveRequest,
            stream = true,
            supportsVision = supportsVision,
            includeStreamUsageOption = true
        )
        val (serializedNames, sentToolChoice) = OpenAiCompatibleJson.extractSerializedToolNames(body)
        _lastDiagnostic.value = ToolDispatchDiagnostic(
            providerType = AiProviderType.OPENROUTER,
            modelId = effectiveRequest.modelId,
            toolsEnabled = effectiveRequest.allowTools && serializedNames.isNotEmpty(),
            availableToolNames = effectiveRequest.tools.map { it.name },
            serializedToolNames = serializedNames,
            toolChoiceSent = sentToolChoice
        )

        var latestUsage: TokenUsage? = null
        var emittedTerminalError: ProviderError? = null
        val toolCallAccumulator = OpenAiCompatibleJson.OpenAiToolCallAccumulator()

        val result = try {
            httpClient.streamSsePost(
                url = "$baseUrl/chat/completions",
                headers = authHeaders(rawApiKey),
                jsonBody = body,
                secretToSanitize = rawApiKey,
                modelId = effectiveRequest.modelId,
                onFrame = { frame ->
                    when (val parsed = OpenAiCompatibleJson.parseStreamChunk(frame.data, rawApiKey, effectiveRequest.modelId)) {
                        is OpenAiCompatibleJson.ParsedSseChunk.Content -> {
                            if (parsed.usage != null) {
                                latestUsage = parsed.usage
                                send(ProviderStreamEvent.Usage(parsed.usage))
                            }
                            if (parsed.text.isNotEmpty()) {
                                send(ProviderStreamEvent.Delta(parsed.text))
                            }
                            parsed.toolCallFragments.forEach { frag ->
                                toolCallAccumulator.addFragment(frag)
                                send(
                                    ProviderStreamEvent.ToolCallDelta(
                                        index = frag.index,
                                        id = frag.id,
                                        name = frag.name,
                                        argumentsDelta = frag.argumentsDelta
                                    )
                                )
                            }
                            if (parsed.completeToolCalls.isNotEmpty()) {
                                toolCallAccumulator.addCompleteToolCalls(parsed.completeToolCalls)
                            }
                            true
                        }
                        is OpenAiCompatibleJson.ParsedSseChunk.UsageOnly -> {
                            latestUsage = parsed.usage
                            send(ProviderStreamEvent.Usage(parsed.usage))
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
                    val parsed = OpenAiCompatibleJson.parsePlainJsonCompletionDetailed(
                        jsonText,
                        rawApiKey,
                        effectiveRequest.modelId
                    )
                    if (parsed.usage != null) {
                        latestUsage = parsed.usage
                        send(ProviderStreamEvent.Usage(parsed.usage))
                    }
                    if (parsed.content.isNotEmpty()) send(ProviderStreamEvent.Delta(parsed.content))
                    if (parsed.toolCalls.isNotEmpty()) {
                        toolCallAccumulator.addCompleteToolCalls(parsed.toolCalls)
                    }
                }
            )
        } catch (ce: CancellationException) {
            throw ce
        }

        val terminalErr = emittedTerminalError ?: result.exceptionOrNull()?.let {
            (it as? ProviderException)?.error ?: NexaHttpClient.mapThrowableToError(it, rawApiKey)
        }
        val assembled = toolCallAccumulator.buildToolCalls()
        _lastDiagnostic.value = _lastDiagnostic.value?.copy(
            returnedToolCallNames = assembled.map { it.toolName },
            responseError = terminalErr?.let { ApiKeyMasker.sanitizeErrorText(it.userMessage) }
        )

        if (terminalErr != null) {
            send(ProviderStreamEvent.Failed(terminalErr))
        } else {
            if (assembled.isNotEmpty()) {
                send(ProviderStreamEvent.ToolCallsCompleted(assembled, latestUsage))
            }
            send(ProviderStreamEvent.Completed(latestUsage))
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
