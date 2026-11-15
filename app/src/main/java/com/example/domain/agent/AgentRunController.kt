package com.example.domain.agent

import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.AmbiguitySelectionRequest
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.ExecutionStep
import com.example.domain.model.TaskVerificationSummary
import com.example.domain.model.ToolApprovalRequest
import com.example.domain.model.UserUnlockWaitRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

data class ActiveAgentRunState(
    val activeTaskId: String? = null,
    val activeConversationId: String? = null,
    val runStatus: AgentRunStatus = AgentRunStatus.IDLE,
    val statusDetail: String = "",
    val streamingText: String = "",
    val liveSteps: List<ExecutionStep> = emptyList(),
    val pendingApproval: ToolApprovalRequest? = null,
    val waitingForUnlock: UserUnlockWaitRequest? = null,
    val pendingAmbiguity: AmbiguitySelectionRequest? = null,
    val currentTargetAppName: String? = null,
    val currentTargetPackage: String? = null,
    val currentForegroundPackage: String? = null,
    val completedStepCount: Int = 0,
    val totalStepCount: Int = 0,
    val isPaused: Boolean = false,
    val verificationSummary: TaskVerificationSummary? = null
) {
    val isRunning: Boolean
        get() = activeTaskId != null && runStatus !in setOf(
            AgentRunStatus.IDLE,
            AgentRunStatus.COMPLETED,
            AgentRunStatus.FAILED,
            AgentRunStatus.CANCELLED
        )
}

interface AgentControlHandle {
    fun isPausedNow(): Boolean
    suspend fun awaitIfPaused()
    suspend fun requestApproval(request: ToolApprovalRequest): ApprovalDecision
    suspend fun awaitUserUnlockManualContinue(
        targetAppName: String,
        targetPackage: String,
        reason: String
    ): Boolean = false
    suspend fun requestAmbiguityChoice(request: AmbiguitySelectionRequest): String? = null
}

class AgentRunController {

    private val _state = MutableStateFlow(ActiveAgentRunState())
    val state: StateFlow<ActiveAgentRunState> = _state.asStateFlow()

    private val pausedFlow = MutableStateFlow(false)
    private var pendingApprovalDeferred: Pair<String, CompletableDeferred<ApprovalDecision>>? = null
    private var pendingUnlockContinueDeferred: Pair<String, CompletableDeferred<Boolean>>? = null
    private var pendingAmbiguityDeferred: Pair<String, CompletableDeferred<String?>>? = null
    private var activeJob: Job? = null
    private val cancelledByUser = AtomicBoolean(false)

    fun beginRun(taskId: String, conversationId: String, job: Job? = null): AgentControlHandle {
        activeJob = job
        cancelledByUser.set(false)
        pausedFlow.value = false
        clearPendingDeferreds("Superseded by new run")
        _state.value = ActiveAgentRunState(
            activeTaskId = taskId,
            activeConversationId = conversationId,
            runStatus = AgentRunStatus.PREPARING,
            statusDetail = "Preparing assistant turn...",
            streamingText = "",
            liveSteps = emptyList(),
            pendingApproval = null,
            waitingForUnlock = null,
            pendingAmbiguity = null,
            isPaused = false,
            verificationSummary = null
        )
        return ControllerHandle(taskId)
    }

    fun attachJob(job: Job) {
        activeJob = job
    }

