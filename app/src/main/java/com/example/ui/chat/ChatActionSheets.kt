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
import com.example.domain.agent.ToolRiskLevel
import com.example.domain.model.Conversation
import com.example.domain.model.ProviderConfig
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SampleDataBadge
import com.example.ui.components.StatusPill
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaErrorContainer
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
import com.example.ui.theme.NexaWarning
import com.example.ui.theme.NexaWarningContainer

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
    var renamingTarget by remember { mutableStateOf<Conversation?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NexaSecondaryBackground,
        contentColor = NexaTextPrimary
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
                    color = NexaTextPrimary
                )
                Button(
                    onClick = {
                        onNewConversation()
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexaOrange,
                        contentColor = NexaOnOrange
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
                                .background(if (isActive) NexaElevatedSurface else NexaSecondaryBackground)
                                .border(
                                    1.dp,
                                    if (isActive) NexaOrange else NexaBorder,
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
                                        color = NexaTextPrimary,
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
                                        color = NexaTextSecondary,
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
                                    tint = NexaTextSecondary,
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
                                    tint = NexaTextSecondary,
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
            containerColor = NexaElevatedSurface,
            title = { Text("Rename conversation", color = NexaTextPrimary) },
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
                    Text("Save", color = NexaOrange)
                }
            },
            dismissButton = {
                TextButton(onClick = { renamingTarget = null }) {
                    Text("Cancel", color = NexaTextSecondary)
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
        containerColor = NexaSecondaryBackground,
        contentColor = NexaTextPrimary
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
                    color = NexaTextPrimary
                )
                TextButton(
                    onClick = {
                        onDismiss()
                        onOpenConnections()
                    }
                ) {
                    Text("Manage Providers", color = NexaOrange)
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
                        containerColor = NexaOrange,
                        contentColor = NexaOnOrange
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
                            tint = NexaTextSecondary
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Clear model search",
                                    tint = NexaTextSecondary
                                )
                            }
                        }
                    }
                )

                if (filteredSections.isEmpty()) {
                    Text(
                        text = "No configured models match \"$searchQuery\".",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaTextSecondary
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
                                        color = NexaTextPrimary
                                    )
                                    Text(
                                        text = provider.maskedApiKey.ifBlank { "Endpoint only" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = NexaTextSecondary
                                    )
                                }
                            }
                            if (models.isEmpty()) {
                                item(key = "empty_${provider.providerType.id}") {
                                    Text(
                                        text = "No models discovered yet. Open Connections to refresh models or enter a manual model ID.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = NexaTextSecondary,
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
                                            .background(if (isSelected) NexaOrangeMuted else NexaElevatedSurface)
                                            .border(
                                                1.dp,
                                                if (isSelected) NexaOrange else NexaBorder,
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
                                                color = if (isSelected) NexaOrange else NexaTextPrimary,
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
                                                color = NexaTextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Outlined.Check,
                                                contentDescription = "Selected model",
                                                tint = NexaOrange,
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NexaSecondaryBackground,
        contentColor = NexaTextPrimary
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
                color = NexaTextPrimary
            )
            Text(
                text = "Text and source files are sent in prompt context. Images are sent only when the selected model reports vision support.",
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
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
                Icon(Icons.Outlined.Image, contentDescription = null, tint = NexaOrange)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Select Photo or Screenshot", color = NexaTextPrimary)
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
                Icon(Icons.Outlined.Description, contentDescription = null, tint = NexaOrange)
                Spacer(modifier = Modifier.width(10.dp))
                Text("Select Text or Code File", color = NexaTextPrimary)
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NexaSecondaryBackground,
        contentColor = NexaTextPrimary
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
                Icon(Icons.Outlined.Mic, contentDescription = null, tint = NexaOrange)
                Text(
                    text = "Voice Input Interface",
                    style = MaterialTheme.typography.titleLarge,
                    color = NexaTextPrimary
                )
            }
            NoticeBanner(
                icon = Icons.Outlined.Info,
                text = "Live audio capture and speech-to-text are not active in Phase 2. Microphone permission is not requested."
            )
            Text(
                text = "Insert a prompt into the composer:",
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )
            OutlinedButton(
                onClick = {
                    onInsertSamplePhrase("Explain the trade-offs between SSE and WebSockets for LLM streaming.")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Insert technical prompt", color = NexaTextPrimary)
            }
            OutlinedButton(
                onClick = {
                    onInsertSamplePhrase("Review this code snippet and suggest concrete improvements.")
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Insert code review prompt", color = NexaTextPrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsCatalogSheet(
    tools: List<AgentTool>,
    executableTools: List<com.example.domain.model.ToolDefinition> = emptyList(),
    onDismiss: () -> Unit
) {
    val activeIds = executableTools.map { it.id }.toSet()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NexaSecondaryBackground,
        contentColor = NexaTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Registered Agent Tools",
                style = MaterialTheme.typography.titleLarge,
                color = NexaTextPrimary
            )
            Text(
                text = "Active Phase 3 tools can be invoked dynamically by tool-capable models. Device UI automation contracts remain disabled until Phase 4.",
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )
            LazyColumn(
                modifier = Modifier.heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(executableTools, key = { "exec_${it.id}" }) { def ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaElevatedSurface)
                            .border(1.dp, NexaBorder, RoundedCornerShape(12.dp))
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
                                color = NexaTextPrimary
                            )
                            StatusPill(
                                label = def.riskCategory.label,
                                textColor = NexaSuccess,
                                containerColor = NexaSuccessContainer,
                                showDot = false
                            )
                        }
                        Text(
                            text = def.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = NexaTextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "${def.id} (${def.parameters.size} param${if (def.parameters.size == 1) "" else "s"})",
                                style = MaterialTheme.typography.labelSmall,
                                color = NexaTextSecondary
                            )
                            Text(
                                text = if (def.isImplemented) "Active (Phase 3)" else "Disabled",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (def.isImplemented) NexaSuccess else NexaNeutral
                            )
                        }
                    }
                }
                items(tools.filterNot { it.id in activeIds }, key = { it.id }) { tool ->
                    val (riskFg, riskBg) = when (tool.riskLevel) {
                        ToolRiskLevel.LOW -> NexaSuccess to NexaSuccessContainer
                        ToolRiskLevel.MEDIUM -> NexaWarning to NexaWarningContainer
                        ToolRiskLevel.HIGH -> NexaError to NexaErrorContainer
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaElevatedSurface)
                            .border(1.dp, NexaBorder, RoundedCornerShape(12.dp))
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
                                color = NexaTextPrimary
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
                            color = NexaTextSecondary
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = tool.id,
                                style = MaterialTheme.typography.labelSmall,
                                color = NexaTextSecondary
                            )
                            Text(
                                text = if (tool.isPhase1Implemented) "Active (Attachments)" else "Planned (Disabled)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (tool.isPhase1Implemented) NexaSuccess else NexaNeutral
                            )
                        }
                    }
                }
            }
        }
    }
}
