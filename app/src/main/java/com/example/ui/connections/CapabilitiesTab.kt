package com.example.ui.connections

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.R
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.CapabilityCategory
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.MediaProjectionConsentState
import com.example.ui.components.CapabilityStatusPill
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.StatusPill
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaError
import com.example.ui.theme.NexaErrorContainer
import com.example.ui.theme.NexaInfo
import com.example.ui.theme.NexaInfoContainer
import com.example.ui.theme.NexaOnOrange
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaSuccess
import com.example.ui.theme.NexaSuccessContainer
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary
import com.example.ui.theme.NexaWarning
import com.example.ui.theme.NexaWarningContainer

@Composable
fun CapabilitiesTab(
    capabilities: List<DeviceCapability>,
    selectedCategory: CapabilityCategory?,
    accessibilityState: AccessibilityRuntimeState,
    accessibilityReason: String,
    canPostNotifications: Boolean,
    onSelectCategory: (CapabilityCategory?) -> Unit,
    onRefresh: () -> Unit,
    onToggleCapability: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    mediaProjectionState: MediaProjectionConsentState = MediaProjectionConsentState.NOT_GRANTED,
    mediaProjectionReason: String = "Screen capture permission not granted. Tap Grant Screen Capture when needed.",
    mediaProjectionResolution: String? = null,
    onRequestScreenCapture: (() -> Unit)? = null,
    onStopScreenCapture: (() -> Unit)? = null
) {
    val context = LocalContext.current

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onRefresh()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefresh()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .testTag("capabilities_list"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item(key = "cap_notice") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                NoticeBanner(
                    icon = Icons.Outlined.Info,
                    text = "Live Android capability inspection. Nexa only enables computer-use tools when required system services and permissions are active.",
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("capabilities_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh capability states",
                        tint = NexaTextPrimary
                    )
                }
            }
        }

        item(key = "accessibility_setup_card") {
            AccessibilitySetupCard(
                accessibilityState = accessibilityState,
                accessibilityReason = accessibilityReason,
                canPostNotifications = canPostNotifications,
                onOpenAccessibilitySettings = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                },
                onEnableNotifications = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                }
            )
        }

        item(key = "screen_capture_setup_card") {
            ScreenCaptureSetupCard(
                consentState = mediaProjectionState,
                reason = mediaProjectionReason,
                resolution = mediaProjectionResolution,
                onRequestScreenCapture = onRequestScreenCapture,
                onStopScreenCapture = onStopScreenCapture
            )
        }

        item(key = "cap_filters") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryFilterPill(
                    label = "All (14)",
                    selected = selectedCategory == null,
                    onClick = { onSelectCategory(null) }
                )
                CapabilityCategory.entries.forEach { category ->
                    CategoryFilterPill(
                        label = category.label,
                        selected = selectedCategory == category,
                        onClick = { onSelectCategory(category) }
                    )
                }
            }
        }

        items(capabilities, key = { it.type.id }) { capability ->
            CapabilityCard(
                capability = capability,
                onTogglePreference = { enabled ->
                    onToggleCapability(capability.type.id, enabled)
                }
            )
        }
    }
}

@Composable
private fun AccessibilitySetupCard(
    accessibilityState: AccessibilityRuntimeState,
    accessibilityReason: String,
    canPostNotifications: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onEnableNotifications: () -> Unit
) {
    val (pillFg, pillBg) = when (accessibilityState) {
        AccessibilityRuntimeState.SERVICE_ENABLED -> NexaSuccess to NexaSuccessContainer
        AccessibilityRuntimeState.ENABLED_NOT_CONNECTED,
        AccessibilityRuntimeState.DEVICE_LOCKED,
        AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE -> NexaInfo to NexaInfoContainer
        AccessibilityRuntimeState.SERVICE_DISABLED,
        AccessibilityRuntimeState.PERMISSION_REQUIRED -> NexaWarning to NexaWarningContainer
        AccessibilityRuntimeState.UNSUPPORTED -> NexaError to NexaErrorContainer
    }

    NexaSurfaceCard(
        borderColor = if (accessibilityState == AccessibilityRuntimeState.SERVICE_ENABLED) NexaSuccess else NexaOrange,
        modifier = Modifier.testTag("accessibility_setup_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NexaOrangeMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccessibilityNew,
                    contentDescription = null,
                    tint = NexaOrange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nexa Computer-Use Service",
                    style = MaterialTheme.typography.titleSmall,
                    color = NexaTextPrimary
                )
                Text(
                    text = "Android Accessibility Bridge",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexaTextSecondary
                )
            }
            StatusPill(
                label = accessibilityState.label,
                textColor = pillFg,
                containerColor = pillBg
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = accessibilityReason,
            style = MaterialTheme.typography.bodySmall,
            color = NexaTextSecondary
        )

        if (accessibilityState != AccessibilityRuntimeState.SERVICE_ENABLED) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Setup: Open Android Accessibility Settings -> Installed apps -> Nexa Computer-Use Service -> Turn on. Nexa never enables accessibility silently.",
                style = MaterialTheme.typography.labelSmall,
                color = NexaTextPrimary
            )
        }

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier
                    .weight(1f)
                    .testTag("capabilities_open_accessibility_settings"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NexaOrange,
                    contentColor = NexaOnOrange
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.open_accessibility_settings))
            }
        }

        if (!canPostNotifications) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = onEnableNotifications,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("capabilities_enable_notifications")
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = NexaOrange,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.enable_task_notifications), color = NexaTextPrimary)
            }
        }
    }
}