    fun applyEvent(event: AgentEvent) {
        _state.update { current ->
            if (current.activeTaskId != event.taskId) return@update current
            when (event) {
                is AgentEvent.StatusChanged -> current.copy(
                    runStatus = if (current.isPaused && event.status != AgentRunStatus.CANCELLED) {
                        AgentRunStatus.PAUSED
                    } else {
                        event.status
                    },
                    statusDetail = event.message,
                    waitingForUnlock = if (event.status == AgentRunStatus.WAITING_FOR_USER_UNLOCK) {
                        current.waitingForUnlock
                    } else {
                        null
                    },
                    pendingAmbiguity = if (event.status == AgentRunStatus.WAITING_FOR_AMBIGUITY_CHOICE) {
                        current.pendingAmbiguity
                    } else {
                        null
                    }
                )
                is AgentEvent.TextDelta -> current.copy(streamingText = event.accumulatedText)
                is AgentEvent.ToolSelected -> current.copy(
                    runStatus = AgentRunStatus.SELECTING_TOOL,
                    statusDetail = "Selected ${event.toolCalls.joinToString { it.toolName }}",
                    waitingForUnlock = null,
                    pendingAmbiguity = null
                )
                is AgentEvent.ApprovalRequired -> current.copy(
                    runStatus = AgentRunStatus.AWAITING_APPROVAL,
                    statusDetail = "Waiting for approval: ${event.request.toolName}",
                    pendingApproval = event.request
                )
                is AgentEvent.ToolStarted -> current.copy(
                    runStatus = AgentRunStatus.EXECUTING_TOOL,
                    statusDetail = "Executing ${event.call.toolName}...",
                    pendingApproval = null,
                    waitingForUnlock = null,
                    pendingAmbiguity = null
                )
                is AgentEvent.ToolCompleted -> {
                    val parsed = runCatching {
                        event.result.outputJson?.let { org.json.JSONObject(it) }
                    }.getOrNull()
                    val targetPkg = parsed?.optString("target_package")?.ifBlank { null }
                        ?: parsed?.optString("packageName")?.ifBlank { null }
                        ?: current.currentTargetPackage
                    val appName = parsed?.optString("display_name")?.ifBlank { null }
                        ?: parsed?.optString("appName")?.ifBlank { null }
                        ?: current.currentTargetAppName
                    val fgPkg = parsed?.optString("observed_foreground_package")?.ifBlank { null }
                        ?: parsed?.optString("foreground_package")?.ifBlank { null }
                        ?: parsed?.optString("observedForegroundPackage")?.ifBlank { null }
                        ?: current.currentForegroundPackage
                    current.copy(
                        statusDetail = "${event.result.toolName}: ${event.result.displaySummary}",
                        pendingApproval = null,
                        currentTargetAppName = appName,
                        currentTargetPackage = targetPkg,
                        currentForegroundPackage = fgPkg
                    )
                }
                is AgentEvent.PlanUpdated -> {
                    val steps = event.plan.steps
                    val completedCount = steps.count {
                        it.state == com.example.domain.model.ExecutionStepState.COMPLETED
                    }
                    current.copy(
                        liveSteps = steps,
                        completedStepCount = completedCount,
                        totalStepCount = steps.size
                    )
                }
                is AgentEvent.Paused -> current.copy(
                    isPaused = true,
                    runStatus = AgentRunStatus.PAUSED,
                    statusDetail = event.reason
                )
                is AgentEvent.Resumed -> current.copy(
                    isPaused = false,
                    waitingForUnlock = null,
                    pendingAmbiguity = null,
                    statusDetail = "Resuming execution..."
                )
                is AgentEvent.WaitingForUserUnlock -> current.copy(
                    runStatus = AgentRunStatus.WAITING_FOR_USER_UNLOCK,
                    statusDetail = event.request.message,
                    waitingForUnlock = event.request,
                    currentTargetAppName = event.request.targetAppName,
                    currentTargetPackage = event.request.targetPackage,
                    currentForegroundPackage = event.request.foregroundPackage
                )
                is AgentEvent.UserUnlockTimeoutManualContinue -> current.copy(
                    runStatus = AgentRunStatus.WAITING_FOR_USER_UNLOCK,
                    statusDetail = event.reason,
                    waitingForUnlock = (current.waitingForUnlock ?: UserUnlockWaitRequest(
                        taskId = event.taskId,
                        targetAppName = event.targetAppName,
                        targetPackage = event.targetPackage,
                        foregroundPackage = "com.transsion.applock",
                        title = "Unlock ${event.targetAppName}",
                        message = event.reason
                    )).copy(
                        remainingSeconds = 0,
                        manualContinueRequired = true,
                        message = event.reason
                    ),
                    currentTargetAppName = event.targetAppName,
                    currentTargetPackage = event.targetPackage
                )
                is AgentEvent.AmbiguityChoiceRequired -> current.copy(
                    runStatus = AgentRunStatus.WAITING_FOR_AMBIGUITY_CHOICE,
                    statusDetail = event.request.reason,
                    pendingAmbiguity = event.request
                )
                is AgentEvent.IntermediateMessagesProduced -> current
                is AgentEvent.Completed -> current.copy(
                    runStatus = AgentRunStatus.COMPLETED,
                    statusDetail = if (event.partiallyCompleted) "Partially completed" else "Completed",
                    liveSteps = event.plan?.steps ?: current.liveSteps,
                    pendingApproval = null,
                    waitingForUnlock = null,
                    pendingAmbiguity = null,
                    isPaused = false,
                    verificationSummary = event.verification
                )
                is AgentEvent.Failed -> current.copy(
                    runStatus = AgentRunStatus.FAILED,
                    statusDetail = event.errorMessage,
                    liveSteps = event.plan?.steps ?: current.liveSteps,
                    pendingApproval = null,
                    waitingForUnlock = null,
                    pendingAmbiguity = null,
                    isPaused = false,
                    verificationSummary = event.verification
                )
                is AgentEvent.Cancelled -> current.copy(
                    runStatus = AgentRunStatus.CANCELLED,
                    statusDetail = "Cancelled by user",
                    liveSteps = event.plan?.steps ?: current.liveSteps,
                    pendingApproval = null,
                    waitingForUnlock = null,
                    pendingAmbiguity = null,
                    isPaused = false,
                    verificationSummary = event.verification
                )
            }
        }
    }

