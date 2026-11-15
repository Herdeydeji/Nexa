package com.example.domain.agent

import com.example.domain.model.AgentEvent
import com.example.domain.model.AgentRunStatus
import com.example.domain.model.ApprovalDecision
import com.example.domain.model.ExecutionStep
import com.example.domain.model.TaskVerificationSummary
import com.example.domain.model.ToolApprovalRequest
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
}

class AgentRunController {

    private val _state = MutableStateFlow(ActiveAgentRunState())
    val state: StateFlow<ActiveAgentRunState> = _state.asStateFlow()

    private val pausedFlow = MutableStateFlow(false)
    private var pendingApprovalDeferred: Pair<String, CompletableDeferred<ApprovalDecision>>? = null
    private var activeJob: Job? = null
    private val cancelledByUser = AtomicBoolean(false)

    fun beginRun(taskId: String, conversationId: String, job: Job? = null): AgentControlHandle {
        activeJob = job
        cancelledByUser.set(false)
        pausedFlow.value = false
        pendingApprovalDeferred?.second?.cancel(CancellationException("Superseded by new run"))
        pendingApprovalDeferred = null
        _state.value = ActiveAgentRunState(
            activeTaskId = taskId,
            activeConversationId = conversationId,
            runStatus = AgentRunStatus.PREPARING,
            statusDetail = "Preparing assistant turn...",
            streamingText = "",
            liveSteps = emptyList(),
            pendingApproval = null,
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
                    statusDetail = event.message
                )
                is AgentEvent.TextDelta -> current.copy(streamingText = event.accumulatedText)
                is AgentEvent.ToolSelected -> current.copy(
                    runStatus = AgentRunStatus.SELECTING_TOOL,
                    statusDetail = "Selected ${event.toolCalls.joinToString { it.toolName }}"
                )
                is AgentEvent.ApprovalRequired -> current.copy(
                    runStatus = AgentRunStatus.AWAITING_APPROVAL,
                    statusDetail = "Waiting for approval: ${event.request.toolName}",
                    pendingApproval = event.request
                )
                is AgentEvent.ToolStarted -> current.copy(
                    runStatus = AgentRunStatus.EXECUTING_TOOL,
                    statusDetail = "Executing ${event.call.toolName}...",
                    pendingApproval = null
                )
                is AgentEvent.ToolCompleted -> current.copy(
                    statusDetail = "${event.result.toolName}: ${event.result.displaySummary}",
                    pendingApproval = null
                )
                is AgentEvent.PlanUpdated -> current.copy(liveSteps = event.plan.steps)
                is AgentEvent.Paused -> current.copy(
                    isPaused = true,
                    runStatus = AgentRunStatus.PAUSED,
                    statusDetail = event.reason
                )
                is AgentEvent.Resumed -> current.copy(
                    isPaused = false,
                    statusDetail = "Resuming execution..."
                )
                is AgentEvent.IntermediateMessagesProduced -> current
                is AgentEvent.Completed -> current.copy(
                    runStatus = AgentRunStatus.COMPLETED,
                    statusDetail = "Completed",
                    liveSteps = event.plan?.steps ?: current.liveSteps,
                    pendingApproval = null,
                    isPaused = false,
                    verificationSummary = event.verification
                )
                is AgentEvent.Failed -> current.copy(
                    runStatus = AgentRunStatus.FAILED,
                    statusDetail = event.errorMessage,
                    liveSteps = event.plan?.steps ?: current.liveSteps,
                    pendingApproval = null,
                    isPaused = false,
                    verificationSummary = event.verification
                )
                is AgentEvent.Cancelled -> current.copy(
                    runStatus = AgentRunStatus.CANCELLED,
                    statusDetail = "Cancelled by user",
                    liveSteps = event.plan?.steps ?: current.liveSteps,
                    pendingApproval = null,
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
            val nextStatus = if (it.pendingApproval != null) {
                AgentRunStatus.AWAITING_APPROVAL
            } else if (it.isRunning || it.runStatus == AgentRunStatus.PAUSED) {
                AgentRunStatus.REPLANNING
            } else {
                it.runStatus
            }
            it.copy(
                isPaused = false,
                runStatus = nextStatus,
                statusDetail = if (it.pendingApproval != null) {
                    "Waiting for approval: ${it.pendingApproval.toolName}"
                } else {
                    "Resuming..."
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

    fun cancelActiveRun(): Boolean {
        val hadActive = activeJob?.isActive == true || _state.value.isRunning || pendingApprovalDeferred != null
        if (!hadActive) return false
        cancelledByUser.set(true)
        pausedFlow.value = false
        pendingApprovalDeferred?.second?.cancel(CancellationException("Task cancelled by user"))
        pendingApprovalDeferred = null
        activeJob?.cancel(CancellationException("Task cancelled by user"))
        activeJob = null
        _state.update {
            it.copy(
                runStatus = AgentRunStatus.CANCELLED,
                statusDetail = "Cancelled by user",
                pendingApproval = null,
                isPaused = false
            )
        }
        return true
    }

    fun wasCancelledByUser(): Boolean = cancelledByUser.get()

    fun finishRun() {
        activeJob = null
        pausedFlow.value = false
        pendingApprovalDeferred = null
        _state.value = ActiveAgentRunState()
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
    }
}
