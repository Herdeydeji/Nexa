package com.example

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.android.accessibility.AccessibilityInteractionController
import com.example.android.accessibility.AccessibilityLifecycleSnapshot
import com.example.android.accessibility.AccessibilityWindowChangeSignal
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.GroundingOutcome
import com.example.android.accessibility.InteractionBackend
import com.example.android.accessibility.InteractionDispatchResult
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.accessibility.ScreenObservationBridge
import com.example.android.accessibility.UiElementGroundingEngine
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.media.MediaProjectionSessionSnapshot
import com.example.android.media.ScreenshotCaptureBackend
import com.example.android.media.VolatileScreenFrame
import com.example.android.ocr.ScreenOcrProcessor
import com.example.android.ocr.ScreenOcrSanitizer
import com.example.android.ocr.SnapshotFusionHelper
import com.example.android.overlay.FloatingIndicatorController
import com.example.android.overlay.FloatingIndicatorUiState
import com.example.android.overlay.FloatingIndicatorVisualState
import com.example.android.overlay.FloatingOverlayHost
import com.example.android.runtime.AndroidActionRuntime
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.runtime.FailureDiagnosisEngine
import com.example.android.runtime.PermissionInspectionSnapshot
import com.example.domain.agent.AgentControlHandle
import com.example.domain.agent.AgentRunController
import com.example.domain.agent.ComputerUseTools
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.ProgressCheckOutcome
import com.example.domain.agent.ProviderStreamCaller
import com.example.domain.agent.TaskProgressMonitor
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentExecutionLimits
import com.example.domain.model.AgentExecutionPhase
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AmbiguitySelectionRequest
import com.example.domain.model.AppSettings
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.ChatMessage
import com.example.domain.model.ComputerUseFailureCategory
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.FloatingIndicatorMode
import com.example.domain.model.MediaProjectionConsentState
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.OcrTextRegion
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScreenshotCaptureOutcome
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.TextEditMode
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolCallResult
import com.example.domain.model.ToolExecutionStatus
import com.example.domain.model.UiBounds
import com.example.domain.model.UiElementGroundingLevel
import com.example.domain.model.UiNodeSource
import com.example.domain.model.VerificationLevel
import com.example.domain.model.WindowDialogType
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderStreamEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaComputerUseEngineV2Test {

    @After
    fun tearDown() {
        NexaAccessibilityService.updateTestStateForTesting(AccessibilityLifecycleSnapshot())
    }

    // --- 1. 9-Level UI Grounding, Snapshot Validation, Disambiguation & Occlusion ---

    @Test
    fun groundingEngineValidatesSnapshotAndUsesContextAndOcrLevels() {
        val obs = ScreenObservation(
            timestampMillis = 1_000L,
            snapshotId = 42L,
            activePackageName = "com.example.shop",
            windowTitle = "Storefront",
            screenWidthPx = 1080,
            screenHeightPx = 2400,
            nodes = listOf(
                ObservedUiNode(
                    index = 0,
                    role = "Container",
                    text = "Headphones Pro",
                    bounds = UiBounds(40, 200, 1040, 500),
                    clickable = false
                ),
                ObservedUiNode(
                    index = 1,
                    viewId = "com.example.shop:id/add_cart_top",
                    role = "Button",
                    text = "Add",
                    bounds = UiBounds(760, 260, 1000, 360),
                    clickable = true,
                    parentIndex = 0
                ),
                ObservedUiNode(
                    index = 2,
                    role = "Container",
                    text = "USB-C Cable",
                    bounds = UiBounds(40, 1300, 1040, 1600),
                    clickable = false
                ),
                ObservedUiNode(
                    index = 3,
                    viewId = "com.example.shop:id/add_cart_bottom",
                    role = "Button",
                    text = "Add",
                    bounds = UiBounds(760, 1360, 1000, 1460),
                    clickable = true,
                    parentIndex = 2
                ),
                ObservedUiNode(
                    index = 4,
                    role = "VisualTextTarget",
                    text = "Checkout Now",
                    bounds = UiBounds(300, 2100, 780, 2220),
                    clickable = true,
                    source = UiNodeSource.OCR_FUSED
                )
            )
        )

        // Stale snapshot rejected for pinned node index
        val stale = UiElementGroundingEngine.resolve(
            observation = obs,
            spec = TargetResolutionSpec(nodeIndex = 1, expectedSnapshotId = 19L)
        )
        assertTrue(stale is GroundingOutcome.StaleSnapshot)

        // Exact snapshot + node index hits Level 1
        val level1 = UiElementGroundingEngine.resolve(
            observation = obs,
            spec = TargetResolutionSpec(nodeIndex = 1, expectedSnapshotId = 42L)
        )
        assertTrue(level1 is GroundingOutcome.Resolved)
        assertEquals(UiElementGroundingLevel.EXACT_SNAPSHOT_NODE, (level1 as GroundingOutcome.Resolved).explanation.level)

        // Two "Add" buttons with no context -> Ambiguous (triggers context re-inspection / user choice)
        val ambiguous = UiElementGroundingEngine.resolve(
            observation = obs,
            spec = TargetResolutionSpec(text = "Add")
        )
        assertTrue(ambiguous is GroundingOutcome.Ambiguous)
        assertEquals(2, (ambiguous as GroundingOutcome.Ambiguous).candidates.size)

        // Disambiguating with anchorContext picks the right button cleanly
        val contextual = UiElementGroundingEngine.resolve(
            observation = obs,
            spec = TargetResolutionSpec(text = "Add", anchorContext = "USB-C Cable")
        )
        assertTrue(contextual is GroundingOutcome.Resolved)
        assertEquals(3, (contextual as GroundingOutcome.Resolved).node.index)

        // OCR fused region resolves at Level 8
        val ocrMatch = UiElementGroundingEngine.resolve(
            observation = obs,
            spec = TargetResolutionSpec(text = "Checkout Now")
        )
        assertTrue(ocrMatch is GroundingOutcome.Resolved)
        val resolvedOcr = ocrMatch as GroundingOutcome.Resolved
        assertEquals(UiElementGroundingLevel.OCR_TEXT_MATCH, resolvedOcr.explanation.level)
        assertEquals(4, resolvedOcr.node.index)
    }

    @Test
    fun groundingEngineFlagsOverlappingDialogOcclusion() {
        val modalObs = ScreenObservation(
            timestampMillis = 2_000L,
            snapshotId = 10L,
            activePackageName = "com.example.bank",
            windowTitle = "Allow Access?",
            dialogType = WindowDialogType.APP_DIALOG,
            nodes = listOf(
                ObservedUiNode(
                    index = 0,
                    role = "Button",
                    text = "Confirm Transfer",
                    bounds = UiBounds(200, 1000, 880, 1140),
                    clickable = true
                ),
                ObservedUiNode(
                    index = 1,
                    role = "Container",
                    text = "System Modal Blocker",
                    bounds = UiBounds(100, 700, 980, 1500),
                    clickable = false
                )
            )
        )

        val resolved = UiElementGroundingEngine.resolve(
            observation = modalObs,
            spec = TargetResolutionSpec(text = "Confirm Transfer")
        )
        assertTrue(resolved is GroundingOutcome.Resolved)
        assertTrue((resolved as GroundingOutcome.Resolved).explanation.overlapDetected)
    }

    // --- 2. OCR Sanitization, Fusion & Automatic Sparse-Tree Observation ---

    @Test
    fun ocrSanitizerAndFusionRedactsSecretsAndFillsSparseTree() {
        assertEquals("OTP: [REDACTED_SENSITIVE]", ScreenOcrSanitizer.sanitize("OTP: 482910"))
        assertEquals("[REDACTED_SENSITIVE]", ScreenOcrSanitizer.sanitize("4532 1928 3344 5511"))
        assertEquals("Play Episode 1", ScreenOcrSanitizer.sanitize("Play Episode 1"))

        val baseObs = ScreenObservation(
            timestampMillis = 100L,
            snapshotId = 1L,
            activePackageName = "com.game.canvas",
            windowTitle = "Canvas Screen",
            hasSparseHierarchy = true,
            nodes = listOf(
                ObservedUiNode(
                    index = 0,
                    role = "Text",
                    text = "Home",
                    bounds = UiBounds(20, 20, 200, 80),
                    clickable = true
                )
            )
        )

        val ocrRegions = listOf(
            // Overlaps "Home" -> skipped during fusion
            OcrTextRegion(0, "Home", UiBounds(22, 22, 198, 78), 0.9f),
            // Sensitive OTP -> redacted
            OcrTextRegion(1, "Verification Code: 998122", UiBounds(100, 300, 500, 360), 0.92f),
            // Unlabeled visual button -> fused as clickable OCR node
            OcrTextRegion(2, "Start Quest", UiBounds(300, 1200, 780, 1300), 0.95f)
        )

        val fused = SnapshotFusionHelper.fuse(baseObs, ocrRegions)
        assertTrue(fused.nodes.any { it.text == "Start Quest" && it.source == UiNodeSource.OCR_FUSED && it.clickable })
        assertTrue(fused.nodes.any { it.text?.contains("[REDACTED_SENSITIVE]") == true && it.isSensitiveRedacted })
        assertEquals(1, fused.nodes.count { it.text == "Home" })
    }

    @Test
    fun androidScreenObserverAutoCapturesOcrAndReclaimsSingleFrameOnNextObserve() = runTest {
        val sparseObs = ScreenObservation(
            timestampMillis = 100L,
            activePackageName = "com.example.canvas",
            windowTitle = "Image Menu",
            hasSparseHierarchy = true,
            hierarchyCompleteness = 0.25f,
            nodes = listOf(
                ObservedUiNode(index = 0, role = "View", bounds = UiBounds(0, 0, 1080, 2400))
            )
        )
        val nonSparseObs = sparseObs.copy(
            hasSparseHierarchy = false,
            hierarchyCompleteness = 1.0f,
            nodes = listOf(
                ObservedUiNode(index = 0, role = "Button", text = "Profile", bounds = UiBounds(10, 10, 200, 90), clickable = true)
            )
        )

        val bridge = SequenceScreenBridge(mutableListOf(sparseObs, nonSparseObs))
        val fakeBackend = FakeMediaProjectionBackend(active = true)
        val fakeOcr = object : ScreenOcrProcessor {
            override suspend fun recognizeText(bitmap: Bitmap): List<OcrTextRegion> = listOf(
                OcrTextRegion(0, "Continue Shopping", UiBounds(120, 900, 960, 1020), 0.94f)
            )
        }

        val observer = AndroidScreenObserver(
            bridge = bridge,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            screenshotBackend = fakeBackend,
            ocrProcessor = fakeOcr
        )

        val first = observer.observe()
        assertTrue(first.nodes.any { it.text == "Continue Shopping" && it.source == UiNodeSource.OCR_FUSED })
        assertNotNull(observer.consumeLatestSparseVisualAttachment(first.snapshotId))
        // Once consumed, single-buffer reference is cleared
        assertNull(observer.consumeLatestSparseVisualAttachment(first.snapshotId))
        // Volatile bitmap recycled deterministically
        assertTrue(fakeBackend.createdBitmaps.all { it.isRecycled })

        // Non-sparse second observation does NOT invoke OCR/capture
        val captureCountBeforeSecond = fakeBackend.volatileCalls
        val second = observer.observe()
        assertEquals(captureCountBeforeSecond, fakeBackend.volatileCalls)
        assertEquals("Profile", second.nodes.first().text)
    }

    // --- 3. Action Verification Levels (LEVEL 0..4) & Failure Diagnosis ---

    @Test
    fun actionVerifierRefusesLevel0ZeroChangeAndDiagnosisClassifies15Failures() = runTest {
        val staticScreen = ScreenObservation(
            timestampMillis = 500L,
            activePackageName = "com.example.app",
            windowTitle = "Checkout",
            hierarchySignature = "sig_static_1",
            isKeyboardVisible = true,
            nodes = listOf(
                ObservedUiNode(index = 0, role = "EditText", text = "", editable = true, focused = true, bounds = UiBounds(40, 200, 900, 320)),
                ObservedUiNode(index = 1, role = "List", scrollable = true, bounds = UiBounds(0, 400, 1080, 1800))
            )
        )
        val observer = AndroidScreenObserver(
            bridge = SequenceScreenBridge(mutableListOf(staticScreen, staticScreen)),
            workerDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        val verifier = AndroidActionVerifier(screenObserver = observer, settleDelayMillis = 1L)

        // No change at all -> Level 0, verified MUST be false
        val zeroChange = verifier.verifyUiAction(
            actionName = "tap_element",
            before = staticScreen
        )
        assertFalse(zeroChange.verified)
        assertEquals(VerificationLevel.LEVEL_0_DISPATCHED, zeroChange.verificationLevel)

        // Package change -> verified Level 1
        val nextAppScreen = staticScreen.copy(
            activePackageName = "com.other.app",
            hierarchySignature = "sig_other_2"
        )
        val obsChanged = AndroidScreenObserver(
            bridge = SequenceScreenBridge(mutableListOf(nextAppScreen)),
            workerDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        val pkgResult = AndroidActionVerifier(obsChanged, settleDelayMillis = 1L).verifyUiAction(
            actionName = "tap_element",
            before = staticScreen
        )
        assertTrue(pkgResult.verified)
        assertEquals(VerificationLevel.LEVEL_1_SCREEN_CHANGED, pkgResult.verificationLevel)

        // Failure diagnoses cover key classes cleanly
        val kbDiag = FailureDiagnosisEngine.diagnose(
            actionName = "tap_element",
            errorCode = "ELEMENT_OBSTRUCTED",
            detail = "Obstructed",
            before = staticScreen,
            after = staticScreen
        )
        assertEquals(ComputerUseFailureCategory.KEYBOARD_BLOCKING_TARGET, kbDiag?.category)

        val offscreenDiag = FailureDiagnosisEngine.diagnose(
            actionName = "tap_element",
            errorCode = "TARGET_NOT_FOUND",
            detail = "Missing",
            before = staticScreen,
            after = staticScreen
        )
        assertEquals(ComputerUseFailureCategory.OFFSCREEN_NEEDS_SCROLL, offscreenDiag?.category)

        val dialogScreen = staticScreen.copy(dialogType = WindowDialogType.SYSTEM_PERMISSION)
        val dialogDiag = FailureDiagnosisEngine.diagnose(
            actionName = "tap_element",
            errorCode = "NO_UI_EFFECT",
            detail = "Popup blocking",
            before = dialogScreen,
            after = dialogScreen
        )
        assertEquals(ComputerUseFailureCategory.UNEXPECTED_DIALOG_OR_OVERLAY, dialogDiag?.category)
    }

    // --- 4. Interaction Controller & ProgressMonitor Loop Prevention ---

    @Test
    fun runtimeHandlesNewToolsWaitForElementEditModesAndDisambiguation() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NexaAccessibilityService.updateTestStateForTesting(
            AccessibilityLifecycleSnapshot(isConnected = true, isInterrupted = false)
        )
        val beforeObs = ScreenObservation(
            timestampMillis = 1_000L,
            snapshotId = 7L,
            activePackageName = "com.example.notes",
            windowTitle = "Notes",
            hierarchySignature = "notes_1",
            nodes = listOf(
                ObservedUiNode(index = 0, viewId = "id/body_edit", role = "EditText", text = "Hello", editable = true, focused = true, bounds = UiBounds(50, 200, 900, 600)),
                ObservedUiNode(index = 1, role = "Button", text = "Save", bounds = UiBounds(800, 80, 1020, 160), clickable = true)
            )
        )
        val afterObs = beforeObs.copy(
            snapshotId = 8L,
            hierarchySignature = "notes_2",
            nodes = listOf(
                ObservedUiNode(index = 0, viewId = "id/body_edit", role = "EditText", text = "Hello World", editable = true, focused = true, bounds = UiBounds(50, 200, 900, 600)),
                ObservedUiNode(index = 1, role = "Button", text = "Save", bounds = UiBounds(800, 80, 1020, 160), clickable = true)
            )
        )

        val bridge = SequenceScreenBridge(mutableListOf(beforeObs, afterObs, afterObs, afterObs))
        val observer = AndroidScreenObserver(bridge, UnconfinedTestDispatcher(testScheduler))
        val verifier = AndroidActionVerifier(observer, settleDelayMillis = 1L)
        val backend = FakeInteractionBackend()
        val permManager = FakePermissionManager(context, AccessibilityRuntimeState.SERVICE_ENABLED)
        val appDiscovery = AndroidAppDiscovery(context)
        val appLauncher = AndroidAppLauncher(context, appDiscovery, permManager, verifier)
        val runtime = AndroidActionRuntime(
            permissionManager = permManager,
            appDiscovery = appDiscovery,
            appLauncher = appLauncher,
            screenObserver = observer,
            interactionController = AccessibilityInteractionController(backend),
            actionVerifier = verifier
        )

        val editOutcome = runtime.editTextField(
            spec = TargetResolutionSpec(viewId = "id/body_edit"),
            mode = TextEditMode.APPEND,
            text = " World"
        )
        assertTrue(editOutcome.success)
        assertTrue(editOutcome.verificationLevel.levelNumber >= 2)

        val swipeOutcome = runtime.swipeScreen(500f, 1500f, 500f, 400f, 300L)
        assertTrue(backend.swiped)
        assertEquals("swipe_screen", swipeOutcome.action)

        val waitOutcome = runtime.waitForElement(
            spec = TargetResolutionSpec(text = "Save"),
            timeoutMillis = 800L
        )
        assertTrue(waitOutcome.success)
    }

    @Test
    fun progressMonitorIgnoresLevel0AsProgressAndHaltsLoop() {
        val monitor = TaskProgressMonitor(objective = "Submit form", noProgressLimit = 2)
        val level0Result = ToolCallResult(
            callId = "c1",
            toolName = "tap_element",
            status = ToolExecutionStatus.FAILED,
            outputJson = JSONObject()
                .put("verificationLevel", 0)
                .put("errorCode", "NO_UI_EFFECT")
                .put("failureCategory", "KEYBOARD_BLOCKING_TARGET")
                .put("screenChanged", false)
                .toString(),
            displaySummary = "No UI effect"
        )
        val call = ToolCall("c1", "tap_element", """{"text":"Submit"}""")
        val cycle1 = monitor.recordToolResult(call, level0Result)
        assertTrue(cycle1 is ProgressCheckOutcome.NoProgressCycle)

        val cycle2 = monitor.recordToolResult(call.copy(id = "c2"), level0Result.copy(callId = "c2"))
        assertTrue(cycle2 is ProgressCheckOutcome.HaltNoProgress)
        assertEquals("KEYBOARD_BLOCKING_TARGET", (cycle2 as ProgressCheckOutcome.HaltNoProgress).blockerClassification)
    }

    // --- 5. Orchestrator: 16-State Phases, Vision Gating, Context Condensation & UI Ambiguity ---

    @Test
    fun orchestratorHandlesUiAmbiguityCondensationSparseVisionAndPhases() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NexaAccessibilityService.updateTestStateForTesting(
            AccessibilityLifecycleSnapshot(isConnected = true, isInterrupted = false)
        )

        // Two "Open" buttons cause UI_AMBIGUOUS, then user chooses node_index=1 -> completes!
        val obs1 = ScreenObservation(
            timestampMillis = 100L,
            snapshotId = 101L,
            activePackageName = "com.example.files",
            windowTitle = "Files",
            hierarchySignature = "files_1",
            hasSparseHierarchy = true,
            nodes = listOf(
                ObservedUiNode(index = 0, role = "Button", text = "Open", bounds = UiBounds(50, 200, 400, 300), clickable = true),
                ObservedUiNode(index = 1, role = "Button", text = "Open", bounds = UiBounds(50, 900, 400, 1000), clickable = true)
            )
        )
        val obs2 = obs1.copy(
            snapshotId = 102L,
            activePackageName = "com.example.viewer",
            windowTitle = "Document",
            hierarchySignature = "viewer_1",
            hasSparseHierarchy = false,
            nodes = listOf(
                ObservedUiNode(index = 0, role = "Text", text = "Document Loaded", bounds = UiBounds(20, 100, 900, 200))
            )
        )

        val bridge = SequenceScreenBridge(mutableListOf(obs1, obs1, obs1, obs1, obs1, obs2))
        val observer = AndroidScreenObserver(
            bridge = bridge,
            workerDispatcher = UnconfinedTestDispatcher(testScheduler),
            screenshotBackend = FakeMediaProjectionBackend(active = true),
            ocrProcessor = object : ScreenOcrProcessor {
                override suspend fun recognizeText(bitmap: Bitmap): List<OcrTextRegion> = emptyList()
            }
        )
        val verifier = AndroidActionVerifier(observer, settleDelayMillis = 1L)
        val backend = FakeInteractionBackend()
        val permManager = FakePermissionManager(context, AccessibilityRuntimeState.SERVICE_ENABLED)
        val appDiscovery = AndroidAppDiscovery(context)
        val runtime = AndroidActionRuntime(
            permissionManager = permManager,
            appDiscovery = appDiscovery,
            appLauncher = AndroidAppLauncher(context, appDiscovery, permManager, verifier),
            screenObserver = observer,
            interactionController = AccessibilityInteractionController(backend),
            actionVerifier = verifier
        )
        val registry = DefaultToolRegistry(context, actionRuntime = runtime)
        val execRuntime = ToolExecutionRuntime(registry, androidActionRuntime = runtime)

        val capturedRequests = mutableListOf<ProviderRequest>()
        var turnNum = 0
        val streamCaller = ProviderStreamCaller { _, req ->
            capturedRequests.add(req)
            turnNum++
            flow {
                when (turnNum) {
                    1 -> emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("t1", "observe_screen", "{}"))))
                    2 -> emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("t2", "tap_element", """{"text":"Open"}"""))))
                    else -> emit(ProviderStreamEvent.Delta("Opened the selected file."))
                }
                if (turnNum >= 3) emit(ProviderStreamEvent.Completed())
            }
        }

        val controller = AgentRunController()
        val handle = controller.beginRun("task_cu_v2", "conv_1")
        val autoChoiceHandle = object : AgentControlHandle by handle {
            override suspend fun requestApproval(request: ToolApprovalRequest): ApprovalDecision = ApprovalDecision.APPROVE
            override suspend fun requestAmbiguityChoice(request: AmbiguitySelectionRequest): String? {
                assertTrue(request.isUiElementSelection)
                assertEquals(2, request.candidates.size)
                return request.candidates[1].packageName // chooses "node:1"
            }
        }

        val orchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = streamCaller,
            toolRegistry = registry,
            safetyPolicyManager = DefaultSafetyPolicyManager(),
            executionRuntime = execRuntime
        )

        val execContext = AgentExecutionContext(
            taskId = "task_cu_v2",
            conversationId = "conv_1",
            userPrompt = "Open the second file",
            provider = ProviderConfig(
                providerType = AiProviderType.OPENROUTER,
                hasApiKey = true,
                maskedApiKey = "sk-***",
                baseUrl = "https://openrouter.ai/api/v1",
                selectedModelId = "openai/gpt-4o",
                isDefault = true,
                availableModels = listOf(
                    AiModelInfo("openai/gpt-4o", "GPT-4o", supportsTools = true, supportsVision = true)
                ),
                isConnected = true
            ),
            settings = AppSettings(confirmationMode = ConfirmationMode.SUPERVISED_AUTO),
            limits = AgentExecutionLimits(),
            availableTools = registry.getAvailableToolDefinitions(),
            systemPrompt = "You are Nexa."
        )

        val events = orchestrator.runTurn(execContext, listOf(
            ChatMessage("m1", "conv_1", MessageRole.USER, "Open the second file", 1L, MessageStatus.COMPLETE)
        ), emptyList(), autoChoiceHandle).toList()

        events.forEach { controller.applyEvent(it) }

        assertTrue(events.any { it is AgentEvent.AmbiguityChoiceRequired && it.request.isUiElementSelection })
        assertTrue(events.any { it is AgentEvent.Completed && !it.partiallyCompleted })

        // Verify sparse-tree visual frame was attached ONLY after Turn 1 (sparse obs), NOT after Turn 2 (non-sparse obs2)
        assertEquals(0, capturedRequests[0].attachments.size)
        assertEquals(1, capturedRequests[1].attachments.size)
        assertEquals(0, capturedRequests[2].attachments.size)

        // Verify Turn 3 condensed Turn 1's older observe_screen node list
        val turn3ToolMessages = capturedRequests[2].messages.filter { it.role == MessageRole.TOOL }
        assertEquals(2, turn3ToolMessages.size)
        val firstScreenObj = JSONObject(turn3ToolMessages[0].content).let { it.optJSONObject("screen") ?: it }
        assertTrue(firstScreenObj.optBoolean("nodesCondensed", false))
        assertFalse(firstScreenObj.has("nodes"))
    }

    // --- 6. Floating Indicator HUD & Auto-Expand Policy ---

    @Test
    fun floatingIndicatorAutoExpandsOnWaitingAndRecovery() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val controller = AgentRunController()
        val settingsFlow = MutableStateFlow(AppSettings(floatingIndicatorMode = FloatingIndicatorMode.AUTO_EXTERNAL_APPS))
        val fakeHost = FakeOverlayHost()
        val indicator = FloatingIndicatorController(
            context = context,
            agentRunController = controller,
            settingsFlow = settingsFlow,
            overlayPermissionProvider = { true },
            overlayHost = fakeHost,
            scope = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        )
        indicator.setNexaForeground(false)
        indicator.setCurrentObjective("Open Spotify and search")

        controller.beginRun("task_hud", "conv_hud")
        controller.applyEvent(
            AgentEvent.StatusChanged(
                taskId = "task_hud",
                status = AgentRunStatus.EXECUTING_TOOL,
                message = "Tapping Search...",
                phase = AgentExecutionPhase.EXECUTING_ACTION,
                currentSubgoal = "Open Spotify search",
                nextPlannedAction = "tap_element"
            )
        )
        val compactState = indicator.uiState.value
        assertTrue(compactState.isVisible)
        assertEquals(FloatingIndicatorVisualState.EXECUTING, compactState.visualState)
        assertFalse(compactState.autoExpandOnState)

        // Transition to RECOVERING -> autoExpandOnState flips true
        controller.applyEvent(
            AgentEvent.SubgoalProgressUpdated(
                taskId = "task_hud",
                subgoal = "Open Spotify search",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED,
                failureCategory = ComputerUseFailureCategory.OFFSCREEN_NEEDS_SCROLL,
                nextPlannedAction = "scroll_screen down"
            )
        )
        controller.applyEvent(
            AgentEvent.StatusChanged(
                taskId = "task_hud",
                status = AgentRunStatus.RECOVERING,
                message = "Scrolling to find Search...",
                phase = AgentExecutionPhase.RECOVERING
            )
        )
        val recoverState = indicator.uiState.value
        assertEquals(FloatingIndicatorVisualState.RECOVERING, recoverState.visualState)
        assertTrue(recoverState.autoExpandOnState)
        assertEquals(ComputerUseFailureCategory.OFFSCREEN_NEEDS_SCROLL.label, recoverState.failureCategoryLabel)

        indicator.shutdown()
    }

    // --- Fakes ---

    private class SequenceScreenBridge(
        private val sequence: MutableList<ScreenObservation>
    ) : ScreenObservationBridge {
        private var idx = 0
        override fun currentObservation(): ScreenObservation {
            val item = sequence.getOrElse(idx) { sequence.last() }
            if (idx < sequence.lastIndex) idx++
            return item
        }
        override suspend fun awaitWindowChange(afterSequence: Long, timeoutMillis: Long): AccessibilityWindowChangeSignal? =
            AccessibilityWindowChangeSignal(
                packageName = currentObservation().activePackageName,
                eventType = 32,
                timestampMillis = System.currentTimeMillis(),
                sequence = afterSequence + 1
            )
        override fun latestEventSequence(): Long = idx.toLong()
    }

    private class FakeMediaProjectionBackend(
        private val active: Boolean
    ) : ScreenshotCaptureBackend {
        val createdBitmaps = mutableListOf<Bitmap>()
        var volatileCalls = 0
        override val state: StateFlow<MediaProjectionSessionSnapshot> = MutableStateFlow(
            MediaProjectionSessionSnapshot(
                consentState = if (active) MediaProjectionConsentState.GRANTED_ACTIVE else MediaProjectionConsentState.NOT_GRANTED,
                isSessionActive = active
            )
        )
        override fun isMediaProjectionSupported(): Boolean = true
        override fun createScreenCaptureIntent(): Intent? = null
        override fun onConsentResult(resultCode: Int, data: Intent?): Boolean = active
        override fun stopSession(revokedBySystem: Boolean) = Unit
        override suspend fun captureScreenshot(includeBase64: Boolean): ScreenshotCaptureOutcome =
            ScreenshotCaptureOutcome(success = active, message = if (active) "Captured" else "Not active")
        override suspend fun captureVolatileFrameForAnalysis(includeVisionBase64: Boolean): VolatileScreenFrame? {
            if (!active) return null
            volatileCalls++
            val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            createdBitmaps.add(bmp)
            return VolatileScreenFrame(bmp, 16, 16, visionJpegBase64 = if (includeVisionBase64) "ZmFrZQ==" else null)
        }
    }

    private class FakeInteractionBackend : InteractionBackend {
        var swiped = false
        override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult {
            return when (val g = UiElementGroundingEngine.resolve(cachedObservation, spec)) {
                is GroundingOutcome.Resolved -> InteractionDispatchResult(true, "Tapped ${g.node.text}", g.node.text, explanation = g.explanation)
                is GroundingOutcome.Ambiguous -> InteractionDispatchResult(false, g.reason, errorCode = "AMBIGUOUS_TARGET", ambiguousCandidates = g.candidates, explanation = g.explanation)
                is GroundingOutcome.LowConfidence -> InteractionDispatchResult(false, g.reason, errorCode = "LOW_CONFIDENCE_MATCH", ambiguousCandidates = g.candidates, explanation = g.explanation)
                is GroundingOutcome.StaleSnapshot -> InteractionDispatchResult(false, g.reason, errorCode = "STALE_SNAPSHOT")
                is GroundingOutcome.NotFound -> InteractionDispatchResult(false, g.reason, errorCode = "TARGET_NOT_FOUND")
            }
        }
        override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult =
            InteractionDispatchResult(true, "Long pressed")
        override suspend fun tapCoordinates(x: Float, y: Float): InteractionDispatchResult =
            InteractionDispatchResult(true, "Tapped ($x,$y)")
        override suspend fun swipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long): InteractionDispatchResult {
            swiped = true
            return InteractionDispatchResult(true, "Swiped")
        }
        override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation): InteractionDispatchResult =
            InteractionDispatchResult(true, "Typed '$text'")
        override suspend fun editTextField(spec: TargetResolutionSpec?, mode: TextEditMode, text: String?, cachedObservation: ScreenObservation): InteractionDispatchResult =
            InteractionDispatchResult(true, "Edited ($mode)")
        override suspend fun scroll(direction: ScrollDirection, targetViewId: String?): InteractionDispatchResult =
            InteractionDispatchResult(true, "Scrolled ${direction.apiValue}")
        override suspend fun pressBack(): InteractionDispatchResult = InteractionDispatchResult(true, "Back")
        override suspend fun pressHome(): InteractionDispatchResult = InteractionDispatchResult(true, "Home")
        override suspend fun pressEnter(): InteractionDispatchResult = InteractionDispatchResult(true, "Enter")
    }

    private class FakePermissionManager(
        context: Context,
        private val accState: AccessibilityRuntimeState
    ) : AndroidPermissionManager(context) {
        override fun inspect(): PermissionInspectionSnapshot = PermissionInspectionSnapshot(
            accessibilityState = accState,
            accessibilityReason = "Enabled in test",
            isServiceEnabledInSettings = true,
            isServiceConnected = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            isServiceInterrupted = false,
            isDeviceLocked = false,
            canPerformGestures = true,
            canRetrieveWindowContent = true,
            canPostNotifications = true,
            hasBrowserHandler = true,
            launchableAppDiscoveryAvailable = true,
            mediaProjectionState = MediaProjectionConsentState.GRANTED_ACTIVE,
            mediaProjectionReason = "Granted",
            canDrawOverlays = true
        )
    }

    private class FakeOverlayHost : FloatingOverlayHost {
        var latestState: FloatingIndicatorUiState? = null
        override fun showOrUpdate(state: FloatingIndicatorUiState) { latestState = state }
        override fun dismiss() { latestState = null }
    }
}