    fun pause() {
        if (!_state.value.isRunning) return
        pausedFlow.value = true
        _state.update {
            it.copy(
                isPaused = true,
                runStatus = AgentRunStatus.PAUSED,
                statusDetail = "Paused by user. Tap Resume to continue."
            )
        }
    }

    fun resume() {
        pausedFlow.value = false
        _state.update {
            val nextStatus = when {
                it.pendingApproval != null -> AgentRunStatus.AWAITING_APPROVAL
                it.waitingForUnlock != null -> AgentRunStatus.WAITING_FOR_USER_UNLOCK
                it.pendingAmbiguity != null -> AgentRunStatus.WAITING_FOR_AMBIGUITY_CHOICE
                it.isRunning || it.runStatus == AgentRunStatus.PAUSED -> AgentRunStatus.REPLANNING
                else -> it.runStatus
            }
            it.copy(
                isPaused = false,
                runStatus = nextStatus,
                statusDetail = when {
                    it.pendingApproval != null -> "Waiting for approval: ${it.pendingApproval.toolName}"
                    it.waitingForUnlock != null -> it.waitingForUnlock.message
                    else -> "Resuming..."
                }
            )
        }
    }

    fun submitApproval(callId: String, decision: ApprovalDecision) {
        val pending = pendingApprovalDeferred
        if (pending != null && pending.first == callId) {
            pending.second.complete(decision)
            pendingApprovalDeferred = null
        }
        _state.update { current ->
            if (current.pendingApproval?.callId == callId) {
                current.copy(pendingApproval = null)
            } else {
                current
            }
        }
    }

    fun submitUnlockContinue(): Boolean {
        val pending = pendingUnlockContinueDeferred
        if (pending != null) {
            pending.second.complete(true)
            pendingUnlockContinueDeferred = null
            _state.update {
                it.copy(
                    waitingForUnlock = null,
                    runStatus = AgentRunStatus.REPLANNING,
                    statusDetail = "Checking unlocked app state..."
                )
            }
            return true
        }
        return false
    }

    fun submitAmbiguityChoice(selectionId: String, selectedPackageName: String?): Boolean {
        val pending = pendingAmbiguityDeferred
        if (pending != null && pending.first == selectionId) {
            pending.second.complete(selectedPackageName)
            pendingAmbiguityDeferred = null
            _state.update { current ->
                current.copy(
                    pendingAmbiguity = null,
                    runStatus = AgentRunStatus.REPLANNING,
                    statusDetail = if (selectedPackageName != null) {
                        "Selected $selectedPackageName"
                    } else {
                        "Selection cancelled"
                    }
                )
            }
            return true
        }
        return false
    }

