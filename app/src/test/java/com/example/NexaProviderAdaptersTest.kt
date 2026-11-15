package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.NexaDatabase
import com.example.data.local.NexaPreferencesDataStore
import com.example.data.local.NexaTypeConverters
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.SettingsRepository
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.chat.AndroidAttachmentProcessor
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ChatMessage
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.domain.provider.AiProviderAdapter
import com.example.domain.provider.AiProviderRegistry
import com.example.domain.provider.AnthropicProviderAdapter
import com.example.domain.provider.ConnectionTestResult
import com.example.domain.provider.CustomOpenAiProviderAdapter
import com.example.domain.provider.GeminiProviderAdapter
import com.example.domain.provider.NetworkAvailabilityChecker
import com.example.domain.provider.NexaHttpClient
import com.example.domain.provider.OpenAiProviderAdapter
import com.example.domain.provider.OpenRouterProviderAdapter
import com.example.domain.provider.ProviderError
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderResponse
import com.example.domain.provider.ProviderStreamEvent
import com.example.domain.security.CredentialCipher
import com.example.ui.chat.ChatViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaProviderAdaptersTest {

    private data class RecordedHttpCall(
        val method: String,
        val url: String,
        val headers: Map<String, String>,
        val body: String
    )

    private class MockTransport(
        private val handler: (RecordedHttpCall) -> Response
    ) : Interceptor {
        val recorded = mutableListOf<RecordedHttpCall>()

        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val buffer = Buffer()
            req.body?.writeTo(buffer)
            val bodyStr = buffer.readUtf8()
            val headerMap = req.headers.names().associateWith { req.header(it).orEmpty() }
            val rec = RecordedHttpCall(
                method = req.method,
                url = req.url.toString(),
                headers = headerMap,
                body = bodyStr
            )
            recorded.add(rec)
            return handler(rec)
        }
    }

    private fun jsonResponse(
        url: String,
        code: Int,
        body: String,
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("HTTP $code")
            .body(body.toResponseBody("application/json; charset=utf-8".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun sseResponse(url: String, ssePayload: String): Response {
        return Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .header("Content-Type", "text/event-stream; charset=utf-8")
            .body(ssePayload.toResponseBody("text/event-stream; charset=utf-8".toMediaType()))
            .build()
    }

    private fun clientWithTransport(
        transport: MockTransport,
        online: Boolean = true
    ): NexaHttpClient {
        val okHttp = OkHttpClient.Builder().addInterceptor(transport).build()
        val checker = object : NetworkAvailabilityChecker {
            override fun isNetworkAvailable(): Boolean = online
        }
        return NexaHttpClient(okHttpClient = okHttp, networkChecker = checker)
    }

    @Test
    fun `openrouter verifies key loads models and streams SSE deltas with usage`() = runTest {
        val transport = MockTransport { call ->
            when {
                call.url.endsWith("/key") -> jsonResponse(call.url, 200, """{"data":{"label":"test"}}""")
                call.url.endsWith("/models") -> jsonResponse(
                    call.url,
                    200,
                    """{"data":[{"id":"anthropic/claude-sonnet-4","name":"Claude Sonnet 4","context_length":200000,"architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools"]}]}"""
                )
                else -> sseResponse(
                    call.url,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Hello \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"OpenRouter!\"}}]}\n\n" +
                        "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4,\"total_tokens\":16}}\n\n" +
                        "data: [DONE]\n\n"
                )
            }
        }
        val adapter: AiProviderAdapter = OpenRouterProviderAdapter(clientWithTransport(transport))
        val config = baseConfig(AiProviderType.OPENROUTER, "anthropic/claude-sonnet-4")

        val testResult = adapter.testConfiguration(config, "sk-or-v1-testsecretkey")
        assertTrue(testResult is ConnectionTestResult.Success)
        val success = testResult as ConnectionTestResult.Success
        assertEquals(1, success.fetchedModels.size)
        assertTrue(success.fetchedModels.first().supportsVision)
        assertEquals("Bearer sk-or-v1-testsecretkey", transport.recorded[0].headers["Authorization"])
        assertEquals("Nexa Android", transport.recorded[0].headers["X-Title"])

        val visionModel = success.fetchedModels.first()
        val events = adapter.streamChat(
            config,
            "sk-or-v1-testsecretkey",
            ProviderRequest(
                modelId = visionModel.id,
                messages = listOf(userMsg("Inspect attached file and screenshot")),
                systemPrompt = "You are Nexa.",
                attachments = listOf(
                    AttachmentPayload("a1", "notes.txt", "text/plain", "content://1", textContent = "line 1"),
                    AttachmentPayload("a2", "img.jpg", "image/jpeg", "content://2", imageBase64 = "AQID")
                ),
                modelInfo = visionModel
            )
        ).toList()

        val deltas = events.filterIsInstance<ProviderStreamEvent.Delta>().joinToString("") { it.text }
        assertEquals("Hello OpenRouter!", deltas)
        val completed = events.filterIsInstance<ProviderStreamEvent.Completed>().single()
        assertEquals(16, completed.usage?.totalTokens)

        val chatReqBody = JSONObject(transport.recorded.last().body)
        val userContent = chatReqBody.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertTrue(userContent.getJSONObject(0).getString("text").contains("line 1"))
        assertEquals("image_url", userContent.getJSONObject(1).getString("type"))
    }

    @Test
    fun `gemini uses x-goog-api-key parses v1beta models and streams generateContent SSE`() = runTest {
        val transport = MockTransport { call ->
            if (call.method == "GET") {
                jsonResponse(
                    call.url,
                    200,
                    """{"models":[{"name":"models/gemini-2.5-flash","displayName":"Gemini 2.5 Flash","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent"]}]}"""
                )
            } else {
                sseResponse(
                    call.url,
                    "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Gemini \"}]}}]}\n\n" +
                        "data: {\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"streaming\"}]}}],\"usageMetadata\":{\"promptTokenCount\":10,\"candidatesTokenCount\":5,\"totalTokenCount\":15}}\n\n"
                )
            }
        }
        val adapter = GeminiProviderAdapter(clientWithTransport(transport))
        val config = baseConfig(AiProviderType.GEMINI, "gemini-2.5-flash")

        val testResult = adapter.testConfiguration(config, "AIzaSyMockGeminiKey123")
        assertTrue(testResult is ConnectionTestResult.Success)
        assertEquals("gemini-2.5-flash", (testResult as ConnectionTestResult.Success).fetchedModels.first().id)
        assertEquals("AIzaSyMockGeminiKey123", transport.recorded[0].headers["x-goog-api-key"])

        val response = adapter.generate(
            config,
            "AIzaSyMockGeminiKey123",
            ProviderRequest(
                modelId = "gemini-2.5-flash",
                messages = listOf(userMsg("Hi"), assistantMsg("Hello"), userMsg("Next?")),
                systemPrompt = "System instruction"
            )
        )
        assertTrue(response is ProviderResponse.Text)
        assertEquals("Gemini streaming", (response as ProviderResponse.Text).content)
        assertEquals(15, response.usage?.totalTokens)

        val postCall = transport.recorded.last()
        assertTrue(postCall.url.contains("/v1beta/models/gemini-2.5-flash:streamGenerateContent?alt=sse"))
        val reqJson = JSONObject(postCall.body)
        assertTrue(reqJson.has("systemInstruction"))
        assertEquals("model", reqJson.getJSONArray("contents").getJSONObject(1).getString("role"))
    }

    @Test
    fun `openai omits temperature for reasoning models and filters chat models`() = runTest {
        val transport = MockTransport { call ->
            if (call.method == "GET") {
                jsonResponse(
                    call.url,
                    200,
                    """{"data":[{"id":"gpt-4.1"},{"id":"o4-mini"},{"id":"whisper-1"}]}"""
                )
            } else {
                sseResponse(
                    call.url,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Reasoned\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
            }
        }
        val adapter = OpenAiProviderAdapter(clientWithTransport(transport))
        val config = baseConfig(AiProviderType.OPENAI, "o4-mini")

        val testResult = adapter.testConfiguration(config, "sk-proj-mockopenaikey")
        assertTrue(testResult is ConnectionTestResult.Success)
        val modelIds = (testResult as ConnectionTestResult.Success).fetchedModels.map { it.id }
        assertTrue("gpt-4.1" in modelIds)
        assertTrue("o4-mini" in modelIds)
        assertFalse("whisper-1" in modelIds)

        val response = adapter.generate(
            config,
            "sk-proj-mockopenaikey",
            ProviderRequest(
                modelId = "o4-mini",
                messages = listOf(userMsg("Solve 2+2")),
                systemPrompt = "Concise"
            )
        )
        assertEquals("Reasoned", (response as ProviderResponse.Text).content)
        val bodyJson = JSONObject(transport.recorded.last().body)
        assertFalse(bodyJson.has("temperature"))
    }

    @Test
    fun `anthropic sends required headers and parses content_block_delta and message_delta`() = runTest {
        val transport = MockTransport { call ->
            if (call.method == "GET") {
                jsonResponse(
                    call.url,
                    200,
                    """{"data":[{"id":"claude-sonnet-4-0","display_name":"Claude Sonnet 4"}]}"""
                )
            } else {
                sseResponse(
                    call.url,
                    "event: message_start\n" +
                        "data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":21}}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Claude \"}}\n\n" +
                        "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"reply\"}}\n\n" +
                        "event: message_delta\n" +
                        "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":9}}\n\n" +
                        "event: message_stop\n" +
                        "data: {\"type\":\"message_stop\"}\n\n"
                )
            }
        }
        val adapter = AnthropicProviderAdapter(clientWithTransport(transport))
        val config = baseConfig(AiProviderType.ANTHROPIC, "claude-sonnet-4-0")

        val testResult = adapter.testConfiguration(config, "sk-ant-mockkey123456")
        assertTrue(testResult is ConnectionTestResult.Success)
        assertEquals("2023-06-01", transport.recorded[0].headers["anthropic-version"])
        assertEquals("sk-ant-mockkey123456", transport.recorded[0].headers["x-api-key"])

        val response = adapter.generate(
            config,
            "sk-ant-mockkey123456",
            ProviderRequest(
                modelId = "claude-sonnet-4-0",
                messages = listOf(userMsg("Hello Claude")),
                systemPrompt = "You are helpful"
            )
        )
        assertTrue(response is ProviderResponse.Text)
        val ok = response as ProviderResponse.Text
        assertEquals("Claude reply", ok.content)
        assertEquals(21, ok.usage?.promptTokens)
        assertEquals(9, ok.usage?.completionTokens)
        assertEquals(30, ok.usage?.totalTokens)

        val bodyJson = JSONObject(transport.recorded.last().body)
        assertEquals("You are helpful", bodyJson.getString("system"))
    }

    @Test
    fun `custom endpoint supports non-streaming json fallback and rejects invalid http remote url`() = runTest {
        val transport = MockTransport { call ->
            if (call.url.endsWith("/models")) {
                jsonResponse(call.url, 404, """{"error":{"message":"not found"}}""")
            } else {
                jsonResponse(
                    call.url,
                    200,
                    """{"choices":[{"message":{"content":"Local Ollama OK"}}],"usage":{"total_tokens":11}}"""
                )
            }
        }
        val adapter = CustomOpenAiProviderAdapter(clientWithTransport(transport))

        val remoteHttp = baseConfig(AiProviderType.CUSTOM, "llama3", baseUrl = "http://public-server.example.com/v1")
        val badUrlTest = adapter.testConfiguration(remoteHttp, "")
        assertTrue(badUrlTest is ConnectionTestResult.Failure)
        assertTrue((badUrlTest as ConnectionTestResult.Failure).error is ProviderError.InvalidEndpoint)

        val localHttp = baseConfig(AiProviderType.CUSTOM, "llama3", baseUrl = "http://localhost:11434/v1")
        val fallbackProbe = adapter.testConfiguration(localHttp, "")
        assertTrue(fallbackProbe is ConnectionTestResult.Success)

        val resp = adapter.generate(
            localHttp,
            "",
            ProviderRequest("llama3", listOf(userMsg("Ping")), "System")
        )
        assertEquals("Local Ollama OK", (resp as ProviderResponse.Text).content)
    }

    @Test
    fun `adapters classify 401 429 503 timeout malformed response and offline states cleanly`() = runTest {
        val error401 = OpenAiProviderAdapter(
            clientWithTransport(MockTransport { jsonResponse(it.url, 401, """{"error":{"message":"Bad key sk-proj-secret123456"}}""") })
        ).testConfiguration(baseConfig(AiProviderType.OPENAI, "gpt-4.1"), "sk-proj-secret123456")
        assertTrue((error401 as ConnectionTestResult.Failure).error is ProviderError.InvalidApiKey)
        assertFalse(error401.error.userMessage.contains("secret123456"))

        val error429 = OpenRouterProviderAdapter(
            clientWithTransport(MockTransport { jsonResponse(it.url, 429, """{"error":{"message":"Too many"}}""", mapOf("Retry-After" to "15")) })
        ).generate(baseConfig(AiProviderType.OPENROUTER, "openai/gpt-4.1"), "sk-or-v1-1234567890", ProviderRequest("openai/gpt-4.1", listOf(userMsg("hi")), "sys"))
        val rateErr = (error429 as ProviderResponse.Error).error as ProviderError.RateLimitExceeded
        assertEquals(15, rateErr.retryAfterSeconds)

        val errorTimeout = AnthropicProviderAdapter(
            clientWithTransport(MockTransport { throw SocketTimeoutException("read timed out") })
        ).generate(baseConfig(AiProviderType.ANTHROPIC, "claude-sonnet-4-0"), "sk-ant-1234567890", ProviderRequest("claude-sonnet-4-0", listOf(userMsg("hi")), "sys"))
        assertTrue((errorTimeout as ProviderResponse.Error).error is ProviderError.Timeout)

        val errorOffline = OpenAiProviderAdapter(
            clientWithTransport(MockTransport { error("should not execute") }, online = false)
        ).generate(baseConfig(AiProviderType.OPENAI, "gpt-4.1"), "sk-proj-1234567890", ProviderRequest("gpt-4.1", listOf(userMsg("hi")), "sys"))
        assertTrue((errorOffline as ProviderResponse.Error).error is ProviderError.NetworkUnavailable)

        val errorMalformed = CustomOpenAiProviderAdapter(
            clientWithTransport(MockTransport { jsonResponse(it.url, 200, "<html>not json</html>") })
        ).testConfiguration(baseConfig(AiProviderType.CUSTOM, "model", baseUrl = "https://custom.example.com/v1"), "")
        assertTrue((errorMalformed as ConnectionTestResult.Failure).error is ProviderError.MalformedResponse)

        val errorModel404 = OpenAiProviderAdapter(
            clientWithTransport(MockTransport { jsonResponse(it.url, 404, """{"error":{"message":"The model 'bad-model' does not exist"}}""") })
        ).generate(baseConfig(AiProviderType.OPENAI, "bad-model"), "sk-proj-1234567890", ProviderRequest("bad-model", listOf(userMsg("hi")), "sys"))
        assertTrue((errorModel404 as ProviderResponse.Error).error is ProviderError.UnsupportedModel)
    }

    @Test
    fun `streaming cancellation propagates CancellationException cleanly`() = runTest {
        val adapter = OpenRouterProviderAdapter(
            clientWithTransport(
                MockTransport { call ->
                    sseResponse(
                        call.url,
                        "data: {\"choices\":[{\"delta\":{\"content\":\"First \"}}]}\n\n" +
                            "data: {\"choices\":[{\"delta\":{\"content\":\"Second\"}}]}\n\n"
                    )
                }
            )
        )
        val firstChunkArrived = CompletableDeferred<Unit>()
        var sawCancellation = false
        val job = launch {
            try {
                adapter.streamChat(
                    baseConfig(AiProviderType.OPENROUTER, "anthropic/claude-sonnet-4"),
                    "sk-or-v1-testsecretkey",
                    ProviderRequest("anthropic/claude-sonnet-4", listOf(userMsg("Stream")), "sys")
                ).collect { event ->
                    if (event is ProviderStreamEvent.Delta) {
                        firstChunkArrived.complete(Unit)
                        kotlinx.coroutines.delay(5_000)
                    }
                }
            } catch (_: CancellationException) {
                sawCancellation = true
            }
        }
        firstChunkArrived.await()
        job.cancel()
        job.join()
        assertTrue(sawCancellation)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `chat viewmodel sends multi-turn context streams without duplicates and retries stopped reply cleanly`() = runTest {
        val mainDispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(mainDispatcher)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NexaDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val converters = NexaTypeConverters()
        val fakeCipher = object : CredentialCipher {
            override fun encrypt(plainText: String): String = "enc:$plainText"
            override fun decrypt(cipherPayload: String): String = cipherPayload.removePrefix("enc:")
        }
        val prefs = NexaPreferencesDataStore(context, fakeCipher)
        var turnNumber = 0
        val transport = MockTransport { call ->
            turnNumber += 1
            when (turnNumber) {
                1 -> sseResponse(
                    call.url,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"An API \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"connects apps.\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
                2 -> jsonResponse(call.url, 503, """{"error":{"message":"Upstream temporary overload"}}""")
                else -> sseResponse(
                    call.url,
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Use HTTP GET \"}}]}\n\n" +
                        "data: {\"choices\":[{\"delta\":{\"content\":\"for weather.\"}}]}\n\n" +
                        "data: [DONE]\n\n"
                )
            }
        }
        val httpClient = clientWithTransport(transport)
        val connectionsRepo = ConnectionsRepository(
            prefs = prefs,
            providerRegistry = AiProviderRegistry(httpClient = httpClient),
            capabilityInspector = AndroidCapabilityInspector(context)
        )
        val chatRepo = ChatRepository(db.conversationDao(), converters)
        val settingsRepo = SettingsRepository(prefs)
        val viewModel = ChatViewModel(
            chatRepository = chatRepo,
            connectionsRepository = connectionsRepo,
            settingsRepository = settingsRepo,
            toolRegistry = DefaultToolRegistry(),
            systemPromptBuilder = SystemPromptBuilder(),
            contextBuilder = ConversationContextBuilder(),
            attachmentProcessor = AndroidAttachmentProcessor(context)
        )

        try {
            prefs.saveProviderConfig(
                type = AiProviderType.CUSTOM,
                rawApiKey = null,
                baseUrl = "http://localhost:11434/v1",
                selectedModelId = "qwen2.5:14b",
                manualModelId = "qwen2.5:14b",
                setAsDefault = true
            )
            // Keep uiState active so stateIn collects DataStore and Room
            val stateCollector = launch { viewModel.uiState.collect { } }
            try {
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (viewModel.uiState.value.defaultProvider?.selectedModelId != "qwen2.5:14b") {
                            kotlinx.coroutines.delay(20)
                        }
                    }
                }

                // Turn 1: send prompt and wait for streamed completion
                viewModel.sendMessage("Explain what an API is.")
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (true) {
                            val msgs = viewModel.uiState.value.messages
                            if (msgs.size == 2 && msgs[1].status == MessageStatus.COMPLETE) break
                            kotlinx.coroutines.delay(20)
                        }
                    }
                }
                val convId = viewModel.uiState.value.activeConversation!!.id
                val afterTurn1 = chatRepo.getMessagesSnapshot(convId)
                assertEquals(2, afterTurn1.size)
                assertEquals("An API connects apps.", afterTurn1[1].content)

                // Turn 2: fails with HTTP 503
                viewModel.sendMessage("Give me a practical example.")
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (true) {
                            val msgs = viewModel.uiState.value.messages
                            if (msgs.size == 4 && msgs[3].status == MessageStatus.FAILED) break
                            kotlinx.coroutines.delay(20)
                        }
                    }
                }
                val failedAssistant = viewModel.uiState.value.messages[3]
                assertEquals(MessageStatus.FAILED, failedAssistant.status)

                // Retry failed message without duplicating user prompt
                viewModel.retryMessage(failedAssistant)
                withContext(Dispatchers.Default) {
                    withTimeout(5_000) {
                        while (true) {
                            val msgs = viewModel.uiState.value.messages
                            if (msgs.size == 4 && msgs[3].status == MessageStatus.COMPLETE) break
                            kotlinx.coroutines.delay(20)
                        }
                    }
                }

                val finalHistory = chatRepo.getMessagesSnapshot(convId)
                assertEquals(4, finalHistory.size)
                assertEquals(1, finalHistory.count { it.content == "Give me a practical example." })
                assertEquals("Use HTTP GET for weather.", finalHistory[3].content)

                // Verify multi-turn payload included system + turn1 user + turn1 assistant + turn2 user
                val thirdReqBody = JSONObject(transport.recorded.last().body)
                val sentMessages = thirdReqBody.getJSONArray("messages")
                assertEquals(4, sentMessages.length())
                assertEquals("system", sentMessages.getJSONObject(0).getString("role"))
                assertEquals("user", sentMessages.getJSONObject(1).getString("role"))
                assertEquals("Explain what an API is.", sentMessages.getJSONObject(1).getString("content"))
                assertEquals("assistant", sentMessages.getJSONObject(2).getString("role"))
                assertEquals("An API connects apps.", sentMessages.getJSONObject(2).getString("content"))
                assertEquals("user", sentMessages.getJSONObject(3).getString("role"))
                assertEquals("Give me a practical example.", sentMessages.getJSONObject(3).getString("content"))
            } finally {
                stateCollector.cancel()
            }
        } finally {
            prefs.clearProviderKey(AiProviderType.CUSTOM)
            db.close()
            Dispatchers.resetMain()
        }
    }

    private fun baseConfig(
        type: AiProviderType,
        modelId: String,
        baseUrl: String = type.defaultBaseUrl
    ) = ProviderConfig(
        providerType = type,
        hasApiKey = true,
        maskedApiKey = "••••",
        baseUrl = baseUrl,
        selectedModelId = modelId,
        isDefault = true,
        availableModels = listOf(AiModelInfo(modelId, modelId))
    )

    private fun userMsg(text: String) = ChatMessage(
        id = "u1",
        conversationId = "c1",
        role = MessageRole.USER,
        content = text,
        createdAtMillis = 1L
    )

    private fun assistantMsg(text: String) = ChatMessage(
        id = "a1",
        conversationId = "c1",
        role = MessageRole.ASSISTANT,
        content = text,
        createdAtMillis = 2L
    )
}