@Composable
private fun ScreenCaptureSetupCard(
    consentState: MediaProjectionConsentState,
    reason: String,
    resolution: String?,
    onRequestScreenCapture: (() -> Unit)?,
    onStopScreenCapture: (() -> Unit)?
) {
    val (pillFg, pillBg) = when (consentState) {
        MediaProjectionConsentState.GRANTED_ACTIVE -> NexaSuccess to NexaSuccessContainer
        MediaProjectionConsentState.NOT_GRANTED,
        MediaProjectionConsentState.REVOKED -> NexaWarning to NexaWarningContainer
        MediaProjectionConsentState.UNSUPPORTED -> NexaError to NexaErrorContainer
    }

    NexaSurfaceCard(
        borderColor = if (consentState == MediaProjectionConsentState.GRANTED_ACTIVE) NexaSuccess else NexaBorder,
        modifier = Modifier.testTag("screen_capture_setup_card")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(NexaOrangeMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Screenshot,
                    contentDescription = null,
                    tint = NexaOrange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Screen Capture (MediaProjection)",
                    style = MaterialTheme.typography.titleSmall,
                    color = NexaTextPrimary
                )
                Text(
                    text = resolution?.let { "Active virtual display ($it)" } ?: "Explicit Android consent required for take_screenshot",
                    style = MaterialTheme.typography.labelSmall,
                    color = NexaTextSecondary
                )
            }
            StatusPill(
                label = consentState.label,
                textColor = pillFg,
                containerColor = pillBg
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = NexaTextSecondary
        )

        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (consentState == MediaProjectionConsentState.GRANTED_ACTIVE) {
                OutlinedButton(
                    onClick = { onStopScreenCapture?.invoke() },
                    enabled = onStopScreenCapture != null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("capabilities_stop_screen_capture")
                ) {
                    Text(stringResource(R.string.stop_screen_capture), color = NexaError)
                }
            } else if (consentState != MediaProjectionConsentState.UNSUPPORTED) {
                Button(
                    onClick = { onRequestScreenCapture?.invoke() },
                    enabled = onRequestScreenCapture != null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("capabilities_request_screen_capture"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = NexaOrange,
                        contentColor = NexaOnOrange
                    )
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Screenshot,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(R.string.grant_screen_capture_permission))
                }
            }
        }
    }
}

@Composable
private fun CategoryFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) NexaOrangeMuted else NexaElevatedSurface)
            .border(1.dp, if (selected) NexaOrange else NexaBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) NexaOrange else NexaTextSecondary
        )
    }
}

@Composable
private fun CapabilityCard(
    capability: DeviceCapability,
    onTogglePreference: (Boolean) -> Unit
) {
    var expanded by rememberSaveable(capability.type.id) { mutableStateOf(false) }

    NexaSurfaceCard(
        onClick = { expanded = !expanded },
        borderColor = NexaBorder,
        modifier = Modifier.testTag("capability_card_${capability.type.id}")
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(NexaElevatedSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = capabilityIcon(capability.type),
                    contentDescription = null,
                    tint = NexaOrange,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = capability.type.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NexaTextPrimary
                )
                Text(
                    text = capability.type.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = NexaTextSecondary
                )
            }
            CapabilityStatusPill(
                status = capability.status,
                labelOverride = capability.statusBadgeOverride
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = capability.statusReason,
            style = MaterialTheme.typography.bodySmall,
            color = NexaTextSecondary
        )

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = capability.type.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NexaTextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (capability.type.category == CapabilityCategory.AUTOMATION) {
                            "Allow in autonomous agent tools"
                        } else {
                            "Allow in agent plans when available"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = NexaTextSecondary
                    )
                    Switch(
                        checked = capability.userEnabled,
                        onCheckedChange = onTogglePreference,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = NexaOnOrange,
                            checkedTrackColor = NexaOrange
                        )
                    )
                }
            }
        }
    }
}

private fun capabilityIcon(type: DeviceCapabilityType): ImageVector = when (type) {
    DeviceCapabilityType.APP_LAUNCHING -> Icons.Outlined.Apps
    DeviceCapabilityType.SCREEN_OBSERVATION -> Icons.Outlined.Visibility
    DeviceCapabilityType.ACCESSIBILITY_INTERACTION -> Icons.Outlined.AccessibilityNew
    DeviceCapabilityType.BROWSER_INTERACTION -> Icons.Outlined.Language
    DeviceCapabilityType.WIFI -> Icons.Outlined.Wifi
    DeviceCapabilityType.BLUETOOTH -> Icons.Outlined.Bluetooth
    DeviceCapabilityType.BRIGHTNESS -> Icons.Outlined.BrightnessMedium
    DeviceCapabilityType.CONTACTS -> Icons.Outlined.Contacts
    DeviceCapabilityType.CALLS -> Icons.Outlined.Call
    DeviceCapabilityType.SMS -> Icons.Outlined.Sms
    DeviceCapabilityType.FILES_AND_MEDIA -> Icons.Outlined.FolderOpen
    DeviceCapabilityType.NOTIFICATIONS -> Icons.Outlined.NotificationsNone
    DeviceCapabilityType.SCREENSHOTS -> Icons.Outlined.Screenshot
    DeviceCapabilityType.LOCATION -> Icons.Outlined.LocationOn
}
