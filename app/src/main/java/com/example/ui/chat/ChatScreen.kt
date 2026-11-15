package com.example.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.agent.RegisteredToolEntry
import com.example.domain.agent.ToolAvailabilityStatus
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AgentStatus
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.ChatMessage
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.MediaProjectionConsentState
import com.example.domain.model.MessageRole
import com.example.domain.model.MessageStatus
import com.example.domain.model.ProviderConfig
import com.example.ui.components.ExpandableAgentExecutionPanel
import com.example.ui.components.MarkdownTextContent
import com.example.ui.components.NexaBrandMark
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SampleDataBadge
import com.example.ui.components.StatusPill
import com.example.ui.components.TaskSummaryCard
import com.example.ui.theme.NexaBackground
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaBorderSubtle
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaErrorContainer
import com.example.ui.theme.NexaInfo
import com.example.ui.theme.NexaInfoContainer
import com.example.ui.theme.NexaNeutral
import com.example.ui.theme.NexaNeutralContainer
import com.example.ui.theme.NexaOnOrange
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaSuccess
import com.example.ui.theme.NexaSuccessContainer
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary
import com.example.ui.theme.NexaTextTertiary
import com.example.ui.theme.NexaWarning
import com.example.ui.theme.NexaWarningContainer

@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenTaskDetail: (String) -> Unit,
    onNavigateToConnections: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshDevicePermissions()
    }

    var showHistorySheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showVoiceSheet by remember { mutableStateOf(false) }
    var showToolsSheet by remember { mutableStateOf(false) }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val name = resolveDisplayName(context, uri) ?: "Selected image"
            val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
            viewModel.addAttachment(name, mime, uri.toString())
        }
    }

    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = resolveDisplayName(context, uri) ?: "Selected file"
            val mime = context.contentResolver.getType(uri) ?: "text/plain"
            viewModel.addAttachment(name, mime, uri.toString())
        }
    }

    val screenCaptureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onMediaProjectionConsentResult(result.resultCode, result.data)
    }

    val onOpenAccessibilitySettings = remember(context) {
        {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(intent) }
            Unit
        }
    }

    val onRequestScreenCapture = remember(viewModel, screenCaptureLauncher) {
        {
            val consentIntent = viewModel.createScreenCaptureIntent()
            if (consentIntent != null) {
                runCatching { screenCaptureLauncher.launch(consentIntent) }
            }
            Unit
        }
    }

    val isNearBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total == 0) {
                true
            } else {
                val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisible >= total - 2
            }
        }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    LaunchedEffect(state.messages.lastOrNull()?.content?.length) {
        if (state.messages.isNotEmpty() && isNearBottom) {
            listState.scrollToItem(state.messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaBackground)
    ) {
        ChatTopHeader(
            modelLabel = state.defaultProvider?.let { prov ->
                val modelShort = prov.selectedModelId?.substringAfterLast("/") ?: "Select model"
                "${prov.providerType.displayName} · $modelShort"
            } ?: stringResource(R.string.chat_no_active_model),
            agentStatus = state.agentStatus,
            onModelClick = { showModelSheet = true },
            onNewChatClick = { viewModel.startNewConversation() },
            onHistoryClick = { showHistorySheet = true }
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (state.messages.isEmpty()) {
                ChatEmptyWelcome(
                    defaultProvider = state.defaultProvider,
                    capabilities = state.capabilities,
                    registeredTools = state.registeredTools,
                    accessibilityState = state.accessibilityState,
                    mediaProjectionState = state.mediaProjectionState,
                    onPromptSelect = { prompt -> viewModel.sendMessage(prompt) },
                    onConfigureProvider = onNavigateToConnections,
                    onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                    onRequestScreenCapture = onRequestScreenCapture,
                    onStopScreenCapture = viewModel::stopMediaProjectionSession,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("chat_message_list"),
                    contentPadding = PaddingValues(
                        horizontal = 16.dp,
                        vertical = if (state.settings.compactMessageSpacing) 10.dp else 16.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(
                        if (state.settings.compactMessageSpacing) 12.dp else 18.dp
                    )
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        ChatMessageRow(
                            message = message,
                            autoExpandPlan = state.settings.autoExpandExecutionPanel,
                            showSampleBadges = state.settings.showSampleBadges,
                            showCodeLineNumbers = state.settings.showCodeLineNumbers,
                            isResponding = state.isResponding,
                            isAccessibilityEnabled = state.isAccessibilityEnabled,
                            isMediaProjectionActive = state.isMediaProjectionActive,
                            mediaProjectionState = state.mediaProjectionState,
                            onOpenTaskDetail = onOpenTaskDetail,
                            onRetry = { viewModel.retryMessage(message) },
                            onOpenConnections = onNavigateToConnections,
                            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                            onRequestScreenCapture = onRequestScreenCapture
                        )
                    }
                }
            }
        }

        if (state.isResponding) {
            Surface(
                color = NexaSecondaryBackground,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("chat_live_execution_bar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = state.runStatus.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (state.isPaused) NexaInfo else NexaOrange
                        )
                        if (state.statusDetail.isNotBlank()) {
                            Text(
                                text = state.statusDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (state.isPaused) viewModel.resumeExecution() else viewModel.pauseExecution()
                            },
                            modifier = Modifier.testTag("chat_pause_resume_button")
                        ) {
                            Text(if (state.isPaused) "Resume" else "Pause")
                        }
                        OutlinedButton(
                            onClick = viewModel::stopResponse,
                            modifier = Modifier.testTag("chat_cancel_run_button")
                        ) {
                            Text("Cancel", color = NexaError)
                        }
                    }
                }
            }
        }

        state.pendingApproval?.let { approvalRequest ->
            com.example.ui.components.ApprovalGateCard(
                request = approvalRequest,
                onApprove = { viewModel.approveToolCall(approvalRequest.callId) },
                onReject = { viewModel.rejectToolCall(approvalRequest.callId) },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        if (state.attachmentNotices.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NoticeBanner(
                    icon = Icons.Outlined.WarningAmber,
                    text = state.attachmentNotices.joinToString(" "),
                    accentColor = NexaWarning,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = viewModel::dismissAttachmentNotices) {
                    Text("Dismiss", color = NexaTextSecondary)
                }
            }
        }

        ChatComposer(
            draftText = state.draftText,
            attachments = state.attachments,
            isResponding = state.isResponding,
            onDraftChange = viewModel::onDraftChanged,
            onRemoveAttachment = viewModel::removeAttachment,
            onSendClick = { viewModel.sendMessage() },
            onStopClick = viewModel::stopResponse,
            onAttachClick = { showAttachmentSheet = true },
            onVoiceClick = { showVoiceSheet = true },
            onToolsClick = { showToolsSheet = true }
        )
    }

    if (showHistorySheet) {
        ConversationHistorySheet(
            conversations = state.conversations,
            activeConversationId = state.activeConversation?.id,
            onSelectConversation = viewModel::selectConversation,
            onNewConversation = viewModel::startNewConversation,
            onRenameConversation = viewModel::renameConversation,
            onDeleteConversation = viewModel::deleteConversation,
            onDismiss = { showHistorySheet = false }
        )
    }

    if (showModelSheet) {
        ModelSelectorSheet(
            providers = state.allProviders,
            onSelectModel = viewModel::selectDefaultModel,
            onOpenConnections = onNavigateToConnections,
            onDismiss = { showModelSheet = false }
        )
    }

    if (showAttachmentSheet) {
        AttachmentPickerSheet(
            onPickImage = {
                photoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onPickDocument = {
                documentPickerLauncher.launch(arrayOf("*/*"))
            },
            onDismiss = { showAttachmentSheet = false }
        )
    }

    if (showVoiceSheet) {
        VoiceInputSheet(
            onInsertSamplePhrase = { phrase -> viewModel.onDraftChanged(phrase) },
            onDismiss = { showVoiceSheet = false }
        )
    }

    if (showToolsSheet) {
        ToolsCatalogSheet(
            tools = state.availableTools,
            executableTools = state.executableTools,
            registeredTools = state.registeredTools,
            onOpenAccessibilitySettings = onOpenAccessibilitySettings,
            onRequestScreenCapture = onRequestScreenCapture,
            onDismiss = { showToolsSheet = false }
        )
    }
}

