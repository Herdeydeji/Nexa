package com.example.ui.chat

import android.app.Activity
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.media.MediaProjectionSessionManager
import com.example.android.notifications.NexaTaskNotificationManager
import com.example.android.overlay.FloatingIndicatorController
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.security.ApprovalAuthResult
import com.example.android.security.ApprovalAuthenticator
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.MemoryRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TaskRepository
import com.example.domain.agent.ActiveAgentRunState
import com.example.domain.agent.AgentRunController
import com.example.domain.agent.AgentTool
import com.example.domain.agent.AutonomousAgentOrchestrator
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.RegisteredToolEntry
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.agent.ToolRegistry
import com.example.domain.chat.AttachmentProcessor
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AgentStatus
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AiProviderType
import com.example.domain.model.AmbiguitySelectionRequest
import com.example.domain.model.AppSettings
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ChatMessage
import com.example.domain.model.ComposerAttachment
import com.example.domain.model.Conversation
import com.example.domain.model.DeviceCapability
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStep
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.MediaProjectionConsentState
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ResumableTaskSnapshot
import com.example.domain.model.StepKind
import com.example.domain.model.TaskSummary
import com.example.domain.model.TaskVerificationSummary
import com.example.domain.model.TokenUsage
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolCall
import com.example.domain.model.ToolDefinition
import com.example.domain.model.ToolDispatchDiagnostic
import com.example.domain.model.ToolExecutionStatus
import com.example.domain.model.ToolRiskCategory
import com.example.domain.model.UserUnlockWaitRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

data class ChatUiState(
    val conversations: List<Conversation> = emptyList(),
    val activeConversation: Conversation? = null,
    val messages: List<ChatMessage> = emptyList(),
    val draftText: String = "",
    val attachments: List<ComposerAttachment> = emptyList(),
    val attachmentNotices: List<String> = emptyList(),
    val defaultProvider: ProviderConfig? = null,
    val allProviders: List<ProviderConfig> = emptyList(),
    val agentStatus: AgentStatus = AgentStatus.OFFLINE,
    val runStatus: AgentRunStatus = AgentRunStatus.IDLE,
    val statusDetail: String = "",
    val liveSteps: List<ExecutionStep> = emptyList(),
    val pendingApproval: ToolApprovalRequest? = null,
    val waitingForUnlock: UserUnlockWaitRequest? = null,
    val pendingAmbiguity: AmbiguitySelectionRequest? = null,
    val isPaused: Boolean = false,
    val activeTaskId: String? = null,
    val isResponding: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val availableTools: List<AgentTool> = emptyList(),
    val executableTools: List<ToolDefinition> = emptyList(),
    val registeredTools: List<RegisteredToolEntry> = emptyList(),
    val capabilities: List<DeviceCapability> = emptyList(),
    val isAccessibilityEnabled: Boolean = false,
    val accessibilityState: AccessibilityRuntimeState = AccessibilityRuntimeState.SERVICE_DISABLED,
    val mediaProjectionState: MediaProjectionConsentState = MediaProjectionConsentState.NOT_GRANTED,
    val isMediaProjectionActive: Boolean = false,
    val lastToolDiagnostic: ToolDispatchDiagnostic? = null,
    val isIncognitoConversation: Boolean = false,
    val queuedPromptsCount: Int = 0,
    val queueNotice: String? = null,
    val biometricWarningBanner: String? = null,
    val memorySavedNotice: String? = null
)

private data class StreamingOverlay(
    val messageId: String? = null,
    val text: String = "",
    val isResponding: Boolean = false
)

