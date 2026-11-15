package com.example.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.AiProviderType
import com.example.ui.theme.NexaBackground
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaBorderSubtle
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary

@Composable
fun ConnectionsScreen(
    viewModel: ConnectionsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingProviderType by remember { mutableStateOf<AiProviderType?>(null) }

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
                        text = stringResource(R.string.connections_title),
                        style = MaterialTheme.typography.headlineMedium,
                        color = NexaTextPrimary
                    )
                    Text(
                        text = "AI providers, Telegram remote bridge, and device capabilities",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaTextSecondary
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NexaElevatedSurface)
                        .border(1.dp, NexaBorder, RoundedCornerShape(12.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ConnectionsSubTab.entries.forEach { subTab ->
                        val selected = state.selectedTab == subTab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (selected) NexaOrangeMuted else NexaElevatedSurface)
                                .clickable { viewModel.selectTab(subTab) }
                                .padding(vertical = 9.dp)
                                .testTag("connections_subtab_${subTab.name.lowercase()}"),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = subTab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) NexaOrange else NexaTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
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

        when (state.selectedTab) {
            ConnectionsSubTab.PROVIDERS -> ProvidersTab(
                providers = state.providers,
                onConfigureProvider = { editingProviderType = it.providerType },
                onTestProvider = viewModel::testProviderConnection,
                onSetDefault = { cfg ->
                    viewModel.setDefaultProvider(cfg.providerType, cfg.selectedModelId)
                }
            )
            ConnectionsSubTab.TELEGRAM -> TelegramTab(
                config = state.telegramConfig,
                formError = state.telegramFormError,
                onSavePairingDraft = viewModel::saveTelegramPairingDraft,
                onUpdatePermissions = viewModel::updateTelegramPermissions,
                onDisconnect = viewModel::disconnectTelegram
            )
            ConnectionsSubTab.CAPABILITIES -> CapabilitiesTab(
                capabilities = state.capabilities,
                selectedCategory = state.capabilityFilter,
                onSelectCategory = viewModel::selectCapabilityCategory,
                onRefresh = viewModel::refreshCapabilities,
                onToggleCapability = viewModel::toggleCapabilityPreference
            )
        }
    }

    editingProviderType?.let { targetType ->
        val currentConfig = state.providers.firstOrNull { it.providerType == targetType }
        if (currentConfig != null) {
            ProviderConfigSheet(
                config = currentConfig,
                isRefreshingModels = targetType in state.refreshingProviders,
                refreshError = state.refreshErrors[targetType],
                onSave = { rawKey, baseUrl, modelId, manualModelId, setDefault, allowManualTools ->
                    viewModel.saveProviderConfig(
                        type = targetType,
                        rawApiKey = rawKey,
                        baseUrl = baseUrl,
                        selectedModelId = modelId,
                        manualModelId = manualModelId,
                        setAsDefault = setDefault,
                        hasExistingKey = currentConfig.hasApiKey,
                        allowManualToolsOverride = allowManualTools
                    )
                },
                onRefreshModels = { viewModel.refreshProviderModels(currentConfig) },
                onDisconnect = { viewModel.disconnectProvider(targetType) },
                onDismiss = { editingProviderType = null }
            )
        }
    }
}
