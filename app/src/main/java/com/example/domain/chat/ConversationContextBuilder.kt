package com.example.domain.chat

import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus

data class ConversationContextResult(
    val messages: List<ChatMessage>,
    val droppedOldTurnsCount: Int,
    val estimatedTokens: Int
)

class ConversationContextBuilder {

    fun buildContext(
        history: List<ChatMessage>,
        systemPrompt: String,
        modelContextLengthTokens: Int?,
        reservedOutputTokens: Int = 2_048
    ): ConversationContextResult {
        val eligible = history.filter { msg ->
            msg.role != MessageRole.SYSTEM &&
                msg.status != MessageStatus.FAILED &&
                !(msg.status == MessageStatus.STREAMING && msg.content.isBlank()) &&
                msg.content.isNotBlank()
        }
        if (eligible.isEmpty()) {
            return ConversationContextResult(emptyList(), 0, estimateTokens(systemPrompt))
        }

        val effectiveWindow = (modelContextLengthTokens ?: DEFAULT_CONTEXT_TOKENS)
            .coerceAtLeast(MIN_CONTEXT_TOKENS)
        val systemTokens = estimateTokens(systemPrompt)
        val inputBudget = ((effectiveWindow - reservedOutputTokens) * 0.85).toInt()
            .coerceAtLeast(MIN_INPUT_BUDGET_TOKENS) - systemTokens

        val selectedReversed = mutableListOf<ChatMessage>()
        var accumulatedTokens = 0

        for (index in eligible.indices.reversed()) {
            val message = eligible[index]
            val msgTokens = estimateMessageTokens(message)
            val isLatestUser = (index == eligible.lastIndex && message.role == MessageRole.USER)
            if (isLatestUser || selectedReversed.isEmpty() || accumulatedTokens + msgTokens <= inputBudget) {
                selectedReversed.add(message)
                accumulatedTokens += msgTokens
            } else {
                break
            }
        }

        val finalMessages = selectedReversed.asReversed()
        val dropped = eligible.size - finalMessages.size
        return ConversationContextResult(
            messages = finalMessages,
            droppedOldTurnsCount = dropped,
            estimatedTokens = systemTokens + accumulatedTokens
        )
    }

    fun estimateMessageTokens(message: ChatMessage): Int {
        val attachmentsNote = if (message.attachments.isNotEmpty()) 24 else 0
        return estimateTokens(message.content) + attachmentsNote + 6
    }

    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        return (text.length / 4).coerceAtLeast(1)
    }

    private companion object {
        const val DEFAULT_CONTEXT_TOKENS = 24_000
        const val MIN_CONTEXT_TOKENS = 4_096
        const val MIN_INPUT_BUDGET_TOKENS = 1_500
    }
}
