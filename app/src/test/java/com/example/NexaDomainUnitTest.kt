package com.example

import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.Phase1AgentOrchestrator
import com.example.domain.agent.Phase1StubExecutionRuntime
import com.example.domain.agent.RuntimeAction
import com.example.domain.agent.RuntimeResult
import com.example.domain.agent.SafetyDecision
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppSettings
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.ProviderConfig
import com.example.domain.provider.AiProviderRegistry
import com.example.domain.provider.ConnectionTestResult
import com.example.domain.provider.ProviderError
import com.example.domain.security.ApiKeyMasker
import com.example.ui.components.MarkdownBlock
import com.example.ui.components.parseMarkdownBlocks
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexaDomainUnitTest {

    @Test
    fun `api key masker masks prefixes and suffixes and sanitizes secrets`() {
        val openAiKey = "sk-proj-1234567890abcdef"
        val masked = ApiKeyMasker.mask(openAiKey)
        assertTrue(masked.startsWith("sk-proj-"))
        assertTrue(masked.endsWith("cdef"))
        assertFalse(masked.contains("1234567890"))

        val orKey = "sk-or-v1-9988776655443322"
        val orMasked = ApiKeyMasker.mask(orKey)
        assertTrue(orMasked.startsWith("sk-or-v1-"))
        assertTrue(orMasked.endsWith("3322"))

        val sanitized = ApiKeyMasker.sanitizeErrorText(
            "Auth failed for Bearer $openAiKey and secret $orKey",
            secret = openAiKey
        )
        assertFalse(sanitized.contains("1234567890"))
        assertFalse(sanitized.contains("9988776655443322"))
    }

    @Test
    fun `api key and endpoint validation enforces key format and HTTPS for remote hosts`() {
        assertTrue(ApiKeyMasker.validateFormat("") is ApiKeyMasker.ValidationResult.Invalid)
        assertTrue(ApiKeyMasker.validateFormat("sk-123") is ApiKeyMasker.ValidationResult.Invalid)
        assertTrue(ApiKeyMasker.validateFormat("sk-valid key with spaces") is ApiKeyMasker.ValidationResult.Invalid)
        assertTrue(ApiKeyMasker.validateFormat("sk-valid-1234567890") is ApiKeyMasker.ValidationResult.Valid)

        assertTrue(ApiKeyMasker.validateEndpointUrl("https://api.example.com/v1", required = true) is ApiKeyMasker.ValidationResult.Valid)
        assertTrue(ApiKeyMasker.validateEndpointUrl("http://localhost:11434/v1", required = true) is ApiKeyMasker.ValidationResult.Valid)
        assertTrue(ApiKeyMasker.validateEndpointUrl("http://192.168.1.25:8080/v1", required = true) is ApiKeyMasker.ValidationResult.Valid)
        assertTrue(ApiKeyMasker.validateEndpointUrl("http://remote-host.example.com/v1", required = true) is ApiKeyMasker.ValidationResult.Invalid)
    }

    @Test
    fun `provider registry registers all 5 adapters and rejects missing credentials`() = runTest {
        val registry = AiProviderRegistry()
        val adapters = registry.allAdapters()
        assertEquals(AiProviderType.entries.size, adapters.size)

        val openRouterAdapter = registry.getAdapter(AiProviderType.OPENROUTER)
        val unconfigured = ProviderConfig(
            providerType = AiProviderType.OPENROUTER,
            hasApiKey = false,
            maskedApiKey = "",
            baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
            selectedModelId = openRouterAdapter.defaultModels().first().id,
            isDefault = false,
            availableModels = openRouterAdapter.defaultModels()
        )
        val result = openRouterAdapter.testConfiguration(unconfigured, rawApiKey = "")
        assertTrue(result is ConnectionTestResult.Failure)
        assertTrue((result as ConnectionTestResult.Failure).error is ProviderError.MissingApiKey)
    }

    @Test
    fun `safety policy requires approval for high risk communication tools`() {
        val toolRegistry = DefaultToolRegistry()
        val safety = DefaultSafetyPolicyManager()
        val smsTool = toolRegistry.findTool("comms.draft_sms")!!
        val pickerTool = toolRegistry.findTool("media.pick_attachment")!!

        val strictSettings = AppSettings(
            confirmationMode = ConfirmationMode.SUPERVISED_AUTO,
            requireApprovalForExternalComms = true
        )
        assertTrue(safety.evaluateTool(smsTool, strictSettings) is SafetyDecision.RequireUserApproval)
        assertEquals(SafetyDecision.Allow, safety.evaluateTool(pickerTool, strictSettings))
    }

    @Test
    fun `phase 1 execution runtime never falsely reports active automation`() = runTest {
        val runtime = Phase1StubExecutionRuntime()
        assertFalse(runtime.isAutomationActive)
        val result = runtime.execute(RuntimeAction("device.launch_app", mapOf("package" to "com.google.android.youtube")))
        assertTrue(result is RuntimeResult.NotImplementedInPhase1)
    }

    @Test
    fun `phase 1 orchestrator produces non-executed plan for task prompts and text for general chat`() = runTest {
        val toolRegistry = DefaultToolRegistry()
        val orchestrator = Phase1AgentOrchestrator(toolRegistry, DefaultSafetyPolicyManager())

        val taskTurn = orchestrator.handleUserPrompt(
            prompt = "Open YouTube and play Asake",
            activeProvider = null,
            settings = AppSettings()
        )
        assertNotNull(taskTurn.executionPlan)
        assertTrue(taskTurn.executionPlan!!.isPreview)
        assertTrue(taskTurn.executionPlan!!.steps.any { it.state == ExecutionStepState.AWAITING_CONFIRMATION })

        val chatTurn = orchestrator.handleUserPrompt(
            prompt = "Explain how providers are structured",
            activeProvider = null,
            settings = AppSettings()
        )
        assertNull(chatTurn.executionPlan)
        assertTrue(chatTurn.assistantReply.contains("Phase 1"))
    }

    @Test
    fun `capability enum covers all 14 required Android capabilities`() {
        assertEquals(14, DeviceCapabilityType.entries.size)
    }

    @Test
    fun `markdown parser extracts headings bullets and fenced code blocks`() {
        val md = """
            # Overview
            - First item
            ```kotlin
            val x = 1
            ```
        """.trimIndent()
        val blocks = parseMarkdownBlocks(md)
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is MarkdownBlock.Heading)
        assertTrue(blocks[1] is MarkdownBlock.Bullet)
        assertTrue(blocks[2] is MarkdownBlock.CodeFence)
    }
}
