package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.core.app.ApplicationProvider
import com.example.android.accessibility.AccessibilityInteractionController
import com.example.android.accessibility.AccessibilityLifecycleSnapshot
import com.example.android.accessibility.AccessibilityWindowChangeSignal
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.InteractionBackend
import com.example.android.accessibility.InteractionDispatchResult
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.accessibility.ScreenObservationBridge
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.media.MediaProjectionSessionManager
import com.example.android.media.MediaProjectionSessionSnapshot
import com.example.android.media.ScreenshotCaptureBackend
import com.example.android.runtime.AndroidActionRuntime
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.runtime.PermissionInspectionSnapshot
import com.example.data.local.NexaPreferencesDataStore
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.ComputerUseTools
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.ProviderStreamCaller
import com.example.domain.agent.ToolAvailabilityStatus
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.agent.ToolRecoveryAction
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentExecutionLimits
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppLaunchStatus
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.AppSettings
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.MediaProjectionConsentState
import com.example.domain.model.MessageRole
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScreenshotCaptureOutcome
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolDispatchDiagnostic
import com.example.domain.model.ToolExecutionStatus
import com.example.domain.provider.NetworkAvailabilityChecker
import com.example.domain.provider.NexaHttpClient
import com.example.domain.provider.OpenAiCompatibleJson
import com.example.domain.provider.OpenRouterProviderAdapter
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderStreamEvent
import com.example.domain.security.CredentialCipher
import com.example.ui.chat.ChatEmptyWelcome
import com.example.ui.chat.ToolsCatalogSheet
import com.example.ui.connections.CapabilitiesTab
import com.example.ui.theme.NexaTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaToolSyncRepairTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDown() {
        NexaAccessibilityService.attachInstanceForTesting(null)
        NexaAccessibilityService.updateTestStateForTesting(AccessibilityLifecycleSnapshot())
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }

    // --- Test Fixtures ---

    private class FakePermissionManager(
        context: Context,
        var accState: AccessibilityRuntimeState = AccessibilityRuntimeState.SERVICE_DISABLED,
        var projState: MediaProjectionConsentState = MediaProjectionConsentState.NOT_GRANTED,
        var isLocked: Boolean = false
    ) : AndroidPermissionManager(context) {
        override fun inspect(): PermissionInspectionSnapshot = PermissionInspectionSnapshot(
            accessibilityState = accState,
            accessibilityReason = "Accessibility service state: ${accState.label}",
            isServiceEnabledInSettings = accState == AccessibilityRuntimeState.SERVICE_ENABLED ||
                accState == AccessibilityRuntimeState.ENABLED_NOT_CONNECTED,
            isServiceConnected = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            isServiceInterrupted = accState == AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE,
            isDeviceLocked = isLocked,
            canPerformGestures = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            canRetrieveWindowContent = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            canPostNotifications = true,
            hasBrowserHandler = true,
            launchableAppDiscoveryAvailable = true,
            mediaProjectionState = projState,
            mediaProjectionReason = "Screen capture permission state: ${projState.label}"
        )

        override fun isAccessibilityReady(): Boolean = accState == AccessibilityRuntimeState.SERVICE_ENABLED
        override fun isMediaProjectionActive(): Boolean = projState == MediaProjectionConsentState.GRANTED_ACTIVE
    }

    private class FakeScreenshotBackend(
        initialSnapshot: MediaProjectionSessionSnapshot = MediaProjectionSessionSnapshot()
    ) : ScreenshotCaptureBackend {
        private val _state = MutableStateFlow(initialSnapshot)
        override val state: StateFlow<MediaProjectionSessionSnapshot> = _state.asStateFlow()

        var nextOutcome: ScreenshotCaptureOutcome? = null

        fun setSnapshot(snapshot: MediaProjectionSessionSnapshot) {
            _state.value = snapshot
        }

        override fun isMediaProjectionSupported(): Boolean =
            _state.value.consentState != MediaProjectionConsentState.UNSUPPORTED

        override fun createScreenCaptureIntent(): Intent = Intent("com.example.TEST_PROJECTION_CONSENT")

        override fun onConsentResult(resultCode: Int, data: Intent?): Boolean = false

        override fun stopSession(revokedBySystem: Boolean) {
            _state.value = MediaProjectionSessionSnapshot(
                consentState = if (revokedBySystem) MediaProjectionConsentState.REVOKED else MediaProjectionConsentState.NOT_GRANTED,
                isSessionActive = false
            )
        }

        override suspend fun captureScreenshot(includeBase64: Boolean): ScreenshotCaptureOutcome {
            if (_state.value.consentState != MediaProjectionConsentState.GRANTED_ACTIVE || !_state.value.isSessionActive) {
                return ScreenshotCaptureOutcome(
                    success = false,
                    message = "Screen capture consent is required before calling take_screenshot.",
                    errorCode = "MEDIA_PROJECTION_CONSENT_REQUIRED",
                    requiresConsent = true
                )
            }
            nextOutcome?.let { return it }
            val context = ApplicationProvider.getApplicationContext<Context>()
            val outDir = File(context.cacheDir, "screenshots").apply { mkdirs() }
            val outFile = File(outDir, "test_screen_${System.currentTimeMillis()}.png")
            outFile.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            return ScreenshotCaptureOutcome(
                success = true,
                filePath = outFile.absolutePath,
                width = 1080,
                height = 2400,
                mimeType = "image/png",
                timestampMillis = System.currentTimeMillis(),
                base64Data = if (includeBase64) "dGVzdC1pbWFnZS1qcGVn" else null,
                message = "Captured 1080x2400 screenshot to ${outFile.name}."
            )
        }
    }

    private fun buildRuntime(
        context: Context,
        perm: FakePermissionManager,
        screenshotBackend: ScreenshotCaptureBackend = FakeScreenshotBackend()
    ): AndroidActionRuntime {
        val observer = AndroidScreenObserver(
            object : ScreenObservationBridge {
                override fun currentObservation() = ScreenObservation(
                    timestampMillis = 1_750_000_000_000L,
                    activePackageName = "com.android.chrome",
                    windowTitle = "Chrome",
                    nodes = emptyList(),
                    hierarchySignature = "sig_chrome"
                )

                override suspend fun awaitWindowChange(
                    afterSequence: Long,
                    timeoutMillis: Long
                ): AccessibilityWindowChangeSignal? = null

                override fun latestEventSequence(): Long = 1L
            }
        )
        val verifier = AndroidActionVerifier(observer)
        val discovery = AndroidAppDiscovery(context)
        val launcher = AndroidAppLauncher(context, discovery, perm, verifier, activityStarter = {})
        val interactions = AccessibilityInteractionController(
            object : InteractionBackend {
                override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                    InteractionDispatchResult(true, "Tapped element")
                override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                    InteractionDispatchResult(true, "Long pressed element")
                override suspend fun tapCoordinates(x: Float, y: Float) =
                    InteractionDispatchResult(true, "Tapped coordinates")
                override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) =
                    InteractionDispatchResult(true, "Typed text")
                override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) =
                    InteractionDispatchResult(true, "Scrolled")
                override suspend fun pressBack() = InteractionDispatchResult(true, "Pressed back")
                override suspend fun pressHome() = InteractionDispatchResult(true, "Pressed home")
                override suspend fun pressEnter() = InteractionDispatchResult(true, "Pressed enter")
            }
        )
        return AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = launcher,
            screenObserver = observer,
            interactionController = interactions,
            actionVerifier = verifier,
            screenshotBackend = screenshotBackend
        )
    }

    private data class CapturedHttp(val url: String, val body: String)

    private class MockTransport(
        private val responder: (CapturedHttp) -> Response
    ) : Interceptor {
        val requests = mutableListOf<CapturedHttp>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val buffer = Buffer()
            req.body?.writeTo(buffer)
            val captured = CapturedHttp(req.url.toString(), buffer.readUtf8())
            requests.add(captured)
            return responder(captured)
        }
    }

    private fun sseOkResponse(url: String, payload: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(payload.toResponseBody("text/event-stream; charset=utf-8".toMediaType()))
            .build()

    private fun jsonOkResponse(url: String, json: String): Response =
        Response.Builder()
            .request(Request.Builder().url(url).build())
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(json.toResponseBody("application/json; charset=utf-8".toMediaType()))
            .build()

    // --- Suite 1: Registry & Capability Synchronization ---

    @Test
    fun `unset preference toggles preserve default enabled capabilities for APP_LAUNCHING and SCREENSHOTS`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = NexaPreferencesDataStore(
            context,
            object : CredentialCipher {
                override fun encrypt(plainText: String): String = plainText
                override fun decrypt(cipherPayload: String): String = cipherPayload
            }
        )
        val storedToggles = prefs.capabilityToggles.first()

        val inspector = AndroidCapabilityInspector(context)
        val caps = inspector.inspectAll(storedToggles)

        val appLaunch = caps.first { it.type == DeviceCapabilityType.APP_LAUNCHING }
        val screenshots = caps.first { it.type == DeviceCapabilityType.SCREENSHOTS }
        assertTrue("APP_LAUNCHING must default to userEnabled=true", appLaunch.userEnabled)
        assertEquals(CapabilityStatus.AVAILABLE, appLaunch.status)
        assertTrue("SCREENSHOTS must default to userEnabled=true", screenshots.userEnabled)
        assertEquals(CapabilityStatus.PERMISSION_REQUIRED, screenshots.status)
    }

    @Test
    fun `unified registry merges utilities, app tools, UI tools, and take_screenshot cleanly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val perm = FakePermissionManager(context)
        val runtime = buildRuntime(context, perm)
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)

        val caps = AndroidCapabilityInspector(context, perm).inspectAll(emptyMap())
        val registered = registry.getAllRegisteredTools(caps)
        val registeredNames = registered.map { it.definition.name }.toSet()

        val expectedExecutable = setOf(
            "calculator",
            "get_current_datetime",
            "get_device_info",
            "list_installed_apps",
            "find_app",
            "launch_app",
            "open_url",
            "play_on_youtube",
            "observe_screen",
            "wait_for_screen_change",
            "tap_element",
            "long_press_element",
            "tap_coordinates",
            "type_text",
            "scroll_screen",
            "press_back",
            "press_home",
            "press_enter",
            "take_screenshot"
        )
        assertEquals(expectedExecutable, registeredNames)

        val legacyIds = registry.getAllTools().map { it.id }.toSet()
        assertTrue(legacyIds.containsAll(registered.map { it.definition.id }))
        assertTrue(legacyIds.contains("comms.draft_sms"))
        assertTrue(legacyIds.contains("media.pick_attachment"))
    }

    @Test
    fun `tool availability reacts dynamically to Accessibility and MediaProjection states`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val perm = FakePermissionManager(
            context,
            accState = AccessibilityRuntimeState.SERVICE_DISABLED,
            projState = MediaProjectionConsentState.NOT_GRANTED
        )
        val screenshotBackend = FakeScreenshotBackend()
        val runtime = buildRuntime(context, perm, screenshotBackend)
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val inspector = AndroidCapabilityInspector(context, perm)

        // 1. Accessibility disabled, MediaProjection not granted -> 8 native tools available (3 utility + 5 app/url/YouTube tools)
        val caps1 = inspector.inspectAll(emptyMap())
        val available1 = registry.getAvailableTools(caps1).map { it.definition.name }.toSet()
        assertEquals(
            setOf(
                "calculator",
                "get_current_datetime",
                "get_device_info",
                "list_installed_apps",
                "find_app",
                "launch_app",
                "open_url",
                "play_on_youtube"
            ),
            available1
        )
        val observeEntry1 = registry.getAllRegisteredTools(caps1).first { it.definition.name == "observe_screen" }
        assertEquals(ToolAvailabilityStatus.SETUP_REQUIRED, observeEntry1.availabilityStatus)
        assertEquals(ToolRecoveryAction.OPEN_ACCESSIBILITY_SETTINGS, observeEntry1.recoveryAction)

        val shotEntry1 = registry.getAllRegisteredTools(caps1).first { it.definition.name == "take_screenshot" }
        assertEquals(ToolAvailabilityStatus.CONSENT_REQUIRED, shotEntry1.availabilityStatus)
        assertEquals(ToolRecoveryAction.REQUEST_MEDIA_PROJECTION, shotEntry1.recoveryAction)

        // 2. Accessibility enabled in Settings but not connected -> TEMPORARILY_UNAVAILABLE
        perm.accState = AccessibilityRuntimeState.ENABLED_NOT_CONNECTED
        val caps2 = inspector.inspectAll(emptyMap())
        val tapEntry2 = registry.getAllRegisteredTools(caps2).first { it.definition.name == "tap_element" }
        assertEquals(ToolAvailabilityStatus.TEMPORARILY_UNAVAILABLE, tapEntry2.availabilityStatus)

        // 3. Accessibility connected & MediaProjection active -> all 19 tools AVAILABLE
        perm.accState = AccessibilityRuntimeState.SERVICE_ENABLED
        perm.projState = MediaProjectionConsentState.GRANTED_ACTIVE
        screenshotBackend.setSnapshot(
            MediaProjectionSessionSnapshot(
                consentState = MediaProjectionConsentState.GRANTED_ACTIVE,
                isSessionActive = true,
                width = 1080,
                height = 2400
            )
        )
        val caps3 = inspector.inspectAll(emptyMap())
        assertEquals(19, registry.getAvailableTools(caps3).size)

        // 4. Runtime execution gate blocks unconsented tool calls deterministically
        perm.accState = AccessibilityRuntimeState.SERVICE_DISABLED
        perm.projState = MediaProjectionConsentState.NOT_GRANTED
        screenshotBackend.stopSession(revokedBySystem = false)
        val gatedCaps = inspector.inspectAll(emptyMap())
        val execRuntime = ToolExecutionRuntime(
            toolRegistry = registry,
            liveCapabilitiesProvider = { gatedCaps }
        )
        val blockedTap = execRuntime.executeToolCall(
            call = ToolCall("c1", "tap_element", """{"text":"OK"}"""),
            settings = AppSettings(),
            timeoutMillis = 5_000L,
            maxResultChars = 8_000
        )
        assertEquals(ToolExecutionStatus.FAILED, blockedTap.status)
        assertTrue(blockedTap.errorMessage.orEmpty().contains("Accessibility", ignoreCase = true))

        val blockedShot = execRuntime.executeToolCall(
            call = ToolCall("c2", "take_screenshot", "{}"),
            settings = AppSettings(),
            timeoutMillis = 5_000L,
            maxResultChars = 8_000
        )
        assertEquals(ToolExecutionStatus.FAILED, blockedShot.status)
        assertTrue(
            blockedShot.displaySummary.contains("Consent required", ignoreCase = true) ||
                blockedShot.errorMessage.orEmpty().contains("screen capture", ignoreCase = true)
        )
    }

    // --- Suite 2: OpenRouter Schema Serialization & Multi-Turn Execution ---

    @Test
    fun `OpenRouter Ling 3_2 Flash Free enables tools in preset and fetched catalog lacking supported_parameters`() {
        val mockHttp = NexaHttpClient(
            okHttpClient = OkHttpClient.Builder().build(),
            networkChecker = object : NetworkAvailabilityChecker {
                override fun isNetworkAvailable(): Boolean = true
            }
        )
        val adapter = OpenRouterProviderAdapter(mockHttp)
        val lingPreset = adapter.defaultModels().firstOrNull { it.id == "inclusionai/ling-3.2-flash:free" }
        assertNotNull("OpenRouter defaultModels must include inclusionai/ling-3.2-flash:free", lingPreset)
        assertTrue(lingPreset!!.supportsTools)

        val fetchedCatalogJson = """
            {
              "data": [
                {
                  "id": "inclusionai/ling-3.2-flash:free",
                  "name": "Ling 3.2 Flash (Free)",
                  "context_length": 131072
                },
                {
                  "id": "openai/whisper-large-v3",
                  "name": "Whisper Large V3",
                  "context_length": 4096
                }
              ]
            }
        """.trimIndent()
        val parsedModels = OpenAiCompatibleJson.parseModelsArray(fetchedCatalogJson, openRouterAutoTools = true)
        val parsedLing = parsedModels.first { it.id == "inclusionai/ling-3.2-flash:free" }
        val parsedWhisper = parsedModels.first { it.id == "openai/whisper-large-v3" }
        assertTrue("OpenRouter :free chat model must auto-enable tools", parsedLing.supportsTools)
        assertFalse("Whisper audio model must NOT auto-enable tools", parsedWhisper.supportsTools)

        val lingConfig = ProviderConfig(
            providerType = AiProviderType.OPENROUTER,
            hasApiKey = true,
            maskedApiKey = "••••test",
            baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
            selectedModelId = "inclusionai/ling-3.2-flash:free",
            isDefault = true,
            availableModels = emptyList()
        )
        assertTrue(lingConfig.effectiveSupportsTools)

        val customConfig = ProviderConfig(
            providerType = AiProviderType.CUSTOM,
            hasApiKey = false,
            maskedApiKey = "",
            baseUrl = "https://local.example/v1",
            selectedModelId = "custom-llama",
            isDefault = true,
            availableModels = emptyList(),
            allowManualToolsOverride = false
        )
        assertFalse(customConfig.effectiveSupportsTools)
    }

    @Test
    fun `OpenRouter serializes all available tools and runs multi-turn tool calls across SSE and JSON fallback`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = shadowOf(context.packageManager)
        val chromeComp = ComponentName("com.android.chrome", "com.google.android.apps.chrome.Main")
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.android.chrome"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.android.chrome"
                    nonLocalizedLabel = "Chrome"
                }
            }
        )
        shadowPm.addActivityIfNotPresent(chromeComp)
        shadowPm.addIntentFilterForActivity(
            chromeComp,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )

        var turn = 0
        val transport = MockTransport { req ->
            turn++
            if (turn == 1) {
                val sse = buildString {
                    append("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call_or_launch_1\",\"type\":\"function\",\"function\":{\"name\":\"launch_app\",\"arguments\":\"{\\\"app\\\":\\\"Chrome\\\"}\"}}]}}]}\n\n")
                    append("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":45,\"completion_tokens\":14,\"total_tokens\":59}}\n\n")
                    append("data: [DONE]\n\n")
                }
                sseOkResponse(req.url, sse)
            } else {
                val plainJson = """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "Chrome launch intent was dispatched."
                          },
                          "finish_reason": "stop"
                        }
                      ],
                      "usage": {"prompt_tokens": 90, "completion_tokens": 11, "total_tokens": 101}
                    }
                """.trimIndent()
                jsonOkResponse(req.url, plainJson)
            }
        }

        val httpClient = NexaHttpClient(
            okHttpClient = OkHttpClient.Builder().addInterceptor(transport).build(),
            networkChecker = object : NetworkAvailabilityChecker {
                override fun isNetworkAvailable(): Boolean = true
            }
        )
        val adapter = OpenRouterProviderAdapter(httpClient)
        val perm = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_DISABLED)
        val runtime = buildRuntime(context, perm)
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val caps = AndroidCapabilityInspector(context, perm).inspectAll(emptyMap())
        val availableTools = registry.getAvailableToolDefinitions(caps)

        val provider = ProviderConfig(
            providerType = AiProviderType.OPENROUTER,
            hasApiKey = true,
            maskedApiKey = "••••test",
            baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
            selectedModelId = "inclusionai/ling-3.2-flash:free",
            isDefault = true,
            availableModels = adapter.defaultModels(),
            allowManualToolsOverride = false
        )

        val recordedDiagnostics = mutableListOf<ToolDispatchDiagnostic>()
        val execRuntime = ToolExecutionRuntime(
            toolRegistry = registry,
            liveCapabilitiesProvider = { caps }
        )
        val orchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = ProviderStreamCaller { prov, request ->
                adapter.streamChat(prov, "sk-or-secret-test-key", request)
            },
            toolRegistry = registry,
            safetyPolicyManager = DefaultSafetyPolicyManager(),
            executionRuntime = execRuntime,
            contextBuilder = ConversationContextBuilder(),
            diagnosticSink = { recordedDiagnostics.add(it) }
        )

        val settings = AppSettings(confirmationMode = ConfirmationMode.SENSITIVE_ONLY)
        val events = orchestrator.runTurn(
            context = AgentExecutionContext(
                taskId = "task_or_1",
                conversationId = "conv_1",
                userPrompt = "Open Chrome for me",
                provider = provider,
                settings = settings,
                limits = settings.toExecutionLimits(128_000),
                availableTools = availableTools,
                systemPrompt = SystemPromptBuilder().build(
                    settings = settings,
                    availableTools = availableTools,
                    toolsEnabledForModel = true,
                    capabilities = caps,
                    registeredTools = registry.getAllRegisteredTools(caps)
                )
            ),
            historySnapshot = listOf(
                ChatMessage(
                    id = "m1",
                    conversationId = "conv_1",
                    role = MessageRole.USER,
                    content = "Open Chrome for me",
                    createdAtMillis = 1000L
                )
            ),
            attachments = emptyList()
        ).toList()

        assertEquals("Two OpenRouter HTTP requests expected for 1 tool iteration + final reply", 2, transport.requests.size)

        val req1Json = JSONObject(transport.requests[0].body)
        assertEquals("auto", req1Json.getString("tool_choice"))
        val toolsArr = req1Json.getJSONArray("tools")
        val serializedToolNames = (0 until toolsArr.length()).map {
            toolsArr.getJSONObject(it).getJSONObject("function").getString("name")
        }.toSet()
        assertEquals(
            setOf(
                "calculator",
                "get_current_datetime",
                "get_device_info",
                "list_installed_apps",
                "find_app",
                "launch_app",
                "open_url",
                "play_on_youtube"
            ),
            serializedToolNames
        )

        val req2Json = JSONObject(transport.requests[1].body)
        val req2Messages = req2Json.getJSONArray("messages")
        val toolResultMsg = (0 until req2Messages.length())
            .map { req2Messages.getJSONObject(it) }
            .firstOrNull { it.optString("role") == "tool" }
        assertNotNull("Follow-up request must include role='tool' result message", toolResultMsg)
        assertEquals("call_or_launch_1", toolResultMsg!!.getString("tool_call_id"))

        val completed = events.filterIsInstance<AgentEvent.Completed>().firstOrNull()
        assertNotNull(completed)
        assertTrue(completed!!.finalText.contains("Chrome"))

        val diag = adapter.lastDiagnostic.value
        assertNotNull(diag)
        assertTrue(diag!!.toolsEnabled)
        assertTrue(diag.serializedToolNames.contains("launch_app"))
        assertEquals("auto", diag.toolChoiceSent)
        assertFalse(recordedDiagnostics.isEmpty())
        assertFalse("Diagnostics must never contain API secret", recordedDiagnostics.toString().contains("sk-or-secret"))
    }

    // --- Suite 3: Android Runtime & Screenshot Vision Forwarding ---

    @Test
    fun `app discovery combines installed and launchable packages and launcher distinguishes statuses`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = shadowOf(context.packageManager)

        val ytComp = ComponentName("com.google.android.youtube", "com.google.android.youtube.HomeActivity")
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.google.android.youtube"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.google.android.youtube"
                    nonLocalizedLabel = "YouTube"
                }
            }
        )
        shadowPm.addActivityIfNotPresent(ytComp)
        shadowPm.addIntentFilterForActivity(
            ytComp,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )

        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.vendor.backgroundservice"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.vendor.backgroundservice"
                    nonLocalizedLabel = "Vendor Sync Service"
                }
            }
        )

        val discovery = AndroidAppDiscovery(context)
        val launchableOnly = discovery.listInstalledApps(includeNonLaunchable = false)
        assertTrue(launchableOnly.apps.any { it.packageName == "com.google.android.youtube" })
        assertFalse(launchableOnly.apps.any { it.packageName == "com.vendor.backgroundservice" })

        val mergedWithNonLaunchable = discovery.listInstalledApps(includeNonLaunchable = true)
        assertTrue(mergedWithNonLaunchable.apps.any { it.packageName == "com.google.android.youtube" && it.isLaunchable })
        assertTrue(mergedWithNonLaunchable.apps.any { it.packageName == "com.vendor.backgroundservice" && !it.isLaunchable })

        assertTrue(discovery.findApp("YouTube") is AppLookupOutcome.Found)
        assertTrue(discovery.findApp("Vendor Sync") is AppLookupOutcome.NotLaunchable)

        val permOff = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_DISABLED)
        val runtimeOff = buildRuntime(context, permOff)
        val ytDispatched = runtimeOff.launchApp("YouTube")
        assertTrue(ytDispatched.success)
        assertEquals(AppLaunchStatus.LAUNCH_DISPATCHED, ytDispatched.launchStatus)

        val missingOutcome = runtimeOff.launchApp("TotallyMissingApp123")
        assertFalse(missingOutcome.success)
        assertEquals(AppLaunchStatus.NOT_FOUND, missingOutcome.launchStatus)
    }

    @Test
    fun `AndroidPermissionManager differentiates all Accessibility conditions honestly`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = AndroidPermissionManager(context)

        // Disabled in Settings
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        NexaAccessibilityService.updateTestStateForTesting(AccessibilityLifecycleSnapshot(isConnected = false))
        assertEquals(AccessibilityRuntimeState.SERVICE_DISABLED, manager.inspect().accessibilityState)

        // Enabled in Settings, not connected
        Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${context.packageName}/${NexaAccessibilityService::class.java.name}"
        )
        NexaAccessibilityService.updateTestStateForTesting(AccessibilityLifecycleSnapshot(isConnected = false))
        assertEquals(AccessibilityRuntimeState.ENABLED_NOT_CONNECTED, manager.inspect().accessibilityState)

        // Interrupted
        NexaAccessibilityService.updateTestStateForTesting(
            AccessibilityLifecycleSnapshot(isConnected = false, isInterrupted = true)
        )
        assertEquals(AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE, manager.inspect().accessibilityState)

        // Connected & active via real service instance
        val serviceInstance = Robolectric.buildService(NexaAccessibilityService::class.java).create().get()
        NexaAccessibilityService.attachInstanceForTesting(serviceInstance)
        NexaAccessibilityService.updateTestStateForTesting(AccessibilityLifecycleSnapshot(isConnected = true))
        assertEquals(AccessibilityRuntimeState.SERVICE_ENABLED, manager.inspect().accessibilityState)
    }

    @Test
    fun `take_screenshot refuses unconsented, captures PNG cleanly when active, and attaches vision only if supported`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val realProj = MediaProjectionSessionManager(context)
        val blocked = realProj.captureScreenshot(includeBase64 = true)
        assertFalse(blocked.success)
        assertTrue(blocked.requiresConsent)
        assertEquals("MEDIA_PROJECTION_CONSENT_REQUIRED", blocked.errorCode)

        val perm = FakePermissionManager(
            context,
            accState = AccessibilityRuntimeState.SERVICE_ENABLED,
            projState = MediaProjectionConsentState.GRANTED_ACTIVE
        )
        val backend = FakeScreenshotBackend(
            MediaProjectionSessionSnapshot(
                consentState = MediaProjectionConsentState.GRANTED_ACTIVE,
                isSessionActive = true,
                width = 1080,
                height = 2400
            )
        )
        val runtime = buildRuntime(context, perm, backend)
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val caps = AndroidCapabilityInspector(context, perm).inspectAll(emptyMap())
        val execRuntime = ToolExecutionRuntime(registry, liveCapabilitiesProvider = { caps })

        // Verify frame-unavailable failure reports error honestly
        backend.nextOutcome = ScreenshotCaptureOutcome(
            success = false,
            message = "No display frame available yet from MediaProjection.",
            errorCode = "SCREENSHOT_FRAME_UNAVAILABLE"
        )
        val failTool = ComputerUseTools.createAll(runtime).first { it.definition.name == "take_screenshot" }
        val failRes = failTool.execute(JSONObject())
        assertTrue(failRes.isError)
        backend.nextOutcome = null

        var turn = 0
        val capturedRequests = mutableListOf<ProviderRequest>()
        val orchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = ProviderStreamCaller { _, request ->
                flow {
                    capturedRequests.add(request)
                    turn++
                    if (turn == 1) {
                        emit(
                            ProviderStreamEvent.ToolCallsCompleted(
                                listOf(ToolCall("call_shot_1", "take_screenshot", "{}"))
                            )
                        )
                    } else {
                        emit(ProviderStreamEvent.Delta("Analyzed the captured screen."))
                    }
                    emit(ProviderStreamEvent.Completed())
                }
            },
            toolRegistry = registry,
            safetyPolicyManager = DefaultSafetyPolicyManager(),
            executionRuntime = execRuntime,
            contextBuilder = ConversationContextBuilder()
        )

        val settings = AppSettings(confirmationMode = ConfirmationMode.SENSITIVE_ONLY)
        val visionProvider = ProviderConfig(
            providerType = AiProviderType.OPENROUTER,
            hasApiKey = true,
            maskedApiKey = "••••test",
            baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
            selectedModelId = "anthropic/claude-sonnet-4",
            isDefault = true,
            availableModels = listOf(
                AiModelInfo("anthropic/claude-sonnet-4", "Claude Sonnet 4", "200K", 200_000, supportsVision = true, supportsTools = true)
            )
        )
        val events = orchestrator.runTurn(
            context = AgentExecutionContext(
                taskId = "t_shot",
                conversationId = "c_shot",
                userPrompt = "Take a screenshot and describe the screen",
                provider = visionProvider,
                settings = settings,
                limits = settings.toExecutionLimits(200_000),
                availableTools = registry.getAvailableToolDefinitions(caps),
                systemPrompt = "system"
            ),
            historySnapshot = listOf(
                ChatMessage("m1", "c_shot", MessageRole.USER, "Take a screenshot", 100L)
            ),
            attachments = emptyList()
        ).toList()

        assertEquals(2, capturedRequests.size)
        assertTrue("Vision-capable model must receive screenshot AttachmentPayload on turn 2", capturedRequests[1].attachments.isNotEmpty())
        assertNotNull(events.filterIsInstance<AgentEvent.Completed>().firstOrNull())

        // Verify non-vision model does NOT receive image attachments
        turn = 0
        capturedRequests.clear()
        val nonVisionProvider = ProviderConfig(
            providerType = AiProviderType.OPENROUTER,
            hasApiKey = true,
            maskedApiKey = "••••test",
            baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
            selectedModelId = "inclusionai/ling-3.2-flash:free",
            isDefault = true,
            availableModels = listOf(
                AiModelInfo("inclusionai/ling-3.2-flash:free", "Ling 3.2 Flash (Free)", "128K", 128_000, supportsVision = false, supportsTools = true)
            )
        )
        orchestrator.runTurn(
            context = AgentExecutionContext(
                taskId = "t_shot_nv",
                conversationId = "c_shot_nv",
                userPrompt = "Take a screenshot",
                provider = nonVisionProvider,
                settings = settings,
                limits = settings.toExecutionLimits(128_000),
                availableTools = registry.getAvailableToolDefinitions(caps),
                systemPrompt = "system"
            ),
            historySnapshot = listOf(
                ChatMessage("m1", "c_shot_nv", MessageRole.USER, "Take a screenshot", 100L)
            ),
            attachments = emptyList()
        ).toList()

        assertEquals(2, capturedRequests.size)
        assertTrue("Non-vision model must not receive image attachments", capturedRequests[1].attachments.isEmpty())
    }

    // --- Suite 4: System Prompt & Compose UI Synchronization ---

    @Test
    fun `SystemPromptBuilder and ChatEmptyWelcome stay synchronized with live capability states`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val perm = FakePermissionManager(
            context,
            accState = AccessibilityRuntimeState.SERVICE_DISABLED,
            projState = MediaProjectionConsentState.NOT_GRANTED
        )
        val backend = FakeScreenshotBackend()
        val runtime = buildRuntime(context, perm, backend)
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val inspector = AndroidCapabilityInspector(context, perm)
        val caps = inspector.inspectAll(emptyMap())
        val registered = registry.getAllRegisteredTools(caps)

        val prompt = SystemPromptBuilder().build(
            settings = AppSettings(),
            availableTools = registry.getAvailableToolDefinitions(caps),
            toolsEnabledForModel = true,
            capabilities = caps,
            registeredTools = registered
        )
        assertTrue(prompt.contains("launch_app"))
        assertTrue(prompt.contains("take_screenshot"))
        assertTrue(prompt.contains("Registered tools currently unavailable"))
        assertFalse("System prompt must never falsely claim MediaProjection is disabled", prompt.contains("Capture display screenshots | Status: Disabled"))

        composeRule.setContent {
            NexaTheme {
                ChatEmptyWelcome(
                    defaultProvider = ProviderConfig(
                        providerType = AiProviderType.OPENROUTER,
                        hasApiKey = true,
                        maskedApiKey = "••••test",
                        baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
                        selectedModelId = "inclusionai/ling-3.2-flash:free",
                        isDefault = true,
                        availableModels = emptyList()
                    ),
                    capabilities = caps,
                    registeredTools = registered,
                    accessibilityState = AccessibilityRuntimeState.SERVICE_DISABLED,
                    mediaProjectionState = MediaProjectionConsentState.NOT_GRANTED,
                    onPromptSelect = {},
                    onConfigureProvider = {},
                    onOpenAccessibilitySettings = {},
                    onRequestScreenCapture = {},
                    onStopScreenCapture = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("welcome_capability_pills").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_pill_utilities").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_pill_app_launch").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_pill_accessibility").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_pill_screen_capture").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_open_accessibility_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("welcome_grant_screen_capture").assertIsDisplayed()
    }

    @Test
    fun `CapabilitiesTab renders ScreenCaptureSetupCard and ENABLED_NOT_CONNECTED state accurately`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val caps = AndroidCapabilityInspector(context).inspectAll(emptyMap())

        composeRule.setContent {
            NexaTheme {
                CapabilitiesTab(
                    capabilities = caps,
                    selectedCategory = null,
                    accessibilityState = AccessibilityRuntimeState.ENABLED_NOT_CONNECTED,
                    accessibilityReason = "Accessibility service is toggled on in Settings but not yet bound.",
                    canPostNotifications = true,
                    onSelectCategory = {},
                    onRefresh = {},
                    onToggleCapability = { _, _ -> },
                    mediaProjectionState = MediaProjectionConsentState.NOT_GRANTED,
                    mediaProjectionReason = "Screen capture permission not granted.",
                    onRequestScreenCapture = {},
                    onStopScreenCapture = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("accessibility_setup_card").assertIsDisplayed()
        composeRule.onNodeWithText("Enabled, not connected").assertIsDisplayed()
        composeRule.onNodeWithTag("capabilities_list").performScrollToIndex(3)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("screen_capture_setup_card").assertIsDisplayed()
        composeRule.onNodeWithTag("capabilities_request_screen_capture").assertIsDisplayed()
    }

    @Test
    fun `ToolsCatalogSheet displays unified RegisteredToolEntry status and recovery actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val perm = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_DISABLED)
        val runtime = buildRuntime(context, perm)
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val caps = AndroidCapabilityInspector(context, perm).inspectAll(emptyMap())
        val registered = registry.getAllRegisteredTools(caps)

        composeRule.setContent {
            NexaTheme {
                ToolsCatalogSheet(
                    tools = registry.getAllTools(),
                    executableTools = registry.getAvailableToolDefinitions(caps),
                    registeredTools = registered,
                    onOpenAccessibilitySettings = {},
                    onRequestScreenCapture = {},
                    onDismiss = {}
                )
            }
        }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tools_catalog_list").assertIsDisplayed()
        val sorted = registered.sortedByDescending { it.isAvailable }
        val launchIndex = sorted.indexOfFirst { it.definition.name == "launch_app" }
        composeRule.onNodeWithTag("tools_catalog_list").performScrollToIndex(launchIndex)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_catalog_item_launch_app").assertIsDisplayed()
        val unavailableIndex = sorted.indexOfFirst { it.definition.name == "take_screenshot" }
        composeRule.onNodeWithTag("tools_catalog_list").performScrollToIndex(unavailableIndex)
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("tool_catalog_item_take_screenshot").assertIsDisplayed()
        composeRule.onNodeWithTag("tool_recovery_screen_capture_take_screenshot")
            .performScrollTo()
            .assertIsDisplayed()
    }
}
