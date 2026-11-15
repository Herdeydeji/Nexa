package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.MessageRole
import com.example.domain.model.TokenUsage
import org.json.JSONArray
import org.json.JSONObject

internal object OpenAiCompatibleJson {

    fun buildChatRequestBody(
        request: ProviderRequest,
        stream: Boolean,
        supportsVision: Boolean,
        includeStreamUsageOption: Boolean = true,
        omitTemperature: Boolean = false
    ): String {
        val root = JSONObject()
        root.put("model", request.modelId)
        root.put("stream", stream)
        if (stream && includeStreamUsageOption) {
            root.put("stream_options", JSONObject().put("include_usage", true))
        }
        if (!omitTemperature) {
            root.put("temperature", request.temperature)
        }
        request.maxTokens?.let { root.put("max_tokens", it) }

        val messagesArray = JSONArray()
        if (request.systemPrompt.isNotBlank()) {
            messagesArray.put(
                JSONObject()
                    .put("role", "system")
                    .put("content", request.systemPrompt)
            )
        }

        val lastUserIndex = request.messages.indexOfLast { it.role == MessageRole.USER }
        request.messages.forEachIndexed { index, msg ->
            if (msg.role == MessageRole.SYSTEM) return@forEachIndexed
            val roleStr = when (msg.role) {
                MessageRole.USER -> "user"
                MessageRole.ASSISTANT -> "assistant"
                MessageRole.SYSTEM -> "system"
            }

            if (index == lastUserIndex && request.attachments.isNotEmpty()) {
                val contentWithTextFiles = appendTextAttachments(msg.content, request.attachments)
                val images = if (supportsVision) {
                    request.attachments.filter { it.isSupported && !it.imageBase64.isNullOrBlank() }
                } else {
                    emptyList()
                }
                if (images.isNotEmpty()) {
                    val parts = JSONArray()
                    parts.put(JSONObject().put("type", "text").put("text", contentWithTextFiles))
                    images.forEach { img ->
                        val mime = img.mimeType.ifBlank { "image/jpeg" }
                        parts.put(
                            JSONObject()
                                .put("type", "image_url")
                                .put(
                                    "image_url",
                                    JSONObject().put("url", "data:$mime;base64,${img.imageBase64}")
                                )
                        )
                    }
                    messagesArray.put(JSONObject().put("role", roleStr).put("content", parts))
                } else {
                    messagesArray.put(JSONObject().put("role", roleStr).put("content", contentWithTextFiles))
                }
            } else {
                messagesArray.put(JSONObject().put("role", roleStr).put("content", msg.content))
            }
        }

        root.put("messages", messagesArray)
        return root.toString()
    }

    fun appendTextAttachments(baseText: String, attachments: List<AttachmentPayload>): String {
        val textFiles = attachments.filter { it.isSupported && !it.textContent.isNullOrBlank() }
        if (textFiles.isEmpty()) return baseText
        return buildString {
            append(baseText)
            textFiles.forEach { file ->
                append("\n\n[Attached file: ")
                append(file.displayName)
                append(" (")
                append(file.mimeType)
                append(")]\n")
                append(file.textContent)
            }
        }
    }

    sealed interface ParsedSseChunk {
        data class Delta(val text: String, val usage: TokenUsage? = null) : ParsedSseChunk
        data class UsageOnly(val usage: TokenUsage) : ParsedSseChunk
        data class Error(val error: ProviderError) : ParsedSseChunk
        data object Ignore : ParsedSseChunk
    }

    fun parseStreamChunk(
        data: String,
        secretToSanitize: String?,
        modelId: String?
    ): ParsedSseChunk {
        val json = runCatching { JSONObject(data) }.getOrNull()
            ?: return ParsedSseChunk.Error(
                ProviderError.MalformedResponse("Received malformed SSE JSON chunk from provider.")
            )

        if (json.has("error") && !json.isNull("error")) {
            val errObj = json.optJSONObject("error")
            val code = errObj?.optInt("code", 500) ?: 500
            return ParsedSseChunk.Error(
                NexaHttpClient.mapHttpStatusToError(
                    code = if (code in 400..599) code else 500,
                    body = data,
                    secretToSanitize = secretToSanitize,
                    modelId = modelId
                )
            )
        }

        val usage = parseUsage(json.optJSONObject("usage"))
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            return if (usage != null) ParsedSseChunk.UsageOnly(usage) else ParsedSseChunk.Ignore
        }

