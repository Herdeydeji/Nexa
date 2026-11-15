package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.NexaDatabase
import com.example.data.local.NexaTypeConverters
import com.example.data.local.SampleSeedData
import com.example.data.repository.ChatRepository
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `read app name from context matches Nexa`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Nexa", appName)
    }

    @Test
    fun `capability inspector returns all 14 capabilities without false active claims`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val inspector = AndroidCapabilityInspector(context)
        val caps = inspector.inspectAll(emptyMap())
        assertEquals(14, caps.size)

        val accessibility = caps.first { it.type == DeviceCapabilityType.ACCESSIBILITY_INTERACTION }
        assertEquals(CapabilityStatus.NOT_CONFIGURED, accessibility.status)

        val screenshots = caps.first { it.type == DeviceCapabilityType.SCREENSHOTS }
        assertEquals(CapabilityStatus.NOT_CONFIGURED, screenshots.status)
    }

    @Test
    fun `room database removes Phase 1 chat preview while keeping tasks and persisted live messages`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val converters = NexaTypeConverters()
        try {
            val (previewConv, previewMsgs) = SampleSeedData.buildPreviewConversation(1000L, converters)
            db.conversationDao().upsertConversation(previewConv)
            db.conversationDao().insertMessages(previewMsgs)

            db.initializePhase2DataIfNeeded(converters)

            val conversationsAfter = db.conversationDao().observeConversations().first()
            assertTrue(conversationsAfter.none { it.isSamplePreview })
            assertTrue(db.taskDao().countTasks() > 0)

            val chatRepo = ChatRepository(db.conversationDao(), converters)
            val convId = chatRepo.createConversation("Live Thread")
            chatRepo.appendMessage(convId, MessageRole.USER, "Explain Flow")
            val assistantId = chatRepo.appendMessage(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.STREAMING,
                providerLabel = "OpenRouter",
                modelLabel = "anthropic/claude-sonnet-4"
            )
            chatRepo.finalizeMessage(
                messageId = assistantId,
                conversationId = convId,
                content = "Partial stopped reply",
                status = MessageStatus.STOPPED
            )

            val savedMessages = chatRepo.getMessagesSnapshot(convId)
            assertEquals(2, savedMessages.size)
            assertEquals(MessageStatus.STOPPED, savedMessages[1].status)
            assertEquals("Partial stopped reply", savedMessages[1].content)

            // Deleting stopped/failed assistant turn leaves user message intact without duplication
            chatRepo.deleteMessage(assistantId)
            val afterRetryPrep = chatRepo.getMessagesSnapshot(convId)
            assertEquals(1, afterRetryPrep.size)
            assertEquals(MessageRole.USER, afterRetryPrep[0].role)
        } finally {
            db.close()
        }
    }

    @Test
    fun `bottom navigation switches across all four Nexa destinations`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("chat_composer_input").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_tasks").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tasks_sample_toggle").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_connections").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("connections_subtab_providers").assertIsDisplayed()
        composeRule.onNodeWithTag("connections_subtab_telegram").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("telegram_tab_list").assertIsDisplayed()
        composeRule.onNodeWithTag("telegram_tab_list").performScrollToIndex(1)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("telegram_token_input").assertIsDisplayed()
        composeRule.onNodeWithTag("telegram_save_draft_button").assertExists()

        composeRule.onNodeWithTag("connections_subtab_capabilities").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("capabilities_list").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_tab_settings").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("settings_list").assertIsDisplayed()

        assertFalse(false)
    }
}
