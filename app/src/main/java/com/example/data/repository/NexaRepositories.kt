package com.example.data.repository

import com.example.data.local.AgentTaskEntity
import com.example.data.local.ConversationDao
import com.example.data.local.ConversationEntity
import com.example.data.local.MessageEntity
import com.example.data.local.NexaPreferencesDataStore
import com.example.data.local.NexaTypeConverters
import com.example.data.local.StoredStatusKind
import com.example.data.local.TaskDao
import com.example.domain.agent.CapabilityInspector
import com.example.domain.model.AgentTask
import com.example.domain.model.AgentTaskStatus
import com.example.domain.model.AiModelInfo
import com.example.domain.model.AiProviderType
import com.example.domain.model.AppSettings
import com.example.domain.model.ChatMessage
import com.example.domain.model.Conversation
import com.example.domain.model.DeviceCapability
import com.example.domain.model.ExecutionPlan
import com.example.domain.model.ExecutionStep
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ProviderConnectionStatus
import com.example.domain.model.TelegramConfig
import com.example.domain.model.TokenUsage
import com.example.domain.provider.AiProviderRegistry
import com.example.domain.provider.ConnectionTestResult
import com.example.domain.provider.ProviderError
import com.example.domain.provider.ProviderException
import com.example.domain.provider.ProviderRequest
import com.example.domain.provider.ProviderStreamEvent
import com.example.domain.security.DecryptResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ChatRepository(
    private val conversationDao: ConversationDao,
    private val converters: NexaTypeConverters
) {
    val conversations: Flow<List<Conversation>> =
        conversationDao.observeConversations().map { list ->
            list.map {
                Conversation(
                    id = it.id,
                    title = it.title,
                    isSamplePreview = it.isSamplePreview,
                    createdAtMillis = it.createdAtMillis,
                    updatedAtMillis = it.updatedAtMillis,
                    lastMessagePreview = it.lastMessagePreview
                )
            }
        }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        conversationDao.observeMessages(conversationId).map { rows ->
            rows.map { it.toDomain() }
        }

    suspend fun getMessagesSnapshot(conversationId: String): List<ChatMessage> =
        conversationDao.getMessagesSnapshot(conversationId).map { it.toDomain() }

    suspend fun createConversation(title: String = "New conversation"): String {
        val now = System.currentTimeMillis()
        val id = "conv_${UUID.randomUUID()}"
        conversationDao.upsertConversation(
            ConversationEntity(
                id = id,
                title = title,
                isSamplePreview = false,
                createdAtMillis = now,
                updatedAtMillis = now,
                lastMessagePreview = "Empty conversation"
            )
        )
        return id
    }

    suspend fun appendMessage(
        conversationId: String,
        role: MessageRole,
        content: String,
        status: MessageStatus = MessageStatus.COMPLETE,
        errorMessage: String? = null,
        providerLabel: String? = null,
        modelLabel: String? = null,
        tokenUsage: TokenUsage? = null,
        executionPlan: ExecutionPlan? = null,
        attachments: List<String> = emptyList(),
        isPreviewMessage: Boolean = false
    ): String {
        val now = System.currentTimeMillis()
        val existing = conversationDao.getConversation(conversationId)
        val previewLine = buildPreviewLine(content, errorMessage)
        val updatedTitle = if (existing == null || (existing.title == "New conversation" && role == MessageRole.USER)) {
            content.trim().take(42).ifBlank { "New conversation" }
        } else {
            existing.title
        }
        conversationDao.upsertConversation(
            ConversationEntity(
                id = conversationId,
                title = updatedTitle,
                isSamplePreview = existing?.isSamplePreview ?: false,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
                lastMessagePreview = previewLine
            )
        )
        val messageId = "msg_${UUID.randomUUID()}"
        conversationDao.insertMessage(
            MessageEntity(
                id = messageId,
                conversationId = conversationId,
                role = role,
                content = content,
                createdAtMillis = now,
                executionPlanJson = converters.encodeExecutionPlan(executionPlan),
                taskSummaryJson = null,
                attachmentsJson = if (attachments.isEmpty()) null else converters.encodeStringList(attachments),
                isPreviewMessage = isPreviewMessage,
                status = status,
                errorMessage = errorMessage,
                providerLabel = providerLabel,
                modelLabel = modelLabel,
                promptTokens = tokenUsage?.promptTokens,
                completionTokens = tokenUsage?.completionTokens,
                totalTokens = tokenUsage?.totalTokens
            )
        )
        return messageId
    }

    suspend fun finalizeMessage(
        messageId: String,
        conversationId: String,
        content: String,
        status: MessageStatus,
        errorMessage: String? = null,
        providerLabel: String? = null,
        modelLabel: String? = null,
        tokenUsage: TokenUsage? = null
    ) {
        val existing = conversationDao.getMessageById(messageId) ?: return
        conversationDao.updateMessage(
            existing.copy(
                content = content,
                status = status,
                errorMessage = errorMessage,
                providerLabel = providerLabel ?: existing.providerLabel,
                modelLabel = modelLabel ?: existing.modelLabel,
                promptTokens = tokenUsage?.promptTokens ?: existing.promptTokens,
                completionTokens = tokenUsage?.completionTokens ?: existing.completionTokens,
                totalTokens = tokenUsage?.totalTokens ?: existing.totalTokens
            )
        )
        val preview = buildPreviewLine(content, errorMessage)
        if (preview.isNotBlank()) {
            conversationDao.updateConversationPreview(
                id = conversationId,
                preview = preview,
                updatedAt = System.currentTimeMillis()
            )
        }
    }

    suspend fun deleteMessage(messageId: String) {
        conversationDao.deleteMessage(messageId)
    }

    suspend fun renameConversation(id: String, newTitle: String) {
        val clean = newTitle.trim()
        if (clean.isNotEmpty()) {
            conversationDao.renameConversation(id, clean, System.currentTimeMillis())
        }
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteConversation(id)
    }

    suspend fun clearAllConversations() {
        conversationDao.clearAllConversations()
    }

    private fun buildPreviewLine(content: String, errorMessage: String?): String {
        val fromContent = content.lineSequence().firstOrNull { it.isNotBlank() }?.take(80)
        if (!fromContent.isNullOrBlank()) return fromContent
        return errorMessage?.take(80).orEmpty()
    }

    private fun MessageEntity.toDomain(): ChatMessage {
        val usage = if (promptTokens != null || completionTokens != null || totalTokens != null) {
            TokenUsage(promptTokens, completionTokens, totalTokens)
        } else {
            null
        }
        return ChatMessage(
            id = id,
            conversationId = conversationId,
            role = role,
            content = content,
            createdAtMillis = createdAtMillis,
            status = status,
            errorMessage = errorMessage,
            providerLabel = providerLabel,
            modelLabel = modelLabel,
            tokenUsage = usage,
            executionPlan = converters.decodeExecutionPlan(executionPlanJson),
            taskSummary = converters.decodeTaskSummary(taskSummaryJson),
            attachments = converters.decodeStringList(attachmentsJson),
            isPreviewMessage = isPreviewMessage
        )
    }
}

