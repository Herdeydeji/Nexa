package com.example

import android.app.Application
import com.example.android.accessibility.AccessibilityInteractionController
import com.example.android.accessibility.AccessibilityNodeInspector
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.LiveAccessibilityInteractionBackend
import com.example.android.accessibility.LiveAccessibilityScreenBridge
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.media.MediaProjectionSessionManager
import com.example.android.notifications.NexaTaskNotificationManager
import com.example.android.runtime.AndroidActionRuntime
import com.example.android.runtime.AndroidActionVerifier
import com.example.android.runtime.AndroidPermissionManager
import com.example.data.local.NexaDatabase
import com.example.data.local.NexaPreferencesDataStore
import com.example.data.local.NexaTypeConverters
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TaskRepository
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.agent.AgentRunController
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.AutonomousAgentOrchestrator
import com.example.domain.agent.CapabilityInspector
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.ExecutionRuntime
import com.example.domain.agent.Phase1AgentOrchestrator
import com.example.domain.agent.SafetyPolicyManager
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.agent.ToolRegistry
import com.example.domain.chat.AndroidAttachmentProcessor
import com.example.domain.chat.AttachmentProcessor
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.provider.AiProviderRegistry
import com.example.domain.provider.AndroidNetworkAvailabilityChecker
import com.example.domain.provider.NexaHttpClient
import com.example.domain.security.AndroidKeyStoreCredentialCipher
import com.example.domain.security.CredentialCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(application: Application) {
    private val converters = NexaTypeConverters()
    private val database: NexaDatabase = NexaDatabase.build(application)
    private val credentialCipher: CredentialCipher = AndroidKeyStoreCredentialCipher()
    private val preferencesDataStore = NexaPreferencesDataStore(application, credentialCipher)

    private val networkChecker = AndroidNetworkAvailabilityChecker(application)
    val nexaHttpClient: NexaHttpClient = NexaHttpClient(networkChecker = networkChecker)
    val providerRegistry: AiProviderRegistry = AiProviderRegistry(httpClient = nexaHttpClient)

    val systemPromptBuilder: SystemPromptBuilder = SystemPromptBuilder()
    val contextBuilder: ConversationContextBuilder = ConversationContextBuilder()
    val attachmentProcessor: AttachmentProcessor = AndroidAttachmentProcessor(application)

    // Phase 4: Native Android Computer-Use Engine + MediaProjection Screen Capture
    val mediaProjectionSessionManager: MediaProjectionSessionManager = MediaProjectionSessionManager(application)
    val permissionManager: AndroidPermissionManager = AndroidPermissionManager(
        context = application,
        screenshotBackend = mediaProjectionSessionManager
    )
    val appDiscovery: AndroidAppDiscovery = AndroidAppDiscovery(application)
    val nodeInspector: AccessibilityNodeInspector = AccessibilityNodeInspector()
    val screenObserver: AndroidScreenObserver = AndroidScreenObserver(
        bridge = LiveAccessibilityScreenBridge(permissionManager, nodeInspector)
    )
    val actionVerifier: AndroidActionVerifier = AndroidActionVerifier(screenObserver)
    val appLauncher: AndroidAppLauncher = AndroidAppLauncher(
        context = application,
        appDiscovery = appDiscovery,
        permissionManager = permissionManager,
        actionVerifier = actionVerifier
    )
    val interactionController: AccessibilityInteractionController = AccessibilityInteractionController(
        backend = LiveAccessibilityInteractionBackend(application, nodeInspector)
    )
    val androidActionRuntime: AndroidActionRuntime = AndroidActionRuntime(
        permissionManager = permissionManager,
        appDiscovery = appDiscovery,
        appLauncher = appLauncher,
        screenObserver = screenObserver,
        interactionController = interactionController,
        actionVerifier = actionVerifier,
        screenshotBackend = mediaProjectionSessionManager
    )
    val taskNotificationManager: NexaTaskNotificationManager = NexaTaskNotificationManager(
        context = application,
        permissionManager = permissionManager
    )

    val capabilityInspector: CapabilityInspector = AndroidCapabilityInspector(
        context = application,
        permissionManager = permissionManager,
        appDiscovery = appDiscovery
    )
    val toolRegistry: ToolRegistry = DefaultToolRegistry(
        context = application,
        actionRuntime = androidActionRuntime
    )
    val safetyPolicyManager: SafetyPolicyManager = DefaultSafetyPolicyManager()
    val chatRepository: ChatRepository = ChatRepository(database.conversationDao(), converters)
    val taskRepository: TaskRepository = TaskRepository(database.taskDao(), converters)
    val connectionsRepository: ConnectionsRepository = ConnectionsRepository(
        prefs = preferencesDataStore,
        providerRegistry = providerRegistry,
        capabilityInspector = capabilityInspector,
        mediaProjectionSessionManager = mediaProjectionSessionManager
    )
    val settingsRepository: SettingsRepository = SettingsRepository(preferencesDataStore)

    val toolExecutionRuntime: ToolExecutionRuntime = ToolExecutionRuntime(
        toolRegistry = toolRegistry,
        androidActionRuntime = androidActionRuntime,
        liveCapabilitiesProvider = { connectionsRepository.latestCapabilities }
    )
    val executionRuntime: ExecutionRuntime = toolExecutionRuntime
    val agentOrchestrator: AgentOrchestrator = Phase1AgentOrchestrator(toolRegistry, safetyPolicyManager)
    val agentRunController: AgentRunController = AgentRunController()

    val autonomousAgentOrchestrator: AutonomousAgentOrchestrator = DefaultAutonomousAgentOrchestrator(
        connectionsRepository = connectionsRepository,
        toolRegistry = toolRegistry,
        safetyPolicyManager = safetyPolicyManager,
        executionRuntime = toolExecutionRuntime,
        contextBuilder = contextBuilder
    )

    suspend fun ensureSeeded() {
        database.initializePhase2DataIfNeeded(converters)
    }
}

class NexaApplication : Application() {
    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        appScope.launch {
            container.ensureSeeded()
        }
    }
}
