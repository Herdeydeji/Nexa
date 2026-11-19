package com.example.android.runtime

import com.example.android.accessibility.AndroidScreenObserver
import com.example.domain.model.ScreenObservation
import com.example.domain.model.VerificationLevel
import com.example.domain.model.WindowDialogType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

enum class ForegroundBlockReason {
    NONE,
    APPLOCK,
    SYSTEM_DIALOG,
    LOADING,
    OTHER_APP,
    UNAVAILABLE
}

data class ForegroundVerificationResult(
    val verified: Boolean,
    val observedPackage: String?,
    val observation: ScreenObservation?,
    val blockReason: ForegroundBlockReason = if (verified) ForegroundBlockReason.NONE else ForegroundBlockReason.OTHER_APP,
    val detail: String = "",
    val verificationLevel: VerificationLevel = if (verified) {
        VerificationLevel.LEVEL_3_SUBGOAL_CONFIRMED
    } else {
        VerificationLevel.LEVEL_0_DISPATCHED
    }
)

data class PostActionVerificationResult(
    val verified: Boolean,
    val screenChanged: Boolean,
    val detail: String,
    val afterObservation: ScreenObservation,
    val verificationLevel: VerificationLevel = if (verified) {
        VerificationLevel.LEVEL_2_TARGET_STATE_MUTATED
    } else {
        VerificationLevel.LEVEL_0_DISPATCHED
    }
)

