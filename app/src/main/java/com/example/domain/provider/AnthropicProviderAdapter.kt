package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.MessageRole
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import com.example.domain.security.ApiKeyMasker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject

class AnthropicProviderAdapter(
    private val httpClient: NexaHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.ANTHROPIC

    override fun defaultModels(): List<AiModelInfo> = listOf(
        AiModelInfo("claude-sonnet-4-0", "Claude Sonnet 4", "200K", 200_000, supportsVision = true, supportsTools = true),
        AiModelInfo("claude-3-7-sonnet-latest", "Claude 3.7 Sonnet", "200K", 200_000, supportsVision = true, supportsTools = true),
        AiModelInfo("claude-3-5-haiku-latest", "Claude 3.5 Haiku", "200K", 200_000, supportsVision = true, supportsTools = true)
    )

    override suspend fun testConfiguration(
        config: ProviderConfig,
        rawApiKey: String
    ): ConnectionTestResult {
        if (rawApiKey.isBlank()) {
            return ConnectionTestResult.Failure(
                ProviderError.MissingApiKey("Enter and save an Anthropic API key before testing.")
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
            message = "Connected to Anthropic (${models.size} models verified).",
            fetchedModels = models
        )
    }

    override suspend fun fetchModels(
        config: ProviderConfig,
        rawApiKey: String
    ): Result<List<AiModelInfo>> {
        if (rawApiKey.isBlank()) {
            return Result.failure(ProviderException(ProviderError.MissingApiKey("Anthropic API key missing.")))
        }
        val baseUrl = normalizedBaseUrl(config)
            ?: return Result.failure(ProviderException(ProviderError.InvalidEndpoint("Invalid Anthropic base URL.")))

        return httpClient.get(
            url = "$baseUrl/models",
            headers = authHeaders(rawApiKey),
            secretToSanitize = rawApiKey
        ).map { resp -> parseAnthropicModels(resp.body) }
    }

    override fun streamChat(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent> = flow {
        if (rawApiKey.isBlank()) {
            emit(ProviderStreamEvent.Failed(ProviderError.MissingApiKey("Configure an Anthropic API key in Connections.")))
            return@flow
        }
        val baseUrl = normalizedBaseUrl(config)
        if (baseUrl == null) {
            emit(ProviderStreamEvent.Failed(ProviderError.InvalidEndpoint("Anthropic base URL must use HTTPS.")))
            return@flow
        }
        if (request.modelId.isBlank()) {
            emit(ProviderStreamEvent.Failed(ProviderError.UnsupportedModel("Select an Anthropic model first.")))
            return@flow
        }

        val supportsVision = request.modelInfo?.supportsVision ?: false
        val body = buildAnthropicRequestBody(request, supportsVision)

        var inputTokens: Int? = null
        var outputTokens: Int? = null
        var emittedError: ProviderError? = null

        val result = try {
            httpClient.streamSsePost(
                url = "$baseUrl/messages",
                headers = authHeaders(rawApiKey),
                jsonBody = body,
                secretToSanitize = rawApiKey,
                modelId = request.modelId,
                onFrame = { frame ->
                    val json = runCatching { JSONObject(frame.data) }.getOrNull()
                    if (json == null) {
                        emittedError = ProviderError.MalformedResponse("Malformed SSE event from Anthropic.")
                        return@streamSsePost false
                    }
                    val type = frame.event ?: json.optString("type")
                    when (type) {
                        "message_start" -> {
                            val usageObj = json.optJSONObject("message")?.optJSONObject("usage")
                            if (usageObj != null && usageObj.has("input_tokens")) {
                                inputTokens = usageObj.optInt("input_tokens")
                            }
                            true
                        }
                        "content_block_delta" -> {
                            val delta = json.optJSONObject("delta")
                            if (delta?.optString("type") == "text_delta") {
                                val text = delta.optString("text", "")
                                if (text.isNotEmpty()) {
                                    emit(ProviderStreamEvent.Delta(text))
                                }
                            }
                            true
                        }
                        "message_delta" -> {
                            val usageObj = json.optJSONObject("usage")
                            if (usageObj != null && usageObj.has("output_tokens")) {
                                outputTokens = usageObj.optInt("output_tokens")
                                val usage = TokenUsage(
                                    promptTokens = inputTokens,
                                    completionTokens = outputTokens,
                                    totalTokens = if (inputTokens != null && outputTokens != null) inputTokens!! + outputTokens!! else null
                                )
                                emit(ProviderStreamEvent.Usage(usage))
                            }
                            true
                        }
                        "error" -> {
                            val errType = json.optJSONObject("error")?.optString("type").orEmpty()
                            val mappedCode = when (errType) {
                                "authentication_error", "permission_error" -> 401
                                "rate_limit_error" -> 429
                                "not_found_error" -> 404
                                "overloaded_error" -> 529
                                else -> 500
                            }
                            emittedError = NexaHttpClient.mapHttpStatusToError(
                                code = mappedCode,
                                body = frame.data,
                                secretToSanitize = rawApiKey,
                                modelId = request.modelId
                            )
                            false
                        }
                        else -> true
                    }
                },
                onPlainJsonFallback = { bodyText ->
                    val json = runCatching { JSONObject(bodyText) }.getOrNull()
                        ?: throw ProviderException(ProviderError.MalformedResponse("Invalid JSON from Anthropic."))
                    if (json.has("error") && !json.isNull("error")) {
                        throw ProviderException(
                            NexaHttpClient.mapHttpStatusToError(500, bodyText, secretToSanitize = rawApiKey, modelId = request.modelId)
                        )
                    }
                    val usageObj = json.optJSONObject("usage")
                    if (usageObj != null) {
                        inputTokens = if (usageObj.has("input_tokens")) usageObj.optInt("input_tokens") else null
                        outputTokens = if (usageObj.has("output_tokens")) usageObj.optInt("output_tokens") else null
                    }
                    val contentArr = json.optJSONArray("content")
                    if (contentArr != null) {
                        for (i in 0 until contentArr.length()) {
                            val block = contentArr.optJSONObject(i) ?: continue
                            if (block.optString("type") == "text") {
                                val text = block.optString("text", "")
                                if (text.isNotEmpty()) emit(ProviderStreamEvent.Delta(text))
                            }
                        }
                    }
                }
            )
        } catch (ce: CancellationException) {
            throw ce
        }

        val finalUsage = if (inputTokens != null || outputTokens != null) {
            TokenUsage(
                promptTokens = inputTokens,
                completionTokens = outputTokens,
                totalTokens = if (inputTokens != null && outputTokens != null) inputTokens!! + outputTokens!! else null
            )
        } else {
            null
        }

        val terminalErr = emittedError ?: result.exceptionOrNull()?.let {
            (it as? ProviderException)?.error ?: NexaHttpClient.mapThrowableToError(it, rawApiKey)
        }
        if (terminalErr != null) {
            emit(ProviderStreamEvent.Failed(terminalErr))
        } else {
            emit(ProviderStreamEvent.Completed(finalUsage))
        }
    }

    private fun buildAnthropicRequestBody(request: ProviderRequest, supportsVision: Boolean): String {
        val root = JSONObject()
        root.put("model", request.modelId)
        root.put("stream", true)
        root.put("max_tokens", request.maxTokens ?: DEFAULT_MAX_TOKENS)
        root.put("temperature", request.temperature)
        if (request.systemPrompt.isNotBlank()) {
            root.put("system", request.systemPrompt)
        }

        val messagesArr = JSONArray()
        val lastUserIndex = request.messages.indexOfLast { it.role == MessageRole.USER }
        request.messages.forEachIndexed { index, msg ->
            if (msg.role == MessageRole.SYSTEM) return@forEachIndexed
            val role = if (msg.role == MessageRole.ASSISTANT) "assistant" else "user"

            if (index == lastUserIndex && request.attachments.isNotEmpty()) {
                val combinedText = OpenAiCompatibleJson.appendTextAttachments(msg.content, request.attachments)
                val images = if (supportsVision) {
                    request.attachments.filter { it.isSupported && !it.imageBase64.isNullOrBlank() }
                } else {
                    emptyList()
                }
                if (images.isNotEmpty()) {
                    val blocks = JSONArray()
                    images.forEach { img ->
                        blocks.put(
                            JSONObject()
                                .put("type", "image")
                                .put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", img.mimeType.ifBlank { "image/jpeg" })
                                        .put("data", img.imageBase64)
                                )
                        )
                    }
                    blocks.put(JSONObject().put("type", "text").put("text", combinedText))
                    messagesArr.put(JSONObject().put("role", role).put("content", blocks))
                } else {
                    messagesArr.put(JSONObject().put("role", role).put("content", combinedText))
                }
            } else {
                messagesArr.put(JSONObject().put("role", role).put("content", msg.content))
            }
        }
        root.put("messages", messagesArr)
        return root.toString()
    }

    private fun parseAnthropicModels(body: String): List<AiModelInfo> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<AiModelInfo>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) continue
            val displayName = item.optString("display_name").takeIf { it.isNotBlank() } ?: id
            val knownCtx = if (id.startsWith("claude-")) 200_000 else null
            val supportsVision = id.contains("claude-3") || id.contains("claude-sonnet-4") || id.contains("claude-opus-4") || id.contains("claude-4")
            list.add(
                AiModelInfo(
                    id = id,
                    displayName = displayName,
                    contextWindowLabel = OpenAiCompatibleJson.formatContextLabel(knownCtx),
                    contextLengthTokens = knownCtx,
                    supportsVision = supportsVision,
                    supportsTools = supportsVision,
                    isManualEntry = false,
                    isCatalogVerified = true
                )
            )
        }
        return list
    }

    private fun authHeaders(rawApiKey: String): Map<String, String> = mapOf(
        "x-api-key" to rawApiKey.trim(),
        "anthropic-version" to ANTHROPIC_VERSION
    )

    private fun normalizedBaseUrl(config: ProviderConfig): String? {
        val candidate = config.baseUrl.ifBlank { providerType.defaultBaseUrl }.trim().trimEnd('/')
        return if (ApiKeyMasker.validateEndpointUrl(candidate, required = true) is ApiKeyMasker.ValidationResult.Valid) {
            candidate
        } else {
            null
        }
    }

    private companion object {
        const val ANTHROPIC_VERSION = "2023-06-01"
        const val DEFAULT_MAX_TOKENS = 4096
    }
}
