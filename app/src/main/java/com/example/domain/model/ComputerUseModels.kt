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
    BLOCKED_BY_APPLOCK,
    BLOCKED_BY_DIALOG,
    APP_LOADING,
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

    data class Ambiguous(
        val query: String,
        val candidates: List<InstalledAppInfo>,
        val reason: String
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
        errorCode == "BLOCKED_BY_APPLOCK" -> AppLaunchStatus.BLOCKED_BY_APPLOCK
        errorCode == "BLOCKED_BY_DIALOG" -> AppLaunchStatus.BLOCKED_BY_DIALOG
        errorCode == "APP_LOADING" -> AppLaunchStatus.APP_LOADING
        else -> AppLaunchStatus.LAUNCH_FAILED
    },
    val requiresUserAction: Boolean = false,
    val recoveryAction: String? = null,
    val blockerReason: String? = null,
    val userTitle: String? = null,
    val userMessage: String? = null,
    val ambiguousCandidates: List<InstalledAppInfo> = emptyList()
)

enum class YouTubePlayStatus {
    SEARCH_OPENED,
    RESULTS_OBSERVED,
    VIDEO_SELECTED,
    PLAYBACK_STARTED,
    PLAYBACK_UNVERIFIED,
    BLOCKED_BY_APPLOCK,
    FAILED
}

data class YouTubePlayOutcome(
    val status: YouTubePlayStatus,
    val query: String,
    val searchUrl: String,
    val targetPackage: String? = null,
    val observedForegroundPackage: String? = null,
    val selectedTitle: String? = null,
    val selectedChannel: String? = null,
    val playbackSignal: String? = null,
    val message: String,
    val launchOutcome: AppLaunchOutcome? = null,
    val postObservation: ScreenObservation? = null
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

    fun contains(x: Int, y: Int): Boolean = x in left..right && y in top..bottom

    fun intersects(other: UiBounds): Boolean =
        left < other.right && right > other.left && top < other.bottom && bottom > other.top

    fun area(): Long = width.toLong() * height.toLong()

    fun toCompactString(): String = "[$left,$top,$right,$bottom]"
}

enum class UiNodeSource {
    ACCESSIBILITY,
    OCR_FUSED
}

enum class WindowDialogType {
    NONE,
    APP_DIALOG,
    SYSTEM_PERMISSION,
    APPLOCK
}

enum class UiElementGroundingLevel(val tier: Int, val label: String) {
    EXACT_SNAPSHOT_NODE(1, "Exact snapshot node index"),
    RESOURCE_VIEW_ID(2, "Accessibility resource view ID"),
    EXACT_CONTENT_DESCRIPTION(3, "Exact content description"),
    EXACT_VISIBLE_TEXT(4, "Exact visible text or hint"),
    NORMALIZED_TEXT_OR_HINT(5, "Normalized case-insensitive text or hint"),
    ROLE_AND_CONTEXT(6, "Role + surrounding parent/sibling context"),
    RELATIVE_BOUNDS_ANCHOR(7, "Relative screen position / anchor"),
    OCR_TEXT_MATCH(8, "On-device OCR visual text region"),
    FUZZY_PARTIAL_SEMANTIC(9, "Unambiguous partial/fuzzy semantic match")
}

data class OcrTextRegion(
    val index: Int,
    val text: String,
    val bounds: UiBounds,
    val confidence: Float = 0.85f
)

data class GroundingMatchExplanation(
    val level: UiElementGroundingLevel,
    val confidence: Float,
    val rationale: String,
    val snapshotId: Long? = null,
    val overlapDetected: Boolean = false
)

enum class VerificationLevel(val levelNumber: Int, val label: String) {
    LEVEL_0_DISPATCHED(0, "Level 0 · Attempted Only"),
    LEVEL_1_SCREEN_CHANGED(1, "Level 1 · Screen Changed"),
    LEVEL_2_TARGET_STATE_MUTATED(2, "Level 2 · Target Verified"),
    LEVEL_3_SUBGOAL_CONFIRMED(3, "Level 3 · Subgoal Confirmed"),
    LEVEL_4_OUTCOME_VERIFIED(4, "Level 4 · Outcome Confirmed")
}

