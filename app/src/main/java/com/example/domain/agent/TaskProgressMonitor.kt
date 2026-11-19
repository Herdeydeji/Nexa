package com.example.domain.agent

import com.example.domain.model.ToolCall
import com.example.domain.model.ToolCallResult
import com.example.domain.model.ToolExecutionStatus
import org.json.JSONObject

data class TaskProgressSnapshot(
    val currentObjective: String,
    val currentStep: String,
    val lastSuccessfulAction: String? = null,
    val currentForegroundPackage: String? = null,
    val currentScreenSignature: String? = null,
    val lastObservationTimestamp: Long = 0L,
    val consecutiveRepeatedActions: Int = 0,
    val consecutiveUnchangedObservations: Int = 0,
    val noProgressCycleCount: Int = 0,
    val timeSinceMeaningfulProgressMillis: Long = 0L
)

sealed interface ProgressCheckOutcome {
    data class Progressed(val snapshot: TaskProgressSnapshot) : ProgressCheckOutcome
    data class NeutralWait(val snapshot: TaskProgressSnapshot) : ProgressCheckOutcome
    data class NoProgressCycle(
        val reason: String,
        val snapshot: TaskProgressSnapshot
    ) : ProgressCheckOutcome
    data class HaltNoProgress(
        val reason: String,
        val blockerClassification: String,
        val snapshot: TaskProgressSnapshot
    ) : ProgressCheckOutcome
}

/**
 * Tracks concrete state transitions across an autonomous run and halts
 * no-progress retry loops (e.g., repeated observe_screen / wait / tap with zero screen change).
 */
