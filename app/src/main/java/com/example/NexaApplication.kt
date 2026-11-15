package com.example

import android.app.Application
import com.example.data.local.NexaDatabase
import com.example.data.local.NexaPreferencesDataStore
import com.example.data.local.NexaTypeConverters
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TaskRepository
import com.example.domain.agent.AgentOrchestrator
import com.example.domain.agent.AndroidCapabilityInspector
import com.example.domain.agent.CapabilityInspector
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.DefaultToolRegistry
import com.example.domain.agent.ExecutionRuntime
import com.example.domain.agent.Phase1AgentOrchestrator
import com.example.domain.agent.Phase1StubExecutionRuntime
import com.example.domain.agent.SafetyPolicyManager
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

    val toolRegistry: ToolRegistry = DefaultToolRegistry()
    val safetyPolicyManager: SafetyPolicyManager = DefaultSafetyPolicyManager()
    val executionRuntime: ExecutionRuntime = Phase1StubExecutionRuntime()
    val capabilityInspector: CapabilityInspector = AndroidCapabilityInspector(application)
    val agentOrchestrator: AgentOrchestrator = Phase1AgentOrchestrator(toolRegistry, safetyPolicyManager)

    val chatRepository: ChatRepository = ChatRepository(database.conversationDao(), converters)
    val taskRepository: TaskRepository = TaskRepository(database.taskDao(), converters)
    val connectionsRepository: ConnectionsRepository = ConnectionsRepository(
        prefs = preferencesDataStore,
        providerRegistry = providerRegistry,
        capabilityInspector = capabilityInspector
    )
    val settingsRepository: SettingsRepository = SettingsRepository(preferencesDataStore)

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
