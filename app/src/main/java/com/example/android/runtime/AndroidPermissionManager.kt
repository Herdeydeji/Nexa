package com.example.android.runtime

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.KeyguardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.android.accessibility.NexaAccessibilityService
import com.example.android.media.ScreenshotCaptureBackend
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.MediaProjectionConsentState
import android.media.projection.MediaProjectionManager

data class PermissionInspectionSnapshot(
    val accessibilityState: AccessibilityRuntimeState,
    val accessibilityReason: String,
    val isServiceEnabledInSettings: Boolean,
    val isServiceConnected: Boolean,
    val isServiceInterrupted: Boolean,
    val isDeviceLocked: Boolean,
    val canPerformGestures: Boolean,
    val canRetrieveWindowContent: Boolean,
    val canPostNotifications: Boolean,
    val hasBrowserHandler: Boolean,
    val launchableAppDiscoveryAvailable: Boolean,
    val mediaProjectionState: MediaProjectionConsentState = MediaProjectionConsentState.NOT_GRANTED,
    val mediaProjectionReason: String = "Screen capture permission not granted."
)

open class AndroidPermissionManager(
    private val context: Context,
    private val screenshotBackend: ScreenshotCaptureBackend? = null
) {

    open fun inspect(): PermissionInspectionSnapshot {
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = runCatching { keyguard?.isKeyguardLocked == true }.getOrDefault(false) ||
            NexaAccessibilityService.runtimeState.value.isScreenLocked

        val enabledInSettings = isNexaAccessibilityEnabledInSystem(am)
        val liveService = NexaAccessibilityService.currentInstance()
        val liveSnapshot = NexaAccessibilityService.runtimeState.value
        val isConnected = liveService != null && liveSnapshot.isConnected
        val isInterrupted = liveSnapshot.isInterrupted

        val (accState, accReason) = when {
            am == null -> AccessibilityRuntimeState.UNSUPPORTED to
                "Unsupported — Android AccessibilityManager service is unavailable on this device."
            isLocked -> AccessibilityRuntimeState.DEVICE_LOCKED to
                "Device locked — Unlock the screen so Nexa Accessibility Service can observe or interact with apps."
            isInterrupted && enabledInSettings -> AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE to
                "Temporarily unavailable — Nexa Accessibility Service was interrupted by the system. Re-toggle it if it does not recover."
            isConnected -> AccessibilityRuntimeState.SERVICE_ENABLED to
                "Service connected — Nexa Accessibility Service is active for screen observation and UI actions."
            enabledInSettings -> AccessibilityRuntimeState.ENABLED_NOT_CONNECTED to
                "Enabled, not connected — Nexa Accessibility Service is enabled in Settings, waiting for Android service binding (or needs re-toggle)."
            else -> AccessibilityRuntimeState.SERVICE_DISABLED to
                "Service disabled (Permission required) — Enable Nexa Computer-Use Service in Android Accessibility Settings."
        }

        val canGestures = isConnected && (liveService?.serviceInfo?.capabilities?.let {
            (it and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0
        } ?: true)

        val canRetrieveContent = isConnected && (liveService?.serviceInfo?.capabilities?.let {
            (it and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0
        } ?: true)

        val notificationsGranted = isNotificationPostingPermitted()

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
        val hasBrowser = runCatching { browserIntent.resolveActivity(pm) != null }.getOrDefault(false)

        val projSnap = screenshotBackend?.state?.value
        val (projState, projReason) = if (projSnap != null) {
            projSnap.consentState to projSnap.statusReason
        } else {
            val supported = runCatching {
                context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager != null
            }.getOrDefault(false)
            if (supported) {
                MediaProjectionConsentState.NOT_GRANTED to
                    "Screen capture permission not granted. Tap Grant Screen Capture when needed."
            } else {
                MediaProjectionConsentState.UNSUPPORTED to
                    "MediaProjection screen capture is unsupported on this device."
            }
        }

        return PermissionInspectionSnapshot(
            accessibilityState = accState,
            accessibilityReason = accReason,
            isServiceEnabledInSettings = enabledInSettings,
            isServiceConnected = isConnected,
            isServiceInterrupted = isInterrupted,
            isDeviceLocked = isLocked,
            canPerformGestures = canGestures,
            canRetrieveWindowContent = canRetrieveContent,
            canPostNotifications = notificationsGranted,
            hasBrowserHandler = hasBrowser,
            launchableAppDiscoveryAvailable = true,
            mediaProjectionState = projState,
            mediaProjectionReason = projReason
        )
    }

    open fun isAccessibilityReady(): Boolean =
        inspect().accessibilityState == AccessibilityRuntimeState.SERVICE_ENABLED

    open fun isMediaProjectionActive(): Boolean =
        inspect().mediaProjectionState == MediaProjectionConsentState.GRANTED_ACTIVE

    open fun isNotificationPostingPermitted(): Boolean {
        val channelEnabled = runCatching {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }.getOrDefault(true)
        if (!channelEnabled) return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun buildOpenAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun buildOpenAppNotificationSettingsIntent(): Intent {
        return Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    private fun isNexaAccessibilityEnabledInSystem(am: AccessibilityManager?): Boolean {
        val expectedComponent = ComponentName(context, NexaAccessibilityService::class.java)
        val expectedFlat = expectedComponent.flattenToString()
        val expectedShort = expectedComponent.flattenToShortString()

        if (am != null) {
            val enabledList = runCatching {
                am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            }.getOrNull().orEmpty()
            val matchedInManager = enabledList.any { info ->
                val resolved = info.resolveInfo?.serviceInfo
                if (resolved != null) {
                    resolved.packageName == context.packageName &&
                        resolved.name == NexaAccessibilityService::class.java.name
                } else {
                    info.id == expectedFlat || info.id == expectedShort
                }
            }
            if (matchedInManager) return true
        }

        val secureValue = runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull().orEmpty()

        if (secureValue.isBlank()) return false
        val accessibilityMasterEnabled = runCatching {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        }.getOrDefault(false)
        if (!accessibilityMasterEnabled) return false

        return secureValue.split(':').any { token ->
            val trimmed = token.trim()
            trimmed.equals(expectedFlat, ignoreCase = true) ||
                trimmed.equals(expectedShort, ignoreCase = true) ||
                ComponentName.unflattenFromString(trimmed) == expectedComponent
        }
    }
}
