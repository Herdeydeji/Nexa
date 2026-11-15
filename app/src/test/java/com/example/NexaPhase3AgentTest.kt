package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.NexaDatabase
import com.example.data.local.NexaPreferencesDataStore
import com.example.data.local.NexaTypeConverters
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TaskRepository
import com.example.domain.agent.AgentControlHandle
import com.example.domain.agent.AgentRunController
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.CalculatorExpressionParser
import com.example.domain.agent.CalculatorTool
import com.example.domain.agent.DateTimeTool
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.DeviceInfoTool
import com.example.domain.agent.ExecutableTool
import com.example.domain.agent.ProviderStreamCaller
import com.example.domain.agent.ToolExecutionOutput
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.agent.ToolPolicyEvaluation
import com.example.domain.chat.AndroidAttachmentProcessor
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentExecutionLimits
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppSettings
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolDefinition
import com.example.domain.model.ToolExecutionStatus
import com.example.domain.model.ToolParameter
import com.example.domain.model.ToolParameterType
import com.example.domain.model.ToolRiskCategory
import com.example.domain.provider.AiProviderRegistry
import com.example.domain.provider.AnthropicProviderAdapter
import com.example.domain.provider.CustomOpenAiProviderAdapter
import com.example.domain.provider.GeminiProviderAdapter
import com.example.domain.provider.NetworkAvailabilityChecker
import com.example.domain.provider.NexaHttpClient
import com.example.domain.provider.OpenAiCompatibleJson
import com.example.domain.provider.OpenAiProviderAdapter
import com.example.domain.provider.OpenRouterProviderAdapter
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderResponse
import com.example.domain.provider.ProviderStreamEvent
import com.example.domain.security.CredentialCipher
import com.example.ui.chat.ChatScreen
import com.example.ui.chat.ChatViewModel
import com.example.ui.components.ApprovalGateCard
import com.example.ui.theme.NexaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaPhase3AgentTest {

    @get:Rule
    val composeRule = createComposeRule()

    // --- Shared Test Helpers ---

    private data class RecordedCall(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String
    )

    private class MockInterceptor(
        private val handler: (RecordedCall) -> Response
    ) : Interceptor {
        val calls = mutableListOf<RecordedCall>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val buf = Buffer()
            req.body?.writeTo(buf)
            val recorded = RecordedCall(
                method = req.method,
                url = req.url.toString(),
                headers = req.headers.names().associateWith { req.header(it).orEmpty() },
                body = buf.readUtf8()
            )
            calls.add(recorded)
            return handler(recorded)
        }
    }

    private fun httpClient(interceptor: MockInterceptor): NexaHttpClient {
        val okHttp = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val onlineChecker = object : NetworkAvailabilityChecker {
            override fun isNetworkAvailable(): Boolean = true
        }
        return NexaHttpClient(okHttpClient = okHttp, networkChecker = onlineChecker)
    }

    private fun jsonResp(url: String, code: Int, body: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    private fun sseResp(url: String, sse: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "text/event-stream; charset=utf-8")
            .body(sse.toResponseBody("text/event-stream; charset=utf-8".toMediaType()))
            .build()

    private fun toolCapableConfig(
        type: AiProviderType = AiProviderType.OPENROUTER,
        modelId: String = "openai/gpt-4.1",
        supportsTools: Boolean = true,
        allowOverride: Boolean = false
    ) = ProviderConfig(
        providerType = type,
        hasApiKey = true,
        maskedApiKey = "••••test",
        baseUrl = type.defaultBaseUrl.ifBlank { "http://localhost:11434/v1" },
        selectedModelId = modelId,
        isDefault = true,
        availableModels = listOf(
            AiModelInfo(
                id = modelId,
                displayName = modelId,
                contextWindowLabel = "128K",
                contextLengthTokens = 128_000,
                supportsVision = false,
                supportsTools = supportsTools,
                isCatalogVerified = supportsTools
            )
        ),
        allowManualToolsOverride = allowOverride
    )

    private fun testContext(
        prompt: String,
        registry: DefaultToolRegistry,
        provider: ProviderConfig = toolCapableConfig(),
        settings: AppSettings = AppSettings(),
        limits: AgentExecutionLimits = settings.toExecutionLimits(128_000)
    ) = AgentExecutionContext(
        taskId = "task_test_${System.nanoTime()}",
        conversationId = "conv_test",
        userPrompt = prompt,
        provider = provider,
        settings = settings,
        limits = limits,
        availableTools = if (provider.effectiveSupportsTools) registry.getAvailableToolDefinitions() else emptyList(),
        systemPrompt = SystemPromptBuilder().build(settings, registry.getAvailableToolDefinitions(), provider.effectiveSupportsTools)
    )

    private fun initialHistory(prompt: String): List<ChatMessage> = listOf(
        ChatMessage(
            id = "u_1",
            conversationId = "conv_test",
            role = MessageRole.USER,
            content = prompt,
            createdAtMillis = 100L,
            status = MessageStatus.COMPLETE
        )
    )

    // --- Calculator, DateTime, DeviceInfo, Safety Unit Tests ---

    @Test
    fun `calculator expression parser handles parentheses decimals percentages commas and rejects invalid syntax`() {
        assertEquals(0, CalculatorExpressionParser.evaluate("14500 + 2750 - 500").result.compareTo(BigDecimal("16750")))
        assertEquals(0, CalculatorExpressionParser.evaluate("14,500 + 2,750").result.compareTo(BigDecimal("17250")))
        assertEquals(0, CalculatorExpressionParser.evaluate("(10 + 5) * 2").result.compareTo(BigDecimal("30")))
        assertEquals(0, CalculatorExpressionParser.evaluate("20% of 16750").result.compareTo(BigDecimal("3350")))
        assertEquals(0, CalculatorExpressionParser.evaluate("100 + 10%").result.compareTo(BigDecimal("110")))
        assertEquals(0, CalculatorExpressionParser.evaluate("3.5 * 2").result.compareTo(BigDecimal("7")))

        val divZero = runCatching { CalculatorExpressionParser.evaluate("100 / 0") }.exceptionOrNull()
        assertNotNull(divZero)
        assertTrue(divZero!!.message!!.contains("zero", ignoreCase = true))

        val badExpr = runCatching { CalculatorExpressionParser.evaluate("System.exit(0)") }.exceptionOrNull()
        assertNotNull(badExpr)
    }

    @Test
    fun `datetime tool returns real clock data and validates timezones`() = runTest {
        val tool = DateTimeTool()
        val ok = tool.execute(JSONObject().put("timezone", "UTC"))
        assertEquals("UTC", ok.outputJson.getString("timezoneId"))
        assertEquals("+00:00", ok.outputJson.getString("utcOffset"))
        assertTrue(ok.outputJson.getLong("epochMillis") > 1_700_000_000_000L)
        assertTrue(ok.outputJson.getString("iso8601").contains("T"))

        assertNotNull(tool.validateArguments(JSONObject().put("timezone", "Mars/Olympus_Mons")))
    }

    @Test
    fun `safety policy enforces non-bypassable approval for sensitive destructive and external tools`() {
        val safety = DefaultSafetyPolicyManager()
        val autoSettings = AppSettings(confirmationMode = ConfirmationMode.SUPERVISED_AUTO)
        val alwaysConfirm = AppSettings(confirmationMode = ConfirmationMode.ALWAYS_CONFIRM)

        val readOnlyTool = CalculatorTool()
        assertTrue(
            safety.evaluateToolCall("t1", readOnlyTool, ToolCall("c1", "calculator", "{}"), JSONObject(), autoSettings)
                is ToolPolicyEvaluation.Allow
        )
        assertTrue(
            safety.evaluateToolCall("t1", readOnlyTool, ToolCall("c1", "calculator", "{}"), JSONObject(), alwaysConfirm)
                is ToolPolicyEvaluation.RequireUserApproval
        )

        val sensitiveTool = fakeRiskTool(ToolRiskCategory.SENSITIVE)
        val destructiveTool = fakeRiskTool(ToolRiskCategory.DESTRUCTIVE)
        val externalTool = fakeRiskTool(ToolRiskCategory.EXTERNAL_SIDE_EFFECT)

        assertTrue(
            safety.evaluateToolCall("t1", sensitiveTool, ToolCall("c2", "sensitive_test", "{}"), JSONObject(), autoSettings)
                is ToolPolicyEvaluation.RequireUserApproval
        )
        assertTrue(
            safety.evaluateToolCall("t1", destructiveTool, ToolCall("c3", "sensitive_test", "{}"), JSONObject(), autoSettings)
                is ToolPolicyEvaluation.RequireUserApproval
        )
        assertTrue(
            safety.evaluateToolCall("t1", externalTool, ToolCall("c4", "sensitive_test", "{}"), JSONObject(), autoSettings)
                is ToolPolicyEvaluation.RequireUserApproval
        )
    }

    // --- Acceptance Tests A through J ---

    @Test
    fun `acceptance A conversational prompt completes without tool calls or execution panel`() = runTest {
        val registry = DefaultToolRegistry()
        val runtime = ToolExecutionRuntime(registry)
        val caller = ProviderStreamCaller { _, req ->
            assertTrue(req.allowTools)
            flow {
                emit(ProviderStreamEvent.Delta("Dependency injection "))
                emit(ProviderStreamEvent.Delta("decouples components cleanly."))
                emit(ProviderStreamEvent.Completed(TokenUsage(20, 10, 30)))
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, DefaultSafetyPolicyManager(), runtime)
        val events = orchestrator.runTurn(
            context = testContext("Explain dependency injection in Kotlin.", registry),
            historySnapshot = initialHistory("Explain dependency injection in Kotlin."),
            attachments = emptyList()
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals("Dependency injection decouples components cleanly.", completed.finalText)
        assertNull(completed.plan)
        assertEquals(0, completed.verification.requestedToolCalls)
        assertTrue(completed.verification.isVerified)
        assertTrue(events.none { it is AgentEvent.ToolStarted })
    }

    @Test
    fun `acceptance B single tool call executes calculator and returns verified result`() = runTest {
        val registry = DefaultToolRegistry()
        val runtime = ToolExecutionRuntime(registry)
        val recordedRequests = mutableListOf<ProviderRequest>()
        var turn = 0
        val caller = ProviderStreamCaller { _, req ->
            recordedRequests.add(req)
            turn++
            flow {
                if (turn == 1) {
                    emit(
                        ProviderStreamEvent.ToolCallsCompleted(
                            listOf(ToolCall("call_calc_1", "calculator", """{"expression":"14500 + 2750 - 500"}""")),
                            TokenUsage(30, 12, 42)
                        )
                    )
                    emit(ProviderStreamEvent.Completed())
                } else {
                    emit(ProviderStreamEvent.Delta("The calculated total is 16750."))
                    emit(ProviderStreamEvent.Completed(TokenUsage(50, 15, 65)))
                }
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, DefaultSafetyPolicyManager(), runtime)
        val events = orchestrator.runTurn(
            context = testContext("What is 14500 + 2750 - 500?", registry),
            historySnapshot = initialHistory("What is 14500 + 2750 - 500?"),
            attachments = emptyList()
        ).toList()

        val toolRes = events.filterIsInstance<AgentEvent.ToolCompleted>().single().result
        assertEquals(ToolExecutionStatus.SUCCEEDED, toolRes.status)
        assertEquals("16750", JSONObject(toolRes.outputJson!!).getString("result"))

        // Second LLM call received the tool call and real tool output
        val secondMessages = recordedRequests[1].messages
        val toolRoleMsg = secondMessages.first { it.role == MessageRole.TOOL }
        assertEquals("call_calc_1", toolRoleMsg.toolCallId)
        assertTrue(toolRoleMsg.content.contains("16750"))

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertFalse(completed.plan!!.isPreview)
        assertTrue(completed.verification.isVerified)
        assertEquals(1, completed.verification.succeededCount)
        assertEquals(107, completed.usage?.totalTokens)
    }

    @Test
    fun `acceptance C multi-step sequential tool calls propagate intermediate results accurately`() = runTest {
        val registry = DefaultToolRegistry()
        val runtime = ToolExecutionRuntime(registry)
        val observedToolResults = mutableListOf<String>()
        var turn = 0
        val caller = ProviderStreamCaller { _, req ->
            req.messages.filter { it.role == MessageRole.TOOL }.forEach {
                if (it.content !in observedToolResults) observedToolResults.add(it.content)
            }
            turn++
            flow {
                when (turn) {
                    1 -> emit(
                        ProviderStreamEvent.ToolCallsCompleted(
                            listOf(ToolCall("c1", "calculator", """{"expression":"14500 + 2750 - 500"}"""))
                        )
                    )
                    2 -> emit(
                        ProviderStreamEvent.ToolCallsCompleted(
                            listOf(ToolCall("c2", "calculator", """{"expression":"20% of 16750"}"""))
                        )
                    )
                    else -> emit(ProviderStreamEvent.Delta("20% of 16,750 is 3,350."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, DefaultSafetyPolicyManager(), runtime)
        val events = orchestrator.runTurn(
            context = testContext("Compute 14500 + 2750 - 500 then 20% of that.", registry),
            historySnapshot = initialHistory("Compute 14500 + 2750 - 500 then 20% of that."),
            attachments = emptyList()
        ).toList()

        assertEquals(2, observedToolResults.size)
        assertTrue(observedToolResults[0].contains("16750"))
        assertTrue(observedToolResults[1].contains("3350"))

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(2, completed.verification.succeededCount)
        assertTrue(completed.verification.isVerified)
        assertEquals(3, turn)
    }

    @Test
    fun `acceptance D live device info tool returns non-sensitive Android metadata without identifiers`() = runTest {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        val registry = DefaultToolRegistry(context = appContext)
        val runtime = ToolExecutionRuntime(registry)
        var capturedOutputJson = ""
        var turn = 0
        val caller = ProviderStreamCaller { _, req ->
            turn++
            if (turn == 2) {
                capturedOutputJson = req.messages.last { it.role == MessageRole.TOOL }.content
            }
            flow {
                if (turn == 1) {
                    emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_dev", "get_device_info", "{}"))))
                } else {
                    emit(ProviderStreamEvent.Delta("Device info retrieved."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, DefaultSafetyPolicyManager(), runtime)
        val events = orchestrator.runTurn(
            context = testContext("Check my Android version and storage.", registry),
            historySnapshot = initialHistory("Check my Android version and storage."),
            attachments = emptyList()
        ).toList()

        val json = JSONObject(capturedOutputJson)
        assertTrue(json.has("androidVersion"))
        assertTrue(json.getInt("sdkInt") > 0)
        assertTrue(json.has("model"))
        assertTrue(json.has("availableStorageFormatted"))
        assertFalse(json.has("imei"))
        assertFalse(json.has("androidId"))
        assertFalse(json.has("serial"))
        assertTrue(events.filterIsInstance<AgentEvent.Completed>().single().verification.isVerified)
    }

    @Test
    fun `acceptance E tool division by zero surfaces structured error to model without hallucination`() = runTest {
        val registry = DefaultToolRegistry()
        val runtime = ToolExecutionRuntime(registry)
        var turn = 0
        val caller = ProviderStreamCaller { _, req ->
            turn++
            flow {
                if (turn == 1) {
                    emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_div", "calculator", """{"expression":"100 / 0"}"""))))
                } else {
                    val errToolMsg = req.messages.last { it.role == MessageRole.TOOL }.content
                    assertTrue(errToolMsg.contains("error"))
                    emit(ProviderStreamEvent.Delta("Division by zero is undefined; the calculator returned an error."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, DefaultSafetyPolicyManager(), runtime)
        val events = orchestrator.runTurn(
            context = testContext("What is 100 / 0?", registry),
            historySnapshot = initialHistory("What is 100 / 0?"),
            attachments = emptyList()
        ).toList()

        val toolCompleted = events.filterIsInstance<AgentEvent.ToolCompleted>().single()
        assertEquals(ToolExecutionStatus.FAILED, toolCompleted.result.status)
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertFalse(completed.verification.isVerified)
        assertEquals(1, completed.verification.failedCount)
    }

    @Test
    fun `acceptance F unknown tool and malformed json arguments are safely rejected`() = runTest {
        val registry = DefaultToolRegistry()
        val runtime = ToolExecutionRuntime(registry)
        var turn = 0
        val caller = ProviderStreamCaller { _, _ ->
            turn++
            flow {
                if (turn == 1) {
                    emit(
                        ProviderStreamEvent.ToolCallsCompleted(
                            listOf(
                                ToolCall("c_unk", "nonexistent_tool", """{"x":1}"""),
                                ToolCall("c_bad", "calculator", """{invalid json""")
                            )
                        )
                    )
                } else {
                    emit(ProviderStreamEvent.Delta("Those tool calls could not run because one tool is unknown and the other had invalid arguments."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, DefaultSafetyPolicyManager(), runtime)
        val events = orchestrator.runTurn(
            context = testContext("Test invalid tool calls.", registry),
            historySnapshot = initialHistory("Test invalid tool calls."),
            attachments = emptyList()
        ).toList()

        val toolResults = events.filterIsInstance<AgentEvent.ToolCompleted>().map { it.result }
        assertEquals(2, toolResults.size)
        assertTrue(toolResults.all { it.status == ToolExecutionStatus.FAILED })
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertFalse(completed.verification.isVerified)
        assertEquals(2, completed.verification.failedCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `acceptance G user cancellation stops execution mid-run and persists CANCELLED task and STOPPED message`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java).allowMainThreadQueries().build()
        val converters = NexaTypeConverters()
        val fakeCipher = object : CredentialCipher {
            override fun encrypt(plainText: String): String = "enc:$plainText"
            override fun decrypt(cipherPayload: String): String = cipherPayload.removePrefix("enc:")
        }
        val prefs = NexaPreferencesDataStore(context, fakeCipher)
        val secondToolExecuted = AtomicInteger(0)
        val registry = DefaultToolRegistry(
            context = context,
            initialExecutableTools = listOf(
                slowTool(onSecondCall = { secondToolExecuted.incrementAndGet() })
            )
        )
        val toolStartedGate = CompletableDeferred<Unit>()
        val caller = ProviderStreamCaller { _, _ ->
            flow {
                emit(
                    ProviderStreamEvent.ToolCallsCompleted(
                        listOf(
                            ToolCall("c_slow_1", "slow_tool", """{"step":"1"}"""),
                            ToolCall("c_slow_2", "slow_tool", """{"step":"2"}""")
                        )
                    )
                )
                emit(ProviderStreamEvent.Completed())
            }
        }
        val runtime = ToolExecutionRuntime(registry)
        val runController = AgentRunController()
        val orchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = caller,
            toolRegistry = registry,
            safetyPolicyManager = DefaultSafetyPolicyManager(),
            executionRuntime = runtime
        )
        val chatRepo = ChatRepository(db.conversationDao(), converters)
        val taskRepo = TaskRepository(db.taskDao(), converters)
        val connectionsRepo = ConnectionsRepository(prefs, AiProviderRegistry(), AndroidCapabilityInspector(context))
        val settingsRepo = SettingsRepository(prefs)
        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            connectionsRepository = connectionsRepo,
            settingsRepository = settingsRepo,
            toolRegistry = registry,
            systemPromptBuilder = SystemPromptBuilder(),
            contextBuilder = ConversationContextBuilder(),
            attachmentProcessor = AndroidAttachmentProcessor(context),
            taskRepository = taskRepo,
            autonomousOrchestrator = orchestrator,
            agentRunController = runController
        )

        try {
            prefs.saveProviderConfig(
                type = AiProviderType.CUSTOM,
                rawApiKey = null,
                baseUrl = "http://localhost:11434/v1",
                selectedModelId = "qwen2.5:14b",
                manualModelId = "qwen2.5:14b",
                setAsDefault = true,
                allowManualToolsOverride = true
            )
            val uiCollector = launch(mainDispatcher) { viewModel.uiState.collect { } }
            val runCollector = launch(mainDispatcher) {
                runController.state.collect { st ->
                    if (st.runStatus == AgentRunStatus.EXECUTING_TOOL && !toolStartedGate.isCompleted) {
                        toolStartedGate.complete(Unit)
                    }
                }
            }
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (viewModel.uiState.value.defaultProvider?.selectedModelId != "qwen2.5:14b") {
                            delay(20)
                        }
                    }
                }
                viewModel.sendMessage("Run two slow tools")
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) { toolStartedGate.await() }
                }
                viewModel.stopResponse()

                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (viewModel.uiState.value.isResponding) {
                            delay(20)
                        }
                    }
                }
                assertEquals(0, secondToolExecuted.get())
                val convId = viewModel.uiState.value.activeConversation!!.id
                val msgs = chatRepo.getMessagesSnapshot(convId)
                val lastMsg = msgs.last()
                assertEquals(MessageStatus.STOPPED, lastMsg.status)

                val tasks = taskRepo.allTasks.first().filterNot { it.isSampleData }
                assertEquals(1, tasks.size)
                assertEquals(AgentTaskStatus.CANCELLED, tasks.first().status)
            } finally {
                uiCollector.cancel()
                runCollector.cancel()
            }
        } finally {
            prefs.clearProviderKey(AiProviderType.CUSTOM)
            db.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `acceptance H iteration limit and repeated failure loop guard terminate runaway tool loops cleanly`() = runTest {
        val registry = DefaultToolRegistry()
        val runtime = ToolExecutionRuntime(registry)
        val infiniteCaller = ProviderStreamCaller { _, _ ->
            flow {
                emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_loop_${System.nanoTime()}", "calculator", """{"expression":"2 + 2"}"""))))
                emit(ProviderStreamEvent.Completed())
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(infiniteCaller, registry, DefaultSafetyPolicyManager(), runtime)
        val events = orchestrator.runTurn(
            context = testContext(
                prompt = "Loop forever",
                registry = registry,
                limits = AgentExecutionLimits(maxIterations = 3, maxToolCalls = 10)
            ),
            historySnapshot = initialHistory("Loop forever"),
            attachments = emptyList()
        ).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.errorMessage.contains("iteration limit", ignoreCase = true))
        assertFalse(failed.verification.isVerified)
        assertEquals(3, events.filterIsInstance<AgentEvent.ToolCompleted>().size)

        // Also verify duplicate identical failure loop guard triggers cleanly
        val failToolCalls = mutableListOf<String>()
        val badLoopCaller = ProviderStreamCaller { _, req ->
            val count = req.messages.count { it.role == MessageRole.TOOL }
            flow {
                if (count < 3) {
                    emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_f_$count", "calculator", """{"expression":"1 / 0"}"""))))
                } else {
                    failToolCalls.add(req.messages.last { it.role == MessageRole.TOOL }.content)
                    emit(ProviderStreamEvent.Delta("Stopped after repeated failure."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }
        DefaultAutonomousAgentOrchestrator(badLoopCaller, registry, DefaultSafetyPolicyManager(), runtime)
            .runTurn(
                context = testContext("Repeat bad calc", registry),
                historySnapshot = initialHistory("Repeat bad calc"),
                attachments = emptyList()
            ).toList()
        assertTrue(failToolCalls.single().contains("infinite loop", ignoreCase = true))
    }

    @Test
    fun `acceptance I sensitive approval gate blocks before confirmation and respects approve vs reject`() = runTest {
        val executions = AtomicInteger(0)
        val sensitiveTool = fakeRiskTool(ToolRiskCategory.SENSITIVE) {
            executions.incrementAndGet()
            ToolExecutionOutput(JSONObject().put("executed", true), "Sensitive action completed")
        }
        val registry = DefaultToolRegistry(initialExecutableTools = listOf(sensitiveTool))
        val runtime = ToolExecutionRuntime(registry)
        var turn = 0
        val caller = ProviderStreamCaller { _, _ ->
            turn++
            flow {
                if (turn == 1) {
                    emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_sens_1", "sensitive_test", """{"target":"demo"}"""))))
                } else {
                    emit(ProviderStreamEvent.Delta("Turn finished."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, DefaultSafetyPolicyManager(), runtime)

        // Case 1: REJECT -> tool MUST NEVER execute
        val rejectControl = object : AgentControlHandle {
            override fun isPausedNow(): Boolean = false
            override suspend fun awaitIfPaused() = Unit
            override suspend fun requestApproval(request: ToolApprovalRequest): ApprovalDecision {
                assertEquals(0, executions.get())
                return ApprovalDecision.REJECT
            }
        }
        val rejectEvents = orchestrator.runTurn(
            context = testContext("Do sensitive thing", registry, settings = AppSettings(confirmationMode = ConfirmationMode.SUPERVISED_AUTO)),
            historySnapshot = initialHistory("Do sensitive thing"),
            attachments = emptyList(),
            control = rejectControl
        ).toList()
        assertEquals(0, executions.get())
        assertTrue(rejectEvents.any { it is AgentEvent.ApprovalRequired })
        assertEquals(ToolExecutionStatus.REJECTED, rejectEvents.filterIsInstance<AgentEvent.ToolCompleted>().single().result.status)

        // Case 2: APPROVE -> tool executes once
        turn = 0
        val approveControl = object : AgentControlHandle {
            override fun isPausedNow(): Boolean = false
            override suspend fun awaitIfPaused() = Unit
            override suspend fun requestApproval(request: ToolApprovalRequest): ApprovalDecision {
                assertEquals(0, executions.get())
                return ApprovalDecision.APPROVE
            }
        }
        val approveEvents = orchestrator.runTurn(
            context = testContext("Do sensitive thing", registry, settings = AppSettings(confirmationMode = ConfirmationMode.SUPERVISED_AUTO)),
            historySnapshot = initialHistory("Do sensitive thing"),
            attachments = emptyList(),
            control = approveControl
        ).toList()
        assertEquals(1, executions.get())
        assertEquals(ToolExecutionStatus.SUCCEEDED, approveEvents.filterIsInstance<AgentEvent.ToolCompleted>().single().result.status)
    }

    @Test
    fun `acceptance J persistence across simulated app restart preserves live tasks verification and reconciles crashed runs`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbFile = File(context.filesDir, "test_phase3_restart_${System.nanoTime()}.db")
        val converters = NexaTypeConverters()
        try {
            // Session 1: Create v3 DB, persist live completed task + crashed RUNNING task + tool-assisted messages
            val db1 = Room.databaseBuilder(context, NexaDatabase::class.java, dbFile.absolutePath)
                .addMigrations(NexaDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()
            val chatRepo1 = ChatRepository(db1.conversationDao(), converters)
            val taskRepo1 = TaskRepository(db1.taskDao(), converters)

            val convId = chatRepo1.createConversation("Budget calc")
            chatRepo1.appendMessage(convId, MessageRole.USER, "Calculate 14500 + 2750 - 500")
            chatRepo1.appendMessage(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = "",
                toolCalls = listOf(ToolCall("c1", "calculator", """{"expression":"14500 + 2750 - 500"}"""))
            )
            chatRepo1.appendMessage(
                conversationId = convId,
                role = MessageRole.TOOL,
                content = """{"result":"16750"}""",
                toolCallId = "c1",
                toolName = "calculator"
            )
            chatRepo1.appendMessage(
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = "The total is 16,750.",
                verificationSummary = com.example.domain.model.TaskVerificationSummary(
                    requestedToolCalls = 1,
                    executedToolCalls = 1,
                    succeededCount = 1,
                    isVerified = true,
                    summaryNote = "Verified 1/1 tool call(s) succeeded."
                )
            )
            taskRepo1.upsertLiveTask(
                taskId = "task_done",
                title = "Calculate budget",
                prompt = "Calculate 14500 + 2750 - 500",
                status = AgentTaskStatus.COMPLETED,
                providerLabel = "OpenAI",
                modelLabel = "gpt-4.1",
                requiredCapabilities = listOf("calculator"),
                steps = listOf(
                    com.example.domain.model.ExecutionStep("s1", "Run calculator", "16750", com.example.domain.model.ExecutionStepState.COMPLETED, "calculator")
                )
            )
            taskRepo1.upsertLiveTask(
                taskId = "task_crashed",
                title = "Interrupted task",
                prompt = "Interrupted",
                status = AgentTaskStatus.RUNNING,
                providerLabel = "OpenAI",
                modelLabel = "gpt-4.1",
                requiredCapabilities = listOf("calculator"),
                steps = emptyList()
            )
            db1.close()

            // Session 2: Reopen DB and run startup initialization
            val db2 = Room.databaseBuilder(context, NexaDatabase::class.java, dbFile.absolutePath)
                .addMigrations(NexaDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()
            db2.initializePhase2DataIfNeeded(converters)
            val chatRepo2 = ChatRepository(db2.conversationDao(), converters)
            val taskRepo2 = TaskRepository(db2.taskDao(), converters)

            val restoredMsgs = chatRepo2.getMessagesSnapshot(convId)
            assertEquals(4, restoredMsgs.size)
            assertEquals("c1", restoredMsgs[1].toolCalls.single().id)
            assertEquals("16750", JSONObject(restoredMsgs[2].content).getString("result"))
            assertTrue(restoredMsgs[3].verificationSummary!!.isVerified)

            val doneTask = taskRepo2.getTaskSnapshot("task_done")!!
            assertEquals(AgentTaskStatus.COMPLETED, doneTask.status)
            assertEquals(1, doneTask.steps.size)

            val interruptedTask = taskRepo2.getTaskSnapshot("task_crashed")!!
            assertEquals(AgentTaskStatus.FAILED, interruptedTask.status)
            assertTrue(interruptedTask.failureReason!!.contains("restarted", ignoreCase = true))
            db2.close()
        } finally {
            dbFile.delete()
            File(dbFile.absolutePath + "-wal").delete()
            File(dbFile.absolutePath + "-shm").delete()
        }
    }

    @Test
    fun `room migration 2 to 3 upgrades existing v2 schema cleanly without losing messages or tasks`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "mig_test_${System.nanoTime()}.db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(2) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, isSamplePreview INTEGER NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, lastMessagePreview TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, executionPlanJson TEXT, taskSummaryJson TEXT, attachmentsJson TEXT, isPreviewMessage INTEGER NOT NULL, status TEXT NOT NULL, errorMessage TEXT, providerLabel TEXT, modelLabel TEXT, promptTokens INTEGER, completionTokens INTEGER, totalTokens INTEGER, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages (conversationId)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, prompt TEXT NOT NULL, status TEXT NOT NULL, isSampleData INTEGER NOT NULL, providerLabel TEXT NOT NULL, modelLabel TEXT NOT NULL, targetApp TEXT, requiredCapabilitiesJson TEXT NOT NULL, failureReason TEXT, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS task_steps (id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, stepOrder INTEGER NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, state TEXT NOT NULL, toolName TEXT, timestampLabel TEXT, FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_steps_taskId ON task_steps (taskId)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val rawDb = helper.writableDatabase
            rawDb.execSQL("INSERT INTO conversations VALUES ('c_v2','Old Chat',0,100,100,'hi')")
            rawDb.execSQL("INSERT INTO messages VALUES ('m_v2','c_v2','USER','hi',100,NULL,NULL,NULL,0,'COMPLETE',NULL,NULL,NULL,NULL,NULL,NULL)")
            rawDb.close()
            helper.close()

            val upgraded = Room.databaseBuilder(context, NexaDatabase::class.java, dbName)
                .addMigrations(NexaDatabase.MIGRATION_2_3)
                .allowMainThreadQueries()
                .build()
            runTest {
                val msgs = upgraded.conversationDao().getMessagesSnapshot("c_v2")
                assertEquals(1, msgs.size)
                assertEquals("hi", msgs[0].content)
            }
            upgraded.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    // --- All 5 Provider Adapters Tool-Calling Protocol Tests ---

    @Test
    fun `all five provider adapters serialize tool definitions and parse tool calls and results faithfully`() = runTest {
        val calcDef = CalculatorTool().definition
        val reqWithTools = ProviderRequest(
            modelId = "test-model",
            messages = listOf(
                ChatMessage("u1", "c", MessageRole.USER, "What is 2+2?", 1L),
                ChatMessage("a1", "c", MessageRole.ASSISTANT, "", 2L, toolCalls = listOf(ToolCall("call_1", "calculator", """{"expression":"2+2"}"""))),
                ChatMessage("t1", "c", MessageRole.TOOL, """{"result":"4"}""", 3L, toolCallId = "call_1", toolName = "calculator")
            ),
            systemPrompt = "Sys",
            tools = listOf(calcDef),
            allowTools = true
        )

        // 1. OpenRouter (chunked tool_calls delta)
        val orTransport = MockInterceptor { call ->
            val body = JSONObject(call.body)
            assertEquals("auto", body.getString("tool_choice"))
            assertEquals(1, body.getJSONArray("tools").length())
            assertEquals("tool", body.getJSONArray("messages").getJSONObject(3).getString("role"))
            sseResp(
                call.url,
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"or_1\",\"function\":{\"name\":\"calculator\",\"arguments\":\"{\\\"expression\\\":\"}}]}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"2+2\\\"}\"}}]}}]}\n\n" +
                    "data: [DONE]\n\n"
            )
        }
        val orResp = OpenRouterProviderAdapter(httpClient(orTransport))
            .generate(toolCapableConfig(AiProviderType.OPENROUTER, "openai/gpt-4.1"), "sk-or-v1-test", reqWithTools) as ProviderResponse.Text
        assertEquals("or_1", orResp.toolCalls.single().id)
        assertEquals("calculator", orResp.toolCalls.single().toolName)
        assertEquals("2+2", JSONObject(orResp.toolCalls.single().argumentsJson).getString("expression"))

        // 2. OpenAI & 5. CustomOpenAi
        val oaiTransport = MockInterceptor { call ->
            val body = JSONObject(call.body)
            assertTrue(body.has("tools"))
            jsonResp(call.url, 200, """{"choices":[{"message":{"content":null,"tool_calls":[{"id":"oa_1","type":"function","function":{"name":"calculator","arguments":"{\"expression\":\"5*5\"}"}}]}}]}""")
        }
        val oaResp = OpenAiProviderAdapter(httpClient(oaiTransport))
            .generate(toolCapableConfig(AiProviderType.OPENAI, "gpt-4.1"), "sk-proj-test", reqWithTools) as ProviderResponse.Text
        assertEquals("oa_1", oaResp.toolCalls.single().id)
        val custResp = CustomOpenAiProviderAdapter(httpClient(oaiTransport))
            .generate(toolCapableConfig(AiProviderType.CUSTOM, "qwen2.5", supportsTools = false, allowOverride = true), "", reqWithTools) as ProviderResponse.Text
        assertEquals("oa_1", custResp.toolCalls.single().id)

        // 3. Gemini (functionDeclarations + functionCall + functionResponse)
        val gemTransport = MockInterceptor { call ->
            val body = JSONObject(call.body)
            assertTrue(body.has("tools"))
            assertEquals("AUTO", body.getJSONObject("toolConfig").getJSONObject("functionCallingConfig").getString("mode"))
            val contents = body.getJSONArray("contents")
            assertEquals("model", contents.getJSONObject(1).getString("role"))
            assertTrue(contents.getJSONObject(1).getJSONArray("parts").getJSONObject(0).has("functionCall"))
            assertEquals("user", contents.getJSONObject(2).getString("role"))
            assertTrue(contents.getJSONObject(2).getJSONArray("parts").getJSONObject(0).has("functionResponse"))
            jsonResp(call.url, 200, """{"candidates":[{"content":{"parts":[{"functionCall":{"name":"calculator","args":{"expression":"9+1"}}}]}}]}""")
        }
        val gemResp = GeminiProviderAdapter(httpClient(gemTransport))
            .generate(toolCapableConfig(AiProviderType.GEMINI, "gemini-2.5-flash"), "AIzaTest1234567890", reqWithTools) as ProviderResponse.Text
        assertEquals("calculator", gemResp.toolCalls.single().toolName)
        assertEquals("9+1", JSONObject(gemResp.toolCalls.single().argumentsJson).getString("expression"))

        // 4. Anthropic (tools input_schema + tool_use SSE + tool_result blocks)
        val antTransport = MockInterceptor { call ->
            val body = JSONObject(call.body)
            assertEquals("auto", body.getJSONObject("tool_choice").getString("type"))
            assertTrue(body.getJSONArray("tools").getJSONObject(0).has("input_schema"))
            val msgs = body.getJSONArray("messages")
            assertEquals("tool_use", msgs.getJSONObject(1).getJSONArray("content").getJSONObject(0).getString("type"))
            assertEquals("tool_result", msgs.getJSONObject(2).getJSONArray("content").getJSONObject(0).getString("type"))
            sseResp(
                call.url,
                "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"calculator\",\"input\":{}}}\n\n" +
                    "event: content_block_delta\n" +
                    "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"expression\\\":\\\"7*8\\\"}\"}}\n\n" +
                    "event: content_block_stop\n" +
                    "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                    "event: message_stop\n" +
                    "data: {\"type\":\"message_stop\"}\n\n"
            )
        }
        val antResp = AnthropicProviderAdapter(httpClient(antTransport))
            .generate(toolCapableConfig(AiProviderType.ANTHROPIC, "claude-sonnet-4-0"), "sk-ant-test123456", reqWithTools) as ProviderResponse.Text
        assertEquals("toolu_1", antResp.toolCalls.single().id)
        assertEquals("calculator", antResp.toolCalls.single().toolName)
        assertEquals("7*8", JSONObject(antResp.toolCalls.single().argumentsJson).getString("expression"))
    }

    @Test
    fun `manual or unverified models default to no tool calling unless explicitly overridden`() {
        val manualModel = ProviderConfig(
            providerType = AiProviderType.CUSTOM,
            hasApiKey = false,
            maskedApiKey = "",
            baseUrl = "http://localhost:11434/v1",
            selectedModelId = "custom-llama",
            manualModelId = "custom-llama",
            isDefault = true,
            availableModels = listOf(AiModelInfo("custom-llama", "custom-llama", supportsTools = false, isManualEntry = true)),
            allowManualToolsOverride = false
        )
        assertFalse(manualModel.effectiveSupportsTools)
        assertTrue(manualModel.copy(allowManualToolsOverride = true).effectiveSupportsTools)

        val noToolsReq = OpenAiCompatibleJson.buildChatRequestBody(
            ProviderRequest("custom-llama", listOf(ChatMessage("1", "c", MessageRole.USER, "hi", 1L)), "sys", tools = listOf(CalculatorTool().definition), allowTools = false),
            stream = true,
            supportsVision = false
        )
        assertFalse(JSONObject(noToolsReq).has("tools"))
    }

    @Test
    fun `inline ApprovalGateCard renders details and invokes approve and reject actions`() {
        var approved = 0
        var rejected = 0
        val req = ToolApprovalRequest(
            approvalId = "apr_ui",
            taskId = "task_ui",
            callId = "call_ui",
            toolId = "comms.test",
            toolName = "sensitive_test",
            riskCategory = ToolRiskCategory.SENSITIVE,
            intentSummary = "Send test confirmation check",
            targetDataSummary = "Demo payload",
            isReversible = false,
            approvalEffect = "Permits one-time execution.",
            argumentsPreview = """{"target":"demo"}"""
        )

        composeRule.setContent {
            NexaTheme {
                ApprovalGateCard(
                    request = req,
                    onApprove = { approved++ },
                    onReject = { rejected++ }
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("approval_gate_card").assertIsDisplayed()
        composeRule.onNodeWithText("Send test confirmation check").assertIsDisplayed()
        composeRule.onNodeWithTag("approve_tool_button").performClick()
        composeRule.onNodeWithTag("reject_tool_button").performClick()
        assertEquals(1, approved)
        assertEquals(1, rejected)
    }

    // --- Helper Tools for Edge/Gate Tests ---

    private fun slowTool(onSecondCall: () -> Unit): ExecutableTool {
        var count = 0
        return object : ExecutableTool {
            override val requiredCapability: DeviceCapabilityType? = null
            override val definition = ToolDefinition(
                id = "test.slow",
                name = "slow_tool",
                description = "Slow test tool for cancellation",
                parameters = listOf(ToolParameter("step", ToolParameterType.STRING, "Step label")),
                riskCategory = ToolRiskCategory.READ_ONLY
            )
            override fun validateArguments(args: JSONObject): String? = null
            override fun describeAction(args: JSONObject): String = "Run slow tool"
            override suspend fun execute(args: JSONObject): ToolExecutionOutput {
                count++
                if (count >= 2) onSecondCall()
                withContext(Dispatchers.Default) {
                    delay(5_000)
                }
                return ToolExecutionOutput(JSONObject().put("ok", true), "Done")
            }
        }
    }

    private fun fakeRiskTool(
        category: ToolRiskCategory,
        onExecute: suspend () -> ToolExecutionOutput = {
            ToolExecutionOutput(JSONObject().put("ok", true), "Executed")
        }
    ): ExecutableTool = object : ExecutableTool {
        override val requiredCapability: DeviceCapabilityType? = null
        override val definition = ToolDefinition(
            id = "test.${category.name.lowercase()}",
            name = "sensitive_test",
            description = "Test tool for $category safety gate",
            parameters = listOf(ToolParameter("target", ToolParameterType.STRING, "Target", required = false)),
            riskCategory = category
        )
        override fun validateArguments(args: JSONObject): String? = null
        override fun describeAction(args: JSONObject): String = "Run sensitive test tool"
        override suspend fun execute(args: JSONObject): ToolExecutionOutput = onExecute()
    }
}