private data class QueuedPrompt(
    val prompt: String,
    val attachments: List<ComposerAttachment>,
    val conversationId: String?
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(
    private val chatRepository: ChatRepository,
    private val connectionsRepository: ConnectionsRepository,
    private val settingsRepository: SettingsRepository,
    private val toolRegistry: ToolRegistry,
    private val systemPromptBuilder: SystemPromptBuilder,
    private val contextBuilder: ConversationContextBuilder,
    private val attachmentProcessor: AttachmentProcessor,
    private val taskRepository: TaskRepository? = null,
    autonomousOrchestrator: AutonomousAgentOrchestrator? = null,
    private val agentRunController: AgentRunController = AgentRunController(),
    private val permissionManager: AndroidPermissionManager? = null,
    private val taskNotificationManager: NexaTaskNotificationManager? = null,
    private val mediaProjectionSessionManager: MediaProjectionSessionManager? = null,
    private val floatingIndicatorController: FloatingIndicatorController? = null,
    private val memoryRepository: MemoryRepository? = null,
    private val approvalAuthenticator: ApprovalAuthenticator? = null
) : ViewModel() {

    private val orchestrator: AutonomousAgentOrchestrator = autonomousOrchestrator
        ?: DefaultAutonomousAgentOrchestrator(
            connectionsRepository = connectionsRepository,
            toolRegistry = toolRegistry,
            safetyPolicyManager = DefaultSafetyPolicyManager(),
            executionRuntime = ToolExecutionRuntime(toolRegistry),
            contextBuilder = contextBuilder
        )

    private val selectedConversationId = MutableStateFlow<String?>(null)
    private val draftText = MutableStateFlow("")
    private val pendingAttachments = MutableStateFlow<List<ComposerAttachment>>(emptyList())
    private val attachmentNotices = MutableStateFlow<List<String>>(emptyList())
    private val streamingOverlay = MutableStateFlow(StreamingOverlay())
    private val lastTurnAttachments = mutableMapOf<String, List<ComposerAttachment>>()

    private val ephemeralConversations = MutableStateFlow<List<Conversation>>(emptyList())
    private val ephemeralMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    private val promptQueue = ArrayDeque<QueuedPrompt>()
    private val queuedCountState = MutableStateFlow(0)
    private val queueNoticeState = MutableStateFlow<String?>(null)
    private val biometricWarningState = MutableStateFlow<String?>(null)
    private val memorySavedState = MutableStateFlow<String?>(null)
    private var pendingCredentialApprovalCallId: String? = null
    @Volatile
    private var stopRequestedByUser: Boolean = false
    @Volatile
    private var latestSettings: AppSettings = AppSettings()

    private var responseJob: Job? = null

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { latestSettings = it }
        }
    }

    private val activeMessagesFlow = selectedConversationId.flatMapLatest { id ->
        if (id == null) {
            flowOf(emptyList())
        } else {
            combine(
                chatRepository.observeMessages(id),
                ephemeralMessages
            ) { persisted, ephMap ->
                ephMap[id] ?: persisted
            }
        }
    }

    private data class ChatMeta(
        val conversations: List<Conversation>,
        val selectedId: String?,
        val messages: List<ChatMessage>,
        val draft: String,
        val attachments: List<ComposerAttachment>
    )

    private val mergedConversationsFlow = combine(
        chatRepository.conversations,
        ephemeralConversations
    ) { persisted, ephemeral ->
        (ephemeral + persisted).sortedByDescending { it.updatedAtMillis }
    }

    private val chatMetaFlow = combine(
        mergedConversationsFlow,
        selectedConversationId,
        activeMessagesFlow,
        draftText,
        pendingAttachments
    ) { conversations, selectedId, messages, draft, attachments ->
        ChatMeta(conversations, selectedId, messages, draft, attachments)
    }

    private val permissionRefreshTick = MutableStateFlow(0)
    private val lastToolDiagnosticState = MutableStateFlow<ToolDispatchDiagnostic?>(null)

    private data class UiExtras(
        val overlay: StreamingOverlay,
        val notices: List<String>,
        val settings: AppSettings,
        val runState: ActiveAgentRunState,
        val diagnostic: ToolDispatchDiagnostic?,
        val queuedCount: Int,
        val queueNotice: String?,
        val biometricWarning: String?,
        val memorySavedNotice: String?
    )

    private val statusNoticesFlow = combine(
        queuedCountState,
        queueNoticeState,
        biometricWarningState,
        memorySavedState
    ) { queued, qNotice, bioWarn, memSaved ->
        arrayOf(queued, qNotice, bioWarn, memSaved)
    }

    private val uiExtrasFlow = combine(
        streamingOverlay,
        attachmentNotices,
        settingsRepository.settings,
        agentRunController.state,
        lastToolDiagnosticState,
        statusNoticesFlow,
        permissionRefreshTick
    ) { values ->
        val overlay = values[0] as StreamingOverlay
        @Suppress("UNCHECKED_CAST")
        val notices = values[1] as List<String>
        val settings = values[2] as AppSettings
        val runState = values[3] as ActiveAgentRunState
        val diagnostic = values[4] as ToolDispatchDiagnostic?
        @Suppress("UNCHECKED_CAST")
        val extraStatus = values[5] as Array<Any?>
        UiExtras(
            overlay = overlay,
            notices = notices,
            settings = settings,
            runState = runState,
            diagnostic = diagnostic,
            queuedCount = extraStatus[0] as Int,
            queueNotice = extraStatus[1] as String?,
            biometricWarning = extraStatus[2] as String?,
            memorySavedNotice = extraStatus[3] as String?
        )
    }

    val uiState: StateFlow<ChatUiState> = combine(
        chatMetaFlow,
        connectionsRepository.providers,
        connectionsRepository.defaultProvider,
        connectionsRepository.capabilities,
        uiExtrasFlow
    ) { meta, providers, defaultProvider, capabilities, extras ->
        val liveConversations = meta.conversations.filterNot { it.isSamplePreview }
        val resolvedActive = liveConversations.firstOrNull { it.id == meta.selectedId }
            ?: liveConversations.firstOrNull()
        if (resolvedActive != null && resolvedActive.id != meta.selectedId) {
            selectedConversationId.value = resolvedActive.id
        }
        val overlay = extras.overlay
        val notices = extras.notices
        val settings = extras.settings
        val runState = extras.runState
        val diagnostic = extras.diagnostic
        val activeIsEphemeral = resolvedActive?.id?.let { id ->
            ephemeralConversations.value.any { it.id == id }
        } == true || !settings.saveConversationHistory

        val visibleMessages = meta.messages
            .filter { msg ->
                when (msg.role) {
                    MessageRole.TOOL -> false
                    MessageRole.ASSISTANT -> msg.content.isNotBlank() ||
                        msg.status == MessageStatus.STREAMING ||
                        msg.status == MessageStatus.FAILED ||
                        msg.status == MessageStatus.STOPPED
                    else -> true
                }
            }
            .map { msg ->
                if (overlay.isResponding && overlay.messageId == msg.id) {
                    val livePlan = if (runState.liveSteps.isNotEmpty() && runState.activeConversationId == msg.conversationId) {
                        ExecutionPlan(
                            goal = msg.content.ifBlank { "Active agent task" },
                            isPreview = false,
                            steps = runState.liveSteps,
                            verificationSummary = runState.verificationSummary
                        )
                    } else {
                        msg.executionPlan
                    }
                    msg.copy(
                        content = overlay.text,
                        status = MessageStatus.STREAMING,
                        executionPlan = livePlan
                    )
                } else {
                    msg
                }
            }

        val hasUsableProvider = defaultProvider != null &&
            !defaultProvider.credentialInvalidated &&
            (defaultProvider.hasApiKey || defaultProvider.providerType.requiresBaseUrl) &&
            !defaultProvider.selectedModelId.isNullOrBlank()

        val status = when {
            runState.isPaused -> AgentStatus.PAUSED
            runState.waitingForUnlock != null ||
                runState.pendingAmbiguity != null ||
                runState.runStatus == AgentRunStatus.WAITING_FOR_USER_UNLOCK ||
                runState.runStatus == AgentRunStatus.WAITING_FOR_AMBIGUITY_CHOICE -> AgentStatus.WAITING_FOR_USER
            runState.pendingApproval != null || runState.runStatus == AgentRunStatus.AWAITING_APPROVAL -> AgentStatus.AWAITING_CONFIRMATION
            runState.runStatus == AgentRunStatus.SELECTING_TOOL ||
                runState.runStatus == AgentRunStatus.LOCATING_TARGET ||
                runState.runStatus == AgentRunStatus.EXECUTING_TOOL ||
                runState.runStatus == AgentRunStatus.OBSERVING_RESULT -> AgentStatus.EXECUTING
            overlay.isResponding || runState.isRunning -> AgentStatus.THINKING
            !hasUsableProvider -> AgentStatus.OFFLINE
            else -> AgentStatus.IDLE
        }

        val permSnap = permissionManager?.inspect()
        val accState = permSnap?.accessibilityState ?: if (NexaAccessibilityService.runtimeState.value.isConnected) {
            AccessibilityRuntimeState.SERVICE_ENABLED
        } else {
            AccessibilityRuntimeState.SERVICE_DISABLED
        }
        val accessibilityReady = accState == AccessibilityRuntimeState.SERVICE_ENABLED
        val projState = mediaProjectionSessionManager?.state?.value?.consentState
            ?: permSnap?.mediaProjectionState
            ?: MediaProjectionConsentState.NOT_GRANTED

        val registered = toolRegistry.getAllRegisteredTools(capabilities)

        ChatUiState(
            conversations = liveConversations,
            activeConversation = resolvedActive,
            messages = visibleMessages,
            draftText = meta.draft,
            attachments = meta.attachments,
            attachmentNotices = notices,
            defaultProvider = defaultProvider,
            allProviders = providers,
            agentStatus = status,
            runStatus = runState.runStatus,
            statusDetail = runState.statusDetail,
            liveSteps = runState.liveSteps,
            pendingApproval = runState.pendingApproval,
            waitingForUnlock = runState.waitingForUnlock,
            pendingAmbiguity = runState.pendingAmbiguity,
            isPaused = runState.isPaused,
            activeTaskId = runState.activeTaskId,
            isResponding = overlay.isResponding,
            settings = settings,
            availableTools = toolRegistry.getAllTools(),
            executableTools = registered.filter { it.isAvailable }.map { it.definition },
            registeredTools = registered,
            capabilities = capabilities,
            isAccessibilityEnabled = accessibilityReady,
            accessibilityState = accState,
            mediaProjectionState = projState,
            isMediaProjectionActive = projState == MediaProjectionConsentState.GRANTED_ACTIVE,
            lastToolDiagnostic = diagnostic,
            isIncognitoConversation = activeIsEphemeral,
            queuedPromptsCount = extras.queuedCount,
            queueNotice = extras.queueNotice,
            biometricWarningBanner = extras.biometricWarning,
            memorySavedNotice = extras.memorySavedNotice
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState(
            availableTools = toolRegistry.getAllTools(),
            executableTools = toolRegistry.getAvailableToolDefinitions(),
            registeredTools = toolRegistry.getAllRegisteredTools()
        )
    )

    fun refreshDevicePermissions() {
        connectionsRepository.refreshCapabilities()
        permissionRefreshTick.value += 1
    }

    fun createScreenCaptureIntent(): Intent? =
        mediaProjectionSessionManager?.createScreenCaptureIntent()

    fun onMediaProjectionConsentResult(resultCode: Int, data: Intent?) {
        mediaProjectionSessionManager?.onConsentResult(resultCode, data)
        refreshDevicePermissions()
    }

    fun stopMediaProjectionSession() {
        mediaProjectionSessionManager?.stopSession(revokedBySystem = false)
        refreshDevicePermissions()
    }

    fun onDraftChanged(value: String) {
        draftText.value = value
    }

    fun addAttachment(displayName: String, mimeType: String, sourceUri: String) {
        val item = ComposerAttachment(
            id = UUID.randomUUID().toString(),
            displayName = displayName,
            mimeType = mimeType,
            sourceUri = sourceUri
        )
        pendingAttachments.value = pendingAttachments.value + item
    }

    fun removeAttachment(id: String) {
        pendingAttachments.value = pendingAttachments.value.filterNot { it.id == id }
    }

    fun dismissAttachmentNotices() {
        attachmentNotices.value = emptyList()
    }

    fun dismissQueueNotice() {
        queueNoticeState.value = null
    }

    fun dismissBiometricWarning() {
        biometricWarningState.value = null
    }

    fun dismissMemorySavedNotice() {
        memorySavedState.value = null
    }

    fun selectConversation(conversationId: String) {
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            if (!currentSettings.allowBackgroundTaskQueue) {
                stopResponse()
            }
            selectedConversationId.value = conversationId
            if (currentSettings.rememberPreferredModelPerThread) {
                val conv = ephemeralConversations.value.firstOrNull { it.id == conversationId }
                    ?: chatRepository.getConversationsSnapshot().firstOrNull { it.id == conversationId }
                val prefProviderId = conv?.preferredProviderId
                val prefModelId = conv?.preferredModelId
                if (!prefProviderId.isNullOrBlank() && !prefModelId.isNullOrBlank()) {
                    AiProviderType.entries.firstOrNull { it.id == prefProviderId }?.let { resolvedType ->
                        connectionsRepository.setDefaultProvider(resolvedType, prefModelId)
                    }
                }
            }
        }
    }

    fun startNewConversation() {
        viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            if (!currentSettings.allowBackgroundTaskQueue) {
                stopResponse()
            }
            val newId = createTargetConversation(currentSettings)
            selectedConversationId.value = newId
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            if (isEphemeralConversation(id)) {
                val clean = title.trim()
                if (clean.isNotEmpty()) {
                    ephemeralConversations.value = ephemeralConversations.value.map {
                        if (it.id == id) it.copy(title = clean, updatedAtMillis = System.currentTimeMillis()) else it
                    }
                }
            } else {
                chatRepository.renameConversation(id, title)
            }
        }
    }

    fun deleteConversation(id: String) {
        lastTurnAttachments.remove(id)
        viewModelScope.launch {
            if (isEphemeralConversation(id)) {
                ephemeralConversations.value = ephemeralConversations.value.filterNot { it.id == id }
                ephemeralMessages.value = ephemeralMessages.value - id
            } else {
                chatRepository.deleteConversation(id)
            }
            if (selectedConversationId.value == id) {
                selectedConversationId.value = null
            }
        }
    }

    fun selectDefaultModel(provider: ProviderConfig, modelId: String) {
        viewModelScope.launch {
            connectionsRepository.setDefaultProvider(provider.providerType, modelId)
            val settings = settingsRepository.settings.first()
            val activeId = selectedConversationId.value
            if (settings.rememberPreferredModelPerThread && activeId != null) {
                if (isEphemeralConversation(activeId)) {
                    ephemeralConversations.value = ephemeralConversations.value.map {
                        if (it.id == activeId) {
                            it.copy(
                                preferredProviderId = provider.providerType.id,
                                preferredModelId = modelId
                            )
                        } else {
                            it
                        }
                    }
                } else {
                    chatRepository.updateConversationPreferredModel(
                        conversationId = activeId,
                        providerId = provider.providerType.id,
                        modelId = modelId
                    )
                }
            }
        }
    }

    fun pauseExecution() {
        agentRunController.pause()
    }

    fun resumeExecution() {
        agentRunController.resume()
    }

    fun approveToolCall(callId: String) {
        agentRunController.submitApproval(callId, ApprovalDecision.APPROVE)
    }

    fun confirmApprovalWithAuth(
        activity: Activity?,
        request: ToolApprovalRequest,
        onDeviceCredentialIntentRequired: ((Intent) -> Unit)? = null
    ) {
        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            val requiresAuth = settings.biometricLockOnApproval && request.riskCategory in setOf(
                ToolRiskCategory.SENSITIVE,
                ToolRiskCategory.DESTRUCTIVE,
                ToolRiskCategory.EXTERNAL_SIDE_EFFECT
            )
            if (!requiresAuth) {
                biometricWarningState.value = null
                approveToolCall(request.callId)
                return@launch
            }
            val authenticator = approvalAuthenticator
            if (authenticator == null) {
                biometricWarningState.value = "Biometric authenticator is unavailable; approval proceeded with notice."
                approveToolCall(request.callId)
                return@launch
            }
            var credentialIntentDispatched = false
            val authResult = authenticator.authenticate(
                activity = activity,
                title = "Authorize sensitive action",
                subtitle = request.intentSummary.ifBlank { request.toolName },
                onDeviceCredentialIntentRequired = { intent ->
                    credentialIntentDispatched = true
                    pendingCredentialApprovalCallId = request.callId
                    onDeviceCredentialIntentRequired?.invoke(intent)
                }
            )
            when (authResult) {
                is ApprovalAuthResult.Verified -> {
                    biometricWarningState.value = null
                    approveToolCall(request.callId)
                }
                is ApprovalAuthResult.ProceedWithWarning -> {
                    biometricWarningState.value = authResult.warningMessage
                    approveToolCall(request.callId)
                }
                is ApprovalAuthResult.Cancelled -> {
                    if (!credentialIntentDispatched) {
                        biometricWarningState.value = authResult.reason
                    }
                }
                is ApprovalAuthResult.Failed -> {
                    biometricWarningState.value = authResult.errorMessage
                }
            }
        }
    }

    fun completeDeviceCredentialApproval(granted: Boolean) {
        val callId = pendingCredentialApprovalCallId ?: return
        pendingCredentialApprovalCallId = null
        if (granted) {
            biometricWarningState.value = null
            approveToolCall(callId)
        } else {
            biometricWarningState.value = "Device credential check cancelled. Sensitive action remains paused."
        }
    }

    fun rejectToolCall(callId: String) {
        biometricWarningState.value = null
        agentRunController.submitApproval(callId, ApprovalDecision.REJECT)
    }

    fun continueUnlockWait() {
        agentRunController.submitUnlockContinue()
    }

    fun selectAmbiguousApp(selectionId: String, selectedPackageName: String?) {
        agentRunController.submitAmbiguityChoice(selectionId, selectedPackageName)
    }

    fun sendMessage(overridePrompt: String? = null) {
        val text = (overridePrompt ?: draftText.value).trim()
        val currentAttachments = pendingAttachments.value
        if (text.isEmpty() && currentAttachments.isEmpty()) return

        val promptToSend = text.ifEmpty {
            "Analyze attached ${currentAttachments.size} item(s): ${currentAttachments.joinToString { it.displayName }}"
        }

        if (streamingOverlay.value.isResponding || agentRunController.state.value.isRunning) {
            if (latestSettings.allowBackgroundTaskQueue) {
                promptQueue.addLast(
                    QueuedPrompt(
                        prompt = promptToSend,
                        attachments = currentAttachments,
                        conversationId = selectedConversationId.value
                    )
                )
                queuedCountState.value = promptQueue.size
                queueNoticeState.value = "Prompt queued (${promptQueue.size} waiting) to run next."
                if (overridePrompt == null) {
                    draftText.value = ""
                }
                pendingAttachments.value = emptyList()
            } else {
                queueNoticeState.value = "A task is currently running. Enable Background Task Queue in Settings to queue follow-up prompts."
            }
            return
        }

        if (overridePrompt == null) {
            draftText.value = ""
        }
        pendingAttachments.value = emptyList()
        attachmentNotices.value = emptyList()
        queueNoticeState.value = null
        stopRequestedByUser = false

        launchPromptTurn(
            promptToSend = promptToSend,
            currentAttachments = currentAttachments,
            requestedConversationId = selectedConversationId.value
        )
    }

    private fun launchPromptTurn(
        promptToSend: String,
        currentAttachments: List<ComposerAttachment>,
        requestedConversationId: String?
    ) {
        responseJob = viewModelScope.launch {
            val currentSettings = settingsRepository.settings.first()
            val targetConvId = resolveTargetConversationId(requestedConversationId, currentSettings)
            selectedConversationId.value = targetConvId

            if (currentAttachments.isNotEmpty()) {
                lastTurnAttachments[targetConvId] = currentAttachments
            } else {
                lastTurnAttachments.remove(targetConvId)
            }

            appendChatMessage(
                conversationId = targetConvId,
                role = MessageRole.USER,
                content = promptToSend,
                status = MessageStatus.COMPLETE,
                attachments = currentAttachments.map { it.displayName }
            )

            val provider = connectionsRepository.defaultProvider.first()
                ?: connectionsRepository.providers.first().firstOrNull {
                    !it.credentialInvalidated &&
                        (it.hasApiKey || (it.providerType.requiresBaseUrl && it.baseUrl.isNotBlank()))
                }

            val modelInfo = provider?.selectedModelInfo
            val modelId = provider?.selectedModelId?.takeIf { it.isNotBlank() }

            if (provider == null || modelId == null) {
                appendChatMessage(
                    conversationId = targetConvId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    status = MessageStatus.FAILED,
                    errorMessage = "No active AI provider and model are configured. Open Connections to configure a provider, then tap Retry."
                )
                return@launch
            }

            if (provider.credentialInvalidated) {
                appendChatMessage(
                    conversationId = targetConvId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    status = MessageStatus.FAILED,
                    errorMessage = "Stored credentials for ${provider.providerType.displayName} could not be decrypted. Re-enter your API key in Connections, then tap Retry.",
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId
                )
                return@launch
            }

            if (!provider.hasApiKey && !provider.providerType.requiresBaseUrl) {
                appendChatMessage(
                    conversationId = targetConvId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    status = MessageStatus.FAILED,
                    errorMessage = "Save an API key for ${provider.providerType.displayName} in Connections, then tap Retry.",
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId
                )
                return@launch
            }

            val processedAttachments = if (currentAttachments.isNotEmpty()) {
                attachmentProcessor.process(
                    attachments = currentAttachments,
                    modelSupportsVision = modelInfo?.supportsVision == true,
                    modelDisplayName = modelInfo?.displayName ?: modelId
                )
            } else {
                emptyList()
            }

            val notices = processedAttachments.mapNotNull { it.unsupportedReason }
            if (notices.isNotEmpty()) {
                attachmentNotices.value = notices
            }

            runAgentTurn(
                conversationId = targetConvId,
                userPrompt = promptToSend,
                provider = provider,
                modelId = modelId,
                processedAttachments = processedAttachments.filter { it.isSupported }
            )
        }
    }

    fun retryMessage(failedOrStoppedMessage: ChatMessage) {
        if (streamingOverlay.value.isResponding) return
        if (failedOrStoppedMessage.role != MessageRole.ASSISTANT) return

        attachmentNotices.value = emptyList()
        stopRequestedByUser = false

        responseJob = viewModelScope.launch {
            val targetConvId = failedOrStoppedMessage.conversationId
            deleteChatMessage(targetConvId, failedOrStoppedMessage.id)

            val provider = connectionsRepository.defaultProvider.first()
                ?: connectionsRepository.providers.first().firstOrNull {
                    !it.credentialInvalidated &&
                        (it.hasApiKey || (it.providerType.requiresBaseUrl && it.baseUrl.isNotBlank()))
                }
            val modelInfo = provider?.selectedModelInfo
            val modelId = provider?.selectedModelId?.takeIf { it.isNotBlank() }

            if (provider == null || modelId == null ||
                provider.credentialInvalidated ||
                (!provider.hasApiKey && !provider.providerType.requiresBaseUrl)
            ) {
                appendChatMessage(
                    conversationId = targetConvId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    status = MessageStatus.FAILED,
                    errorMessage = "Configure an active AI provider and valid credentials in Connections before retrying."
                )
                return@launch
            }

            val cachedAttachments = lastTurnAttachments[targetConvId].orEmpty()
            val processedAttachments = if (cachedAttachments.isNotEmpty()) {
                attachmentProcessor.process(
                    attachments = cachedAttachments,
                    modelSupportsVision = modelInfo?.supportsVision == true,
                    modelDisplayName = modelInfo?.displayName ?: modelId
                ).also { processed ->
                    val notices = processed.mapNotNull { it.unsupportedReason }
                    if (notices.isNotEmpty()) {
                        attachmentNotices.value = notices
                    }
                }.filter { it.isSupported }
            } else {
                emptyList()
            }

            val latestPrompt = getChatHistorySnapshot(targetConvId)
                .lastOrNull { it.role == MessageRole.USER }?.content ?: "Retry prompt"

            runAgentTurn(
                conversationId = targetConvId,
                userPrompt = latestPrompt,
                provider = provider,
                modelId = modelId,
                processedAttachments = processedAttachments
            )
        }
    }

    fun stopResponse() {
        stopRequestedByUser = true
        promptQueue.clear()
        queuedCountState.value = 0
        agentRunController.cancelActiveRun()
        if (responseJob?.isActive == true) {
            responseJob?.cancel()
        }
    }

    private suspend fun runAgentTurn(
        conversationId: String,
        userPrompt: String,
        provider: ProviderConfig,
        modelId: String,
        processedAttachments: List<AttachmentPayload>
    ) {
        connectionsRepository.refreshCapabilities()
        val settings = settingsRepository.settings.first()
        val capabilities = connectionsRepository.capabilities.first()
        val memories = if (settings.localMemoryEnabled) {
            memoryRepository?.getSnapshot().orEmpty()
        } else {
            emptyList()
        }
        val registeredTools = toolRegistry.getAllRegisteredTools(capabilities)
        val allImplementedTools = registeredTools.filter { it.isAvailable }.map { it.definition }
        val availableTools = if (provider.effectiveSupportsTools) allImplementedTools else emptyList()
        val systemPrompt = systemPromptBuilder.build(
            settings = settings,
            availableTools = allImplementedTools,
            toolsEnabledForModel = provider.effectiveSupportsTools,
            capabilities = capabilities,
            registeredTools = registeredTools,
            memoryEntries = memories
        )
        lastToolDiagnosticState.value = ToolDispatchDiagnostic(
            providerType = provider.providerType,
            modelId = modelId,
            toolsEnabled = provider.effectiveSupportsTools && availableTools.isNotEmpty(),
            availableToolNames = availableTools.map { it.name },
            serializedToolNames = availableTools.map { it.name },
            toolChoiceSent = if (provider.effectiveSupportsTools && availableTools.isNotEmpty()) "auto" else "none"
        )
        val historySnapshot = getChatHistorySnapshot(conversationId)

        var currentAssistantId = appendChatMessage(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
            providerLabel = provider.providerType.displayName,
            modelLabel = modelId
        )

        val taskId = "task_${UUID.randomUUID()}"
        val context = AgentExecutionContext(
            taskId = taskId,
            conversationId = conversationId,
            userPrompt = userPrompt,
            provider = provider,
            settings = settings,
            limits = settings.toExecutionLimits(provider.selectedModelInfo?.contextLengthTokens),
            availableTools = availableTools,
            systemPrompt = systemPrompt
        )

        val job = currentCoroutineContext()[Job]
        val control = agentRunController.beginRun(taskId, conversationId, job)
        floatingIndicatorController?.setCurrentObjective(userPrompt)
        var liveTaskCreated = false
        var latestPlan: ExecutionPlan? = null
        var latestText = ""
        var lastPersistedAt = System.currentTimeMillis()
        val usedToolNames = mutableSetOf<String>()
        val taskCreatedAt = System.currentTimeMillis()

        streamingOverlay.value = StreamingOverlay(
            messageId = currentAssistantId,
            text = "",
            isResponding = true
        )

        try {
            orchestrator.runTurn(context, historySnapshot, processedAttachments, control).collect { event ->
                agentRunController.applyEvent(event)
                when (event) {
                    is AgentEvent.TextDelta -> {
                        latestText = event.accumulatedText
                        streamingOverlay.value = StreamingOverlay(
                            messageId = currentAssistantId,
                            text = latestText,
                            isResponding = true
                        )
                        val now = System.currentTimeMillis()
                        if (latestText.isNotEmpty() && now - lastPersistedAt > 500L) {
                            lastPersistedAt = now
                            finalizeChatMessage(
                                messageId = currentAssistantId,
                                conversationId = conversationId,
                                content = latestText,
                                status = MessageStatus.STREAMING
                            )
                        }
                    }

                    is AgentEvent.ToolSelected -> {
                        liveTaskCreated = true
                        event.toolCalls.forEach { usedToolNames.add(it.toolName) }
                        taskNotificationManager?.showRunningProgress(
                            taskId = taskId,
                            title = userPrompt,
                            detail = "Running ${event.toolCalls.joinToString { it.toolName }}..."
                        )
                        taskRepository?.upsertLiveTask(
                            taskId = taskId,
                            title = userPrompt.take(64),
                            prompt = userPrompt,
                            status = AgentTaskStatus.RUNNING,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId,
                            requiredCapabilities = usedToolNames.toList(),
                            steps = latestPlan?.steps.orEmpty(),
                            createdAtMillis = taskCreatedAt
                        )
                    }

                    is AgentEvent.ApprovalRequired -> {
                        if (liveTaskCreated) {
                            taskNotificationManager?.showRunningProgress(
                                taskId = taskId,
                                title = userPrompt,
                                detail = "Waiting confirmation: ${event.request.intentSummary}"
                            )
                            taskRepository?.upsertLiveTask(
                                taskId = taskId,
                                title = userPrompt.take(64),
                                prompt = userPrompt,
                                status = AgentTaskStatus.AWAITING_APPROVAL,
                                providerLabel = provider.providerType.displayName,
                                modelLabel = modelId,
                                requiredCapabilities = usedToolNames.toList(),
                                steps = latestPlan?.steps.orEmpty(),
                                createdAtMillis = taskCreatedAt
                            )
                        }
                    }

                    is AgentEvent.Paused -> {
                        if (liveTaskCreated) {
                            taskNotificationManager?.showRunningProgress(
                                taskId = taskId,
                                title = userPrompt,
                                detail = "Paused by user"
                            )
                            taskRepository?.updateLiveTaskOutcome(taskId, AgentTaskStatus.PAUSED)
                        }
                    }

                    is AgentEvent.Resumed -> {
                        if (liveTaskCreated) {
                            taskNotificationManager?.showRunningProgress(
                                taskId = taskId,
                                title = userPrompt,
                                detail = "Resuming..."
                            )
                            taskRepository?.updateLiveTaskOutcome(taskId, AgentTaskStatus.RUNNING)
                        }
                    }

                    is AgentEvent.WaitingForUserUnlock -> {
                        liveTaskCreated = true
                        taskNotificationManager?.showWaitingForUnlock(
                            taskId = taskId,
                            title = event.request.title,
                            message = event.request.message,
                            showContinueAction = event.request.manualContinueRequired
                        )
                        taskRepository?.upsertLiveTask(
                            taskId = taskId,
                            title = userPrompt.take(64),
                            prompt = userPrompt,
                            status = AgentTaskStatus.WAITING_FOR_USER_UNLOCK,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId,
                            requiredCapabilities = usedToolNames.toList(),
                            steps = latestPlan?.steps.orEmpty(),
                            createdAtMillis = taskCreatedAt,
                            targetApp = event.request.targetAppName,
                            resumableSnapshot = ResumableTaskSnapshot(
                                taskId = taskId,
                                conversationId = conversationId,
                                prompt = userPrompt,
                                targetAppName = event.request.targetAppName,
                                targetPackage = event.request.targetPackage,
                                reason = event.request.message
                            )
                        )
                    }

                    is AgentEvent.UserUnlockTimeoutManualContinue -> {
                        liveTaskCreated = true
                        taskNotificationManager?.showWaitingForUnlock(
                            taskId = taskId,
                            title = "Unlock ${event.targetAppName}",
                            message = event.reason,
                            showContinueAction = true
                        )
                        taskRepository?.upsertLiveTask(
                            taskId = taskId,
                            title = userPrompt.take(64),
                            prompt = userPrompt,
                            status = AgentTaskStatus.WAITING_FOR_USER_UNLOCK,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId,
                            requiredCapabilities = usedToolNames.toList(),
                            steps = latestPlan?.steps.orEmpty(),
                            createdAtMillis = taskCreatedAt,
                            targetApp = event.targetAppName,
                            resumableSnapshot = ResumableTaskSnapshot(
                                taskId = taskId,
                                conversationId = conversationId,
                                prompt = userPrompt,
                                targetAppName = event.targetAppName,
                                targetPackage = event.targetPackage,
                                reason = event.reason
                            )
                        )
                    }

                    is AgentEvent.AmbiguityChoiceRequired -> {
                        liveTaskCreated = true
                        taskNotificationManager?.showRunningProgress(
                            taskId = taskId,
                            title = userPrompt,
                            detail = event.request.reason
                        )
                    }

                    is AgentEvent.PlanUpdated -> {
                        latestPlan = event.plan
                        if (liveTaskCreated) {
                            val runStateNow = agentRunController.state.value
                            val taskStatus = when {
                                runStateNow.isPaused -> AgentTaskStatus.PAUSED
                                runStateNow.waitingForUnlock != null -> AgentTaskStatus.WAITING_FOR_USER_UNLOCK
                                runStateNow.pendingApproval != null -> AgentTaskStatus.AWAITING_APPROVAL
                                else -> AgentTaskStatus.RUNNING
                            }
                            taskRepository?.upsertLiveTask(
                                taskId = taskId,
                                title = userPrompt.take(64),
                                prompt = userPrompt,
                                status = taskStatus,
                                providerLabel = provider.providerType.displayName,
                                modelLabel = modelId,
                                requiredCapabilities = usedToolNames.toList(),
                                steps = event.plan.steps,
                                verificationSummary = event.plan.verificationSummary,
                                createdAtMillis = taskCreatedAt,
                                targetApp = runStateNow.currentTargetAppName
                            )
                        }
                    }

                    is AgentEvent.IntermediateMessagesProduced -> {
                        // Replace temporary streaming bubble so chronological order stays:
                        // USER -> ASSISTANT(toolCalls) -> TOOL(results) -> ASSISTANT(final)
                        deleteChatMessage(conversationId, currentAssistantId)
                        appendChatMessage(
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = event.assistantText,
                            status = MessageStatus.COMPLETE,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId,
                            toolCalls = event.assistantToolCalls
                        )
                        event.toolResults.forEach { res ->
                            val toolJson = res.outputJson
                                ?: JSONObject().put("error", res.errorMessage ?: res.displaySummary).toString()
                            appendChatMessage(
                                conversationId = conversationId,
                                role = MessageRole.TOOL,
                                content = toolJson,
                                status = if (res.status == ToolExecutionStatus.SUCCEEDED) MessageStatus.COMPLETE else MessageStatus.FAILED,
                                toolCallId = res.callId,
                                toolName = res.toolName
                            )
                        }
                        latestText = ""
                        currentAssistantId = appendChatMessage(
                            conversationId = conversationId,
                            role = MessageRole.ASSISTANT,
                            content = "",
                            status = MessageStatus.STREAMING,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId
                        )
                        streamingOverlay.value = StreamingOverlay(
                            messageId = currentAssistantId,
                            text = "",
                            isResponding = true
                        )
                    }

                    is AgentEvent.Completed -> {
                        val terminalStatus = if (event.partiallyCompleted) {
                            AgentTaskStatus.PARTIALLY_COMPLETED
                        } else {
                            AgentTaskStatus.COMPLETED
                        }
                        if (liveTaskCreated) {
                            taskNotificationManager?.showTerminalOutcome(
                                title = userPrompt,
                                detail = event.verification.summaryNote.ifBlank {
                                    if (event.partiallyCompleted) "Partially completed." else "Task completed."
                                },
                                isError = event.partiallyCompleted
                            )
                            taskRepository?.upsertLiveTask(
                                taskId = taskId,
                                title = userPrompt.take(64),
                                prompt = userPrompt,
                                status = terminalStatus,
                                providerLabel = provider.providerType.displayName,
                                modelLabel = modelId,
                                requiredCapabilities = usedToolNames.toList(),
                                steps = event.plan?.steps ?: latestPlan?.steps.orEmpty(),
                                summaryMessage = event.finalText.take(280),
                                verificationSummary = event.verification,
                                createdAtMillis = taskCreatedAt,
                                targetApp = agentRunController.state.value.currentTargetAppName
                            )
                        }
                        finalizeChatMessage(
                            messageId = currentAssistantId,
                            conversationId = conversationId,
                            content = event.finalText,
                            status = MessageStatus.COMPLETE,
                            errorMessage = null,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId,
                            tokenUsage = event.usage,
                            executionPlan = event.plan,
                            taskSummary = event.taskSummary,
                            verificationSummary = event.verification
                        )
                    }

                    is AgentEvent.Failed -> {
                        if (liveTaskCreated) {
                            taskNotificationManager?.showTerminalOutcome(
                                title = userPrompt,
                                detail = event.errorMessage,
                                isError = true
                            )
                            taskRepository?.upsertLiveTask(
                                taskId = taskId,
                                title = userPrompt.take(64),
                                prompt = userPrompt,
                                status = AgentTaskStatus.FAILED,
                                providerLabel = provider.providerType.displayName,
                                modelLabel = modelId,
                                requiredCapabilities = usedToolNames.toList(),
                                steps = event.plan?.steps ?: latestPlan?.steps.orEmpty(),
                                failureReason = event.errorMessage,
                                verificationSummary = event.verification,
                                createdAtMillis = taskCreatedAt
                            )
                        }
                        finalizeChatMessage(
                            messageId = currentAssistantId,
                            conversationId = conversationId,
                            content = event.partialText,
                            status = MessageStatus.FAILED,
                            errorMessage = event.errorMessage,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId,
                            tokenUsage = event.usage,
                            executionPlan = event.plan,
                            taskSummary = event.taskSummary,
                            verificationSummary = event.verification
                        )
                    }

                    is AgentEvent.Cancelled -> {
                        if (liveTaskCreated) {
                            taskNotificationManager?.showTerminalOutcome(
                                title = userPrompt,
                                detail = "Cancelled by user.",
                                isError = true
                            )
                            taskRepository?.upsertLiveTask(
                                taskId = taskId,
                                title = userPrompt.take(64),
                                prompt = userPrompt,
                                status = AgentTaskStatus.CANCELLED,
                                providerLabel = provider.providerType.displayName,
                                modelLabel = modelId,
                                requiredCapabilities = usedToolNames.toList(),
                                steps = event.plan?.steps ?: latestPlan?.steps.orEmpty(),
                                failureReason = "Cancelled by user",
                                verificationSummary = event.verification,
                                createdAtMillis = taskCreatedAt
                            )
                        }
                        finalizeChatMessage(
                            messageId = currentAssistantId,
                            conversationId = conversationId,
                            content = event.partialText,
                            status = MessageStatus.STOPPED,
                            errorMessage = if (event.partialText.isBlank()) "Generation stopped before output arrived." else null,
                            providerLabel = provider.providerType.displayName,
                            modelLabel = modelId,
                            tokenUsage = event.usage,
                            executionPlan = event.plan,
                            taskSummary = event.taskSummary,
                            verificationSummary = event.verification
                        )
                    }

                    is AgentEvent.ToolStarted -> {
                        if (liveTaskCreated) {
                            taskNotificationManager?.showRunningProgress(
                                taskId = taskId,
                                title = userPrompt,
                                detail = "Executing ${event.call.toolName}..."
                            )
                        }
                    }

                    is AgentEvent.ToolCompleted -> {
                        if (event.result.toolName == "save_memory_note" &&
                            event.result.status == ToolExecutionStatus.SUCCEEDED
                        ) {
                            memorySavedState.value = event.result.displaySummary
                        }
                        if (liveTaskCreated) {
                            taskNotificationManager?.showRunningProgress(
                                taskId = taskId,
                                title = userPrompt,
                                detail = "${event.result.toolName}: ${event.result.displaySummary}"
                            )
                        }
                    }

                    is AgentEvent.StatusChanged -> Unit
                    is AgentEvent.SubgoalProgressUpdated -> Unit
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                val cancelledSteps = latestPlan?.steps?.map { step ->
                    if (step.state in setOf(
                            ExecutionStepState.PENDING,
                            ExecutionStepState.RUNNING,
                            ExecutionStepState.AWAITING_CONFIRMATION
                        )
                    ) {
                        step.copy(
                            state = ExecutionStepState.CANCELLED,
                            detail = "Cancelled by user",
                            kind = StepKind.CANCELLED
                        )
                    } else {
                        step
                    }
                }.orEmpty()
                val cancelledPlan = latestPlan?.copy(steps = cancelledSteps)
                val cancelledSummary = if (liveTaskCreated) {
                    TaskSummary(
                        taskId = taskId,
                        title = userPrompt.take(64),
                        status = AgentTaskStatus.CANCELLED,
                        completedSteps = cancelledSteps.count { it.state == ExecutionStepState.COMPLETED },
                        totalSteps = cancelledSteps.size,
                        targetApp = "Nexa Tools",
                        isSampleData = false
                    )
                } else {
                    null
                }
                if (liveTaskCreated) {
                    taskNotificationManager?.showTerminalOutcome(
                        title = userPrompt,
                        detail = "Cancelled by user.",
                        isError = true
                    )
                    taskRepository?.upsertLiveTask(
                        taskId = taskId,
                        title = userPrompt.take(64),
                        prompt = userPrompt,
                        status = AgentTaskStatus.CANCELLED,
                        providerLabel = provider.providerType.displayName,
                        modelLabel = modelId,
                        requiredCapabilities = usedToolNames.toList(),
                        steps = cancelledSteps,
                        failureReason = "Cancelled by user.",
                        createdAtMillis = taskCreatedAt
                    )
                }
                finalizeChatMessage(
                    messageId = currentAssistantId,
                    conversationId = conversationId,
                    content = latestText,
                    status = MessageStatus.STOPPED,
                    errorMessage = if (latestText.isBlank()) "Generation stopped before output arrived." else null,
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId,
                    executionPlan = cancelledPlan,
                    taskSummary = cancelledSummary
                )
            }
            throw ce
        } finally {
            withContext(NonCancellable) {
                streamingOverlay.value = StreamingOverlay()
                agentRunController.finishRun()
            }
            if (!stopRequestedByUser && promptQueue.isNotEmpty()) {
                val nextPrompt = promptQueue.removeFirst()
                queuedCountState.value = promptQueue.size
                if (promptQueue.isEmpty()) {
                    queueNoticeState.value = null
                }
                launchPromptTurn(
                    promptToSend = nextPrompt.prompt,
                    currentAttachments = nextPrompt.attachments,
                    requestedConversationId = nextPrompt.conversationId ?: conversationId
                )
            }
        }
    }

    private fun isEphemeralConversation(conversationId: String): Boolean =
        ephemeralConversations.value.any { it.id == conversationId }

    private suspend fun createTargetConversation(settings: AppSettings): String {
        return if (settings.saveConversationHistory) {
            chatRepository.createConversation("New conversation")
        } else {
            val now = System.currentTimeMillis()
            val id = "incognito_${UUID.randomUUID()}"
            val conv = Conversation(
                id = id,
                title = "Temporary chat",
                isSamplePreview = false,
                createdAtMillis = now,
                updatedAtMillis = now,
                lastMessagePreview = "Not saving history"
            )
            ephemeralConversations.value = listOf(conv) + ephemeralConversations.value
            ephemeralMessages.value = ephemeralMessages.value + (id to emptyList())
            id
        }
    }

    private suspend fun resolveTargetConversationId(
        requestedId: String?,
        settings: AppSettings
    ): String {
        return if (settings.saveConversationHistory) {
            if (requestedId != null && !isEphemeralConversation(requestedId)) {
                requestedId
            } else {
                chatRepository.createConversation("New conversation")
            }
        } else {
            if (requestedId != null && isEphemeralConversation(requestedId)) {
                requestedId
            } else {
                createTargetConversation(settings)
            }
        }
    }

    private suspend fun getChatHistorySnapshot(conversationId: String): List<ChatMessage> {
        return ephemeralMessages.value[conversationId] ?: chatRepository.getMessagesSnapshot(conversationId)
    }

    private suspend fun appendChatMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE,
        errorMessage: String? = null,
        providerLabel: String? = null,
        modelLabel: String? = null,
        tokenUsage: TokenUsage? = null,
        executionPlan: ExecutionPlan? = null,
        taskSummary: TaskSummary? = null,
        attachments: List<String> = emptyList(),
        toolCalls: List<ToolCall> = emptyList(),
        toolCallId: String? = null,
        toolName: String? = null,
        verificationSummary: TaskVerificationSummary? = null
    ): String {
        if (!isEphemeralConversation(conversationId)) {
            return chatRepository.appendMessage(
                conversationId = conversationId,
                role = role,
                content = content,
                status = status,
                errorMessage = errorMessage,
                providerLabel = providerLabel,
                modelLabel = modelLabel,
                tokenUsage = tokenUsage,
                executionPlan = executionPlan,
                taskSummary = taskSummary,
                attachments = attachments,
                toolCalls = toolCalls,
                toolCallId = toolCallId,
                toolName = toolName,
                verificationSummary = verificationSummary
            )
        }
        val msgId = "eph_msg_${UUID.randomUUID()}"
        val now = System.currentTimeMillis()
        val msg = ChatMessage(
            id = msgId,
            conversationId = conversationId,
            role = role,
            content = content,
            createdAtMillis = now,
            status = status,
            errorMessage = errorMessage,
            providerLabel = providerLabel,
            modelLabel = modelLabel,
            tokenUsage = tokenUsage,
            executionPlan = executionPlan,
            taskSummary = taskSummary,
            attachments = attachments,
            isPreviewMessage = false,
            toolCalls = toolCalls,
            toolCallId = toolCallId,
            toolName = toolName,
            verificationSummary = verificationSummary
        )
        val existingList = ephemeralMessages.value[conversationId].orEmpty()
        ephemeralMessages.value = ephemeralMessages.value + (conversationId to (existingList + msg))
        ephemeralConversations.value = ephemeralConversations.value.map { conv ->
            if (conv.id == conversationId) {
                val updatedTitle = if (conv.title == "Temporary chat" && role == MessageRole.USER) {
                    content.trim().take(42).ifBlank { "Temporary chat" }
                } else {
                    conv.title
                }
                val preview = content.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
                    ?: errorMessage?.take(80)
                    ?: conv.lastMessagePreview
                conv.copy(title = updatedTitle, updatedAtMillis = now, lastMessagePreview = preview)
            } else {
                conv
            }
        }
        return msgId
    }

    private suspend fun finalizeChatMessage(
        messageId: String,
        conversationId: String,
        content: String,
        status: MessageStatus,
        errorMessage: String? = null,
        providerLabel: String? = null,
        modelLabel: String? = null,
        tokenUsage: TokenUsage? = null,
        executionPlan: ExecutionPlan? = null,
        taskSummary: TaskSummary? = null,
        verificationSummary: TaskVerificationSummary? = null
    ) {
        if (!isEphemeralConversation(conversationId)) {
            chatRepository.finalizeMessage(
                messageId = messageId,
                conversationId = conversationId,
                content = content,
                status = status,
                errorMessage = errorMessage,
                providerLabel = providerLabel,
                modelLabel = modelLabel,
                tokenUsage = tokenUsage,
                executionPlan = executionPlan,
                taskSummary = taskSummary,
                verificationSummary = verificationSummary
            )
            return
        }
        val list = ephemeralMessages.value[conversationId].orEmpty()
        val updated = list.map { existing ->
            if (existing.id == messageId) {
                existing.copy(
                    content = content,
                    status = status,
                    errorMessage = errorMessage,
                    providerLabel = providerLabel ?: existing.providerLabel,
                    modelLabel = modelLabel ?: existing.modelLabel,
                    tokenUsage = tokenUsage ?: existing.tokenUsage,
                    executionPlan = executionPlan ?: existing.executionPlan,
                    taskSummary = taskSummary ?: existing.taskSummary,
                    verificationSummary = verificationSummary ?: existing.verificationSummary
                )
            } else {
                existing
            }
        }
        ephemeralMessages.value = ephemeralMessages.value + (conversationId to updated)
    }

    private suspend fun deleteChatMessage(conversationId: String, messageId: String) {
        if (!isEphemeralConversation(conversationId)) {
            chatRepository.deleteMessage(messageId)
        } else {
            val list = ephemeralMessages.value[conversationId].orEmpty()
            ephemeralMessages.value = ephemeralMessages.value + (conversationId to list.filterNot { it.id == messageId })
        }
    }

    class Factory(
        private val chatRepository: ChatRepository,
        private val connectionsRepository: ConnectionsRepository,
        private val settingsRepository: SettingsRepository,
        private val toolRegistry: ToolRegistry,
        private val systemPromptBuilder: SystemPromptBuilder,
        private val contextBuilder: ConversationContextBuilder,
        private val attachmentProcessor: AttachmentProcessor,
        private val taskRepository: TaskRepository? = null,
        private val autonomousOrchestrator: AutonomousAgentOrchestrator? = null,
        private val agentRunController: AgentRunController = AgentRunController(),
        private val permissionManager: AndroidPermissionManager? = null,
        private val taskNotificationManager: NexaTaskNotificationManager? = null,
        private val mediaProjectionSessionManager: MediaProjectionSessionManager? = null,
        private val floatingIndicatorController: FloatingIndicatorController? = null,
        private val memoryRepository: MemoryRepository? = null,
        private val approvalAuthenticator: ApprovalAuthenticator? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ChatViewModel(
                chatRepository = chatRepository,
                connectionsRepository = connectionsRepository,
                settingsRepository = settingsRepository,
                toolRegistry = toolRegistry,
                systemPromptBuilder = systemPromptBuilder,
                contextBuilder = contextBuilder,
                attachmentProcessor = attachmentProcessor,
                taskRepository = taskRepository,
                autonomousOrchestrator = autonomousOrchestrator,
                agentRunController = agentRunController,
                permissionManager = permissionManager,
                taskNotificationManager = taskNotificationManager,
                mediaProjectionSessionManager = mediaProjectionSessionManager,
                floatingIndicatorController = floatingIndicatorController,
                memoryRepository = memoryRepository,
                approvalAuthenticator = approvalAuthenticator
            ) as T
        }
    }
}
