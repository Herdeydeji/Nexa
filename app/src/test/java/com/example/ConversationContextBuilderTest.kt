package com.example

import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ResponseVerbosity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationContextBuilderTest {

    private val systemPromptBuilder = SystemPromptBuilder()
    private val contextBuilder = ConversationContextBuilder()

    @Test
    fun `system prompt includes capability boundaries verbosity and custom prompt`() {
        val prompt = systemPromptBuilder.build(
            AppSettings(
                responseVerbosity = ResponseVerbosity.CONCISE,
                customSystemPrompt = "Always answer with Kotlin 2.0 examples."
            )
        )
        assertTrue(prompt.contains("conversational AI assistant"))
        assertTrue(prompt.contains("Device automation"))
        assertTrue(prompt.contains("concise", ignoreCase = true))
        assertTrue(prompt.contains("Always answer with Kotlin 2.0 examples."))
    }

    @Test
    fun `context builder excludes failed turns and blank streaming placeholders`() {
        val messages = listOf(
            msg("1", MessageRole.USER, "Hello", MessageStatus.COMPLETE),
            msg("2", MessageRole.ASSISTANT, "Hi there!", MessageStatus.COMPLETE),
            msg("3", MessageRole.USER, "Bad request turn", MessageStatus.COMPLETE),
            msg("4", MessageRole.ASSISTANT, "", MessageStatus.FAILED),
            msg("5", MessageRole.ASSISTANT, "Partial stopped output", MessageStatus.STOPPED),
            msg("6", MessageRole.USER, "Latest follow up", MessageStatus.COMPLETE),
            msg("7", MessageRole.ASSISTANT, "", MessageStatus.STREAMING)
        )

        val result = contextBuilder.buildContext(
            history = messages,
            systemPrompt = "System",
            modelContextLengthTokens = 16_000
        )

        val ids = result.messages.map { it.id }
        assertFalse("4" in ids)
        assertFalse("7" in ids)
        assertTrue("5" in ids)
        assertTrue("6" in ids)
        assertEquals("6", result.messages.last().id)
    }

    @Test
    fun `context builder trims oldest turns while preserving latest user turn`() {
        val longOldTurn = "word ".repeat(2_000) // ~2500 tokens
        val messages = listOf(
            msg("1", MessageRole.USER, longOldTurn),
            msg("2", MessageRole.ASSISTANT, longOldTurn),
            msg("3", MessageRole.USER, "Recent question"),
            msg("4", MessageRole.ASSISTANT, "Recent answer"),
            msg("5", MessageRole.USER, "Follow up question")
        )

        val result = contextBuilder.buildContext(
            history = messages,
            systemPrompt = "You are Nexa.",
            modelContextLengthTokens = 4_096,
            reservedOutputTokens = 1_024
        )

        assertTrue(result.droppedOldTurnsCount > 0)
        assertEquals("5", result.messages.last().id)
        assertFalse(result.messages.any { it.id == "1" })
    }

    private fun msg(
        id: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE
    ) = ChatMessage(
        id = id,
        conversationId = "c1",
        role = role,
        content = content,
        createdAtMillis = 1000L,
        status = status
    )
}
