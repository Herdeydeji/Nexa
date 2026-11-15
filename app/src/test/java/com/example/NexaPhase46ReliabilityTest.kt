package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.view.accessibility.AccessibilityEvent
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.example.android.accessibility.AccessibilityInteractionController
import com.example.android.accessibility.AccessibilityLifecycleSnapshot
import com.example.android.accessibility.AccessibilityWindowChangeSignal
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.InteractionBackend
import com.example.android.accessibility.InteractionDispatchResult
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.accessibility.ScreenObservationBridge
import com.example.android.accessibility.TargetResolutionOutcome
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.notifications.NexaTaskNotificationManager
import com.example.android.overlay.FloatingAgentOverlayService
import com.example.android.overlay.FloatingIndicatorController
import com.example.android.overlay.FloatingIndicatorUiState
import com.example.android.overlay.FloatingIndicatorVisualState
import com.example.android.overlay.FloatingOverlayBounds
import com.example.android.overlay.FloatingOverlayHost
import com.example.android.runtime.AndroidActionRuntime
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.runtime.PermissionInspectionSnapshot
import com.example.data.local.NexaDatabase
import com.example.data.local.NexaTypeConverters
import com.example.domain.agent.AgentRunController
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.ProgressCheckOutcome
import com.example.domain.agent.ProviderStreamCaller
import com.example.domain.agent.TaskProgressMonitor
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppLaunchStatus
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.FloatingIndicatorMode
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ResumableTaskSnapshot
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolCallResult
import com.example.domain.model.ToolExecutionStatus
import com.example.domain.model.UiBounds
import com.example.domain.model.YouTubePlayStatus
import com.example.domain.provider.ProviderStreamEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaPhase46ReliabilityTest {

    @After
    fun tearDown() {
        NexaAccessibilityService.updateTestStateForTesting(AccessibilityLifecycleSnapshot())
    }

    private class FakePermissionManager(
        context: Context,
        var accState: AccessibilityRuntimeState = AccessibilityRuntimeState.SERVICE_ENABLED
    ) : AndroidPermissionManager(context) {
        override fun inspect(): PermissionInspectionSnapshot = PermissionInspectionSnapshot(
            accessibilityState = accState,
            accessibilityReason = "Test accessibility state",
            isServiceEnabledInSettings = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            isServiceConnected = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            isServiceInterrupted = false,
            isDeviceLocked = false,
            canPerformGestures = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            canRetrieveWindowContent = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            canPostNotifications = true,
            hasBrowserHandler = true,
            launchableAppDiscoveryAvailable = true,
            canDrawOverlays = true
        )

        override fun isAccessibilityReady(): Boolean = accState == AccessibilityRuntimeState.SERVICE_ENABLED
    }

    private class FakeScreenBridge(
        var current: ScreenObservation,
        var onWaitChange: (() -> ScreenObservation)? = null
    ) : ScreenObservationBridge {
        private var seq = 1L
        override fun currentObservation(): ScreenObservation = current

        override suspend fun awaitWindowChange(
            afterSequence: Long,
            timeoutMillis: Long
        ): AccessibilityWindowChangeSignal? {
            val updater = onWaitChange ?: return null
            delay(10)
            current = updater()
            seq++
            return AccessibilityWindowChangeSignal(
                packageName = current.activePackageName,
                eventType = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                timestampMillis = System.currentTimeMillis(),
                sequence = seq
            )
        }

        override fun latestEventSequence(): Long = seq
    }

    private fun installFakeApp(
        context: Context,
        pkg: String,
        label: String,
        activityName: String = "$pkg.MainActivity",
        launchable: Boolean = true,
        supportsHttpView: Boolean = false
    ) {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName(pkg, activityName)
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = pkg
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = label
                }
            }
        )
        shadowPm.addActivityIfNotPresent(component)
        if (launchable) {
            shadowPm.addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
            )
        }
        if (supportsHttpView) {
            shadowPm.addIntentFilterForActivity(
                component,
                IntentFilter(Intent.ACTION_VIEW).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    addDataScheme("https")
                    addDataAuthority("www.youtube.com", null)
                }
            )
        }
    }

    private fun sampleObservation(
        pkg: String,
        nodes: List<ObservedUiNode> = emptyList(),
        sig: String = "sig_${pkg}_${nodes.size}_${nodes.joinToString { it.text.orEmpty() }}",
        windowTitle: String = pkg,
        windowType: String = "application"
    ) = ScreenObservation(
        timestampMillis = 1_750_000_000_000L,
        activePackageName = pkg,
        windowTitle = windowTitle,
        windowType = windowType,
        isInteractive = true,
        isRestrictedWindow = false,
        nodes = nodes,
        totalNodesVisited = nodes.size,
        observationError = null,
        hierarchySignature = sig
    )

    private fun toolProvider() = ProviderConfig(
        providerType = AiProviderType.OPENROUTER,
        hasApiKey = true,
        maskedApiKey = "••••test",
        baseUrl = AiProviderType.OPENROUTER.defaultBaseUrl,
        selectedModelId = "inclusionai/ling-3.2-flash:free",
        isDefault = true,
        availableModels = listOf(
            AiModelInfo(
                id = "inclusionai/ling-3.2-flash:free",
                displayName = "Ling 3.2 Flash",
                contextWindowLabel = "128K",
                contextLengthTokens = 128_000,
                supportsVision = false,
                supportsTools = true,
                isCatalogVerified = true
            )
        )
    )

    // --- 1. Unified App Inventory, Exact/Normalized/Ambiguous Resolution & Pagination ---

    @Test
    fun `shared inventory resolves YouTube by display name and package ID, detects ambiguity, and paginates large app lists`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installFakeApp(context, "com.google.android.youtube", "YouTube", supportsHttpView = true)
        installFakeApp(context, "com.demo.notes.alpha", "Note Vault Pro")
        installFakeApp(context, "com.demo.notes.beta", "Note Vault Lite")
        for (i in 1..65) {
            installFakeApp(context, "com.vendor.bulkapp$i", "Bulk Utility $i")
        }

        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        assertFalse(pkgInfo.requestedPermissions.orEmpty().contains("android.permission.QUERY_ALL_PACKAGES"))

        val discovery = AndroidAppDiscovery(context)

        // Priority 1 & 2: display name (with whitespace/casing) and exact package name both resolve to YouTube
        val byName = discovery.findApp("YouTube")
        val byWhitespace = discovery.findApp("  youtube ")
        val byPkg = discovery.findApp("com.google.android.youtube")
        assertTrue(byName is AppLookupOutcome.Found)
        assertTrue(byWhitespace is AppLookupOutcome.Found)
        assertTrue(byPkg is AppLookupOutcome.Found)
        assertEquals("com.google.android.youtube", (byName as AppLookupOutcome.Found).app.packageName)
        assertEquals("YouTube", byName.app.appName)
        assertTrue(byName.app.isLaunchable)

        // Same inventory powers list_installed_apps
        val listedYouTube = discovery.listInstalledApps(query = "youtube")
        assertTrue(listedYouTube.apps.any { it.packageName == "com.google.android.youtube" && it.appName == "YouTube" })

        // Pagination across > 59 discovered apps
        val page1 = discovery.listInstalledApps(limit = 25, offset = 0)
        assertTrue(page1.totalDiscovered >= 68)
        assertEquals(25, page1.apps.size)
        assertTrue(page1.hasMore)
        assertEquals(25, page1.nextOffset)

        val page2 = discovery.listInstalledApps(limit = 25, offset = page1.nextOffset!!)
        assertEquals(25, page2.apps.size)
        assertTrue(page1.apps.map { it.packageName }.intersect(page2.apps.map { it.packageName }.toSet()).isEmpty())

        // Priority 4: Ambiguity returns candidates cleanly without guessing
        val ambiguous = discovery.findApp("Note Vault")
        assertTrue("Expected ambiguity for 'Note Vault', got $ambiguous", ambiguous is AppLookupOutcome.Ambiguous)
        val candidates = (ambiguous as AppLookupOutcome.Ambiguous).candidates.map { it.packageName }.toSet()
        assertTrue(candidates.contains("com.demo.notes.alpha"))
        assertTrue(candidates.contains("com.demo.notes.beta"))

        // Missing app
        assertTrue(discovery.findApp("NonExistentGhostApp999") is AppLookupOutcome.NotFound)

        // Ambiguity in AndroidAppLauncher returns structured APP_AMBIGUOUS instead of launching
        val perm = FakePermissionManager(context)
        val bridge = FakeScreenBridge(sampleObservation("com.example"))
        val verifier = AndroidActionVerifier(AndroidScreenObserver(bridge), settleDelayMillis = 10L)
        var launchedCount = 0
        val launcher = AndroidAppLauncher(context, discovery, perm, verifier) { launchedCount++ }
        val ambLaunch = launcher.launch("Note Vault")
        assertFalse(ambLaunch.success)
        assertEquals("APP_AMBIGUOUS", ambLaunch.errorCode)
        assertTrue(ambLaunch.requiresUserAction)
        assertEquals(0, launchedCount)
    }

    // --- 2. Transsion AppLock, System Dialog & Loading Foreground Verification ---

    @Test
    fun `launcher classifies Transsion AppLock, dialogs, and loading screens without marking foreground verified`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installFakeApp(context, "com.google.android.youtube", "YouTube")

        val discovery = AndroidAppDiscovery(context)
        val perm = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_ENABLED)

        // Case A: Blocked by com.transsion.applock
        val appLockBridge = FakeScreenBridge(
            current = sampleObservation(
                pkg = AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE,
                windowTitle = "AppLock"
            )
        )
        val verifierLock = AndroidActionVerifier(AndroidScreenObserver(appLockBridge), settleDelayMillis = 10L)
        val launcherLock = AndroidAppLauncher(context, discovery, perm, verifierLock) {}
        val lockOutcome = launcherLock.launch("YouTube")
        assertFalse(lockOutcome.success)
        assertTrue(lockOutcome.launchIntentDispatched)
        assertFalse(lockOutcome.foregroundVerified)
        assertEquals(AppLaunchStatus.BLOCKED_BY_APPLOCK, lockOutcome.launchStatus)
        assertEquals(AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE, lockOutcome.observedForegroundPackage)
        assertEquals("BLOCKED_BY_APPLOCK", lockOutcome.errorCode)
        assertTrue(lockOutcome.requiresUserAction)
        assertEquals("WAIT_FOR_USER_UNLOCK", lockOutcome.recoveryAction)
        assertEquals("Unlock YouTube", lockOutcome.userTitle)
        assertTrue(lockOutcome.userMessage.orEmpty().contains("Unlock it on your phone. Nexa will continue automatically when access is available."))

        // Case B: Blocked by permission/system dialog
        val dialogBridge = FakeScreenBridge(
            current = sampleObservation(pkg = "com.android.permissioncontroller", windowType = "system")
        )
        val verifierDialog = AndroidActionVerifier(AndroidScreenObserver(dialogBridge), settleDelayMillis = 10L)
        val dialogOutcome = AndroidAppLauncher(context, discovery, perm, verifierDialog) {}.launch("YouTube")
        assertFalse(dialogOutcome.success)
        assertFalse(dialogOutcome.foregroundVerified)
        assertEquals(AppLaunchStatus.BLOCKED_BY_DIALOG, dialogOutcome.launchStatus)

        // Case C: Launcher/loading screen remains foreground
        val loadingBridge = FakeScreenBridge(
            current = sampleObservation(pkg = "com.transsion.hios")
        )
        val verifierLoading = AndroidActionVerifier(AndroidScreenObserver(loadingBridge), settleDelayMillis = 10L)
        val loadingOutcome = AndroidAppLauncher(context, discovery, perm, verifierLoading) {}.launch("YouTube")
        assertFalse(loadingOutcome.success)
        assertFalse(loadingOutcome.foregroundVerified)
        assertEquals(AppLaunchStatus.APP_LOADING, loadingOutcome.launchStatus)
    }

    // --- 3. AppLock Auto-Watch Auto-Resume & Timeout Manual Continue on Exact Same Task ---

    @Test
    fun `orchestrator waits on AppLock without relaunching and auto-resumes same taskId when unlocked`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installFakeApp(context, "com.google.android.youtube", "YouTube")

        val discovery = AndroidAppDiscovery(context)
        val perm = FakePermissionManager(context)
        val fgSequence = listOf(
            AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE,
            AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE,
            "com.google.android.youtube"
        )
        val fgChecks = AtomicInteger(0)
        val currentFgProvider = {
            val idx = fgChecks.getAndIncrement().coerceAtMost(fgSequence.lastIndex)
            fgSequence[idx]
        }

        val bridge = FakeScreenBridge(sampleObservation(AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE))
        val verifier = AndroidActionVerifier(AndroidScreenObserver(bridge), settleDelayMillis = 5L)
        var launchDispatches = 0
        val launcher = AndroidAppLauncher(context, discovery, perm, verifier) { launchDispatches++ }
        val runtime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = launcher,
            screenObserver = AndroidScreenObserver(bridge),
            interactionController = AccessibilityInteractionController(noOpBackend()),
            actionVerifier = verifier,
            context = context
        )
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val execRuntime = ToolExecutionRuntime(registry, androidActionRuntime = runtime)
        val safety = DefaultSafetyPolicyManager()
        val runController = AgentRunController()

        var llmTurn = 0
        val caller = ProviderStreamCaller { _, req ->
            llmTurn++
            flow {
                if (llmTurn == 1) {
                    emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_launch", "launch_app", """{"app":"YouTube"}"""))))
                } else {
                    val toolMsg = req.messages.last { it.role == MessageRole.TOOL }.content
                    assertTrue(toolMsg.contains("USER_UNLOCKED_APP") || toolMsg.contains("com.google.android.youtube"))
                    emit(ProviderStreamEvent.Delta("YouTube is now unlocked and active."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }

        val settings = AppSettings(confirmationMode = ConfirmationMode.SENSITIVE_ONLY, appLockWaitTimeoutSeconds = 10)
        val caps = AndroidCapabilityInspector(context, perm, discovery).inspectAll(emptyMap())
        val tools = registry.getAvailableToolDefinitions(caps)
        val orchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = caller,
            toolRegistry = registry,
            safetyPolicyManager = safety,
            executionRuntime = execRuntime,
            foregroundPackageProvider = currentFgProvider,
            appLockPollIntervalMillis = 20L
        )

        val taskId = "task_applock_auto_resume"
        val control = runController.beginRun(taskId, "conv_applock")
        val events = orchestrator.runTurn(
            context = AgentExecutionContext(
                taskId = taskId,
                conversationId = "conv_applock",
                userPrompt = "Open YouTube",
                provider = toolProvider(),
                settings = settings,
                limits = settings.toExecutionLimits(128_000),
                availableTools = tools,
                systemPrompt = SystemPromptBuilder().build(settings, tools, true, caps)
            ),
            historySnapshot = listOf(ChatMessage("u1", "conv_applock", MessageRole.USER, "Open YouTube", 1L)),
            attachments = emptyList(),
            control = control
        ).toList()

        assertEquals("Locked app must NOT be relaunched repeatedly while waiting", 1, launchDispatches)
        val waitEvent = events.filterIsInstance<AgentEvent.WaitingForUserUnlock>().firstOrNull()
        assertNotNull(waitEvent)
        assertEquals(taskId, waitEvent!!.taskId)
        assertEquals("Unlock YouTube", waitEvent.request.title)
        assertTrue(events.any { it is AgentEvent.Resumed && it.taskId == taskId })
        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(taskId, completed.taskId)
        assertFalse(completed.partiallyCompleted)
        assertTrue(completed.finalText.contains("unlocked", ignoreCase = true))
    }

    @Test
    fun `AppLock wait times out to manual Continue without active-task timeout and resumes same taskId when user taps Continue`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installFakeApp(context, "com.google.android.youtube", "YouTube")

        val discovery = AndroidAppDiscovery(context)
        val perm = FakePermissionManager(context)
        var simulatedFg = AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE
        val bridge = FakeScreenBridge(sampleObservation(AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE))
        val verifier = AndroidActionVerifier(AndroidScreenObserver(bridge), settleDelayMillis = 5L)
        val launcher = AndroidAppLauncher(context, discovery, perm, verifier) {}
        val runtime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = launcher,
            screenObserver = AndroidScreenObserver(bridge),
            interactionController = AccessibilityInteractionController(noOpBackend()),
            actionVerifier = verifier,
            context = context
        )
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val execRuntime = ToolExecutionRuntime(registry, androidActionRuntime = runtime)
        val safety = DefaultSafetyPolicyManager()
        val runController = AgentRunController()

        var llmTurn = 0
        val caller = ProviderStreamCaller { _, _ ->
            llmTurn++
            flow {
                if (llmTurn == 1) {
                    emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_launch", "launch_app", """{"app":"YouTube"}"""))))
                } else {
                    emit(ProviderStreamEvent.Delta("Resumed after manual Continue and verified YouTube."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }

        // Keep taskTimeoutSeconds intentionally tiny (1s) so this also proves active-execution clock pauses during user waits!
        val settings = AppSettings(
            confirmationMode = ConfirmationMode.SENSITIVE_ONLY,
            appLockWaitTimeoutSeconds = 1,
            taskTimeoutSeconds = 1
        )
        val caps = AndroidCapabilityInspector(context, perm, discovery).inspectAll(emptyMap())
        val tools = registry.getAvailableToolDefinitions(caps)
        val orchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = caller,
            toolRegistry = registry,
            safetyPolicyManager = safety,
            executionRuntime = execRuntime,
            foregroundPackageProvider = { simulatedFg },
            appLockPollIntervalMillis = 30L
        )

        val taskId = "task_applock_manual_continue"
        val control = runController.beginRun(taskId, "conv_manual")
        val collected = mutableListOf<AgentEvent>()
        val collectorJob = launch(UnconfinedTestDispatcher(testScheduler)) {
            orchestrator.runTurn(
                context = AgentExecutionContext(
                    taskId = taskId,
                    conversationId = "conv_manual",
                    userPrompt = "Open YouTube",
                    provider = toolProvider(),
                    settings = settings,
                    limits = settings.toExecutionLimits(128_000),
                    availableTools = tools,
                    systemPrompt = SystemPromptBuilder().build(settings, tools, true, caps)
                ),
                historySnapshot = listOf(ChatMessage("u1", "conv_manual", MessageRole.USER, "Open YouTube", 1L)),
                attachments = emptyList(),
                control = control
            ).collect { ev ->
                runController.applyEvent(ev)
                collected.add(ev)
            }
        }

        // Wait until auto-watch (1s) transitions to UserUnlockTimeoutManualContinue
        val deadline = System.currentTimeMillis() + 5_000L
        while (collected.none { it is AgentEvent.UserUnlockTimeoutManualContinue } &&
            System.currentTimeMillis() < deadline
        ) {
            delay(25)
        }
        assertTrue(collected.any { it is AgentEvent.UserUnlockTimeoutManualContinue })
        assertTrue(runController.state.value.waitingForUnlock?.manualContinueRequired == true)

        // User unlocks YouTube and taps Continue -> exact same task resumes & completes
        simulatedFg = "com.google.android.youtube"
        assertTrue(runController.submitUnlockContinue())
        collectorJob.join()

        val completed = collected.filterIsInstance<AgentEvent.Completed>().single()
        assertEquals(taskId, completed.taskId)
        assertFalse(completed.partiallyCompleted)
    }

    // --- 4. play_on_youtube, Grounded Video Match, Playback Verification & open_url ---

    @Test
    fun `play_on_youtube encodes search URL dynamically, catches AppLock, prefers newer match, and distinguishes verified vs unverified playback`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        installFakeApp(context, "com.google.android.youtube", "YouTube", supportsHttpView = true)

        val discovery = AndroidAppDiscovery(context)
        val perm = FakePermissionManager(context)
        val capturedIntents = mutableListOf<Intent>()

        val searchResultsObs = sampleObservation(
            pkg = "com.google.android.youtube",
            nodes = listOf(
                ObservedUiNode(0, "title_old", "TextView", "Text", text = "Focus Beats LoFi Study 3 years ago", bounds = UiBounds(20, 200, 900, 320), clickable = true),
                ObservedUiNode(1, "title_new", "TextView", "Text", text = "Focus Beats LoFi Study 2 weeks ago", bounds = UiBounds(20, 360, 900, 480), clickable = true)
            ),
            sig = "yt_results_sig"
        )
        val playingObs = sampleObservation(
            pkg = "com.google.android.youtube",
            nodes = listOf(
                ObservedUiNode(0, "com.google.android.youtube:id/player_view", "FrameLayout", "View", contentDescription = "Pause video", bounds = UiBounds(0, 0, 1080, 620))
            ),
            sig = "yt_playing_sig"
        )
        val unverifiedObs = sampleObservation(
            pkg = "com.google.android.youtube",
            nodes = listOf(
                ObservedUiNode(0, "title_new", "TextView", "Text", text = "Focus Beats LoFi Study 2 weeks ago", bounds = UiBounds(20, 360, 900, 480))
            ),
            sig = "yt_unverified_sig"
        )

        val bridge = FakeScreenBridge(searchResultsObs)
        var tappedIndex = -1
        var nextPostTapObs = playingObs
        val backend = object : InteractionBackend {
            override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult {
                tappedIndex = spec.nodeIndex ?: -1
                bridge.current = nextPostTapObs
                return InteractionDispatchResult(true, "Tapped item $tappedIndex")
            }
            override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) = InteractionDispatchResult(true, "ok")
            override suspend fun tapCoordinates(x: Float, y: Float) = InteractionDispatchResult(true, "ok")
            override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) = InteractionDispatchResult(true, "ok")
            override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) = InteractionDispatchResult(true, "ok")
            override suspend fun pressBack() = InteractionDispatchResult(true, "ok")
            override suspend fun pressHome() = InteractionDispatchResult(true, "ok")
            override suspend fun pressEnter() = InteractionDispatchResult(true, "ok")
        }

        val verifier = AndroidActionVerifier(AndroidScreenObserver(bridge), settleDelayMillis = 10L)
        var audioActive = false
        val runtime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = AndroidAppLauncher(context, discovery, perm, verifier),
            screenObserver = AndroidScreenObserver(bridge),
            interactionController = AccessibilityInteractionController(backend),
            actionVerifier = verifier,
            context = context,
            externalIntentStarter = { intent -> capturedIntents.add(intent) },
            audioActiveChecker = { audioActive }
        )

        // 1. Valid playback verification + newer upload tiebreak
        val started = runtime.playOnYouTube("Focus Beats LoFi")
        assertEquals(YouTubePlayStatus.PLAYBACK_STARTED, started.status)
        assertEquals(1, tappedIndex) // "2 weeks ago" beat "3 years ago"
        assertEquals("com.google.android.youtube", capturedIntents.last().`package`)
        assertEquals(Uri.parse("https://www.youtube.com/results?search_query=Focus+Beats+LoFi"), capturedIntents.last().data)

        // 2. Unverified playback when neither player controls nor audio active
        bridge.current = searchResultsObs
        nextPostTapObs = unverifiedObs
        audioActive = false
        val unverified = runtime.playOnYouTube("Focus Beats LoFi")
        assertEquals(YouTubePlayStatus.PLAYBACK_UNVERIFIED, unverified.status)

        // 3. Blocked by Transsion AppLock
        bridge.current = sampleObservation(AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE, windowTitle = "AppLock")
        val locked = runtime.playOnYouTube("Focus Beats LoFi")
        assertEquals(YouTubePlayStatus.BLOCKED_BY_APPLOCK, locked.status)

        // 4. open_url validation & dispatch
        val badUrl = runtime.openUrl("javascript:alert(1)")
        assertFalse(badUrl.success)
        assertEquals("INVALID_URL", badUrl.errorCode)

        bridge.current = sampleObservation("com.google.android.youtube")
        val goodUrl = runtime.openUrl("https://www.youtube.com")
        assertTrue(goodUrl.success)
    }

    // --- 5. UI Grounding: type_text Editable Readback & Ambiguous UI Target ---

    @Test
    fun `type_text fails verification without editable field readback and ambiguous node lookup returns Ambiguous`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = FakeScreenBridge(
            sampleObservation(
                pkg = "com.demo",
                nodes = listOf(
                    ObservedUiNode(0, "input_box", "EditText", "TextField", hintText = "Type here", bounds = UiBounds(0, 0, 500, 80), editable = true),
                    ObservedUiNode(1, "btn_a", "Button", "Button", text = "Continue", bounds = UiBounds(0, 100, 300, 180), clickable = true),
                    ObservedUiNode(2, "btn_b", "Button", "Button", text = "Continue", bounds = UiBounds(0, 200, 300, 280), clickable = true)
                ),
                sig = "initial_sig"
            )
        )
        val verifier = AndroidActionVerifier(AndroidScreenObserver(bridge), settleDelayMillis = 10L)

        // Ambiguous target detection
        val ambOutcome = AccessibilityInteractionController.resolveTargetNodeChecked(
            bridge.current,
            TargetResolutionSpec(text = "Continue")
        )
        assertTrue(ambOutcome is TargetResolutionOutcome.Ambiguous)

        // Failed text readback: backend pretends success, but observed EditText never contains "hello"
        val noTextBackend = object : InteractionBackend {
            override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) = InteractionDispatchResult(true, "ok")
            override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) = InteractionDispatchResult(true, "ok")
            override suspend fun tapCoordinates(x: Float, y: Float) = InteractionDispatchResult(true, "ok")
            override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) =
                InteractionDispatchResult(true, "Dispatched IME")
            override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) = InteractionDispatchResult(true, "ok")
            override suspend fun pressBack() = InteractionDispatchResult(true, "ok")
            override suspend fun pressHome() = InteractionDispatchResult(true, "ok")
            override suspend fun pressEnter() = InteractionDispatchResult(true, "ok")
        }
        val perm = FakePermissionManager(context)
        val discovery = AndroidAppDiscovery(context)
        val runtime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = AndroidAppLauncher(context, discovery, perm, verifier),
            screenObserver = AndroidScreenObserver(bridge),
            interactionController = AccessibilityInteractionController(noTextBackend),
            actionVerifier = verifier
        )
        val unverifiedType = runtime.typeText(TargetResolutionSpec(viewId = "input_box"), "hello world")
        assertFalse("type_text must NOT be marked verified without editable text readback", unverifiedType.success)
        assertEquals("VERIFICATION_INCONCLUSIVE", unverifiedType.errorCode)
    }

    // --- 6. Loop Prevention & Honest Partial Verification ---

    @Test
    fun `TaskProgressMonitor and orchestrator halt repeated no-progress observe loops and report PARTIALLY_COMPLETED`() = runTest {
        val monitor = TaskProgressMonitor("Open YouTube", noProgressLimit = 3)
        val sameScreenJson = JSONObject()
            .put("activePackageName", "com.android.launcher")
            .put("hierarchySignature", "static_launcher_sig")
            .put("nodes", org.json.JSONArray())
        val call = ToolCall("c1", "observe_screen", "{}")
        val res = ToolCallResult(
            callId = "c1",
            toolName = "observe_screen",
            status = ToolExecutionStatus.SUCCEEDED,
            outputJson = sameScreenJson.toString(),
            displaySummary = "Observed"
        )

        val o1 = monitor.recordToolResult(call, res)
        assertTrue(o1 is ProgressCheckOutcome.Progressed) // first observation sets baseline signature
        assertTrue(monitor.recordToolResult(call, res) is ProgressCheckOutcome.NoProgressCycle)
        assertTrue(monitor.recordToolResult(call, res) is ProgressCheckOutcome.NoProgressCycle)
        val o4 = monitor.recordToolResult(call, res)
        assertTrue("Expected HaltNoProgress on 3rd unchanged observation cycle", o4 is ProgressCheckOutcome.HaltNoProgress)

        // Full orchestrator loop guard test
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = FakeScreenBridge(sampleObservation("com.android.launcher", sig = "unchanged_launcher"))
        val verifier = AndroidActionVerifier(AndroidScreenObserver(bridge), settleDelayMillis = 5L)
        val perm = FakePermissionManager(context)
        val discovery = AndroidAppDiscovery(context)
        val runtime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = AndroidAppLauncher(context, discovery, perm, verifier),
            screenObserver = AndroidScreenObserver(bridge),
            interactionController = AccessibilityInteractionController(noOpBackend()),
            actionVerifier = verifier,
            context = context
        )
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val execRuntime = ToolExecutionRuntime(registry, androidActionRuntime = runtime)
        var modelCalls = 0
        val loopingCaller = ProviderStreamCaller { _, _ ->
            modelCalls++
            flow {
                emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_$modelCalls", "observe_screen", "{}"))))
                emit(ProviderStreamEvent.Completed())
            }
        }
        val settings = AppSettings(noProgressRetryLimit = 3, confirmationMode = ConfirmationMode.SENSITIVE_ONLY)
        val caps = AndroidCapabilityInspector(context, perm, discovery).inspectAll(emptyMap())
        val tools = registry.getAvailableToolDefinitions(caps)
        val orchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = loopingCaller,
            toolRegistry = registry,
            safetyPolicyManager = DefaultSafetyPolicyManager(),
            executionRuntime = execRuntime
        )
        val events = orchestrator.runTurn(
            context = AgentExecutionContext(
                taskId = "task_loop_halt",
                conversationId = "conv_loop",
                userPrompt = "Keep watching screen forever",
                provider = toolProvider(),
                settings = settings,
                limits = settings.toExecutionLimits(128_000),
                availableTools = tools,
                systemPrompt = SystemPromptBuilder().build(settings, tools, true, caps)
            ),
            historySnapshot = listOf(ChatMessage("u1", "conv_loop", MessageRole.USER, "Watch", 1L)),
            attachments = emptyList()
        ).toList()

        val failed = events.filterIsInstance<AgentEvent.Failed>().single()
        assertTrue(failed.errorMessage.contains("infinite loop", ignoreCase = true))
        assertNotNull(failed.verification)
        assertFalse(failed.verification!!.objectiveVerified)
        assertTrue("Orchestrator must stop before hitting 12 maxSteps", modelCalls <= 4)

        // Honest partial completion when play_on_youtube returns PLAYBACK_UNVERIFIED
        installFakeApp(context, "com.google.android.youtube", "YouTube", supportsHttpView = true)
        val ytResults = sampleObservation(
            pkg = "com.google.android.youtube",
            nodes = listOf(
                ObservedUiNode(0, "title_1", "TextView", "Text", text = "Focus LoFi 1 week ago", bounds = UiBounds(20, 200, 900, 320), clickable = true)
            ),
            sig = "yt_results_unverified"
        )
        val ytBridge = FakeScreenBridge(ytResults)
        val ytVerifier = AndroidActionVerifier(AndroidScreenObserver(ytBridge), settleDelayMillis = 10L)
        val ytDiscovery = AndroidAppDiscovery(context)
        val ytRuntime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = ytDiscovery,
            appLauncher = AndroidAppLauncher(context, ytDiscovery, perm, ytVerifier),
            screenObserver = AndroidScreenObserver(ytBridge),
            interactionController = AccessibilityInteractionController(
                object : InteractionBackend {
                    override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult {
                        ytBridge.current = sampleObservation(pkg = "com.google.android.youtube", nodes = emptyList(), sig = "yt_no_player")
                        return InteractionDispatchResult(true, "Tapped item")
                    }
                    override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) = InteractionDispatchResult(true, "ok")
                    override suspend fun tapCoordinates(x: Float, y: Float) = InteractionDispatchResult(true, "ok")
                    override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) = InteractionDispatchResult(true, "ok")
                    override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) = InteractionDispatchResult(true, "ok")
                    override suspend fun pressBack() = InteractionDispatchResult(true, "ok")
                    override suspend fun pressHome() = InteractionDispatchResult(true, "ok")
                    override suspend fun pressEnter() = InteractionDispatchResult(true, "ok")
                }
            ),
            actionVerifier = ytVerifier,
            context = context,
            externalIntentStarter = {},
            audioActiveChecker = { false }
        )
        val ytRegistry = DefaultToolRegistry(context = context, actionRuntime = ytRuntime)
        val ytExecRuntime = ToolExecutionRuntime(ytRegistry, androidActionRuntime = ytRuntime)
        var ytTurn = 0
        val ytCaller = ProviderStreamCaller { _, _ ->
            ytTurn++
            flow {
                if (ytTurn == 1) {
                    emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c_yt", "play_on_youtube", """{"query":"Focus LoFi"}"""))))
                } else {
                    emit(ProviderStreamEvent.Delta("Opened search results and tapped top match, but playback could not be verified."))
                }
                emit(ProviderStreamEvent.Completed())
            }
        }
        val ytCaps = AndroidCapabilityInspector(context, perm, ytDiscovery).inspectAll(emptyMap())
        val ytTools = ytRegistry.getAvailableToolDefinitions(ytCaps)
        val ytOrchestrator = DefaultAutonomousAgentOrchestrator(
            streamCaller = ytCaller,
            toolRegistry = ytRegistry,
            safetyPolicyManager = DefaultSafetyPolicyManager(),
            executionRuntime = ytExecRuntime
        )
        val ytEvents = ytOrchestrator.runTurn(
            context = AgentExecutionContext(
                taskId = "task_yt_partial",
                conversationId = "conv_yt_partial",
                userPrompt = "Play Focus LoFi on YouTube",
                provider = toolProvider(),
                settings = settings,
                limits = settings.toExecutionLimits(128_000),
                availableTools = ytTools,
                systemPrompt = SystemPromptBuilder().build(settings, ytTools, true, ytCaps)
            ),
            historySnapshot = listOf(ChatMessage("u2", "conv_yt_partial", MessageRole.USER, "Play Focus LoFi", 1L)),
            attachments = emptyList()
        ).toList()

        val completed = ytEvents.filterIsInstance<AgentEvent.Completed>().single()
        assertTrue(completed.partiallyCompleted)
        assertEquals(AgentTaskStatus.PARTIALLY_COMPLETED, completed.taskSummary?.status)
        assertNotNull(completed.verification)
        assertFalse(completed.verification!!.objectiveVerified)
        assertEquals(1, completed.verification!!.unverifiedCompletedCount)
    }

    // --- 7. Floating Agent Indicator Visibility, Fallback, Bounds & Service ---

    @Test
    fun `floating indicator auto-shows in external apps, uses notification fallback when denied, clamps bounds, and stops cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runController = AgentRunController()
        val settingsFlow = MutableStateFlow(AppSettings(floatingIndicatorMode = FloatingIndicatorMode.AUTO_EXTERNAL_APPS))
        var permissionGranted = true
        val shownStates = mutableListOf<FloatingIndicatorUiState>()
        var dismissCount = 0
        val fakeHost = object : FloatingOverlayHost {
            override fun showOrUpdate(state: FloatingIndicatorUiState) {
                shownStates.add(state)
            }
            override fun dismiss() {
                dismissCount++
            }
        }

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val controller = FloatingIndicatorController(
            context = context,
            agentRunController = runController,
            settingsFlow = settingsFlow,
            taskNotificationManager = NexaTaskNotificationManager(context, FakePermissionManager(context)),
            overlayPermissionProvider = { permissionGranted },
            overlayHost = fakeHost,
            scope = CoroutineScope(dispatcher)
        )
        val collectJob = launch(dispatcher) {
            controller.uiState.collect {}
        }

        // Hidden while Nexa is foreground
        runController.beginRun("task_ov_1", "conv_ov")
        runController.applyEvent(AgentEvent.StatusChanged("task_ov_1", AgentRunStatus.EXECUTING_TOOL, "Running YouTube"))
        controller.setCurrentObjective("Play YouTube video")
        controller.setNexaForeground(true)
        assertFalse(controller.uiState.value.isVisible)

        // Automatically shows when user/task leaves Nexa for another app
        controller.setNexaForeground(false)
        assertTrue(controller.uiState.value.isVisible)
        assertEquals(FloatingIndicatorVisualState.EXECUTING, controller.uiState.value.visualState)
        assertFalse(controller.uiState.value.usingNotificationFallback)

        // Permission denied switches cleanly to notification fallback and dismisses overlay
        permissionGranted = false
        controller.notifyOverlayPermissionChanged()
        assertFalse(controller.uiState.value.isVisible)
        assertTrue(controller.uiState.value.usingNotificationFallback)
        assertTrue(dismissCount > 0)

        // Bounds clamping keeps pill/card strictly onscreen
        val clamped = FloatingOverlayBounds.clampPosition(
            x = -200,
            y = 5000,
            viewWidth = 200,
            viewHeight = 80,
            screenWidth = 1080,
            screenHeight = 2400,
            marginPx = 12
        )
        assertEquals(12, clamped.first)
        assertEquals(2400 - 80 - 12, clamped.second)

        // Service lifecycle smoke test via Robolectric (no ViewTreeLifecycleOwner crash or duplicate window)
        val serviceCtrl = Robolectric.buildService(FloatingAgentOverlayService::class.java).create()
        serviceCtrl.startCommand(0, 0)
        serviceCtrl.startCommand(0, 0)
        serviceCtrl.destroy()

        runController.cancelActiveRun()
        runController.finishRun()
        assertFalse(controller.uiState.value.isVisible)
        controller.shutdown()
        collectJob.cancel()
    }

    // --- 8. Room Migration v3 -> v4 & Interrupted AppLock Wait Reconciliation ---

    @Test
    fun `room migration 3 to 4 preserves tasks and reconciles interrupted AppLock wait snapshot to PAUSED`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "mig34_test_${System.nanoTime()}.db"
        val factory = FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, isSamplePreview INTEGER NOT NULL, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, lastMessagePreview TEXT NOT NULL)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS messages (id TEXT NOT NULL PRIMARY KEY, conversationId TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL, createdAtMillis INTEGER NOT NULL, executionPlanJson TEXT, taskSummaryJson TEXT, attachmentsJson TEXT, isPreviewMessage INTEGER NOT NULL, status TEXT NOT NULL, errorMessage TEXT, providerLabel TEXT, modelLabel TEXT, promptTokens INTEGER, completionTokens INTEGER, totalTokens INTEGER, toolCallsJson TEXT, toolCallId TEXT, toolName TEXT, verificationJson TEXT, FOREIGN KEY(conversationId) REFERENCES conversations(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_conversationId ON messages (conversationId)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS agent_tasks (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, prompt TEXT NOT NULL, status TEXT NOT NULL, isSampleData INTEGER NOT NULL, providerLabel TEXT NOT NULL, modelLabel TEXT NOT NULL, targetApp TEXT, requiredCapabilitiesJson TEXT NOT NULL, failureReason TEXT, createdAtMillis INTEGER NOT NULL, updatedAtMillis INTEGER NOT NULL, summaryMessage TEXT, verificationJson TEXT)")
                        db.execSQL("CREATE TABLE IF NOT EXISTS task_steps (id TEXT NOT NULL PRIMARY KEY, taskId TEXT NOT NULL, stepOrder INTEGER NOT NULL, title TEXT NOT NULL, detail TEXT NOT NULL, state TEXT NOT NULL, toolName TEXT, timestampLabel TEXT, kind TEXT NOT NULL DEFAULT 'EXECUTED', FOREIGN KEY(taskId) REFERENCES agent_tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS index_task_steps_taskId ON task_steps (taskId)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build()
        )
        try {
            val raw = helper.writableDatabase
            raw.execSQL("INSERT INTO agent_tasks VALUES ('t_v3','Open YouTube','Open YouTube','WAITING_FOR_USER_UNLOCK',0,'OpenRouter','ling','YouTube','[\"launch_app\"]',NULL,100,100,NULL,NULL)")
            raw.close()
            helper.close()

            val db = Room.databaseBuilder(context, NexaDatabase::class.java, dbName)
                .addMigrations(NexaDatabase.MIGRATION_3_4, NexaDatabase.MIGRATION_4_5)
                .allowMainThreadQueries()
                .build()
            val converters = NexaTypeConverters()
            val snapJson = converters.encodeResumableSnapshot(
                ResumableTaskSnapshot(
                    taskId = "t_v3",
                    conversationId = "c_1",
                    prompt = "Open YouTube",
                    targetAppName = "YouTube",
                    targetPackage = "com.google.android.youtube",
                    reason = "Unlock YouTube"
                )
            )!!
            db.taskDao().updateResumableSnapshot("t_v3", snapJson, 200L)
            db.initializePhase2DataIfNeeded(converters)
            val loaded = db.taskDao().getActiveLiveTasks()
            assertEquals(AgentTaskStatus.PAUSED, loaded.single { it.id == "t_v3" }.status)
            val cursor = db.openHelper.readableDatabase.query("SELECT status, resumableSnapshotJson FROM agent_tasks WHERE id = 't_v3'")
            assertTrue(cursor.moveToFirst())
            assertEquals(AgentTaskStatus.PAUSED.name, cursor.getString(0))
            assertTrue(cursor.getString(1).contains("com.google.android.youtube"))
            cursor.close()
            db.close()
        } finally {
            context.deleteDatabase(dbName)
        }
    }

    private fun noOpBackend() = object : InteractionBackend {
        override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
            InteractionDispatchResult(true, "ok")
        override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
            InteractionDispatchResult(true, "ok")
        override suspend fun tapCoordinates(x: Float, y: Float) =
            InteractionDispatchResult(true, "ok")
        override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) =
            InteractionDispatchResult(true, "ok")
        override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) =
            InteractionDispatchResult(true, "ok")
        override suspend fun pressBack() = InteractionDispatchResult(true, "ok")
        override suspend fun pressHome() = InteractionDispatchResult(true, "ok")
        override suspend fun pressEnter() = InteractionDispatchResult(true, "ok")
    }
}
