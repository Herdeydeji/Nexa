package com.example.domain.model

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM,
    TOOL
}

enum class MessageStatus {
    COMPLETE,
    STREAMING,
    STOPPED,
    FAILED
}

data class ComposerAttachment(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sourceUri: String,
    val sizeBytes: Long = 0L,
    val isImage: Boolean = mimeType.startsWith("image/")
)

data class AttachmentPayload(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val sourceUri: String,
    val textContent: String? = null,
    val imageBase64: String? = null,
    val byteSize: Long = 0L,
    val isSupported: Boolean = true,
    val unsupportedReason: String? = null
)

data class TokenUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val createdAtMillis: Long,
    val status: MessageStatus = MessageStatus.COMPLETE,
    val errorMessage: String? = null,
    val providerLabel: String? = null,
    val modelLabel: String? = null,
    val tokenUsage: TokenUsage? = null,
    val executionPlan: ExecutionPlan? = null,
    val taskSummary: TaskSummary? = null,
    val attachments: List<String> = emptyList(),
    val isPreviewMessage: Boolean = false,
    val toolCalls: List<ToolCall> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null,
    val verificationSummary: TaskVerificationSummary? = null
)

data class Conversation(
    val id: String,
    val title: String,
    val isSamplePreview: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val lastMessagePreview: String = ""
)
