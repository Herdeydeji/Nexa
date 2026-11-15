package com.example.ui.connections

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.ProviderConfig
import com.example.ui.components.NoticeBanner
import com.example.ui.components.StatusPill
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaInfo
import com.example.ui.theme.NexaInfoContainer
import com.example.ui.theme.NexaNeutral
import com.example.ui.theme.NexaNeutralContainer
import com.example.ui.theme.NexaOnOrange
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSecondaryBackground
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProviderConfigSheet(
    config: ProviderConfig,
    isRefreshingModels: Boolean,
    refreshError: String?,
    onSave: (rawKey: String, baseUrl: String, selectedModelId: String?, manualModelId: String, setAsDefault: Boolean, allowManualToolsOverride: Boolean) -> String?,
    onRefreshModels: () -> Unit,
    onDisconnect: () -> Unit,
    onDismiss: () -> Unit
) {
    var apiKeyInput by remember(config.providerType) { mutableStateOf("") }
    var showKeyPlaintext by remember { mutableStateOf(false) }
    var baseUrlInput by remember(config.providerType) { mutableStateOf(config.baseUrl) }
    var selectedModelId by remember(config.providerType) { mutableStateOf(config.selectedModelId) }
    var manualModelInput by remember(config.providerType) { mutableStateOf(config.manualModelId) }
    var modelSearchQuery by remember(config.providerType) { mutableStateOf("") }
    var setAsDefault by remember(config.providerType) { mutableStateOf(config.isDefault) }
    var allowManualToolsOverride by remember(config.providerType) { mutableStateOf(config.allowManualToolsOverride) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val canDisconnect = config.hasApiKey || (config.providerType.requiresBaseUrl && config.baseUrl.isNotBlank())
    val filteredModels = remember(config.availableModels, modelSearchQuery) {
        val q = modelSearchQuery.trim()
        if (q.isEmpty()) {
            config.availableModels
        } else {
            config.availableModels.filter {
                it.id.contains(q, ignoreCase = true) ||
                    it.displayName.contains(q, ignoreCase = true)
            }
        }
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = NexaSecondaryBackground,
        contentColor = NexaTextPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = config.providerType.displayName,
                style = MaterialTheme.typography.titleLarge,
                color = NexaTextPrimary
            )
            Text(
                text = config.providerType.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = NexaTextSecondary
            )

            if (canDisconnect) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(NexaElevatedSurface)
                        .border(1.dp, NexaBorder, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when {
                                config.credentialInvalidated -> "Stored Key Invalidated"
                                config.hasApiKey -> "Stored Key (Encrypted)"
                                else -> "Configured Endpoint"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (config.credentialInvalidated) NexaError else NexaTextSecondary
                        )
                        Text(
                            text = if (config.hasApiKey) config.maskedApiKey else config.baseUrl,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (config.credentialInvalidated) NexaError else NexaOrange,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(
                        onClick = {
                            onDisconnect()
                            onDismiss()
                        },
                        modifier = Modifier.testTag("provider_disconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.DeleteOutline,
                            contentDescription = "Disconnect provider",
                            tint = NexaError,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Disconnect", color = NexaError)
                    }
                }
            }

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = {
                    apiKeyInput = it
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider_api_key_input"),
                label = {
                    Text(
                        when {
                            config.hasApiKey -> "Replace API Key (optional)"
                            config.providerType.requiresBaseUrl -> "API Key (optional for local host)"
                            else -> "API Key"
                        }
                    )
                },
                placeholder = { Text(config.providerType.keyHint) },
                singleLine = true,
                visualTransformation = if (showKeyPlaintext) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { showKeyPlaintext = !showKeyPlaintext }) {
                        Icon(
                            imageVector = if (showKeyPlaintext) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (showKeyPlaintext) "Hide API key" else "Show API key",
                            tint = NexaTextSecondary
                        )
                    }
                }
            )

            OutlinedTextField(
                value = baseUrlInput,
                onValueChange = {
                    baseUrlInput = it
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider_base_url_input"),
                label = {
                    Text(if (config.providerType.requiresBaseUrl) "Base Endpoint URL (Required)" else "Base Endpoint URL")
                },
                placeholder = { Text("https://api.example.com/v1") },
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Model Selection",
                    style = MaterialTheme.typography.titleSmall,
                    color = NexaTextPrimary
                )
                TextButton(
                    onClick = onRefreshModels,
                    enabled = !isRefreshingModels && (config.hasApiKey || config.providerType.requiresBaseUrl),
                    modifier = Modifier.testTag("provider_refresh_models_button")
                ) {
                    if (isRefreshingModels) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = NexaOrange
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Loading...")
                    } else {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh Models")
                    }
                }
            }

            if (!refreshError.isNullOrBlank()) {
                NoticeBanner(
                    icon = Icons.Outlined.ErrorOutline,
                    text = refreshError,
                    accentColor = NexaError
                )
            }

            OutlinedTextField(
                value = manualModelInput,
                onValueChange = {
                    manualModelInput = it
                    if (it.isNotBlank()) {
                        selectedModelId = it.trim()
                    }
                    errorMessage = null
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("provider_manual_model_input"),
                label = { Text("Manual Model ID (optional override)") },
                placeholder = { Text("e.g. openai/gpt-4.1-mini or qwen2.5:14b") },
                singleLine = true,
                trailingIcon = {
                    if (manualModelInput.isNotBlank()) {
                        TextButton(onClick = { selectedModelId = manualModelInput.trim() }) {
                            Text("Use", color = NexaOrange)
                        }
                    }
                }
            )

            if (config.availableModels.isNotEmpty()) {
                OutlinedTextField(
                    value = modelSearchQuery,
                    onValueChange = { modelSearchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("provider_model_search_input"),
                    label = { Text("Search ${config.availableModels.size} models") },
                    placeholder = { Text("Filter by model ID or name...") },
                    singleLine = true,
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = NexaTextSecondary
                        )
                    },
                    trailingIcon = {
                        if (modelSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { modelSearchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Clear model search",
                                    tint = NexaTextSecondary
                                )
                            }
                        }
                    }
                )
            }

            if (config.availableModels.isNotEmpty() && filteredModels.isEmpty()) {
                Text(
                    text = "No models match \"$modelSearchQuery\". You can enter a manual model ID above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaTextSecondary
                )
            } else if (filteredModels.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredModels, key = { it.id }) { model ->
                        val isSelected = selectedModelId == model.id
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
                                .clickable { selectedModelId = model.id }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (isSelected) NexaOrange else NexaTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    StatusPill(
                                        label = if (model.isManualEntry) "Manual" else if (model.isCatalogVerified) "Live" else "Preset",
                                        textColor = if (model.isCatalogVerified) NexaInfo else NexaNeutral,
                                        containerColor = if (model.isCatalogVerified) NexaInfoContainer else NexaNeutralContainer,
                                        showDot = false
                                    )
                                }
                                Text(
                                    text = buildString {
                                        append("${model.contextWindowLabel} ctx")
                                        if (model.supportsVision) append(" · Vision")
                                        if (model.supportsTools) append(" · Tools")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = NexaTextSecondary
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { setAsDefault = !setAsDefault },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = setAsDefault,
                    onCheckedChange = { setAsDefault = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = NexaOrange,
                        checkmarkColor = NexaOnOrange
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Set as default Nexa model",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexaTextPrimary
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { allowManualToolsOverride = !allowManualToolsOverride }
                    .testTag("provider_allow_tools_switch"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = allowManualToolsOverride,
                    onCheckedChange = { allowManualToolsOverride = it },
                    colors = CheckboxDefaults.colors(
                        checkedColor = NexaOrange,
                        checkmarkColor = NexaOnOrange
                    )
                )
                Spacer(modifier = Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Allow tool calling for manual/unverified models",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexaTextPrimary
                    )
                    Text(
                        text = "Catalog-verified models enable tools automatically. Manual or custom models default to conversational-only unless enabled here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NexaTextSecondary
                    )
                }
            }

            errorMessage?.let { err ->
                NoticeBanner(
                    icon = Icons.Outlined.ErrorOutline,
                    text = err,
                    accentColor = NexaError
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = {
                        val err = onSave(
                            apiKeyInput,
                            baseUrlInput,
                            selectedModelId,
                            manualModelInput,
                            setAsDefault,
                            allowManualToolsOverride
                        )
                        if (err == null) {
                            onDismiss()
                        } else {
                            errorMessage = err
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("provider_save_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexaOrange,
                        contentColor = NexaOnOrange
                    )
                ) {
                    Text("Save Config")
                }
            }
        }
    }
}
