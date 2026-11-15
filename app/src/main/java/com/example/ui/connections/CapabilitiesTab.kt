package com.example.ui.connections

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.example.R
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.CapabilityCategory
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.InstalledAppInfo
import com.example.domain.model.MediaProjectionConsentState
import com.example.ui.components.CapabilityStatusPill
import com.example.ui.components.EmptyStateView
import com.example.ui.components.NexaSurfaceCard
import com.example.ui.components.NoticeBanner
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalNexaHapticFeedback
import com.example.ui.theme.NexaTheme

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
    totalCapabilitiesCount: Int = capabilities.size,
    availableCapabilitiesCount: Int = capabilities.count { it.status == CapabilityStatus.AVAILABLE },
    actionNeededCapabilitiesCount: Int = capabilities.count { it.status != CapabilityStatus.AVAILABLE },
    mediaProjectionState: MediaProjectionConsentState = MediaProjectionConsentState.NOT_GRANTED,
    mediaProjectionReason: String = "Screen capture permission not granted. Tap Grant Screen Capture when needed.",
    mediaProjectionResolution: String? = null,
    onRequestScreenCapture: (() -> Unit)? = null,
    onStopScreenCapture: (() -> Unit)? = null,
    canDrawOverlays: Boolean = false,
    onOpenOverlaySettings: (() -> Unit)? = null,
    installedAppsSheetVisible: Boolean = false,
    installedAppsSearchQuery: String = "",
    discoveredApps: List<InstalledAppInfo> = emptyList(),
    appLaunchFeedback: String? = null,
    onOpenInstalledApps: (() -> Unit)? = null,
    onCloseInstalledApps: (() -> Unit)? = null,
    onAppsSearchQueryChange: ((String) -> Unit)? = null,
    onTestLaunchApp: ((String) -> Unit)? = null,
    onDismissAppLaunchFeedback: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val colors = NexaTheme.colors
    val haptic = LocalNexaHapticFeedback.current
    var actionFeedback by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onRefresh()
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) {
        onRefresh()
    }

    val contactsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        actionFeedback = if (granted) {
            "Contacts permission granted."
        } else {
            "Contacts permission denied."
        }
        onRefresh()
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val anyGranted = grants.values.any { it }
        actionFeedback = if (anyGranted) {
            "Location permission granted."
        } else {
            "Location permission denied."
        }
        onRefresh()
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        actionFeedback = if (uri != null) {
            "Zero-permission Photo Picker verified (${uri.lastPathSegment ?: "media selected"})."
        } else {
            "Photo Picker closed with no selection."
        }
    }

    val launchSafeIntent: (Intent, String) -> Unit = { intent, fallbackError ->
        val finalIntent = intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        val ok = runCatching { context.startActivity(finalIntent) }.isSuccess
        if (!ok) {
            actionFeedback = fallbackError
        }
    }

    val triggerNotificationsAction: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !canPostNotifications) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
            launchSafeIntent(intent, "Could not open notification settings.")
        }
    }

    val openAccessibility: () -> Unit = {
        launchSafeIntent(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            "Could not open Android Accessibility Settings."
        )
    }

    val handleCapabilityAction: (DeviceCapability) -> Unit = { capability ->
        haptic.performPrimary()
        when (capability.type) {
            DeviceCapabilityType.APP_LAUNCHING -> {
                if (onOpenInstalledApps != null) {
                    onOpenInstalledApps()
                } else {
                    actionFeedback = "Installed apps browser is unavailable in this view."
                }
            }
            DeviceCapabilityType.SCREEN_OBSERVATION,
            DeviceCapabilityType.ACCESSIBILITY_INTERACTION -> openAccessibility()
            DeviceCapabilityType.BROWSER_INTERACTION -> {
                launchSafeIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://developer.android.com")),
                    "No web browser app is installed to open URLs."
                )
            }
            DeviceCapabilityType.WIFI -> {
                val wifiIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                } else {
                    Intent(Settings.ACTION_WIFI_SETTINGS)
                }
                val launchedPanel = runCatching {
                    context.startActivity(wifiIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                }.isSuccess
                if (!launchedPanel) {
                    launchSafeIntent(Intent(Settings.ACTION_WIFI_SETTINGS), "Could not open Wi-Fi settings.")
                }
            }
            DeviceCapabilityType.BLUETOOTH -> {
                launchSafeIntent(
                    Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
                    "Could not open Bluetooth settings."
                )
            }
            DeviceCapabilityType.BRIGHTNESS -> {
                val writeSettingsIntent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                val opened = runCatching { context.startActivity(writeSettingsIntent) }.isSuccess
                if (!opened) {
                    launchSafeIntent(
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS),
                        "Could not open Modify System Settings screen."
                    )
                }
            }
            DeviceCapabilityType.CONTACTS -> {
                if (capability.status == CapabilityStatus.AVAILABLE) {
                    launchSafeIntent(
                        Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
                        "No Contacts app available."
                    )
                } else {
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
            }
            DeviceCapabilityType.CALLS -> {
                launchSafeIntent(Intent(Intent.ACTION_DIAL), "No phone dialer app found.")
            }
            DeviceCapabilityType.SMS -> {
                launchSafeIntent(
                    Intent(Intent.ACTION_VIEW, Uri.parse("sms:")),
                    "No SMS messaging app found."
                )
            }
            DeviceCapabilityType.FILES_AND_MEDIA -> {
                val launched = runCatching {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)
                    )
                }.isSuccess
                if (!launched) {
                    actionFeedback = "Photo picker activity unavailable on this device."
                }
            }
            DeviceCapabilityType.NOTIFICATIONS -> triggerNotificationsAction()
            DeviceCapabilityType.SCREENSHOTS -> {
                if (mediaProjectionState == MediaProjectionConsentState.GRANTED_ACTIVE) {
                    onStopScreenCapture?.invoke()
                } else {
                    onRequestScreenCapture?.invoke()
                }
            }
            DeviceCapabilityType.LOCATION -> {
                if (capability.status == CapabilityStatus.AVAILABLE) {
                    launchSafeIntent(
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                        "Could not open Location settings."
                    )
                } else {
                    locationPermissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
            }
        }
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
                    onClick = {
                        haptic.performPrimary()
                        onRefresh()
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("capabilities_refresh_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = "Refresh capability states",
                        tint = colors.textPrimary
                    )
                }
            }
        }

        item(key = "cap_summary_row") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.secondaryBackground)
                    .border(1.dp, colors.borderSubtle, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .testTag("capabilities_summary_counts"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$availableCapabilitiesCount available · $actionNeededCapabilitiesCount action needed",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.textPrimary
                )
                Text(
                    text = "$totalCapabilitiesCount total",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
        }

        actionFeedback?.let { msg ->
            item(key = "cap_action_feedback") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("capabilities_action_feedback_banner"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NoticeBanner(
                        icon = Icons.Outlined.Info,
                        text = msg,
                        accentColor = colors.info,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { actionFeedback = null }) {
                        Text("Dismiss", color = colors.textSecondary)
                    }
                }
            }
        }

        item(key = "accessibility_setup_card") {
            AccessibilitySetupCard(
                accessibilityState = accessibilityState,
                accessibilityReason = accessibilityReason,
                canPostNotifications = canPostNotifications,
                onOpenAccessibilitySettings = openAccessibility,
                onEnableNotifications = triggerNotificationsAction
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

        item(key = "overlay_permission_setup_card") {
            OverlayPermissionSetupCard(
                canDrawOverlays = canDrawOverlays,
                onOpenOverlaySettings = {
                    if (onOpenOverlaySettings != null) {
                        onOpenOverlaySettings()
                    } else {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}")
                        )
                        launchSafeIntent(intent, "Could not open overlay permission settings.")
                    }
                }
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
                    label = "All ($totalCapabilitiesCount)",
                    selected = selectedCategory == null,
                    onClick = {
                        haptic.performPrimary()
                        onSelectCategory(null)
                    },
                    testTag = "capability_filter_all"
                )
                CapabilityCategory.entries.forEach { category ->
                    CategoryFilterPill(
                        label = category.label,
                        selected = selectedCategory == category,
                        onClick = {
                            haptic.performPrimary()
                            onSelectCategory(category)
                        },
                        testTag = "capability_filter_${category.name.lowercase()}"
                    )
                }
            }
        }

        items(capabilities, key = { it.type.id }) { capability ->
            CapabilityCard(
                capability = capability,
                mediaProjectionState = mediaProjectionState,
                canPostNotifications = canPostNotifications,
                actionLabel = capabilityActionLabel(
                    capability = capability,
                    mediaProjectionState = mediaProjectionState,
                    canPostNotifications = canPostNotifications
                ),
                onActionClick = { handleCapabilityAction(capability) },
                onTogglePreference = { enabled ->
                    haptic.performToggle()
                    onToggleCapability(capability.type.id, enabled)
                }
            )
        }
    }

    if (installedAppsSheetVisible) {
        InstalledAppsBrowserSheet(
            searchQuery = installedAppsSearchQuery,
            apps = discoveredApps,
            launchFeedback = appLaunchFeedback,
            onSearchQueryChange = { onAppsSearchQueryChange?.invoke(it) },
            onTestLaunch = { pkg -> onTestLaunchApp?.invoke(pkg) },
            onDismissFeedback = { onDismissAppLaunchFeedback?.invoke() },
            onDismiss = { onCloseInstalledApps?.invoke() }
        )
    }
}

