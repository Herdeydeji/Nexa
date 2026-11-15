package com.example.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.repository.ChatRepository
import com.example.data.repository.ConnectionsRepository
import com.example.data.repository.SettingsRepository
import com.example.domain.agent.AgentTool
import com.example.domain.agent.ToolRegistry
import com.example.domain.chat.AttachmentProcessor
import com.example.domain.chat.ConversationContextBuilder
import com.example.domain.chat.SystemPromptBuilder
import com.example.domain.model.AgentStatus
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.ComposerAttachment
import com.example.domain.model.Conversation
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.domain.model.TokenUsage
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderStreamEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
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
    val isResponding: Boolean = false,
    val settings: AppSettings = AppSettings(),
    val availableTools: List<AgentTool> = emptyList()
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
    private val attachmentProcessor: AttachmentProcessor
) : ViewModel() {

    private val selectedConversationId = MutableStateFlow<String?>(null)
    private val draftText = MutableStateFlow("")
    private val pendingAttachments = MutableStateFlow<List<ComposerAttachment>>(emptyList())
    private val attachmentNotices = MutableStateFlow<List<String>>(emptyList())
    private val streamingOverlay = MutableStateFlow(StreamingOverlay())

    private var responseJob: Job? = null
    @Volatile
    private var cancelledByUser: Boolean = false

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
        settingsRepository.settings
    ) { overlay, notices, settings ->
        Triple(overlay, notices, settings)
    }

    val uiState: StateFlow<ChatUiState> = combine(
        chatMetaFlow,
        connectionsRepository.providers,
        connectionsRepository.defaultProvider,
        uiExtrasFlow
    ) { meta, providers, defaultProvider, extras ->
        val (overlay, notices, settings) = extras
        val liveConversations = meta.conversations.filterNot { it.isSamplePreview }
        val resolvedActive = liveConversations.firstOrNull { it.id == meta.selectedId }
            ?: liveConversations.firstOrNull()
        if (resolvedActive != null && resolvedActive.id != meta.selectedId) {
            selectedConversationId.value = resolvedActive.id
        }

        val mergedMessages = meta.messages.map { msg ->
            if (overlay.isResponding && overlay.messageId == msg.id) {
                msg.copy(
                    content = overlay.text,
                    status = MessageStatus.STREAMING
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
            overlay.isResponding -> AgentStatus.THINKING
            !hasUsableProvider -> AgentStatus.OFFLINE
            else -> AgentStatus.IDLE
        }

        ChatUiState(
            conversations = liveConversations,
            activeConversation = resolvedActive,
            messages = mergedMessages,
            draftText = meta.draft,
            attachments = meta.attachments,
            attachmentNotices = notices,
            defaultProvider = defaultProvider,
            allProviders = providers,
            agentStatus = status,
            isResponding = overlay.isResponding,
            settings = settings,
            availableTools = toolRegistry.getAllTools()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ChatUiState(availableTools = toolRegistry.getAllTools())
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

            streamAssistantReply(
                conversationId = targetConvId,
                provider = provider,
                modelId = modelId,
                processedAttachments = processedAttachments.filter { it.isSupported }
            )
        }
    }

    fun retryMessage(failedOrStoppedMessage: ChatMessage) {
        if (streamingOverlay.value.isResponding) return
        if (failedOrStoppedMessage.role != MessageRole.ASSISTANT) return

        responseJob = viewModelScope.launch {
            val targetConvId = failedOrStoppedMessage.conversationId
            chatRepository.deleteMessage(failedOrStoppedMessage.id)

            val provider = connectionsRepository.defaultProvider.first()
                ?: connectionsRepository.providers.first().firstOrNull {
                    !it.credentialInvalidated &&
                        (it.hasApiKey || (it.providerType.requiresBaseUrl && it.baseUrl.isNotBlank()))
                }
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

            streamAssistantReply(
                conversationId = targetConvId,
                provider = provider,
                modelId = modelId,
                processedAttachments = emptyList()
            )
        }
    }

    fun stopResponse() {
        if (responseJob?.isActive == true) {
            cancelledByUser = true
            responseJob?.cancel()
        }
    }

    private suspend fun streamAssistantReply(
        conversationId: String,
        provider: ProviderConfig,
        modelId: String,
        processedAttachments: List<com.example.domain.model.AttachmentPayload>
    ) {
        cancelledByUser = false
        val settings = settingsRepository.settings.first()
        val systemPrompt = systemPromptBuilder.build(settings)
        val historySnapshot = chatRepository.getMessagesSnapshot(conversationId)
        val contextResult = contextBuilder.buildContext(
            history = historySnapshot,
            systemPrompt = systemPrompt,
            modelContextLengthTokens = provider.selectedModelInfo?.contextLengthTokens
        )

        if (contextResult.messages.isEmpty()) {
            chatRepository.appendMessage(
                conversationId = conversationId,
                role = MessageRole.ASSISTANT,
                content = "",
                status = MessageStatus.FAILED,
                errorMessage = "Nothing to send in this conversation.",
                providerLabel = provider.providerType.displayName,
                modelLabel = modelId
            )
            return
        }

        val assistantId = chatRepository.appendMessage(
            conversationId = conversationId,
            role = MessageRole.ASSISTANT,
            content = "",
            status = MessageStatus.STREAMING,
            providerLabel = provider.providerType.displayName,
            modelLabel = modelId
        )

        val streamBuffer = StringBuilder()
        var latestUsage: TokenUsage? = null
        var failureMessage: String? = null
        var completedCleanly = false
        var lastPersistedAt = System.currentTimeMillis()

        streamingOverlay.value = StreamingOverlay(
            messageId = assistantId,
            text = "",
            isResponding = true
        )

        try {
            val request = ProviderRequest(
                modelId = modelId,
                messages = contextResult.messages,
                systemPrompt = systemPrompt,
                attachments = processedAttachments,
                modelInfo = provider.selectedModelInfo
            )

            connectionsRepository.streamChat(provider, request).collect { event ->
                when (event) {
                    is ProviderStreamEvent.Delta -> {
                        streamBuffer.append(event.text)
                        val textNow = streamBuffer.toString()
                        streamingOverlay.value = StreamingOverlay(
                            messageId = assistantId,
                            text = textNow,
                            isResponding = true
                        )
                        val now = System.currentTimeMillis()
                        if (now - lastPersistedAt > 500L) {
                            lastPersistedAt = now
                            chatRepository.finalizeMessage(
                                messageId = assistantId,
                                conversationId = conversationId,
                                content = textNow,
                                status = MessageStatus.STREAMING,
                                tokenUsage = latestUsage
                            )
                        }
                    }
                    is ProviderStreamEvent.Usage -> {
                        latestUsage = event.usage
                    }
                    is ProviderStreamEvent.Completed -> {
                        if (event.usage != null) latestUsage = event.usage
                        completedCleanly = true
                    }
                    is ProviderStreamEvent.Failed -> {
                        failureMessage = event.error.userMessage
                    }
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                val partial = streamBuffer.toString()
                chatRepository.finalizeMessage(
                    messageId = assistantId,
                    conversationId = conversationId,
                    content = partial,
                    status = MessageStatus.STOPPED,
                    errorMessage = if (partial.isBlank()) "Generation stopped before output arrived." else null,
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId,
                    tokenUsage = latestUsage
                )
            }
            streamingOverlay.value = StreamingOverlay()
            throw ce
        } finally {
            if (!cancelledByUser) {
                streamingOverlay.value = StreamingOverlay()
            }
        }

        val finalText = streamBuffer.toString()
        val err = failureMessage
        when {
            cancelledByUser -> {
                chatRepository.finalizeMessage(
                    messageId = assistantId,
                    conversationId = conversationId,
                    content = finalText,
                    status = MessageStatus.STOPPED,
                    errorMessage = if (finalText.isBlank()) "Generation stopped before output arrived." else null,
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId,
                    tokenUsage = latestUsage
                )
            }
            err != null -> {
                chatRepository.finalizeMessage(
                    messageId = assistantId,
                    conversationId = conversationId,
                    content = finalText,
                    status = MessageStatus.FAILED,
                    errorMessage = err,
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId,
                    tokenUsage = latestUsage
                )
            }
            completedCleanly && finalText.isBlank() -> {
                chatRepository.finalizeMessage(
                    messageId = assistantId,
                    conversationId = conversationId,
                    content = "",
                    status = MessageStatus.FAILED,
                    errorMessage = "Provider returned an empty response. Please retry or check model compatibility.",
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId,
                    tokenUsage = latestUsage
                )
            }
            else -> {
                chatRepository.finalizeMessage(
                    messageId = assistantId,
                    conversationId = conversationId,
                    content = finalText,
                    status = MessageStatus.COMPLETE,
                    errorMessage = null,
                    providerLabel = provider.providerType.displayName,
                    modelLabel = modelId,
                    tokenUsage = latestUsage
                )
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
        private val attachmentProcessor: AttachmentProcessor
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
                attachmentProcessor = attachmentProcessor
            ) as T
        }
    }
}
