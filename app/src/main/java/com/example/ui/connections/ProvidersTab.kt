package com.example.ui.connections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.ProviderConfig
import com.example.domain.model.ProviderConnectionStatus
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.SectionHeader
import com.example.ui.components.StatusPill
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaErrorContainer
import com.example.ui.theme.NexaInfo
import com.example.ui.theme.NexaInfoContainer
import com.example.ui.theme.NexaNeutral
import com.example.ui.theme.NexaNeutralContainer
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSuccess
import com.example.ui.theme.NexaSuccessContainer
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary
import com.example.ui.theme.NexaWarning
import com.example.ui.theme.NexaWarningContainer

@Composable
fun ProvidersTab(
    providers: List<ProviderConfig>,
    onConfigureProvider: (ProviderConfig) -> Unit,
    onTestProvider: (ProviderConfig) -> Unit,
    onSetDefault: (ProviderConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuredCount = providers.count {
        it.hasApiKey || (it.providerType.requiresBaseUrl && it.baseUrl.isNotBlank())
    }
    val defaultProvider = providers.firstOrNull {
        it.isDefault && (it.hasApiKey || (it.providerType.requiresBaseUrl && it.baseUrl.isNotBlank()))
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("providers_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item(key = "providers_overview") {
            NexaSurfaceCard(borderColor = NexaBorder) {
                SectionHeader(
                    title = "Model Management",
                    subtitle = "Provider adapters with Keystore-encrypted credentials & live verification"
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Default Model",
                            style = MaterialTheme.typography.labelSmall,
                            color = NexaTextSecondary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = defaultProvider?.let {
                                "${it.providerType.displayName} · ${it.selectedModelId ?: "Select model"}"
                            } ?: "No active default model configured",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (defaultProvider != null) NexaOrange else NexaTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    StatusPill(
                        label = "$configuredCount/${providers.size} ready",
                        textColor = if (configuredCount > 0) NexaInfo else NexaNeutral,
                        containerColor = if (configuredCount > 0) NexaInfoContainer else NexaNeutralContainer,
                        showDot = false
                    )
                }
            }
        }

        if (configuredCount == 0) {
            item(key = "no_keys_notice") {
                NoticeBanner(
                    icon = Icons.Outlined.Info,
                    text = "Configure an API key or compatible endpoint below, then tap Test Connection to verify credentials and load available models."
                )
            }
        }

        if (providers.isEmpty()) {
            item(key = "empty_providers") {
                EmptyStateView(
                    icon = Icons.Outlined.CloudOff,
                    title = "No AI providers registered",
                    description = "Add an AI provider adapter to configure models."
                )
            }
        } else {
            items(providers, key = { it.providerType.id }) { config ->
                ProviderCard(
                    config = config,
                    onConfigure = { onConfigureProvider(config) },
                    onTest = { onTestProvider(config) },
                    onSetDefault = { onSetDefault(config) }
                )
            }
        }
    }
}

@Composable
private fun ProviderCard(
    config: ProviderConfig,
    onConfigure: () -> Unit,
    onTest: () -> Unit,
    onSetDefault: () -> Unit
) {
    val canActivate = config.hasApiKey || (config.providerType.requiresBaseUrl && config.baseUrl.isNotBlank())
    val selectedModel = config.selectedModelInfo

    NexaSurfaceCard(
        onClick = onConfigure,
        borderColor = if (config.isDefault) NexaOrange.copy(alpha = 0.6f) else NexaBorder,
        modifier = Modifier.testTag("provider_card_${config.providerType.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = config.providerType.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = NexaTextPrimary
                    )
                    if (config.isDefault) {
                        StatusPill(
                            label = "DEFAULT",
                            textColor = NexaOrange,
                            containerColor = NexaOrangeMuted,
                            showDot = false
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = config.providerType.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaTextSecondary
                )
            }
            ProviderStatusBadge(status = config.connectionStatus)
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(NexaElevatedSurface)
                .padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Key,
                    contentDescription = null,
                    tint = if (config.hasApiKey) NexaOrange else NexaTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = when {
                        config.hasApiKey -> config.maskedApiKey
                        config.providerType.requiresBaseUrl && config.baseUrl.isNotBlank() -> config.baseUrl
                        else -> "No credential saved"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (canActivate) NexaTextPrimary else NexaTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = buildString {
                    append(config.selectedModelId?.substringAfterLast("/") ?: "No model")
                    if (selectedModel?.isManualEntry == true) append(" (Manual)")
                },
                style = MaterialTheme.typography.labelSmall,
                color = NexaTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        when (val status = config.connectionStatus) {
            is ProviderConnectionStatus.Connected -> {
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(
                    icon = Icons.Outlined.CheckCircle,
                    text = status.message,
                    accentColor = NexaSuccess
                )
            }
            is ProviderConnectionStatus.AuthFailed -> {
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(icon = Icons.Outlined.ErrorOutline, text = status.message, accentColor = NexaError)
            }
            is ProviderConnectionStatus.RateLimited -> {
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(icon = Icons.Outlined.Info, text = status.message, accentColor = NexaWarning)
            }
            is ProviderConnectionStatus.EndpointError -> {
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(icon = Icons.Outlined.ErrorOutline, text = status.message, accentColor = NexaError)
            }
            is ProviderConnectionStatus.NetworkError -> {
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(icon = Icons.Outlined.ErrorOutline, text = status.message, accentColor = NexaWarning)
            }
            is ProviderConnectionStatus.ValidationError -> {
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(icon = Icons.Outlined.ErrorOutline, text = status.message, accentColor = NexaError)
            }
            is ProviderConnectionStatus.KeyInvalidated -> {
                Spacer(modifier = Modifier.height(8.dp))
                NoticeBanner(icon = Icons.Outlined.ErrorOutline, text = status.message, accentColor = NexaError)
            }
            else -> Unit
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = onConfigure,
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Configure")
            }

            OutlinedButton(
                onClick = onTest,
                enabled = config.connectionStatus !is ProviderConnectionStatus.Testing,
                modifier = Modifier
                    .weight(1f)
                    .testTag("provider_test_${config.providerType.id}")
            ) {
                if (config.connectionStatus is ProviderConnectionStatus.Testing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = NexaOrange
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Testing...")
                } else {
                    Text("Test Connection")
                }
            }

            if (canActivate && !config.isDefault) {
                TextButton(
                    onClick = onSetDefault,
                    modifier = Modifier.clickable { onSetDefault() }
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Star,
                        contentDescription = "Set default",
                        tint = NexaOrange,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ProviderStatusBadge(status: ProviderConnectionStatus) {
    val (label, fg, bg) = when (status) {
        ProviderConnectionStatus.NotConfigured -> Triple("Not configured", NexaNeutral, NexaNeutralContainer)
        ProviderConnectionStatus.ConfiguredUnverified -> Triple("Saved (Unverified)", NexaWarning, NexaWarningContainer)
        ProviderConnectionStatus.Testing -> Triple("Testing...", NexaInfo, NexaInfoContainer)
        is ProviderConnectionStatus.Connected -> Triple("Connected", NexaSuccess, NexaSuccessContainer)
        is ProviderConnectionStatus.AuthFailed -> Triple("Auth Failed", NexaError, NexaErrorContainer)
        is ProviderConnectionStatus.RateLimited -> Triple("Rate Limited", NexaWarning, NexaWarningContainer)
        is ProviderConnectionStatus.EndpointError -> Triple("Endpoint Error", NexaError, NexaErrorContainer)
        is ProviderConnectionStatus.NetworkError -> Triple("Network Error", NexaWarning, NexaWarningContainer)
        is ProviderConnectionStatus.ValidationError -> Triple("Error", NexaError, NexaErrorContainer)
        is ProviderConnectionStatus.KeyInvalidated -> Triple("Key Invalidated", NexaError, NexaErrorContainer)
    }
    StatusPill(label = label, textColor = fg, containerColor = bg)
}
