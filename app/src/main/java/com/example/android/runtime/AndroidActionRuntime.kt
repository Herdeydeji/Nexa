package com.example.android.runtime

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import com.example.android.accessibility.AccessibilityInteractionController
import com.example.android.accessibility.AndroidScreenObserver
import com.example.android.accessibility.GroundingOutcome
import com.example.android.accessibility.InteractionDispatchResult
import com.example.android.accessibility.UiElementGroundingEngine
import com.example.android.app.AndroidAppDiscovery
import com.example.android.app.AndroidAppLauncher
import com.example.android.app.AppDiscoveryBatch
import com.example.android.media.ScreenshotCaptureBackend
import com.example.domain.model.AccessibilityRuntimeState
import com.example.domain.model.AppLaunchOutcome
import com.example.domain.model.AppLaunchStatus
import com.example.domain.model.AppLookupOutcome
import com.example.domain.model.AttachmentPayload
import com.example.domain.model.ComputerUseActionOutcome
import com.example.domain.model.ComputerUseFailureCategory
import com.example.domain.model.ObservedUiNode
import com.example.domain.model.ScreenObservation
import com.example.domain.model.ScreenshotCaptureOutcome
import com.example.domain.model.ScrollDirection
import com.example.domain.model.TargetResolutionSpec
import com.example.domain.model.TextEditMode
import com.example.domain.model.VerificationLevel
import com.example.domain.model.YouTubePlayOutcome
import com.example.domain.model.YouTubePlayStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