@Composable
private fun ChatTopHeader(
    modelLabel: String,
    agentStatus: AgentStatus,
    onModelClick: () -> Unit,
    onNewChatClick: () -> Unit,
    onHistoryClick: () -> Unit
) {
    Surface(
        color = NexaSecondaryBackground,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NexaBrandMark(size = 34.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = NexaTextPrimary
                        )
                        AgentStatusPill(status = agentStatus)
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onModelClick)
                            .padding(vertical = 2.dp)
                            .testTag("header_model_selector"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = modelLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = NexaTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.Outlined.ExpandMore,
                            contentDescription = "Select AI model",
                            tint = NexaTextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onNewChatClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("header_new_chat_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.AddComment,
                        contentDescription = stringResource(R.string.chat_new_conversation),
                        tint = NexaTextPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = onHistoryClick,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("header_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.History,
                        contentDescription = stringResource(R.string.chat_history),
                        tint = NexaTextPrimary,
                        modifier = Modifier.size(21.dp)
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(NexaBorderSubtle)
            )
        }
    }
}

@Composable
private fun AgentStatusPill(status: AgentStatus) {
    val (fg, bg) = when (status) {
        AgentStatus.IDLE -> NexaSuccess to NexaSuccessContainer
        AgentStatus.PREVIEW -> NexaOrange to NexaOrangeMuted
        AgentStatus.THINKING, AgentStatus.EXECUTING -> NexaInfo to NexaInfoContainer
        AgentStatus.AWAITING_CONFIRMATION -> NexaWarning to NexaWarningContainer
        AgentStatus.PAUSED -> NexaInfo to NexaInfoContainer
        AgentStatus.OFFLINE -> NexaNeutral to NexaNeutralContainer
    }
    StatusPill(label = status.label, textColor = fg, containerColor = bg)
}