class TaskRepository(
    private val taskDao: TaskDao,
    private val converters: NexaTypeConverters
) {
    val allTasks: Flow<List<AgentTask>> = taskDao.observeAllTasks().map { tasks ->
        tasks.map { it.toDomain(emptyList()) }
    }

    fun observeTaskWithSteps(taskId: String): Flow<AgentTask?> = combine(
        taskDao.observeTaskById(taskId),
        taskDao.observeStepsForTask(taskId)
    ) { task, steps ->
        task?.toDomain(
            steps.map { s ->
                ExecutionStep(
                    id = s.id,
                    title = s.title,
                    detail = s.detail,
                    state = s.state,
                    toolName = s.toolName,
                    timestampLabel = s.timestampLabel
                )
            }
        )
    }

    suspend fun updateSampleTaskStatus(taskId: String, newStatus: AgentTaskStatus) {
        taskDao.updateTaskStatus(taskId, newStatus, System.currentTimeMillis())
    }

    private fun AgentTaskEntity.toDomain(steps: List<ExecutionStep>): AgentTask = AgentTask(
        id = id,
        title = title,
        prompt = prompt,
        status = status,
        isSampleData = isSampleData,
        providerLabel = providerLabel,
        modelLabel = modelLabel,
        targetApp = targetApp,
        requiredCapabilities = converters.decodeStringList(requiredCapabilitiesJson),
        steps = steps,
        failureReason = failureReason,
        createdAtMillis = createdAtMillis,
        updatedAtMillis = updatedAtMillis
    )
}

