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
import com.example.domain.model.CapabilityStatus
import com.example.domain.model.DeviceCapability
import com.example.domain.model.DeviceCapabilityType

interface CapabilityInspector {
    fun inspectAll(userToggles: Map<String, Boolean>): List<DeviceCapability>
}

class AndroidCapabilityInspector(
    private val context: Context
) : CapabilityInspector {

    override fun inspectAll(userToggles: Map<String, Boolean>): List<DeviceCapability> {
        val pm = context.packageManager
        return DeviceCapabilityType.entries.map { type ->
            val enabled = userToggles[type.id] ?: false
            val (status, reason) = evaluateCapability(type, pm)
            DeviceCapability(
                type = type,
                status = status,
                statusReason = reason,
                userEnabled = enabled
            )
        }
    }

    private fun evaluateCapability(
        type: DeviceCapabilityType,
        pm: PackageManager
    ): Pair<CapabilityStatus, String> {
        return when (type) {
            DeviceCapabilityType.APP_LAUNCHING -> {
                CapabilityStatus.NOT_CONFIGURED to
                    "Intent launcher interface ready. Target package querying is deferred to Phase 2."
            }
            DeviceCapabilityType.SCREEN_OBSERVATION -> {
                CapabilityStatus.NOT_CONFIGURED to
                    "Nexa Accessibility Service is not registered or enabled in Phase 1."
            }
            DeviceCapabilityType.ACCESSIBILITY_INTERACTION -> {
                CapabilityStatus.NOT_CONFIGURED to
                    "Accessibility gesture bridge is not active in Phase 1."
            }
            DeviceCapabilityType.BROWSER_INTERACTION -> {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
                val hasBrowser = browserIntent.resolveActivity(pm) != null
                if (hasBrowser) {
                    CapabilityStatus.AVAILABLE to
                        "Default browser intent handler detected on this device."
                } else {
                    CapabilityStatus.UNAVAILABLE to
                        "No browser activity resolved for web intents."
                }
            }
            DeviceCapabilityType.WIFI -> {
                val hasWifi = pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
                if (!hasWifi) {
                    CapabilityStatus.UNAVAILABLE to "Wi-Fi hardware feature not reported."
                } else {
                    CapabilityStatus.PERMISSION_REQUIRED to
                        "Hardware present; Wi-Fi control requires Phase 2 system panel or network state permissions."
                }
            }
            DeviceCapabilityType.BLUETOOTH -> {
                val hasBt = pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)
                val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                when {
                    !hasBt || adapter == null ->
                        CapabilityStatus.UNAVAILABLE to "Bluetooth adapter unavailable on this device."
                    else ->
                        CapabilityStatus.PERMISSION_REQUIRED to
                            "Bluetooth hardware present; BLUETOOTH_CONNECT runtime permission not requested in Phase 1."
                }
            }
            DeviceCapabilityType.BRIGHTNESS -> {
                val canWrite = Settings.System.canWrite(context)
                if (canWrite) {
                    CapabilityStatus.AVAILABLE to "System write-settings permission is granted."
                } else {
                    CapabilityStatus.PERMISSION_REQUIRED to
                        "Requires user approval in Android 'Modify system settings'."
                }
            }
            DeviceCapabilityType.CONTACTS -> {
                val granted = hasPermission(Manifest.permission.READ_CONTACTS)
                if (granted) {
                    CapabilityStatus.AVAILABLE to "Contacts permission granted."
                } else {
                    CapabilityStatus.PERMISSION_REQUIRED to
                        "READ_CONTACTS is not declared or granted in Phase 1."
                }
            }
            DeviceCapabilityType.CALLS -> {
                val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                if (!hasTelephony) {
                    CapabilityStatus.UNAVAILABLE to "Device does not report telephony hardware."
                } else {
                    CapabilityStatus.RESTRICTED to
                        "Direct call placement is restricted; Phase 2 will use explicit confirmation and dialer handoff."
                }
            }
            DeviceCapabilityType.SMS -> {
                val hasTelephony = pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
                if (!hasTelephony) {
                    CapabilityStatus.UNAVAILABLE to "SMS telephony hardware not present."
                } else {
                    CapabilityStatus.RESTRICTED to
                        "Direct SMS sending is restricted under Google Play policy; Phase 2 will compose via SMS intent."
                }
            }
            DeviceCapabilityType.FILES_AND_MEDIA -> {
                CapabilityStatus.AVAILABLE to
                    "Android Photo Picker and Storage Access Framework are available with zero broad storage permissions."
            }
            DeviceCapabilityType.NOTIFICATIONS -> {
                val enabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                    CapabilityStatus.PERMISSION_REQUIRED to
                        "POST_NOTIFICATIONS permission not yet requested in Phase 1."
                } else if (enabled) {
                    CapabilityStatus.AVAILABLE to "App notification channel delivery is enabled."
                } else {
                    CapabilityStatus.RESTRICTED to "Notifications are disabled at the system level."
                }
            }
            DeviceCapabilityType.SCREENSHOTS -> {
                CapabilityStatus.NOT_CONFIGURED to
                    "MediaProjection screen capture session is not configured in Phase 1."
            }
            DeviceCapabilityType.LOCATION -> {
                val hasLocation = pm.hasSystemFeature(PackageManager.FEATURE_LOCATION)
                when {
                    !hasLocation ->
                        CapabilityStatus.UNAVAILABLE to "Location hardware feature not reported."
                    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ->
                        CapabilityStatus.AVAILABLE to "Foreground location permission granted."
                    else ->
                        CapabilityStatus.PERMISSION_REQUIRED to
                            "Location permission is not declared or requested in Phase 1."
                }
            }
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}
