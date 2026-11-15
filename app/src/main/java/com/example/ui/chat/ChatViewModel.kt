package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.SettingsRepository
import com.example.data.repository.TaskRepository
import com.example.domain.agent.ActiveAgentRunState
import com.example.domain.agent.AgentRunController
import com.example.domain.agent.AgentTool
import com.example.domain.agent.AutonomousAgentOrchestrator
import com.example.domain.agent.DefaultAutonomousAgentOrchestrator
import com.example.domain.agent.DefaultSafetyPolicyManager
import com.example.domain.agent.ToolExecutionRuntime
import com.example.domain.agent.ToolRegistry
import com.example.domain.chat.AttachmentProcessor
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentExecutionContext
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AgentStatus
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ChatMessage
import com.example.domain.model.ComposerAttachment
import com.example.domain.model.Conversation
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStep
import com.example.domain.model.ExecutionStepState
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.domain.model.StepKind
import com.example.domain.model.TaskSummary
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.ToolDefinition
import com.example.domain.model.ToolExecutionStatus
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
    val isPaused: Boolean = false,
    val activeTaskId: String? = null,
    val isResponding: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val availableTools: List<AgentTool> = emptyList(),
    val executableTools: List<ToolDefinition> = emptyList()
)

private data class StreamingOverlay(
    val messageId: String? = null,
    val text: String = "",
    val isResponding: Boolean = false
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
    private val agentRunController: AgentRunController = AgentRunController()
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

    private var responseJob: Job? = null

    private val activeMessagesFlow = selectedConversationId.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else chatRepository.observeMessages(id)
    }

    private data class ChatMeta(
        val conversations: List<Conversation>,
        val selectedId: String?,
        val messages: List<ChatMessage>,
        val draft: String,
        val attachments: List<ComposerAttachment>
    )

    private data class UiExtras(
        val overlay: StreamingOverlay,
        val notices: List<String>,
        val settings: AppSettings,
        val runState: ActiveAgentRunState
    )

    private val chatMetaFlow = combine(
        chatRepository.conversations,
        selectedConversationId,
        activeMessagesFlow,
        draftText,
        pendingAttachments
    ) { conversations, selectedId, messages, draft, attachments ->
        ChatMeta(conversations, selectedId, messages, draft, attachments)
    }

    private val uiExtrasFlow = combine(
        streamingOverlay,
        attachmentNotices,
        settingsRepository.settings,
        agentRunController.state
    ) { overlay, notices, settings, runState ->
        UiExtras(overlay, notices, settings, runState)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        chatMetaFlow,
        connectionsRepository.providers,
        connectionsRepository.defaultProvider,
        uiExtrasFlow
    ) { meta, providers, defaultProvider, extras ->
        val (overlay, notices, settings, runState) = extras
        val liveConversations = meta.conversations.filterNot { it.isSamplePreview }
        val resolvedActive = liveConversations.firstOrNull { it.id == meta.selectedId }
            ?: liveConversations.firstOrNull()
        if (resolvedActive != null && resolvedActive.id != meta.selectedId) {
            selectedConversationId.value = resolvedActive.id
        }

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
            runState.pendingApproval != null || runState.runStatus == AgentRunStatus.AWAITING_APPROVAL -> AgentStatus.AWAITING_CONFIRMATION
            runState.runStatus == AgentRunStatus.SELECTING_TOOL ||
                runState.runStatus == AgentRunStatus.EXECUTING_TOOL ||
                runState.runStatus == AgentRunStatus.OBSERVING_RESULT -> AgentStatus.EXECUTING
            overlay.isResponding || runState.isRunning -> AgentStatus.THINKING
            !hasUsableProvider -> AgentStatus.OFFLINE
            else -> AgentStatus.IDLE
        }

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
            isPaused = runState.isPaused,
            activeTaskId = runState.activeTaskId,
            isResponding = overlay.isResponding,
            settings = settings,
            availableTools = toolRegistry.getAllTools(),
            executableTools = toolRegistry.getExecutableTools().map { it.definition }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState(
            availableTools = toolRegistry.getAllTools(),
            executableTools = toolRegistry.getExecutableTools().map { it.definition }
        )
    )

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

    fun selectConversation(conversationId: String) {
        stopResponse()
        selectedConversationId.value = conversationId
    }

    fun startNewConversation() {
        stopResponse()
        viewModelScope.launch {
            val newId = chatRepository.createConversation("New conversation")
            selectedConversationId.value = newId
        }
    }

    fun renameConversation(id: String, title: String) {
        viewModelScope.launch {
            chatRepository.renameConversation(id, title)
        }
    }

    fun deleteConversation(id: String) {
        lastTurnAttachments.remove(id)
        viewModelScope.launch {
            chatRepository.deleteConversation(id)
            if (selectedConversationId.value == id) {
                selectedConversationId.value = null
            }
        }
    }

    fun selectDefaultModel(provider: ProviderConfig, modelId: String) {
        viewModelScope.launch {
            connectionsRepository.setDefaultProvider(provider.providerType, modelId)
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

    fun rejectToolCall(callId: String) {
        agentRunController.submitApproval(callId, ApprovalDecision.REJECT)
    }

    fun sendMessage(overridePrompt: String? = null) {
        val text = (overridePrompt ?: draftText.value).trim()
        val currentAttachments = pendingAttachments.value
        if (text.isEmpty() && currentAttachments.isEmpty()) return
        if (streamingOverlay.value.isResponding) return

        val promptToSend = text.ifEmpty {
            "Analyze attached ${currentAttachments.size} item(s): ${currentAttachments.joinToString { it.displayName }}"
        }

        if (overridePrompt == null) {
            draftText.value = ""
        }
        pendingAttachments.value = emptyList()
        attachmentNotices.value = emptyList()

        responseJob = viewModelScope.launch {
            val targetConvId = selectedConversationId.value
                ?: chatRepository.createConversation("New conversation").also {
                    selectedConversationId.value = it
                }

            if (currentAttachments.isNotEmpty()) {
                lastTurnAttachments[targetConvId] = currentAttachments
            } else {
                lastTurnAttachments.remove(targetConvId)
            }

            chatRepository.appendMessage(
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
                chatRepository.appendMessage(
                    conversationId = targetConvId,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    status = MessageStatus.FAILED,
                    errorMessage = "No active AI provider and model are configured. Open Connections to configure a provider, then tap Retry."
                )
                return@launch
            }

            if (provider.credentialInvalidated) {
                chatRepository.appendMessage(
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
                chatRepository.appendMessage(
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

        responseJob = viewModelScope.launch {
            val targetConvId = failedOrStoppedMessage.conversationId
            chatRepository.deleteMessage(failedOrStoppedMessage.id)

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
                chatRepository.appendMessage(
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

            val latestPrompt = chatRepository.getMessagesSnapshot(targetConvId)
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
        val settings = settingsRepository.settings.first()
        val capabilities = connectionsRepository.capabilities.first()
        val allImplementedTools = toolRegistry.getAvailableToolDefinitions(capabilities)
        val availableTools = if (provider.effectiveSupportsTools) allImplementedTools else emptyList()
        val systemPrompt = systemPromptBuilder.build(
            settings = settings,
            availableTools = allImplementedTools,
            toolsEnabledForModel = provider.effectiveSupportsTools
        )
        val historySnapshot = chatRepository.getMessagesSnapshot(conversationId)

        var currentAssistantId = chatRepository.appendMessage(
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
                            chatRepository.finalizeMessage(
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
                            taskRepository?.updateLiveTaskOutcome(taskId, AgentTaskStatus.PAUSED)
                        }
                    }

                    is AgentEvent.Resumed -> {
                        if (liveTaskCreated) {
                            taskRepository?.updateLiveTaskOutcome(taskId, AgentTaskStatus.RUNNING)
                        }
                    }

                    is AgentEvent.PlanUpdated -> {
                        latestPlan = event.plan
                        if (liveTaskCreated) {
                            val runStateNow = agentRunController.state.value
                            val taskStatus = when {
                                runStateNow.isPaused -> AgentTaskStatus.PAUSED
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
                                createdAtMillis = taskCreatedAt
                            )
                        }
                    }

                    is AgentEvent.IntermediateMessagesProduced -> {
                        // Replace temporary streaming bubble so chronological order in Room stays:
                        // USER -> ASSISTANT(toolCalls) -> TOOL(results) -> ASSISTANT(final)
                        chatRepository.deleteMessage(currentAssistantId)
                        chatRepository.appendMessage(
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
                            chatRepository.appendMessage(
                                conversationId = conversationId,
                                role = MessageRole.TOOL,
                                content = toolJson,
                                status = if (res.status == ToolExecutionStatus.SUCCEEDED) MessageStatus.COMPLETE else MessageStatus.FAILED,
                                toolCallId = res.callId,
                                toolName = res.toolName
                            )
                        }
                        latestText = ""
                        currentAssistantId = chatRepository.appendMessage(
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
                        if (liveTaskCreated) {
                            taskRepository?.upsertLiveTask(
                                taskId = taskId,
                                title = userPrompt.take(64),
                                prompt = userPrompt,
                                status = AgentTaskStatus.COMPLETED,
                                providerLabel = provider.providerType.displayName,
                                modelLabel = modelId,
                                requiredCapabilities = usedToolNames.toList(),
                                steps = event.plan?.steps ?: latestPlan?.steps.orEmpty(),
                                summaryMessage = event.finalText.take(280),
                                verificationSummary = event.verification,
                                createdAtMillis = taskCreatedAt
                            )
                        }
                        chatRepository.finalizeMessage(
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
                        chatRepository.finalizeMessage(
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
                        chatRepository.finalizeMessage(
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

                    is AgentEvent.StatusChanged,
                    is AgentEvent.ToolStarted,
                    is AgentEvent.ToolCompleted -> Unit
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
                chatRepository.finalizeMessage(
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
        private val agentRunController: AgentRunController = AgentRunController()
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
                agentRunController = agentRunController
            ) as T
        }
    }
}
