package com.example.ui.settings

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.agent.DiagnosticsExportBundle
import com.example.domain.model.AppearancePosture
import com.example.domain.model.ConfirmationMode
import com.example.domain.model.MemoryEntry
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NexaBrandMark
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.connections.ConnectionsSubTab
import com.example.ui.theme.LocalNexaHapticFeedback
import com.example.ui.theme.NexaTheme
import com.example.ui.theme.resolveNexaPalette

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateToConnectionsTab: (ConnectionsSubTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = NexaTheme.colors
    val haptic = LocalNexaHapticFeedback.current
    val settings = state.settings
    var showClearDialog by remember { mutableStateOf(false) }
    var showCustomPromptDialog by remember { mutableStateOf(false) }
    var showMemorySheet by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshOverlayPermission()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Surface(
            color = colors.secondaryBackground,
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
                        color = colors.textPrimary
                    )
                    Text(
                        text = "Behavior, model defaults, permissions, and privacy",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(colors.borderSubtle)
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_status_banner"),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        NoticeBanner(
                            icon = Icons.Outlined.Info,
                            text = msg,
                            accentColor = colors.success,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { viewModel.dismissClearedMessage() }) {
                            Text("Dismiss", color = colors.textSecondary)
                        }
                    }
                }
            }

            // 1. General preferences
            item(key = "sec_general") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "General Preferences")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Compact message spacing",
                        subtitle = "Tighten vertical spacing inside conversation threads.",
                        checked = settings.compactMessageSpacing,
                        testTag = "toggle_compact_spacing",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(compactMessageSpacing = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Show sample preview badges",
                        subtitle = "Clearly label demonstration threads and sample tasks.",
                        checked = settings.showSampleBadges,
                        testTag = "toggle_sample_badges",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(showSampleBadges = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Haptic feedback",
                        subtitle = "Subtle tactile confirmation on primary actions.",
                        checked = settings.hapticFeedbackEnabled,
                        testTag = "toggle_haptic_feedback",
                        onCheckedChange = { v ->
                            viewModel.update { it.copy(hapticFeedbackEnabled = v) }
                        }
                    )
                }
            }

            // 2. Appearance
            item(key = "sec_appearance") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(
                        title = "Appearance",
                        subtitle = "Choose surface palette and code formatting posture"
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    val systemDark = isSystemInDarkTheme()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppearancePosture.entries.forEach { posture ->
                            val selected = settings.appearancePosture == posture
                            val previewPalette = resolveNexaPalette(posture, systemDark)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selected) colors.orangeMuted else colors.elevatedSurface)
                                    .border(
                                        width = if (selected) 1.5.dp else 1.dp,
                                        color = if (selected) colors.orange else colors.border,
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    .clickable {
                                        haptic.performPrimary()
                                        viewModel.selectAppearance(posture)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                                    .testTag("appearance_option_${posture.name}"),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(previewPalette.background)
                                            .border(1.dp, previewPalette.border, CircleShape)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(previewPalette.secondaryBackground)
                                            .border(1.dp, previewPalette.border, CircleShape)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .background(previewPalette.orange)
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = posture.label,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = if (selected) colors.orange else colors.textPrimary
                                    )
                                    Text(
                                        text = posture.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textSecondary
                                    )
                                }
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Outlined.CheckCircle,
                                        contentDescription = "Selected theme",
                                        tint = colors.orange,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Code block line numbers",
                        subtitle = "Display monospace line numbers in fenced code output.",
                        checked = settings.showCodeLineNumbers,
                        testTag = "toggle_code_line_numbers",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(showCodeLineNumbers = v) }
                        }
                    )
                }
            }

            // 3. Agent behaviour
            item(key = "sec_agent_behaviour") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Agent Behaviour")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Response verbosity",
                        value = settings.responseVerbosity.label,
                        onClick = {
                            haptic.performPrimary()
                            viewModel.cycleVerbosity()
                        }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Auto-expand execution panel",
                        subtitle = "Show step-by-step agent activity expanded by default.",
                        checked = settings.autoExpandExecutionPanel,
                        testTag = "toggle_auto_expand_panel",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(autoExpandExecutionPanel = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Max reasoning iterations",
                        value = "${settings.maxStepsPerTask} turns",
                        onClick = {
                            haptic.performPrimary()
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
                            haptic.performPrimary()
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
                            haptic.performPrimary()
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
                            haptic.performPrimary()
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
                            .heightIn(min = 48.dp)
                            .clickable {
                                haptic.performPrimary()
                                showCustomPromptDialog = true
                            }
                            .padding(vertical = 8.dp)
                            .testTag("settings_custom_prompt_row"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                            Text(
                                text = "Custom system prompt",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = settings.customSystemPrompt.ifBlank { "Default Nexa assistant instructions active" },
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 2
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.ChevronRight,
                            contentDescription = "Edit custom system prompt",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 3b. Floating Agent Indicator & Reliability
            item(key = "sec_floating_indicator") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Floating Agent Indicator")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Floating indicator mode",
                        value = "${settings.floatingIndicatorMode.label} — ${settings.floatingIndicatorMode.summary}",
                        onClick = {
                            haptic.performPrimary()
                            viewModel.cycleFloatingIndicatorMode()
                        },
                        testTag = "settings_floating_indicator_mode"
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "AppLock auto-watch timeout",
                        value = "${settings.appLockWaitTimeoutSeconds}s before manual Continue",
                        onClick = {
                            haptic.performPrimary()
                            viewModel.update {
                                val next = when (it.appLockWaitTimeoutSeconds) {
                                    60 -> 180
                                    180 -> 300
                                    else -> 60
                                }
                                it.copy(appLockWaitTimeoutSeconds = next)
                            }
                        },
                        testTag = "settings_applock_timeout"
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "No-progress loop guard",
                        value = "${settings.noProgressRetryLimit} cycles max",
                        onClick = {
                            haptic.performPrimary()
                            viewModel.update {
                                val next = when (it.noProgressRetryLimit) {
                                    2 -> 3
                                    3 -> 5
                                    else -> 2
                                }
                                it.copy(noProgressRetryLimit = next)
                            }
                        },
                        testTag = "settings_loop_guard_limit"
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = stringResource(R.string.overlay_permission_title),
                        value = if (state.canDrawOverlays) {
                            "Granted — floating bubble available over other apps"
                        } else {
                            "Permission needed — tap to grant overlay permission"
                        },
                        onClick = {
                            haptic.performPrimary()
                            val intent = viewModel.buildOpenOverlaySettingsIntent()
                            if (intent != null) {
                                runCatching { context.startActivity(intent) }
                            } else {
                                onNavigateToConnectionsTab(ConnectionsSubTab.CAPABILITIES)
                            }
                        },
                        testTag = "settings_overlay_permission_row"
                    )
                }
            }

            // 4. Default AI model
            item(key = "sec_default_model") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Default AI Model")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Active default model",
                        value = state.defaultProvider?.let {
                            "${it.providerType.displayName} · ${it.selectedModelId ?: "Unselected"}"
                        } ?: "Not configured — tap to open AI Providers",
                        onClick = {
                            haptic.performPrimary()
                            onNavigateToConnectionsTab(ConnectionsSubTab.PROVIDERS)
                        }
                    )
                }
            }

            // 5. Task permissions
            item(key = "sec_task_permissions") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Task Permissions")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Allow background task queue",
                        subtitle = "Queue follow-up prompts while another task runs and keep active tasks running when switching tabs.",
                        checked = settings.allowBackgroundTaskQueue,
                        testTag = "toggle_background_task_queue",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(allowBackgroundTaskQueue = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Allow app launch suggestions",
                        subtitle = "Propose installed target app launch prompts in chat welcome suggestions.",
                        checked = settings.allowAppLaunchSuggestions,
                        testTag = "toggle_app_launch_suggestions",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(allowAppLaunchSuggestions = v) }
                        }
                    )
                }
            }

            // 6. Confirmation preferences
            item(key = "sec_confirmation") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Confirmation Preferences")
                    Spacer(modifier = Modifier.height(10.dp))
                    ConfirmationMode.entries.forEachIndexed { index, mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    haptic.performPrimary()
                                    viewModel.selectConfirmationMode(mode)
                                }
                                .padding(vertical = 6.dp)
                                .testTag("confirmation_mode_${mode.name}"),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = settings.confirmationMode == mode,
                                onClick = {
                                    haptic.performPrimary()
                                    viewModel.selectConfirmationMode(mode)
                                },
                                colors = RadioButtonDefaults.colors(selectedColor = colors.orange)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = mode.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary
                                )
                                Text(
                                    text = mode.summary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
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
                        subtitle = "Enforce explicit approval before external communication or messaging actions.",
                        checked = settings.requireApprovalForExternalComms,
                        testTag = "toggle_require_external_comms_approval",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(requireApprovalForExternalComms = v) }
                        }
                    )
                }
            }

            // 7. Conversation history
            item(key = "sec_history") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Conversation History")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Save conversations locally",
                        subtitle = "When off, new chats stay temporary in memory and are never written to Room.",
                        checked = settings.saveConversationHistory,
                        testTag = "toggle_save_history",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(saveConversationHistory = v) }
                        }
                    )
                    SettingsDivider()
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Local retention window",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.textPrimary
                            )
                            Text(
                                text = "${settings.historyRetentionDays}d · ${state.conversationCount} threads",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary
                            )
                        }
                        Text(
                            text = "Automatically prunes old user chats past this window while preserving active tasks and samples.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(7, 14, 30, 90).forEach { days ->
                                val selected = settings.historyRetentionDays == days
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(9.dp))
                                        .background(if (selected) colors.orangeMuted else colors.elevatedSurface)
                                        .border(
                                            1.dp,
                                            if (selected) colors.orange else colors.border,
                                            RoundedCornerShape(9.dp)
                                        )
                                        .clickable {
                                            haptic.performPrimary()
                                            viewModel.updateHistoryRetentionDays(days)
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                        .testTag("retention_chip_$days")
                                ) {
                                    Text(
                                        text = "${days}d",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (selected) colors.orange else colors.textSecondary
                                    )
                                }
                            }
                        }
                    }
                    SettingsDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable {
                                haptic.performPrimary()
                                showClearDialog = true
                            }
                            .padding(vertical = 8.dp)
                            .testTag("settings_clear_history_button"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Clear local conversation history",
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.error
                            )
                            Text(
                                text = "Remove all saved threads from this device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary
                            )
                        }
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = "Clear conversation history",
                            tint = colors.error
                        )
                    }
                }
            }

            // 8. Memory settings
            item(key = "sec_memory") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Memory Settings")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "On-device context memory",
                        subtitle = "Retain saved user preferences locally and include them across chat sessions.",
                        checked = settings.localMemoryEnabled,
                        testTag = "toggle_local_memory",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(localMemoryEnabled = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Manage On-Device Memory",
                        value = "${state.memoryEntries.size} saved note(s)",
                        onClick = {
                            haptic.performPrimary()
                            showMemorySheet = true
                        },
                        testTag = "manage_memory_button"
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Remember model per thread",
                        subtitle = "Restore each conversation's preferred provider and model when switching chats.",
                        checked = settings.rememberPreferredModelPerThread,
                        testTag = "toggle_remember_model_per_thread",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(rememberPreferredModelPerThread = v) }
                        }
                    )
                }
            }

            // 9. Integrations
            item(key = "sec_integrations") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Integrations")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "AI Providers & Models",
                        value = "OpenRouter, Gemini, OpenAI, Anthropic, Custom",
                        onClick = {
                            haptic.performPrimary()
                            onNavigateToConnectionsTab(ConnectionsSubTab.PROVIDERS)
                        }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Telegram Remote Bridge",
                        value = "Status: ${state.telegramConfig.status.label}",
                        onClick = {
                            haptic.performPrimary()
                            onNavigateToConnectionsTab(ConnectionsSubTab.TELEGRAM)
                        }
                    )
                }
            }

            // 10. Device permissions
            item(key = "sec_device_permissions") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Device Permissions")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsActionRow(
                        title = "Inspect Android capabilities",
                        value = "${state.availableCapabilitiesCount}/${state.capabilityCount} available now",
                        onClick = {
                            haptic.performPrimary()
                            onNavigateToConnectionsTab(ConnectionsSubTab.CAPABILITIES)
                        },
                        testTag = "settings_inspect_capabilities_row"
                    )
                }
            }

            // 11. Security and privacy
            item(key = "sec_security") {
                NexaSurfaceCard(borderColor = colors.border) {
                    SectionHeader(title = "Security and Privacy")
                    Spacer(modifier = Modifier.height(10.dp))
                    SettingsToggleRow(
                        title = "Mask credentials in UI & exports",
                        subtitle = "API keys encrypted via Android Keystore and redacted in diagnostics exports.",
                        checked = settings.maskCredentialsInExports,
                        testTag = "toggle_mask_credentials",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(maskCredentialsInExports = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Redact sensitive step arguments",
                        subtitle = "Hide sensitive inputs and personal identifiers in saved task timelines.",
                        checked = settings.redactSensitiveStepsInLogs,
                        testTag = "toggle_redact_sensitive_steps",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(redactSensitiveStepsInLogs = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsToggleRow(
                        title = "Biometric prompt on task approval",
                        subtitle = "Require device unlock or biometric check before approving sensitive actions.",
                        checked = settings.biometricLockOnApproval,
                        testTag = "toggle_biometric_approval",
                        onCheckedChange = { v ->
                            haptic.performToggle()
                            viewModel.update { it.copy(biometricLockOnApproval = v) }
                        }
                    )
                    SettingsDivider()
                    SettingsActionRow(
                        title = "Export Diagnostics & Transcripts",
                        value = "Generate report respecting your masking & redaction settings",
                        onClick = {
                            haptic.performPrimary()
                            viewModel.prepareDiagnosticsExport()
                        },
                        testTag = "export_diagnostics_button"
                    )
                }
            }

            // 12. About Nexa
            item(key = "sec_about") {
                NexaSurfaceCard(borderColor = colors.border) {
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
                                color = colors.textPrimary
                            )
                            Text(
                                text = stringResource(R.string.nexa_version),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.orange
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "Native Kotlin & Jetpack Compose conversational AI assistant with multi-provider streaming and supervised Android architecture.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = colors.elevatedSurface,
            title = { Text("Clear conversation history?", color = colors.textPrimary) },
            text = {
                Text(
                    "This deletes all saved chat threads stored locally on this device.",
                    color = colors.textSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        haptic.performConfirm()
                        viewModel.clearAllConversations()
                        showClearDialog = false
                    }
                ) {
                    Text("Clear All", color = colors.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("Cancel", color = colors.textSecondary)
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
            containerColor = colors.elevatedSurface,
            title = { Text("Custom system prompt", color = colors.textPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Appended to Nexa's core assistant instructions on every multi-turn conversation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
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
                        haptic.performConfirm()
                        viewModel.update { it.copy(customSystemPrompt = promptDraft.trim()) }
                        showCustomPromptDialog = false
                    },
                    modifier = Modifier.testTag("custom_system_prompt_save")
                ) {
                    Text("Save", color = colors.orange)
                }
            },
            dismissButton = {
                Row {
                    if (promptDraft.isNotBlank()) {
                        TextButton(onClick = {
                            viewModel.update { it.copy(customSystemPrompt = "") }
                            showCustomPromptDialog = false
                        }) {
                            Text("Reset", color = colors.error)
                        }
                    }
                    TextButton(onClick = { showCustomPromptDialog = false }) {
                        Text("Cancel", color = colors.textSecondary)
                    }
                }
            }
        )
    }

    if (showMemorySheet) {
        MemoryManagerSheet(
            entries = state.memoryEntries,
            localMemoryEnabled = settings.localMemoryEnabled,
            onAdd = viewModel::addMemory,
            onUpdate = viewModel::updateMemory,
            onRemove = viewModel::removeMemory,
            onClearAll = viewModel::clearMemories,
            onDismiss = { showMemorySheet = false }
        )
    }

    state.diagnosticsExport?.let { bundle ->
        DiagnosticsExportDialog(
            bundle = bundle,
            onDismiss = viewModel::dismissDiagnosticsExport
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun MemoryManagerSheet(
    entries: List<MemoryEntry>,
    localMemoryEnabled: Boolean,
    onAdd: (String, String) -> Unit,
    onUpdate: (String, String, String) -> Unit,
    onRemove: (String) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    val haptic = LocalNexaHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var newNoteText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("Preference") }
    var editingEntry by remember { mutableStateOf<MemoryEntry?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.secondaryBackground,
        contentColor = colors.textPrimary,
        modifier = Modifier.testTag("memory_manager_sheet")
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "On-Device Memory",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = if (localMemoryEnabled) {
                            "${entries.size} note(s) injected into chat context"
                        } else {
                            "Context memory is currently toggled off"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (entries.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                haptic.performConfirm()
                                onClearAll()
                            },
                            modifier = Modifier.testTag("memory_clear_all_button")
                        ) {
                            Text("Clear All", color = colors.error)
                        }
                    }
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("memory_sheet_close_button")
                    ) {
                        Text("Close", color = colors.textSecondary)
                    }
                }
            }

            OutlinedTextField(
                value = newNoteText,
                onValueChange = { newNoteText = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("memory_add_input"),
                label = { Text("Add memory note") },
                placeholder = { Text("e.g. Prefer concise Kotlin answers using Flow") },
                singleLine = false,
                maxLines = 3
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FlowRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Preference", "Profile", "Workflow", "Note").forEach { category ->
                        val active = selectedCategory == category
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (active) colors.orangeMuted else colors.elevatedSurface)
                                .border(
                                    1.dp,
                                    if (active) colors.orange else colors.border,
                                    RoundedCornerShape(8.dp)
                                )
                                .clickable { selectedCategory = category }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = category,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (active) colors.orange else colors.textSecondary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        haptic.performConfirm()
                        onAdd(newNoteText, selectedCategory)
                        newNoteText = ""
                    },
                    enabled = newNoteText.isNotBlank(),
                    modifier = Modifier.testTag("memory_add_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.orange,
                        contentColor = colors.onOrange
                    )
                ) {
                    Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Save")
                }
            }

            if (entries.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Outlined.Psychology,
                    title = "No saved memories yet",
                    description = "Save preferences above or ask Nexa in chat to remember something."
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.elevatedSurface)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.orange
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = entry.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textPrimary
                                )
                            }
                            IconButton(
                                onClick = { editingEntry = entry },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("memory_edit_${entry.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Edit,
                                    contentDescription = "Edit memory",
                                    tint = colors.textSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    haptic.performPrimary()
                                    onRemove(entry.id)
                                },
                                modifier = Modifier
                                    .size(40.dp)
                                    .testTag("memory_delete_${entry.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteOutline,
                                    contentDescription = "Delete memory",
                                    tint = colors.error,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    editingEntry?.let { entry ->
        var draft by remember(entry.id) { mutableStateOf(entry.text) }
        AlertDialog(
            onDismissRequest = { editingEntry = null },
            containerColor = colors.elevatedSurface,
            title = { Text("Edit memory note", color = colors.textPrimary) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    haptic.performConfirm()
                    onUpdate(entry.id, draft, entry.category)
                    editingEntry = null
                }) {
                    Text("Save", color = colors.orange)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingEntry = null }) {
                    Text("Cancel", color = colors.textSecondary)
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticsExportDialog(
    bundle: DiagnosticsExportBundle,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val haptic = LocalNexaHapticFeedback.current
    var copiedNotice by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.elevatedSurface,
        modifier = Modifier.testTag("diagnostics_export_dialog"),
        title = {
            Text(
                text = "Diagnostics & Transcripts",
                color = colors.textPrimary
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StatusPill(
                        label = if (bundle.maskedCredentialsApplied) "Keys Masked" else "Keys Unmasked",
                        textColor = if (bundle.maskedCredentialsApplied) colors.success else colors.warning,
                        containerColor = if (bundle.maskedCredentialsApplied) colors.successContainer else colors.warningContainer
                    )
                    StatusPill(
                        label = if (bundle.redactedSensitiveStepsApplied) "Steps Redacted" else "Raw Steps",
                        textColor = if (bundle.redactedSensitiveStepsApplied) colors.info else colors.warning,
                        containerColor = if (bundle.redactedSensitiveStepsApplied) colors.infoContainer else colors.warningContainer
                    )
                }
                Text(
                    text = "${bundle.conversationCount} thread(s) · ${bundle.taskCount} task(s)",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
                if (copiedNotice) {
                    Text(
                        text = "Copied report to clipboard.",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.success
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.background)
                        .border(1.dp, colors.border, RoundedCornerShape(10.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp)
                        .testTag("diagnostics_export_preview")
                ) {
                    Text(
                        text = bundle.content,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.textPrimary
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = {
                        haptic.performConfirm()
                        clipboard.setText(AnnotatedString(bundle.content))
                        copiedNotice = true
                    },
                    modifier = Modifier.testTag("diagnostics_copy_button")
                ) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy")
                }
                Button(
                    onClick = {
                        haptic.performConfirm()
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Nexa Diagnostics Report")
                            putExtra(Intent.EXTRA_TEXT, bundle.content)
                        }
                        runCatching {
                            context.startActivity(
                                Intent.createChooser(shareIntent, "Share Nexa Diagnostics")
                                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                            )
                        }
                    },
                    modifier = Modifier.testTag("diagnostics_share_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.orange,
                        contentColor = colors.onOrange
                    )
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Share")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("diagnostics_close_button")
            ) {
                Text("Close", color = colors.textSecondary)
            }
        }
    )
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    testTag: String? = null
) {
    val colors = NexaTheme.colors
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
                color = colors.textPrimary
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (testTag != null) Modifier.testTag(testTag) else Modifier,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onOrange,
                checkedTrackColor = colors.orange
            )
        )
    }
}

@Composable
private fun SettingsActionRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    testTag: String? = null
) {
    val colors = NexaTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (testTag != null) it.testTag(testTag) else it }
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = colors.textSecondary
            )
        }
        Icon(
            imageVector = Icons.Outlined.ChevronRight,
            contentDescription = null,
            tint = colors.textSecondary,
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 6.dp),
        thickness = 1.dp,
        color = NexaTheme.colors.borderSubtle
    )
}
