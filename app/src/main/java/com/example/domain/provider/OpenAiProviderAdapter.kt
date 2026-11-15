package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import com.example.domain.security.ApiKeyMasker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

class OpenAiProviderAdapter(
    private val httpClient: NexaHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.OPENAI

    override fun defaultModels(): List<AiModelInfo> = listOf(
        AiModelInfo("gpt-4.1", "GPT-4.1", "1M", 1_000_000, supportsVision = true, supportsTools = true),
        AiModelInfo("gpt-4.1-mini", "GPT-4.1 Mini", "1M", 1_000_000, supportsVision = true, supportsTools = true),
        AiModelInfo("gpt-4o", "GPT-4o", "128K", 128_000, supportsVision = true, supportsTools = true),
        AiModelInfo("o4-mini", "o4-mini", "200K", 200_000, supportsVision = true, supportsTools = true)
    )

    override suspend fun testConfiguration(
        config: ProviderConfig,
        rawApiKey: String
    ): ConnectionTestResult {
        if (rawApiKey.isBlank()) {
            return ConnectionTestResult.Failure(
                ProviderError.MissingApiKey("Enter and save an OpenAI API key before testing.")
            )
        }
        val modelsResult = fetchModels(config, rawApiKey)
        modelsResult.onFailure { throwable ->
            val err = (throwable as? ProviderException)?.error
                ?: NexaHttpClient.mapThrowableToError(throwable, rawApiKey)
            return ConnectionTestResult.Failure(err)
        }
        val models = modelsResult.getOrNull().orEmpty()
        return ConnectionTestResult.Success(
            message = "Connected to OpenAI (${models.size} models available).",
            fetchedModels = models
        )
    }

    override suspend fun fetchModels(
        config: ProviderConfig,
        rawApiKey: String
    ): Result<List<AiModelInfo>> {
        if (rawApiKey.isBlank()) {
            return Result.failure(ProviderException(ProviderError.MissingApiKey("OpenAI API key missing.")))
        }
        val baseUrl = normalizedBaseUrl(config)
            ?: return Result.failure(ProviderException(ProviderError.InvalidEndpoint("Invalid OpenAI base URL.")))

        return httpClient.get(
            url = "$baseUrl/models",
            headers = mapOf("Authorization" to "Bearer ${rawApiKey.trim()}"),
            secretToSanitize = rawApiKey
        ).map { resp ->
            OpenAiCompatibleJson.parseModelsArray(resp.body, inferFromId = true)
                .filter { model ->
                    val id = model.id.lowercase()
                    id.startsWith("gpt-") || id.startsWith("o1") || id.startsWith("o3") || id.startsWith("o4") || id.startsWith("chatgpt-")
                }
                .sortedBy { it.id }
        }
    }

    override fun streamChat(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent> = channelFlow {
        if (rawApiKey.isBlank()) {
            send(ProviderStreamEvent.Failed(ProviderError.MissingApiKey("Configure an OpenAI API key in Connections.")))
            return@channelFlow
        }
        val baseUrl = normalizedBaseUrl(config)
        if (baseUrl == null) {
            send(ProviderStreamEvent.Failed(ProviderError.InvalidEndpoint("OpenAI base URL must use HTTPS.")))
            return@channelFlow
        }
        if (request.modelId.isBlank()) {
            send(ProviderStreamEvent.Failed(ProviderError.UnsupportedModel("Select an OpenAI model first.")))
            return@channelFlow
        }

        val lowerModel = request.modelId.lowercase()
        val isReasoningModel = lowerModel.startsWith("o1") || lowerModel.startsWith("o3") || lowerModel.startsWith("o4")
        val supportsVision = request.modelInfo?.supportsVision ?: false

        val body = OpenAiCompatibleJson.buildChatRequestBody(
            request = request,
            stream = true,
            supportsVision = supportsVision,
            includeStreamUsageOption = true,
            omitTemperature = isReasoningModel
        )

        var latestUsage: TokenUsage? = null
        var emittedError: ProviderError? = null
        val toolCallAccumulator = OpenAiCompatibleJson.OpenAiToolCallAccumulator()

        val result = try {
            httpClient.streamSsePost(
                url = "$baseUrl/chat/completions",
                headers = mapOf("Authorization" to "Bearer ${rawApiKey.trim()}"),
                jsonBody = body,
                secretToSanitize = rawApiKey,
                modelId = request.modelId,
                onFrame = { frame ->
                    when (val parsed = OpenAiCompatibleJson.parseStreamChunk(frame.data, rawApiKey, request.modelId)) {
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
                            emittedError = parsed.error
                            false
                        }
                        OpenAiCompatibleJson.ParsedSseChunk.Ignore -> true
                    }
                },
                onPlainJsonFallback = { jsonText ->
                    val parsed = OpenAiCompatibleJson.parsePlainJsonCompletionDetailed(
                        jsonText,
                        rawApiKey,
                        request.modelId
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

        val terminalErr = emittedError ?: result.exceptionOrNull()?.let {
            (it as? ProviderException)?.error ?: NexaHttpClient.mapThrowableToError(it, rawApiKey)
        }
        if (terminalErr != null) {
            send(ProviderStreamEvent.Failed(terminalErr))
        } else {
            val assembled = toolCallAccumulator.buildToolCalls()
            if (assembled.isNotEmpty()) {
                send(ProviderStreamEvent.ToolCallsCompleted(assembled, latestUsage))
            }
            send(ProviderStreamEvent.Completed(latestUsage))
        }
    }

    private fun normalizedBaseUrl(config: ProviderConfig): String? {
        val candidate = config.baseUrl.ifBlank { providerType.defaultBaseUrl }.trim().trimEnd('/')
        return if (ApiKeyMasker.validateEndpointUrl(candidate, required = true) is ApiKeyMasker.ValidationResult.Valid) {
            candidate
        } else {
            null
        }
    }
}