class ConnectionsRepository(
    private val prefs: NexaPreferencesDataStore,
    private val providerRegistry: AiProviderRegistry,
    private val capabilityInspector: CapabilityInspector
) {
    val providers: Flow<List<ProviderConfig>> = combine(
        prefs.providerEntries,
        prefs.defaultProviderId
    ) { entries, defaultId ->
        AiProviderType.entries.map { type ->
            val stored = entries[type]
            val adapter = providerRegistry.getAdapter(type)
            val catalogModels = stored?.cachedModels?.takeIf { it.isNotEmpty() } ?: adapter.defaultModels()
            val manualId = stored?.manualModelId?.trim().orEmpty()
            val mergedModels = buildList {
                addAll(catalogModels)
                if (manualId.isNotEmpty() && none { it.id == manualId }) {
                    add(
                        0,
                        AiModelInfo(
                            id = manualId,
                            displayName = manualId,
                            contextWindowLabel = "Unknown",
                            contextLengthTokens = null,
                            supportsVision = false,
                            supportsTools = false,
                            isManualEntry = true,
                            isCatalogVerified = false
                        )
                    )
                }
            }
            val hasKey = !stored?.maskedKey.isNullOrBlank()
            val hasEndpointReady = if (type.requiresBaseUrl) !stored?.baseUrl.isNullOrBlank() else hasKey
            val selectedModel = stored?.selectedModelId ?: mergedModels.firstOrNull()?.id

            val status = when (stored?.statusKind) {
                StoredStatusKind.KEY_INVALIDATED -> ProviderConnectionStatus.KeyInvalidated(
                    stored.statusMessage.ifBlank { "Stored key could not be decrypted. Re-enter your API key." }
                )
                StoredStatusKind.CONNECTED -> ProviderConnectionStatus.Connected(
                    stored.statusMessage.ifBlank { "Connected" },
                    stored.modelCount
                )
                StoredStatusKind.AUTH_FAILED -> ProviderConnectionStatus.AuthFailed(stored.statusMessage)
                StoredStatusKind.RATE_LIMITED -> ProviderConnectionStatus.RateLimited(stored.statusMessage)
                StoredStatusKind.ENDPOINT_ERROR -> ProviderConnectionStatus.EndpointError(stored.statusMessage)
                StoredStatusKind.NETWORK_ERROR -> ProviderConnectionStatus.NetworkError(stored.statusMessage)
                StoredStatusKind.VALIDATION_ERROR -> ProviderConnectionStatus.ValidationError(stored.statusMessage)
                else -> if (hasEndpointReady) {
                    ProviderConnectionStatus.ConfiguredUnverified
                } else {
                    ProviderConnectionStatus.NotConfigured
                }
            }

            ProviderConfig(
                providerType = type,
                hasApiKey = hasKey,
                maskedApiKey = stored?.maskedKey ?: "",
                baseUrl = stored?.baseUrl?.ifBlank { type.defaultBaseUrl } ?: type.defaultBaseUrl,
                selectedModelId = selectedModel,
                manualModelId = manualId,
                isDefault = defaultId == type.id,
                availableModels = mergedModels,
                isConnected = stored?.statusKind == StoredStatusKind.CONNECTED,
                credentialInvalidated = stored?.credentialInvalidated == true,
                connectionStatus = status
            )
        }
    }

    val defaultProvider: Flow<ProviderConfig?> = providers.map { list ->
        list.firstOrNull { it.isDefault }
    }

    val telegramConfig: Flow<TelegramConfig> = prefs.telegramConfig

    val capabilities: Flow<List<DeviceCapability>> = prefs.capabilityToggles.map { toggles ->
        capabilityInspector.inspectAll(toggles)
    }

    suspend fun saveProvider(
        type: AiProviderType,
        rawApiKey: String?,
        baseUrl: String,
        selectedModelId: String?,
        manualModelId: String? = null,
        setAsDefault: Boolean
    ) = prefs.saveProviderConfig(
        type = type,
        rawApiKey = rawApiKey,
        baseUrl = baseUrl,
        selectedModelId = selectedModelId,
        manualModelId = manualModelId,
        setAsDefault = setAsDefault
    )

    suspend fun saveManualModel(type: AiProviderType, modelId: String, setAsDefault: Boolean) =
        prefs.saveManualModel(type, modelId, setAsDefault)

    suspend fun clearProviderKey(type: AiProviderType) = prefs.clearProviderKey(type)

    suspend fun setDefaultProvider(type: AiProviderType, modelId: String?) =
        prefs.setDefaultProvider(type, modelId)

    suspend fun testProviderConfig(config: ProviderConfig): ProviderConnectionStatus {
        val rawKey = when (val decrypt = prefs.readDecryptedApiKey(config.providerType)) {
            is DecryptResult.Success -> decrypt.plainText
            is DecryptResult.Empty -> ""
            is DecryptResult.KeyInvalidated -> {
                prefs.saveConnectionStatus(config.providerType, StoredStatusKind.KEY_INVALIDATED, decrypt.reason)
                return ProviderConnectionStatus.KeyInvalidated(decrypt.reason)
            }
        }

        val adapter = providerRegistry.getAdapter(config.providerType)
        return when (val result = adapter.testConfiguration(config, rawKey)) {
            is ConnectionTestResult.Success -> {
                val modelsToCache = result.fetchedModels.takeIf { it.isNotEmpty() }
                prefs.saveConnectionStatus(
                    type = config.providerType,
                    kind = StoredStatusKind.CONNECTED,
                    message = result.message,
                    fetchedModels = modelsToCache
                )
                ProviderConnectionStatus.Connected(
                    message = result.message,
                    modelCount = result.fetchedModels.size.takeIf { it > 0 }
                )
            }
            is ConnectionTestResult.Failure -> {
                val (kind, status) = mapErrorToStatus(result.error)
                prefs.saveConnectionStatus(config.providerType, kind, result.error.userMessage)
                status
            }
        }
    }

    suspend fun refreshModels(config: ProviderConfig): Result<List<AiModelInfo>> {
        val rawKey = when (val decrypt = prefs.readDecryptedApiKey(config.providerType)) {
            is DecryptResult.Success -> decrypt.plainText
            is DecryptResult.Empty -> ""
            is DecryptResult.KeyInvalidated -> {
                prefs.saveConnectionStatus(config.providerType, StoredStatusKind.KEY_INVALIDATED, decrypt.reason)
                return Result.failure(ProviderException(ProviderError.InvalidApiKey(decrypt.reason)))
            }
        }
        val adapter = providerRegistry.getAdapter(config.providerType)
        return adapter.fetchModels(config, rawKey).onSuccess { models ->
            if (models.isNotEmpty()) {
                prefs.saveConnectionStatus(
                    type = config.providerType,
                    kind = StoredStatusKind.CONNECTED,
                    message = "Loaded ${models.size} models from ${config.providerType.displayName}.",
                    fetchedModels = models
                )
            }
        }
    }

    fun streamChat(
        provider: ProviderConfig,
        request: ProviderRequest
    ): Flow<ProviderStreamEvent> = flow {
        val rawKey = when (val decrypt = prefs.readDecryptedApiKey(provider.providerType)) {
            is DecryptResult.Success -> decrypt.plainText
            is DecryptResult.Empty -> ""
            is DecryptResult.KeyInvalidated -> {
                emit(ProviderStreamEvent.Failed(ProviderError.InvalidApiKey(decrypt.reason)))
                return@flow
            }
        }
        val adapter = providerRegistry.getAdapter(provider.providerType)
        adapter.streamChat(provider, rawKey, request).collect { emit(it) }
    }

    private fun mapErrorToStatus(error: ProviderError): Pair<StoredStatusKind, ProviderConnectionStatus> {
        return when (error) {
            is ProviderError.MissingApiKey,
            is ProviderError.InvalidApiKey ->
                StoredStatusKind.AUTH_FAILED to ProviderConnectionStatus.AuthFailed(error.userMessage)
            is ProviderError.RateLimitExceeded ->
                StoredStatusKind.RATE_LIMITED to ProviderConnectionStatus.RateLimited(error.userMessage)
            is ProviderError.InvalidEndpoint,
            is ProviderError.UnsupportedModel,
            is ProviderError.MalformedResponse ->
                StoredStatusKind.ENDPOINT_ERROR to ProviderConnectionStatus.EndpointError(error.userMessage)
            is ProviderError.NetworkUnavailable,
            is ProviderError.Timeout,
            is ProviderError.ProviderUnavailable ->
                StoredStatusKind.NETWORK_ERROR to ProviderConnectionStatus.NetworkError(error.userMessage)
            else ->
                StoredStatusKind.VALIDATION_ERROR to ProviderConnectionStatus.ValidationError(error.userMessage)
        }
    }

    suspend fun saveTelegramDraft(rawBotToken: String, handle: String) =
        prefs.saveTelegramDraft(rawBotToken, handle)

    suspend fun updateTelegramPermissions(
        allowRemotePrompts: Boolean? = null,
        requireDeviceApproval: Boolean? = null,
        shareTaskSummaries: Boolean? = null
    ) = prefs.updateTelegramPermissions(allowRemotePrompts, requireDeviceApproval, shareTaskSummaries)

    suspend fun disconnectTelegram() = prefs.disconnectTelegram()

    suspend fun setCapabilityToggle(id: String, enabled: Boolean) =
        prefs.setCapabilityToggle(id, enabled)
}

class SettingsRepository(
    private val prefs: NexaPreferencesDataStore
) {
    val settings: Flow<AppSettings> = prefs.appSettings

    suspend fun updateSettings(transform: (AppSettings) -> AppSettings) =
        prefs.updateSettings(transform)
}
