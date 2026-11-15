package com.example.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsEthernet
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.agent.AgentTool
import com.example.domain.agent.RegisteredToolEntry
import com.example.domain.agent.ToolAvailabilityStatus
import com.example.domain.agent.ToolRecoveryAction
import com.example.domain.agent.ToolRiskLevel
import com.example.domain.model.Conversation
import com.example.domain.model.ProviderConfig
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SampleDataBadge
import com.example.ui.components.StatusPill
import com.example.ui.theme.NexaTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistorySheet(
    conversations: List<Conversation>,
    activeConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onNewConversation: () -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    var renamingTarget by remember { mutableStateOf<Conversation?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.secondaryBackground,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Conversation History",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                Button(
                    onClick = {
                        onNewConversation()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.orange,
                        contentColor = colors.onOrange
                    )
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("New Chat")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (conversations.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Outlined.Forum,
                    title = "No saved conversations",
                    description = "Send a message to start a new AI conversation stored locally on your device."
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(conversations, key = { it.id }) { item ->
                        val isActive = item.id == activeConversationId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isActive) colors.elevatedSurface else colors.secondaryBackground)
                                .border(
                                    1.dp,
                                    if (isActive) colors.orange else colors.border,
                                    RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    onSelectConversation(item.id)
                                    onDismiss()
                                }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.textPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (item.isSamplePreview) {
                                        SampleDataBadge()
                                    }
                                }
                                if (item.lastMessagePreview.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = item.lastMessagePreview,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(
                                onClick = { renamingTarget = item },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Rename conversation",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = { onDeleteConversation(item.id) },
                                modifier = Modifier.size(40.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete conversation",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    renamingTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.title) }
        AlertDialog(
            onDismissRequest = { renamingTarget = null },
            containerColor = colors.elevatedSurface,
            title = { Text("Rename conversation", color = colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("Title") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameConversation(target.id, text)
                        renamingTarget = null
                    }
                ) {
                    Text("Save", color = colors.orange)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingTarget = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    providers: List<ProviderConfig>,
    onSelectModel: (ProviderConfig, String) -> Unit,
    onOpenConnections: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    val configuredProviders = providers.filter {
        (it.hasApiKey || (it.providerType.requiresBaseUrl && it.baseUrl.isNotBlank())) &&
            !it.credentialInvalidated
    }
    var searchQuery by remember { mutableStateOf("") }
    val filteredSections = remember(configuredProviders, searchQuery) {
        val q = searchQuery.trim()
        configuredProviders.mapNotNull { provider ->
            if (q.isEmpty()) {
                provider to provider.availableModels
            } else if (provider.providerType.displayName.contains(q, ignoreCase = true)) {
                provider to provider.availableModels
            } else {
                val matched = provider.availableModels.filter {
                    it.id.contains(q, ignoreCase = true) ||
                        it.displayName.contains(q, ignoreCase = true)
                }
                if (matched.isNotEmpty()) provider to matched else null
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.secondaryBackground,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model Selector",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenConnections()
                    }
                ) {
                    Text("Manage Providers", color = colors.orange)
                }
            }

            if (configuredProviders.isEmpty()) {
                NoticeBanner(
                    icon = Icons.Outlined.Info,
                    text = "No provider credentials are configured yet. Configure an AI provider in Connections to start live conversations."
                )
                Button(
                    onClick = {
                        onDismiss()
                        onOpenConnections()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.orange,
                        contentColor = colors.onOrange
                    )
                ) {
                    Icon(Icons.Outlined.SettingsEthernet, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open AI Providers")
                }
            } else {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("model_selector_search_input"),
                    label = { Text("Search models or providers") },
                    placeholder = { Text("e.g. claude, gemini, gpt-4.1...") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = colors.textSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Clear model search",
                                    tint = colors.textSecondary
                                )
                            }
                        }
                    }
                )

                if (filteredSections.isEmpty()) {
                    Text(
                        text = "No configured models match \"$searchQuery\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        filteredSections.forEach { (provider, models) ->
                            item(key = "hdr_${provider.providerType.id}") {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 6.dp, bottom = 2.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = provider.providerType.displayName,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = colors.textPrimary
                                    )
                                    Text(
                                        text = provider.maskedApiKey.ifBlank { "Endpoint only" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.textSecondary
                                    )
                                }
                            }
                            if (models.isEmpty()) {
                                item(key = "empty_${provider.providerType.id}") {
                                    Text(
                                        text = "No models discovered yet. Open Connections to refresh models or enter a manual model ID.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary,
                                        modifier = Modifier.padding(bottom = 6.dp)
                                    )
                                }
                            } else {
                                items(
                                    items = models,
                                    key = { model -> "${provider.providerType.id}:${model.id}" }
                                ) { model ->
                                    val isSelected = provider.isDefault && provider.selectedModelId == model.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) colors.orangeMuted else colors.elevatedSurface)
                                            .border(
                                                1.dp,
                                                if (isSelected) colors.orange else colors.border,
                                                RoundedCornerShape(10.dp)
                                            )
                                            .clickable {
                                                onSelectModel(provider, model.id)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isSelected) colors.orange else colors.textPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = buildString {
                                                    append(model.id)
                                                    append(" · ")
                                                    append(
                                                        when {
                                                            model.isManualEntry -> "Manual"
                                                            model.isCatalogVerified -> "${model.contextWindowLabel} ctx · Live"
                                                            else -> "${model.contextWindowLabel} ctx · Preset"
                                                        }
                                                    )
                                                    if (model.supportsVision) append(" · Vision")
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = colors.textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = "Selected model",
                                                tint = colors.orange,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentPickerSheet(
    onPickImage: () -> Unit,
    onPickDocument: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.secondaryBackground,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Attach Context",
                style = MaterialTheme.typography.titleLarge,
                color = colors.textPrimary
            )
            Text(
                text = "Text and source files are sent in prompt context. Images are sent only when the selected model reports vision support.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onPickImage()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attach_photo_option")
            ) {
                Icon(Icons.Outlined.Image, contentDescription = null, tint = colors.orange)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Select Photo or Screenshot", color = colors.textPrimary)
            }
            OutlinedButton(
                onClick = {
                    onDismiss()
                    onPickDocument()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("attach_document_option")
            ) {
                Icon(Icons.Outlined.Description, contentDescription = null, tint = colors.orange)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Select Text or Code File", color = colors.textPrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoiceInputSheet(
    onInsertSamplePhrase: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.secondaryBackground,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = colors.orange)
                Text(
                    text = "Voice Input Interface",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
            }
            NoticeBanner(
                icon = Icons.Outlined.Info,
                text = "Live audio capture and speech-to-text are not active in this build. Microphone permission is not requested."
            )
            Text(
                text = "Insert a prompt into the composer:",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            OutlinedButton(
                onClick = {
                    onInsertSamplePhrase("Explain the trade-offs between SSE and WebSockets for LLM streaming.")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Insert technical prompt", color = colors.textPrimary)
            }
            OutlinedButton(
                onClick = {
                    onInsertSamplePhrase("Review this code snippet and suggest concrete improvements.")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Insert code review prompt", color = colors.textPrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsCatalogSheet(
    tools: List<AgentTool> = emptyList(),
    executableTools: List<com.example.domain.model.ToolDefinition> = emptyList(),
    registeredTools: List<RegisteredToolEntry> = emptyList(),
    onOpenAccessibilitySettings: (() -> Unit)? = null,
    onRequestScreenCapture: (() -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    val activeCount = if (registeredTools.isNotEmpty()) {
        registeredTools.count { it.isAvailable }
    } else {
        executableTools.count { it.isImplemented }
    }
    val registeredIds = if (registeredTools.isNotEmpty()) {
        registeredTools.mapTo(mutableSetOf()) { it.definition.id }
    } else {
        executableTools.mapTo(mutableSetOf()) { it.id }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.secondaryBackground,
        contentColor = colors.textPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Registered Agent Tools",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.textPrimary
                )
                StatusPill(
                    label = "$activeCount ready",
                    textColor = colors.success,
                    containerColor = colors.successContainer,
                    showDot = true
                )
            }
            Text(
                text = "Live synchronized tool catalog sent to tool-capable models. Unavailable tools show exact permission or consent requirements.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
            LazyColumn(
                modifier = Modifier
                    .heightIn(max = 440.dp)
                    .testTag("tools_catalog_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (registeredTools.isNotEmpty()) {
                    val sorted = registeredTools.sortedByDescending { it.isAvailable }
                    items(sorted, key = { "reg_${it.definition.id}" }) { entry ->
                        val def = entry.definition
                        val (statusFg, statusBg) = when (entry.availabilityStatus) {
                            ToolAvailabilityStatus.AVAILABLE -> colors.success to colors.successContainer
                            ToolAvailabilityStatus.SETUP_REQUIRED,
                            ToolAvailabilityStatus.CONSENT_REQUIRED,
                            ToolAvailabilityStatus.TEMPORARILY_UNAVAILABLE -> colors.warning to colors.warningContainer
                            ToolAvailabilityStatus.DISABLED_BY_USER,
                            ToolAvailabilityStatus.UNSUPPORTED -> colors.error to colors.errorContainer
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.elevatedSurface)
                                .border(
                                    1.dp,
                                    if (entry.isAvailable) colors.border else colors.warning.copy(alpha = 0.45f),
                                    RoundedCornerShape(12.dp)
                                )
                                .padding(12.dp)
                                .testTag("tool_catalog_item_${def.name}"),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = def.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                StatusPill(
                                    label = entry.availabilityStatus.label,
                                    textColor = statusFg,
                                    containerColor = statusBg,
                                    showDot = entry.isAvailable
                                )
                            }
                            Text(
                                text = def.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = "${entry.capabilityRequirement.label} · ${def.riskCategory.label} · ${def.parameters.size} param${if (def.parameters.size == 1) "" else "s"}",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            if (!entry.isAvailable) {
                                Text(
                                    text = entry.availabilityReason,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.warning
                                )
                                when (entry.recoveryAction) {
                                    ToolRecoveryAction.OPEN_ACCESSIBILITY_SETTINGS -> if (onOpenAccessibilitySettings != null) {
                                        OutlinedButton(
                                            onClick = {
                                                onDismiss()
                                                onOpenAccessibilitySettings()
                                            },
                                            modifier = Modifier.testTag("tool_recovery_accessibility_${def.name}")
                                        ) {
                                            Text("Open Accessibility Settings", color = colors.orange)
                                        }
                                    }
                                    ToolRecoveryAction.REQUEST_MEDIA_PROJECTION -> if (onRequestScreenCapture != null) {
                                        OutlinedButton(
                                            onClick = {
                                                onDismiss()
                                                onRequestScreenCapture()
                                            },
                                            modifier = Modifier.testTag("tool_recovery_screen_capture_${def.name}")
                                        ) {
                                            Text("Grant Screen Capture", color = colors.orange)
                                        }
                                    }
                                    else -> Unit
                                }
                            }
                        }
                    }
                } else {
                    items(executableTools, key = { "exec_${it.id}" }) { def ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.elevatedSurface)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = def.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary
                                )
                                StatusPill(
                                    label = def.riskCategory.label,
                                    textColor = colors.success,
                                    containerColor = colors.successContainer,
                                    showDot = false
                                )
                            }
                            Text(
                                text = def.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                    }
                }
                items(tools.filterNot { it.id in registeredIds }, key = { it.id }) { tool ->
                    val (riskFg, riskBg) = when (tool.riskLevel) {
                        ToolRiskLevel.LOW -> colors.success to colors.successContainer
                        ToolRiskLevel.MEDIUM -> colors.warning to colors.warningContainer
                        ToolRiskLevel.HIGH -> colors.error to colors.errorContainer
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.elevatedSurface)
                            .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = tool.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            StatusPill(
                                label = tool.riskLevel.label,
                                textColor = riskFg,
                                containerColor = riskBg,
                                showDot = false
                            )
                        }
                        Text(
                            text = tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = tool.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                            Text(
                                text = if (tool.isPhase1Implemented) "Active (Attachments)" else "Planned (Disabled)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tool.isPhase1Implemented) colors.success else colors.neutral
                            )
                        }
                    }
                }
            }
        }
    }
}