enum class ComputerUseFailureCategory(val label: String) {
    TARGET_NOT_FOUND("Target not found on screen"),
    LOW_CONFIDENCE_MATCH("Low-confidence target match"),
    AMBIGUOUS_TARGET("Ambiguous UI targets"),
    STALE_ELEMENT("Stale element snapshot"),
    ELEMENT_DISABLED("Target element disabled"),
    ELEMENT_NOT_CLICKABLE("Target element not clickable"),
    ELEMENT_OBSTRUCTED("Target obstructed by overlay or dialog"),
    OFFSCREEN_NEEDS_SCROLL("Target offscreen — scroll required"),
    TEXT_ENTRY_REJECTED("Text entry rejected by field"),
    KEYBOARD_BLOCKING_TARGET("Soft keyboard blocking target"),
    APP_STILL_LOADING("Application UI still loading"),
    UNEXPECTED_DIALOG_OR_OVERLAY("Unexpected dialog or popup blocking progress"),
    APPLOCK_OR_AUTH_REQUIRED("AppLock or user authentication required"),
    MISSING_PERMISSION("Required Android capability/permission missing"),
    NO_PROGRESS_LOOP("Repeated cycle with no measurable progress")
}

enum class AgentExecutionPhase(val label: String) {
    IDLE("Idle"),
    UNDERSTANDING_REQUEST("Understanding Request"),
    PLANNING("Planning"),
    OBSERVING("Observing Screen"),
    LOCATING_TARGET("Locating Target"),
    EXECUTING_ACTION("Interacting"),
    WAITING_FOR_TRANSITION("Waiting for UI"),
    VERIFYING("Verifying"),
    REPLANNING("Replanning"),
    WAITING_FOR_USER("Waiting for User"),
    PAUSED("Paused"),
    RECOVERING("Recovering"),
    COMPLETED("Completed"),
    PARTIALLY_COMPLETED("Partially Completed"),
    FAILED("Failed"),
    CANCELLED("Cancelled")
}

enum class TextEditMode(val apiValue: String) {
    REPLACE("replace"),
    APPEND("append"),
    CLEAR("clear"),
    SELECT_ALL("select_all"),
    PASTE("paste");

    companion object {
        fun parse(raw: String?): TextEditMode? {
            val clean = raw?.trim()?.lowercase() ?: return null
            return entries.firstOrNull { it.apiValue == clean }
        }
    }
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
    val depth: Int = 0,
    val parentIndex: Int? = null,
    val childIndices: List<Int> = emptyList(),
    val longClickable: Boolean = false,
    val selected: Boolean = false,
    val supportedActions: List<String> = emptyList(),
    val source: UiNodeSource = UiNodeSource.ACCESSIBILITY
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
    val hierarchySignature: String = "",
    val snapshotId: Long = 0L,
    val screenWidthPx: Int = 1080,
    val screenHeightPx: Int = 2400,
    val isKeyboardVisible: Boolean = false,
    val dialogType: WindowDialogType = WindowDialogType.NONE,
    val isLoadingLikely: Boolean = false,
    val hierarchyCompleteness: Float = 1.0f,
    val hasSparseHierarchy: Boolean = false,
    val screenSummary: String = "",
    val ocrRegions: List<OcrTextRegion> = emptyList()
)

data class TargetResolutionSpec(
    val nodeIndex: Int? = null,
    val viewId: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val role: String? = null,
    val editableOnly: Boolean = false,
    val expectedSnapshotId: Long? = null,
    val anchorContext: String? = null,
    val preferredQuarter: String? = null,
    val allowOcrFallback: Boolean = true
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
    val requiresAccessibilityPermission: Boolean = false,
    val verificationLevel: VerificationLevel = if (success) {
        VerificationLevel.LEVEL_2_TARGET_STATE_MUTATED
    } else {
        VerificationLevel.LEVEL_0_DISPATCHED
    },
    val failureCategory: ComputerUseFailureCategory? = null,
    val groundingExplanation: GroundingMatchExplanation? = null,
    val suggestedRecovery: String? = null,
    val ambiguousCandidates: List<ObservedUiNode> = emptyList(),
    val recoveryTried: String? = null
)
