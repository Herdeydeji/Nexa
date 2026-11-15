package com.example.android.runtime

import com.example.android.accessibility.AccessibilityInteractionController
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.InteractionDispatchResult
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.app.AppDiscoveryBatch
import com.example.android.media.ScreenshotCaptureBackend
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AppLaunchOutcome
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.ComputerUseActionOutcome
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScreenshotCaptureOutcome
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

open class AndroidActionRuntime(
    val permissionManager: AndroidPermissionManager,
    val appDiscovery: AndroidAppDiscovery,
    val appLauncher: AndroidAppLauncher,
    val screenObserver: AndroidScreenObserver,
    val interactionController: AccessibilityInteractionController,
    val actionVerifier: AndroidActionVerifier,
    val screenshotBackend: ScreenshotCaptureBackend? = null
) {

    open fun isAutomationActive(): Boolean = permissionManager.isAccessibilityReady()

    open fun listInstalledApps(
        query: String? = null,
        includeSystemApps: Boolean = true,
        limit: Int = AndroidAppDiscovery.DEFAULT_LIST_LIMIT,
        includeNonLaunchable: Boolean = false
    ): AppDiscoveryBatch = appDiscovery.listInstalledApps(
        query = query,
        includeSystemApps = includeSystemApps,
        includeNonLaunchable = includeNonLaunchable,
        limit = limit
    )

    open fun findApp(query: String): AppLookupOutcome = appDiscovery.findApp(query)

    open suspend fun launchApp(appQuery: String): AppLaunchOutcome {
        currentCoroutineContext().ensureActive()
        return appLauncher.launch(appQuery)
    }

    open suspend fun takeScreenshot(includeBase64: Boolean = false): ScreenshotCaptureOutcome {
        currentCoroutineContext().ensureActive()
        val backend = screenshotBackend
            ?: return ScreenshotCaptureOutcome(
                success = false,
                message = "Screen capture consent is required before calling take_screenshot. Grant MediaProjection permission in Chat or Connections -> Capabilities.",
                errorCode = "MEDIA_PROJECTION_CONSENT_REQUIRED",
                requiresConsent = true
            )
        return backend.captureScreenshot(includeBase64)
    }

    open suspend fun observeScreen(): ScreenObservation {
        currentCoroutineContext().ensureActive()
        return screenObserver.observe()
    }

    open suspend fun waitForScreenChange(
        timeoutMillis: Long = 3_000L,
        expectedPackage: String? = null
    ): ComputerUseActionOutcome {
        currentCoroutineContext().ensureActive()
        val permError = checkAccessibilityGate("wait_for_screen_change")
        if (permError != null) return permError

        val (changed, obs) = screenObserver.waitForChange(timeoutMillis, expectedPackage)
        return ComputerUseActionOutcome(
            success = obs.observationError == null,
            action = "wait_for_screen_change",
            summary = if (changed) {
                "Observed screen change on ${obs.activePackageName ?: "active window"}."
            } else {
                "No screen change detected within ${timeoutMillis}ms on ${obs.activePackageName ?: "active window"}."
            },
            verificationDetail = obs.observationError ?: "Observed ${obs.nodes.size} nodes.",
            afterPackage = obs.activePackageName,
            screenChanged = changed,
            postObservation = obs,
            errorCode = if (obs.observationError != null) "OBSERVATION_ERROR" else null
        )
    }

    open suspend fun tapElement(spec: TargetResolutionSpec): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "tap_element",
            validate = {
                if (spec.nodeIndex == null && spec.viewId.isNullOrBlank() &&
                    spec.text.isNullOrBlank() && spec.contentDescription.isNullOrBlank()
                ) {
                    "Provide at least one target selector: node_index, view_id, text, or content_description."
                } else {
                    null
                }
            },
            execute = { before -> interactionController.tapElement(spec, before) }
        )

    open suspend fun longPressElement(spec: TargetResolutionSpec): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "long_press_element",
            validate = {
                if (spec.nodeIndex == null && spec.viewId.isNullOrBlank() &&
                    spec.text.isNullOrBlank() && spec.contentDescription.isNullOrBlank()
                ) {
                    "Provide at least one target selector: node_index, view_id, text, or content_description."
                } else {
                    null
                }
            },
            execute = { before -> interactionController.longPressElement(spec, before) }
        )

    open suspend fun tapCoordinates(x: Float, y: Float): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "tap_coordinates",
            validate = {
                if (x < 0f || y < 0f) "Coordinates x and y must be non-negative." else null
            },
            execute = { interactionController.tapCoordinates(x, y) }
        )

    open suspend fun typeText(spec: TargetResolutionSpec?, text: String): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "type_text",
            validate = { null },
            expectedEnteredText = text,
            execute = { before -> interactionController.typeText(spec, text, before) }
        )

    open suspend fun scrollScreen(direction: ScrollDirection, targetViewId: String? = null): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "scroll_screen",
            validate = { null },
            execute = { interactionController.scroll(direction, targetViewId) }
        )

    open suspend fun pressBack(): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "press_back",
            validate = { null },
            execute = { interactionController.pressBack() }
        )

    open suspend fun pressHome(): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "press_home",
            validate = { null },
            execute = { interactionController.pressHome() }
        )

    open suspend fun pressEnter(): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "press_enter",
            validate = { null },
            execute = { interactionController.pressEnter() }
        )

    private suspend fun runVerifiedInteraction(
        actionName: String,
        validate: () -> String?,
        expectedEnteredText: String? = null,
        execute: suspend (ScreenObservation) -> InteractionDispatchResult
    ): ComputerUseActionOutcome {
        // 1. Validate request
        val validationError = validate()
        if (validationError != null) {
            return ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = validationError,
                verificationDetail = "Validation failed before execution.",
                errorCode = "INVALID_ARGUMENT"
            )
        }

        // Check cancellation & permission gate
        currentCoroutineContext().ensureActive()
        val permError = checkAccessibilityGate(actionName)
        if (permError != null) return permError

        // 2. Inspect current screen state
        val before = screenObserver.observe()
        if (before.observationError != null && before.isDeviceLocked) {
            return ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = before.observationError,
                verificationDetail = "Device screen is locked.",
                beforePackage = before.activePackageName,
                errorCode = "DEVICE_LOCKED"
            )
        }

        // 3 & 4. Resolve target & execute interaction
        currentCoroutineContext().ensureActive()
        val dispatch = execute(before)
        if (!dispatch.executed) {
            val afterFail = screenObserver.observe()
            return ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = dispatch.detail,
                verificationDetail = "Interaction was not executed (${dispatch.errorCode ?: "FAILED"}).",
                beforePackage = before.activePackageName,
                afterPackage = afterFail.activePackageName,
                screenChanged = afterFail.hierarchySignature != before.hierarchySignature,
                postObservation = afterFail,
                errorCode = dispatch.errorCode ?: "ACTION_FAILED"
            )
        }

        // 5. Observe and verify resulting UI state
        currentCoroutineContext().ensureActive()
        val verification = actionVerifier.verifyUiAction(
            actionName = actionName,
            before = before,
            expectedEnteredText = expectedEnteredText
        )

        // 6. Return actual execution status
        val refreshedNote = if (dispatch.staleNodeRefreshed) " (refreshed stale node)" else ""
        return ComputerUseActionOutcome(
            success = verification.verified,
            action = actionName,
            summary = "${dispatch.detail}$refreshedNote",
            verificationDetail = verification.detail,
            beforePackage = before.activePackageName,
            afterPackage = verification.afterObservation.activePackageName,
            screenChanged = verification.screenChanged,
            postObservation = verification.afterObservation,
            errorCode = if (verification.verified) null else "VERIFICATION_INCONCLUSIVE"
        )
    }

    private fun checkAccessibilityGate(actionName: String): ComputerUseActionOutcome? {
        val perm = permissionManager.inspect()
        return when (perm.accessibilityState) {
            AccessibilityRuntimeState.SERVICE_ENABLED -> null
            AccessibilityRuntimeState.SERVICE_DISABLED,
            AccessibilityRuntimeState.PERMISSION_REQUIRED -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = "Nexa Accessibility Service is disabled. Enable it in Android Settings > Accessibility > Nexa Computer-Use Service before running '$actionName'.",
                verificationDetail = perm.accessibilityReason,
                errorCode = "ACCESSIBILITY_PERMISSION_REQUIRED",
                requiresAccessibilityPermission = true
            )
            AccessibilityRuntimeState.ENABLED_NOT_CONNECTED -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = "Nexa Accessibility Service is enabled in Settings, but Android has not bound it yet. Re-toggle the service or wait briefly.",
                verificationDetail = perm.accessibilityReason,
                errorCode = "ACCESSIBILITY_NOT_CONNECTED",
                requiresAccessibilityPermission = true
            )
            AccessibilityRuntimeState.DEVICE_LOCKED -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = "Device screen is locked. Unlock the phone before running '$actionName'.",
                verificationDetail = perm.accessibilityReason,
                errorCode = "DEVICE_LOCKED"
            )
            AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = perm.accessibilityReason,
                verificationDetail = perm.accessibilityReason,
                errorCode = "TEMPORARILY_UNAVAILABLE"
            )
            AccessibilityRuntimeState.UNSUPPORTED -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = perm.accessibilityReason,
                verificationDetail = perm.accessibilityReason,
                errorCode = "UNSUPPORTED"
            )
        }
    }

    fun formatOutcomeJson(outcome: ComputerUseActionOutcome): JSONObject {
        val obj = JSONObject()
            .put("success", outcome.success)
            .put("action", outcome.action)
            .put("summary", outcome.summary)
            .put("verificationDetail", outcome.verificationDetail)
            .put("screenChanged", outcome.screenChanged)
        outcome.beforePackage?.let { obj.put("beforePackage", it) }
        outcome.afterPackage?.let { obj.put("afterPackage", it) }
        outcome.errorCode?.let { obj.put("errorCode", it) }
        if (outcome.requiresAccessibilityPermission) {
            obj.put("permissionRequired", "ACCESSIBILITY_SERVICE")
            obj.put("howToEnable", "Open Android Settings -> Accessibility -> Nexa Computer-Use Service and turn it on.")
        }
        outcome.postObservation?.let { post ->
            obj.put("screen", screenObserver.toCompactJson(post))
        }
        return obj
    }
}
