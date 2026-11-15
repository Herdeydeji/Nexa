package com.example.domain.provider

import com.example.domain.model.AiModelInfo
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.MessageRole
import com.example.domain.model.TokenUsage
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolDefinition
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
            when (msg.role) {
                MessageRole.SYSTEM -> return@forEachIndexed
                MessageRole.USER -> {
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
                            messagesArray.put(JSONObject().put("role", "user").put("content", parts))
                        } else {
                            messagesArray.put(JSONObject().put("role", "user").put("content", contentWithTextFiles))
                        }
                    } else {
                        messagesArray.put(JSONObject().put("role", "user").put("content", msg.content))
                    }
                }
                MessageRole.ASSISTANT -> {
                    val obj = JSONObject().put("role", "assistant")
                    if (msg.toolCalls.isNotEmpty()) {
                        obj.put("content", msg.content.ifBlank { JSONObject.NULL })
                        val callsArr = JSONArray()
                        msg.toolCalls.forEach { tc ->
                            callsArr.put(
                                JSONObject()
                                    .put("id", tc.id)
                                    .put("type", "function")
                                    .put(
                                        "function",
                                        JSONObject()
                                            .put("name", tc.toolName)
                                            .put("arguments", tc.argumentsJson.ifBlank { "{}" })
                                    )
                            )
                        }
                        obj.put("tool_calls", callsArr)
                    } else {
                        obj.put("content", msg.content)
                    }
                    messagesArray.put(obj)
                }
                MessageRole.TOOL -> {
                    val obj = JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", msg.toolCallId ?: "")
                        .put("content", msg.content)
                    if (!msg.toolName.isNullOrBlank()) {
                        obj.put("name", msg.toolName)
                    }
                    messagesArray.put(obj)
                }
            }
        }

        root.put("messages", messagesArray)

        if (request.allowTools && request.tools.isNotEmpty()) {
            val toolsArr = JSONArray()
            request.tools.forEach { tool ->
                toolsArr.put(
                    JSONObject()
                        .put("type", "function")
                        .put(
                            "function",
                            JSONObject()
                                .put("name", tool.name)
                                .put("description", tool.description)
                                .put("parameters", buildJsonSchemaObject(tool))
                        )
                )
            }
            root.put("tools", toolsArr)
            root.put("tool_choice", "auto")
        }

        return root.toString()
    }

    fun buildJsonSchemaObject(tool: ToolDefinition, uppercaseTypes: Boolean = false): JSONObject {
        val props = JSONObject()
        val requiredArr = JSONArray()
        tool.parameters.forEach { param ->
            val pObj = JSONObject()
                .put(
                    "type",
                    if (uppercaseTypes) param.type.jsonType.uppercase() else param.type.jsonType
                )
                .put("description", param.description)
            if (param.enumValues.isNotEmpty()) {
                val enumArr = JSONArray()
                param.enumValues.forEach { enumArr.put(it) }
                pObj.put("enum", enumArr)
            }
            props.put(param.name, pObj)
            if (param.required) {
                requiredArr.put(param.name)
            }
        }
        return JSONObject()
            .put("type", if (uppercaseTypes) "OBJECT" else "object")
            .put("properties", props)
            .apply {
                if (requiredArr.length() > 0) {
                    put("required", requiredArr)
                }
            }
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

    data class StreamToolCallFragment(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String
    )

    class OpenAiToolCallAccumulator {
        private class MutableSlot(
            var id: String? = null,
            val nameBuilder: StringBuilder = StringBuilder(),
            val argsBuilder: StringBuilder = StringBuilder()
        )

        private val slots = linkedMapOf<Int, MutableSlot>()
        private val completeToolCallsFromNonStream = mutableListOf<ToolCall>()

        fun addFragment(fragment: StreamToolCallFragment) {
            val slot = slots.getOrPut(fragment.index) { MutableSlot() }
            if (!fragment.id.isNullOrBlank()) {
                slot.id = fragment.id
            }
            if (!fragment.name.isNullOrBlank()) {
                slot.nameBuilder.append(fragment.name)
            }
            if (fragment.argumentsDelta.isNotEmpty()) {
                slot.argsBuilder.append(fragment.argumentsDelta)
            }
        }

        fun addCompleteToolCalls(calls: List<ToolCall>) {
            calls.forEach { call ->
                if (completeToolCallsFromNonStream.none { it.id == call.id && it.toolName == call.toolName }) {
                    completeToolCallsFromNonStream.add(call)
                }
            }
        }

        fun isEmpty(): Boolean = slots.isEmpty() && completeToolCallsFromNonStream.isEmpty()

        fun buildToolCalls(): List<ToolCall> {
            if (completeToolCallsFromNonStream.isNotEmpty()) {
                return completeToolCallsFromNonStream.toList()
            }
            return slots.entries
                .sortedBy { it.key }
                .mapNotNull { (idx, slot) ->
                    val name = slot.nameBuilder.toString().trim()
                    if (name.isEmpty()) return@mapNotNull null
                    val id = slot.id?.takeIf { it.isNotBlank() } ?: "call_${idx}_$name"
                    val rawArgs = slot.argsBuilder.toString().trim().ifEmpty { "{}" }
                    ToolCall(id = id, toolName = name, argumentsJson = rawArgs)
                }
        }
    }

    sealed interface ParsedSseChunk {
        data class Content(
            val text: String = "",
            val toolCallFragments: List<StreamToolCallFragment> = emptyList(),
            val completeToolCalls: List<ToolCall> = emptyList(),
            val usage: TokenUsage? = null,
            val finishReason: String? = null
        ) : ParsedSseChunk

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
        val finishReason = first.optString("finish_reason").takeIf { it.isNotBlank() && it != "null" }
        val delta = first.optJSONObject("delta")
        val messageObj = first.optJSONObject("message")

        val text = when {
            delta != null && delta.has("content") && !delta.isNull("content") ->
                delta.optString("content", "")
            messageObj != null && messageObj.has("content") && !messageObj.isNull("content") ->
                messageObj.optString("content", "")
            else -> ""
        }

        val fragments = mutableListOf<StreamToolCallFragment>()
        val deltaCalls = delta?.optJSONArray("tool_calls")
        if (deltaCalls != null) {
            for (i in 0 until deltaCalls.length()) {
                val item = deltaCalls.optJSONObject(i) ?: continue
                val idx = item.optInt("index", i)
                val id = item.optString("id").takeIf { it.isNotBlank() && it != "null" }
                val fn = item.optJSONObject("function")
                val name = fn?.optString("name")?.takeIf { it.isNotBlank() && it != "null" }
                val argsDelta = fn?.optString("arguments", "") ?: ""
                fragments.add(
                    StreamToolCallFragment(
                        index = idx,
                        id = id,
                        name = name,
                        argumentsDelta = argsDelta
                    )
                )
            }
        }

        val completeCalls = parseToolCallsArray(messageObj?.optJSONArray("tool_calls"))

        return when {
            text.isNotEmpty() || fragments.isNotEmpty() || completeCalls.isNotEmpty() || finishReason != null -> {
                ParsedSseChunk.Content(
                    text = text,
                    toolCallFragments = fragments,
                    completeToolCalls = completeCalls,
                    usage = usage,
                    finishReason = finishReason
                )
            }
            usage != null -> ParsedSseChunk.UsageOnly(usage)
            else -> ParsedSseChunk.Ignore
        }
    }

    data class PlainCompletionResult(
        val content: String,
        val toolCalls: List<ToolCall>,
        val usage: TokenUsage?
    )

    fun parsePlainJsonCompletionDetailed(
        body: String,
        secretToSanitize: String?,
        modelId: String?
    ): PlainCompletionResult {
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
        val messageObj = choices?.optJSONObject(0)?.optJSONObject("message")
            ?: throw ProviderException(
                ProviderError.MalformedResponse("Provider response missing choices[0].message.")
            )
        val content = if (messageObj.has("content") && !messageObj.isNull("content")) {
            messageObj.optString("content", "")
        } else {
            ""
        }
        val toolCalls = parseToolCallsArray(messageObj.optJSONArray("tool_calls"))
        if (!messageObj.has("content") && toolCalls.isEmpty()) {
            throw ProviderException(
                ProviderError.MalformedResponse("Provider response missing choices[0].message.content or tool_calls.")
            )
        }
        return PlainCompletionResult(content = content, toolCalls = toolCalls, usage = usage)
    }

    fun parsePlainJsonCompletion(
        body: String,
        secretToSanitize: String?,
        modelId: String?
    ): Pair<String, TokenUsage?> {
        val detailed = parsePlainJsonCompletionDetailed(body, secretToSanitize, modelId)
        return detailed.content to detailed.usage
    }

    private fun parseToolCallsArray(arr: JSONArray?): List<ToolCall> {
        if (arr == null) return emptyList()
        val calls = mutableListOf<ToolCall>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val fn = item.optJSONObject("function") ?: continue
            val name = fn.optString("name").trim()
            if (name.isBlank()) continue
            val id = item.optString("id").takeIf { it.isNotBlank() } ?: "call_${i}_$name"
            val args = fn.optString("arguments", "{}").ifBlank { "{}" }
            calls.add(ToolCall(id = id, toolName = name, argumentsJson = args))
        }
        return calls
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

    fun parseModelsArray(
        body: String,
        inferFromId: Boolean = false,
        openRouterAutoTools: Boolean = false
    ): List<AiModelInfo> {
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

            val supportsTools = when {
                supportedParams != null -> hasToolsParam || (openRouterAutoTools && lowerId.endsWith(":free"))
                openRouterAutoTools && lowerId.endsWith(":free") -> {
                    val excluded = lowerId.contains("audio") ||
                        lowerId.contains("tts") ||
                        lowerId.contains("whisper") ||
                        lowerId.contains("embedding")
                    !excluded
                }
                inferFromId -> {
                    val excluded = lowerId.contains("audio") ||
                        lowerId.contains("realtime") ||
                        lowerId.contains("transcribe") ||
                        lowerId.contains("tts") ||
                        lowerId.contains("search-preview")
                    !excluded && (
                        lowerId.contains("gpt-4") ||
                            lowerId.contains("gpt-5") ||
                            lowerId.contains("o3") ||
                            lowerId.contains("o4")
                        )
                }
                else -> false
            }

            list.add(
                AiModelInfo(
                    id = id,
                    displayName = name,
                    contextWindowLabel = formatContextLabel(ctxTokens),
                    contextLengthTokens = ctxTokens,
                    supportsVision = supportsVision,
                    supportsTools = supportsTools,
                    isManualEntry = false,
                    isCatalogVerified = true
                )
            )
        }
        return list
    }

    fun extractSerializedToolNames(requestBodyJson: String): Pair<List<String>, String?> {
        val root = runCatching { JSONObject(requestBodyJson) }.getOrNull() ?: return emptyList<String>() to null
        val toolChoice = root.optString("tool_choice").takeIf { it.isNotBlank() }
        val toolsArr = root.optJSONArray("tools") ?: return emptyList<String>() to toolChoice
        val names = buildList {
            for (i in 0 until toolsArr.length()) {
                val fn = toolsArr.optJSONObject(i)?.optJSONObject("function")
                val name = fn?.optString("name")?.trim()
                if (!name.isNullOrEmpty()) add(name)
            }
        }
        return names to toolChoice
    }
}
