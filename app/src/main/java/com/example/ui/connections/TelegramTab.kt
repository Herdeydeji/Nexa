package com.example.ui.connections

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.domain.model.TelegramConfig
import com.example.domain.model.TelegramConnectionStatus
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalNexaHapticFeedback
import com.example.ui.theme.NexaTheme

@Composable
fun TelegramTab(
    config: TelegramConfig,
    formError: String?,
    onSavePairingDraft: (botToken: String, handle: String) -> Unit,
    onUpdatePermissions: (allowRemote: Boolean?, requireApproval: Boolean?, shareSummaries: Boolean?) -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = NexaTheme.colors
    val haptic = LocalNexaHapticFeedback.current
    var botTokenInput by remember { mutableStateOf("") }
    var handleInput by remember(config.pairingHandle) { mutableStateOf(config.pairingHandle) }

    val (statusColor, statusBg) = when (config.status) {
        TelegramConnectionStatus.DISCONNECTED -> colors.neutral to colors.neutralContainer
        TelegramConnectionStatus.PAIRING_PENDING -> colors.warning to colors.warningContainer
        TelegramConnectionStatus.VALIDATION_ERROR -> colors.error to colors.errorContainer
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("telegram_tab_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "tg_status") {
            NexaSurfaceCard(borderColor = colors.border) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.Send,
                            contentDescription = null,
                            tint = colors.orange,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Telegram Bridge",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                    }
                    StatusPill(
                        label = config.status.label,
                        textColor = statusColor,
                        containerColor = statusBg
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                NoticeBanner(
                    icon = Icons.Outlined.Info,
                    text = config.statusNote,
                    accentColor = colors.warning
                )
            }
        }

        item(key = "tg_pairing") {
            NexaSurfaceCard(borderColor = colors.border) {
                SectionHeader(
                    title = "Pairing Interface",
                    subtitle = "Store encrypted bot draft credentials locally for Telegram remote bridge"
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (config.maskedBotToken.isNotBlank()) {
                    Text(
                        text = "Saved token: ${config.maskedBotToken}",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.orange
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                OutlinedTextField(
                    value = botTokenInput,
                    onValueChange = { botTokenInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("telegram_token_input"),
                    label = { Text("Bot Token (Optional draft)") },
                    placeholder = { Text("123456:ABC-DEF...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = handleInput,
                    onValueChange = { handleInput = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("telegram_handle_input"),
                    label = { Text("Authorized Telegram Handle or Pairing Code") },
                    placeholder = { Text("@username or 6-digit pairing code") },
                    singleLine = true
                )

                formError?.let { err ->
                    Spacer(modifier = Modifier.height(10.dp))
                    NoticeBanner(
                        icon = Icons.Outlined.ErrorOutline,
                        text = err,
                        accentColor = colors.error
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = {
                            haptic.performConfirm()
                            onSavePairingDraft(botTokenInput, handleInput)
                            botTokenInput = ""
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("telegram_save_draft_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.orange,
                            contentColor = colors.onOrange
                        )
                    ) {
                        Text("Save Draft")
                    }
                    OutlinedButton(
                        onClick = {
                            haptic.performPrimary()
                            botTokenInput = ""
                            handleInput = ""
                            onDisconnect()
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("telegram_disconnect_button")
                    ) {
                        Icon(Icons.Outlined.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Disconnect")
                    }
                }
            }
        }

        item(key = "tg_permissions") {
            NexaSurfaceCard(borderColor = colors.border) {
                SectionHeader(
                    title = "Remote Access Permissions",
                    subtitle = "Define guardrails for Telegram commands"
                )
                Spacer(modifier = Modifier.height(12.dp))

                TelegramPermissionRow(
                    title = "Allow remote task prompts",
                    subtitle = "Permit paired Telegram chats to queue Nexa tasks.",
                    checked = config.allowRemotePrompts,
                    onCheckedChange = {
                        haptic.performToggle()
                        onUpdatePermissions(it, null, null)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                TelegramPermissionRow(
                    title = "Require on-device approval",
                    subtitle = "Pause sensitive device actions until confirmed locally on phone.",
                    checked = config.requireDeviceApproval,
                    onCheckedChange = {
                        haptic.performToggle()
                        onUpdatePermissions(null, it, null)
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
                TelegramPermissionRow(
                    title = "Send task status summaries",
                    subtitle = "Forward completion or failure notices back to Telegram.",
                    checked = config.shareTaskSummaries,
                    onCheckedChange = {
                        haptic.performToggle()
                        onUpdatePermissions(null, null, it)
                    }
                )
            }
        }
    }
}

@Composable
private fun TelegramPermissionRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = NexaTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onOrange,
                checkedTrackColor = colors.orange
            )
        )
    }
}
