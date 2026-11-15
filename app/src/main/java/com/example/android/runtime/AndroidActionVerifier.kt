package com.example.android.runtime

import com.example.android.accessibility.AndroidScreenObserver
import com.example.domain.model.ScreenObservation
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
    val detail: String = ""
)

data class PostActionVerificationResult(
    val verified: Boolean,
    val screenChanged: Boolean,
    val detail: String,
    val afterObservation: ScreenObservation
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
                detail = initial.observationError ?: "Screen observation is unavailable."
            )
        }

        // First bounded check / wait for target or blocking window
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

        // If target appeared, do a short bounded settle re-check when configured so AppLock intercepts are caught cleanly
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
                detail = buildDetail(expectedPackage, settled.activePackageName, settledReason, settled)
            )
        }

        // If still launcher/loading on first pass, wait a bounded settle period and re-observe once more
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
                detail = buildDetail(expectedPackage, settled.activePackageName, settledReason, settled)
            )
        }

        return ForegroundVerificationResult(
            verified = immediateClassification == ForegroundBlockReason.NONE,
            observedPackage = immediatePkg,
            observation = firstObs,
            blockReason = immediateClassification,
            detail = buildDetail(expectedPackage, immediatePkg, immediateClassification, firstObs)
        )
    }

    open suspend fun verifyUiAction(
        actionName: String,
        before: ScreenObservation,
        expectedEnteredText: String? = null,
        settleDelayMillis: Long = 220L
    ): PostActionVerificationResult {
        currentCoroutineContext().ensureActive()
        if (settleDelayMillis > 0L) {
            delay(settleDelayMillis)
        }
        val after = screenObserver.observe()
        val packageChanged = after.activePackageName != before.activePackageName
        val signatureChanged = after.hierarchySignature.isNotBlank() &&
            after.hierarchySignature != before.hierarchySignature
        val screenChanged = packageChanged || signatureChanged

        if (after.observationError != null) {
            return PostActionVerificationResult(
                verified = false,
                screenChanged = screenChanged,
                detail = "Post-action screen observation reported: ${after.observationError}",
                afterObservation = after
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
            val detail = when {
                textObserved && after.nodes.any { it.isSensitiveRedacted && it.editable } ->
                    "Verified sensitive text input updated (value redacted in observation)."
                textObserved ->
                    "Verified text entry is present in editable field on ${after.activePackageName ?: "active screen"}."
                screenChanged ->
                    "Screen changed without confirming typed text in any editable field on ${after.activePackageName ?: "active screen"}."
                else ->
                    "Action submitted, but editable field did not reflect the entered text in post-action observation."
            }
            return PostActionVerificationResult(
                verified = textObserved,
                screenChanged = screenChanged,
                detail = detail,
                afterObservation = after
            )
        }

        val detail = when {
            packageChanged ->
                "Active app changed from '${before.activePackageName ?: "unknown"}' to '${after.activePackageName ?: "unknown"}'."
            signatureChanged ->
                "Verified UI hierarchy updated on '${after.activePackageName ?: "active screen"}' (${after.nodes.size} nodes)."
            else ->
                "Completed $actionName; screen hierarchy remained visually unchanged (${after.nodes.size} nodes)."
        }

        return PostActionVerificationResult(
            verified = true,
            screenChanged = screenChanged,
            detail = detail,
            afterObservation = after
        )
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
        if (pkg == TRANSSION_APPLOCK_PACKAGE) {
            return ForegroundBlockReason.APPLOCK
        }
        if (pkg in SYSTEM_DIALOG_PACKAGES ||
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
