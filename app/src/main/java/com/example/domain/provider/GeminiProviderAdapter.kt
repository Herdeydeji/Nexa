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

class GeminiProviderAdapter(
    private val httpClient: NexaHttpClient
) : AiProviderAdapter {

    override val providerType: AiProviderType = AiProviderType.GEMINI

    override fun defaultModels(): List<AiModelInfo> = listOf(
        AiModelInfo("gemini-2.5-pro", "Gemini 2.5 Pro", "1M", 1_048_576, supportsVision = true, supportsTools = true),
        AiModelInfo("gemini-2.5-flash", "Gemini 2.5 Flash", "1M", 1_048_576, supportsVision = true, supportsTools = true),
        AiModelInfo("gemini-2.5-flash-lite", "Gemini 2.5 Flash-Lite", "1M", 1_048_576, supportsVision = true, supportsTools = true)
    )

    override suspend fun testConfiguration(
        config: ProviderConfig,
        rawApiKey: String
    ): ConnectionTestResult {
        if (rawApiKey.isBlank()) {
            return ConnectionTestResult.Failure(
                ProviderError.MissingApiKey("Enter and save a Google Gemini API key before testing.")
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
            message = "Connected to Google Gemini (${models.size} models verified).",
            fetchedModels = models
        )
    }

    override suspend fun fetchModels(
        config: ProviderConfig,
        rawApiKey: String
    ): Result<List<AiModelInfo>> {
        if (rawApiKey.isBlank()) {
            return Result.failure(ProviderException(ProviderError.MissingApiKey("Gemini API key missing.")))
        }
        val baseUrl = normalizedBaseUrl(config)
            ?: return Result.failure(ProviderException(ProviderError.InvalidEndpoint("Invalid Gemini base URL.")))

        return httpClient.get(
            url = "$baseUrl/v1beta/models",
            headers = mapOf("x-goog-api-key" to rawApiKey.trim()),
            secretToSanitize = rawApiKey
        ).map { resp -> parseGeminiModels(resp.body) }
    }

    override fun streamChat(
        config: ProviderConfig,
        rawApiKey: String,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent> = flow {
        if (rawApiKey.isBlank()) {
            emit(ProviderStreamEvent.Failed(ProviderError.MissingApiKey("Configure a Google Gemini API key in Connections.")))
            return@flow
        }
        val baseUrl = normalizedBaseUrl(config)
        if (baseUrl == null) {
            emit(ProviderStreamEvent.Failed(ProviderError.InvalidEndpoint("Gemini base URL must use HTTPS.")))
            return@flow
        }
        if (request.modelId.isBlank()) {
            emit(ProviderStreamEvent.Failed(ProviderError.UnsupportedModel("Select a Gemini model first.")))
            return@flow
        }

        val cleanModelId = request.modelId.removePrefix("models/").trim()
        val supportsVision = request.modelInfo?.supportsVision ?: false
        val jsonBody = buildGeminiRequestBody(request, supportsVision)

        var latestUsage: TokenUsage? = null
        var emittedError: ProviderError? = null

        val result = try {
            httpClient.streamSsePost(
                url = "$baseUrl/v1beta/models/$cleanModelId:streamGenerateContent?alt=sse",
                headers = mapOf("x-goog-api-key" to rawApiKey.trim()),
                jsonBody = jsonBody,
                secretToSanitize = rawApiKey,
                modelId = cleanModelId,
                onFrame = { frame ->
                    val parsed = parseGeminiResponseObject(frame.data, rawApiKey, cleanModelId)
                    when (parsed) {
                        is GeminiParseResult.Chunk -> {
                            if (parsed.usage != null) {
                                latestUsage = parsed.usage
                                emit(ProviderStreamEvent.Usage(parsed.usage))
                            }
                            if (parsed.text.isNotEmpty()) {
                                emit(ProviderStreamEvent.Delta(parsed.text))
                            }
                            true
                        }
                        is GeminiParseResult.Error -> {
                            emittedError = parsed.error
                            false
                        }
                    }
                },
                onPlainJsonFallback = { bodyText ->
                    val arrOrObj = bodyText.trimStart()
                    if (arrOrObj.startsWith("[")) {
                        val arr = runCatching { JSONArray(bodyText) }.getOrNull()
                            ?: throw ProviderException(ProviderError.MalformedResponse("Malformed Gemini JSON array response."))
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            when (val parsed = parseGeminiResponseObject(item.toString(), rawApiKey, cleanModelId)) {
                                is GeminiParseResult.Chunk -> {
                                    if (parsed.usage != null) {
                                        latestUsage = parsed.usage
                                        emit(ProviderStreamEvent.Usage(parsed.usage))
                                    }
                                    if (parsed.text.isNotEmpty()) emit(ProviderStreamEvent.Delta(parsed.text))
                                }
                                is GeminiParseResult.Error -> throw ProviderException(parsed.error)
                            }
                        }
                    } else {
                        when (val parsed = parseGeminiResponseObject(bodyText, rawApiKey, cleanModelId)) {
                            is GeminiParseResult.Chunk -> {
                                if (parsed.usage != null) {
                                    latestUsage = parsed.usage
                                    emit(ProviderStreamEvent.Usage(parsed.usage))
                                }
                                if (parsed.text.isNotEmpty()) emit(ProviderStreamEvent.Delta(parsed.text))
                            }
                            is GeminiParseResult.Error -> throw ProviderException(parsed.error)
                        }
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
            emit(ProviderStreamEvent.Failed(terminalErr))
        } else {
            emit(ProviderStreamEvent.Completed(latestUsage))
        }
    }

    private fun buildGeminiRequestBody(request: ProviderRequest, supportsVision: Boolean): String {
        val root = JSONObject()

        if (request.systemPrompt.isNotBlank()) {
            root.put(
                "systemInstruction",
                JSONObject().put(
                    "parts",
                    JSONArray().put(JSONObject().put("text", request.systemPrompt))
                )
            )
        }

        val contents = JSONArray()
        val lastUserIndex = request.messages.indexOfLast { it.role == MessageRole.USER }
        request.messages.forEachIndexed { index, msg ->
            if (msg.role == MessageRole.SYSTEM) return@forEachIndexed
            val role = if (msg.role == MessageRole.ASSISTANT) "model" else "user"
            val parts = JSONArray()

            if (index == lastUserIndex && request.attachments.isNotEmpty()) {
                val combinedText = OpenAiCompatibleJson.appendTextAttachments(msg.content, request.attachments)
                parts.put(JSONObject().put("text", combinedText))
                if (supportsVision) {
                    request.attachments
                        .filter { it.isSupported && !it.imageBase64.isNullOrBlank() }
                        .forEach { img ->
                            parts.put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", img.mimeType.ifBlank { "image/jpeg" })
                                        .put("data", img.imageBase64)
                                )
                            )
                        }
                }
            } else {
                parts.put(JSONObject().put("text", msg.content))
            }

            contents.put(
                JSONObject()
                    .put("role", role)
                    .put("parts", parts)
            )
        }
        root.put("contents", contents)

        val genConfig = JSONObject().put("temperature", request.temperature)
        request.maxTokens?.let { genConfig.put("maxOutputTokens", it) }
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    private fun parseGeminiModels(body: String): List<AiModelInfo> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val models = root.optJSONArray("models") ?: return emptyList()
        val out = mutableListOf<AiModelInfo>()
        for (i in 0 until models.length()) {
            val item = models.optJSONObject(i) ?: continue
            val methods = item.optJSONArray("supportedGenerationMethods")
            val supportsGenerate = if (methods != null) {
                (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            } else {
                true
            }
            if (!supportsGenerate) continue

            val rawName = item.optString("name").trim()
            val id = rawName.removePrefix("models/").trim()
            if (id.isBlank()) continue
            val displayName = item.optString("displayName").takeIf { it.isNotBlank() } ?: id
            val inputLimit = if (item.has("inputTokenLimit")) item.optInt("inputTokenLimit").takeIf { it > 0 } else null

            val isGeminiMultimodal = id.lowercase().contains("gemini")
            out.add(
                AiModelInfo(
                    id = id,
                    displayName = displayName,
                    contextWindowLabel = OpenAiCompatibleJson.formatContextLabel(inputLimit),
                    contextLengthTokens = inputLimit,
                    supportsVision = isGeminiMultimodal,
                    supportsTools = isGeminiMultimodal,
                    isManualEntry = false,
                    isCatalogVerified = true
                )
            )
        }
        return out
    }

    private sealed interface GeminiParseResult {
        data class Chunk(val text: String, val usage: TokenUsage? = null) : GeminiParseResult
        data class Error(val error: ProviderError) : GeminiParseResult
    }

    private fun parseGeminiResponseObject(
        rawJson: String,
        secretToSanitize: String?,
        modelId: String
    ): GeminiParseResult {
        val root = runCatching { JSONObject(rawJson) }.getOrNull()
            ?: return GeminiParseResult.Error(
                ProviderError.MalformedResponse("Received malformed JSON from Gemini.")
            )
        if (root.has("error") && !root.isNull("error")) {
            val code = root.optJSONObject("error")?.optInt("code", 500) ?: 500
            return GeminiParseResult.Error(
                NexaHttpClient.mapHttpStatusToError(
                    code = if (code in 400..599) code else 500,
                    body = rawJson,
                    secretToSanitize = secretToSanitize,
                    modelId = modelId
                )
            )
        }

        val usageObj = root.optJSONObject("usageMetadata")
        val usage = if (usageObj != null) {
            TokenUsage(
                promptTokens = if (usageObj.has("promptTokenCount")) usageObj.optInt("promptTokenCount") else null,
                completionTokens = if (usageObj.has("candidatesTokenCount")) usageObj.optInt("candidatesTokenCount") else null,
                totalTokens = if (usageObj.has("totalTokenCount")) usageObj.optInt("totalTokenCount") else null
            )
        } else {
            null
        }

        val textBuilder = StringBuilder()
        val candidates = root.optJSONArray("candidates")
        val parts = candidates?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.has("text") && !part.isNull("text")) {
                    textBuilder.append(part.optString("text", ""))
                }
            }
        }
        return GeminiParseResult.Chunk(text = textBuilder.toString(), usage = usage)
    }

    private fun normalizedBaseUrl(config: ProviderConfig): String? {
        val raw = config.baseUrl.ifBlank { providerType.defaultBaseUrl }
            .trim()
            .trimEnd('/')
            .removeSuffix("/v1beta")
            .removeSuffix("/v1")
        return if (ApiKeyMasker.validateEndpointUrl(raw, required = true) is ApiKeyMasker.ValidationResult.Valid) {
            raw
        } else {
            null
        }
    }
}
