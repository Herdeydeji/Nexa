package com.example.android.runtime

import com.example.domain.model.ComputerUseFailureCategory
import com.example.domain.model.ScreenObservation
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.VerificationLevel
import com.example.domain.model.WindowDialogType

data class FailureDiagnosis(
    val category: ComputerUseFailureCategory,
    val summary: String,
    val recoverySuggestion: String
)

/**
 * Classifies UI automation failures across 15+ distinct failure categories and
 * proposes concrete alternative strategies instead of blind retries.
 */
object FailureDiagnosisEngine {

    fun diagnose(
        actionName: String,
        errorCode: String?,
        detail: String,
        before: ScreenObservation?,
        after: ScreenObservation?,
        targetSpec: TargetResolutionSpec? = null,
        verificationLevel: VerificationLevel? = null
    ): FailureDiagnosis? {
        val code = errorCode?.uppercase().orEmpty()
        val obs = after ?: before

        return when {
            code in setOf(
                "ACCESSIBILITY_PERMISSION_REQUIRED",
                "ACCESSIBILITY_NOT_CONNECTED",
                "MEDIA_PROJECTION_CONSENT_REQUIRED",
                "SERVICE_DISCONNECTED"
            ) -> FailureDiagnosis(
                category = ComputerUseFailureCategory.MISSING_PERMISSION,
                summary = detail,
                recoverySuggestion = "Ask user to enable Accessibility Service or grant Screen Capture permission before continuing UI interaction."
            )

            code == "BLOCKED_BY_APPLOCK" || obs?.dialogType == WindowDialogType.APPLOCK -> FailureDiagnosis(
                category = ComputerUseFailureCategory.APPLOCK_OR_AUTH_REQUIRED,
                summary = "Blocked by Transsion AppLock or authentication screen.",
                recoverySuggestion = "Wait cleanly for user authentication/unlock; never bypass or tap AppLock UI."
            )

            obs?.dialogType == WindowDialogType.SYSTEM_PERMISSION || obs?.dialogType == WindowDialogType.APP_DIALOG -> FailureDiagnosis(
                category = ComputerUseFailureCategory.UNEXPECTED_DIALOG_OR_OVERLAY,
                summary = "An active popup or permission dialog (${obs.dialogType.name}) is intercepting interactions.",
                recoverySuggestion = "Observe dialog buttons and dismiss/resolve the dialog (or ask user approval) before retrying target."
            )

            code == "AMBIGUOUS_TARGET" || code == "UI_AMBIGUOUS" -> FailureDiagnosis(
                category = ComputerUseFailureCategory.AMBIGUOUS_TARGET,
                summary = detail,
                recoverySuggestion = "Use anchor_context, view_id, node_index with snapshot_id, or ask the user to choose among tied candidates."
            )

            code == "LOW_CONFIDENCE_MATCH" -> FailureDiagnosis(
                category = ComputerUseFailureCategory.LOW_CONFIDENCE_MATCH,
                summary = detail,
                recoverySuggestion = "Observe refreshed screen or capture OCR/screenshot context before retrying with a clearer selector."
            )

            code == "STALE_SNAPSHOT" || code == "STALE_NODE" -> FailureDiagnosis(
                category = ComputerUseFailureCategory.STALE_ELEMENT,
                summary = detail,
                recoverySuggestion = "Re-observe the active screen to acquire a fresh snapshot_id and updated node indices."
            )

            code == "ELEMENT_OBSTRUCTED" -> {
                if (obs?.isKeyboardVisible == true) {
                    FailureDiagnosis(
                        category = ComputerUseFailureCategory.KEYBOARD_BLOCKING_TARGET,
                        summary = "Target appears covered by the soft keyboard or lower overlay.",
                        recoverySuggestion = "Call press_back to dismiss the soft keyboard or scroll_screen down before tapping."
                    )
                } else {
                    FailureDiagnosis(
                        category = ComputerUseFailureCategory.ELEMENT_OBSTRUCTED,
                        summary = detail,
                        recoverySuggestion = "Dismiss covering popup/sheet or scroll target into clear viewport before tapping."
                    )
                }
            }

            code == "ELEMENT_DISABLED" -> FailureDiagnosis(
                category = ComputerUseFailureCategory.ELEMENT_DISABLED,
                summary = detail,
                recoverySuggestion = "Complete prerequisite form fields or toggles before interacting with this disabled control."
            )

            code == "ELEMENT_NOT_CLICKABLE" -> FailureDiagnosis(
                category = ComputerUseFailureCategory.ELEMENT_NOT_CLICKABLE,
                summary = detail,
                recoverySuggestion = "Try tapping clickable parent/container bounds via tap_coordinates or double_tap_element."
            )

            code == "TEXT_ENTRY_REJECTED" ||
                (code == "VERIFICATION_INCONCLUSIVE" && actionName in setOf("type_text", "edit_text_field")) -> FailureDiagnosis(
                category = ComputerUseFailureCategory.TEXT_ENTRY_REJECTED,
                summary = detail,
                recoverySuggestion = "Tap or focus the field first, then retry edit_text_field(mode='paste') or clear existing text."
            )

            obs?.isKeyboardVisible == true && actionName == "tap_element" && targetSpec?.text?.contains("submit", ignoreCase = true) == true -> FailureDiagnosis(
                category = ComputerUseFailureCategory.KEYBOARD_BLOCKING_TARGET,
                summary = "Soft keyboard may be covering the bottom submit action.",
                recoverySuggestion = "Try press_enter directly or press_back to dismiss the keyboard."
            )

            obs?.isLoadingLikely == true -> FailureDiagnosis(
                category = ComputerUseFailureCategory.APP_STILL_LOADING,
                summary = "Screen contains active progress/loading indicators.",
                recoverySuggestion = "Use wait_for_element or wait_for_screen_change for dynamic content to finish loading."
            )

            code == "TARGET_NOT_FOUND" -> {
                val hasScrollable = obs?.nodes?.any { it.scrollable } == true
                if (hasScrollable) {
                    FailureDiagnosis(
                        category = ComputerUseFailureCategory.OFFSCREEN_NEEDS_SCROLL,
                        summary = detail,
                        recoverySuggestion = "Target was not visible in current viewport; scroll_screen('down') or check navigation drawer/tabs."
                    )
                } else {
                    FailureDiagnosis(
                        category = ComputerUseFailureCategory.TARGET_NOT_FOUND,
                        summary = detail,
                        recoverySuggestion = "Inspect refreshed screen nodes or capture screenshot/OCR for unlabeled icons."
                    )
                }
            }

            code == "SCROLL_BOUNDARY_REACHED" -> FailureDiagnosis(
                category = ComputerUseFailureCategory.OFFSCREEN_NEEDS_SCROLL,
                summary = detail,
                recoverySuggestion = "Scroll container reached its boundary without changing content; reverse direction or switch tabs."
            )

            verificationLevel == VerificationLevel.LEVEL_0_DISPATCHED || code == "NO_UI_EFFECT" -> FailureDiagnosis(
                category = ComputerUseFailureCategory.NO_PROGRESS_LOOP,
                summary = "Action executed without measurable screen, focus, or content change.",
                recoverySuggestion = "Switch strategy: target a clickable ancestor/OCR region, use tap_coordinates, or press_back if trapped in an overlay."
            )

            else -> FailureDiagnosis(
                category = ComputerUseFailureCategory.NO_PROGRESS_LOOP,
                summary = detail,
                recoverySuggestion = "Re-observe screen and adapt plan with a different selector or navigation path."
            )
        }
    }
}
