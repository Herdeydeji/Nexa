package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import com.example.domain.security.ApiKeyMasker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

class CustomOpenAiProviderAdapter(
    private val httpClient: NexaHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.CUSTOM

    override fun defaultModels(): List<AiModelInfo> = emptyList()

    override suspend fun testConfiguration(
        config: ProviderConfig,
        rawApiKey: String
    ): ConnectionTestResult {
        val baseUrlValidation = ApiKeyMasker.validateEndpointUrl(config.baseUrl, required = true)
        if (baseUrlValidation is ApiKeyMasker.ValidationResult.Invalid) {
            return ConnectionTestResult.Failure(ProviderError.InvalidEndpoint(baseUrlValidation.reason))
        }
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val headers = optionalAuthHeaders(rawApiKey)

        val modelsResp = httpClient.get("$baseUrl/models", headers, secretToSanitize = rawApiKey)
        if (modelsResp.isSuccess) {
            val body = modelsResp.getOrNull()?.body.orEmpty()
            val parsedJson = runCatching { JSONObject(body) }.getOrNull()
            if (parsedJson == null || !parsedJson.has("data")) {
                return ConnectionTestResult.Failure(
                    ProviderError.MalformedResponse(
                        "Endpoint responded, but /models did not return an OpenAI-compatible JSON {data: [...]} payload."
                    )
                )
            }
            val models = OpenAiCompatibleJson.parseModelsArray(body)
            return ConnectionTestResult.Success(
                message = "Connected to compatible endpoint (${models.size} models discovered).",
                fetchedModels = models
            )
        }

        val err = (modelsResp.exceptionOrNull() as? ProviderException)?.error
        if (err is ProviderError.InvalidApiKey ||
            err is ProviderError.NetworkUnavailable ||
            err is ProviderError.Timeout ||
            err is ProviderError.RateLimitExceeded
        ) {
            return ConnectionTestResult.Failure(err)
        }

        // Some minimal OpenAI-compatible servers do not expose GET /models (404/405).
        // If a model ID is configured, verify /chat/completions with a minimal probe.
        val probeModel = config.selectedModelId?.takeIf { it.isNotBlank() }
        if (probeModel != null) {
            val probeBody = JSONObject()
                .put("model", probeModel)
                .put("stream", false)
                .put("max_tokens", 1)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ping"))
                )
                .toString()
            val chatResp = httpClient.postJson(
                url = "$baseUrl/chat/completions",
                headers = headers,
                jsonBody = probeBody,
                secretToSanitize = rawApiKey
            )
            return chatResp.fold(
                onSuccess = { resp ->
                    val parsed = runCatching {
                        OpenAiCompatibleJson.parsePlainJsonCompletion(resp.body, rawApiKey, probeModel)
                    }
                    if (parsed.isSuccess) {
                        ConnectionTestResult.Success(
                            message = "Verified /chat/completions for '$probeModel' (/models endpoint unavailable)."
                        )
                    } else {
                        ConnectionTestResult.Failure(
                            ProviderError.MalformedResponse(
                                "Endpoint responded to /chat/completions without a valid OpenAI completion payload."
                            )
                        )
                    }
                },
                onFailure = { throwable ->
                    val chatErr = (throwable as? ProviderException)?.error
                        ?: NexaHttpClient.mapThrowableToError(throwable, rawApiKey)
                    ConnectionTestResult.Failure(chatErr)
                }
            )
        }

        return ConnectionTestResult.Failure(
            err ?: ProviderError.InvalidEndpoint("Endpoint could not be verified. Check base URL and model ID.")
        )
    }

    override suspend fun fetchModels(
        config: ProviderConfig,
        rawApiKey: String
    ): Result<List<AiModelInfo>> {
        val urlValidation = ApiKeyMasker.validateEndpointUrl(config.baseUrl, required = true)
        if (urlValidation is ApiKeyMasker.ValidationResult.Invalid) {
            return Result.failure(ProviderException(ProviderError.InvalidEndpoint(urlValidation.reason)))
        }
        val baseUrl = config.baseUrl.trim().trimEnd('/')
        return httpClient.get(
            url = "$baseUrl/models",
            headers = optionalAuthHeaders(rawApiKey),
            secretToSanitize = rawApiKey
        ).mapCatching { resp ->
            val root = runCatching { JSONObject(resp.body) }.getOrNull()
            if (root == null || !root.has("data")) {
                throw ProviderException(
                    ProviderError.MalformedResponse("Endpoint /models did not return OpenAI-compatible JSON.")
                )
            }
            OpenAiCompatibleJson.parseModelsArray(resp.body)
        }
    }

    override fun streamChat(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent> = flow {
        val urlValidation = ApiKeyMasker.validateEndpointUrl(config.baseUrl, required = true)
        if (urlValidation is ApiKeyMasker.ValidationResult.Invalid) {
            emit(ProviderStreamEvent.Failed(ProviderError.InvalidEndpoint(urlValidation.reason)))
            return@flow
        }
        if (request.modelId.isBlank()) {
            emit(ProviderStreamEvent.Failed(ProviderError.UnsupportedModel("Enter or select a model ID for this endpoint.")))
            return@flow
        }

        val baseUrl = config.baseUrl.trim().trimEnd('/')
        val chatUrl = if (baseUrl.endsWith("/chat/completions")) baseUrl else "$baseUrl/chat/completions"
        val supportsVision = request.modelInfo?.supportsVision ?: false
        val body = OpenAiCompatibleJson.buildChatRequestBody(
            request = request,
            stream = true,
            supportsVision = supportsVision,
            includeStreamUsageOption = false
        )

        var latestUsage: TokenUsage? = null
        var emittedError: ProviderError? = null

        val result = try {
            httpClient.streamSsePost(
                url = chatUrl,
                headers = optionalAuthHeaders(rawApiKey),
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
                            emittedError = parsed.error
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

        val terminalErr = emittedError ?: result.exceptionOrNull()?.let {
            (it as? ProviderException)?.error ?: NexaHttpClient.mapThrowableToError(it, rawApiKey)
        }
        if (terminalErr != null) {
            emit(ProviderStreamEvent.Failed(terminalErr))
        } else {
            emit(ProviderStreamEvent.Completed(latestUsage))
        }
    }

    private fun optionalAuthHeaders(rawApiKey: String): Map<String, String> =
        if (rawApiKey.isNotBlank()) mapOf("Authorization" to "Bearer ${rawApiKey.trim()}") else emptyMap()
}
