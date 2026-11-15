package com.example.domain.model

enum class AccessibilityRuntimeState(val label: String) {
    SERVICE_ENABLED("Service connected"),
    SERVICE_DISABLED("Service disabled"),
    ENABLED_NOT_CONNECTED("Enabled, not connected"),
    PERMISSION_REQUIRED("Permission required"),
    TEMPORARILY_UNAVAILABLE("Temporarily unavailable"),
    DEVICE_LOCKED("Device locked"),
    UNSUPPORTED("Unsupported")
}

enum class AppLaunchStatus {
    LAUNCH_DISPATCHED,
    FOREGROUND_VERIFIED,
    NOT_FOUND,
    NOT_LAUNCHABLE,
    LAUNCH_FAILED
}

data class InstalledAppInfo(
    val appName: String,
    val packageName: String,
    val launcherActivity: String? = null,
    val isLaunchable: Boolean = true,
    val isSystemApp: Boolean = false
)

sealed interface AppLookupOutcome {
    data class Found(
        val app: InstalledAppInfo,
        val alternatives: List<InstalledAppInfo> = emptyList()
    ) : AppLookupOutcome

    data class NotLaunchable(
        val appName: String,
        val packageName: String,
        val reason: String
    ) : AppLookupOutcome

    data class NotFound(
        val query: String,
        val visibilityRestricted: Boolean,
        val reason: String
    ) : AppLookupOutcome
}

data class AppLaunchOutcome(
    val success: Boolean,
    val appName: String?,
    val packageName: String,
    val launchIntentDispatched: Boolean,
    val foregroundVerified: Boolean,
    val observedForegroundPackage: String? = null,
    val message: String,
    val errorCode: String? = null,
    val launchStatus: AppLaunchStatus = when {
        success && foregroundVerified -> AppLaunchStatus.FOREGROUND_VERIFIED
        success && launchIntentDispatched -> AppLaunchStatus.LAUNCH_DISPATCHED
        errorCode == "APP_NOT_FOUND" -> AppLaunchStatus.NOT_FOUND
        errorCode == "APP_NOT_LAUNCHABLE" -> AppLaunchStatus.NOT_LAUNCHABLE
        else -> AppLaunchStatus.LAUNCH_FAILED
    }
)

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = (right - left).coerceAtLeast(0)
    val height: Int get() = (bottom - top).coerceAtLeast(0)
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun toCompactString(): String = "[$left,$top,$right,$bottom]"
}

data class ObservedUiNode(
    val index: Int,
    val viewId: String? = null,
    val className: String? = null,
    val role: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val hintText: String? = null,
    val bounds: UiBounds,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val focused: Boolean = false,
    val enabled: Boolean = true,
    val isPassword: Boolean = false,
    val isSensitiveRedacted: Boolean = false,
    val depth: Int = 0
)

data class ScreenObservation(
    val timestampMillis: Long,
    val activePackageName: String?,
    val windowTitle: String?,
    val windowType: String = "application",
    val isInteractive: Boolean = true,
    val isDeviceLocked: Boolean = false,
    val isRestrictedWindow: Boolean = false,
    val nodes: List<ObservedUiNode> = emptyList(),
    val totalNodesVisited: Int = 0,
    val truncatedByLimit: Boolean = false,
    val observationError: String? = null,
    val hierarchySignature: String = ""
)

data class TargetResolutionSpec(
    val nodeIndex: Int? = null,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val editableOnly: Boolean = false
)

enum class ScrollDirection(val apiValue: String) {
    DOWN("down"),
    UP("up"),
    LEFT("left"),
    RIGHT("right");

    companion object {
        fun parse(raw: String?): ScrollDirection? {
            val clean = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.apiValue == clean }
        }
    }
}

data class ComputerUseActionOutcome(
    val success: Boolean,
    val action: String,
    val summary: String,
    val verificationDetail: String,
    val beforePackage: String? = null,
    val afterPackage: String? = null,
    val screenChanged: Boolean = false,
    val postObservation: ScreenObservation? = null,
    val errorCode: String? = null,
    val requiresAccessibilityPermission: Boolean = false
)