        val first = choices.optJSONObject(0) ?: return ParsedSseChunk.Ignore
        val delta = first.optJSONObject("delta")
        val text = when {
            delta != null && delta.has("content") && !delta.isNull("content") -> {
                delta.optString("content", "")
            }
            first.has("message") -> {
                first.optJSONObject("message")?.optString("content", "") ?: ""
            }
            else -> ""
        }

        return when {
            text.isNotEmpty() -> ParsedSseChunk.Delta(text = text, usage = usage)
            usage != null -> ParsedSseChunk.UsageOnly(usage)
            else -> ParsedSseChunk.Ignore
        }
    }

    fun parsePlainJsonCompletion(
        body: String,
        secretToSanitize: String?,
        modelId: String?
    ): Pair<String, TokenUsage?> {
        val json = runCatching { JSONObject(body) }.getOrNull()
            ?: throw ProviderException(
                ProviderError.MalformedResponse("Provider returned invalid JSON response.")
            )
        if (json.has("error") && !json.isNull("error")) {
            throw ProviderException(
                NexaHttpClient.mapHttpStatusToError(
                    code = 500,
                    body = body,
                    secretToSanitize = secretToSanitize,
                    modelId = modelId
                )
            )
        }
        val usage = parseUsage(json.optJSONObject("usage"))
        val choices = json.optJSONArray("choices")
        val content = choices?.optJSONObject(0)?.optJSONObject("message")?.optString("content", "")
            ?: throw ProviderException(
                ProviderError.MalformedResponse("Provider response missing choices[0].message.content.")
            )
        return content to usage
    }

    fun parseUsage(usageObj: JSONObject?): TokenUsage? {
        if (usageObj == null) return null
        val prompt = if (usageObj.has("prompt_tokens")) usageObj.optInt("prompt_tokens") else null
        val completion = if (usageObj.has("completion_tokens")) usageObj.optInt("completion_tokens") else null
        val total = if (usageObj.has("total_tokens")) usageObj.optInt("total_tokens") else null
        if (prompt == null && completion == null && total == null) return null
        return TokenUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
    }

    fun formatContextLabel(tokens: Int?): String {
        if (tokens == null || tokens <= 0) return "Unknown"
        return when {
            tokens >= 1_000_000 -> "${tokens / 1_000_000}M"
            tokens >= 1_000 -> "${tokens / 1_000}K"
            else -> tokens.toString()
        }
    }

    fun parseModelsArray(body: String, inferFromId: Boolean = false): List<AiModelInfo> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        val data = root.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<AiModelInfo>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = item.optString("id").trim()
            if (id.isBlank()) continue
            val name = item.optString("name").takeIf { it.isNotBlank() } ?: id
            val ctxTokens = when {
                item.has("context_length") && !item.isNull("context_length") -> item.optInt("context_length").takeIf { it > 0 }
                item.has("context_window") && !item.isNull("context_window") -> item.optInt("context_window").takeIf { it > 0 }
                else -> null
            }
            val arch = item.optJSONObject("architecture")
            val inputModalities = arch?.optJSONArray("input_modalities")
            val hasImageModality = if (inputModalities != null) {
                (0 until inputModalities.length()).any { idx ->
                    inputModalities.optString(idx).equals("image", ignoreCase = true)
                }
            } else {
                false
            }
            val supportedParams = item.optJSONArray("supported_parameters")
            val hasToolsParam = if (supportedParams != null) {
                (0 until supportedParams.length()).any { idx ->
                    supportedParams.optString(idx).equals("tools", ignoreCase = true)
                }
            } else {
                false
            }

            val lowerId = id.lowercase()
            val supportsVision = if (inputModalities != null) {
                hasImageModality
            } else if (inferFromId) {
                lowerId.contains("gpt-4o") || lowerId.contains("gpt-4.1") ||
                    lowerId.contains("gpt-5") || lowerId.contains("o3") ||
                    lowerId.contains("o4") || lowerId.contains("vision")
            } else {
                false
            }

            list.add(
                AiModelInfo(
                    id = id,
                    displayName = name,
                    contextWindowLabel = formatContextLabel(ctxTokens),
                    contextLengthTokens = ctxTokens,
                    supportsVision = supportsVision,
                    supportsTools = hasToolsParam,
                    isManualEntry = false,
                    isCatalogVerified = true
                )
            )
        }
        return list
    }
}