    fun cancelActiveRun(): Boolean {
        val hadActive = activeJob?.isActive == true ||
            _state.value.isRunning ||
            pendingApprovalDeferred != null ||
            pendingUnlockContinueDeferred != null ||
            pendingAmbiguityDeferred != null
        if (!hadActive) return false
        cancelledByUser.set(true)
        pausedFlow.value = false
        clearPendingDeferreds("Task cancelled by user")
        activeJob?.cancel(CancellationException("Task cancelled by user"))
        activeJob = null
        _state.update {
            it.copy(
                runStatus = AgentRunStatus.CANCELLED,
                statusDetail = "Cancelled by user",
                pendingApproval = null,
                waitingForUnlock = null,
                pendingAmbiguity = null,
                isPaused = false
            )
        }
        return true
    }

    fun wasCancelledByUser(): Boolean = cancelledByUser.get()

    fun finishRun() {
        activeJob = null
        pausedFlow.value = false
        clearPendingDeferreds("Run finished")
        _state.value = ActiveAgentRunState()
    }

    private fun clearPendingDeferreds(reason: String) {
        val ce = CancellationException(reason)
        pendingApprovalDeferred?.second?.cancel(ce)
        pendingApprovalDeferred = null
        pendingUnlockContinueDeferred?.second?.cancel(ce)
        pendingUnlockContinueDeferred = null
        pendingAmbiguityDeferred?.second?.cancel(ce)
        pendingAmbiguityDeferred = null
    }

    private inner class ControllerHandle(private val taskId: String) : AgentControlHandle {
        override fun isPausedNow(): Boolean = pausedFlow.value

        override suspend fun awaitIfPaused() {
            if (pausedFlow.value) {
                pausedFlow.filter { !it }.first()
            }
        }

        override suspend fun requestApproval(request: ToolApprovalRequest): ApprovalDecision {
            val deferred = CompletableDeferred<ApprovalDecision>()
            pendingApprovalDeferred = request.callId to deferred
            _state.update {
                if (it.activeTaskId == taskId) {
                    it.copy(
                        pendingApproval = request,
                        runStatus = AgentRunStatus.AWAITING_APPROVAL,
                        statusDetail = "Waiting for approval: ${request.toolName}"
                    )
                } else {
                    it
                }
            }
            return try {
                deferred.await()
            } finally {
                if (pendingApprovalDeferred?.first == request.callId) {
                    pendingApprovalDeferred = null
                }
            }
        }

        override suspend fun awaitUserUnlockManualContinue(
            targetAppName: String,
            targetPackage: String,
            reason: String
        ): Boolean {
            val deferred = CompletableDeferred<Boolean>()
            pendingUnlockContinueDeferred = targetPackage to deferred
            _state.update { current ->
                if (current.activeTaskId == taskId) {
                    val updatedWait = (current.waitingForUnlock ?: UserUnlockWaitRequest(
                        taskId = taskId,
                        targetAppName = targetAppName,
                        targetPackage = targetPackage,
                        foregroundPackage = "com.transsion.applock",
                        title = "Unlock $targetAppName",
                        message = reason
                    )).copy(
                        remainingSeconds = 0,
                        manualContinueRequired = true,
                        message = reason
                    )
                    current.copy(
                        runStatus = AgentRunStatus.WAITING_FOR_USER_UNLOCK,
                        statusDetail = reason,
                        waitingForUnlock = updatedWait,
                        currentTargetAppName = targetAppName,
                        currentTargetPackage = targetPackage
                    )
                } else {
                    current
                }
            }
            return try {
                deferred.await()
            } catch (_: CancellationException) {
                false
            } finally {
                if (pendingUnlockContinueDeferred?.first == targetPackage) {
                    pendingUnlockContinueDeferred = null
                }
            }
        }

        override suspend fun requestAmbiguityChoice(request: AmbiguitySelectionRequest): String? {
            val deferred = CompletableDeferred<String?>()
            pendingAmbiguityDeferred = request.selectionId to deferred
            _state.update {
                if (it.activeTaskId == taskId) {
                    it.copy(
                        pendingAmbiguity = request,
                        runStatus = AgentRunStatus.WAITING_FOR_AMBIGUITY_CHOICE,
                        statusDetail = request.reason
                    )
                } else {
                    it
                }
            }
            return try {
                deferred.await()
            } catch (_: CancellationException) {
                null
            } finally {
                if (pendingAmbiguityDeferred?.first == request.selectionId) {
                    pendingAmbiguityDeferred = null
                }
            }
        }
    }
}