open class AndroidActionRuntime(
    val permissionManager: AndroidPermissionManager,
    val appDiscovery: AndroidAppDiscovery,
    val appLauncher: AndroidAppLauncher,
    val screenObserver: AndroidScreenObserver,
    val interactionController: AccessibilityInteractionController,
    val actionVerifier: AndroidActionVerifier,
    val screenshotBackend: ScreenshotCaptureBackend? = null,
    private val context: Context? = null,
    private val externalIntentStarter: ((Intent) -> Unit)? = null,
    private val audioActiveChecker: (() -> Boolean)? = null
) {

    open fun isAutomationActive(): Boolean = permissionManager.isAccessibilityReady()

    fun consumeLatestSparseVisualAttachment(expectedSnapshotId: Long? = null): AttachmentPayload? =
        screenObserver.consumeLatestSparseVisualAttachment(expectedSnapshotId)

    open fun listInstalledApps(
        query: String? = null,
        includeSystemApps: Boolean = true,
        limit: Int = AndroidAppDiscovery.DEFAULT_LIST_LIMIT,
        includeNonLaunchable: Boolean = false,
        offset: Int = 0
    ): AppDiscoveryBatch = appDiscovery.listInstalledApps(
        query = query,
        includeSystemApps = includeSystemApps,
        includeNonLaunchable = includeNonLaunchable,
        limit = limit,
        offset = offset
    )

    open fun findApp(query: String): AppLookupOutcome = appDiscovery.findApp(query)

    open suspend fun launchApp(appQuery: String): AppLaunchOutcome {
        currentCoroutineContext().ensureActive()
        return appLauncher.launch(appQuery)
    }

    open suspend fun openUrl(url: String): ComputerUseActionOutcome {
        currentCoroutineContext().ensureActive()
        val clean = url.trim()
        val parsed = runCatching { Uri.parse(clean) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase()
        if (parsed == null || (scheme != "http" && scheme != "https") || parsed.host.isNullOrBlank()) {
            return ComputerUseActionOutcome(
                success = false,
                action = "open_url",
                summary = "Invalid URL '$url'. Provide a complete http:// or https:// URL.",
                verificationDetail = "URL validation failed before dispatch.",
                errorCode = "INVALID_URL",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
        }

        val starter = resolveIntentStarter()
            ?: return ComputerUseActionOutcome(
                success = false,
                action = "open_url",
                summary = "No Android Context or intent starter available to open URL.",
                verificationDetail = "Missing Android Context.",
                errorCode = "UNSUPPORTED",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )

        val before = if (permissionManager.isAccessibilityReady()) screenObserver.observe() else null
        val intent = Intent(Intent.ACTION_VIEW, parsed).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val dispatchError = try {
            starter(intent)
            null
        } catch (e: ActivityNotFoundException) {
            "No installed browser or app can handle '$clean': ${e.message}"
        } catch (e: SecurityException) {
            "Android blocked opening URL '$clean': ${e.message}"
        } catch (t: Throwable) {
            "Failed to open URL '$clean': ${t.message}"
        }

        if (dispatchError != null) {
            return ComputerUseActionOutcome(
                success = false,
                action = "open_url",
                summary = dispatchError,
                verificationDetail = "ACTION_VIEW dispatch failed.",
                beforePackage = before?.activePackageName,
                errorCode = "OPEN_URL_FAILED",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
        }

        if (!permissionManager.isAccessibilityReady()) {
            return ComputerUseActionOutcome(
                success = true,
                action = "open_url",
                summary = "Dispatched Android browser intent for $clean (foreground verification unavailable without Accessibility).",
                verificationDetail = "Intent dispatched; Accessibility service is disabled.",
                beforePackage = before?.activePackageName,
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
        }

        val (changed, after) = screenObserver.waitForChange(timeoutMillis = 2_400L)
        if (after.activePackageName.equals(AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE, ignoreCase = true)) {
            return ComputerUseActionOutcome(
                success = false,
                action = "open_url",
                summary = "Opening $clean was blocked by Transsion AppLock (${after.activePackageName}).",
                verificationDetail = "AppLock intercepted the target app.",
                beforePackage = before?.activePackageName,
                afterPackage = after.activePackageName,
                screenChanged = changed,
                postObservation = after,
                errorCode = "BLOCKED_BY_APPLOCK",
                verificationLevel = VerificationLevel.LEVEL_1_SCREEN_CHANGED,
                failureCategory = ComputerUseFailureCategory.APPLOCK_OR_AUTH_REQUIRED
            )
        }

        return ComputerUseActionOutcome(
            success = true,
            action = "open_url",
            summary = "Opened $clean in ${after.activePackageName ?: "active app"}.",
            verificationDetail = "Post-open foreground window: ${after.activePackageName ?: "unknown"}.",
            beforePackage = before?.activePackageName,
            afterPackage = after.activePackageName,
            screenChanged = changed,
            postObservation = after,
            verificationLevel = if (changed) {
                VerificationLevel.LEVEL_3_SUBGOAL_CONFIRMED
            } else {
                VerificationLevel.LEVEL_1_SCREEN_CHANGED
            }
        )
    }

    open suspend fun playOnYouTube(query: String): YouTubePlayOutcome {
        currentCoroutineContext().ensureActive()
        val cleanQuery = query.trim().replace(Regex("\\s+"), " ")
        if (cleanQuery.isEmpty()) {
            return YouTubePlayOutcome(
                status = YouTubePlayStatus.FAILED,
                query = query,
                searchUrl = "",
                message = "Provide a non-empty search query for YouTube."
            )
        }

        val encoded = URLEncoder.encode(cleanQuery, StandardCharsets.UTF_8.name())
        val searchUrl = "https://www.youtube.com/results?search_query=$encoded"
        val searchUri = Uri.parse(searchUrl)

        val lookup = appDiscovery.findApp("YouTube")
        val targetApp = (lookup as? AppLookupOutcome.Found)?.app
            ?.takeIf { appDiscovery.canPackageHandleViewUri(it.packageName, searchUri) }

        val starter = resolveIntentStarter()
            ?: return YouTubePlayOutcome(
                status = YouTubePlayStatus.FAILED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = targetApp?.packageName,
                message = "No Android Context or intent starter available to open YouTube."
            )

        val viewIntent = Intent(Intent.ACTION_VIEW, searchUri).apply {
            if (targetApp != null) {
                setPackage(targetApp.packageName)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val launchError = try {
            starter(viewIntent)
            null
        } catch (e: ActivityNotFoundException) {
            "No YouTube or browser activity could handle $searchUrl: ${e.message}"
        } catch (e: SecurityException) {
            "Android blocked opening YouTube search: ${e.message}"
        } catch (t: Throwable) {
            "Failed to dispatch YouTube search intent: ${t.message}"
        }

        if (launchError != null) {
            return YouTubePlayOutcome(
                status = YouTubePlayStatus.FAILED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = targetApp?.packageName,
                message = launchError
            )
        }

        if (!permissionManager.isAccessibilityReady()) {
            return YouTubePlayOutcome(
                status = YouTubePlayStatus.SEARCH_OPENED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = targetApp?.packageName,
                message = "Opened YouTube search for '$cleanQuery'. Playback is unverified because Nexa Accessibility Service is disabled, so tap a video result or enable Accessibility."
            )
        }

        val targetPkg = targetApp?.packageName
        val verification = if (targetPkg != null) {
            actionVerifier.verifyForegroundPackage(targetPkg)
        } else {
            val (_, obs) = screenObserver.waitForChange(timeoutMillis = 2_400L)
            val reason = actionVerifier.classifyWindow(obs.activePackageName, obs.activePackageName.orEmpty(), obs)
            ForegroundVerificationResult(
                verified = obs.activePackageName != null &&
                    !obs.activePackageName.equals(AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE, ignoreCase = true),
                observedPackage = obs.activePackageName,
                observation = obs,
                blockReason = if (obs.activePackageName.equals(AndroidActionVerifier.TRANSSION_APPLOCK_PACKAGE, ignoreCase = true)) {
                    ForegroundBlockReason.APPLOCK
                } else {
                    reason
                }
            )
        }

        if (verification.blockReason == ForegroundBlockReason.APPLOCK) {
            val appName = targetApp?.appName ?: "YouTube"
            val blockedOutcome = AppLaunchOutcome(
                success = false,
                appName = appName,
                packageName = targetPkg ?: "com.google.android.youtube",
                launchIntentDispatched = true,
                foregroundVerified = false,
                launchStatus = AppLaunchStatus.BLOCKED_BY_APPLOCK,
                observedForegroundPackage = verification.observedPackage,
                message = "The target application ($appName) is protected by Transsion AppLock.",
                errorCode = "BLOCKED_BY_APPLOCK",
                requiresUserAction = true,
                recoveryAction = "WAIT_FOR_USER_UNLOCK",
                blockerReason = "The target application is protected by Transsion AppLock.",
                userTitle = "Unlock $appName",
                userMessage = "Your device's AppLock is protecting this application. Unlock it on your phone. Nexa will continue automatically when access is available."
            )
            return YouTubePlayOutcome(
                status = YouTubePlayStatus.BLOCKED_BY_APPLOCK,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = blockedOutcome.packageName,
                observedForegroundPackage = verification.observedPackage,
                message = blockedOutcome.userMessage ?: blockedOutcome.message,
                launchOutcome = blockedOutcome,
                postObservation = verification.observation
            )
        }

        if (targetPkg != null && !verification.verified) {
            return YouTubePlayOutcome(
                status = YouTubePlayStatus.SEARCH_OPENED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = targetPkg,
                observedForegroundPackage = verification.observedPackage,
                message = "Dispatched YouTube search for '$cleanQuery', but foreground remained '${verification.observedPackage ?: "unknown"}'. Playback has not started.",
                postObservation = verification.observation
            )
        }

        val resultsObs = waitForYouTubeResults(verification.observation)
        val candidate = selectBestYouTubeResultCandidate(resultsObs, cleanQuery)
        if (candidate == null) {
            return YouTubePlayOutcome(
                status = YouTubePlayStatus.SEARCH_OPENED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = resultsObs.activePackageName ?: targetPkg,
                observedForegroundPackage = resultsObs.activePackageName,
                message = "Opened YouTube search for '$cleanQuery' (${resultsObs.nodes.size} UI elements observed), but no grounded video result could be matched yet.",
                postObservation = resultsObs
            )
        }

        val tapResult = interactionController.tapElement(
            TargetResolutionSpec(nodeIndex = candidate.index),
            resultsObs
        )
        if (!tapResult.executed) {
            return YouTubePlayOutcome(
                status = YouTubePlayStatus.RESULTS_OBSERVED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = resultsObs.activePackageName ?: targetPkg,
                observedForegroundPackage = resultsObs.activePackageName,
                selectedTitle = candidate.text ?: candidate.contentDescription,
                message = "Observed YouTube results for '$cleanQuery', but tapping '${candidate.text ?: candidate.contentDescription}' failed: ${tapResult.detail}",
                postObservation = resultsObs
            )
        }

        val playerVerify = actionVerifier.verifyUiAction(
            actionName = "play_on_youtube",
            before = resultsObs,
            settleDelayMillis = 450L
        )
        val playerObs = playerVerify.afterObservation
        val playbackSignal = detectPlaybackSignal(playerObs)
        val audioPlaying = isDeviceAudioActive()

        return if (playbackSignal != null || audioPlaying) {
            val signalDesc = listOfNotNull(
                playbackSignal,
                if (audioPlaying) "device audio active" else null
            ).joinToString(" & ")
            YouTubePlayOutcome(
                status = YouTubePlayStatus.PLAYBACK_STARTED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = playerObs.activePackageName ?: targetPkg,
                observedForegroundPackage = playerObs.activePackageName,
                selectedTitle = candidate.text ?: candidate.contentDescription,
                playbackSignal = signalDesc,
                message = "Selected '${candidate.text ?: candidate.contentDescription}' on YouTube and verified playback ($signalDesc).",
                postObservation = playerObs
            )
        } else {
            YouTubePlayOutcome(
                status = YouTubePlayStatus.PLAYBACK_UNVERIFIED,
                query = cleanQuery,
                searchUrl = searchUrl,
                targetPackage = playerObs.activePackageName ?: targetPkg,
                observedForegroundPackage = playerObs.activePackageName,
                selectedTitle = candidate.text ?: candidate.contentDescription,
                message = "Tapped '${candidate.text ?: candidate.contentDescription}' on YouTube, but playback controls or active audio were not confirmed yet.",
                postObservation = playerObs
            )
        }
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
            errorCode = if (obs.observationError != null) "OBSERVATION_ERROR" else null,
            verificationLevel = if (changed) {
                VerificationLevel.LEVEL_1_SCREEN_CHANGED
            } else {
                VerificationLevel.LEVEL_0_DISPATCHED
            }
        )
    }

    open suspend fun waitForElement(
        spec: TargetResolutionSpec,
        timeoutMillis: Long = 4_000L
    ): ComputerUseActionOutcome {
        currentCoroutineContext().ensureActive()
        val permError = checkAccessibilityGate("wait_for_element")
        if (permError != null) return permError

        val bounded = timeoutMillis.coerceIn(300L, 10_000L)
        val deadline = System.currentTimeMillis() + bounded
        var lastObs = screenObserver.observe()

        while (true) {
            currentCoroutineContext().ensureActive()
            when (val res = UiElementGroundingEngine.resolve(lastObs, spec, spec.editableOnly)) {
                is GroundingOutcome.Resolved -> {
                    return ComputerUseActionOutcome(
                        success = true,
                        action = "wait_for_element",
                        summary = "Element '${UiElementGroundingEngine.describeCandidate(res.node)}' is visible on screen.",
                        verificationDetail = res.explanation.rationale,
                        afterPackage = lastObs.activePackageName,
                        screenChanged = true,
                        postObservation = lastObs,
                        verificationLevel = VerificationLevel.LEVEL_2_TARGET_STATE_MUTATED,
                        groundingExplanation = res.explanation
                    )
                }
                else -> Unit
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) break
            val (_, nextObs) = screenObserver.waitForChange(minOf(remaining, 700L))
            lastObs = nextObs
            if (remaining <= 200L) delay(100L)
        }

        val diagnosis = FailureDiagnosisEngine.diagnose(
            actionName = "wait_for_element",
            errorCode = "TARGET_NOT_FOUND",
            detail = "Target element did not appear within ${bounded}ms.",
            before = null,
            after = lastObs,
            targetSpec = spec
        )
        return ComputerUseActionOutcome(
            success = false,
            action = "wait_for_element",
            summary = "Element did not appear within ${bounded}ms.",
            verificationDetail = diagnosis?.summary ?: "Element not found.",
            afterPackage = lastObs.activePackageName,
            postObservation = lastObs,
            errorCode = "TARGET_NOT_FOUND",
            verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED,
            failureCategory = diagnosis?.category,
            suggestedRecovery = diagnosis?.recoverySuggestion
        )
    }

    open suspend fun tapElement(spec: TargetResolutionSpec): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "tap_element",
            targetSpec = spec,
            validate = {
                if (spec.nodeIndex == null && spec.viewId.isNullOrBlank() &&
                    spec.text.isNullOrBlank() && spec.contentDescription.isNullOrBlank() &&
                    spec.anchorContext.isNullOrBlank()
                ) {
                    "Provide at least one target selector: node_index, view_id, text, content_description, or anchor_context."
                } else {
                    null
                }
            },
            execute = { before -> interactionController.tapElement(spec, before) }
        )

    open suspend fun doubleTapElement(spec: TargetResolutionSpec): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "double_tap_element",
            targetSpec = spec,
            validate = {
                if (spec.nodeIndex == null && spec.viewId.isNullOrBlank() &&
                    spec.text.isNullOrBlank() && spec.contentDescription.isNullOrBlank()
                ) {
                    "Provide at least one target selector: node_index, view_id, text, or content_description."
                } else {
                    null
                }
            },
            execute = { before -> interactionController.doubleTapElement(spec, before) }
        )

    open suspend fun longPressElement(spec: TargetResolutionSpec): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "long_press_element",
            targetSpec = spec,
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

    open suspend fun swipeScreen(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 320L
    ): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "swipe_screen",
            validate = {
                if (startX < 0f || startY < 0f || endX < 0f || endY < 0f) {
                    "Swipe coordinates must be non-negative."
                } else {
                    null
                }
            },
            execute = { interactionController.swipe(startX, startY, endX, endY, durationMs) }
        )

    open suspend fun dragAndDrop(
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 750L
    ): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = "drag_and_drop",
            validate = {
                if (startX < 0f || startY < 0f || endX < 0f || endY < 0f) {
                    "Drag coordinates must be non-negative."
                } else {
                    null
                }
            },
            execute = { interactionController.dragAndDrop(startX, startY, endX, endY, durationMs) }
        )

    open suspend fun typeText(spec: TargetResolutionSpec?, text: String): ComputerUseActionOutcome =
        editTextField(spec, TextEditMode.REPLACE, text)

    open suspend fun editTextField(
        spec: TargetResolutionSpec?,
        mode: TextEditMode,
        text: String?
    ): ComputerUseActionOutcome =
        runVerifiedInteraction(
            actionName = if (mode == TextEditMode.REPLACE) "type_text" else "edit_text_field",
            targetSpec = spec,
            validate = {
                if ((mode == TextEditMode.REPLACE || mode == TextEditMode.APPEND) && text == null) {
                    "Parameter 'text' is required for mode '${mode.apiValue}'."
                } else {
                    null
                }
            },
            expectedEnteredText = when (mode) {
                TextEditMode.REPLACE, TextEditMode.APPEND, TextEditMode.PASTE -> text
                TextEditMode.CLEAR -> ""
                TextEditMode.SELECT_ALL -> null
            },
            execute = { before -> interactionController.editTextField(spec, mode, text, before) }
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

    private suspend fun waitForYouTubeResults(initial: ScreenObservation?): ScreenObservation {
        val startObs = initial ?: screenObserver.observe()
        if (selectBestYouTubeResultCandidate(startObs, "") != null || startObs.nodes.size >= 4) {
            return startObs
        }
        val (_, updated) = screenObserver.waitForChange(timeoutMillis = 2_000L)
        return updated
    }

    internal fun selectBestYouTubeResultCandidate(
        obs: ScreenObservation,
        query: String
    ): ObservedUiNode? {
        val tokens = query.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 }

        val chromeWords = setOf(
            "home", "shorts", "subscriptions", "library", "you", "search",
            "filters", "voice search", "navigate up", "more options", "cast",
            "sponsored", "ad", "install", "open app", "sign in"
        )
        val recentHints = listOf("hour ago", "hours ago", "day ago", "days ago", "week ago", "weeks ago", "month ago", "months ago", "just now", "2026", "2025")

        return obs.nodes
            .filter { node ->
                node.enabled &&
                    !node.editable &&
                    (node.clickable || !node.viewId.isNullOrBlank()) &&
                    (!node.text.isNullOrBlank() || !node.contentDescription.isNullOrBlank())
            }
            .mapNotNull { node ->
                val label = listOfNotNull(node.text, node.contentDescription).joinToString(" ").trim()
                val lower = label.lowercase()
                if (lower.isBlank() || chromeWords.any { lower == it || lower.startsWith("$it ") && lower.length < 18 }) {
                    return@mapNotNull null
                }
                if (lower.contains("sponsored") || node.viewId?.contains("ad_", ignoreCase = true) == true) {
                    return@mapNotNull null
                }
                var score = 0
                if (node.clickable) score += 10
                if (node.viewId?.contains("video", ignoreCase = true) == true ||
                    node.viewId?.contains("result", ignoreCase = true) == true ||
                    node.viewId?.contains("title", ignoreCase = true) == true
                ) {
                    score += 20
                }
                if (tokens.isNotEmpty()) {
                    val matchedTokens = tokens.count { lower.contains(it) }
                    score += matchedTokens * 25
                    if (matchedTokens == 0 && query.isNotBlank()) score -= 5
                }
                if (recentHints.any { lower.contains(it) }) {
                    score += 12
                }
                if (Regex("\\d+:\\d{2}").containsMatchIn(lower) || lower.contains("views")) {
                    score += 10
                }
                if (score > 0) score to node else null
            }
            .maxByOrNull { it.first }
            ?.second
    }

    internal fun detectPlaybackSignal(obs: ScreenObservation): String? {
        obs.nodes.forEach { node ->
            val combined = listOfNotNull(node.viewId, node.text, node.contentDescription)
                .joinToString(" ")
                .lowercase()
            when {
                combined.contains("pause video") || combined == "pause" || combined.contains("pause ") ->
                    return "player pause control visible"
                combined.contains("player_view") || combined.contains("watch_player") || combined.contains("youtube_player") ->
                    return "video player surface active"
                combined.contains("time_bar") || combined.contains("seek") || combined.contains("scrubber") ->
                    return "video timeline scrubber active"
                node.viewId?.contains("miniplayer", ignoreCase = true) == true ->
                    return "YouTube miniplayer active"
            }
        }
        return null
    }

    private fun isDeviceAudioActive(): Boolean {
        audioActiveChecker?.let { return runCatching { it() }.getOrDefault(false) }
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching { am.isMusicActive }.getOrDefault(false)
    }

    private fun resolveIntentStarter(): ((Intent) -> Unit)? {
        externalIntentStarter?.let { return it }
        val ctx = context ?: return null
        return { intent -> ctx.startActivity(intent) }
    }

    private suspend fun runVerifiedInteraction(
        actionName: String,
        targetSpec: TargetResolutionSpec? = null,
        validate: () -> String?,
        expectedEnteredText: String? = null,
        execute: suspend (ScreenObservation) -> InteractionDispatchResult
    ): ComputerUseActionOutcome {
        val validationError = validate()
        if (validationError != null) {
            return ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = validationError,
                verificationDetail = "Validation failed before execution.",
                errorCode = "INVALID_ARGUMENT",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
        }

        currentCoroutineContext().ensureActive()
        val permError = checkAccessibilityGate(actionName)
        if (permError != null) return permError

        var before = screenObserver.observe()
        if (before.observationError != null && before.isDeviceLocked) {
            return ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = before.observationError,
                verificationDetail = "Device screen is locked.",
                beforePackage = before.activePackageName,
                errorCode = "DEVICE_LOCKED",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED,
                failureCategory = ComputerUseFailureCategory.APPLOCK_OR_AUTH_REQUIRED
            )
        }

        currentCoroutineContext().ensureActive()
        var dispatch = execute(before)

        // Confirmed Phase 1 rule: on ambiguous/low-confidence UI match, re-inspect fresh screen context once before asking user.
        if (!dispatch.executed && (dispatch.errorCode == "AMBIGUOUS_TARGET" || dispatch.errorCode == "LOW_CONFIDENCE_MATCH")) {
            delay(180L)
            currentCoroutineContext().ensureActive()
            val reinspected = screenObserver.observe()
            val retryDispatch = execute(reinspected)
            if (retryDispatch.executed) {
                before = reinspected
                dispatch = retryDispatch.copy(staleNodeRefreshed = true)
            } else {
                dispatch = retryDispatch.copy(staleNodeRefreshed = true)
            }
        }

        if (!dispatch.executed) {
            val afterFail = screenObserver.observe()
            val effectiveCode = if (dispatch.errorCode == "AMBIGUOUS_TARGET" || dispatch.errorCode == "LOW_CONFIDENCE_MATCH") {
                "UI_AMBIGUOUS"
            } else {
                dispatch.errorCode ?: "ACTION_FAILED"
            }
            val diagnosis = FailureDiagnosisEngine.diagnose(
                actionName = actionName,
                errorCode = effectiveCode,
                detail = dispatch.detail,
                before = before,
                after = afterFail,
                targetSpec = targetSpec,
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
            return ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = dispatch.detail,
                verificationDetail = diagnosis?.summary ?: "Interaction was not executed ($effectiveCode).",
                beforePackage = before.activePackageName,
                afterPackage = afterFail.activePackageName,
                screenChanged = afterFail.hierarchySignature != before.hierarchySignature,
                postObservation = afterFail,
                errorCode = effectiveCode,
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED,
                failureCategory = diagnosis?.category,
                groundingExplanation = dispatch.explanation,
                suggestedRecovery = diagnosis?.recoverySuggestion,
                ambiguousCandidates = dispatch.ambiguousCandidates
            )
        }

        currentCoroutineContext().ensureActive()
        val verification = actionVerifier.verifyUiAction(
            actionName = actionName,
            before = before,
            expectedEnteredText = expectedEnteredText
        )

        val refreshedNote = if (dispatch.staleNodeRefreshed) " (re-inspected context)" else ""
        val failureErrorCode = when {
            verification.verified -> null
            expectedEnteredText != null -> "VERIFICATION_INCONCLUSIVE"
            else -> "NO_UI_EFFECT"
        }
        val diagnosis = if (!verification.verified) {
            FailureDiagnosisEngine.diagnose(
                actionName = actionName,
                errorCode = failureErrorCode,
                detail = verification.detail,
                before = before,
                after = verification.afterObservation,
                targetSpec = targetSpec,
                verificationLevel = verification.verificationLevel
            )
        } else {
            null
        }
        return ComputerUseActionOutcome(
            success = verification.verified,
            action = actionName,
            summary = "${dispatch.detail}$refreshedNote",
            verificationDetail = verification.detail,
            beforePackage = before.activePackageName,
            afterPackage = verification.afterObservation.activePackageName,
            screenChanged = verification.screenChanged,
            postObservation = verification.afterObservation,
            errorCode = failureErrorCode,
            verificationLevel = verification.verificationLevel,
            failureCategory = diagnosis?.category,
            groundingExplanation = dispatch.explanation,
            suggestedRecovery = diagnosis?.recoverySuggestion
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
                requiresAccessibilityPermission = true,
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED,
                failureCategory = ComputerUseFailureCategory.MISSING_PERMISSION
            )
            AccessibilityRuntimeState.ENABLED_NOT_CONNECTED -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = "Nexa Accessibility Service is enabled in Settings, but Android has not bound it yet. Re-toggle the service or wait briefly.",
                verificationDetail = perm.accessibilityReason,
                errorCode = "ACCESSIBILITY_NOT_CONNECTED",
                requiresAccessibilityPermission = true,
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED,
                failureCategory = ComputerUseFailureCategory.MISSING_PERMISSION
            )
            AccessibilityRuntimeState.DEVICE_LOCKED -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = "Device screen is locked. Unlock the phone before running '$actionName'.",
                verificationDetail = perm.accessibilityReason,
                errorCode = "DEVICE_LOCKED",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED,
                failureCategory = ComputerUseFailureCategory.APPLOCK_OR_AUTH_REQUIRED
            )
            AccessibilityRuntimeState.TEMPORARILY_UNAVAILABLE -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = perm.accessibilityReason,
                verificationDetail = perm.accessibilityReason,
                errorCode = "TEMPORARILY_UNAVAILABLE",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
            AccessibilityRuntimeState.UNSUPPORTED -> ComputerUseActionOutcome(
                success = false,
                action = actionName,
                summary = perm.accessibilityReason,
                verificationDetail = perm.accessibilityReason,
                errorCode = "UNSUPPORTED",
                verificationLevel = VerificationLevel.LEVEL_0_DISPATCHED
            )
        }
    }

    fun formatOutcomeJson(outcome: ComputerUseActionOutcome): JSONObject {
        val obj = JSONObject()
            .put("success", outcome.success)
            .put("action", outcome.action)
            .put("summary", outcome.summary)
            .put("verificationDetail", outcome.verificationDetail)
            .put("verificationLevel", outcome.verificationLevel.levelNumber)
            .put("verificationLabel", outcome.verificationLevel.label)
            .put("screenChanged", outcome.screenChanged)
        outcome.beforePackage?.let { obj.put("beforePackage", it) }
        outcome.afterPackage?.let { obj.put("afterPackage", it) }
        outcome.errorCode?.let { obj.put("errorCode", it) }
        outcome.failureCategory?.let { obj.put("failureCategory", it.name) }
        outcome.suggestedRecovery?.let { obj.put("suggestedRecovery", it) }
        outcome.recoveryTried?.let { obj.put("recoveryTried", it) }
        outcome.groundingExplanation?.let { g ->
            obj.put(
                "grounding",
                JSONObject()
                    .put("tier", g.level.tier)
                    .put("method", g.level.name)
                    .put("confidence", g.confidence.toDouble())
                    .put("rationale", g.rationale)
            )
        }
        if (outcome.ambiguousCandidates.isNotEmpty()) {
            obj.put("status", "UI_AMBIGUOUS")
            val candArr = JSONArray()
            outcome.ambiguousCandidates.forEach { c ->
                candArr.put(
                    JSONObject()
                        .put("node_index", c.index)
                        .put("role", c.role)
                        .put("label", c.text ?: c.contentDescription ?: c.hintText ?: c.viewId ?: "${c.role}[${c.index}]")
                        .put("bounds", c.bounds.toCompactString())
                )
            }
            obj.put("candidates", candArr)
        }
        if (outcome.requiresAccessibilityPermission) {
            obj.put("permissionRequired", "ACCESSIBILITY_SERVICE")
            obj.put("howToEnable", "Open Android Settings -> Accessibility -> Nexa Computer-Use Service and turn it on.")
        }
        outcome.postObservation?.let { post ->
            obj.put("screen", screenObserver.toCompactJson(post))
        }
        return obj
    }

    fun formatLaunchOutcomeJson(outcome: AppLaunchOutcome): JSONObject {
        val obj = JSONObject()
            .put("status", outcome.launchStatus.name)
            .put("success", outcome.success)
            .put("target_package", outcome.packageName)
            .put("packageName", outcome.packageName)
            .put("foreground_package", outcome.observedForegroundPackage ?: JSONObject.NULL)
            .put("launchIntentDispatched", outcome.launchIntentDispatched)
            .put("foregroundVerified", outcome.foregroundVerified)
            .put("requires_user_action", outcome.requiresUserAction)
            .put("message", outcome.message)
        outcome.appName?.let { obj.put("appName", it) }
        outcome.observedForegroundPackage?.let { obj.put("observedForegroundPackage", it) }
        outcome.errorCode?.let { obj.put("errorCode", it) }
        outcome.blockerReason?.let { obj.put("reason", it) }
        outcome.recoveryAction?.let { obj.put("recovery_action", it) }
        outcome.userTitle?.let { obj.put("user_title", it) }
        outcome.userMessage?.let { obj.put("user_message", it) }
        if (outcome.ambiguousCandidates.isNotEmpty()) {
            val arr = JSONArray()
            outcome.ambiguousCandidates.forEach {
                arr.put(
                    JSONObject()
                        .put("display_name", it.appName)
                        .put("package_name", it.packageName)
                        .put("launchable", it.isLaunchable)
                )
            }
            obj.put("candidates", arr)
        }
        return obj
    }
}
