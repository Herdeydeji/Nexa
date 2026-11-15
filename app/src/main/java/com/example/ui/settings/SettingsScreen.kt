package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.ConfirmationMode
import com.example.ui.components.NexaBrandMark
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SectionHeader
import com.example.ui.connections.ConnectionsSubTab
import com.example.ui.theme.NexaBackground
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaBorderSubtle
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaOnOrange
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaSuccess
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToConnectionsTab: (ConnectionsSubTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val settings = state.settings
    var showClearDialog by remember { mutableStateOf(false) }
    var showCustomPromptDialog by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(NexaBackground)
    ) {
        Surface(
            color = NexaSecondaryBackground,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = NexaTextPrimary
                    )
                    Text(
                        text = "Behavior, model defaults, permissions, and privacy",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaTextSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(NexaBorderSubtle)
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("settings_list"),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            state.historyClearedMessage?.let { msg ->
                item(key = "cleared_banner") {
                    NoticeBanner(
                        icon = Icons.Outlined.Info,
                        text = msg,
                        accentColor = NexaSuccess
                    )
                }
            }

            // 1. General preferences
            item(key = "sec_general") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "General Preferences")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Compact message spacing",
                        subtitle = "Tighten vertical spacing inside conversation threads.",
                        checked = settings.compactMessageSpacing,
                        onCheckedChange = { v -> viewModel.update { it.copy(compactMessageSpacing = v) } }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Show sample preview badges",
                        subtitle = "Clearly label demonstration threads and sample tasks.",
                        checked = settings.showSampleBadges,
                        onCheckedChange = { v -> viewModel.update { it.copy(showSampleBadges = v) } }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Haptic feedback",
                        subtitle = "Subtle tactile confirmation on primary actions.",
                        checked = settings.hapticFeedbackEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(hapticFeedbackEnabled = v) } }
                    )
                }
            }

            // 2. Appearance
            item(key = "sec_appearance") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Appearance")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Surface posture",
                        value = settings.appearancePosture.label,
                        onClick = { viewModel.cycleAppearance() }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Code block line numbers",
                        subtitle = "Display monospace line numbers in fenced code output.",
                        checked = settings.showCodeLineNumbers,
                        onCheckedChange = { v -> viewModel.update { it.copy(showCodeLineNumbers = v) } }
                    )
                }
            }

            // 3. Agent behaviour
            item(key = "sec_agent_behaviour") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Agent Behaviour")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Response verbosity",
                        value = settings.responseVerbosity.label,
                        onClick = { viewModel.cycleVerbosity() }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Auto-expand execution panel",
                        subtitle = "Show step-by-step agent activity expanded by default.",
                        checked = settings.autoExpandExecutionPanel,
                        onCheckedChange = { v -> viewModel.update { it.copy(autoExpandExecutionPanel = v) } }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Max reasoning iterations",
                        value = "${settings.maxStepsPerTask} turns",
                        onClick = {
                            viewModel.update {
                                val next = when (it.maxStepsPerTask) {
                                    8 -> 12
                                    12 -> 20
                                    else -> 8
                                }
                                it.copy(maxStepsPerTask = next)
                            }
                        }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Max tool calls per task",
                        value = "${settings.maxToolCallsPerTask} calls",
                        onClick = {
                            viewModel.update {
                                val next = when (it.maxToolCallsPerTask) {
                                    6 -> 12
                                    12 -> 24
                                    else -> 6
                                }
                                it.copy(maxToolCallsPerTask = next)
                            }
                        }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Per-tool & task timeout",
                        value = "${settings.toolTimeoutSeconds}s tool · ${settings.taskTimeoutSeconds}s task",
                        onClick = {
                            viewModel.update {
                                when (it.toolTimeoutSeconds) {
                                    10 -> it.copy(toolTimeoutSeconds = 20, taskTimeoutSeconds = 180)
                                    20 -> it.copy(toolTimeoutSeconds = 30, taskTimeoutSeconds = 300)
                                    else -> it.copy(toolTimeoutSeconds = 10, taskTimeoutSeconds = 120)
                                }
                            }
                        }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Max tool output buffer",
                        value = "${settings.maxToolResultChars} chars",
                        onClick = {
                            viewModel.update {
                                val next = when (it.maxToolResultChars) {
                                    4000 -> 8000
                                    8000 -> 16000
                                    else -> 4000
                                }
                                it.copy(maxToolResultChars = next)
                            }
                        }
                    )
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showCustomPromptDialog = true }
                            .padding(vertical = 8.dp)
                            .testTag("settings_custom_prompt_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "Custom system prompt",
                                style = MaterialTheme.typography.titleSmall,
                                color = NexaTextPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = settings.customSystemPrompt.ifBlank { "Default Nexa assistant instructions active" },
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaTextSecondary,
                                maxLines = 2
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = "Edit custom system prompt",
                            tint = NexaTextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 4. Default AI model
            item(key = "sec_default_model") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Default AI Model")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Active default model",
                        value = state.defaultProvider?.let {
                            "${it.providerType.displayName} · ${it.selectedModelId ?: "Unselected"}"
                        } ?: "Not configured — tap to open AI Providers",
                        onClick = { onNavigateToConnectionsTab(ConnectionsSubTab.PROVIDERS) }
                    )
                }
            }

            // 5. Task permissions
            item(key = "sec_task_permissions") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Task Permissions")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Allow background task queue",
                        subtitle = "Permit queued tasks to wait for approval while minimized (Phase 2).",
                        checked = settings.allowBackgroundTaskQueue,
                        onCheckedChange = { v -> viewModel.update { it.copy(allowBackgroundTaskQueue = v) } }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Allow app launch suggestions",
                        subtitle = "Permit Nexa to propose opening installed target apps.",
                        checked = settings.allowAppLaunchSuggestions,
                        onCheckedChange = { v -> viewModel.update { it.copy(allowAppLaunchSuggestions = v) } }
                    )
                }
            }

            // 6. Confirmation preferences
            item(key = "sec_confirmation") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Confirmation Preferences")
                    Spacer(modifier = Modifier.height(10.dp))
                    ConfirmationMode.entries.forEachIndexed { index, mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.selectConfirmationMode(mode) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.confirmationMode == mode,
                                onClick = { viewModel.selectConfirmationMode(mode) },
                                colors = RadioButtonDefaults.colors(selectedColor = NexaOrange)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = NexaTextPrimary
                                )
                                Text(
                                    text = mode.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = NexaTextSecondary
                                )
                            }
                        }
                        if (index < ConfirmationMode.entries.lastIndex) {
                            SettingsDivider()
                        }
                    }
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Require confirmation for calls & SMS",
                        subtitle = "Enforce explicit approval before external communication.",
                        checked = settings.requireApprovalForExternalComms,
                        onCheckedChange = { v -> viewModel.update { it.copy(requireApprovalForExternalComms = v) } }
                    )
                }
            }

            // 7. Conversation history
            item(key = "sec_history") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Conversation History")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Save conversations locally",
                        subtitle = "Persist threads in the on-device Room database.",
                        checked = settings.saveConversationHistory,
                        onCheckedChange = { v -> viewModel.update { it.copy(saveConversationHistory = v) } }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Local retention window",
                        value = "${settings.historyRetentionDays} days (${state.conversationCount} threads)",
                        onClick = {
                            viewModel.update {
                                val next = when (it.historyRetentionDays) {
                                    7 -> 30
                                    30 -> 90
                                    else -> 7
                                }
                                it.copy(historyRetentionDays = next)
                            }
                        }
                    )
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showClearDialog = true }
                            .padding(vertical = 8.dp)
                            .testTag("settings_clear_history_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Clear local conversation history",
                                style = MaterialTheme.typography.titleSmall,
                                color = NexaError
                            )
                            Text(
                                text = "Remove all saved threads from this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = NexaTextSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear conversation history",
                            tint = NexaError
                        )
                    }
                }
            }

            // 8. Memory settings
            item(key = "sec_memory") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Memory Settings")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "On-device context memory",
                        subtitle = "Retain user preferences locally across sessions (Phase 2 index).",
                        checked = settings.localMemoryEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(localMemoryEnabled = v) } }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Remember model per thread",
                        subtitle = "Keep track of the selected model when switching chats.",
                        checked = settings.rememberPreferredModelPerThread,
                        onCheckedChange = { v -> viewModel.update { it.copy(rememberPreferredModelPerThread = v) } }
                    )
                }
            }

            // 9. Integrations
            item(key = "sec_integrations") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Integrations")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "AI Providers & Models",
                        value = "OpenRouter, Gemini, OpenAI, Anthropic, Custom",
                        onClick = { onNavigateToConnectionsTab(ConnectionsSubTab.PROVIDERS) }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Telegram Remote Bridge",
                        value = "Status: ${state.telegramConfig.status.label}",
                        onClick = { onNavigateToConnectionsTab(ConnectionsSubTab.TELEGRAM) }
                    )
                }
            }

            // 10. Device permissions
            item(key = "sec_device_permissions") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Device Permissions")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Inspect Android capabilities",
                        value = "${state.availableCapabilitiesCount}/${state.capabilityCount} available now",
                        onClick = { onNavigateToConnectionsTab(ConnectionsSubTab.CAPABILITIES) }
                    )
                }
            }

            // 11. Security and privacy
            item(key = "sec_security") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "Security and Privacy")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Mask credentials in UI & exports",
                        subtitle = "API keys encrypted via Android Keystore and shown masked.",
                        checked = settings.maskCredentialsInExports,
                        onCheckedChange = { v -> viewModel.update { it.copy(maskCredentialsInExports = v) } }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Redact sensitive step arguments",
                        subtitle = "Hide personal identifiers in saved task timelines.",
                        checked = settings.redactSensitiveStepsInLogs,
                        onCheckedChange = { v -> viewModel.update { it.copy(redactSensitiveStepsInLogs = v) } }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Biometric prompt on task approval",
                        subtitle = "Require device unlock confirmation for high-risk actions (Phase 2).",
                        checked = settings.biometricLockOnApproval,
                        onCheckedChange = { v -> viewModel.update { it.copy(biometricLockOnApproval = v) } }
                    )
                }
            }

            // 12. About Nexa
            item(key = "sec_about") {
                NexaSurfaceCard(borderColor = NexaBorder) {
                    SectionHeader(title = "About Nexa")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        NexaBrandMark(size = 40.dp)
                        Column {
                            Text(
                                text = "Nexa Android",
                                style = MaterialTheme.typography.titleMedium,
                                color = NexaTextPrimary
                            )
                            Text(
                                text = stringResource(R.string.nexa_version),
                                style = MaterialTheme.typography.labelSmall,
                                color = NexaOrange
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Native Kotlin & Jetpack Compose conversational AI assistant with multi-provider streaming and supervised Android architecture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaTextSecondary
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = NexaElevatedSurface,
            title = { Text("Clear conversation history?", color = NexaTextPrimary) },
            text = {
                Text(
                    "This deletes all saved chat threads stored locally on this device.",
                    color = NexaTextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllConversations()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear All", color = NexaError)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = NexaTextSecondary)
                }
            }
        )
    }

    if (showCustomPromptDialog) {
        var promptDraft by remember(settings.customSystemPrompt) {
            mutableStateOf(settings.customSystemPrompt)
        }
        AlertDialog(
            onDismissRequest = { showCustomPromptDialog = false },
            containerColor = NexaElevatedSurface,
            title = { Text("Custom system prompt", color = NexaTextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Appended to Nexa's core assistant instructions on every multi-turn conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaTextSecondary
                    )
                    OutlinedTextField(
                        value = promptDraft,
                        onValueChange = { promptDraft = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("custom_system_prompt_input"),
                        placeholder = { Text("e.g. Answer concisely using Kotlin idioms.") },
                        minLines = 3,
                        maxLines = 6
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.update { it.copy(customSystemPrompt = promptDraft.trim()) }
                        showCustomPromptDialog = false
                    },
                    modifier = Modifier.testTag("custom_system_prompt_save")
                ) {
                    Text("Save", color = NexaOrange)
                }
            },
            dismissButton = {
                Row {
                    if (promptDraft.isNotBlank()) {
                        TextButton(onClick = {
                            viewModel.update { it.copy(customSystemPrompt = "") }
                            showCustomPromptDialog = false
                        }) {
                            Text("Reset", color = NexaError)
                        }
                    }
                    TextButton(onClick = { showCustomPromptDialog = false }) {
                        Text("Cancel", color = NexaTextSecondary)
                    }
                }
            }
        )
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = NexaTextPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NexaOnOrange,
                checkedTrackColor = NexaOrange
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = NexaTextPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = NexaTextSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        thickness = 1.dp,
        color = NexaBorderSubtle
    )
}