open class AndroidActionVerifier(
    private val screenObserver: AndroidScreenObserver,
    private val settleDelayMillis: Long = 350L
) {

    open suspend fun verifyForegroundPackage(
        expectedPackage: String,
        timeoutMillis: Long = 2_400L
    ): ForegroundVerificationResult {
        currentCoroutineContext().ensureActive()
        val initial = screenObserver.observe()
        if (initial.observationError != null && !initial.isRestrictedWindow && initial.activePackageName == null) {
            return ForegroundVerificationResult(
                verified = false,
                observedPackage = null,
                observation = initial,
                blockReason = ForegroundBlockReason.UNAVAILABLE,
                detail = initial.observationError ?: "Screen observation is unavailable.",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
        }

        val (matched, firstObs) = if (initial.activePackageName.equals(expectedPackage, ignoreCase = true)) {
            true to initial
        } else {
            screenObserver.waitForChange(
                timeoutMillis = timeoutMillis,
                expectedPackage = expectedPackage
            )
        }

        val immediatePkg = firstObs.activePackageName
        val immediateClassification = classifyWindow(immediatePkg, expectedPackage, firstObs)

        if (immediateClassification == ForegroundBlockReason.NONE && matched && settleDelayMillis > 0L) {
            delay(settleDelayMillis.coerceAtMost(600L))
            currentCoroutineContext().ensureActive()
            val settled = screenObserver.observe()
            val settledReason = classifyWindow(settled.activePackageName, expectedPackage, settled)
            return ForegroundVerificationResult(
                verified = settledReason == ForegroundBlockReason.NONE,
                observedPackage = settled.activePackageName,
                observation = settled,
                blockReason = settledReason,
                detail = buildDetail(expectedPackage, settled.activePackageName, settledReason, settled),
                verificationLevel = if (settledReason == ForegroundBlockReason.NONE) {
                    VerificationLevel.LEVEL_3_SUBGOAL_CONFIRMED
                } else {
                    VerificationLevel.LEVEL_1_SCREEN_CHANGED
                }
            )
        }

        if (immediateClassification == ForegroundBlockReason.LOADING && settleDelayMillis > 0L) {
            delay(settleDelayMillis.coerceAtLeast(300L).coerceAtMost(900L))
            currentCoroutineContext().ensureActive()
            val settled = screenObserver.observe()
            val settledReason = classifyWindow(settled.activePackageName, expectedPackage, settled)
            return ForegroundVerificationResult(
                verified = settledReason == ForegroundBlockReason.NONE,
                observedPackage = settled.activePackageName,
                observation = settled,
                blockReason = settledReason,
                detail = buildDetail(expectedPackage, settled.activePackageName, settledReason, settled),
                verificationLevel = if (settledReason == ForegroundBlockReason.NONE) {
                    VerificationLevel.LEVEL_3_SUBGOAL_CONFIRMED
                } else {
                    VerificationLevel.LEVEL_1_SCREEN_CHANGED
                }
            )
        }

        return ForegroundVerificationResult(
            verified = immediateClassification == ForegroundBlockReason.NONE,
            observedPackage = immediatePkg,
            observation = firstObs,
            blockReason = immediateClassification,
            detail = buildDetail(expectedPackage, immediatePkg, immediateClassification, firstObs),
            verificationLevel = if (immediateClassification == ForegroundBlockReason.NONE) {
                VerificationLevel.LEVEL_3_SUBGOAL_CONFIRMED
            } else {
                VerificationLevel.LEVEL_1_SCREEN_CHANGED
            }
        )
    }

    /**
     * Strict 5-level verification:
     * - LEVEL 0: Action attempted/dispatched only, zero observable mutation -> verified = false (except explicit read-only wait).
     * - LEVEL 1: Screen/package/OCR changed -> verified = true for generic navigation/taps.
     * - LEVEL 2: Explicit target mutation confirmed (typed text read-back, scroll displacement, checked state toggle, expected element visible).
     * - LEVEL 3/4: Elevated when playback controls/subgoal confirmation or audio session are verified by caller.
     */
    open suspend fun verifyUiAction(
        actionName: String,
        before: ScreenObservation,
        expectedEnteredText: String? = null,
        settleDelayMillis: Long = 220L,
        expectedNodeTextPresent: String? = null,
        requireStateMutation: Boolean = false
    ): PostActionVerificationResult {
        currentCoroutineContext().ensureActive()
        if (settleDelayMillis > 0L) {
            delay(settleDelayMillis)
        }
        val after = screenObserver.observe()
        val packageChanged = after.activePackageName != before.activePackageName
        val signatureChanged = after.hierarchySignature.isNotBlank() &&
            after.hierarchySignature != before.hierarchySignature
        val ocrChanged = after.ocrRegions.map { it.text } != before.ocrRegions.map { it.text } && after.ocrRegions.isNotEmpty()
        val focusChanged = after.nodes.firstOrNull { it.focused }?.let { n ->
            n.index to n.viewId
        } != before.nodes.firstOrNull { it.focused }?.let { n -> n.index to n.viewId }
        val checkedStateChanged = after.nodes.count { it.checked } != before.nodes.count { it.checked }
        val screenChanged = packageChanged || signatureChanged || ocrChanged

        if (after.observationError != null) {
            return PostActionVerificationResult(
                verified = false,
                screenChanged = screenChanged,
                detail = "Post-action screen observation reported: ${after.observationError}",
                afterObservation = after,
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
        }

        if (expectedEnteredText != null) {
            val cleanExpected = expectedEnteredText.trim()
            val normalizedExpected = cleanExpected.lowercase().replace(Regex("\\s+"), " ")
            val textObserved = cleanExpected.isEmpty() || after.nodes.any { node ->
                node.editable && (
                    node.isSensitiveRedacted ||
                        node.text?.contains(cleanExpected, ignoreCase = false) == true ||
                        node.text?.trim()?.lowercase()?.replace(Regex("\\s+"), " ")
                            ?.contains(normalizedExpected) == true
                    )
            }
            val level = when {
                textObserved -> VerificationLevel.LEVEL_2_TARGET_STATE_MUTATED
                screenChanged -> VerificationLevel.LEVEL_1_SCREEN_CHANGED
                else -> VerificationLevel.LEVEL_0_DISPATCHED
            }
            val detail = when {
                textObserved && after.nodes.any { it.isSensitiveRedacted && it.editable } ->
                    "Level 2 Verified: Sensitive text field updated (value redacted)."
                textObserved ->
                    "Level 2 Verified text entry in editable field on ${after.activePackageName ?: "active screen"}."
                screenChanged ->
                    "Level 1 Screen changed without confirming typed text in any editable field on ${after.activePackageName ?: "active screen"}."
                else ->
                    "Level 0 Only: Text action dispatched, but editable field did not reflect entered text."
            }
            return PostActionVerificationResult(
                verified = textObserved,
                screenChanged = screenChanged,
                detail = detail,
                afterObservation = after,
                verificationLevel = level
            )
        }

        if (!expectedNodeTextPresent.isNullOrBlank()) {
            val targetPresent = after.nodes.any { n ->
                listOfNotNull(n.text, n.contentDescription, n.hintText)
                    .any { it.contains(expectedNodeTextPresent, ignoreCase = true) }
            }
            val level = when {
                targetPresent -> VerificationLevel.LEVEL_2_TARGET_STATE_MUTATED
                screenChanged -> VerificationLevel.LEVEL_1_SCREEN_CHANGED
                else -> VerificationLevel.LEVEL_0_DISPATCHED
            }
            return PostActionVerificationResult(
                verified = targetPresent,
                screenChanged = screenChanged,
                detail = if (targetPresent) {
                    "Level 2 Verified: Expected target '$expectedNodeTextPresent' confirmed on screen."
                } else {
                    "Target '$expectedNodeTextPresent' not yet observed on screen."
                },
                afterObservation = after,
                verificationLevel = level
            )
        }

        // Scroll displacement verification
        if (actionName == "scroll_screen" || actionName == "swipe_screen") {
            val moved = screenChanged || boundsShifted(before, after)
            val level = if (moved) VerificationLevel.LEVEL_2_TARGET_STATE_MUTATED else VerificationLevel.LEVEL_0_DISPATCHED
            val detail = if (moved) {
                "Level 2 Verified: Viewport shifted after $actionName on '${after.activePackageName ?: "active screen"}'."
            } else {
                "Level 0 Only: $actionName dispatched, but visible bounds and content stayed identical (container may be at edge)."
            }
            return PostActionVerificationResult(
                verified = moved,
                screenChanged = moved,
                detail = detail,
                afterObservation = after,
                verificationLevel = level
            )
        }

        if (checkedStateChanged || focusChanged) {
            return PostActionVerificationResult(
                verified = true,
                screenChanged = true,
                detail = "Level 2 Verified: UI control checked/focus state updated on '${after.activePackageName ?: "active screen"}'.",
                afterObservation = after,
                verificationLevel = VerificationLevel.LEVEL_2_TARGET_STATE_MUTATED
            )
        }

        if (packageChanged || signatureChanged || ocrChanged) {
            val level = VerificationLevel.LEVEL_1_SCREEN_CHANGED
            val detail = when {
                packageChanged ->
                    "Level 1 Verified: Foreground changed from '${before.activePackageName ?: "unknown"}' to '${after.activePackageName ?: "unknown"}'."
                ocrChanged && !signatureChanged ->
                    "Level 1 Verified: Visual OCR content changed after $actionName."
                else ->
                    "Level 1 Verified: UI hierarchy updated on '${after.activePackageName ?: "active screen"}' (${after.nodes.size} nodes)."
            }
            return PostActionVerificationResult(
                verified = true,
                screenChanged = true,
                detail = detail,
                afterObservation = after,
                verificationLevel = level
            )
        }

        // No observable mutation occurred -> honestly classify as LEVEL 0 Unverified!
        return PostActionVerificationResult(
            verified = !requireStateMutation && false,
            screenChanged = false,
            detail = "Level 0 Attempted Only: $actionName executed, but screen hierarchy, focus, and package remained unchanged (${after.nodes.size} nodes).",
            afterObservation = after,
            verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
        )
    }

    private fun boundsShifted(before: ScreenObservation, after: ScreenObservation): Boolean {
        if (before.nodes.isEmpty() || after.nodes.isEmpty()) return false
        val beforeSample = before.nodes.take(10).map { "${it.text.orEmpty()}|${it.bounds.top}" }
        val afterSample = after.nodes.take(10).map { "${it.text.orEmpty()}|${it.bounds.top}" }
        return beforeSample != afterSample
    }

    internal fun classifyWindow(
        observedPackage: String?,
        expectedPackage: String,
        observation: ScreenObservation?
    ): ForegroundBlockReason {
        val pkg = observedPackage?.trim()?.lowercase().orEmpty()
        if (pkg == expectedPackage.trim().lowercase()) {
            return ForegroundBlockReason.NONE
        }
        if (pkg == TRANSSION_APPLOCK_PACKAGE || observation?.dialogType == WindowDialogType.APPLOCK) {
            return ForegroundBlockReason.APPLOCK
        }
        if (pkg in SYSTEM_DIALOG_PACKAGES ||
            observation?.dialogType == WindowDialogType.SYSTEM_PERMISSION ||
            observation?.windowType.equals("system", ignoreCase = true) ||
            observation?.nodes?.any {
                it.viewId?.contains("alertTitle", ignoreCase = true) == true ||
                    it.viewId?.contains("permission_", ignoreCase = true) == true
            } == true
        ) {
            return ForegroundBlockReason.SYSTEM_DIALOG
        }
        if (pkg.isEmpty() || LAUNCHER_OR_LOADING_PACKAGES.any { pkg.startsWith(it) }) {
            return ForegroundBlockReason.LOADING
        }
        return ForegroundBlockReason.OTHER_APP
    }

    private fun buildDetail(
        expectedPackage: String,
        observedPackage: String?,
        reason: ForegroundBlockReason,
        obs: ScreenObservation?
    ): String {
        val title = obs?.windowTitle?.takeIf { it.isNotBlank() }?.let { " ('$it')" }.orEmpty()
        return when (reason) {
            ForegroundBlockReason.NONE ->
                "Verified foreground package '$observedPackage'$title matches target '$expectedPackage'."
            ForegroundBlockReason.APPLOCK ->
                "The target application ($expectedPackage) is protected by Transsion AppLock ($observedPackage)$title."
            ForegroundBlockReason.SYSTEM_DIALOG ->
                "A system or permission dialog ($observedPackage)$title is currently in front of '$expectedPackage'."
            ForegroundBlockReason.LOADING ->
                "Target '$expectedPackage' is still loading; foreground observed is '${observedPackage ?: "unknown"}'."
            ForegroundBlockReason.OTHER_APP ->
                "Foreground window is '${observedPackage ?: "unknown"}'$title instead of requested target '$expectedPackage'."
            ForegroundBlockReason.UNAVAILABLE ->
                "Foreground verification unavailable."
        }
    }

    companion object {
        const val TRANSSION_APPLOCK_PACKAGE = "com.transsion.applock"

        private val SYSTEM_DIALOG_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "android"
        )

        private val LAUNCHER_OR_LOADING_PACKAGES = listOf(
            "com.android.launcher",
            "com.google.android.apps.nexuslauncher",
            "com.transsion.hios",
            "com.transsion.xos",
            "com.android.systemui"
        )
    }
}