private fun capabilityActionLabel(
    capability: DeviceCapability,
    mediaProjectionState: MediaProjectionConsentState,
    canPostNotifications: Boolean
): String = when (capability.type) {
    DeviceCapabilityType.APP_LAUNCHING -> "Browse Installed Apps"
    DeviceCapabilityType.SCREEN_OBSERVATION,
    DeviceCapabilityType.ACCESSIBILITY_INTERACTION -> "Open Accessibility Settings"
    DeviceCapabilityType.BROWSER_INTERACTION -> "Open Browser"
    DeviceCapabilityType.WIFI -> "Open Wi-Fi Panel"
    DeviceCapabilityType.BLUETOOTH -> "Open Bluetooth Settings"
    DeviceCapabilityType.BRIGHTNESS -> "Allow Modify System Settings"
    DeviceCapabilityType.CONTACTS ->
        if (capability.status == CapabilityStatus.AVAILABLE) "Open Contacts" else "Grant Contacts Permission"
    DeviceCapabilityType.CALLS -> "Open Dialer"
    DeviceCapabilityType.SMS -> "Open Messages"
    DeviceCapabilityType.FILES_AND_MEDIA -> "Test Photo Picker"
    DeviceCapabilityType.NOTIFICATIONS ->
        if (canPostNotifications) "Notification Settings" else "Grant Notification Permission"
    DeviceCapabilityType.SCREENSHOTS ->
        if (mediaProjectionState == MediaProjectionConsentState.GRANTED_ACTIVE) {
            "Stop Capture Session"
        } else {
            "Grant Screen Capture"
        }
    DeviceCapabilityType.LOCATION ->
        if (capability.status == CapabilityStatus.AVAILABLE) "Location Settings" else "Grant Location Permission"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstalledAppsBrowserSheet(
    searchQuery: String,
    apps: List<InstalledAppInfo>,
    launchFeedback: String?,
    onSearchQueryChange: (String) -> Unit,
    onTestLaunch: (String) -> Unit,
    onDismissFeedback: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = NexaTheme.colors
    val haptic = LocalNexaHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.secondaryBackground,
        contentColor = colors.textPrimary,
        modifier = Modifier.testTag("installed_apps_sheet")
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
                        text = "Installed Apps Inventory",
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "${apps.size} launchable app(s) matching query",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                }
                TextButton(onClick = onDismiss) {
                    Text("Close", color = colors.textSecondary)
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("installed_apps_search_input"),
                label = { Text("Search installed apps") },
                placeholder = { Text("Name or package...") },
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = colors.textSecondary)
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Outlined.Close, contentDescription = "Clear search", tint = colors.textSecondary)
                        }
                    }
                }
            )

            launchFeedback?.let { feedback ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("installed_apps_feedback_banner"),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NoticeBanner(
                        icon = Icons.Outlined.Info,
                        text = feedback,
                        accentColor = colors.info,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismissFeedback) {
                        Text("Dismiss", color = colors.textSecondary)
                    }
                }
            }

            if (apps.isEmpty()) {
                EmptyStateView(
                    icon = Icons.Outlined.Apps,
                    title = "No matching apps found",
                    description = "Try a different app name or package query."
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(apps, key = { it.packageName }) { app ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(colors.elevatedSurface)
                                .border(1.dp, colors.border, RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.appName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = colors.textPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = app.packageName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.textSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    haptic.performPrimary()
                                    onTestLaunch(app.packageName)
                                },
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("launch_app_${app.packageName}")
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.PlayArrow,
                                    contentDescription = "Launch ${app.appName}",
                                    tint = colors.orange,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Launch", color = colors.orange)
                            }
                        }
                    }
                }
            }
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
    val colors = NexaTheme.colors
    val (pillFg, pillBg) = when (accessibilityState) {
        AccessibilityRuntimeState.SERVICE_ENABLED -> colors.success to colors.successContainer
        AccessibilityRuntimeState.ENABLED_NOT_CONNECTED,
        AccessibilityRuntimeState.DEVICE_LOCKED,
        AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE -> colors.info to colors.infoContainer
        AccessibilityRuntimeState.SERVICE_DISABLED,
        AccessibilityRuntimeState.PERMISSION_REQUIRED -> colors.warning to colors.warningContainer
        AccessibilityRuntimeState.UNSUPPORTED -> colors.error to colors.errorContainer
    }

    NexaSurfaceCard(
        borderColor = if (accessibilityState == AccessibilityRuntimeState.SERVICE_ENABLED) colors.success else colors.orange,
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
                    .background(colors.orangeMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.AccessibilityNew,
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Nexa Computer-Use Service",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = "Android Accessibility Bridge",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
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
            color = colors.textSecondary
        )

        if (accessibilityState != AccessibilityRuntimeState.SERVICE_ENABLED) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Setup: Open Android Accessibility Settings -> Installed apps -> Nexa Computer-Use Service -> Turn on. Nexa never enables accessibility silently.",
                style = MaterialTheme.typography.labelSmall,
                color = colors.textPrimary
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
                    .heightIn(min = 48.dp)
                    .testTag("capabilities_open_accessibility_settings"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.orange,
                    contentColor = colors.onOrange
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
                    .heightIn(min = 48.dp)
                    .testTag("capabilities_enable_notifications")
            ) {
                Icon(
                    imageVector = Icons.Outlined.NotificationsActive,
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.enable_task_notifications), color = colors.textPrimary)
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
    val colors = NexaTheme.colors
    val (pillFg, pillBg) = when (consentState) {
        MediaProjectionConsentState.GRANTED_ACTIVE -> colors.success to colors.successContainer
        MediaProjectionConsentState.NOT_GRANTED,
        MediaProjectionConsentState.REVOKED -> colors.warning to colors.warningContainer
        MediaProjectionConsentState.UNSUPPORTED -> colors.error to colors.errorContainer
    }

    NexaSurfaceCard(
        borderColor = if (consentState == MediaProjectionConsentState.GRANTED_ACTIVE) colors.success else colors.border,
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
                    .background(colors.orangeMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Screenshot,
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Screen Capture (MediaProjection)",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = resolution?.let { "Active virtual display ($it)" } ?: "Explicit Android consent required for take_screenshot",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
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
            color = colors.textSecondary
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
                        .heightIn(min = 48.dp)
                        .testTag("capabilities_stop_screen_capture")
                ) {
                    Text(stringResource(R.string.stop_screen_capture), color = colors.error)
                }
            } else if (consentState != MediaProjectionConsentState.UNSUPPORTED) {
                Button(
                    onClick = { onRequestScreenCapture?.invoke() },
                    enabled = onRequestScreenCapture != null,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("capabilities_request_screen_capture"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.orange,
                        contentColor = colors.onOrange
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
private fun OverlayPermissionSetupCard(
    canDrawOverlays: Boolean,
    onOpenOverlaySettings: () -> Unit
) {
    val colors = NexaTheme.colors
    val (pillFg, pillBg) = if (canDrawOverlays) {
        colors.success to colors.successContainer
    } else {
        colors.warning to colors.warningContainer
    }

    NexaSurfaceCard(
        borderColor = if (canDrawOverlays) colors.success else colors.border,
        modifier = Modifier.testTag("overlay_permission_setup_card")
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
                    .background(colors.orangeMuted),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Layers,
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(20.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.overlay_permission_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = "Floating Nexa status bubble over external apps",
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
                )
            }
            StatusPill(
                label = if (canDrawOverlays) "Granted" else "Not Granted",
                textColor = pillFg,
                containerColor = pillBg
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.overlay_permission_rationale),
            style = MaterialTheme.typography.bodySmall,
            color = colors.textSecondary
        )

        if (!canDrawOverlays) {
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = onOpenOverlaySettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("capabilities_grant_overlay_permission"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.orange,
                    contentColor = colors.onOrange
                )
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.grant_overlay_permission))
            }
        }
    }
}