class TaskProgressMonitor(
    private val objective: String,
    private val noProgressLimit: Int = 3,
    private val timeProvider: () -> Long = { System.currentTimeMillis() }
) {
    private var currentStep: String = "Starting task"
    private var lastSuccessfulAction: String? = null
    private var currentForegroundPackage: String? = null
    private var currentScreenSignature: String? = null
    private var lastObservationTimestamp: Long = 0L
    private var lastMeaningfulProgressAt: Long = timeProvider()
    private var lastCallSignature: String? = null
    private var consecutiveRepeatedActions: Int = 0
    private var consecutiveUnchangedObservations: Int = 0
    private var noProgressCycleCount: Int = 0

    fun recordToolResult(call: ToolCall, result: ToolCallResult): ProgressCheckOutcome {
        val now = timeProvider()
        currentStep = "${call.toolName}: ${result.displaySummary}".take(120)
        val parsed = runCatching { result.outputJson?.let { JSONObject(it) } }.getOrNull()
        val callSig = "${call.toolName}:${canonicalArgs(call.argumentsJson)}"

        val statusTag = parsed?.optString("status").orEmpty()
        val errorCode = parsed?.optString("errorCode").orEmpty()
        val failureCategory = parsed?.optString("failureCategory").orEmpty()
        val verificationLevel = if (parsed != null && parsed.has("verificationLevel")) {
            parsed.optInt("verificationLevel", -1)
        } else {
            -1
        }

        // Explicit user-action waits (AppLock or app/UI ambiguity prompt) are blocking pauses, not blind loops.
        if (statusTag == "BLOCKED_BY_APPLOCK" ||
            errorCode == "BLOCKED_BY_APPLOCK" ||
            statusTag == "AMBIGUOUS" ||
            statusTag == "UI_AMBIGUOUS" ||
            errorCode == "APP_AMBIGUOUS" ||
            errorCode == "UI_AMBIGUOUS"
        ) {
            lastMeaningfulProgressAt = now
            return ProgressCheckOutcome.NeutralWait(snapshot(now))
        }

        val observedFg = parsed?.let {
            it.optString("observed_foreground_package").ifBlank {
                it.optString("observedForegroundPackage").ifBlank {
                    it.optString("foreground_package").ifBlank {
                        it.optString("afterPackage").ifBlank {
                            it.optString("activePackageName").ifBlank {
                                it.optJSONObject("screen")?.optString("activePackageName")?.ifBlank { null }
                            }
                        }
                    }
                }
            }
        }
        val observedScreenSig = parsed?.let { buildScreenSignature(call.toolName, it) }

        val foregroundChanged = observedFg != null && observedFg != currentForegroundPackage
        val screenChangedFlag = parsed?.let {
            if (it.has("screenChanged")) it.optBoolean("screenChanged")
            else if (it.has("changed")) it.optBoolean("changed")
            else null
        }
        val screenSignatureChanged = observedScreenSig != null && observedScreenSig != currentScreenSignature

        if (observedFg != null) {
            currentForegroundPackage = observedFg
        }
        if (observedScreenSig != null) {
            currentScreenSignature = observedScreenSig
            lastObservationTimestamp = now
        }

        val isObservationTool = call.toolName in setOf(
            "observe_screen",
            "wait_for_screen_change",
            "wait_for_element"
        )
        val isLookupTool = call.toolName == "find_app" || call.toolName == "list_installed_apps"

        val repeatedSameCall = (callSig == lastCallSignature)
        lastCallSignature = callSig
        consecutiveRepeatedActions = if (repeatedSameCall) consecutiveRepeatedActions + 1 else 0

        val waitElementFound = call.toolName == "wait_for_element" && parsed?.optBoolean("found", false) == true
        val level0NoEffect = verificationLevel == 0 || errorCode == "NO_UI_EFFECT"

        val meaningfulProgress = when {
            result.status != ToolExecutionStatus.SUCCEEDED -> false
            waitElementFound -> true
            foregroundChanged || screenSignatureChanged -> true
            screenChangedFlag == true -> true
            verificationLevel >= 1 -> true
            call.toolName == "play_on_youtube" && statusTag == "PLAYBACK_STARTED" -> true
            call.toolName == "launch_app" && (statusTag == "FOREGROUND_VERIFIED" || statusTag == "LAUNCH_DISPATCHED") -> true
            call.toolName == "open_url" && (statusTag == "FOREGROUND_VERIFIED" || statusTag == "LAUNCH_DISPATCHED") -> true
            !isObservationTool && !isLookupTool && !level0NoEffect && screenChangedFlag != false -> true
            else -> false
        }

        if (meaningfulProgress) {
            lastSuccessfulAction = currentStep
            lastMeaningfulProgressAt = now
            consecutiveUnchangedObservations = 0
            noProgressCycleCount = 0
            return ProgressCheckOutcome.Progressed(snapshot(now))
        }

        if (isObservationTool && !foregroundChanged && !screenSignatureChanged && !waitElementFound) {
            consecutiveUnchangedObservations += 1
        } else if (screenChangedFlag == false || level0NoEffect) {
            consecutiveUnchangedObservations += 1
        }

        val noStateChange = !foregroundChanged &&
            !screenSignatureChanged &&
            (screenChangedFlag == false || level0NoEffect || repeatedSameCall || isObservationTool || result.status != ToolExecutionStatus.SUCCEEDED)

        if (noStateChange) {
            noProgressCycleCount += 1
        }

        val limit = noProgressLimit.coerceAtLeast(2)
        val snap = snapshot(now)
        val reason = buildString {
            append("No measurable screen or goal progress across $limit cycles ")
            append("(last step: ${call.toolName}")
            currentForegroundPackage?.let { append(", foreground: $it") }
            append(").")
        }

        if (noProgressCycleCount >= limit ||
            consecutiveUnchangedObservations >= limit ||
            consecutiveRepeatedActions >= limit
        ) {
            val blocker = classifyBlocker(parsed, statusTag, failureCategory, currentForegroundPackage)
            return ProgressCheckOutcome.HaltNoProgress(
                reason = reason,
                blockerClassification = blocker,
                snapshot = snap
            )
        }

        return ProgressCheckOutcome.NoProgressCycle(
            reason = reason,
            snapshot = snap
        )
    }

    fun snapshot(now: Long = timeProvider()): TaskProgressSnapshot = TaskProgressSnapshot(
        currentObjective = objective,
        currentStep = currentStep,
        lastSuccessfulAction = lastSuccessfulAction,
        currentForegroundPackage = currentForegroundPackage,
        currentScreenSignature = currentScreenSignature,
        lastObservationTimestamp = lastObservationTimestamp,
        consecutiveRepeatedActions = consecutiveRepeatedActions,
        consecutiveUnchangedObservations = consecutiveUnchangedObservations,
        noProgressCycleCount = noProgressCycleCount,
        timeSinceMeaningfulProgressMillis = (now - lastMeaningfulProgressAt).coerceAtLeast(0L)
    )

    private fun buildScreenSignature(toolName: String, obj: JSONObject): String? {
        val explicitSig = obj.optString("hierarchySignature").ifBlank { null }
        if (explicitSig != null) return explicitSig
        val screenObj = obj.optJSONObject("screen") ?: obj.optJSONObject("postObservation")
        if (screenObj != null) {
            val sig = screenObj.optString("hierarchySignature").ifBlank { null }
            if (sig != null) return sig
            val pkg = screenObj.optString("activePackageName")
            val nodes = screenObj.optJSONArray("nodes")?.length() ?: screenObj.optInt("visibleNodeCount", 0)
            if (pkg.isNotBlank() || nodes > 0) return "$pkg|$nodes|${screenObj.optString("windowTitle")}"
        }
        val pkg = obj.optString("activePackageName").ifBlank { obj.optString("observed_foreground_package") }
        val nodes = obj.optJSONArray("nodes")?.length() ?: obj.optInt("nodeCount", -1)
        val title = obj.optString("windowTitle")
        if (pkg.isBlank() && nodes < 0 && title.isBlank()) return null
        if (toolName == "wait_for_screen_change" && !obj.optBoolean("changed", true)) {
            return currentScreenSignature
        }
        return "$pkg|$title|$nodes"
    }

    private fun classifyBlocker(
        parsed: JSONObject?,
        statusTag: String,
        failureCategory: String,
        fgPkg: String?
    ): String = when {
        failureCategory.isNotBlank() -> failureCategory
        statusTag == "BLOCKED_BY_APPLOCK" || fgPkg == "com.transsion.applock" -> "BLOCKED_BY_APPLOCK"
        statusTag == "BLOCKED_BY_DIALOG" -> "BLOCKED_BY_DIALOG"
        statusTag == "PLAYBACK_UNVERIFIED" -> "PLAYBACK_UNVERIFIED"
        parsed?.optString("errorCode")?.isNotBlank() == true -> parsed.optString("errorCode")
        else -> "UNCHANGED_SCREEN_STATE"
    }

    private fun canonicalArgs(raw: String): String {
        val obj = runCatching { JSONObject(raw.trim().ifEmpty { "{}" }) }.getOrNull() ?: return raw.trim()
        val sorted = JSONObject()
        obj.keys().asSequence().sorted().forEach { k -> sorted.put(k, obj.opt(k)) }
        return sorted.toString()
    }
}
