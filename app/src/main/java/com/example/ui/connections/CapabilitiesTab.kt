package com.example.ui.connections

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.domain.model.CapabilityCategory
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.ui.components.CapabilityStatusPill
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.theme.NexaBorder
import com.example.ui.theme.NexaElevatedSurface
import com.example.ui.theme.NexaOnOrange
import com.example.ui.theme.NexaOrange
import com.example.ui.theme.NexaOrangeMuted
import com.example.ui.theme.NexaTextPrimary
import com.example.ui.theme.NexaTextSecondary

@Composable
fun CapabilitiesTab(
    capabilities: List<DeviceCapability>,
    selectedCategory: CapabilityCategory?,
    onSelectCategory: (CapabilityCategory?) -> Unit,
    onRefresh: () -> Unit,
    onToggleCapability: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
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
                    text = "Live Android capability inspection. Nexa never marks unconfigured or unauthorized permissions as active.",
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh capability states",
                        tint = NexaTextPrimary
                    )
                }
            }
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
            CapabilityStatusPill(status = capability.status)
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
                        text = "Allow in Phase 2 agent plans",
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
