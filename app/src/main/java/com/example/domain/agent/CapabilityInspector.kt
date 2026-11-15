package com.example.domain.agent

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.android.app.AndroidAppDiscovery
import com.example.android.runtime.AndroidPermissionManager
import com.example.android.runtime.PermissionInspectionSnapshot
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType
import com.example.domain.model.MediaProjectionConsentState

interface CapabilityInspector {
    fun inspectAll(userToggles: Map<String, Boolean>): List<DeviceCapability>
}

class AndroidCapabilityInspector(
    private val context: Context,
    private val permissionManager: AndroidPermissionManager = AndroidPermissionManager(context),
    private val appDiscovery: AndroidAppDiscovery = AndroidAppDiscovery(context)
) : CapabilityInspector {

    override fun inspectAll(userToggles: Map<String, Boolean>): List<DeviceCapability> {
        val pm = context.packageManager
        val permSnapshot = permissionManager.inspect()
        return DeviceCapabilityType.entries.map { type ->
            val defaultEnabled = type in DEFAULT_ENABLED_CAPABILITIES
            val enabled = userToggles[type.id] ?: defaultEnabled
            val eval = evaluateCapability(type, pm, permSnapshot)
            DeviceCapability(
                type = type,
                status = eval.status,
                statusReason = eval.reason,
                userEnabled = enabled,
                statusBadgeOverride = eval.badgeOverride
            )
        }
    }

    private data class CapabilityEval(
        val status: CapabilityStatus,
        val reason: String,
        val badgeOverride: String? = null
    )

    private fun evaluateCapability(
        type: DeviceCapabilityType,
        pm: PackageManager,
        perm: PermissionInspectionSnapshot
    ): CapabilityEval {
        return when (type) {
            DeviceCapabilityType.APP_LAUNCHING -> {
                val batch = appDiscovery.listInstalledApps(includeNonLaunchable = true, limit = 50)
                val launchableCount = batch.apps.count { it.isLaunchable }
                if (batch.totalDiscovered > 0) {
                    CapabilityEval(
                        status = CapabilityStatus.AVAILABLE,
                        reason = "$launchableCount launchable app(s) (${batch.totalDiscovered} visible packages) discovered via Android launcher & package queries."
                    )
                } else {
                    CapabilityEval(
                        status = CapabilityStatus.AVAILABLE,
                        reason = "Launcher & package discovery active (visibility scoped by Android <queries>)."
                    )
                }
            }
            DeviceCapabilityType.SCREEN_OBSERVATION -> {
                when (perm.accessibilityState) {
                    AccessibilityRuntimeState.SERVICE_ENABLED -> CapabilityEval(
                        status = if (perm.canRetrieveWindowContent) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
                        reason = perm.accessibilityReason,
                        badgeOverride = if (perm.canRetrieveWindowContent) "Service enabled" else "Permission required"
                    )
                    AccessibilityRuntimeState.SERVICE_DISABLED,
                    AccessibilityRuntimeState.PERMISSION_REQUIRED -> CapabilityEval(
                        status = CapabilityStatus.PERMISSION_REQUIRED,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Service disabled"
                    )
                    AccessibilityRuntimeState.ENABLED_NOT_CONNECTED -> CapabilityEval(
                        status = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Enabled, not connected"
                    )
                    AccessibilityRuntimeState.DEVICE_LOCKED -> CapabilityEval(
                        status = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Device locked"
                    )
                    AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE -> CapabilityEval(
                        status = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Temporarily unavailable"
                    )
                    AccessibilityRuntimeState.UNSUPPORTED -> CapabilityEval(
                        status = CapabilityStatus.UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Unsupported"
                    )
                }
            }
            DeviceCapabilityType.ACCESSIBILITY_INTERACTION -> {
                when (perm.accessibilityState) {
                    AccessibilityRuntimeState.SERVICE_ENABLED -> CapabilityEval(
                        status = if (perm.canPerformGestures) CapabilityStatus.AVAILABLE else CapabilityStatus.PERMISSION_REQUIRED,
                        reason = if (perm.canPerformGestures) {
                            "Service enabled — Tap, long-press, type, scroll, Back, Home, and Enter tools are active."
                        } else {
                            "Accessibility service connected without gesture capability."
                        },
                        badgeOverride = if (perm.canPerformGestures) "Service enabled" else "Permission required"
                    )
                    AccessibilityRuntimeState.SERVICE_DISABLED,
                    AccessibilityRuntimeState.PERMISSION_REQUIRED -> CapabilityEval(
                        status = CapabilityStatus.PERMISSION_REQUIRED,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Service disabled"
                    )
                    AccessibilityRuntimeState.ENABLED_NOT_CONNECTED -> CapabilityEval(
                        status = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Enabled, not connected"
                    )
                    AccessibilityRuntimeState.DEVICE_LOCKED -> CapabilityEval(
                        status = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Device locked"
                    )
                    AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE -> CapabilityEval(
                        status = CapabilityStatus.TEMPORARILY_UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Temporarily unavailable"
                    )
                    AccessibilityRuntimeState.UNSUPPORTED -> CapabilityEval(
                        status = CapabilityStatus.UNAVAILABLE,
                        reason = perm.accessibilityReason,
                        badgeOverride = "Unsupported"
                    )
                }
            }
            DeviceCapabilityType.BROWSER_INTERACTION -> {
                when {
                    !perm.hasBrowserHandler -> CapabilityEval(
                        status = CapabilityStatus.UNAVAILABLE,
                        reason = "No browser activity resolved for web intents."
                    )
                    perm.accessibilityState == AccessibilityRuntimeState.SERVICE_ENABLED -> CapabilityEval(
                        status = CapabilityStatus.AVAILABLE,
                        reason = "Browser launcher detected and Accessibility UI interaction is active for in-browser navigation."
                    )
                    else -> CapabilityEval(
                        status = CapabilityStatus.PERMISSION_REQUIRED,
                        reason = "Browser detected for launching; enable Nexa Accessibility Service to interact inside browser pages.",
                        badgeOverride = "Service disabled"
                    )
                }
            }
            DeviceCapabilityType.WIFI -> {
                val hasWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
                if (!hasWifi) {
                    CapabilityEval(CapabilityStatus.UNAVAILABLE, "Wi-Fi hardware feature not reported.")
                } else {
                    CapabilityEval(
                        CapabilityStatus.PERMISSION_REQUIRED,
                        "Hardware present; Wi-Fi control uses Android system settings panels."
                    )
                }
            }
            DeviceCapabilityType.BLUETOOTH -> {
                val hasBt = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                when {
                    !hasBt || adapter == null ->
                        CapabilityEval(CapabilityStatus.UNAVAILABLE, "Bluetooth adapter unavailable on this device.")
                    else ->
                        CapabilityEval(
                            CapabilityStatus.PERMISSION_REQUIRED,
                            "Bluetooth hardware present; BLUETOOTH_CONNECT runtime permission not requested."
                        )
                }
            }
            DeviceCapabilityType.BRIGHTNESS -> {
                val canWrite = Settings.System.canWrite(context)
                if (canWrite) {
                    CapabilityEval(CapabilityStatus.AVAILABLE, "System write-settings permission is granted.")
                } else {
                    CapabilityEval(
                        CapabilityStatus.PERMISSION_REQUIRED,
                        "Requires user approval in Android 'Modify system settings'."
                    )
                }
            }
            DeviceCapabilityType.CONTACTS -> {
                val granted = hasPermission(Manifest.permission.READ_CONTACTS)
                if (granted) {
                    CapabilityEval(CapabilityStatus.AVAILABLE, "Contacts permission granted.")
                } else {
                    CapabilityEval(
                        CapabilityStatus.PERMISSION_REQUIRED,
                        "READ_CONTACTS is not declared or requested."
                    )
                }
            }
            DeviceCapabilityType.CALLS -> {
                val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                if (!hasTelephony) {
                    CapabilityEval(CapabilityStatus.UNAVAILABLE, "Device does not report telephony hardware.")
                } else {
                    CapabilityEval(
                        CapabilityStatus.RESTRICTED,
                        "Direct call placement is restricted; explicit confirmation and dialer handoff required."
                    )
                }
            }
            DeviceCapabilityType.SMS -> {
                val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                if (!hasTelephony) {
                    CapabilityEval(CapabilityStatus.UNAVAILABLE, "SMS telephony hardware not present.")
                } else {
                    CapabilityEval(
                        CapabilityStatus.RESTRICTED,
                        "Direct SMS sending is restricted under Google Play policy; requires explicit user confirmation."
                    )
                }
            }
            DeviceCapabilityType.FILES_AND_MEDIA -> {
                CapabilityEval(
                    CapabilityStatus.AVAILABLE,
                    "Android Photo Picker and Storage Access Framework are available with zero broad storage permissions."
                )
            }
            DeviceCapabilityType.NOTIFICATIONS -> {
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    CapabilityEval(
                        CapabilityStatus.PERMISSION_REQUIRED,
                        "Grant notification permission to show ongoing background task progress and quick Cancel alerts."
                    )
                } else if (enabled) {
                    CapabilityEval(
                        CapabilityStatus.AVAILABLE,
                        "Task progress notifications and inline Cancel controls are enabled."
                    )
                } else {
                    CapabilityEval(
                        CapabilityStatus.RESTRICTED,
                        "Notifications are disabled at the system level."
                    )
                }
            }
            DeviceCapabilityType.SCREENSHOTS -> {
                when (perm.mediaProjectionState) {
                    MediaProjectionConsentState.GRANTED_ACTIVE -> CapabilityEval(
                        status = CapabilityStatus.AVAILABLE,
                        reason = perm.mediaProjectionReason,
                        badgeOverride = "Active session"
                    )
                    MediaProjectionConsentState.REVOKED -> CapabilityEval(
                        status = CapabilityStatus.PERMISSION_REQUIRED,
                        reason = perm.mediaProjectionReason,
                        badgeOverride = "Session revoked"
                    )
                    MediaProjectionConsentState.UNSUPPORTED -> CapabilityEval(
                        status = CapabilityStatus.UNAVAILABLE,
                        reason = perm.mediaProjectionReason,
                        badgeOverride = "Unsupported"
                    )
                    MediaProjectionConsentState.NOT_GRANTED -> CapabilityEval(
                        status = CapabilityStatus.PERMISSION_REQUIRED,
                        reason = perm.mediaProjectionReason,
                        badgeOverride = "Consent required"
                    )
                }
            }
            DeviceCapabilityType.LOCATION -> {
                val hasLocation = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION)
                when {
                    !hasLocation ->
                        CapabilityEval(CapabilityStatus.UNAVAILABLE, "Location hardware feature not reported.")
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ->
                        CapabilityEval(CapabilityStatus.AVAILABLE, "Foreground location permission granted.")
                    else ->
                        CapabilityEval(
                            CapabilityStatus.PERMISSION_REQUIRED,
                            "Location permission is not declared or requested."
                        )
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val DEFAULT_ENABLED_CAPABILITIES = setOf(
            DeviceCapabilityType.APP_LAUNCHING,
            DeviceCapabilityType.SCREEN_OBSERVATION,
            DeviceCapabilityType.ACCESSIBILITY_INTERACTION,
            DeviceCapabilityType.BROWSER_INTERACTION,
            DeviceCapabilityType.FILES_AND_MEDIA,
            DeviceCapabilityType.NOTIFICATIONS,
            DeviceCapabilityType.SCREENSHOTS
        )
    }
}