@Composable
private fun CategoryFilterPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String
) {
    val colors = NexaTheme.colors
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.orangeMuted else colors.elevatedSurface)
            .border(1.dp, if (selected) colors.orange else colors.border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(testTag)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) colors.orange else colors.textSecondary
        )
    }
}

@Composable
private fun CapabilityCard(
    capability: DeviceCapability,
    mediaProjectionState: MediaProjectionConsentState,
    canPostNotifications: Boolean,
    actionLabel: String,
    onActionClick: () -> Unit,
    onTogglePreference: (Boolean) -> Unit
) {
    val colors = NexaTheme.colors
    var expanded by rememberSaveable(capability.type.id) { mutableStateOf(false) }

    val actionEnabled = when (capability.type) {
        DeviceCapabilityType.SCREENSHOTS -> mediaProjectionState != MediaProjectionConsentState.UNSUPPORTED
        else -> true
    }

    NexaSurfaceCard(
        onClick = { expanded = !expanded },
        borderColor = colors.border,
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
                    .background(colors.elevatedSurface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = capabilityIcon(capability.type),
                    contentDescription = null,
                    tint = colors.orange,
                    modifier = Modifier.size(18.dp)
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = capability.type.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.textPrimary
                )
                Text(
                    text = capability.type.category.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.textSecondary
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
            color = colors.textSecondary
        )

        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onActionClick,
            enabled = actionEnabled,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("capability_action_${capability.type.id}")
        ) {
            Text(
                text = actionLabel,
                color = if (capability.type == DeviceCapabilityType.SCREENSHOTS &&
                    mediaProjectionState == MediaProjectionConsentState.GRANTED_ACTIVE
                ) {
                    colors.error
                } else {
                    colors.orange
                }
            )
        }

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
                    color = colors.textPrimary
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
                        color = colors.textSecondary
                    )
                    Switch(
                        checked = capability.userEnabled,
                        onCheckedChange = onTogglePreference,
                        modifier = Modifier.testTag("capability_switch_${capability.type.id}"),
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = colors.onOrange,
                            checkedTrackColor = colors.orange
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
