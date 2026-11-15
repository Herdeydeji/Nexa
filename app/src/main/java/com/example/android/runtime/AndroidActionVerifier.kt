package com.example.android.runtime

import com.example.android.accessibility.AndroidScreenObserver
import com.example.domain.model.ScreenObservation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

data class ForegroundVerificationResult(
    val verified: Boolean,
    val observedPackage: String?,
    val observation: ScreenObservation?
)

data class PostActionVerificationResult(
    val verified: Boolean,
    val screenChanged: Boolean,
    val detail: String,
    val afterObservation: ScreenObservation
)

open class AndroidActionVerifier(
    private val screenObserver: AndroidScreenObserver
) {

    open suspend fun verifyForegroundPackage(
        expectedPackage: String,
        timeoutMillis: Long = 2_400L
    ): ForegroundVerificationResult {
        currentCoroutineContext().ensureActive()
        val (matched, obs) = screenObserver.waitForChange(
            timeoutMillis = timeoutMillis,
            expectedPackage = expectedPackage
        )
        val isMatch = obs.activePackageName.equals(expectedPackage, ignoreCase = true)
        return ForegroundVerificationResult(
            verified = isMatch || matched && obs.activePackageName.equals(expectedPackage, ignoreCase = true),
            observedPackage = obs.activePackageName,
            observation = obs
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
            val textObserved = cleanExpected.isEmpty() || after.nodes.any { node ->
                node.editable && (
                    node.isSensitiveRedacted ||
                        node.text?.contains(cleanExpected, ignoreCase = false) == true
                    )
            }
            val detail = when {
                textObserved && after.nodes.any { it.isSensitiveRedacted && it.editable } ->
                    "Verified sensitive text input updated (value redacted in observation)."
                textObserved ->
                    "Verified text entry is present in editable field on ${after.activePackageName ?: "active screen"}."
                screenChanged ->
                    "Screen state changed after text entry, though literal text was not echoed in visible editable nodes."
                else ->
                    "Action submitted, but editable field did not show updated text in post-action observation."
            }
            return PostActionVerificationResult(
                verified = textObserved || screenChanged,
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
}