@Composable
private fun ChatMessageRow(
    message: ChatMessage,
    autoExpandPlan: Boolean,
    showSampleBadges: Boolean,
    showCodeLineNumbers: Boolean,
    isResponding: Boolean,
    isAccessibilityEnabled: Boolean,
    isMediaProjectionActive: Boolean,
    mediaProjectionState: MediaProjectionConsentState,
    onOpenTaskDetail: (String) -> Unit,
    onRetry: () -> Unit,
    onOpenConnections: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestScreenCapture: () -> Unit
) {
    if (message.role == MessageRole.USER) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 310.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(NexaElevatedSurface)
                    .border(1.dp, NexaBorder, RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexaTextPrimary
                )
                if (message.attachments.isNotEmpty()) {
                    Text(
                        text = "Attached: ${message.attachments.joinToString()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = NexaTextSecondary
                    )
                }
            }
        }
    } else {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NexaBrandMark(size = 22.dp)
                Text(
                    text = "Nexa",
                    style = MaterialTheme.typography.titleSmall,
                    color = NexaTextPrimary
                )
                if (!message.modelLabel.isNullOrBlank()) {
                    Text(
                        text = message.modelLabel.substringAfterLast("/"),
                        style = MaterialTheme.typography.labelSmall,
                        color = NexaTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
                when (message.status) {
                    MessageStatus.STREAMING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(12.dp),
                            strokeWidth = 1.8.dp,
                            color = NexaOrange
                        )
                    }
                    MessageStatus.STOPPED -> {
                        StatusPill(
                            label = "Stopped",
                            textColor = NexaWarning,
                            containerColor = NexaWarningContainer,
                            showDot = false
                        )
                    }
                    MessageStatus.FAILED -> {
                        StatusPill(
                            label = "Error",
                            textColor = NexaError,
                            containerColor = NexaErrorContainer,
                            showDot = false
                        )
                    }
                    MessageStatus.COMPLETE -> {
                        if (message.isPreviewMessage && showSampleBadges) {
                            SampleDataBadge()
                        }
                    }
                }
            }

            if (message.status == MessageStatus.STREAMING && message.content.isBlank()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Text(
                        text = "Thinking…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaTextSecondary
                    )
                }
            } else if (message.content.isNotBlank()) {
                MarkdownTextContent(
                    markdown = message.content,
                    showCodeLineNumbers = showCodeLineNumbers
                )
            }

            if (message.status == MessageStatus.FAILED && !message.errorMessage.isNullOrBlank()) {
                NoticeBanner(
                    icon = Icons.Outlined.ErrorOutline,
                    text = message.errorMessage,
                    accentColor = NexaError
                )
            } else if (message.status == MessageStatus.STOPPED && message.content.isBlank() && !message.errorMessage.isNullOrBlank()) {
                Text(
                    text = message.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaTextSecondary
                )
            }

            val mentionsAccessibility = !isAccessibilityEnabled && (
                message.content.contains("Accessibility", ignoreCase = true) ||
                    message.errorMessage?.contains("Accessibility", ignoreCase = true) == true ||
                    message.executionPlan?.steps?.any {
                        it.detail.contains("Accessibility", ignoreCase = true)
                    } == true
                )

            val mentionsScreenCapture = !isMediaProjectionActive &&
                mediaProjectionState != MediaProjectionConsentState.UNSUPPORTED && (
                    message.content.contains("screenshot", ignoreCase = true) ||
                        message.content.contains("screen capture", ignoreCase = true) ||
                        message.content.contains("MediaProjection", ignoreCase = true) ||
                        message.content.contains("take_screenshot", ignoreCase = true) ||
                        message.errorMessage?.contains("screen capture", ignoreCase = true) == true ||
                        message.executionPlan?.steps?.any {
                            it.detail.contains("MediaProjection", ignoreCase = true) ||
                                it.detail.contains("screen capture", ignoreCase = true)
                        } == true
                    )

            if ((mentionsAccessibility || mentionsScreenCapture) && message.status != MessageStatus.STREAMING) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (mentionsAccessibility) {
                        OutlinedButton(
                            onClick = onOpenAccessibilitySettings,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("chat_open_accessibility_settings")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.AccessibilityNew,
                                contentDescription = null,
                                tint = NexaOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.open_accessibility_settings),
                                color = NexaOrange
                            )
                        }
                    }
                    if (mentionsScreenCapture) {
                        OutlinedButton(
                            onClick = onRequestScreenCapture,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag("chat_grant_screen_capture")
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Screenshot,
                                contentDescription = null,
                                tint = NexaOrange,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = stringResource(R.string.grant_screen_capture_permission),
                                color = NexaOrange
                            )
                        }
                    }
                }
            }

            if (message.status == MessageStatus.FAILED || message.status == MessageStatus.STOPPED) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        enabled = !isResponding,
                        modifier = Modifier.testTag("retry_message_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Retry")
                    }
                    val needsConfig = message.errorMessage?.contains("Connections", ignoreCase = true) == true ||
                        message.errorMessage?.contains("API key", ignoreCase = true) == true
                    if (needsConfig) {
                        TextButton(onClick = onOpenConnections) {
                            Text("Open Connections", color = NexaOrange)
                        }
                    }
                }
            }

            if (message.status == MessageStatus.COMPLETE && message.tokenUsage?.totalTokens != null) {
                Text(
                    text = "${message.tokenUsage.totalTokens} tokens",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexaTextTertiary
                )
            }

            message.verificationSummary?.takeIf { it.requestedToolCalls > 0 }?.let { verification ->
                Text(
                    text = verification.summaryNote,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (verification.isVerified) NexaSuccess else NexaWarning
                )
            }

            message.executionPlan?.let { plan ->
                ExpandableAgentExecutionPanel(
                    plan = plan,
                    initiallyExpanded = autoExpandPlan,
                    showSampleBadge = showSampleBadges
                )
            }

            message.taskSummary?.let { summary ->
                TaskSummaryCard(
                    summary = summary,
                    onOpenTask = onOpenTaskDetail
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChatEmptyWelcome(
    defaultProvider: ProviderConfig?,
    capabilities: List<DeviceCapability>,
    registeredTools: List<RegisteredToolEntry>,
    accessibilityState: AccessibilityRuntimeState,
    mediaProjectionState: MediaProjectionConsentState,
    onPromptSelect: (String) -> Unit,
    onConfigureProvider: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestScreenCapture: () -> Unit,
    onStopScreenCapture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isProviderReady = defaultProvider != null &&
        !defaultProvider.credentialInvalidated &&
        (defaultProvider.hasApiKey || defaultProvider.providerType.requiresBaseUrl) &&
        !defaultProvider.selectedModelId.isNullOrBlank()

    val availableToolCount = registeredTools.count { it.isAvailable }
    val appLaunchCap = capabilities.firstOrNull { it.type == DeviceCapabilityType.APP_LAUNCHING }
    val appLaunchReady = appLaunchCap?.let {
        it.userEnabled && it.status == CapabilityStatus.AVAILABLE
    } ?: registeredTools.any {
        it.definition.name == "launch_app" && it.availabilityStatus == ToolAvailabilityStatus.AVAILABLE
    }
    val accessibilityReady = accessibilityState == AccessibilityRuntimeState.SERVICE_ENABLED
    val captureActive = mediaProjectionState == MediaProjectionConsentState.GRANTED_ACTIVE

    val starters = buildList {
        if (appLaunchReady) {
            add("List installed apps on this device or open YouTube.")
        }
        if (accessibilityReady) {
            add("Observe the current screen and summarize interactive controls.")
        } else {
            add("Check device info, current date/time, and compute 18.5% tip on $124.80.")
        }
        add("Compare Kotlin Coroutines StateFlow vs SharedFlow with a concise example.")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        NexaBrandMark(size = 48.dp)
        Text(
            text = "Ask Nexa anything",
            style = MaterialTheme.typography.headlineMedium,
            color = NexaTextPrimary
        )
        Text(
            text = if (isProviderReady) {
                "Connected to ${defaultProvider?.providerType?.displayName} (${defaultProvider?.selectedModelId?.substringAfterLast("/")}) · $availableToolCount tools ready."
            } else {
                "Connect OpenRouter, Google Gemini, OpenAI, Anthropic, or a custom compatible endpoint to start live conversations."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = NexaTextSecondary
        )

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("welcome_capability_pills"),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(modifier = Modifier.testTag("welcome_pill_utilities")) {
                StatusPill(
                    label = "Utilities · Ready",
                    textColor = NexaSuccess,
                    containerColor = NexaSuccessContainer,
                    showDot = true
                )
            }
            Box(modifier = Modifier.testTag("welcome_pill_app_launch")) {
                StatusPill(
                    label = if (appLaunchReady) "App Launch · Active" else "App Launch · Off",
                    textColor = if (appLaunchReady) NexaSuccess else NexaWarning,
                    containerColor = if (appLaunchReady) NexaSuccessContainer else NexaWarningContainer,
                    showDot = appLaunchReady
                )
            }
            Box(modifier = Modifier.testTag("welcome_pill_accessibility")) {
                StatusPill(
                    label = "Screen UI · ${accessibilityState.label}",
                    textColor = if (accessibilityReady) NexaSuccess else NexaWarning,
                    containerColor = if (accessibilityReady) NexaSuccessContainer else NexaWarningContainer,
                    showDot = accessibilityReady
                )
            }
            Box(modifier = Modifier.testTag("welcome_pill_screen_capture")) {
                StatusPill(
                    label = "Capture · ${mediaProjectionState.label}",
                    textColor = when {
                        captureActive -> NexaSuccess
                        mediaProjectionState == MediaProjectionConsentState.UNSUPPORTED -> NexaNeutral
                        else -> NexaWarning
                    },
                    containerColor = when {
                        captureActive -> NexaSuccessContainer
                        mediaProjectionState == MediaProjectionConsentState.UNSUPPORTED -> NexaNeutralContainer
                        else -> NexaWarningContainer
                    },
                    showDot = captureActive
                )
            }
        }

        if (!accessibilityReady || mediaProjectionState != MediaProjectionConsentState.UNSUPPORTED) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!accessibilityReady) {
                    OutlinedButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("welcome_open_accessibility_settings")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccessibilityNew,
                            contentDescription = null,
                            tint = NexaOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.open_accessibility_settings),
                            color = NexaOrange
                        )
                    }
                }
                if (captureActive) {
                    OutlinedButton(
                        onClick = onStopScreenCapture,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("welcome_stop_screen_capture")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.StopCircle,
                            contentDescription = null,
                            tint = NexaError,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.stop_screen_capture),
                            color = NexaError
                        )
                    }
                } else if (mediaProjectionState != MediaProjectionConsentState.UNSUPPORTED) {
                    OutlinedButton(
                        onClick = onRequestScreenCapture,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("welcome_grant_screen_capture")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Screenshot,
                            contentDescription = null,
                            tint = NexaOrange,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.grant_screen_capture_permission),
                            color = NexaOrange
                        )
                    }
                }
            }
        }

        if (!isProviderReady) {
            Button(
                onClick = onConfigureProvider,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("empty_configure_provider_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexaOrange,
                    contentColor = NexaOnOrange
                )
            ) {
                Icon(Icons.Outlined.SettingsEthernet, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Configure AI Provider")
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        starters.forEach { starter ->
            OutlinedButton(
                onClick = { onPromptSelect(starter) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = starter,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun resolveDisplayName(context: Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            }
    }.getOrNull() ?: uri.lastPathSegment
}
