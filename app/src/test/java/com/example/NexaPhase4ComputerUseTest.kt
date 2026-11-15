package com.example

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.text.InputType
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.android.accessibility.AccessibilityInteractionController
import com.example.android.accessibility.AccessibilityLifecycleSnapshot
import com.example.android.accessibility.AccessibilityNodeInspector
import com.example.android.accessibility.AccessibilityWindowChangeSignal
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.InteractionBackend
import com.example.android.accessibility.InteractionDispatchResult
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.accessibility.RawNodeAdapter
import com.example.android.accessibility.ScreenObservationBridge
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.runtime.AndroidActionRuntime
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.runtime.PermissionInspectionSnapshot
import com.example.domain.agent.AgentControlHandle
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.ComputerUseTools
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.ProviderStreamCaller
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.agent.ToolPolicyEvaluation
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentExecutionLimits
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppLaunchOutcome
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.AppSettings
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.ChatMessage
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolExecutionStatus
import com.example.domain.model.UiBounds
import com.example.domain.provider.ProviderStreamEvent
import com.example.ui.connections.CapabilitiesTab
import com.example.ui.theme.NexaTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class NexaPhase4ComputerUseTest {

    @get:Rule
    val composeRule = createComposeRule()

    @After
    fun tearDownAccessibilityState() {
        NexaAccessibilityService.updateTestStateForTesting(AccessibilityLifecycleSnapshot())
    }

    // --- Test Fixtures ---

    private data class FakeNode(
        override val viewIdResourceName: String? = null,
        override val className: String? = "android.view.View",
        override val text: String? = null,
        override val contentDescription: String? = null,
        override val hintText: String? = null,
        override val bounds: UiBounds = UiBounds(0, 0, 100, 48),
        override val isClickable: Boolean = false,
        override val isEditable: Boolean = false,
        override val isScrollable: Boolean = false,
        override val isCheckable: Boolean = false,
        override val isChecked: Boolean = false,
        override val isFocused: Boolean = false,
        override val isEnabled: Boolean = true,
        override val isVisibleToUser: Boolean = true,
        override val isPassword: Boolean = false,
        override val inputType: Int = InputType.TYPE_NULL,
        val children: List<FakeNode> = emptyList()
    ) : RawNodeAdapter {
        override val childCount: Int get() = children.size
        override fun childAt(index: Int): RawNodeAdapter? = children.getOrNull(index)
    }

    private class FakePermissionManager(
        context: Context,
        var accState: AccessibilityRuntimeState = AccessibilityRuntimeState.SERVICE_ENABLED,
        var accReason: String = "Service enabled for tests.",
        var isLocked: Boolean = false
    ) : AndroidPermissionManager(context) {
        override fun inspect(): PermissionInspectionSnapshot = PermissionInspectionSnapshot(
            accessibilityState = accState,
            accessibilityReason = accReason,
            isServiceEnabledInSettings = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            isServiceConnected = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            isServiceInterrupted = false,
            isDeviceLocked = isLocked,
            canPerformGestures = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            canRetrieveWindowContent = accState == AccessibilityRuntimeState.SERVICE_ENABLED,
            canPostNotifications = true,
            hasBrowserHandler = true,
            launchableAppDiscoveryAvailable = true
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
            delay(15)
            current = updater()
            seq++
            return AccessibilityWindowChangeSignal(
                packageName = current.activePackageName,
                eventType = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                timestampMillis = System.currentTimeMillis(),
                sequence = seq
            )
        }

        override fun latestEventSequence(): Long = seq
    }

    private fun sampleObservation(
        pkg: String = "com.demo.video",
        nodes: List<ObservedUiNode>,
        sig: String = "sig_${pkg}_${nodes.size}_${nodes.firstOrNull()?.text.orEmpty()}",
        error: String? = null
    ) = ScreenObservation(
        timestampMillis = 1_750_000_000_000L,
        activePackageName = pkg,
        windowTitle = "MainWindow",
        isInteractive = error == null,
        isRestrictedWindow = error != null,
        nodes = nodes,
        totalNodesVisited = nodes.size,
        observationError = error,
        hierarchySignature = sig
    )

    private fun toolCapableProvider() = ProviderConfig(
        providerType = AiProviderType.OPENAI,
        hasApiKey = true,
        maskedApiKey = "••••test",
        baseUrl = AiProviderType.OPENAI.defaultBaseUrl,
        selectedModelId = "gpt-4.1",
        isDefault = true,
        availableModels = listOf(
            AiModelInfo(
                id = "gpt-4.1",
                displayName = "GPT-4.1",
                contextWindowLabel = "128K",
                contextLengthTokens = 128_000,
                supportsVision = false,
                supportsTools = true,
                isCatalogVerified = true
            )
        )
    )

    // --- 1. Manifest & Service Configuration Verification ---

    @Test
    fun `manifest declares NexaAccessibilityService and targeted queries without QUERY_ALL_PACKAGES`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pm = context.packageManager
        val pkgInfo = pm.getPackageInfo(
            context.packageName,
            PackageManager.GET_SERVICES or PackageManager.GET_PERMISSIONS
        )

        val permissions = pkgInfo.requestedPermissions?.toList().orEmpty()
        assertFalse("Must not request QUERY_ALL_PACKAGES", permissions.contains("android.permission.QUERY_ALL_PACKAGES"))
        assertTrue(permissions.contains(android.Manifest.permission.POST_NOTIFICATIONS))

        val serviceInfo = pkgInfo.services?.firstOrNull {
            it.name == NexaAccessibilityService::class.java.name
        }
        assertNotNull("NexaAccessibilityService must be declared in AndroidManifest.xml", serviceInfo)
        assertEquals(android.Manifest.permission.BIND_ACCESSIBILITY_SERVICE, serviceInfo!!.permission)

        // Verify XML config exists and parses
        val xmlParser = context.resources.getXml(R.xml.nexa_accessibility_service_config)
        assertNotNull(xmlParser)
    }

    // --- 2. App Discovery & Launcher Tests ---

    @Test
    fun `app discovery finds installed apps, handles missing and non-launchable packages`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = shadowOf(context.packageManager)

        // Install a launchable fake third-party app ("VideoHub")
        val launchComponent = ComponentName("com.example.videohub", "com.example.videohub.MainActivity")
        val launchPkgInfo = PackageInfo().apply {
            packageName = "com.example.videohub"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.example.videohub"
                name = "VideoHub"
                nonLocalizedLabel = "VideoHub"
            }
        }
        shadowPm.installPackage(launchPkgInfo)
        shadowPm.addActivityIfNotPresent(launchComponent)
        val mainFilter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        shadowPm.addIntentFilterForActivity(launchComponent, mainFilter)

        // Install a package with no launcher activity ("Background Helper")
        val nonLaunchPkg = PackageInfo().apply {
            packageName = "com.example.bghelper"
            applicationInfo = ApplicationInfo().apply {
                packageName = "com.example.bghelper"
                nonLocalizedLabel = "Background Helper"
            }
        }
        shadowPm.installPackage(nonLaunchPkg)

        val discovery = AndroidAppDiscovery(context)

        val listBatch = discovery.listLaunchableApps(query = "Video")
        assertTrue(listBatch.apps.any { it.packageName == "com.example.videohub" })

        val found = discovery.findApp("VideoHub")
        assertTrue(found is AppLookupOutcome.Found)
        assertEquals("com.example.videohub", (found as AppLookupOutcome.Found).app.packageName)

        val notLaunchable = discovery.findApp("com.example.bghelper")
        assertTrue(notLaunchable is AppLookupOutcome.NotLaunchable)

        val missing = discovery.findApp("NonExistentAppXYZ")
        assertTrue(missing is AppLookupOutcome.NotFound)
        assertTrue((missing as AppLookupOutcome.NotFound).visibilityRestricted)
    }

    @Test
    fun `app launcher dispatches explicit intent, catches ActivityNotFound, and distinguishes verified vs unverified foreground`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = shadowOf(context.packageManager)
        val comp = ComponentName("com.example.browserdemo", "com.example.browserdemo.BrowserActivity")
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.example.browserdemo"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.example.browserdemo"
                    nonLocalizedLabel = "BrowserDemo"
                }
            }
        )
        shadowPm.addActivityIfNotPresent(comp)
        shadowPm.addIntentFilterForActivity(
            comp,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )

        val discovery = AndroidAppDiscovery(context)
        val permDisabled = FakePermissionManager(context, accState = AccessibilityRuntimeState.PERMISSION_REQUIRED)
        val bridge = FakeScreenBridge(sampleObservation(pkg = "com.example.browserdemo", nodes = emptyList()))
        val verifier = AndroidActionVerifier(AndroidScreenObserver(bridge))

        var launchedIntent: Intent? = null
        val launcherUnverified = AndroidAppLauncher(
            context = context,
            appDiscovery = discovery,
            permissionManager = permDisabled,
            actionVerifier = verifier,
            activityStarter = { launchedIntent = it }
        )
        val unverifiedOutcome = launcherUnverified.launch("BrowserDemo")
        assertTrue(unverifiedOutcome.success)
        assertTrue(unverifiedOutcome.launchIntentDispatched)
        assertFalse("Must not claim foreground verification when accessibility is disabled", unverifiedOutcome.foregroundVerified)
        assertEquals("com.example.browserdemo", launchedIntent?.component?.packageName)

        // When accessibility is enabled, foreground package is verified
        val permEnabled = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_ENABLED)
        val launcherVerified = AndroidAppLauncher(
            context = context,
            appDiscovery = discovery,
            permissionManager = permEnabled,
            actionVerifier = verifier,
            activityStarter = {}
        )
        val verifiedOutcome = launcherVerified.launch("BrowserDemo")
        assertTrue(verifiedOutcome.success)
        assertTrue(verifiedOutcome.foregroundVerified)
        assertEquals("com.example.browserdemo", verifiedOutcome.observedForegroundPackage)

        // ActivityNotFoundException failure handled cleanly
        val blockedLauncher = AndroidAppLauncher(
            context = context,
            appDiscovery = discovery,
            permissionManager = permEnabled,
            actionVerifier = verifier,
            activityStarter = { throw ActivityNotFoundException("Not exported") }
        )
        val blockedOutcome = blockedLauncher.launch("BrowserDemo")
        assertFalse(blockedOutcome.success)
        assertEquals("LAUNCH_BLOCKED", blockedOutcome.errorCode)
    }

    // --- 3. Accessibility Service Lifecycle, Node Traversal, Redaction & Limits ---

    @Test
    fun `accessibility service lifecycle updates state transitions cleanly`() {
        val controller = Robolectric.buildService(NexaAccessibilityService::class.java).create()
        val service = controller.get()

        service.onAccessibilityEvent(
            android.view.accessibility.AccessibilityEvent.obtain().apply {
                eventType = android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                packageName = "com.example.player"
                text.add("Home")
            }
        )
        val activeState = NexaAccessibilityService.runtimeState.value
        assertTrue(activeState.isConnected)
        assertFalse(activeState.isInterrupted)
        assertEquals("com.example.player", activeState.activePackageName)
        assertEquals("Home", activeState.activeWindowTitle)

        service.onInterrupt()
        assertTrue(NexaAccessibilityService.runtimeState.value.isInterrupted)

        service.onUnbind(Intent())
        assertFalse(NexaAccessibilityService.runtimeState.value.isConnected)
    }

    @Test
    fun `node inspector enforces traversal bounds, role classification, duplicate collapse, and sensitive redaction`() {
        val inspector = AccessibilityNodeInspector(
            maxDepth = 5,
            maxVisitedNodes = 25,
            maxEmittedNodes = 10,
            maxTextLength = 24
        )
        val root = FakeNode(
            className = "android.widget.FrameLayout",
            children = listOf(
                FakeNode(
                    viewIdResourceName = "com.app:id/search_box",
                    className = "android.widget.EditText",
                    hintText = "Search videos",
                    text = "kotlin android tutorial very long query text",
                    isEditable = true,
                    isClickable = true,
                    bounds = UiBounds(10, 20, 500, 90)
                ),
                // Duplicate node at same bounds/role/text
                FakeNode(
                    viewIdResourceName = "com.app:id/search_box",
                    className = "android.widget.EditText",
                    hintText = "Search videos",
                    text = "kotlin android tutorial very long query text",
                    isEditable = true,
                    isClickable = true,
                    bounds = UiBounds(10, 20, 500, 90)
                ),
                // Password input
                FakeNode(
                    viewIdResourceName = "com.app:id/pin_input",
                    className = "android.widget.EditText",
                    text = "987654",
                    isEditable = true,
                    isPassword = true,
                    bounds = UiBounds(10, 100, 500, 170)
                ),
                // OTP semantic input
                FakeNode(
                    viewIdResourceName = "com.app:id/otp_code_entry",
                    className = "android.widget.EditText",
                    hintText = "Enter 6-digit OTP",
                    text = "442910",
                    isEditable = true,
                    inputType = InputType.TYPE_CLASS_NUMBER,
                    bounds = UiBounds(10, 180, 500, 250)
                ),
                FakeNode(
                    viewIdResourceName = "com.app:id/feed_list",
                    className = "androidx.recyclerview.widget.RecyclerView",
                    isScrollable = true,
                    bounds = UiBounds(0, 260, 1080, 1900)
                ),
                FakeNode(
                    viewIdResourceName = "com.app:id/invisible_node",
                    text = "Should not emit",
                    isVisibleToUser = false
                )
            )
        )

        val obs = inspector.inspectAdapter(root, "com.app", "Sign In")
        assertFalse(obs.isRestrictedWindow)
        // Duplicate collapsed, invisible ignored -> 4 emitted nodes
        assertEquals(4, obs.nodes.size)

        val searchNode = obs.nodes[0]
        assertEquals("SearchOrAddressField", searchNode.role)
        assertTrue(searchNode.text!!.length <= 24)
        assertTrue(searchNode.text!!.endsWith("…"))

        val pwNode = obs.nodes[1]
        assertEquals("PasswordField", pwNode.role)
        assertTrue(pwNode.isSensitiveRedacted)
        assertEquals(AccessibilityNodeInspector.REDACTED_VALUE, pwNode.text)

        val otpNode = obs.nodes[2]
        assertTrue(otpNode.isSensitiveRedacted)
        assertEquals(AccessibilityNodeInspector.REDACTED_VALUE, otpNode.text)

        val scrollNode = obs.nodes[3]
        assertEquals("ScrollContainer", scrollNode.role)
        assertTrue(scrollNode.scrollable)
    }

    // --- 4. Action Runtime, Verification, & Failure Handling ---

    @Test
    fun `action runtime enforces accessibility permission, resolves selectors, and verifies post-action state`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val initialNodes = listOf(
            ObservedUiNode(0, "search_btn", "ImageButton", "Button", contentDescription = "Search", bounds = UiBounds(900, 50, 1020, 150), clickable = true),
            ObservedUiNode(1, "query_field", "EditText", "SearchOrAddressField", hintText = "Search...", bounds = UiBounds(40, 50, 880, 150), editable = true, clickable = true)
        )
        val afterTapNodes = listOf(
            ObservedUiNode(0, "query_field", "EditText", "SearchOrAddressField", hintText = "Search...", bounds = UiBounds(40, 50, 880, 150), editable = true, focused = true)
        )
        val bridge = FakeScreenBridge(sampleObservation(nodes = initialNodes, sig = "before_sig"))
        val observer = AndroidScreenObserver(bridge)
        val verifier = AndroidActionVerifier(observer)

        val backend = object : InteractionBackend {
            override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult {
                val target = AccessibilityInteractionController.resolveTargetNode(cachedObservation, spec)
                    ?: return InteractionDispatchResult(false, "Target not found", errorCode = "TARGET_NOT_FOUND")
                bridge.current = sampleObservation(nodes = afterTapNodes, sig = "after_tap_sig")
                return InteractionDispatchResult(true, "Tapped ${target.contentDescription ?: target.viewId}")
            }

            override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                InteractionDispatchResult(true, "Long pressed")

            override suspend fun tapCoordinates(x: Float, y: Float): InteractionDispatchResult {
                bridge.current = sampleObservation(nodes = afterTapNodes, sig = "coord_sig")
                return InteractionDispatchResult(true, "Tapped coords")
            }

            override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation): InteractionDispatchResult {
                bridge.current = sampleObservation(
                    nodes = listOf(ObservedUiNode(0, "query_field", "EditText", "SearchOrAddressField", text = text, bounds = UiBounds(40, 50, 880, 150), editable = true, focused = true)),
                    sig = "typed_sig"
                )
                return InteractionDispatchResult(true, "Entered '$text'")
            }

            override suspend fun scroll(direction: ScrollDirection, targetViewId: String?): InteractionDispatchResult {
                bridge.current = sampleObservation(nodes = afterTapNodes, sig = "scrolled_${direction.apiValue}")
                return InteractionDispatchResult(true, "Scrolled ${direction.apiValue}")
            }

            override suspend fun pressBack(): InteractionDispatchResult {
                bridge.current = sampleObservation(pkg = "com.launcher", nodes = emptyList(), sig = "back_sig")
                return InteractionDispatchResult(true, "Back pressed")
            }

            override suspend fun pressHome(): InteractionDispatchResult {
                bridge.current = sampleObservation(pkg = "com.launcher", nodes = emptyList(), sig = "home_sig")
                return InteractionDispatchResult(true, "Home pressed")
            }

            override suspend fun pressEnter(): InteractionDispatchResult {
                bridge.current = sampleObservation(
                    nodes = listOf(ObservedUiNode(0, "result_1", "TextView", "Text", text = "Result Item", bounds = UiBounds(20, 200, 900, 320), clickable = true)),
                    sig = "results_sig"
                )
                return InteractionDispatchResult(true, "Submitted search")
            }
        }

        val perm = FakePermissionManager(context, accState = AccessibilityRuntimeState.PERMISSION_REQUIRED)
        val discovery = AndroidAppDiscovery(context)
        val launcher = AndroidAppLauncher(context, discovery, perm, verifier)
        val runtime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = launcher,
            screenObserver = observer,
            interactionController = AccessibilityInteractionController(backend),
            actionVerifier = verifier
        )

        // When Accessibility is disabled, interaction is honestly blocked
        val blocked = runtime.tapElement(TargetResolutionSpec(contentDescription = "Search"))
        assertFalse(blocked.success)
        assertTrue(blocked.requiresAccessibilityPermission)
        assertEquals("ACCESSIBILITY_PERMISSION_REQUIRED", blocked.errorCode)

        // Enable accessibility
        perm.accState = AccessibilityRuntimeState.SERVICE_ENABLED
        assertTrue(runtime.isAutomationActive())

        val tapOk = runtime.tapElement(TargetResolutionSpec(contentDescription = "Search"))
        assertTrue(tapOk.success)
        assertTrue(tapOk.screenChanged)

        val typeOk = runtime.typeText(TargetResolutionSpec(viewId = "query_field", editableOnly = true), "Android Compose")
        assertTrue(typeOk.success)
        assertTrue(typeOk.verificationDetail.contains("Verified text entry", ignoreCase = true))

        val enterOk = runtime.pressEnter()
        assertTrue(enterOk.success)
        assertEquals("Result Item", enterOk.postObservation?.nodes?.firstOrNull()?.text)

        val badCoords = runtime.tapCoordinates(-10f, 50f)
        assertFalse(badCoords.success)
        assertEquals("INVALID_ARGUMENT", badCoords.errorCode)
    }

    // --- 5. Model-Driven Multi-Step Computer-Use Agent & Recovery ---

    @Test
    fun `agent orchestrator chains app discovery, launch, observe, tap, type, enter dynamically and recovers from failed tap`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shadowPm = shadowOf(context.packageManager)
        val demoComp = ComponentName("com.example.shopdemo", "com.example.shopdemo.MainActivity")
        shadowPm.installPackage(
            PackageInfo().apply {
                packageName = "com.example.shopdemo"
                applicationInfo = ApplicationInfo().apply {
                    packageName = "com.example.shopdemo"
                    nonLocalizedLabel = "ShopDemo"
                }
            }
        )
        shadowPm.addActivityIfNotPresent(demoComp)
        shadowPm.addIntentFilterForActivity(
            demoComp,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        )

        val screen1 = sampleObservation(
            pkg = "com.example.shopdemo",
            nodes = listOf(
                ObservedUiNode(0, "search_icon", "ImageButton", "Button", contentDescription = "Search", bounds = UiBounds(900, 40, 1000, 120), clickable = true)
            ),
            sig = "shop_home"
        )
        val screen2 = sampleObservation(
            pkg = "com.example.shopdemo",
            nodes = listOf(
                ObservedUiNode(0, "search_input", "EditText", "SearchOrAddressField", hintText = "Search items", bounds = UiBounds(40, 40, 880, 120), editable = true, focused = true)
            ),
            sig = "shop_search_open"
        )
        val screen3 = sampleObservation(
            pkg = "com.example.shopdemo",
            nodes = listOf(
                ObservedUiNode(0, "search_input", "EditText", "SearchOrAddressField", text = "wireless mouse", bounds = UiBounds(40, 40, 880, 120), editable = true)
            ),
            sig = "shop_typed"
        )
        val screen4 = sampleObservation(
            pkg = "com.example.shopdemo",
            nodes = listOf(
                ObservedUiNode(0, "product_title", "TextView", "Text", text = "Wireless Mouse Pro", bounds = UiBounds(20, 200, 900, 300), clickable = true)
            ),
            sig = "shop_results"
        )

        val bridge = FakeScreenBridge(screen1)
        val observer = AndroidScreenObserver(bridge)
        val verifier = AndroidActionVerifier(observer)
        val perm = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_ENABLED)
        val discovery = AndroidAppDiscovery(context)
        val launcher = AndroidAppLauncher(context, discovery, perm, verifier, activityStarter = {})

        val backend = object : InteractionBackend {
            override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult {
                if (spec.text == "NonExistentButton") {
                    return InteractionDispatchResult(false, "Element 'NonExistentButton' not found.", errorCode = "TARGET_NOT_FOUND")
                }
                bridge.current = screen2
                return InteractionDispatchResult(true, "Tapped Search")
            }

            override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                InteractionDispatchResult(true, "Long pressed")

            override suspend fun tapCoordinates(x: Float, y: Float) =
                InteractionDispatchResult(true, "Tapped coordinates")

            override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation): InteractionDispatchResult {
                bridge.current = screen3
                return InteractionDispatchResult(true, "Typed '$text'")
            }

            override suspend fun scroll(direction: ScrollDirection, targetViewId: String?): InteractionDispatchResult {
                bridge.current = screen1
                return InteractionDispatchResult(true, "Scrolled ${direction.apiValue}")
            }

            override suspend fun pressBack() = InteractionDispatchResult(true, "Back")
            override suspend fun pressHome() = InteractionDispatchResult(true, "Home")
            override suspend fun pressEnter(): InteractionDispatchResult {
                bridge.current = screen4
                return InteractionDispatchResult(true, "Submitted search")
            }
        }

        val actionRuntime = AndroidActionRuntime(
            permissionManager = perm,
            appDiscovery = discovery,
            appLauncher = launcher,
            screenObserver = observer,
            interactionController = AccessibilityInteractionController(backend),
            actionVerifier = verifier
        )
        val registry = DefaultToolRegistry(context = context, actionRuntime = actionRuntime)
        val execRuntime = ToolExecutionRuntime(registry, androidActionRuntime = actionRuntime)
        val safety = DefaultSafetyPolicyManager()

        var turn = 0
        val caller = ProviderStreamCaller { _, req ->
            turn++
            flow {
                when (turn) {
                    1 -> emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c1", "find_app", """{"query":"ShopDemo"}"""))))
                    2 -> emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c2", "launch_app", """{"app":"ShopDemo"}"""))))
                    3 -> emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c3", "observe_screen", "{}"))))
                    4 -> emit(
                        // Simulate wrong selector first -> failure -> model recovers next turn
                        ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c4", "tap_element", """{"text":"NonExistentButton"}""")))
                    )
                    5 -> {
                        val prevErr = req.messages.last { it.role == MessageRole.TOOL }.content
                        assertTrue(prevErr.contains("TARGET_NOT_FOUND"))
                        emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c5", "tap_element", """{"content_description":"Search"}"""))))
                    }
                    6 -> emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c6", "type_text", """{"text":"wireless mouse","view_id":"search_input"}"""))))
                    7 -> emit(ProviderStreamEvent.ToolCallsCompleted(listOf(ToolCall("c7", "press_enter", "{}"))))
                    else -> {
                        val lastResult = req.messages.last { it.role == MessageRole.TOOL }.content
                        assertTrue(lastResult.contains("Wireless Mouse Pro"))
                        emit(ProviderStreamEvent.Delta("I opened ShopDemo, searched for 'wireless mouse', and verified 'Wireless Mouse Pro' appeared."))
                    }
                }
                emit(ProviderStreamEvent.Completed())
            }
        }

        val capabilities = AndroidCapabilityInspector(context, perm, discovery).inspectAll(emptyMap())
        val availableTools = registry.getAvailableToolDefinitions(capabilities)
        assertTrue(availableTools.any { it.name == "launch_app" })
        assertTrue(availableTools.any { it.name == "observe_screen" })
        assertTrue(availableTools.any { it.name == "tap_element" })

        val settings = AppSettings(confirmationMode = ConfirmationMode.SENSITIVE_ONLY)
        val orchestrator = DefaultAutonomousAgentOrchestrator(caller, registry, safety, execRuntime)
        val events = orchestrator.runTurn(
            context = AgentExecutionContext(
                taskId = "task_p4_e2e",
                conversationId = "conv_p4",
                userPrompt = "Open ShopDemo and search for wireless mouse",
                provider = toolCapableProvider(),
                settings = settings,
                limits = settings.toExecutionLimits(128_000),
                availableTools = availableTools,
                systemPrompt = SystemPromptBuilder().build(settings, availableTools, true, capabilities)
            ),
            historySnapshot = listOf(
                ChatMessage("u1", "conv_p4", MessageRole.USER, "Open ShopDemo and search for wireless mouse", 10L, status = MessageStatus.COMPLETE)
            ),
            attachments = emptyList()
        ).toList()

        val completed = events.filterIsInstance<AgentEvent.Completed>().single()
        assertTrue(completed.finalText.contains("Wireless Mouse Pro"))
        // 7 tool calls ran dynamically: c4 failed, 6 succeeded
        val toolResults = events.filterIsInstance<AgentEvent.ToolCompleted>().map { it.result }
        assertEquals(7, toolResults.size)
        assertEquals(ToolExecutionStatus.FAILED, toolResults[3].status)
        assertEquals(ToolExecutionStatus.SUCCEEDED, toolResults[4].status)
        assertEquals(ToolExecutionStatus.SUCCEEDED, toolResults[6].status)
    }

    // --- 6. Safety Policy, Sensitive Target Gate & Mid-Run Cancellation ---

    @Test
    fun `safety policy auto-runs reversible steps in SENSITIVE_ONLY, confirms sensitive UI targets, and blocks disabled app launch`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bridge = FakeScreenBridge(sampleObservation(nodes = emptyList()))
        val observer = AndroidScreenObserver(bridge)
        val verifier = AndroidActionVerifier(observer)
        val perm = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_ENABLED)
        val discovery = AndroidAppDiscovery(context)
        val runtime = AndroidActionRuntime(
            perm,
            discovery,
            AndroidAppLauncher(context, discovery, perm, verifier),
            observer,
            AccessibilityInteractionController(
                object : InteractionBackend {
                    override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "ok")
                    override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "ok")
                    override suspend fun tapCoordinates(x: Float, y: Float) = InteractionDispatchResult(true, "ok")
                    override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "ok")
                    override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) =
                        InteractionDispatchResult(true, "ok")
                    override suspend fun pressBack() = InteractionDispatchResult(true, "ok")
                    override suspend fun pressHome() = InteractionDispatchResult(true, "ok")
                    override suspend fun pressEnter() = InteractionDispatchResult(true, "ok")
                }
            ),
            verifier
        )
        val tools = ComputerUseTools.createAll(runtime).associateBy { it.definition.name }
        val safety = DefaultSafetyPolicyManager()
        val sensitiveOnly = AppSettings(confirmationMode = ConfirmationMode.SENSITIVE_ONLY)
        val alwaysConfirm = AppSettings(confirmationMode = ConfirmationMode.ALWAYS_CONFIRM)
        val launchDisabled = AppSettings(allowAppLaunchSuggestions = false)

        // Reversible UI tap auto-runs in SENSITIVE_ONLY
        val safeTapEval = safety.evaluateToolCall(
            "t1",
            tools.getValue("tap_element"),
            ToolCall("c1", "tap_element", """{"text":"Search"}"""),
            JSONObject("""{"text":"Search"}"""),
            sensitiveOnly
        )
        assertTrue(safeTapEval is ToolPolicyEvaluation.Allow)

        // Sensitive UI target ("Confirm Payment" / "Delete account") ALWAYS requires approval
        val payTapEval = safety.evaluateToolCall(
            "t1",
            tools.getValue("tap_element"),
            ToolCall("c2", "tap_element", """{"text":"Confirm Payment"}"""),
            JSONObject("""{"text":"Confirm Payment"}"""),
            sensitiveOnly
        )
        assertTrue(payTapEval is ToolPolicyEvaluation.RequireUserApproval)

        // ALWAYS_CONFIRM gates even read-only / reversible tools
        val alwaysEval = safety.evaluateToolCall(
            "t1",
            tools.getValue("observe_screen"),
            ToolCall("c3", "observe_screen", "{}"),
            JSONObject(),
            alwaysConfirm
        )
        assertTrue(alwaysEval is ToolPolicyEvaluation.RequireUserApproval)

        // Setting allowAppLaunchSuggestions=false denies launch_app
        val denyLaunch = safety.evaluateToolCall(
            "t1",
            tools.getValue("launch_app"),
            ToolCall("c4", "launch_app", """{"app":"Settings"}"""),
            JSONObject("""{"app":"Settings"}"""),
            launchDisabled
        )
        assertTrue(denyLaunch is ToolPolicyEvaluation.Deny)
    }

    @Test
    fun `disabled accessibility excludes UI tools from available definitions and stops unauthorized execution`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val disabledPerm = FakePermissionManager(context, accState = AccessibilityRuntimeState.PERMISSION_REQUIRED)
        val discovery = AndroidAppDiscovery(context)
        val observer = AndroidScreenObserver(FakeScreenBridge(sampleObservation(nodes = emptyList())))
        val verifier = AndroidActionVerifier(observer)
        val runtime = AndroidActionRuntime(
            disabledPerm,
            discovery,
            AndroidAppLauncher(context, discovery, disabledPerm, verifier),
            observer,
            AccessibilityInteractionController(
                object : InteractionBackend {
                    override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "Should not run")
                    override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "Should not run")
                    override suspend fun tapCoordinates(x: Float, y: Float) = InteractionDispatchResult(true, "Should not run")
                    override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "Should not run")
                    override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) =
                        InteractionDispatchResult(true, "Should not run")
                    override suspend fun pressBack() = InteractionDispatchResult(true, "Should not run")
                    override suspend fun pressHome() = InteractionDispatchResult(true, "Should not run")
                    override suspend fun pressEnter() = InteractionDispatchResult(true, "Should not run")
                }
            ),
            verifier
        )
        val registry = DefaultToolRegistry(context = context, actionRuntime = runtime)
        val caps = AndroidCapabilityInspector(context, disabledPerm, discovery).inspectAll(emptyMap())
        val defs = registry.getAvailableToolDefinitions(caps).map { it.name }.toSet()

        assertTrue(defs.contains("list_installed_apps"))
        assertTrue(defs.contains("find_app"))
        assertTrue(defs.contains("launch_app"))
        assertFalse(defs.contains("observe_screen"))
        assertFalse(defs.contains("tap_element"))
        assertFalse(defs.contains("type_text"))
    }

    @Test
    fun `cancelling coroutine stops subsequent computer-use actions cleanly`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val execCount = AtomicInteger(0)
        val firstTapStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val perm = FakePermissionManager(context, accState = AccessibilityRuntimeState.SERVICE_ENABLED)
        val discovery = AndroidAppDiscovery(context)
        val observer = AndroidScreenObserver(
            FakeScreenBridge(sampleObservation(nodes = emptyList())),
            workerDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        )
        val verifier = AndroidActionVerifier(observer)
        val runtime = AndroidActionRuntime(
            perm,
            discovery,
            AndroidAppLauncher(context, discovery, perm, verifier),
            observer,
            AccessibilityInteractionController(
                object : InteractionBackend {
                    override suspend fun tapElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation): InteractionDispatchResult {
                        execCount.incrementAndGet()
                        firstTapStarted.complete(Unit)
                        delay(500)
                        return InteractionDispatchResult(true, "tapped")
                    }
                    override suspend fun longPressElement(spec: TargetResolutionSpec, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "ok")
                    override suspend fun tapCoordinates(x: Float, y: Float) = InteractionDispatchResult(true, "ok")
                    override suspend fun typeText(spec: TargetResolutionSpec?, text: String, cachedObservation: ScreenObservation) =
                        InteractionDispatchResult(true, "ok")
                    override suspend fun scroll(direction: ScrollDirection, targetViewId: String?) =
                        InteractionDispatchResult(true, "ok")
                    override suspend fun pressBack() = InteractionDispatchResult(true, "ok")
                    override suspend fun pressHome() = InteractionDispatchResult(true, "ok")
                    override suspend fun pressEnter() = InteractionDispatchResult(true, "ok")
                }
            ),
            verifier
        )

        var caughtCancel = false
        val job = launch {
            try {
                runtime.tapElement(TargetResolutionSpec(text = "One"))
                runtime.tapElement(TargetResolutionSpec(text = "Two"))
            } catch (_: CancellationException) {
                caughtCancel = true
            }
        }
        firstTapStarted.await()
        job.cancel()
        job.join()
        assertTrue(caughtCancel || job.isCancelled)
        assertEquals(1, execCount.get())
    }

    // --- 7. UI Setup Card Verification ---

    @Test
    fun `capabilities tab displays Nexa Accessibility Service setup card and settings button`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val caps = AndroidCapabilityInspector(context).inspectAll(emptyMap())
        composeRule.setContent {
            NexaTheme {
                CapabilitiesTab(
                    capabilities = caps,
                    selectedCategory = null,
                    accessibilityState = AccessibilityRuntimeState.PERMISSION_REQUIRED,
                    accessibilityReason = "Enable Nexa Computer-Use Service in Android Accessibility Settings.",
                    canPostNotifications = false,
                    onSelectCategory = {},
                    onRefresh = {},
                    onToggleCapability = { _, _ -> }
                )
            }
        }
        composeRule.onNodeWithTag("accessibility_setup_card").assertIsDisplayed()
        composeRule.onNodeWithTag("capabilities_open_accessibility_settings").assertIsDisplayed()
        composeRule.onNodeWithTag("capabilities_enable_notifications").assertIsDisplayed()
        composeRule.onNodeWithText("Nexa Computer-Use Service").assertIsDisplayed()
    }
}
